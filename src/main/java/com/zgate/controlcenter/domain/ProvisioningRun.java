package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One terraform invocation: who ran what, against which stack, and the whole log.
 *
 * <p>This is the audit trail for infrastructure changes to a regulated financial system, so rows are
 * append-only — never edited after they reach a terminal status, never deleted.
 *
 * <p>The log is safe to store and display: every secret in the Terraform configuration is declared
 * {@code sensitive}, so Terraform redacts it in its own output. Nothing here re-derives a value.
 */
@Entity
@Table(name = "provisioning_runs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProvisioningRun {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID stackId;

    @Column(nullable = false)
    private UUID organizationId;

    /** Links this infrastructure change back to the {@link Deployment} that triggered it, if any. */
    private UUID deploymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    // Parsed from the plan so the UI can say "12 to add, 3 to change, 1 to destroy"
    // without an operator reading the raw log.
    private Integer resourcesToAdd;
    private Integer resourcesToChange;
    private Integer resourcesToDestroy;

    @Column(columnDefinition = "TEXT")
    private String log;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String planJson;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Integer exitCode;

    @Column(length = 120)
    private String triggeredBy;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }

    public enum Action { PLAN, APPLY, DESTROY, REFRESH }

    public enum Status { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

    /** True when this run proposes changes — the signal for "an update is available". */
    public boolean hasPendingChanges() {
        return (resourcesToAdd != null && resourcesToAdd > 0)
            || (resourcesToChange != null && resourcesToChange > 0)
            || (resourcesToDestroy != null && resourcesToDestroy > 0);
    }
}
