package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhooks")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Webhook {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String url;

    /** SHA-256 hash of the raw HMAC signing secret. Never serialized. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String secretHash;

    /** JSON array of event types, e.g. ["DEPLOYMENT_SUCCESS","LICENSE_EXPIRY"] */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String events;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * JSON map of extra HTTP headers sent on delivery — by design this holds third-party
     * Authorization values, so it is write-only: never returned on a read.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String headers;

    private LocalDateTime lastFiredAt;

    /** Last delivery outcome: SUCCESS / FAILED / PENDING */
    private String lastStatus;

    @Column(nullable = false)
    private long fireCount;

    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (fireCount == 0) {
            fireCount = 0L;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
