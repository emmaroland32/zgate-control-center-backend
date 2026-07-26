package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Organization {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    private String contactEmail;
    private String contactName;
    private String country;
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentStatus deploymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentEnv deploymentEnv;

    /** Base URL of the deployed ZGATE instance (for health checks, webhook delivery) */
    private String backendUrl;

    private String deployedVersion;

    /** Machine-to-machine API key hash — used by the ZGATE instance to call back to ControlCenter */
    private String serviceApiKeyHash;

    private UUID partnerId;
    private Integer activeUsers;

    /**
     * Commercial entitlement — the levers that make licensing a real subscription control:
     *   subscriptionValidUntil — paid-through date. The license renewal job re-issues short-lived
     *       licenses only while this is in the future; once it lapses, renewals stop and the org's
     *       license expires → grace → lock (the non-payment kill switch). NULL = unmanaged/perpetual
     *       (never auto-lapsed) so existing orgs are unaffected until a subscription is set.
     *   entitledVersion — highest ZGATE version this org has paid for; stamped as the license
     *       maxVersion so a self-upgrade past it is refused at runtime.
     *   licenseTtlDays — how short-lived each issued/renewed license is (default applied when null).
     */
    private LocalDateTime subscriptionValidUntil;
    private String entitledVersion;
    private Integer licenseTtlDays;

    /**
     * Entitled number of concurrent installs. The instance registry (distinct machine fingerprints
     * reported within a window) is compared to this; more live installs than this = a copied /
     * over-deployed license. NULL = unmanaged (no concurrent-use check).
     */
    private Integer maxInstances;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
    private LocalDateTime lastSeenAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Tier             { FREE, STARTER, STANDARD, ENTERPRISE }
    public enum DeploymentStatus { HEALTHY, DEGRADED, OFFLINE, PROVISIONING, SUSPENDED }
    public enum DeploymentEnv    { LOCAL, STAGING, PRODUCTION }
}
