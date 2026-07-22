package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiKey {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** SHA-256 hash of the raw key — used for lookup/verification */
    @Column(nullable = false, unique = true)
    private String keyHash;

    /** First 20 characters of the raw key — safe to display in the UI */
    @Column(nullable = false, length = 20)
    private String keyPrefix;

    /** JSON array of permission scopes, e.g. ["read:orgs","write:deployments"] */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String scopes;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    private LocalDateTime revokedAt;

    private String revokedBy;

    private LocalDateTime lastUsedAt;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
