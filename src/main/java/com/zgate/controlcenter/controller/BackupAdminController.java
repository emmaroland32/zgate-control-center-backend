package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.BackupPlan;
import com.zgate.controlcenter.domain.BackupRecord;
import com.zgate.controlcenter.service.BackupService;
import com.zgate.controlcenter.web.ResponseMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Control Center admin API for managed backups: fleet view, per-org backups/usage, and plan
 * management. JWT-authenticated; responses use the standard {@code ApiResponse} envelope.
 */
@RestController
@RequestMapping("/api/v1/admin/backups")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
public class BackupAdminController {

    private final BackupService backupService;
    private final com.zgate.controlcenter.service.AuditService audit;

    /** Fleet-wide backup records (paged, newest first by creation). */
    @GetMapping
    public ResponseEntity<Page<BackupRecord>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(backupService.adminList(PageRequest.of(page, size)));
    }

    @GetMapping("/stats")
    public ResponseEntity<BackupService.FleetBackupStats> stats() {
        return ResponseEntity.ok(backupService.fleetStats());
    }

    @GetMapping("/org/{orgId}")
    public ResponseEntity<List<BackupRecord>> forOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok(backupService.adminListForOrg(orgId));
    }

    @GetMapping("/org/{orgId}/usage")
    public ResponseEntity<BackupService.OrgBackupUsage> usage(@PathVariable UUID orgId) {
        return ResponseEntity.ok(backupService.usageForOrg(orgId));
    }

    @GetMapping("/org/{orgId}/plan")
    public ResponseEntity<BackupPlan> getPlan(@PathVariable UUID orgId) {
        return ResponseEntity.ok(backupService.getPlan(orgId));
    }

    /** Create or update an org's backup subscription (quota, retention, pricing, paid-through). */
    @PutMapping("/org/{orgId}/plan")
    @ResponseMessage(code = "BACKUP_PLAN_UPDATED", value = "Backup plan updated")
    public ResponseEntity<BackupPlan> upsertPlan(@PathVariable UUID orgId, @RequestBody BackupPlan plan) {
        return ResponseEntity.ok(backupService.upsertPlan(orgId, plan));
    }

    /**
     * Operator-assisted recovery: a short-lived presigned download of a completed backup's
     * CIPHERTEXT. Zero-knowledge holds — the customer's passphrase decrypts it, never Control
     * Center — so this is for handing a recovering customer their own bytes, and it is
     * SUPER_ADMIN + audited because it is still an export of a customer's database dump.
     */
    @PostMapping("/org/{orgId}/{backupId}/restore-url")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "BACKUP_RESTORE_URL", value = "Download link created (valid ~30 minutes)")
    public ResponseEntity<java.util.Map<String, String>> restoreUrl(
            @PathVariable UUID orgId, @PathVariable UUID backupId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails user) {
        // Audited BEFORE the URL exists: a failure after minting would leave a live 30-minute
        // capability to download a customer's database ciphertext with nothing recording it.
        audit.log(user.getUsername(), user.getUsername(), "BACKUP_RESTORE_URL_ISSUED",
                  "BackupRecord", backupId.toString(), orgId, null,
                  "ciphertext download link requested by operator (~30 min validity)",
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
        String url = backupService.restoreUrl(orgId, backupId);
        return ResponseEntity.ok(java.util.Map.of("downloadUrl", url));
    }
}
