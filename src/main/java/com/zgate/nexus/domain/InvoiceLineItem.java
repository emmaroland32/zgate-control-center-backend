package com.zgate.nexus.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_line_items")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class InvoiceLineItem {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private String description;

    private Long quantity;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    private UUID serviceId;
}
