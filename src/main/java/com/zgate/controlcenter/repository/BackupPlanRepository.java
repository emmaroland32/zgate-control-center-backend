package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.BackupPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BackupPlanRepository extends JpaRepository<BackupPlan, UUID> {
    Optional<BackupPlan> findByOrganizationId(UUID organizationId);
}
