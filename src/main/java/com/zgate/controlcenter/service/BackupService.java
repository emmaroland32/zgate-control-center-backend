package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.BackupPlan;
import com.zgate.controlcenter.domain.BackupRecord;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.BackupPlanRepository;
import com.zgate.controlcenter.repository.BackupRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Managed-backup orchestration: entitlement + quota gating, backup lifecycle (initiate → complete /
 * fail), restore, retention/quota eviction, and usage metering for billing. The bytes never pass
 * through here — {@link BackupStorageService} hands the org instance a presigned S3 URL.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {

    private static final long GIB = 1024L * 1024 * 1024;

    private final BackupPlanRepository planRepo;
    private final BackupRecordRepository recordRepo;
    private final BackupStorageService storage;

    // ---- Records the API exchanges ----

    public record InitiateResult(UUID backupId, String uploadUrl,
                                 Map<String, String> requiredHeaders, LocalDateTime expiresAt) {}

    public record OrgBackupUsage(long usedBytes, long quotaBytes, long completedCount,
                                 boolean active, BigDecimal estimatedMonthlyCharge, String currency) {}

    public record FleetBackupStats(long plans, long activePlans, long backups, long completed,
                                   long failed, long totalStoredBytes) {}

    // ---- Agent (M2M) flow ----

    /**
     * Gate on the org's plan + quota, create an INITIATED record, and return a presigned upload URL.
     * The agent uploads the already-encrypted ciphertext directly to S3, then calls {@link #complete}.
     */
    @Transactional
    public InitiateResult initiate(UUID orgId, Long sizeBytes, String nodeId, String label) {
        if (!storage.isEnabled()) {
            throw new ControlCenterException(
                    "Managed backup storage is not configured on this Control Center.",
                    "BACKUP_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        }
        BackupPlan plan = planRepo.findByOrganizationId(orgId).orElseThrow(() -> new ControlCenterException(
                "This organization has no managed-backup subscription.",
                "BACKUP_NOT_ENTITLED", HttpStatus.FORBIDDEN));
        if (!plan.isActive()) {
            throw new ControlCenterException(
                    "The managed-backup subscription is disabled or past its paid-through date.",
                    "BACKUP_INACTIVE", HttpStatus.PAYMENT_REQUIRED);
        }

        long incoming = sizeBytes == null ? 0 : Math.max(0, sizeBytes);
        long used = recordRepo.sumCompletedSizeBytes(orgId);
        long quota = (long) plan.getStorageQuotaGb() * GIB;
        if (used + incoming > quota) {
            throw new ControlCenterException(
                    "Storage quota exceeded (" + plan.getStorageQuotaGb() + " GiB). Delete old backups or upgrade the plan.",
                    "BACKUP_QUOTA_EXCEEDED", HttpStatus.PAYMENT_REQUIRED);
        }
        if (plan.getMaxRetainedBackups() != null) {
            long completed = recordRepo.countByOrganizationIdAndStatus(orgId, BackupRecord.Status.COMPLETED);
            if (completed >= plan.getMaxRetainedBackups()) {
                throw new ControlCenterException(
                        "Retained-backup limit reached (" + plan.getMaxRetainedBackups() + "). Delete old backups first.",
                        "BACKUP_MAX_REACHED", HttpStatus.PAYMENT_REQUIRED);
            }
        }

        String s3Key = storage.objectKey(orgId, UUID.randomUUID());
        BackupRecord rec = recordRepo.save(BackupRecord.builder()
                .organizationId(orgId).nodeId(nodeId).label(label).s3Key(s3Key)
                .sizeBytes(incoming > 0 ? incoming : null)
                .clientEncrypted(true)
                .status(BackupRecord.Status.INITIATED)
                .build());

        BackupStorageService.PresignedUpload up = storage.presignedUpload(s3Key);
        log.info("Backup initiated org={} id={} node={} (~{} bytes)", orgId, rec.getId(), nodeId, incoming);
        return new InitiateResult(rec.getId(), up.url(), up.requiredHeaders(), up.expiresAt());
    }

    /** Confirm a successful upload: record size/checksum, stamp the retention window. */
    @Transactional
    public BackupRecord complete(UUID orgId, UUID backupId, String sha256, Long sizeBytes) {
        BackupRecord rec = ownedRecord(orgId, backupId);
        if (rec.getStatus() != BackupRecord.Status.INITIATED) {
            throw new ControlCenterException("Backup is not awaiting completion (" + rec.getStatus() + ").",
                    "BACKUP_BAD_STATE", HttpStatus.CONFLICT);
        }
        if (sizeBytes != null) rec.setSizeBytes(sizeBytes);
        if (sha256 != null && !sha256.isBlank()) rec.setSha256(sha256);
        rec.setStatus(BackupRecord.Status.COMPLETED);
        rec.setCompletedAt(LocalDateTime.now());
        int retentionDays = planRepo.findByOrganizationId(orgId).map(BackupPlan::getRetentionDays).orElse(30);
        rec.setExpiresAt(rec.getCompletedAt().plusDays(retentionDays));
        recordRepo.save(rec);
        log.info("Backup completed org={} id={} size={} bytes", orgId, backupId, rec.getSizeBytes());
        return rec;
    }

    /** Mark an in-flight backup failed and purge its partial object. */
    @Transactional
    public void fail(UUID orgId, UUID backupId, String reason) {
        BackupRecord rec = ownedRecord(orgId, backupId);
        // Only an in-flight upload may be failed+purged. Refusing any other state stops a caller from
        // destroying a COMPLETED (good) backup's object through the fail path.
        if (rec.getStatus() != BackupRecord.Status.INITIATED) {
            throw new ControlCenterException("Only an in-progress backup can be failed (" + rec.getStatus() + ").",
                    "BACKUP_BAD_STATE", HttpStatus.CONFLICT);
        }
        rec.setStatus(BackupRecord.Status.FAILED);
        rec.setFailureReason(reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
        recordRepo.save(rec);
        deleteAfterCommit(List.of(rec.getS3Key()));   // purge only if the FAILED flip commits
    }

    /** Agent reports whether a completed backup proved restorable (decrypt + pg_restore --list). */
    @Transactional
    public BackupRecord recordVerification(UUID orgId, UUID backupId, boolean verified, String error) {
        BackupRecord rec = ownedRecord(orgId, backupId);
        rec.setVerified(verified);
        rec.setVerifiedAt(LocalDateTime.now());
        rec.setVerifyError(verified ? null : (error != null && error.length() > 500 ? error.substring(0, 500) : error));
        recordRepo.save(rec);
        log.info("Backup {} verification: {}", backupId, verified ? "OK" : "FAILED (" + error + ")");
        return rec;
    }

    @Transactional(readOnly = true)
    public List<BackupRecord> listForOrg(UUID orgId) {
        return recordRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId);
    }

    /** Presigned download URL for a restore — only for a completed, unexpired backup. */
    @Transactional(readOnly = true)
    public String restoreUrl(UUID orgId, UUID backupId) {
        BackupRecord rec = ownedRecord(orgId, backupId);
        if (rec.getStatus() != BackupRecord.Status.COMPLETED) {
            throw new ControlCenterException("Backup is not restorable (" + rec.getStatus() + ").",
                    "BACKUP_NOT_RESTORABLE", HttpStatus.CONFLICT);
        }
        return storage.presignedDownload(rec.getS3Key());
    }

    // ---- Admin flow ----

    @Transactional(readOnly = true)
    public Page<BackupRecord> adminList(Pageable pageable) {
        return recordRepo.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<BackupRecord> adminListForOrg(UUID orgId) {
        return listForOrg(orgId);
    }

    @Transactional(readOnly = true)
    public BackupPlan getPlan(UUID orgId) {
        return planRepo.findByOrganizationId(orgId).orElse(null);
    }

    /** Create or update an org's backup plan (admin). */
    @Transactional
    public BackupPlan upsertPlan(UUID orgId, BackupPlan incoming) {
        BackupPlan plan = planRepo.findByOrganizationId(orgId).orElseGet(() -> {
            BackupPlan p = new BackupPlan();
            p.setOrganizationId(orgId);
            return p;
        });
        plan.setEnabled(incoming.isEnabled());
        plan.setStorageQuotaGb(Math.max(0, incoming.getStorageQuotaGb()));
        plan.setRetentionDays(Math.max(1, incoming.getRetentionDays()));
        plan.setMaxRetainedBackups(incoming.getMaxRetainedBackups());
        if (incoming.getPricePerMonth() != null) plan.setPricePerMonth(incoming.getPricePerMonth());
        if (incoming.getPricePerGbMonth() != null) plan.setPricePerGbMonth(incoming.getPricePerGbMonth());
        if (incoming.getCurrency() != null && !incoming.getCurrency().isBlank()) plan.setCurrency(incoming.getCurrency());
        plan.setSubscriptionValidUntil(incoming.getSubscriptionValidUntil());
        return planRepo.save(plan);
    }

    /** Current usage + the metered monthly charge (base + stored GiB × per-GiB rate). */
    @Transactional(readOnly = true)
    public OrgBackupUsage usageForOrg(UUID orgId) {
        BackupPlan plan = planRepo.findByOrganizationId(orgId).orElse(null);
        long used = recordRepo.sumCompletedSizeBytes(orgId);
        long completed = recordRepo.countByOrganizationIdAndStatus(orgId, BackupRecord.Status.COMPLETED);
        long quota = plan != null ? (long) plan.getStorageQuotaGb() * GIB : 0;
        BigDecimal charge = BigDecimal.ZERO;
        String currency = plan != null ? plan.getCurrency() : "USD";
        if (plan != null) {
            BigDecimal storedGib = BigDecimal.valueOf(used).divide(BigDecimal.valueOf(GIB), 4, RoundingMode.HALF_UP);
            charge = plan.getPricePerMonth().add(storedGib.multiply(plan.getPricePerGbMonth()))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return new OrgBackupUsage(used, quota, completed, plan != null && plan.isActive(), charge, currency);
    }

    /**
     * An invoice line item for the org's backup charge (base + stored GiB × rate), or null to skip.
     * Carries the plan {@code currency} so the caller can reconcile it against the invoice currency
     * before summing — the amount is meaningless without it.
     */
    public record BackupChargeLine(String description, long quantity, BigDecimal unitPrice,
                                   BigDecimal totalPrice, String currency) {}

    @Transactional(readOnly = true)
    public BackupChargeLine monthlyChargeLine(UUID orgId) {
        BackupPlan plan = planRepo.findByOrganizationId(orgId).orElse(null);
        if (plan == null || !plan.isEnabled()) return null;
        long used = recordRepo.sumCompletedSizeBytes(orgId);
        BigDecimal storedGib = BigDecimal.valueOf(used).divide(BigDecimal.valueOf(GIB), 4, RoundingMode.HALF_UP);
        BigDecimal total = plan.getPricePerMonth()
                .add(storedGib.multiply(plan.getPricePerGbMonth()))
                .setScale(2, RoundingMode.HALF_UP);
        if (total.signum() <= 0) return null;
        return new BackupChargeLine(
                String.format("Managed backup subscription (%.2f GiB stored)", storedGib), 1L, total, total,
                plan.getCurrency());
    }

    @Transactional(readOnly = true)
    public FleetBackupStats fleetStats() {
        List<BackupPlan> plans = planRepo.findAll();
        long active = plans.stream().filter(BackupPlan::isActive).count();
        List<BackupRecord> all = recordRepo.findAll();
        long completed = all.stream().filter(b -> b.getStatus() == BackupRecord.Status.COMPLETED).count();
        long failed = all.stream().filter(b -> b.getStatus() == BackupRecord.Status.FAILED).count();
        long stored = all.stream().filter(b -> b.getStatus() == BackupRecord.Status.COMPLETED)
                .mapToLong(b -> b.getSizeBytes() == null ? 0 : b.getSizeBytes()).sum();
        return new FleetBackupStats(plans.size(), active, all.size(), completed, failed, stored);
    }

    // ---- Retention ----

    /** Hours after which an INITIATED record whose upload never completed is reaped (quota reclaimed). */
    private static final long STALE_INITIATED_HOURS = 24;

    /**
     * Hourly sweep: (1) mark completed backups past their retention window EXPIRED, and (2) reap
     * INITIATED records whose upload never completed (agent crashed mid-upload) so they stop counting
     * toward the quota and any partial S3 object is purged. All S3 deletes fire post-commit.
     */
    @Scheduled(fixedDelayString = "${controlcenter.backup.expirySweepMs:3600000}")
    @Transactional
    public void expireDueBackups() {
        if (!storage.isEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        List<String> keys = new ArrayList<>();

        List<BackupRecord> expired = recordRepo.findByStatusAndExpiresAtBefore(BackupRecord.Status.COMPLETED, now);
        for (BackupRecord rec : expired) {
            rec.setStatus(BackupRecord.Status.EXPIRED);
            recordRepo.save(rec);
            keys.add(rec.getS3Key());
        }

        List<BackupRecord> stale = recordRepo.findByStatusAndCreatedAtBefore(
                BackupRecord.Status.INITIATED, now.minusHours(STALE_INITIATED_HOURS));
        for (BackupRecord rec : stale) {
            rec.setStatus(BackupRecord.Status.FAILED);
            rec.setFailureReason("Upload did not complete within " + STALE_INITIATED_HOURS + "h (reaped).");
            recordRepo.save(rec);
            keys.add(rec.getS3Key());
        }

        if (keys.isEmpty()) return;
        deleteAfterCommit(keys);   // delete from S3 only after the status changes are durably committed
        log.info("Backup sweep: {} expired past retention, {} stale INITIATED reaped", expired.size(), stale.size());
    }

    /**
     * Delete S3 objects only after the current transaction commits, so an irreversible storage delete
     * never runs ahead of the row that records it. If the transaction rolls back, nothing is deleted
     * and the records stay COMPLETED (still restorable). A post-commit delete that itself fails leaves
     * a harmless orphaned object ({@link BackupStorageService#delete} swallows errors) — strictly
     * better than a COMPLETED record whose object is already gone. Runs inline if there is no active tx.
     */
    private void deleteAfterCommit(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    for (String k : keys) storage.delete(k);
                }
            });
        } else {
            for (String k : keys) storage.delete(k);
        }
    }

    private BackupRecord ownedRecord(UUID orgId, UUID backupId) {
        return recordRepo.findByIdAndOrganizationId(backupId, orgId).orElseThrow(() -> new ControlCenterException(
                "Backup not found: " + backupId, "BACKUP_NOT_FOUND", HttpStatus.NOT_FOUND));
    }
}
