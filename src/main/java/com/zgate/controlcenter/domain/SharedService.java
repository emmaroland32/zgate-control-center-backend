package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shared_services")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SharedService {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String provider;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal pricePerCall;

    @Column(nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PricingModel pricingModel = PricingModel.PER_CALL;

    @Column(columnDefinition = "TEXT")
    private String volumeTiers; // JSON array of {from, to, price}

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Category     { IDENTITY, SANCTIONS, KYC, CREDIT, COMMUNICATION }
    public enum PricingModel { PER_CALL, TIERED, FLAT }
}
