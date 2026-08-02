package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ProvisioningRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisioningRunRepository extends JpaRepository<ProvisioningRun, UUID> {

    Page<ProvisioningRun> findByStackIdOrderByCreatedAtDesc(UUID stackId, Pageable pageable);

    List<ProvisioningRun> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<ProvisioningRun> findFirstByStackIdOrderByCreatedAtDesc(UUID stackId);

    /** In-flight runs — used to refuse a second concurrent action on the same stack. */
    List<ProvisioningRun> findByStackIdAndStatusIn(UUID stackId, List<ProvisioningRun.Status> statuses);

    /** Newest successful run of an action since a cutoff — how a rollout notices an operator's manual apply. */
    Optional<ProvisioningRun> findFirstByStackIdAndActionAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            UUID stackId, ProvisioningRun.Action action, ProvisioningRun.Status status,
            java.time.LocalDateTime createdAfter);
}
