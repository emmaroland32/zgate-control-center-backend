package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "alerts")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Alert {

    @Id @UuidGenerator
    private UUID id;

    private UUID ruleId;

    @Column(nullable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertRule.Severity severity;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    private Double metricValue;
    private String acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime firedAt;

    @PrePersist void prePersist() { firedAt = LocalDateTime.now(); }

    public enum Status { FIRING, ACKNOWLEDGED, RESOLVED }
}
