package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.InfrastructureStack;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Row-locking read for the "is this stack already busy?" check.
     *
     * <p>That check is read-then-write, so a plain {@code findById} makes it a race: under
     * READ COMMITTED two callers both read {@code ACTIVE}, both pass, and both start a Terraform
     * run against the same remote state — corrupting a customer's production stack. Terraform's own
     * DynamoDB lock would catch it, but {@code controlcenter.provisioning.state.lockTable} is blank
     * by default, so nothing downstream does.
     *
     * <p>{@code SELECT ... FOR UPDATE} serialises the check across replicas AND across concurrent
     * requests on one replica. It must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InfrastructureStack s where s.id = :id")
    Optional<InfrastructureStack> findByIdForUpdate(@Param("id") UUID id);
}
