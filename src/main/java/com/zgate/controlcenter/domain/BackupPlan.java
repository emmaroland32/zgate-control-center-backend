package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An organization's managed-backup subscription. Gates {@code POST /api/v1/backups/initiate}
 * (must be {@code enabled} and within {@code subscriptionValidUntil}) and defines the storage quota,
 * retention, and pricing the {@code Invoice} pipeline bills against. One per organization.
 */
@Entity
@Table(name = "backup_plan")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class BackupPlan {

    @Id @UuidGenerator
    private UUID id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private UUID organizationId;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** Maximum total stored (completed) backup size, in GiB. */
    @Column(name = "storage_quota_gb", nullable = false)
    @Builder.Default
    private int storageQuotaGb = 10;

    /** How long a completed backup is retained before it is expired + purged from S3. */
    @Column(name = "retention_days", nullable = false)
    @Builder.Default
    private int retentionDays = 30;

    /** Optional cap on the number of retained backups (null = unlimited; quota still applies). */
    @Column(name = "max_retained_backups")
    private Integer maxRetainedBackups;

    @Column(name = "price_per_month", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal pricePerMonth = BigDecimal.ZERO;

    @Column(name = "price_per_gb_month", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal pricePerGbMonth = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    /** Paid-through date; null = active until cancelled. */
    @Column(name = "subscription_valid_until")
    private LocalDateTime subscriptionValidUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Active = enabled and not past its paid-through date. */
    public boolean isActive() {
        return enabled && (subscriptionValidUntil == null || subscriptionValidUntil.isAfter(LocalDateTime.now()));
    }
}
