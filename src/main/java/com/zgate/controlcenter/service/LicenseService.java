package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.IssueLicenseRequest;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseService {

    private final LicenseRepository repo;
    private final OrganizationRepository orgRepo;
    private final LicenseSigningService signingService;

    public List<License> findAll() {
        return repo.findAll();
    }

    public List<License> findByOrg(UUID orgId) {
        return repo.findByOrganizationId(orgId);
    }

    /** Default lifetime of an issued/renewed license when the org has not set its own TTL. */
    static final int DEFAULT_LICENSE_TTL_DAYS = 30;

    @Transactional
    public License issue(IssueLicenseRequest req, String issuedBy) {
        var org = orgRepo.findById(req.getOrganizationId())
            .orElseThrow(() -> new ControlCenterException("Organization not found: " + req.getOrganizationId()));

        // Deactivate existing if present
        repo.findFirstByOrganizationIdAndModuleNameOrderByActivatedAtDesc(req.getOrganizationId(), req.getModuleName())
            .ifPresent(existing -> {
                existing.setStatus(License.Status.SUSPENDED);
                repo.save(existing);
            });

        // Short-lived by default: absent an explicit expiry, licenses live for the org's TTL and are
        // kept alive only by the renewal job (which enforces the subscription). maxVersion defaults to
        // what the org has paid for, so a manual issue still can't over-entitle a customer by omission.
        LocalDateTime expiresAt = req.getExpiresAt() != null
            ? req.getExpiresAt()
            : LocalDateTime.now().plusDays(ttlDays(org));
        String maxVersion = req.getMaxVersion() != null ? req.getMaxVersion() : org.getEntitledVersion();

        License license = repo.save(License.builder()
            .organizationId(req.getOrganizationId())
            .moduleName(req.getModuleName())
            .status(License.Status.ACTIVE)
            .expiresAt(expiresAt)
            .maxUsers(req.getMaxUsers())
            .features(req.getFeatures())
            .fingerprint(req.getFingerprint())
            .maxVersion(maxVersion)
            .imageDigest(req.getImageDigest())
            .graceDays(req.getGraceDays())
            .deploymentTier(org.getDeploymentTier() != null ? org.getDeploymentTier().name() : null)
            .maxInstances(org.getMaxInstances())
            .activatedAt(LocalDateTime.now())
            .issuedBy(issuedBy)
            .build());

        // Pre-generate signed bundle if key is available
        if (signingService.isSigningAvailable()) {
            try {
                String bundle = signingService.generateSignedBundle(license, org.getName());
                license.setBundleJson(bundle);
                license.setDeliveryStatus(License.DeliveryStatus.PENDING);
                license = repo.save(license);
            } catch (Exception e) {
                log.warn("Could not pre-generate bundle for license {}: {}", license.getId(), e.getMessage());
            }
        }

        return license;
    }

    /** The org's configured license lifetime in days, or the default when unset. */
    static int ttlDays(Organization org) {
        Integer ttl = org.getLicenseTtlDays();
        return (ttl != null && ttl > 0) ? ttl : DEFAULT_LICENSE_TTL_DAYS;
    }

    public enum RenewalOutcome { RENEWED, SKIPPED_NOT_ENTITLED, SKIPPED_NOT_DUE, SKIPPED_LICENSE_GONE }

    /**
     * The non-payment kill switch. Extends a short-lived license (so the org's hourly poll keeps it
     * alive) ONLY while the org's subscription is valid; a lapsed subscription is left to expire, after
     * which the org-side grace window ends and the deployment locks.
     *
     * <p>The renewed expiry is capped at {@code subscriptionValidUntil}, so a license can never outlive
     * what the customer has paid for — when they renew payment, the next run extends it again.
     */
    @Transactional
    public RenewalOutcome renewIfEntitled(UUID licenseId) {
        License l = repo.findById(licenseId).orElse(null);
        if (l == null || l.getStatus() != License.Status.ACTIVE) return RenewalOutcome.SKIPPED_LICENSE_GONE;

        Organization org = orgRepo.findById(l.getOrganizationId()).orElse(null);
        if (org == null) return RenewalOutcome.SKIPPED_LICENSE_GONE;

        LocalDateTime now = LocalDateTime.now();
        if (!isEntitled(org, now)) {
            log.warn("License renewal REFUSED for org {} (subscription lapsed at {}) — license {} left to expire.",
                org.getId(), org.getSubscriptionValidUntil(), licenseId);
            return RenewalOutcome.SKIPPED_NOT_ENTITLED;
        }

        LocalDateTime newExpiry = renewalExpiry(org, now);
        if (l.getExpiresAt() != null && !newExpiry.isAfter(l.getExpiresAt())) {
            // Subscription doesn't extend the current coverage (already covers the paid-through date).
            return RenewalOutcome.SKIPPED_NOT_DUE;
        }

        l.setExpiresAt(newExpiry);
        if (org.getEntitledVersion() != null && !org.getEntitledVersion().isBlank()) {
            l.setMaxVersion(org.getEntitledVersion()); // pick up any version-entitlement change
        }
        // Refresh topology entitlement too, so a tier/seat change flows out on the next renewal.
        l.setDeploymentTier(org.getDeploymentTier() != null ? org.getDeploymentTier().name() : null);
        l.setMaxInstances(org.getMaxInstances());
        // Regenerate the signed bundle so the org picks up the extended expiry on its next poll.
        if (signingService.isSigningAvailable()) {
            l.setBundleJson(signingService.generateSignedBundle(l, org.getName()));
            l.setDeliveryStatus(License.DeliveryStatus.PENDING);
        }
        repo.save(l);
        log.info("Renewed license {} (org {}) to {}", licenseId, org.getId(), newExpiry);
        return RenewalOutcome.RENEWED;
    }

    /**
     * An org is entitled while its subscription is unset (perpetual/unmanaged) or still valid.
     *
     * <p>Public because the provisioning subpackage gates on it too: a lapsed customer must not be
     * able to obtain a freshly provisioned deployment any more than they can pull a new image.
     * Both paths deliberately consult this one predicate rather than reimplementing the rule.
     */
    public static boolean isEntitled(Organization org, LocalDateTime now) {
        LocalDateTime until = org.getSubscriptionValidUntil();
        return until == null || until.isAfter(now);
    }

    /** now + TTL, but never past the paid-through date, so a license can't outlive the subscription. */
    static LocalDateTime renewalExpiry(Organization org, LocalDateTime now) {
        LocalDateTime ttlExpiry = now.plusDays(ttlDays(org));
        LocalDateTime until = org.getSubscriptionValidUntil();
        return (until != null && until.isBefore(ttlExpiry)) ? until : ttlExpiry;
    }

    /**
     * Issue licenses for multiple modules in one transaction and return a single
     * combined signed bundle covering all of them.
     */
    @Transactional
    public LicenseBundle issueBulk(IssueBulkRequest req, String issuedBy) {
        Organization org = orgRepo.findById(req.organizationId())
            .orElseThrow(() -> new ControlCenterException("Organization not found: " + req.organizationId()));

        LocalDateTime expiresAt = req.expiresAt() != null
            ? req.expiresAt()
            : LocalDateTime.now().plusDays(ttlDays(org));
        String maxVersion = req.maxVersion() != null ? req.maxVersion() : org.getEntitledVersion();

        List<License> issued = new java.util.ArrayList<>();
        for (String moduleName : req.moduleNames()) {
            // Suspend any existing active license for this module
            repo.findFirstByOrganizationIdAndModuleNameOrderByActivatedAtDesc(req.organizationId(), moduleName)
                .ifPresent(existing -> {
                    existing.setStatus(License.Status.SUSPENDED);
                    repo.save(existing);
                });

            License license = repo.save(License.builder()
                .organizationId(req.organizationId())
                .moduleName(moduleName)
                .status(License.Status.ACTIVE)
                .expiresAt(expiresAt)
                .maxUsers(req.maxUsers())
                .features(req.features())
                .fingerprint(req.fingerprint())
                .maxVersion(maxVersion)
                .imageDigest(req.imageDigest())
                .graceDays(req.graceDays())
                .deploymentTier(org.getDeploymentTier() != null ? org.getDeploymentTier().name() : null)
                .maxInstances(org.getMaxInstances())
                .activatedAt(LocalDateTime.now())
                .issuedBy(issuedBy)
                .build());
            issued.add(license);
        }

        // Generate one combined bundle for all modules
        String bundleJson = signingService.generateSignedBundleForModules(issued, org.getName());
        String integrity = sha256Hex(bundleJson);

        // Persist bundle on each issued license row
        for (License license : issued) {
            license.setBundleJson(bundleJson);
            license.setDeliveryStatus(License.DeliveryStatus.PENDING);
            repo.save(license);
        }

        return new LicenseBundle(issued.get(0).getId(), req.organizationId(),
            String.join(",", req.moduleNames()), bundleJson, integrity, LocalDateTime.now());
    }

    @Transactional
    public License deactivate(UUID id) {
        License license = repo.findById(id)
            .orElseThrow(() -> new ControlCenterException("License not found: " + id));
        license.setStatus(License.Status.SUSPENDED);
        return repo.save(license);
    }

    public List<License> findExpiringSoon() {
        return repo.findExpiringSoon(LocalDateTime.now().plusDays(30));
    }

    public LicenseStats getStats() {
        LocalDateTime cutoff = LocalDateTime.now().plusDays(30);
        return new LicenseStats(
            repo.countByStatus(License.Status.ACTIVE),
            repo.countByStatus(License.Status.EXPIRED),
            repo.countByStatus(License.Status.SUSPENDED),
            repo.findExpiringSoon(cutoff).size()
        );
    }

    /**
     * Generate a fingerprint token for an organisation. The fingerprint is a
     * deterministic Base64-URL-encoded SHA-256 of "orgId:moduleName" and is
     * used as an identifier when activating a license bundle on a ZGATE instance.
     */
    public FingerprintResult getFingerprint(UUID orgId, String moduleName) {
        String input = orgId.toString() + ":" + moduleName;
        String fp = sha256Base64(input);
        return new FingerprintResult(orgId, moduleName, fp);
    }

    /**
     * Activate a license from a JSON payload (issued by ControlCenter and delivered
     * out-of-band). Stores the SHA-256 hash on the matching License row.
     */
    @Transactional
    public License activateJson(UUID orgId, String moduleName, String licenseJson, String activatedBy) {
        String fileHash = sha256Hex(licenseJson);
        License license = repo.findFirstByOrganizationIdAndModuleNameOrderByActivatedAtDesc(orgId, moduleName)
            .orElseThrow(() -> new ControlCenterException(
                "No license found for org " + orgId + " / module " + moduleName));
        license.setLicenseFileHash(fileHash);
        license.setActivatedAt(LocalDateTime.now());
        license.setStatus(License.Status.ACTIVE);
        return repo.save(license);
    }

    /**
     * Activate a license from a raw file (binary or text).
     * Stores the SHA-256 hash of the file bytes as the licenseFileHash.
     */
    @Transactional
    public License activateFile(UUID orgId, String moduleName, byte[] fileBytes, String activatedBy) {
        String fileHash = sha256HexBytes(fileBytes);
        License license = repo.findFirstByOrganizationIdAndModuleNameOrderByActivatedAtDesc(orgId, moduleName)
            .orElseThrow(() -> new ControlCenterException(
                "No license found for org " + orgId + " / module " + moduleName));
        license.setLicenseFileHash(fileHash);
        license.setActivatedAt(LocalDateTime.now());
        license.setStatus(License.Status.ACTIVE);
        return repo.save(license);
    }

    /**
     * Generate (or regenerate) a signed license bundle for a given license.
     * The bundle is persisted on the License row and returned.
     */
    @Transactional
    public LicenseBundle generateBundle(UUID licenseId) {
        License license = repo.findById(licenseId)
            .orElseThrow(() -> new ControlCenterException("License not found: " + licenseId));

        String orgName = orgRepo.findById(license.getOrganizationId())
            .map(o -> o.getName())
            .orElse(null);

        String bundleJson = signingService.generateSignedBundle(license, orgName);
        String integrity = sha256Hex(bundleJson);

        license.setBundleJson(bundleJson);
        license.setDeliveryStatus(License.DeliveryStatus.PENDING);
        repo.save(license);

        return new LicenseBundle(license.getId(), license.getOrganizationId(),
            license.getModuleName(), bundleJson, integrity, LocalDateTime.now());
    }

    /**
     * Verify the integrity of a license by checking that a bundle hash exists.
     */
    public VerifyResult verifyIntegrity(UUID licenseId) {
        License license = repo.findById(licenseId)
            .orElseThrow(() -> new ControlCenterException("License not found: " + licenseId));
        boolean valid = license.getLicenseFileHash() != null;
        String message = valid ? "License integrity verified" : "No license file hash recorded";
        return new VerifyResult(licenseId, valid, message);
    }

    public BulkVerifyResult verifyAllIntegrity() {
        List<License> all = repo.findAll();
        long valid = all.stream().filter(l -> l.getBundleJson() != null).count();
        long missing = all.size() - valid;
        return new BulkVerifyResult(all.size(), valid, missing);
    }

    /**
     * Called by an org instance to fetch active signed bundles.
     * Marks each returned bundle as DELIVERED and records fetchedAt.
     */
    @Transactional
    public List<BundleDelivery> fetchBundlesForOrg(UUID orgId) {
        List<License> licenses = repo.findByOrganizationIdAndDeliveryStatusNot(
            orgId, License.DeliveryStatus.ACTIVATED);

        return licenses.stream()
            .filter(l -> l.getBundleJson() != null)
            .filter(l -> l.getStatus() == License.Status.ACTIVE)
            .map(l -> {
                if (l.getDeliveryStatus() == License.DeliveryStatus.PENDING) {
                    l.setDeliveryStatus(License.DeliveryStatus.DELIVERED);
                    l.setFetchedAt(LocalDateTime.now());
                    repo.save(l);
                }
                return new BundleDelivery(l.getId(), l.getModuleName(), l.getBundleJson(),
                    l.getExpiresAt(), l.getDeliveryStatus());
            })
            .collect(Collectors.toList());
    }

    /**
     * Called by an org instance to report the result of license activation.
     * Updates deliveryStatus to ACTIVATED or FAILED and stores reported module state.
     */
    @Transactional
    public void reportActivation(UUID orgId, String moduleName, boolean success, String orgModulesJson) {
        repo.findFirstByOrganizationIdAndModuleNameOrderByActivatedAtDesc(orgId, moduleName).ifPresent(license -> {
            license.setDeliveryStatus(success
                ? License.DeliveryStatus.ACTIVATED
                : License.DeliveryStatus.FAILED);
            license.setOrgActivatedAt(LocalDateTime.now());
            if (orgModulesJson != null) {
                license.setOrgModules(orgModulesJson);
            }
            repo.save(license);
            log.info("License activation report: org={} module={} success={}", orgId, moduleName, success);
        });
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String sha256Hex(String input) {
        return sha256HexBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256HexBytes(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new ControlCenterException("SHA-256 not available", e);
        }
    }

    private String sha256Base64(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new ControlCenterException("SHA-256 not available", e);
        }
    }

    // ----------------------------------------------------------------
    // Return types
    // ----------------------------------------------------------------

    public record IssueBulkRequest(
        UUID organizationId,
        List<String> moduleNames,
        java.time.LocalDateTime expiresAt,
        Integer maxUsers,
        String features,
        String fingerprint,
        String maxVersion,
        String imageDigest,
        Integer graceDays
    ) {}
    public record LicenseStats(long active, long expired, long suspended, long expiringSoon) {}
    public record FingerprintResult(UUID orgId, String moduleName, String fingerprint) {}
    public record LicenseBundle(UUID licenseId, UUID orgId, String moduleName,
                                String payload, String integrity, LocalDateTime generatedAt) {}
    public record VerifyResult(UUID licenseId, boolean valid, String message) {}
    public record BulkVerifyResult(long total, long valid, long missing) {}
    public record BundleDelivery(UUID licenseId, String moduleName, String bundleJson,
                                 LocalDateTime expiresAt, License.DeliveryStatus deliveryStatus) {}
}
