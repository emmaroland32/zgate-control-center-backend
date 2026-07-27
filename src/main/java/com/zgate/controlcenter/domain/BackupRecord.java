package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One managed backup. The encrypted bytes live in S3 under {@link #s3Key}; this row is only metadata.
 * Lifecycle: INITIATED (presigned upload issued) → COMPLETED (agent confirmed, size/sha256 recorded)
 * or FAILED; later EXPIRED (past retention, purged from S3) or DELETED.
 */
@Entity
@Table(name = "backup_record")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BackupRecord {

    public enum Status { INITIATED, COMPLETED, FAILED, EXPIRED, DELETED }

    @Id @UuidGenerator
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    /** Which org instance/node produced it (optional, from the telemetry node id). */
    @Column(name = "node_id")
    private String nodeId;

    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    private String label;

    /** Ciphertext size in bytes; set on completion. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Client-computed checksum of the uploaded ciphertext (integrity check on restore). */
    private String sha256;

    /** True when the payload is customer-encrypted before upload (the required, zero-knowledge mode). */
    @Column(name = "client_encrypted", nullable = false)
    @Builder.Default
    private boolean clientEncrypted = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private Status status = Status.INITIATED;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** Proven restorable (agent decrypted it + pg_restore --list succeeded). */
    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verify_error", length = 512)
    private String verifyError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
