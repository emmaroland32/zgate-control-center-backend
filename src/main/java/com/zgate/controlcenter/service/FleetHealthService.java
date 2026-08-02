package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.AlertRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The fleet's liveness authority. Telemetry updates {@code Organization.lastSeenAt} on every
 * heartbeat, but until now NOTHING ever moved an org's status when heartbeats stopped — a customer
 * whose deployment died stayed HEALTHY on every screen forever. This sweep closes that loop:
 *
 * <ul>
 *   <li>HEALTHY/DEGRADED org silent past the threshold → OFFLINE + a firing alert;</li>
 *   <li>OFFLINE org heard from again → HEALTHY, alert resolved;</li>
 *   <li>PROVISIONING org heard from → HEALTHY — the apply proved the infrastructure exists,
 *       the first heartbeat proves ZGATE actually booted inside it.</li>
 * </ul>
 *
 * <p>SUSPENDED is operator-owned and never touched. Orgs that have never phoned home
 * ({@code lastSeenAt} null — e.g. air-gapped installs) are out of scope: silence is their normal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FleetHealthService {

    private static final String OFFLINE_ALERT_TITLE = "Deployment offline";

    private final OrganizationRepository orgRepo;
    private final AlertRepository alertRepo;

    @Value("${controlcenter.fleet.health.enabled:true}")
    private boolean enabled;

    /** Minutes of telemetry silence before an org is declared offline. The default heartbeat
     *  interval is 30 s, so 15 min of silence is ~30 missed beats — an outage, not jitter. */
    @Value("${controlcenter.fleet.health.offlineAfterMinutes:15}")
    private int offlineAfterMinutes;

    @Scheduled(fixedDelayString = "${controlcenter.fleet.health.sweepMs:60000}", initialDelay = 45_000)
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "fleetHealthSweep", lockAtMostFor = "PT5M")
    @Transactional
    public void sweep() {
        if (!enabled) return;
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(offlineAfterMinutes);

        for (Organization org : orgRepo.findAll()) {
            if (org.getLastSeenAt() == null) continue;                       // never phoned home
            switch (org.getDeploymentStatus()) {
                case HEALTHY, DEGRADED -> {
                    if (org.getLastSeenAt().isBefore(cutoff)) markOffline(org);
                }
                case OFFLINE -> {
                    if (org.getLastSeenAt().isAfter(cutoff)) markRecovered(org);
                }
                case PROVISIONING -> {
                    if (org.getLastSeenAt().isAfter(cutoff)) {
                        org.setDeploymentStatus(Organization.DeploymentStatus.HEALTHY);
                        orgRepo.save(org);
                        log.info("Org {} is now HEALTHY — first heartbeat after provisioning", org.getSlug());
                    }
                }
                default -> { /* SUSPENDED — operator-owned */ }
            }
        }
    }

    private void markOffline(Organization org) {
        org.setDeploymentStatus(Organization.DeploymentStatus.OFFLINE);
        orgRepo.save(org);
        boolean alreadyFiring = alertRepo
            .findByOrganizationIdAndStatus(org.getId(), Alert.Status.FIRING).stream()
            .anyMatch(a -> OFFLINE_ALERT_TITLE.equals(a.getTitle()));
        if (!alreadyFiring) {
            alertRepo.save(Alert.builder()
                .organizationId(org.getId())
                .status(Alert.Status.FIRING)
                .severity(AlertRule.Severity.CRITICAL)
                .title(OFFLINE_ALERT_TITLE)
                .message("No telemetry from " + org.getName() + " since " + org.getLastSeenAt()
                       + " (threshold " + offlineAfterMinutes + " min). The deployment or its "
                       + "outbound connectivity is down.")
                .build());
        }
        log.warn("Org {} marked OFFLINE — last heartbeat {}", org.getSlug(), org.getLastSeenAt());
    }

    private void markRecovered(Organization org) {
        org.setDeploymentStatus(Organization.DeploymentStatus.HEALTHY);
        orgRepo.save(org);
        alertRepo.findByOrganizationIdAndStatus(org.getId(), Alert.Status.FIRING).stream()
            .filter(a -> OFFLINE_ALERT_TITLE.equals(a.getTitle()))
            .forEach(a -> {
                a.setStatus(Alert.Status.RESOLVED);
                a.setResolvedAt(LocalDateTime.now());
                alertRepo.save(a);
            });
        log.info("Org {} recovered — telemetry resumed", org.getSlug());
    }
}
