package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * How Control Center authenticates to a customer's cloud in order to provision ZGATE for them.
 *
 * <p><b>Preferred mode is {@link AuthMode#AWS_ASSUME_ROLE}, which holds no secret at all.</b> The
 * customer creates an IAM role trusting the vendor account, guarded by an external id; the runner's
 * own credentials assume it. Nothing here can leak a standing customer key because there is none.
 *
 * <p>The other modes necessarily hold a long-lived secret. It is stored as AES-GCM ciphertext in
 * {@link #secretCiphertext}, tagged with the {@link #secretKeyId} that produced it so a key rotation
 * can distinguish old envelopes from new ones and an unknown id fails closed rather than silently
 * decrypting with the wrong key. Plaintext exists only inside
 * {@code CloudCredentialService.decrypt} and the environment of the provisioning container.
 *
 * <p>The non-secret identifiers (account id, role arn, subscription id, project id) are stored in the
 * clear on purpose: they are shown in the UI and used to render a plan without needing a decrypt.
 */
@Entity
@Table(name = "cloud_credentials")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CloudCredential {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 16)
    private String provider;   // aws | azure | gcp

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AuthMode authMode;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(length = 64)
    private String defaultRegion;

    // ── Non-secret identifiers ──────────────────────────────────────────────
    @Column(length = 32)  private String awsAccountId;
    @Column(length = 512) private String awsRoleArn;
    /** Unguessable but not a password — it only has meaning in the customer's own trust policy. */
    @Column(length = 128) private String awsExternalId;
    /** The non-secret half of a static key pair; the secret access key is the encrypted half. */
    @Column(length = 128) private String awsAccessKeyId;
    @Column(length = 64)  private String azureSubscriptionId;
    @Column(length = 64)  private String azureTenantId;
    @Column(length = 64)  private String azureClientId;
    @Column(length = 64)  private String gcpProjectId;
    @Column(length = 256) private String gcpClientEmail;

    // ── Bare metal (SSH_KEY) ────────────────────────────────────────────────
    /** Hostname or IP Control Center connects to. */
    @Column(length = 255) private String sshHost;
    private Integer sshPort;
    @Column(length = 64)  private String sshUser;
    /**
     * The server's SSH host public key. Not secret — it is the server's public identity — but
     * pinning it is what stops a man in the middle receiving the customer's database credentials.
     */
    @Column(columnDefinition = "TEXT") private String sshHostPublicKey;

    // ── Secret material ─────────────────────────────────────────────────────
    /**
     * AES-GCM ciphertext (base64) of the mode-specific secret: the AWS secret access key, the Azure
     * client secret, or the whole GCP service-account JSON. Null for {@code AWS_ASSUME_ROLE}.
     */
    @Column(columnDefinition = "TEXT")
    private String secretCiphertext;

    /** Key id that produced {@link #secretCiphertext}. An unknown id must throw, never guess. */
    @Column(length = 64)
    private String secretKeyId;

    // ── Verification ────────────────────────────────────────────────────────
    /**
     * Outcome of the last reachability check. Surfaced in the UI so a broken or revoked credential is
     * visible before an operator starts a provision, rather than three minutes into one.
     */
    private LocalDateTime lastVerifiedAt;

    @Column(length = 16)
    private String lastVerifyStatus;  // OK | FAILED

    @Column(columnDefinition = "TEXT")
    private String lastVerifyMessage;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum AuthMode {
        /** No stored secret: the vendor's identity assumes a customer role guarded by an external id. */
        AWS_ASSUME_ROLE,
        /** Long-lived AWS access key pair. Held encrypted; prefer AWS_ASSUME_ROLE. */
        AWS_STATIC_KEYS,
        /** Azure service principal — client id + encrypted client secret. */
        AZURE_SERVICE_PRINCIPAL,
        /** Google service-account JSON key, encrypted whole. */
        GCP_SERVICE_ACCOUNT,
        /**
         * An SSH private key for a customer-supplied server. Encrypted whole, exactly like the
         * cloud secrets — but note this one is not scoped by any cloud IAM policy: whatever the
         * login user can do on that machine, this key can do.
         */
        SSH_KEY
    }

    /** True when this credential carries no standing secret — the safe shape. */
    public boolean isSecretless() {
        return authMode == AuthMode.AWS_ASSUME_ROLE;
    }
}
