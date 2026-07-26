package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.TelemetryEvent;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.TelemetryEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class TelemetryService {

    private final TelemetryEventRepository repo;
    private final OrganizationRepository orgRepo;
    private final AnomalyDetectionService anomalyDetection;

    /**
     * Called by org instances. Accepts a batch of events in one call to reduce
     * network overhead. Also updates org.lastSeenAt as a heartbeat side-effect.
     */
    public List<TelemetryEvent> ingest(UUID orgId, List<TelemetryEvent> events) {
        return ingest(orgId, events, null, null, null);
    }

    @Transactional
    public List<TelemetryEvent> ingest(UUID orgId, List<TelemetryEvent> events,
                                       String reportedFingerprint, String nodeId, String platform) {
        var org = orgRepo.findById(orgId)
            .orElseThrow(() -> new ControlCenterException("Organization not found: " + orgId));

        events.forEach(e -> {
            e.setOrganizationId(orgId);
            if (e.getOccurredAt() == null) e.setOccurredAt(LocalDateTime.now());
        });

        List<TelemetryEvent> saved = repo.saveAll(events);

        // Heartbeat — update lastSeenAt whenever the org ships any telemetry
        org.setLastSeenAt(LocalDateTime.now());
        orgRepo.save(org);

        // Commercial-enforcement radar: flag over-version / running-while-unentitled. Best-effort —
        // a detection failure must never reject the org's telemetry.
        try {
            anomalyDetection.inspect(org, events, reportedFingerprint, nodeId, platform);
        } catch (Exception ex) {
            log.warn("Anomaly detection failed for org {}: {}", orgId, ex.getMessage());
        }

        return saved;
    }

    public Page<TelemetryEvent> search(UUID orgId,
                                       TelemetryEvent.Level level,
                                       TelemetryEvent.Category category,
                                       LocalDateTime from,
                                       LocalDateTime to,
                                       Boolean acknowledged,
                                       int page, int size) {
        return repo.search(orgId,
                level != null ? level.name() : null,
                category != null ? category.name() : null,
                from, to, acknowledged,
                PageRequest.of(page, size));
    }

    public TelemetryStats getStats() {
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        long errors   = repo.countByLevelAndAcknowledgedFalse(TelemetryEvent.Level.ERROR);
        long warnings = repo.countByLevelAndAcknowledgedFalse(TelemetryEvent.Level.WARNING);
        long errorsToday = repo.search(null, TelemetryEvent.Level.ERROR.name(), null,
                last24h, null, null, PageRequest.of(0, 1)).getTotalElements();
        return new TelemetryStats(errors, warnings, errorsToday);
    }

    public TelemetryStats getStatsByOrg(UUID orgId) {
        long errors   = repo.countByOrganizationIdAndLevelAndAcknowledgedFalse(orgId, TelemetryEvent.Level.ERROR);
        long warnings = repo.countByOrganizationIdAndLevelAndAcknowledgedFalse(orgId, TelemetryEvent.Level.WARNING);
        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        long errorsToday = repo.search(orgId, TelemetryEvent.Level.ERROR.name(), null,
                last24h, null, null, PageRequest.of(0, 1)).getTotalElements();
        return new TelemetryStats(errors, warnings, errorsToday);
    }

    @Transactional
    public TelemetryEvent acknowledge(UUID id, String acknowledgedBy) {
        TelemetryEvent event = repo.findById(id)
            .orElseThrow(() -> new ControlCenterException("Telemetry event not found: " + id));
        event.setAcknowledged(true);
        event.setAcknowledgedBy(acknowledgedBy);
        event.setAcknowledgedAt(LocalDateTime.now());
        return repo.save(event);
    }

    @Transactional
    public void acknowledgeAll(UUID orgId, TelemetryEvent.Level level, String acknowledgedBy) {
        String levelName = level != null ? level.name() : null;
        int pageNum = 0;
        Page<TelemetryEvent> page;
        do {
            page = repo.search(orgId, levelName, null, null, null, false,
                    PageRequest.of(pageNum, 500));
            LocalDateTime now = LocalDateTime.now();
            page.getContent().forEach(e -> {
                e.setAcknowledged(true);
                e.setAcknowledgedBy(acknowledgedBy);
                e.setAcknowledgedAt(now);
            });
            repo.saveAll(page.getContent());
            pageNum++;
        } while (page.hasNext());
    }

    public record TelemetryStats(long unacknowledgedErrors, long unacknowledgedWarnings, long errorsLast24h) {}

    /**
     * Fleet-wide licensing posture for the compliance dashboard: how many open LICENSE anomalies exist,
     * across how many orgs, broken down by type, plus the most recent events.
     */
    public LicenseAnomalySummary licenseAnomalySummary() {
        var cat = TelemetryEvent.Category.LICENSE;
        java.util.Map<String, Long> byCode = new java.util.LinkedHashMap<>();
        long total = 0;
        for (Object[] row : repo.countUnacknowledgedByCode(cat)) {
            long c = ((Number) row[1]).longValue();
            byCode.put((String) row[0], c);
            total += c;
        }
        long affectedOrgs = repo.countDistinctAffectedOrgs(cat);
        List<TelemetryEvent> recent = repo.search(null, null, cat.name(), null, null, false,
                PageRequest.of(0, 100)).getContent();
        return new LicenseAnomalySummary(total, affectedOrgs, byCode, recent);
    }

    public record LicenseAnomalySummary(long total, long affectedOrgs,
                                        java.util.Map<String, Long> byCode, List<TelemetryEvent> recent) {}

    public String exportCsv(UUID orgId, TelemetryEvent.Level level,
                             TelemetryEvent.Category category,
                             LocalDateTime from, LocalDateTime to) {
        Page<TelemetryEvent> page = search(orgId, level, category, from, to, null, 0, 10000);
        StringBuilder sb = new StringBuilder();
        sb.append("Timestamp,Organization,Level,Category,Message,Acknowledged\n");
        for (TelemetryEvent e : page.getContent()) {
            sb.append(e.getOccurredAt()).append(',')
              .append(e.getOrganizationId()).append(',')
              .append(e.getLevel()).append(',')
              .append(e.getCategory()).append(',')
              .append('"').append(e.getMessage() != null ? e.getMessage().replace("\"", "\"\"") : "").append('"').append(',')
              .append(e.isAcknowledged()).append('\n');
        }
        return sb.toString();
    }
}
