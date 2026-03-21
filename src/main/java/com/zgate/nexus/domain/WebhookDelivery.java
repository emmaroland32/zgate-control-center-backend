package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_deliveries")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookDelivery {

    @Id @UuidGenerator
    private UUID id;

    /** FK to webhooks.id */
    @Column(nullable = false)
    private UUID webhookId;

    @Column(nullable = false)
    private String event;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private Integer statusCode;

    @Column(columnDefinition = "TEXT")
    private String response;

    @Column(nullable = false)
    private LocalDateTime firedAt;

    private Integer durationMs;

    @PrePersist
    void prePersist() {
        if (firedAt == null) {
            firedAt = LocalDateTime.now();
        }
    }
}
