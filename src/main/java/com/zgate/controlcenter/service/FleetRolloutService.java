package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Creates and controls staged fleet rollouts. The actual advancing — planning, applying, soaking,
 * moving between waves — happens in {@link FleetRolloutOrchestrator} on a schedule; this service
 * only writes the rollout's shape and handles operator control actions (pause / resume / cancel).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FleetRolloutService {

    private static final List<FleetRolloutItem.Status> LIVE_ITEM_STATUSES = List.of(
        FleetRolloutItem.Status.PENDING, FleetRolloutItem.Status.PLANNING,
        FleetRolloutItem.Status.PLANNED, FleetRolloutItem.Status.APPLYING,
        FleetRolloutItem.Status.SOAKING);

    private final FleetRolloutRepository rolloutRepo;
    private final FleetRolloutItemRepository itemRepo;
    private final InfrastructureStackRepository stackRepo;
    private final OrganizationRepository orgRepo;
    private final ReleaseRepository releaseRepo;

    /** Maker-checker: when on, a rollout starts only after a DIFFERENT operator approves it. */
    @org.springframework.beans.factory.annotation.Value("${controlcenter.fleet.rollout.requireApproval:false}")
    private boolean requireApproval;

    // ── Reads ───────────────────────────────────────────────────────────────

    public List<FleetRollout> findAll() {
        return rolloutRepo.findAllByOrderByCreatedAtDesc();
    }

    public FleetRollout find(UUID id) {
        return rolloutRepo.findById(id).orElseThrow(() -> new ControlCenterException(
            "Fleet rollout not found: " + id, "ROLLOUT_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public List<FleetRolloutItem> items(UUID rolloutId) {
        find(rolloutId);
        return itemRepo.findByRolloutIdOrderByWaveAscCreatedAtAsc(rolloutId);
    }

    // ── Create ──────────────────────────────────────────────────────────────

    /**
     * Create a rollout over the given stacks (or, when none are named, every upgradable stack).
     *
     * <p>Selection rules, enforced here so the orchestrator never has to think about them:
     * <ul>
     *   <li>only applied, non-destroyed stacks are upgradable;</li>
     *   <li>a stack already on the target release is left out — nothing to do;</li>
     *   <li>a stack already inside a live rollout is refused outright: two rollouts racing the same
     *       stack would interleave plans and applies unpredictably.</li>
     * </ul>
     * Entitlement is deliberately NOT pre-filtered: it is enforced per stack at plan time by
     * {@code ProvisioningService.upgrade}, so a lapsed org shows up as SKIPPED in the rollout —
     * visible — rather than silently missing from it.
     *
     * <p>Wave assignment: the first {@code canarySize} stacks form wave 0, the rest are chunked into
     * waves of {@code waveSize}, ordered by organization slug then environment so the order is
     * deterministic and reviewable before anything runs.
     */
    @Transactional
    public FleetRollout create(CreateRolloutRequest req, String actor) {
        Release release = releaseRepo.findById(req.getReleaseId()).orElseThrow(() ->
            new ControlCenterException("Release not found: " + req.getReleaseId(),
                "RELEASE_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (release.getApprovalStatus() != Release.ApprovalStatus.APPROVED) {
            throw new ControlCenterException(
                "Release " + release.getVersion() + " is not approved for distribution.",
                "RELEASE_NOT_APPROVED", HttpStatus.CONFLICT);
        }

        List<InfrastructureStack> candidates = resolveStacks(req.getStackIds());

        List<InfrastructureStack> targets = candidates.stream()
            .filter(s -> s.getStatus() != InfrastructureStack.Status.DESTROYED
                      && s.getLastAppliedAt() != null
                      && !release.getVersion().equals(s.getReleaseVersion()))
            .sorted(Comparator.comparing(this::orgSlugOf).thenComparing(InfrastructureStack::getEnvironment))
            .toList();

        if (targets.isEmpty()) {
            throw new ControlCenterException(
                "No upgradable stack needs release " + release.getVersion()
                + " — every candidate is unapplied, destroyed, or already on it.",
                "ROLLOUT_NOTHING_TO_DO", HttpStatus.CONFLICT);
        }

        for (InfrastructureStack s : targets) {
            if (itemRepo.existsByStackIdAndStatusIn(s.getId(), LIVE_ITEM_STATUSES)) {
                throw new ControlCenterException(
                    "Stack " + s.getId() + " (" + orgSlugOf(s) + "/" + s.getEnvironment()
                    + ") is already part of a rollout that has not finished.",
                    "STACK_IN_LIVE_ROLLOUT", HttpStatus.CONFLICT);
            }
        }

        int canarySize = req.getCanarySize() == null ? 1 : Math.max(1, req.getCanarySize());
        int waveSize   = req.getWaveSize()   == null ? 5 : Math.max(1, req.getWaveSize());

        FleetRollout rollout = rolloutRepo.save(FleetRollout.builder()
            .releaseId(release.getId())
            .releaseVersion(release.getVersion())
            .status(FleetRollout.Status.PENDING)
            .approvalStatus(requireApproval ? FleetRollout.ApprovalStatus.PENDING
                                            : FleetRollout.ApprovalStatus.APPROVED)
            .canarySize(canarySize)
            .waveSize(waveSize)
            .autoApply(Boolean.TRUE.equals(req.getAutoApply()))
            .soakMinutes(req.getSoakMinutes() == null ? 15 : Math.max(0, req.getSoakMinutes()))
            .createdBy(actor)
            .build());

        int index = 0;
        for (InfrastructureStack s : targets) {
            int wave = index < canarySize ? 0 : 1 + (index - canarySize) / waveSize;
            itemRepo.save(FleetRolloutItem.builder()
                .rolloutId(rollout.getId())
                .stackId(s.getId())
                .organizationId(s.getOrganizationId())
                .wave(wave)
                .status(FleetRolloutItem.Status.PENDING)
                .fromVersion(s.getReleaseVersion())
                .toVersion(release.getVersion())
                .build());
            index++;
        }

        log.info("Fleet rollout {} created: release {} across {} stack(s), canary {}, wave size {}, "
                 + "autoApply={}, by {}", rollout.getId(), release.getVersion(), targets.size(),
                 canarySize, waveSize, rollout.isAutoApply(), actor);
        return rollout;
    }

    // ── Control ─────────────────────────────────────────────────────────────

    /**
     * Second-operator approval. The maker cannot check their own work: the approver must be a
     * different operator than the creator, the same four-eyes rule ZGATE itself applies to
     * financial changes — this button upgrades customers' production systems.
     */
    @Transactional
    public FleetRollout approve(UUID id, String actor) {
        FleetRollout r = find(id);
        if (r.getApprovalStatus() != FleetRollout.ApprovalStatus.PENDING) {
            throw new ControlCenterException("This rollout is not awaiting approval.",
                "ROLLOUT_NOT_PENDING_APPROVAL", HttpStatus.CONFLICT);
        }
        // Fail closed on an unknown creator: a null would otherwise let the maker self-approve.
        if (r.getCreatedBy() == null || r.getCreatedBy().equalsIgnoreCase(actor)) {
            throw new ControlCenterException(
                "A rollout must be approved by a different operator than its creator.",
                "ROLLOUT_SELF_APPROVAL", HttpStatus.FORBIDDEN);
        }
        r.setApprovalStatus(FleetRollout.ApprovalStatus.APPROVED);
        r.setApprovedBy(actor);
        r.setApprovedAt(java.time.LocalDateTime.now());
        log.info("Fleet rollout {} approved by {}", id, actor);
        return rolloutRepo.save(r);
    }

    @Transactional
    public FleetRollout pause(UUID id, String reason, String actor) {
        FleetRollout r = find(id);
        if (r.isTerminal()) {
            throw new ControlCenterException("This rollout has finished.", "ROLLOUT_FINISHED", HttpStatus.CONFLICT);
        }
        r.setStatus(FleetRollout.Status.PAUSED);
        r.setStatusReason(reason == null || reason.isBlank() ? "Paused by " + actor : reason);
        log.info("Fleet rollout {} paused by {}", id, actor);
        return rolloutRepo.save(r);
    }

    /**
     * Resume a paused rollout. FAILED items in the current wave are reset to PENDING — pausing,
     * fixing the underlying problem, and resuming is the retry path.
     */
    @Transactional
    public FleetRollout resume(UUID id, String actor) {
        FleetRollout r = find(id);
        if (r.getStatus() != FleetRollout.Status.PAUSED) {
            throw new ControlCenterException("Only a paused rollout can be resumed.",
                "ROLLOUT_NOT_PAUSED", HttpStatus.CONFLICT);
        }
        for (FleetRolloutItem item : itemRepo.findByRolloutIdAndWave(id, r.getCurrentWave())) {
            if (item.getStatus() == FleetRolloutItem.Status.FAILED) {
                item.setStatus(FleetRolloutItem.Status.PENDING);
                item.setErrorMessage(null);
                item.setPlanRunId(null);
                item.setApplyRunId(null);
                itemRepo.save(item);
            }
        }
        r.setStatus(FleetRollout.Status.IN_PROGRESS);
        r.setStatusReason(null);
        log.info("Fleet rollout {} resumed by {}", id, actor);
        return rolloutRepo.save(r);
    }

    /**
     * Cancel a rollout: no NEW work starts. A plan or apply already handed to the runner finishes on
     * its own (a terraform apply cannot be safely killed mid-flight) and its outcome is still
     * recorded on the item, but the orchestrator stops advancing the moment the status flips.
     */
    @Transactional
    public FleetRollout cancel(UUID id, String actor) {
        FleetRollout r = find(id);
        if (r.isTerminal()) {
            throw new ControlCenterException("This rollout has finished.", "ROLLOUT_FINISHED", HttpStatus.CONFLICT);
        }
        for (FleetRolloutItem item : itemRepo.findByRolloutIdOrderByWaveAscCreatedAtAsc(id)) {
            if (item.getStatus() == FleetRolloutItem.Status.PENDING) {
                item.setStatus(FleetRolloutItem.Status.SKIPPED);
                item.setErrorMessage("Rollout cancelled before this stack was reached.");
                itemRepo.save(item);
            }
        }
        r.setStatus(FleetRollout.Status.CANCELLED);
        r.setStatusReason("Cancelled by " + actor);
        r.setCompletedAt(java.time.LocalDateTime.now());
        log.warn("Fleet rollout {} cancelled by {}", id, actor);
        return rolloutRepo.save(r);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<InfrastructureStack> resolveStacks(List<UUID> stackIds) {
        if (stackIds == null || stackIds.isEmpty()) {
            return stackRepo.findAll();
        }
        List<InfrastructureStack> out = new ArrayList<>();
        for (UUID id : stackIds) {
            out.add(stackRepo.findById(id).orElseThrow(() -> new ControlCenterException(
                "Infrastructure stack not found: " + id, "STACK_NOT_FOUND", HttpStatus.NOT_FOUND)));
        }
        return out;
    }

    private String orgSlugOf(InfrastructureStack s) {
        return orgRepo.findById(s.getOrganizationId()).map(Organization::getSlug)
            .orElse(s.getOrganizationId().toString());
    }

    @Data
    public static class CreateRolloutRequest {
        @NotNull(message = "releaseId is required")
        private UUID releaseId;

        /** Explicit stacks; empty/null means every upgradable stack in the fleet. */
        private List<UUID> stackIds;

        private Integer canarySize;
        private Integer waveSize;
        private Boolean autoApply;
        private Integer soakMinutes;
    }
}
