package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.IntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IntegrationConfigRepository extends JpaRepository<IntegrationConfig, UUID> {
    Optional<IntegrationConfig> findByCode(String code);
}
