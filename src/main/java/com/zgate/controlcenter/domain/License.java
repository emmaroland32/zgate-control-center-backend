package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "licenses")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class License {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String moduleName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private LocalDateTime expiresAt;
    private Integer maxUsers;
    private LocalDateTime activatedAt;
    private String licenseFileHash;
    private String fingerprint;

    /**
     * Commercial-enforcement fields carried into the signed bundle and enforced at runtime by the
     * org-side LicenseEnforcementService:
     *   maxVersion  — highest ZGATE build this license permits (blocks customer self-upgrade)
     *   imageDigest — the entitled container image digest (verified against the running image)
     *   graceDays   — days after expiry before the org hard-locks (null → org-side default)
     */
    private String maxVersion;
    private String imageDigest;
    private Integer graceDays;

    @Column(columnDefinition = "TEXT")
    private String features; // JSON

    private String issuedBy;

    /** Tracks whether the signed bundle has been fetched/activated by the org. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    /** Cached signed .lic bundle — regenerated on each generateBundle() call. */
    @Column(columnDefinition = "TEXT")
    private String bundleJson;

    private LocalDateTime fetchedAt;      // when org first pulled the bundle
    private LocalDateTime orgActivatedAt; // when org confirmed successful activation

    @Column(columnDefinition = "TEXT")
    private String orgModules; // JSON array of module statuses reported back by org

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Status         { ACTIVE, EXPIRED, NOT_LICENSED, SUSPENDED }
    public enum DeliveryStatus { PENDING, DELIVERED, ACTIVATED, FAILED }
}
