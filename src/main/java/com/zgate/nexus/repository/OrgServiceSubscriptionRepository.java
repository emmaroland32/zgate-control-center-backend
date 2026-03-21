package com.zgate.nexus.repository;

import com.zgate.nexus.domain.OrgServiceSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgServiceSubscriptionRepository extends JpaRepository<OrgServiceSubscription, UUID> {
    List<OrgServiceSubscription> findByOrganizationId(UUID orgId);
    List<OrgServiceSubscription> findByOrganizationIdAndEnabled(UUID orgId, boolean enabled);
    Optional<OrgServiceSubscription> findByOrganizationIdAndServiceId(UUID orgId, UUID serviceId);
    boolean existsByOrganizationIdAndServiceId(UUID orgId, UUID serviceId);
    List<OrgServiceSubscription> findByServiceId(UUID serviceId);
}
