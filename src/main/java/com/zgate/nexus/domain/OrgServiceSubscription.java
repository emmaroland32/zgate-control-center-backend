package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "org_service_subscriptions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "service_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrgServiceSubscription {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private UUID serviceId;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Monthly call cap; null = unlimited */
    private Long callLimit;

    /** Hashed API key sent by ZGATE instance in X-Nexus-Api-Key header */
    private String apiKeyHash;

    private String enabledBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime enabledAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { enabledAt = LocalDateTime.now(); updatedAt = enabledAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
