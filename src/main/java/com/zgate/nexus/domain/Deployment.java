package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "deployments")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Deployment {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    private UUID releaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String deployedBy;
    private String fromVersion;
    private String toVersion;

    @Column(columnDefinition = "TEXT")
    private String logs;

    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }

    public enum Status { PENDING, IN_PROGRESS, SUCCESS, FAILED, ROLLED_BACK, CANCELLED }
}
