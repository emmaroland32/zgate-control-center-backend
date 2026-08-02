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

    /** Machine-to-machine API key hash (SHA-256) — used to authenticate the ZGATE instance's callbacks. */
    private String serviceApiKeyHash;

    /**
     * The raw service API key, surfaced ONCE in the create / regenerate response so the operator can
     * configure it on the install. Never persisted (only the hash is) and null on every other read.
     */
    @Transient
    private String serviceApiKey;

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
     * What a month of the platform subscription costs this org — drives automatic renewal
     * invoicing ahead of {@link #subscriptionValidUntil}. NULL = billed out of band / unmanaged.
     */
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal subscriptionMonthlyFee;

    /**
     * Contractual change window. When set, orchestrator-initiated applies run only inside
     * [start, end) in {@link #maintenanceTimezone} (a window with start > end wraps midnight).
     * NULL = no restriction. Manual operator applies are never gated — an incident fix must not
     * wait for a window.
     */
    private java.time.LocalTime maintenanceWindowStart;
    private java.time.LocalTime maintenanceWindowEnd;
    @Column(length = 64)
    private String maintenanceTimezone;

    /**
     * Entitled number of concurrent installs. The instance registry (distinct machine fingerprints
     * reported within a window) is compared to this; more live installs than this = a copied /
     * over-deployed license. NULL = unmanaged (no concurrent-use check).
     */
    private Integer maxInstances;

    /**
     * Entitled deployment topology — the licensing tier for how the software is run:
     *   SINGLE_NODE       — one process, one environment (base price)
     *   HIGH_AVAILABILITY — orchestrated / horizontally scaled (Kubernetes, ECS, multiple replicas)
     *   MULTI_REGION      — plus failover / DR across separate environments
     * Observed topology beyond the entitled tier raises an anomaly. NULL = unmanaged (no tier check).
     */
    @Enumerated(EnumType.STRING)
    private DeploymentTier deploymentTier;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
    private LocalDateTime lastSeenAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Tier             { FREE, STARTER, STANDARD, ENTERPRISE }
    public enum DeploymentStatus { HEALTHY, DEGRADED, OFFLINE, PROVISIONING, SUSPENDED }
    public enum DeploymentEnv    { LOCAL, STAGING, PRODUCTION }

    /** Licensed deployment topology, cheapest → most expensive. Ordinal order encodes entitlement rank. */
    public enum DeploymentTier   { SINGLE_NODE, HIGH_AVAILABILITY, MULTI_REGION }
}
