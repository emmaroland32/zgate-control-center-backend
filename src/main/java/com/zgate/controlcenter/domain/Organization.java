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
