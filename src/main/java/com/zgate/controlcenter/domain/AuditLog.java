package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditLog {

    @Id @UuidGenerator
    private UUID id;

    private String actor;
    private String actorEmail;

    @Column(nullable = false)
    private String action;

    private String entityType;
    private String entityId;
    private UUID organizationId;
    private String ipAddress;

    @Column(columnDefinition = "TEXT")
    private String details; // JSON

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); }

    public enum Status { SUCCESS, FAILURE, WARNING }
}
