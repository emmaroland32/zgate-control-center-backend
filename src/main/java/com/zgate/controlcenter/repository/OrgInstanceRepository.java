package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.OrgInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgInstanceRepository extends JpaRepository<OrgInstance, UUID> {

    Optional<OrgInstance> findByOrganizationIdAndFingerprintAndNodeId(
            UUID organizationId, String fingerprint, String nodeId);

    List<OrgInstance> findByOrganizationIdAndLastSeenAtAfter(UUID organizationId, LocalDateTime since);

    /** Every node reporting since a cutoff — the fleet's live resource picture. */
    List<OrgInstance> findByLastSeenAtAfter(LocalDateTime since);

    /** Distinct deployments/environments (fingerprints) live since {@code since} — the failover axis. */
    @Query("SELECT COUNT(DISTINCT i.fingerprint) FROM OrgInstance i " +
           "WHERE i.organizationId = :orgId AND i.lastSeenAt > :since")
    long countDistinctFingerprints(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    /** The largest replica count of any single deployment (nodes sharing one fingerprint) — the HA axis. */
    @Query(value = "SELECT COALESCE(MAX(c), 0) FROM (" +
                   "  SELECT COUNT(DISTINCT node_id) AS c FROM org_instances " +
                   "  WHERE organization_id = :orgId AND last_seen_at > :since GROUP BY fingerprint" +
                   ") t", nativeQuery = true)
    long maxNodesPerFingerprint(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);

    /** Whether any live node reports an orchestrated platform (kubernetes/ecs) since {@code since}. */
    @Query("SELECT COUNT(i) > 0 FROM OrgInstance i WHERE i.organizationId = :orgId " +
           "AND i.lastSeenAt > :since AND LOWER(i.platform) IN ('kubernetes','ecs')")
    boolean hasOrchestratedNode(@Param("orgId") UUID orgId, @Param("since") LocalDateTime since);
}
