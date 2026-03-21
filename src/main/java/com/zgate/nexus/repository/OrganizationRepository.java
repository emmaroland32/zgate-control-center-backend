package com.zgate.nexus.repository;

import com.zgate.nexus.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findBySlug(String slug);
    List<Organization> findByPartnerId(UUID partnerId);
    List<Organization> findByDeploymentStatus(Organization.DeploymentStatus status);
    long countByDeploymentEnv(Organization.DeploymentEnv env);
    long countByDeploymentStatus(Organization.DeploymentStatus status);

    @Query("SELECT o FROM Organization o WHERE o.serviceApiKeyHash = :keyHash")
    Optional<Organization> findByServiceApiKeyHash(String keyHash);
}
