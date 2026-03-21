package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "config_snapshots")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConfigSnapshot {
    @Id @UuidGenerator
    private UUID id;

    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    @Column(length = 500)
    private String note;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "snapshot_data", columnDefinition = "TEXT", nullable = false)
    private String snapshotData;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;

    @PrePersist
    void prePersist() {
        if (takenAt == null) takenAt = LocalDateTime.now();
    }
}
