package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.domain.OrgInstance;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.TelemetryEvent;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrgInstanceRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.TelemetryEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the phone-home telemetry stream into commercial-enforcement detection. Every heartbeat batch
 * is checked against the org's entitlement and any violation is raised as a LICENSE-category anomaly
 * event (deduped) and reflected in the org's deployment status — giving the vendor auditable evidence
 * of abuse even for installs that have patched out the client-side gate.
 *
 * <p>Detections:
 * <ul>
 *   <li><b>VERSION_BEYOND_ENTITLEMENT</b> — the reported build is newer than the org's entitledVersion
 *       (self-upgrade past what they paid for).</li>
 *   <li><b>RUNNING_WHILE_UNENTITLED</b> — the org is still phoning home after its subscription lapsed.</li>
 * </ul>
 *
 * <p>Unmanaged orgs (no {@code entitledVersion} / no {@code subscriptionValidUntil}) raise nothing, so
 * existing installs are unaffected. Detection never locks an install — that is the org-side gate's job;
 * this is the vendor-side radar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetectionService {

    private final TelemetryEventRepository telemetryRepo;
    private final OrganizationRepository orgRepo;
    private final LicenseRepository licenseRepo;
    private final OrgInstanceRepository instanceRepo;

    @Value("${controlcenter.anomaly.enabled:true}")
    private boolean enabled;

    /** Don't re-raise the same anomaly for an org more than once per this window. */
    @Value("${controlcenter.anomaly.dedupeWindowHours:12}")
    private int dedupeWindowHours;

    /**
     * How recently a fingerprint must have reported to count as a live install. Kept modest so a DB
     * failover / host move (which changes the fingerprint) ages the old install out quickly rather than
     * lingering as a phantom second instance.
     */
    @Value("${controlcenter.anomaly.instanceWindowHours:6}")
    private int instanceWindowHours;

    /** Version comparison precision for the over-version check: major | minor | exact. */
    @Value("${controlcenter.anomaly.versionGranularity:minor}")
    private String versionGranularity;

    /** @see #inspect(Organization, List, String) — no fingerprint reported. */
    public List<TelemetryEvent> inspect(Organization org, List<TelemetryEvent> events) {
        return inspect(org, events, null);
    }

    /**
     * Inspect a telemetry batch from {@code org} and raise any entitlement anomalies. Also refreshes
     * {@code org.deployedVersion} from the reported build. Returns the anomalies raised (persisted).
     *
     * @param reportedFingerprint the machine fingerprint the instance reported (header), used to detect
     *                            a copied license (a fingerprint that differs from the one the org's
     *                            license is bound to); null when not reported.
     */
    public List<TelemetryEvent> inspect(Organization org, List<TelemetryEvent> events, String reportedFingerprint) {
        if (!enabled || org == null) return List.of();

        LocalDateTime now = LocalDateTime.now();
        String reportedVersion = latestVersion(events);

        // Keep deployedVersion live off the heartbeat (also feeds the dashboard).
        if (reportedVersion != null && !reportedVersion.equals(org.getDeployedVersion())) {
            org.setDeployedVersion(reportedVersion);
            orgRepo.save(org);
        }

        List<TelemetryEvent> raised = new ArrayList<>();

        // 1. Running a build newer than entitled → self-upgrade past the subscription.
        if (reportedVersion != null && org.getEntitledVersion() != null && !org.getEntitledVersion().isBlank()
                && compareVersions(reportedVersion, org.getEntitledVersion(), components()) > 0) {
            raise(org, "VERSION_BEYOND_ENTITLEMENT",
                "Deployment is running " + reportedVersion + " but is only entitled to "
                    + org.getEntitledVersion() + ".", now, raised);
        }

        // 2. Still alive after the subscription lapsed → running unpaid.
        if (org.getSubscriptionValidUntil() != null && org.getSubscriptionValidUntil().isBefore(now)) {
            raise(org, "RUNNING_WHILE_UNENTITLED",
                "Deployment is still active but its subscription lapsed on "
                    + org.getSubscriptionValidUntil() + ".", now, raised);
        }

        // 3. Copied license, two complementary signals:
        if (reportedFingerprint != null && !reportedFingerprint.isBlank()) {
            // (a) Wrong-machine: reported fingerprint != the one the org's license is bound to.
            String bound = boundFingerprint(org.getId());
            if (bound != null && !bound.equals(reportedFingerprint)) {
                raise(org, "FINGERPRINT_MISMATCH",
                    "Reported machine fingerprint does not match the license binding — possible copied license.",
                    now, raised);
            }

            // (b) Too-many-machines: record this install and flag if more distinct installs are live
            // than the org is entitled to. Catches copies even when the license is unbound.
            recordInstance(org.getId(), reportedFingerprint, reportedVersion, now);
            if (org.getMaxInstances() != null) {
                long live = instanceRepo.countByOrganizationIdAndLastSeenAtAfter(
                    org.getId(), now.minusHours(Math.max(1, instanceWindowHours)));
                if (live > org.getMaxInstances()) {
                    raise(org, "MULTIPLE_INSTANCES",
                        live + " live installs detected but only " + org.getMaxInstances()
                            + " entitled — possible copied / over-deployed license.", now, raised);
                }
            }
        }

        // Reflect any fresh anomaly on the dashboard without clobbering a stronger status.
        if (!raised.isEmpty() && org.getDeploymentStatus() == Organization.DeploymentStatus.HEALTHY) {
            org.setDeploymentStatus(Organization.DeploymentStatus.DEGRADED);
            orgRepo.save(org);
        }
        return raised;
    }

    private void raise(Organization org, String code, String message, LocalDateTime now, List<TelemetryEvent> out) {
        LocalDateTime since = now.minusHours(Math.max(1, dedupeWindowHours));
        if (telemetryRepo.existsByOrganizationIdAndErrorCodeAndReceivedAtAfter(org.getId(), code, since)) {
            return; // already flagged recently
        }
        TelemetryEvent ev = TelemetryEvent.builder()
            .organizationId(org.getId())
            .appVersion(org.getDeployedVersion())
            .environment(org.getDeploymentEnv() != null ? org.getDeploymentEnv().name() : null)
            .level(TelemetryEvent.Level.ERROR)
            .category(TelemetryEvent.Category.LICENSE)
            .message(message)
            .errorCode(code)
            .host("control-center")
            .occurredAt(now)
            .build();
        out.add(telemetryRepo.save(ev));
        log.warn("LICENSE ANOMALY [{}] org={}: {}", code, org.getId(), message);
    }

    /** The org's live installs (fingerprints seen within the instance window) — for the CC dashboard. */
    public List<OrgInstance> liveInstances(java.util.UUID orgId) {
        return instanceRepo.findByOrganizationIdAndLastSeenAtAfter(
            orgId, LocalDateTime.now().minusHours(Math.max(1, instanceWindowHours)));
    }

    /** Upsert the reporting install into the instance registry, refreshing its last-seen timestamp. */
    private void recordInstance(java.util.UUID orgId, String fingerprint, String appVersion, LocalDateTime now) {
        OrgInstance inst = instanceRepo.findByOrganizationIdAndFingerprint(orgId, fingerprint)
            .orElseGet(() -> OrgInstance.builder()
                .organizationId(orgId).fingerprint(fingerprint).firstSeenAt(now).build());
        inst.setLastSeenAt(now);
        if (appVersion != null && !appVersion.isBlank()) inst.setAppVersion(appVersion);
        instanceRepo.save(inst);
    }

    /** The fingerprint the org's active license is bound to, or null if unbound / no active license. */
    private String boundFingerprint(java.util.UUID orgId) {
        return licenseRepo.findByOrganizationId(orgId).stream()
            .filter(l -> l.getStatus() == License.Status.ACTIVE)
            .map(License::getFingerprint)
            .filter(fp -> fp != null && !fp.isBlank())
            .findFirst()
            .orElse(null);
    }

    /** The highest version across the batch (an org may report several events per heartbeat). */
    static String latestVersion(List<TelemetryEvent> events) {
        String best = null;
        for (TelemetryEvent e : events) {
            String v = e.getAppVersion();
            if (v == null || v.isBlank()) continue;
            if (best == null || compareVersions(v, best, 0) > 0) best = v;
        }
        return best;
    }

    private int components() {
        String g = versionGranularity == null ? "minor" : versionGranularity.trim().toLowerCase();
        return switch (g) {
            case "major" -> 1;
            case "exact", "patch" -> 0;
            default -> 2;
        };
    }

    /**
     * Compare dotted numeric versions on the first {@code components} parts ({@code 0} = all), ignoring
     * any non-numeric suffix. &gt;0 if {@code a} newer than {@code b}. Unparseable → 0 (never flags).
     */
    static int compareVersions(String a, String b, int components) {
        int[] va = parse(a), vb = parse(b);
        if (va == null || vb == null) return 0;
        int n = components > 0 ? components : Math.max(va.length, vb.length);
        for (int i = 0; i < n; i++) {
            int x = i < va.length ? va[i] : 0;
            int y = i < vb.length ? vb[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null) return null;
        String core = v.trim();
        int cut = core.indexOf('-'); if (cut >= 0) core = core.substring(0, cut);
        cut = core.indexOf('+');     if (cut >= 0) core = core.substring(0, cut);
        if (core.isBlank()) return null;
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
