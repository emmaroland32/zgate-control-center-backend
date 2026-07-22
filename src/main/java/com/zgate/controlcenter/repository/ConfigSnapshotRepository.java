package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ConfigSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ConfigSnapshotRepository extends JpaRepository<ConfigSnapshot, UUID> {
    List<ConfigSnapshot> findAllByOrderByTakenAtDesc();
}
