package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.OrgInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrgInstanceRepository extends JpaRepository<OrgInstance, UUID> {

    Optional<OrgInstance> findByOrganizationIdAndFingerprint(UUID organizationId, String fingerprint);

    /** Distinct live installs for an org (each row is a distinct fingerprint) seen since {@code since}. */
    long countByOrganizationIdAndLastSeenAtAfter(UUID organizationId, LocalDateTime since);

    List<OrgInstance> findByOrganizationIdAndLastSeenAtAfter(UUID organizationId, LocalDateTime since);
}
