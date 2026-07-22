package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.License;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LicenseRepository extends JpaRepository<License, UUID> {
    List<License> findByOrganizationId(UUID orgId);
    Optional<License> findFirstByOrganizationIdAndModuleNameOrderByActivatedAtDesc(UUID orgId, String moduleName);
    long countByStatus(License.Status status);

    @Query("SELECT l FROM License l WHERE l.status = 'ACTIVE' AND l.expiresAt IS NOT NULL AND l.expiresAt <= :cutoff")
    List<License> findExpiringSoon(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Query(value = "SELECT * FROM licenses WHERE status = 'ACTIVE' AND expires_at < NOW() + (CAST(:days AS INTEGER) * INTERVAL '1 day')",
           nativeQuery = true)
    List<License> findExpiringWithinDays(@Param("days") int days);

    List<License> findByOrganizationIdAndDeliveryStatusNot(UUID orgId, License.DeliveryStatus status);
}
