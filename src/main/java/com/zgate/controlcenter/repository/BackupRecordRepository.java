package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.BackupRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BackupRecordRepository extends JpaRepository<BackupRecord, UUID> {

    List<BackupRecord> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Page<BackupRecord> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    Optional<BackupRecord> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("SELECT COALESCE(SUM(b.sizeBytes), 0) FROM BackupRecord b "
         + "WHERE b.organizationId = :orgId AND b.status = :status")
    long sumSizeByStatus(@Param("orgId") UUID orgId, @Param("status") BackupRecord.Status status);

    /** Total ciphertext bytes an org currently occupies (only completed backups count toward quota). */
    default long sumCompletedSizeBytes(UUID orgId) {
        return sumSizeByStatus(orgId, BackupRecord.Status.COMPLETED);
    }

    long countByOrganizationIdAndStatus(UUID organizationId, BackupRecord.Status status);

    /** Completed backups whose retention window has elapsed — due for purge. */
    List<BackupRecord> findByStatusAndExpiresAtBefore(BackupRecord.Status status, LocalDateTime cutoff);

    /** Records stuck in a status since before a cutoff — e.g. INITIATED uploads the agent never finished. */
    List<BackupRecord> findByStatusAndCreatedAtBefore(BackupRecord.Status status, LocalDateTime cutoff);
}
