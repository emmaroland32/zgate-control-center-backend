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

    /**
     * HMAC-SHA256 over this row's content, signed at write time. NULL means the row predates
     * signing or no key was configured — reported as "unsigned", never as "valid".
     */
    @Column(length = 64)
    private String integrityHash;

    /**
     * Only stamps a timestamp that has not been set. AuditService sets it BEFORE signing, because
     * createdAt is part of the signed content — overwriting it here made every freshly written row
     * fail verification, since the signature covered a different instant than the one stored.
     */
    @PrePersist void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public enum Status { SUCCESS, FAILURE, WARNING }
}
