package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "alert_rules")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AlertRule {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String metric;

    @Column(nullable = false)
    private String operator;

    @Column(nullable = false)
    private Double threshold;

    private Integer evaluationWindowMinutes = 5;
    private Integer cooldownMinutes = 30;

    @Column(columnDefinition = "TEXT")
    private String channels; // JSON array: ["EMAIL","SLACK"]

    @Enumerated(EnumType.STRING)
    private OrgScope orgScope = OrgScope.ALL;

    private UUID orgId;

    @Column(nullable = false)
    private boolean enabled = true;

    private Long triggerCount = 0L;
    private LocalDateTime lastTriggeredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }
    public enum OrgScope { ALL, SPECIFIC }
}
