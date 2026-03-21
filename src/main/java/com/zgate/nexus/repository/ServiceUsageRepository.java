package com.zgate.nexus.repository;

import com.zgate.nexus.domain.ServiceUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceUsageRepository extends JpaRepository<ServiceUsage, UUID> {
    List<ServiceUsage> findByOrganizationId(UUID orgId);
    List<ServiceUsage> findByOrganizationIdAndPeriodStartBetween(UUID orgId, LocalDate from, LocalDate to);
    Optional<ServiceUsage> findByOrganizationIdAndServiceIdAndPeriodStart(UUID orgId, UUID serviceId, LocalDate period);

    @Query("SELECT COALESCE(SUM(u.costUsd), 0) FROM ServiceUsage u WHERE u.organizationId = :orgId AND u.periodStart >= :from AND u.periodEnd <= :to")
    BigDecimal sumCostByOrgAndPeriod(UUID orgId, LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(u.callCount), 0) FROM ServiceUsage u WHERE u.organizationId = :orgId AND u.periodStart >= :from")
    Long sumCallsByOrgSince(UUID orgId, LocalDate from);

    List<ServiceUsage> findByServiceId(UUID serviceId);
}
