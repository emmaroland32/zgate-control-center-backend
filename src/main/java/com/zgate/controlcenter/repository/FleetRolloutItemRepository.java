package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.FleetRolloutItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FleetRolloutItemRepository extends JpaRepository<FleetRolloutItem, UUID> {

    List<FleetRolloutItem> findByRolloutIdOrderByWaveAscCreatedAtAsc(UUID rolloutId);

    List<FleetRolloutItem> findByRolloutIdAndWave(UUID rolloutId, int wave);

    /** Whether any non-terminal rollout already covers this stack — one rollout per stack at a time. */
    boolean existsByStackIdAndStatusIn(UUID stackId, List<FleetRolloutItem.Status> statuses);
}
