package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.FleetRollout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FleetRolloutRepository extends JpaRepository<FleetRollout, UUID> {

    List<FleetRollout> findByStatusIn(List<FleetRollout.Status> statuses);

    List<FleetRollout> findAllByOrderByCreatedAtDesc();
}
