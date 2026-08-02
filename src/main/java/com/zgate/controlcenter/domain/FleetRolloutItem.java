package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stack inside one {@link FleetRollout}. Carries the audit chain both ways: the provisioning
 * runs it triggered ({@link #planRunId}/{@link #applyRunId}) and the {@link Deployment} history row
 * it maintains, so the fleet view, the stack view and the org's deployment history all agree.
 */
@Entity
@Table(name = "fleet_rollout_items",
       uniqueConstraints = @UniqueConstraint(name = "uq_fleet_rollout_stack",
               columnNames = {"rollout_id", "stack_id"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FleetRolloutItem {

    @Id @UuidGenerator
    private UUID id;

    @Column(name = "rollout_id", nullable = false)
    private UUID rolloutId;

    @Column(name = "stack_id", nullable = false)
    private UUID stackId;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private int wave;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(length = 40)
    private String fromVersion;

    @Column(nullable = false, length = 40)
    private String toVersion;

    private UUID planRunId;
    private UUID applyRunId;
    private UUID deploymentId;

    private LocalDateTime appliedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Status {
        PENDING,
        /** Upgrade plan queued or running. */
        PLANNING,
        /** Plan succeeded; awaiting apply (operator, or the orchestrator when autoApply). */
        PLANNED,
        APPLYING,
        /** Applied; inside the soak window awaiting a healthy heartbeat on the target version. */
        SOAKING,
        SUCCEEDED,
        /** Plan or apply failed — halts the rollout. */
        FAILED,
        /** Refused up front: lapsed subscription, beyond entitlement, or the stack is not upgradable. */
        SKIPPED
    }

    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.SKIPPED;
    }
}
