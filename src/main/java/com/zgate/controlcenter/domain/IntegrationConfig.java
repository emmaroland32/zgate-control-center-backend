package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "integration_configs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class IntegrationConfig {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;  // e.g. "slack", "jira", "email", "teams"

    @Column(nullable = false)
    private String name;

    private String description;
    private String category; // "communication", "project_management", "monitoring"
    private String iconUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(columnDefinition = "TEXT")
    private String configJson; // JSON object with provider-specific settings

    private String configuredBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
