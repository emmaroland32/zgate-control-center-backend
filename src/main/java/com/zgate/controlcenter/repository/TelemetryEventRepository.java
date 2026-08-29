package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.TelemetryEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, UUID> {

    /**
     * Delete events received before the cutoff. Backs the retention sweep — this table holds customer
     * production error text (messages, stack traces, context) and must not grow unbounded / retain
     * that data indefinitely. Returns the number of rows removed.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM TelemetryEvent t WHERE t.receivedAt < :cutoff")
    int deleteByReceivedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    Page<TelemetryEvent> findByOrganizationId(UUID orgId, Pageable pageable);

    Page<TelemetryEvent> findByLevel(TelemetryEvent.Level level, Pageable pageable);

    Page<TelemetryEvent> findByOrganizationIdAndLevel(UUID orgId, TelemetryEvent.Level level, Pageable pageable);

    long countByLevelAndAcknowledgedFalse(TelemetryEvent.Level level);

    /** Window counts behind the error-rate metric. */
    long countByOccurredAtAfter(LocalDateTime since);

    long countByLevelAndOccurredAtAfter(TelemetryEvent.Level level, LocalDateTime since);

    long countByOrganizationIdAndLevelAndAcknowledgedFalse(UUID orgId, TelemetryEvent.Level level);

    @Query(value = """
        SELECT * FROM telemetry_events t
        WHERE (CAST(:orgId AS uuid) IS NULL OR t.organization_id = CAST(:orgId AS uuid))
          AND (CAST(:level AS varchar) IS NULL OR t.level = CAST(:level AS varchar))
          AND (CAST(:category AS varchar) IS NULL OR t.category = CAST(:category AS varchar))
          AND (CAST(:fromDate AS timestamp) IS NULL OR t.occurred_at >= CAST(:fromDate AS timestamp))
          AND (CAST(:toDate AS timestamp) IS NULL OR t.occurred_at <= CAST(:toDate AS timestamp))
          AND (CAST(:acknowledged AS boolean) IS NULL OR t.acknowledged = CAST(:acknowledged AS boolean))
        ORDER BY t.occurred_at DESC
        """,
        countQuery = """
        SELECT count(*) FROM telemetry_events t
        WHERE (CAST(:orgId AS uuid) IS NULL OR t.organization_id = CAST(:orgId AS uuid))
          AND (CAST(:level AS varchar) IS NULL OR t.level = CAST(:level AS varchar))
          AND (CAST(:category AS varchar) IS NULL OR t.category = CAST(:category AS varchar))
          AND (CAST(:fromDate AS timestamp) IS NULL OR t.occurred_at >= CAST(:fromDate AS timestamp))
          AND (CAST(:toDate AS timestamp) IS NULL OR t.occurred_at <= CAST(:toDate AS timestamp))
          AND (CAST(:acknowledged AS boolean) IS NULL OR t.acknowledged = CAST(:acknowledged AS boolean))
        """,
        nativeQuery = true)
    Page<TelemetryEvent> search(
            @Param("orgId") UUID orgId,
            @Param("level") String level,
            @Param("category") String category,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("acknowledged") Boolean acknowledged,
            Pageable pageable);

    @Query("""
        SELECT t.organizationId, t.level, COUNT(t) as cnt
        FROM TelemetryEvent t
        WHERE t.occurredAt >= :since
        GROUP BY t.organizationId, t.level
        """)
    List<Object[]> countByOrgAndLevelSince(@Param("since") LocalDateTime since);

    List<TelemetryEvent> findTop20ByOrganizationIdAndLevelOrderByOccurredAtDesc(
            UUID orgId, TelemetryEvent.Level level);

    /** Dedupe guard for anomaly detection: has this org already been flagged with this code recently? */
    boolean existsByOrganizationIdAndErrorCodeAndReceivedAtAfter(
            UUID organizationId, String errorCode, LocalDateTime since);

    /** Fleet licensing dashboard: unacknowledged event counts per error code within a category. */
    @Query("SELECT t.errorCode, COUNT(t) FROM TelemetryEvent t " +
           "WHERE t.category = :category AND t.acknowledged = false AND t.errorCode IS NOT NULL " +
           "GROUP BY t.errorCode")
    List<Object[]> countUnacknowledgedByCode(@Param("category") TelemetryEvent.Category category);

    /** How many distinct orgs have an open anomaly in this category. */
    @Query("SELECT COUNT(DISTINCT t.organizationId) FROM TelemetryEvent t " +
           "WHERE t.category = :category AND t.acknowledged = false")
    long countDistinctAffectedOrgs(@Param("category") TelemetryEvent.Category category);
}
