package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.CloudCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CloudCredentialRepository extends JpaRepository<CloudCredential, UUID> {

    List<CloudCredential> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<CloudCredential> findByOrganizationIdAndProviderAndEnabledTrue(UUID organizationId, String provider);
}
