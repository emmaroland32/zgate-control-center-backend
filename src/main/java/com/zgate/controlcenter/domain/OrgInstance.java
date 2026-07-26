package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One live ZGATE node (pod / task / process) of an organization, keyed on
 * {@code (organizationId, fingerprint, nodeId)}. Distinct fingerprints = separate deployments /
 * environments; distinct node ids under one fingerprint = replicas of one deployment (K8s/ECS scale).
 * Refreshed on each telemetry heartbeat, so counting rows within a window reveals the live topology.
 */
@Entity
@Table(name = "org_instances",
       uniqueConstraints = @UniqueConstraint(name = "uq_org_instance_node",
               columnNames = {"organizationId", "fingerprint", "nodeId"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrgInstance {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    /** Deployment identity (DB/environment) — shared by all replicas of one install. */
    @Column(nullable = false)
    private String fingerprint;

    /** Per-node identity (pod name / container id / process) — distinct per replica. */
    @Column(nullable = false)
    private String nodeId;

    /** Reported orchestrator: bare | docker | kubernetes | ecs. */
    private String platform;

    private String appVersion;

    /** Real runtime metrics reported on the heartbeat (JVM heap MB + process uptime seconds). */
    private Integer memUsedMb;
    private Integer memMaxMb;
    private Long uptimeSeconds;
    private Integer cpuPct;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @PrePersist void prePersist() {
        if (firstSeenAt == null) firstSeenAt = LocalDateTime.now();
        if (lastSeenAt == null) lastSeenAt = firstSeenAt;
    }
}
