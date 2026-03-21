package com.zgate.nexus.repository;

import com.zgate.nexus.domain.NexusConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NexusConfigRepository extends JpaRepository<NexusConfig, UUID> {
    Optional<NexusConfig> findByConfigKey(String key);
    List<NexusConfig> findByCategory(String category);
}
