package com.zgate.nexus.repository;

import com.zgate.nexus.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByOrganizationId(UUID orgId, Pageable pageable);
    Page<AuditLog> findByActorEmailContainingIgnoreCase(String email, Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE (:orgId IS NULL OR a.organizationId = :orgId) " +
           "AND (:action IS NULL OR a.action LIKE %:action%) " +
           "AND (:from IS NULL OR a.createdAt >= :from) " +
           "AND (:to IS NULL OR a.createdAt <= :to) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(UUID orgId, String action, LocalDateTime from, LocalDateTime to, Pageable pageable);
}
