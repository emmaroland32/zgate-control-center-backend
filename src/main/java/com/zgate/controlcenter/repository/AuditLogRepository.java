package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByOrganizationId(UUID orgId, Pageable pageable);
    Page<AuditLog> findByActorEmailContainingIgnoreCase(String email, Pageable pageable);

    /**
     * General audit search. Every filter is optional (NULL = no constraint). {@code action} and
     * {@code actor} are case-insensitive substring matches; {@code status} and {@code entityType}
     * are exact. Ordering is baked in — do not pass a Sort (see AuditController).
     */
    @Query(value = """
        SELECT * FROM audit_logs a
        WHERE (CAST(:orgId AS uuid) IS NULL OR a.organization_id = CAST(:orgId AS uuid))
          AND (CAST(:action AS varchar) IS NULL OR a.action LIKE '%' || CAST(:action AS varchar) || '%')
          AND (CAST(:actor AS varchar) IS NULL OR LOWER(a.actor_email) LIKE '%' || LOWER(CAST(:actor AS varchar)) || '%')
          AND (CAST(:status AS varchar) IS NULL OR a.status = CAST(:status AS varchar))
          AND (CAST(:entityType AS varchar) IS NULL OR a.entity_type = CAST(:entityType AS varchar))
          AND (CAST(:fromDate AS timestamp) IS NULL OR a.created_at >= CAST(:fromDate AS timestamp))
          AND (CAST(:toDate AS timestamp) IS NULL OR a.created_at <= CAST(:toDate AS timestamp))
        ORDER BY a.created_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM audit_logs a
        WHERE (CAST(:orgId AS uuid) IS NULL OR a.organization_id = CAST(:orgId AS uuid))
          AND (CAST(:action AS varchar) IS NULL OR a.action LIKE '%' || CAST(:action AS varchar) || '%')
          AND (CAST(:actor AS varchar) IS NULL OR LOWER(a.actor_email) LIKE '%' || LOWER(CAST(:actor AS varchar)) || '%')
          AND (CAST(:status AS varchar) IS NULL OR a.status = CAST(:status AS varchar))
          AND (CAST(:entityType AS varchar) IS NULL OR a.entity_type = CAST(:entityType AS varchar))
          AND (CAST(:fromDate AS timestamp) IS NULL OR a.created_at >= CAST(:fromDate AS timestamp))
          AND (CAST(:toDate AS timestamp) IS NULL OR a.created_at <= CAST(:toDate AS timestamp))
        """,
        nativeQuery = true)
    Page<AuditLog> search(
            @Param("orgId") UUID orgId,
            @Param("action") String action,
            @Param("actor") String actor,
            @Param("status") String status,
            @Param("entityType") String entityType,
            @Param("fromDate") LocalDateTime from,
            @Param("toDate") LocalDateTime to,
            Pageable pageable);

    /**
     * Everything one operator DID (they are the actor) plus everything done TO their account
     * (the account is the entity — identity events are keyed by the user's id, sign-in events by
     * the attempted email, because a failed sign-in has no id to point at).
     */
    @Query(value = """
        SELECT * FROM audit_logs a
        WHERE LOWER(a.actor_email) = LOWER(CAST(:email AS varchar))
           OR (a.entity_type = 'ControlCenterUser'
               AND (a.entity_id = CAST(:id AS varchar) OR LOWER(a.entity_id) = LOWER(CAST(:email AS varchar))))
        ORDER BY a.created_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM audit_logs a
        WHERE LOWER(a.actor_email) = LOWER(CAST(:email AS varchar))
           OR (a.entity_type = 'ControlCenterUser'
               AND (a.entity_id = CAST(:id AS varchar) OR LOWER(a.entity_id) = LOWER(CAST(:email AS varchar))))
        """,
        nativeQuery = true)
    Page<AuditLog> findOperatorActivity(@Param("email") String email,
                                        @Param("id") String id,
                                        Pageable pageable);

    /**
     * Retention for sign-in noise: failed attempts against addresses that are not operator
     * accounts (typos, probes, third parties) older than the cut-off. Rows about real operators
     * are never touched here.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = """
        DELETE FROM audit_logs a
        WHERE a.action IN (:actions)
          AND a.created_at < :before
          AND NOT EXISTS (SELECT 1 FROM control_center_users u WHERE LOWER(u.email) = LOWER(a.actor_email))
        """, nativeQuery = true)
    int purgeSignInNoise(@Param("actions") Collection<String> actions, @Param("before") LocalDateTime before);

    long countByActionInAndCreatedAtAfter(Collection<String> actions, LocalDateTime since);

    long countByStatusAndCreatedAtAfter(AuditLog.Status status, LocalDateTime since);

    long countByEntityTypeAndCreatedAtAfter(String entityType, LocalDateTime since);

    @Query("select count(distinct a.actorEmail) from AuditLog a where a.createdAt > :since and a.status = :status")
    long countDistinctActorsSince(@Param("since") LocalDateTime since, @Param("status") AuditLog.Status status);

    @Query("select a from AuditLog a where a.action in :actions and a.createdAt > :since order by a.createdAt desc")
    List<AuditLog> findRecentByActions(@Param("actions") Collection<String> actions,
                                       @Param("since") LocalDateTime since, Pageable pageable);
}
