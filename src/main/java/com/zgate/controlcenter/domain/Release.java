package com.zgate.controlcenter.domain;

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

    /**
     * The cosign-signed digest of the published backend image ({@code sha256:…}). When present it is
     * rendered into provisioning specs so stacks pull by immutable digest, and the runtime
     * image-digest gate has a value to enforce. Blank means tag-only (digest gate stays advisory).
     */
    @Column(length = 80)
    private String imageDigest;

    @Column(length = 80)
    private String webImageDigest;

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
