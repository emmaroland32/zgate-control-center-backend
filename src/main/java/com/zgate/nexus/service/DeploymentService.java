package com.zgate.nexus.service;

import com.zgate.nexus.domain.Deployment;
import com.zgate.nexus.domain.Organization;
import com.zgate.nexus.exception.NexusException;
import com.zgate.nexus.payload.request.PushUpdateRequest;
import com.zgate.nexus.repository.DeploymentRepository;
import com.zgate.nexus.repository.OrganizationRepository;
import com.zgate.nexus.repository.ReleaseRepository;
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

    public Page<Deployment> findByOrg(UUID orgId, Pageable pageable) {
        return repo.findByOrganizationId(orgId, pageable);
    }

    public List<Deployment> findAll() {
        return repo.findAll();
    }

    public Deployment findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NexusException("Deployment not found: " + id));
    }

    @Transactional
    public List<Deployment> pushUpdate(PushUpdateRequest req, String pushedBy) {
        var release = releaseRepo.findById(req.getReleaseId())
            .orElseThrow(() -> new NexusException("Release not found"));

        return req.getOrganizationIds().stream().map(orgId -> {
            Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new NexusException("Organization not found: " + orgId));

            Deployment dep = repo.save(Deployment.builder()
                .organizationId(orgId)
                .releaseId(release.getId())
                .status(req.getScheduledAt() != null ? Deployment.Status.PENDING : Deployment.Status.IN_PROGRESS)
                .deployedBy(pushedBy)
                .fromVersion(org.getDeployedVersion())
                .toVersion(release.getVersion())
                .scheduledAt(req.getScheduledAt())
                .startedAt(req.getScheduledAt() == null ? LocalDateTime.now() : null)
                .build());

            // Mark org as updating
            org.setDeploymentStatus(Organization.DeploymentStatus.DEGRADED);
            orgRepo.save(org);

            return dep;
        }).toList();
    }

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
            throw new NexusException("Cannot rollback: no previous version recorded");
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
