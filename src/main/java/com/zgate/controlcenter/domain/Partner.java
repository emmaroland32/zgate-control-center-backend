package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "partners")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Partner {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String country;
    private String region;
    private String website;

    @Column(precision = 5, scale = 2)
    private BigDecimal revenueSharePercent;

    private LocalDate contractExpiry;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Tier   { PLATINUM, GOLD, SILVER, BRONZE, RESELLER }
    public enum Status { ACTIVE, SUSPENDED, INACTIVE }
}
