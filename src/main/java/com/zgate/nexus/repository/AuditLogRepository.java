package com.zgate.nexus.repository;

import com.zgate.nexus.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByOrganizationId(UUID orgId, Pageable pageable);
    Page<AuditLog> findByActorEmailContainingIgnoreCase(String email, Pageable pageable);

    @Query(value = """
        SELECT * FROM audit_logs a
        WHERE (CAST(:orgId AS uuid) IS NULL OR a.organization_id = CAST(:orgId AS uuid))
          AND (CAST(:action AS varchar) IS NULL OR a.action LIKE '%' || CAST(:action AS varchar) || '%')
          AND (CAST(:fromDate AS timestamp) IS NULL OR a.created_at >= CAST(:fromDate AS timestamp))
          AND (CAST(:toDate AS timestamp) IS NULL OR a.created_at <= CAST(:toDate AS timestamp))
        ORDER BY a.created_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM audit_logs a
        WHERE (CAST(:orgId AS uuid) IS NULL OR a.organization_id = CAST(:orgId AS uuid))
          AND (CAST(:action AS varchar) IS NULL OR a.action LIKE '%' || CAST(:action AS varchar) || '%')
          AND (CAST(:fromDate AS timestamp) IS NULL OR a.created_at >= CAST(:fromDate AS timestamp))
          AND (CAST(:toDate AS timestamp) IS NULL OR a.created_at <= CAST(:toDate AS timestamp))
        """,
        nativeQuery = true)
    Page<AuditLog> search(
            @Param("orgId") UUID orgId,
            @Param("action") String action,
            @Param("fromDate") LocalDateTime from,
            @Param("toDate") LocalDateTime to,
            Pageable pageable);
}
