package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Deployment;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.PushUpdateRequest;
import com.zgate.controlcenter.repository.DeploymentRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentService {

    private final DeploymentRepository repo;
    private final OrganizationRepository orgRepo;
    private final ReleaseRepository releaseRepo;
    private final com.zgate.controlcenter.repository.InfrastructureStackRepository stackRepo;
    private final FleetRolloutService rolloutService;

    public Page<Deployment> findByOrg(UUID orgId, Pageable pageable) {
        return repo.findByOrganizationId(orgId, pageable);
    }

    public List<Deployment> findAll() {
        return repo.findAll();
    }

    public Deployment findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ControlCenterException("Deployment not found: " + id));
    }

    /**
     * Push a release to a set of organizations.
     *
     * <p>This used to write {@code Deployment} rows and nothing else — the "Push Update" button
     * changed no infrastructure, contacted no instance, and a scheduled push sat PENDING forever
     * because no job ever read {@code scheduledAt}. It now creates a real {@link
     * com.zgate.controlcenter.domain.FleetRollout} over the orgs' provisioned stacks, which the
     * orchestrator executes (honouring the schedule, the canary/wave shape and the entitlement gate).
     *
     * <p>Organizations with no Control-Center-provisioned stack still get a history row marked
     * PENDING: those are customer-run installs, which update by pulling (the update checker tells
     * them a release is entitled) rather than by anything this console can push.
     */
    @Transactional
    public PushResult pushUpdate(PushUpdateRequest req, String pushedBy) {
        var release = releaseRepo.findById(req.getReleaseId())
            .orElseThrow(() -> new ControlCenterException("Release not found"));

        List<UUID> pushable = new java.util.ArrayList<>();
        List<Deployment> records = new java.util.ArrayList<>();

        for (UUID orgId : req.getOrganizationIds()) {
            Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ControlCenterException("Organization not found: " + orgId));

            boolean hasStack = stackRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .anyMatch(st -> st.getLastAppliedAt() != null
                    && st.getStatus() != com.zgate.controlcenter.domain.InfrastructureStack.Status.DESTROYED);
            if (hasStack) {
                pushable.add(orgId);
                continue;   // the rollout owns the history row for these
            }

            // Self-hosted: record the intent so the org page shows what it is entitled to, and be
            // explicit in the log that this one is not something we can push.
            records.add(repo.save(Deployment.builder()
                .organizationId(orgId)
                .releaseId(release.getId())
                .status(Deployment.Status.PENDING)
                .deployedBy(pushedBy)
                .fromVersion(org.getDeployedVersion())
                .toVersion(release.getVersion())
                .scheduledAt(req.getScheduledAt())
                .logs("No Control-Center-provisioned stack for this organization — it updates by "
                    + "pulling its entitled release, not by a push from here.")
                .build()));
        }

        UUID rolloutId = null;
        if (!pushable.isEmpty()) {
            List<UUID> stackIds = pushable.stream()
                .flatMap(orgId -> stackRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream())
                .filter(st -> st.getLastAppliedAt() != null
                    && st.getStatus() != com.zgate.controlcenter.domain.InfrastructureStack.Status.DESTROYED)
                .map(com.zgate.controlcenter.domain.InfrastructureStack::getId)
                .toList();

            var rolloutReq = new FleetRolloutService.CreateRolloutRequest();
            rolloutReq.setReleaseId(release.getId());
            rolloutReq.setStackIds(stackIds);
            rolloutReq.setScheduledFor(req.getScheduledAt());
            // A push from this screen is an explicit operator action on a chosen set, so it applies
            // unattended; the canary shape still limits the blast radius of a bad release.
            rolloutReq.setAutoApply(true);
            var rollout = rolloutService.create(rolloutReq, pushedBy);
            rolloutId = rollout.getId();
        }

        log.info("Push update: release {} to {} org(s) — {} via rollout {}, {} self-hosted",
                 release.getVersion(), req.getOrganizationIds().size(), pushable.size(),
                 rolloutId, records.size());
        return new PushResult(rolloutId, records, pushable.size(), records.size());
    }

    /**
     * @param rolloutId       the rollout executing the push, or null when every target is self-hosted
     * @param selfHostedCount organizations recorded but NOT pushed (no provisioned stack)
     */
    public record PushResult(UUID rolloutId, List<Deployment> deployments,
                             int rolloutStackCount, int selfHostedCount) {}

    public Deployment updateStatus(UUID id, Deployment.Status status, String logs) {
        Deployment dep = findById(id);
        dep.setStatus(status);
        if (logs != null) dep.setLogs(logs);
        if (status == Deployment.Status.IN_PROGRESS && dep.getStartedAt() == null) {
            dep.setStartedAt(LocalDateTime.now());
        }
        if (status == Deployment.Status.SUCCESS || status == Deployment.Status.FAILED) {
            dep.setCompletedAt(LocalDateTime.now());
            // Update org version and status
            orgRepo.findById(dep.getOrganizationId()).ifPresent(org -> {
                if (status == Deployment.Status.SUCCESS) {
                    org.setDeployedVersion(dep.getToVersion());
                    org.setDeploymentStatus(Organization.DeploymentStatus.HEALTHY);
                } else {
                    org.setDeploymentStatus(Organization.DeploymentStatus.DEGRADED);
                }
                orgRepo.save(org);
            });
        }
        return repo.save(dep);
    }

    @Transactional
    public Deployment rollback(UUID id, String rolledBackBy) {
        Deployment original = findById(id);
        if (original.getFromVersion() == null) {
            throw new ControlCenterException("Cannot rollback: no previous version recorded");
        }
        Deployment rollback = repo.save(Deployment.builder()
            .organizationId(original.getOrganizationId())
            .status(Deployment.Status.IN_PROGRESS)
            .deployedBy(rolledBackBy)
            .fromVersion(original.getToVersion())
            .toVersion(original.getFromVersion())
            .startedAt(LocalDateTime.now())
            .build());

        original.setStatus(Deployment.Status.ROLLED_BACK);
        repo.save(original);

        // Update org to reflect rollback version
        orgRepo.findById(original.getOrganizationId()).ifPresent(org -> {
            org.setDeployedVersion(original.getFromVersion());
            org.setDeploymentStatus(Organization.DeploymentStatus.DEGRADED);
            orgRepo.save(org);
        });

        return rollback;
    }

    public DeploymentStats getStats() {
        return new DeploymentStats(
            repo.count(),
            repo.countByStatus(Deployment.Status.IN_PROGRESS),
            repo.countByStatus(Deployment.Status.FAILED),
            repo.countByStatus(Deployment.Status.SUCCESS)
        );
    }

    public record DeploymentStats(long total, long inProgress, long failed, long successful) {}
}
