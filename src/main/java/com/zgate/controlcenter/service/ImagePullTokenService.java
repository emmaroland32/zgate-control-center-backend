package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Controls the image supply. When a (possibly firewalled) org update-agent wants to pull a ZGATE
 * release image, it asks Control Center first — and CC only authorizes the pull if the org's
 * subscription is valid AND the requested release is within what the org has paid for (its
 * entitledVersion). This is the belt to the org-side version gate's suspenders: even if a customer
 * gets hold of a newer image tag, they can't obtain a pull credential for it here.
 *
 * <p>The entitlement decision and entitled-release resolution are always enforced; issuing an actual
 * short-lived registry credential is delegated to a {@link RegistryCredentialProvider} (e.g. AWS ECR),
 * which is optional — without it the org is still told whether it may pull and the exact image ref.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImagePullTokenService {

    private final OrganizationRepository orgRepo;
    private final ReleaseRepository releaseRepo;

    @Autowired(required = false)
    private RegistryCredentialProvider credentialProvider;

    @Value("${controlcenter.pullToken.versionGranularity:minor}")
    private String versionGranularity;

    /**
     * @param orgId            the requesting org (authenticated by its Control-Center org id / API key)
     * @param requestedVersion the release version the agent wants; null → the latest release
     */
    public PullAuthorization authorize(UUID orgId, String requestedVersion) {
        Organization org = orgRepo.findById(orgId).orElse(null);
        if (org == null) {
            return PullAuthorization.denied("org_not_found", "Unknown organization.");
        }

        // 1. Subscription must be valid (the same gate the license-renewal kill switch uses).
        if (!LicenseService.isEntitled(org, LocalDateTime.now())) {
            log.warn("Pull DENIED for org {} — subscription lapsed at {}", orgId, org.getSubscriptionValidUntil());
            return PullAuthorization.denied("subscription_lapsed",
                "Your subscription has lapsed. Renew to receive updates.");
        }

        // 2. Resolve the requested release (explicit version, else latest).
        Optional<Release> found = (requestedVersion != null && !requestedVersion.isBlank())
            ? releaseRepo.findByVersion(requestedVersion.trim())
            : releaseRepo.findByIsLatestTrue();
        if (found.isEmpty()) {
            return PullAuthorization.denied("release_not_found", "No such release.");
        }
        Release release = found.get();

        if (release.getApprovalStatus() != Release.ApprovalStatus.APPROVED) {
            return PullAuthorization.denied("release_not_approved", "Release is not approved for distribution.");
        }

        // 3. Version entitlement — can't pull a release beyond what the org has paid for.
        if (org.getEntitledVersion() != null && !org.getEntitledVersion().isBlank()
                && AnomalyDetectionService.compareVersions(release.getVersion(), org.getEntitledVersion(), components()) > 0) {
            log.warn("Pull DENIED for org {} — release {} exceeds entitled {}",
                orgId, release.getVersion(), org.getEntitledVersion());
            return PullAuthorization.denied("version_not_entitled",
                "Release " + release.getVersion() + " is beyond your entitlement (" + org.getEntitledVersion() + ").");
        }

        // Authorized. Mint a short-lived pull credential if a provider is configured.
        RegistryCredentialProvider.RegistryCredential cred =
            credentialProvider != null ? credentialProvider.mint(release) : null;

        log.info("Pull AUTHORIZED for org {} → release {} ({}), credential={}",
            orgId, release.getVersion(), release.getDockerTag(), cred != null ? "issued" : "none");

        return new PullAuthorization(true, "authorized",
            "Authorized to pull " + release.getVersion(),
            release.getDockerRegistry(), release.getDockerTag(), release.getVersion(),
            cred != null ? cred.username() : null,
            cred != null ? cred.password() : null,
            cred != null ? cred.expiresAt() : null);
    }

    private int components() {
        String g = versionGranularity == null ? "minor" : versionGranularity.trim().toLowerCase();
        return switch (g) {
            case "major" -> 1;
            case "exact", "patch" -> 0;
            default -> 2;
        };
    }

    /**
     * The pull decision. {@code authorized=false} carries a reason; when true it carries the entitled
     * image coordinates and (if a provider is configured) a short-lived pull credential.
     */
    public record PullAuthorization(
        boolean authorized,
        String reason,
        String message,
        String registry,
        String dockerTag,
        String version,
        String credentialUsername,
        String credentialPassword,
        LocalDateTime credentialExpiresAt) {

        static PullAuthorization denied(String reason, String message) {
            return new PullAuthorization(false, reason, message, null, null, null, null, null, null);
        }
    }
}
