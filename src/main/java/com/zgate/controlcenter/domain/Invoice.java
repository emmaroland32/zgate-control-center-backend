package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Invoice {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, unique = true)
    private String invoiceNumber;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /**
     * USAGE — the monthly shared-services + backup invoice.
     * SUBSCRIPTION — a renewal of the platform subscription; marking it PAID extends
     * {@code Organization.subscriptionValidUntil} to this invoice's {@code periodEnd}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 16)
    @Builder.Default
    private Type type = Type.USAGE;

    private LocalDate dueDate;
    private LocalDateTime paidAt;
    private LocalDateTime sentAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private String generatedBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate  void preUpdate()  { updatedAt = LocalDateTime.now(); }

    public enum Status { DRAFT, SENT, PAID, OVERDUE, CANCELLED }
    public enum Type   { USAGE, SUBSCRIPTION }
}
