package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A staged upgrade of one release across many stacks: a small canary wave first, then waves of
 * {@link #waveSize}, halting the moment anything fails or a soak check finds an upgraded org
 * silent or on the wrong version.
 *
 * <p>The rollout never touches infrastructure itself — every step goes through
 * {@code ProvisioningService.upgrade(...)} / {@code apply(...)}, so each stack keeps its stored
 * spec (only the image block changes), every action lands in the {@code provisioning_runs} audit
 * trail, and the entitlement gate is enforced per organization exactly as for a manual upgrade.
 */
@Entity
@Table(name = "fleet_rollouts")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FleetRollout {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID releaseId;

    @Column(nullable = false, length = 40)
    private String releaseVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;

    @Column(nullable = false)
    @Builder.Default
    private int canarySize = 1;

    @Column(nullable = false)
    @Builder.Default
    private int waveSize = 5;

    /**
     * true = each wave plans AND applies unattended, then soaks.
     * false = each stack stops at PLANNED and an operator applies it from the stack screen; the
     * rollout advances as those applies succeed.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean autoApply = false;

    /**
     * Minutes after a wave's last apply before the wave is declared good. During the soak every
     * upgraded org must heartbeat on the target version; silence or a wrong version pauses the
     * rollout. 0 skips verification (advance immediately on apply success).
     */
    @Column(nullable = false)
    @Builder.Default
    private int soakMinutes = 15;

    /** Why the rollout is PAUSED or FAILED — the headline, not the per-item detail. */
    @Column(columnDefinition = "TEXT")
    private String statusReason;

    /**
     * Maker-checker: when {@code controlcenter.fleet.rollout.requireApproval} is on, a rollout is
     * created PENDING and the orchestrator refuses to start it until a DIFFERENT operator approves.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    @Column(length = 120)
    private String approvedBy;

    private LocalDateTime approvedAt;

    @Column(nullable = false)
    @Builder.Default
    private int currentWave = 0;

    @Column(nullable = false, length = 120)
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum ApprovalStatus { PENDING, APPROVED }

    public enum Status {
        /** Created; the orchestrator has not started wave 0 yet. */
        PENDING,
        IN_PROGRESS,
        /** Halted — by an operator, a failure, or a failed soak check. Resumable. */
        PAUSED,
        COMPLETED,
        /** Cancelled or abandoned after failure. Terminal. */
        FAILED,
        CANCELLED
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
    }
}
