package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "releases")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Release {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(nullable = false)
    private String dockerTag;

    private String dockerRegistry;

    @Column(columnDefinition = "TEXT")
    private String releaseNotes;

    private boolean hasBreakingChanges;
    private boolean isLatest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    @Column(columnDefinition = "TEXT")
    private String migrations; // JSON array of migration scripts

    private String publishedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime publishedAt;

    @PrePersist void prePersist() { publishedAt = LocalDateTime.now(); }

    public enum Channel { STABLE, LTS, BETA, HOTFIX }

    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
}
