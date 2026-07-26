package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One live ZGATE install of an organization, identified by the machine fingerprint it reports on each
 * telemetry heartbeat. Counting the rows seen within a recent window = how many installs are running
 * for that org; more than the org's entitled {@code maxInstances} means a copied / over-deployed license.
 */
@Entity
@Table(name = "org_instances",
       uniqueConstraints = @UniqueConstraint(name = "uq_org_instance", columnNames = {"organizationId", "fingerprint"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrgInstance {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String fingerprint;

    private String appVersion;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @PrePersist void prePersist() {
        if (firstSeenAt == null) firstSeenAt = LocalDateTime.now();
        if (lastSeenAt == null) lastSeenAt = firstSeenAt;
    }
}
