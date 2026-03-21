package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "telemetry_events")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TelemetryEvent {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    private String appVersion;
    private String environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "TEXT")
    private String stackTrace;

    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String context; // JSON

    private String host;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false)
    private boolean acknowledged;

    private String acknowledgedBy;
    private LocalDateTime acknowledgedAt;

    @PrePersist
    void prePersist() {
        receivedAt = LocalDateTime.now();
        if (acknowledged == false) acknowledged = false;
    }

    public enum Level    { ERROR, WARNING, INFO, METRIC }
    public enum Category { SYSTEM, DATABASE, AUTH, API, PERFORMANCE, LICENSE }
}
