package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import com.zgate.controlcenter.service.provisioning.ProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Advances live fleet rollouts, one tick at a time.
 *
 * <p>Design rules that keep this safe to run against production customer stacks:
 * <ul>
 *   <li><b>Nothing here touches Terraform directly.</b> Every step is a call into
 *       {@link ProvisioningService#upgrade}/{@link ProvisioningService#apply} — the same
 *       entitlement-gated, audited path an operator uses, so a rollout cannot do anything an
 *       operator could not.</li>
 *   <li><b>One wave at a time, halt on failure.</b> A failed plan or apply pauses the whole rollout;
 *       nothing later starts until an operator resumes. The blast radius of a bad release is the
 *       wave, and the canary wave is deliberately small.</li>
 *   <li><b>Idempotent ticks.</b> Every transition re-reads state and moves one step; a crashed or
 *       repeated tick re-derives where it was from the database. {@code STACK_BUSY} is not an error —
 *       the item just waits for the next tick.</li>
 *   <li><b>Soak verification is evidence-based:</b> after a wave applies, each upgraded org must
 *       heartbeat on the target version before the next wave starts. No heartbeat, wrong version →
 *       the rollout pauses and says so. Orgs that never phone home pass with an explicit
 *       "unverified" note rather than blocking forever.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FleetRolloutOrchestrator {

    /** Refusals that mean "this org/stack should sit this rollout out", not "stop the rollout". */
    private static final Set<String> SKIP_CODES = Set.of(
        "SUBSCRIPTION_LAPSED", "VERSION_NOT_ENTITLED", "RELEASE_NOT_APPROVED",
        "STACK_ALREADY_ON_RELEASE", "STACK_NEVER_APPLIED", "STACK_DESTROYED");

    private final FleetRolloutRepository rolloutRepo;
    private final FleetRolloutItemRepository itemRepo;
    private final ProvisioningRunRepository runRepo;
    private final InfrastructureStackRepository stackRepo;
    private final OrganizationRepository orgRepo;
    private final DeploymentRepository deploymentRepo;
    private final ProvisioningService provisioningService;
    private final DeploymentService deploymentService;

    @Value("${controlcenter.fleet.rollout.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${controlcenter.fleet.rollout.tickMs:20000}", initialDelay = 30_000)
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "fleetRolloutTick", lockAtMostFor = "PT10M")
    public void tick() {
        if (!enabled) return;
        List<FleetRollout> live = rolloutRepo.findByStatusIn(
            List.of(FleetRollout.Status.PENDING, FleetRollout.Status.IN_PROGRESS));
        for (FleetRollout rollout : live) {
            try {
                advance(rollout);
            } catch (Exception e) {
                // One broken rollout must not stop the others from advancing.
                log.error("Fleet rollout {} tick failed: {}", rollout.getId(), e.toString());
            }
        }
    }

    /**
     * Deliberately NOT {@code @Transactional} (it would be self-invoked from {@link #tick} and
     * silently ignored anyway): every step below is its own small write, so partial progress
     * survives a crash and the next tick re-derives where it was from the database.
     */
    protected void advance(FleetRollout rollout) {
        // Maker-checker: a rollout awaiting a second operator's approval does not start.
        if (rollout.getApprovalStatus() == FleetRollout.ApprovalStatus.PENDING) return;

        // A scheduled rollout waits for its window. Checked before anything else so a scheduled
        // change cannot be pulled forward by an unrelated tick.
        if (rollout.getScheduledFor() != null && LocalDateTime.now().isBefore(rollout.getScheduledFor())) return;

        if (rollout.getStatus() == FleetRollout.Status.PENDING) {
            rollout.setStatus(FleetRollout.Status.IN_PROGRESS);
            rolloutRepo.save(rollout);
            log.info("Fleet rollout {} started (release {})", rollout.getId(), rollout.getReleaseVersion());
        }

        List<FleetRolloutItem> wave = itemRepo.findByRolloutIdAndWave(rollout.getId(), rollout.getCurrentWave());
        if (wave.isEmpty()) {
            complete(rollout);
            return;
        }

        for (FleetRolloutItem item : wave) {
            // Re-check between items: a failure earlier in this loop pauses the rollout,
            // and nothing after it may start.
            if (rolloutRepo.findById(rollout.getId())
                    .map(r -> r.getStatus() != FleetRollout.Status.IN_PROGRESS).orElse(true)) {
                return;
            }
            switch (item.getStatus()) {
                case PENDING  -> startPlan(rollout, item);
                case PLANNING -> checkPlan(rollout, item);
                case PLANNED  -> startOrDetectApply(rollout, item);
                case APPLYING -> checkApply(rollout, item);
                case SOAKING  -> checkSoak(rollout, item);
                default -> { /* terminal — nothing to do */ }
            }
        }

        boolean waveDone = itemRepo.findByRolloutIdAndWave(rollout.getId(), rollout.getCurrentWave())
            .stream().allMatch(FleetRolloutItem::isTerminal);
        if (waveDone) {
            rollout.setCurrentWave(rollout.getCurrentWave() + 1);
            rolloutRepo.save(rollout);
            log.info("Fleet rollout {} advanced to wave {}", rollout.getId(), rollout.getCurrentWave());
        }
    }

    // ── Steps ───────────────────────────────────────────────────────────────

    private void startPlan(FleetRollout rollout, FleetRolloutItem item) {
        try {
            ProvisioningRun run = provisioningService.upgrade(
                item.getStackId(), rollout.getReleaseId(), "fleet-rollout:" + rollout.getCreatedBy());
            item.setPlanRunId(run.getId());
            item.setStatus(FleetRolloutItem.Status.PLANNING);
            itemRepo.save(item);
        } catch (ControlCenterException e) {
            if ("STACK_BUSY".equals(e.getCode())) return;                 // operator action in flight — wait
            if ("PROVISIONING_UNAVAILABLE".equals(e.getCode())
                    || "PROVISIONING_STATE_NOT_CONFIGURED".equals(e.getCode())) {
                pause(rollout, "Provisioning is unavailable: " + e.getMessage());
                return;
            }
            if (SKIP_CODES.contains(e.getCode())) {
                item.setStatus(FleetRolloutItem.Status.SKIPPED);
                item.setErrorMessage(e.getMessage());
                itemRepo.save(item);
                return;
            }
            fail(rollout, item, "Upgrade plan could not start: " + e.getMessage());
        }
    }

    private void checkPlan(FleetRollout rollout, FleetRolloutItem item) {
        ProvisioningRun run = runRepo.findById(item.getPlanRunId()).orElse(null);
        if (run == null) {
            fail(rollout, item, "Plan run vanished: " + item.getPlanRunId());
            return;
        }
        switch (run.getStatus()) {
            case SUCCESS -> {
                item.setStatus(FleetRolloutItem.Status.PLANNED);
                itemRepo.save(item);
            }
            case FAILED, CANCELLED ->
                fail(rollout, item, "Plan failed: " + nz(run.getErrorMessage(), "see run " + run.getId()));
            default -> { /* QUEUED / RUNNING — wait */ }
        }
    }

    private void startOrDetectApply(FleetRollout rollout, FleetRolloutItem item) {
        if (rollout.isAutoApply()) {
            // Contractual change windows gate only orchestrator-initiated applies; a manual
            // operator apply (the branch below) is a human decision and is never blocked here.
            if (!inMaintenanceWindow(item.getOrganizationId())) return;
            try {
                ProvisioningRun run = provisioningService.apply(
                    item.getStackId(), "fleet-rollout:" + rollout.getCreatedBy());
                item.setApplyRunId(run.getId());
                item.setStatus(FleetRolloutItem.Status.APPLYING);
                item.setDeploymentId(openDeployment(rollout, item).getId());
                itemRepo.save(item);
            } catch (ControlCenterException e) {
                if ("STACK_BUSY".equals(e.getCode())) return;
                fail(rollout, item, "Apply could not start: " + e.getMessage());
            }
            return;
        }

        // Manual mode: the operator applies from the stack screen; we notice the successful APPLY
        // run that is newer than our plan and treat it as this item's apply.
        LocalDateTime planTime = runRepo.findById(item.getPlanRunId())
            .map(ProvisioningRun::getCreatedAt).orElse(item.getCreatedAt());
        runRepo.findFirstByStackIdAndActionAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                item.getStackId(), ProvisioningRun.Action.APPLY, ProvisioningRun.Status.SUCCESS, planTime)
            .ifPresent(applied -> {
                item.setApplyRunId(applied.getId());
                item.setDeploymentId(openDeployment(rollout, item).getId());
                onApplied(rollout, item);
            });
    }

    private void checkApply(FleetRollout rollout, FleetRolloutItem item) {
        ProvisioningRun run = runRepo.findById(item.getApplyRunId()).orElse(null);
        if (run == null) {
            fail(rollout, item, "Apply run vanished: " + item.getApplyRunId());
            return;
        }
        switch (run.getStatus()) {
            case SUCCESS -> onApplied(rollout, item);
            case FAILED, CANCELLED -> {
                closeDeployment(item, Deployment.Status.FAILED,
                    "Apply failed: " + nz(run.getErrorMessage(), ""));
                fail(rollout, item, "Apply failed: " + nz(run.getErrorMessage(), "see run " + run.getId()));
            }
            default -> { /* wait */ }
        }
    }

    private void onApplied(FleetRollout rollout, FleetRolloutItem item) {
        item.setAppliedAt(LocalDateTime.now());
        if (rollout.getSoakMinutes() == 0) {
            succeed(item, null);
        } else {
            item.setStatus(FleetRolloutItem.Status.SOAKING);
            itemRepo.save(item);
        }
    }

    private void checkSoak(FleetRollout rollout, FleetRolloutItem item) {
        LocalDateTime soakEnd = item.getAppliedAt().plusMinutes(rollout.getSoakMinutes());
        if (LocalDateTime.now().isBefore(soakEnd)) return;

        Organization org = orgRepo.findById(item.getOrganizationId()).orElse(null);
        if (org == null) {
            succeed(item, "Organization record missing — soak unverified.");
            return;
        }
        if (org.getLastSeenAt() == null) {
            // This org has never phoned home; telemetry can't confirm anything, and waiting
            // forever would wedge the rollout on a deployment that works fine without it.
            succeed(item, "Org has no telemetry — soak unverified.");
            return;
        }
        boolean heartbeatFresh = org.getLastSeenAt().isAfter(item.getAppliedAt());
        boolean versionMatches = item.getToVersion().equals(org.getDeployedVersion());
        if (heartbeatFresh && versionMatches) {
            succeed(item, null);
            return;
        }
        String why = !heartbeatFresh
            ? "no heartbeat since the apply at " + item.getAppliedAt()
            : "heartbeat reports version " + org.getDeployedVersion() + ", expected " + item.getToVersion();
        pause(rollout, "Soak check failed for " + org.getSlug() + "/"
            + stackRepo.findById(item.getStackId()).map(InfrastructureStack::getEnvironment).orElse("?")
            + ": " + why + ". Fix or investigate, then resume.");
        // The item stays SOAKING: resuming re-runs this check against fresh telemetry.
    }

    /**
     * True when the org has no window (no restriction) or the current time in the org's own
     * timezone falls inside [start, end). A window whose start is after its end wraps midnight
     * (22:00–04:00). A bad timezone string fails OPEN with a warning — a typo in a config field
     * must not silently freeze a customer's upgrades forever.
     */
    private boolean inMaintenanceWindow(java.util.UUID orgId) {
        Organization org = orgRepo.findById(orgId).orElse(null);
        if (org == null || org.getMaintenanceWindowStart() == null
                || org.getMaintenanceWindowEnd() == null) {
            return true;
        }
        java.time.ZoneId zone;
        try {
            zone = org.getMaintenanceTimezone() == null || org.getMaintenanceTimezone().isBlank()
                ? java.time.ZoneId.systemDefault()
                : java.time.ZoneId.of(org.getMaintenanceTimezone());
        } catch (Exception e) {
            log.warn("Org {} has an invalid maintenance timezone '{}' — treating the window as open",
                     org.getSlug(), org.getMaintenanceTimezone());
            return true;
        }
        java.time.LocalTime now = java.time.LocalTime.now(zone);
        java.time.LocalTime start = org.getMaintenanceWindowStart();
        java.time.LocalTime end = org.getMaintenanceWindowEnd();
        return start.isBefore(end)
            ? !now.isBefore(start) && now.isBefore(end)
            : !now.isBefore(start) || now.isBefore(end);   // wraps midnight
    }

    // ── Terminal transitions ────────────────────────────────────────────────

    private void succeed(FleetRolloutItem item, String note) {
        item.setStatus(FleetRolloutItem.Status.SUCCEEDED);
        item.setErrorMessage(note);
        itemRepo.save(item);
        closeDeployment(item, Deployment.Status.SUCCESS, note);
    }

    private void fail(FleetRollout rollout, FleetRolloutItem item, String error) {
        item.setStatus(FleetRolloutItem.Status.FAILED);
        item.setErrorMessage(error);
        itemRepo.save(item);
        pause(rollout, "Stack " + item.getStackId() + " failed: " + error
            + " Fix the cause, then resume — failed items in the current wave are retried.");
    }

    private void pause(FleetRollout rollout, String reason) {
        rollout.setStatus(FleetRollout.Status.PAUSED);
        rollout.setStatusReason(reason);
        rolloutRepo.save(rollout);
        log.warn("Fleet rollout {} paused: {}", rollout.getId(), reason);
    }

    private void complete(FleetRollout rollout) {
        rollout.setStatus(FleetRollout.Status.COMPLETED);
        rollout.setCompletedAt(LocalDateTime.now());
        rolloutRepo.save(rollout);
        log.info("Fleet rollout {} completed (release {})", rollout.getId(), rollout.getReleaseVersion());
    }

    // ── Deployment history rows ─────────────────────────────────────────────

    /**
     * The org's visible deployment history is the {@code deployments} table; keeping it (and, via
     * {@link DeploymentService#updateStatus}, {@code Organization.deployedVersion}) in step is what
     * makes the fleet view, the stack view and the org page tell one story.
     */
    private Deployment openDeployment(FleetRollout rollout, FleetRolloutItem item) {
        return deploymentRepo.save(Deployment.builder()
            .organizationId(item.getOrganizationId())
            .releaseId(rollout.getReleaseId())
            .status(Deployment.Status.IN_PROGRESS)
            .deployedBy("fleet-rollout:" + rollout.getCreatedBy())
            .fromVersion(item.getFromVersion())
            .toVersion(item.getToVersion())
            .startedAt(LocalDateTime.now())
            .build());
    }

    private void closeDeployment(FleetRolloutItem item, Deployment.Status status, String note) {
        if (item.getDeploymentId() == null) return;
        try {
            deploymentService.updateStatus(item.getDeploymentId(), status, note);
        } catch (Exception e) {
            log.warn("Could not close deployment {} for rollout item {}: {}",
                     item.getDeploymentId(), item.getId(), e.getMessage());
        }
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
