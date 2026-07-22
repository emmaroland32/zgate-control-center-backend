package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ControlCenterConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ControlCenterConfigRepository extends JpaRepository<ControlCenterConfig, UUID> {
    Optional<ControlCenterConfig> findByConfigKey(String key);
    List<ControlCenterConfig> findByCategory(String category);
}
