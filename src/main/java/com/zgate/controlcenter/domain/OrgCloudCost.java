package com.zgate.controlcenter.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What one customer's infrastructure cost in one month, as reported by the cloud provider's cost
 * API grouped by the {@code zgate:org-id} tag every provisioned stack applies. Upserted by the
 * nightly sync — the current month's row is rewritten as the provider's number firms up.
 */
@Entity
@Table(name = "org_cloud_costs",
       uniqueConstraints = @UniqueConstraint(name = "uq_org_cloud_cost",
               columnNames = {"organization_id", "month", "source"}))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrgCloudCost {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    /** First day of the covered month. */
    @Column(nullable = false)
    private LocalDate month;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String source = "AWS_CE";

    @Column(nullable = false)
    private LocalDateTime fetchedAt;

    @PrePersist @PreUpdate void stamp() { fetchedAt = LocalDateTime.now(); }
}
