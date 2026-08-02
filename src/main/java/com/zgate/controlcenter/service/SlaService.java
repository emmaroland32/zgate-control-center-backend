package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.AlertRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Real per-org uptime, computed from evidence instead of the mock numbers the SLA page used to
 * show. The fleet liveness sweep records every outage as a "Deployment offline" alert
 * (firedAt → resolvedAt), so downtime is the sum of those intervals clipped to the window.
 *
 * <p>Honest limits, stated rather than hidden: resolution is the sweep's threshold (an org is
 * "down" only after {@code offlineAfterMinutes} of silence, so brief blips don't count), and the
 * history only reaches back to when the sweep was first deployed. Orgs that never phone home
 * (air-gapped) have no evidence either way and are reported as untracked, not as 100%.
 */
@Service
@RequiredArgsConstructor
public class SlaService {

    private static final String OFFLINE_ALERT_TITLE = "Deployment offline";

    private final OrganizationRepository orgRepo;
    private final AlertRepository alertRepo;

    public List<OrgSla> compute(int windowDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minusDays(Math.max(1, windowDays));
        long windowSeconds = Duration.between(windowStart, now).getSeconds();

        List<OrgSla> out = new ArrayList<>();
        for (Organization org : orgRepo.findAll()) {
            if (org.getLastSeenAt() == null) {
                out.add(new OrgSla(org.getId(), org.getName(), org.getSlug(), false,
                                   null, 0, List.of()));
                continue;
            }

            List<Incident> incidents = new ArrayList<>();
            long downSeconds = 0;
            for (Alert a : alertRepo.findByOrganizationIdAndStatus(org.getId(), Alert.Status.RESOLVED)) {
                if (!OFFLINE_ALERT_TITLE.equals(a.getTitle()) || a.getResolvedAt() == null) continue;
                downSeconds += clip(a.getFiredAt(), a.getResolvedAt(), windowStart, now, incidents);
            }
            // A still-firing outage counts up to "now".
            for (Alert a : alertRepo.findByOrganizationIdAndStatus(org.getId(), Alert.Status.FIRING)) {
                if (!OFFLINE_ALERT_TITLE.equals(a.getTitle())) continue;
                downSeconds += clip(a.getFiredAt(), now, windowStart, now, incidents);
            }
            // Acknowledged outages are still outages.
            for (Alert a : alertRepo.findByOrganizationIdAndStatus(org.getId(), Alert.Status.ACKNOWLEDGED)) {
                if (!OFFLINE_ALERT_TITLE.equals(a.getTitle())) continue;
                downSeconds += clip(a.getFiredAt(), a.getResolvedAt() == null ? now : a.getResolvedAt(),
                                    windowStart, now, incidents);
            }

            double uptimePct = 100.0 * (1.0 - (double) downSeconds / windowSeconds);
            incidents.sort(Comparator.comparing(Incident::startedAt).reversed());
            out.add(new OrgSla(org.getId(), org.getName(), org.getSlug(), true,
                               Math.round(uptimePct * 1000.0) / 1000.0, incidents.size(), incidents));
        }
        out.sort(Comparator.comparing(OrgSla::orgSlug, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    /** Overlap of [firedAt, until] with the window, recorded as an incident when non-zero. */
    private long clip(LocalDateTime firedAt, LocalDateTime until,
                      LocalDateTime windowStart, LocalDateTime windowEnd, List<Incident> incidents) {
        if (firedAt == null || until == null || !until.isAfter(firedAt)) return 0;
        LocalDateTime start = firedAt.isBefore(windowStart) ? windowStart : firedAt;
        LocalDateTime end = until.isAfter(windowEnd) ? windowEnd : until;
        if (!end.isAfter(start)) return 0;
        long seconds = Duration.between(start, end).getSeconds();
        incidents.add(new Incident(start, end, seconds));
        return seconds;
    }

    public record OrgSla(UUID organizationId, String orgName, String orgSlug,
                         boolean tracked, Double uptimePct, int incidentCount,
                         List<Incident> incidents) {}

    public record Incident(LocalDateTime startedAt, LocalDateTime endedAt, long durationSeconds) {}
}
