package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ServiceUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceUsageRepository extends JpaRepository<ServiceUsage, UUID> {
    List<ServiceUsage> findByOrganizationId(UUID orgId);
    List<ServiceUsage> findByOrganizationIdAndPeriodStartBetween(UUID orgId, LocalDate from, LocalDate to);
    Optional<ServiceUsage> findByOrganizationIdAndServiceIdAndPeriodStart(UUID orgId, UUID serviceId, LocalDate period);

    /**
     * Cost of every usage bucket that OVERLAPS the window. Usage rows are calendar-month buckets,
     * so the old containment test ({@code periodEnd <= :to}) excluded the current month until its
     * last day and every "current month cost" read 0 for 29 days out of 30.
     */
    @Query("SELECT COALESCE(SUM(u.costUsd), 0) FROM ServiceUsage u " +
           "WHERE u.organizationId = :orgId AND u.periodStart <= :to AND u.periodEnd >= :from")
    BigDecimal sumCostByOrgAndPeriod(@Param("orgId") UUID orgId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(u.callCount), 0) FROM ServiceUsage u " +
           "WHERE u.organizationId = :orgId AND u.periodStart >= :from")
    Long sumCallsByOrgSince(@Param("orgId") UUID orgId,
                            @Param("from") LocalDate from);

    List<ServiceUsage> findByServiceId(UUID serviceId);
}
