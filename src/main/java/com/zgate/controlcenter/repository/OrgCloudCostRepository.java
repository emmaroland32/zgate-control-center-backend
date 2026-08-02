package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.OrgCloudCost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgCloudCostRepository extends JpaRepository<OrgCloudCost, UUID> {

    Optional<OrgCloudCost> findByOrganizationIdAndMonthAndSource(UUID orgId, LocalDate month, String source);

    List<OrgCloudCost> findByMonth(LocalDate month);

    List<OrgCloudCost> findByOrganizationIdOrderByMonthDesc(UUID orgId);
}
