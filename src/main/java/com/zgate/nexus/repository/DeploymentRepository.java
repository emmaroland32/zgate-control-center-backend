package com.zgate.nexus.repository;

import com.zgate.nexus.domain.Deployment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentRepository extends JpaRepository<Deployment, UUID> {
    Page<Deployment> findByOrganizationId(UUID orgId, Pageable pageable);
    List<Deployment> findByStatusIn(List<Deployment.Status> statuses);
    long countByStatus(Deployment.Status status);
    List<Deployment> findTop5ByOrganizationIdOrderByCreatedAtDesc(UUID orgId);
}
