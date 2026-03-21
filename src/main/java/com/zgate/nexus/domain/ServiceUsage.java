package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "service_usage",
       uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "service_id", "period_start"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ServiceUsage {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private UUID serviceId;

    @Column(nullable = false)
    private Long callCount = 0L;

    private Long successCount = 0L;
    private Long failureCount = 0L;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }
}
