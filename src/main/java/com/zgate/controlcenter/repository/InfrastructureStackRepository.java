package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.InfrastructureStack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InfrastructureStackRepository extends JpaRepository<InfrastructureStack, UUID> {

    List<InfrastructureStack> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    /** The uniqueness key — matches uq_infra_stack and the Terraform state key. */
    Optional<InfrastructureStack> findByOrganizationIdAndEnvironmentAndTarget(
            UUID organizationId, String environment, InfrastructureStack.Target target);

    List<InfrastructureStack> findByStatus(InfrastructureStack.Status status);

    long countByStatus(InfrastructureStack.Status status);

    List<InfrastructureStack> findByDriftDetectedTrue();
}
