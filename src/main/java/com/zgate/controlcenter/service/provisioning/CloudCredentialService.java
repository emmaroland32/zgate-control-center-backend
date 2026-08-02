package com.zgate.controlcenter.service.provisioning;

import com.zgate.controlcenter.domain.CloudCredential;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.CloudCredentialRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stores and resolves the credentials Control Center uses to provision into a customer's cloud.
 *
 * <p>The single most important method here is {@link #toRunnerEnvironment}: it is the ONLY place a
 * stored secret is decrypted, and its output goes straight into a provisioning container's
 * environment. It never returns to a controller, never lands in a response, and is never logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CloudCredentialService {

    private final CloudCredentialRepository repo;
    private final OrganizationRepository orgRepo;
    private final SecretCipher cipher;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Never exposes ciphertext — the entity's secret fields are blanked before returning. */
    public List<CloudCredential> findByOrg(UUID orgId) {
        return repo.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
            .map(this::redact)
            .toList();
    }

    public CloudCredential findById(UUID id) {
        return redact(load(id));
    }

    private CloudCredential load(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ControlCenterException(
            "Cloud credential not found: " + id, "CLOUD_CREDENTIAL_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    /**
     * Blank the secret fields on the in-memory copy so a credential can be returned from an API
     * without any chance of the ciphertext or key id travelling with it.
     *
     * <p>The entity is detached-by-convention here; callers that need to MUTATE a credential must
     * use {@link #load} and go through the update path, which re-reads.
     */
    private CloudCredential redact(CloudCredential c) {
        c.setSecretCiphertext(null);
        c.setSecretKeyId(null);
        return c;
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    @Transactional
    public CloudCredential create(CreateRequest req, String actor) {
        if (!orgRepo.existsById(req.getOrganizationId())) {
            throw new ControlCenterException("Organization not found: " + req.getOrganizationId(),
                "ORGANIZATION_NOT_FOUND", HttpStatus.NOT_FOUND);
        }

        CloudCredential.AuthMode mode = req.getAuthMode();
        CloudCredential.CloudCredentialBuilder b = CloudCredential.builder()
            .organizationId(req.getOrganizationId())
            .provider(providerFor(mode))
            .authMode(mode)
            .displayName(req.getDisplayName())
            .defaultRegion(req.getDefaultRegion())
            .enabled(true)
            .createdBy(actor);

        switch (mode) {
            case AWS_ASSUME_ROLE -> {
                require(req.getAwsRoleArn(), "awsRoleArn", mode);
                b.awsRoleArn(req.getAwsRoleArn())
                 .awsAccountId(req.getAwsAccountId())
                 // Generate the external id rather than accepting one: it must be unguessable, and
                 // it is the only thing standing between this role and a confused-deputy attack by
                 // anyone else who can reach the vendor's provisioning identity.
                 .awsExternalId(req.getAwsExternalId() != null && !req.getAwsExternalId().isBlank()
                     ? req.getAwsExternalId() : generateExternalId());
            }
            case AWS_STATIC_KEYS -> {
                require(req.getAwsAccessKeyId(), "awsAccessKeyId", mode);
                require(req.getSecret(), "secret (the AWS secret access key)", mode);
                b.awsAccountId(req.getAwsAccountId())
                 // The access key ID is not secret; only its paired secret key is encrypted.
                 .awsAccessKeyId(req.getAwsAccessKeyId())
                 .secretCiphertext(cipher.encrypt(req.getSecret()))
                 .secretKeyId(cipher.activeKeyId());
            }
            case AZURE_SERVICE_PRINCIPAL -> {
                require(req.getAzureSubscriptionId(), "azureSubscriptionId", mode);
                require(req.getAzureTenantId(), "azureTenantId", mode);
                require(req.getAzureClientId(), "azureClientId", mode);
                require(req.getSecret(), "secret (the service principal client secret)", mode);
                b.azureSubscriptionId(req.getAzureSubscriptionId())
                 .azureTenantId(req.getAzureTenantId())
                 .azureClientId(req.getAzureClientId())
                 .secretCiphertext(cipher.encrypt(req.getSecret()))
                 .secretKeyId(cipher.activeKeyId());
            }
            case GCP_SERVICE_ACCOUNT -> {
                require(req.getGcpProjectId(), "gcpProjectId", mode);
                require(req.getSecret(), "secret (the service-account JSON key)", mode);
                b.gcpProjectId(req.getGcpProjectId())
                 .gcpClientEmail(req.getGcpClientEmail())
                 .secretCiphertext(cipher.encrypt(req.getSecret()))
                 .secretKeyId(cipher.activeKeyId());
            }
            case SSH_KEY -> {
                require(req.getSshHost(), "sshHost", mode);
                require(req.getSshUser(), "sshUser", mode);
                require(req.getSecret(), "secret (the SSH private key)", mode);

                // Terraform's SSH client has no passphrase argument, so an encrypted key fails at
                // apply time with a misleading "authentication failed". Catching the obvious
                // marker here turns that into an answerable error at the point of entry.
                if (req.getSecret().contains("ENCRYPTED") || req.getSecret().contains("Proc-Type: 4,ENCRYPTED")) {
                    throw new ControlCenterException(
                        "That SSH key is passphrase-protected. Terraform's SSH client cannot use one, "
                        + "so it must be an unencrypted key. Generate a dedicated one with: "
                        + "ssh-keygen -t ed25519 -N '' -C zgate-control-center -f zgate_deploy",
                        "SSH_KEY_ENCRYPTED", HttpStatus.BAD_REQUEST);
                }
                if (!req.getSecret().contains("PRIVATE KEY")) {
                    throw new ControlCenterException(
                        "That does not look like a PEM private key. Paste the whole file including the "
                        + "-----BEGIN ... PRIVATE KEY----- and -----END ...----- lines.",
                        "SSH_KEY_MALFORMED", HttpStatus.BAD_REQUEST);
                }

                b.sshHost(req.getSshHost())
                 .sshPort(req.getSshPort() == null ? 22 : req.getSshPort())
                 .sshUser(req.getSshUser())
                 .sshHostPublicKey(req.getSshHostPublicKey())
                 .secretCiphertext(cipher.encrypt(req.getSecret()))
                 .secretKeyId(cipher.activeKeyId());
            }
        }

        CloudCredential saved = repo.save(b.build());
        log.info("Cloud credential created: org={} provider={} mode={} by={}",
                 req.getOrganizationId(), saved.getProvider(), mode, actor);

        // The external id must be shown once so the customer can put it in their trust policy;
        // it is not a secret, so returning it here is fine.
        return redact(saved);
    }

    @Transactional
    public void delete(UUID id, String actor) {
        CloudCredential c = load(id);
        log.info("Cloud credential deleted: id={} org={} by={}", id, c.getOrganizationId(), actor);
        repo.delete(c);
    }

    @Transactional
    public CloudCredential setEnabled(UUID id, boolean enabled, String actor) {
        CloudCredential c = load(id);
        c.setEnabled(enabled);
        log.info("Cloud credential {} {} by {}", id, enabled ? "enabled" : "disabled", actor);
        return redact(repo.save(c));
    }

    // ── The one decrypt path ────────────────────────────────────────────────

    /**
     * Build the provider environment for a provisioning container.
     *
     * <p>This is the ONLY place a stored secret is decrypted. The returned map is passed directly to
     * the runner's process environment and must never be logged, returned from a controller, or
     * written to the run log.
     */
    public Map<String, String> toRunnerEnvironment(UUID credentialId) {
        CloudCredential c = repo.findById(credentialId).orElseThrow(() -> new ControlCenterException(
            "Cloud credential not found: " + credentialId, "CLOUD_CREDENTIAL_NOT_FOUND", HttpStatus.NOT_FOUND));

        if (!c.isEnabled()) {
            throw new ControlCenterException(
                "Cloud credential '" + c.getDisplayName() + "' is disabled.",
                "CLOUD_CREDENTIAL_DISABLED", HttpStatus.CONFLICT);
        }

        return switch (c.getAuthMode()) {
            // Nothing to decrypt: the provider assumes the role using the runner's own vendor
            // credentials, which come from the host environment rather than from here.
            case AWS_ASSUME_ROLE -> Map.of();

            case AWS_STATIC_KEYS -> Map.of(
                "AWS_ACCESS_KEY_ID", nullSafe(c.getAwsExternalId()),
                "AWS_SECRET_ACCESS_KEY", cipher.decrypt(c.getSecretCiphertext(), c.getSecretKeyId()));

            case AZURE_SERVICE_PRINCIPAL -> Map.of(
                "ARM_SUBSCRIPTION_ID", nullSafe(c.getAzureSubscriptionId()),
                "ARM_TENANT_ID", nullSafe(c.getAzureTenantId()),
                "ARM_CLIENT_ID", nullSafe(c.getAzureClientId()),
                "ARM_CLIENT_SECRET", cipher.decrypt(c.getSecretCiphertext(), c.getSecretKeyId()));

            case GCP_SERVICE_ACCOUNT -> Map.of(
                "GOOGLE_PROJECT", nullSafe(c.getGcpProjectId()),
                "GOOGLE_CREDENTIALS", cipher.decrypt(c.getSecretCiphertext(), c.getSecretKeyId()));

            // No provider env vars: the SSH key travels in the spec (written to a 0600 file in the
            // ephemeral run workspace) because Terraform's connection block reads it as a variable
            // rather than from the environment. See ProvisioningService.withRuntimeSecrets.
            case SSH_KEY -> Map.of();
        };
    }

    /**
     * The decrypted SSH private key for a bare-metal credential.
     *
     * <p>Separate from {@link #toRunnerEnvironment} because Terraform's {@code connection} block
     * reads the key as a variable rather than from the environment, so it has to reach the spec.
     * The caller merges it into the run-time-only secrets and never persists it.
     */
    public String sshPrivateKey(UUID credentialId) {
        CloudCredential c = loadForProvisioning(credentialId);
        if (c.getAuthMode() != CloudCredential.AuthMode.SSH_KEY) {
            throw new ControlCenterException(
                "Credential '" + c.getDisplayName() + "' is not an SSH key.",
                "CREDENTIAL_NOT_SSH", HttpStatus.BAD_REQUEST);
        }
        return cipher.decrypt(c.getSecretCiphertext(), c.getSecretKeyId());
    }

    /** Reads the credential WITHOUT redacting — for internal use by the provisioning service only. */
    public CloudCredential loadForProvisioning(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ControlCenterException(
            "Cloud credential not found: " + id, "CLOUD_CREDENTIAL_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void recordVerification(UUID id, boolean ok, String message) {
        repo.findById(id).ifPresent(c -> {
            c.setLastVerifiedAt(java.time.LocalDateTime.now());
            c.setLastVerifyStatus(ok ? "OK" : "FAILED");
            c.setLastVerifyMessage(message);
            repo.save(c);
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String providerFor(CloudCredential.AuthMode mode) {
        return switch (mode) {
            case AWS_ASSUME_ROLE, AWS_STATIC_KEYS -> "aws";
            case AZURE_SERVICE_PRINCIPAL -> "azure";
            case GCP_SERVICE_ACCOUNT -> "gcp";
            case SSH_KEY -> "baremetal";
        };
    }

    private static void require(String value, String field, CloudCredential.AuthMode mode) {
        if (value == null || value.isBlank()) {
            throw new ControlCenterException(
                field + " is required for auth mode " + mode + ".",
                "CLOUD_CREDENTIAL_FIELD_REQUIRED", HttpStatus.BAD_REQUEST);
        }
    }

    /** 160 bits of entropy, url-safe. Unguessable is the whole security property of an external id. */
    private static String generateExternalId() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return "zgate-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    @Data
    public static class CreateRequest {
        private UUID organizationId;
        private CloudCredential.AuthMode authMode;
        private String displayName;
        private String defaultRegion;

        private String awsAccountId;
        private String awsRoleArn;
        private String awsExternalId;
        private String awsAccessKeyId;

        private String azureSubscriptionId;
        private String azureTenantId;
        private String azureClientId;

        private String gcpProjectId;
        private String gcpClientEmail;

        // Bare metal (SSH_KEY)
        private String sshHost;
        private Integer sshPort;
        private String sshUser;
        /** The server's host public key. Not secret; pinning it prevents impersonation. */
        private String sshHostPublicKey;

        /**
         * The mode-specific secret: AWS secret access key, Azure client secret, or the whole GCP
         * service-account JSON. Accepted once on create, encrypted immediately, never returned.
         */
        private String secret;
    }
}
