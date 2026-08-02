package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import com.zgate.controlcenter.service.provisioning.ProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The rollout state machine is what stands between "ship 2.4.0" and forty customer production
 * systems. These tests pin its safety properties: entitlement refusals skip (never fail) a stack,
 * a busy stack waits, any real failure pauses the whole rollout, soak verification demands a
 * heartbeat on the target version, and waves only advance when everything before them is settled.
 */
class FleetRolloutOrchestratorTest {

    private FleetRolloutRepository rolloutRepo;
    private FleetRolloutItemRepository itemRepo;
    private ProvisioningRunRepository runRepo;
    private InfrastructureStackRepository stackRepo;
    private OrganizationRepository orgRepo;
    private DeploymentRepository deploymentRepo;
    private ProvisioningService provisioning;
    private DeploymentService deployments;
    private FleetRolloutOrchestrator orchestrator;

    private final UUID rolloutId = UUID.randomUUID();
    private final UUID releaseId = UUID.randomUUID();
    private final UUID stackId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private FleetRollout rollout;
    private FleetRolloutItem item;

    @BeforeEach
    void setUp() {
        rolloutRepo = mock(FleetRolloutRepository.class);
        itemRepo = mock(FleetRolloutItemRepository.class);
        runRepo = mock(ProvisioningRunRepository.class);
        stackRepo = mock(InfrastructureStackRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        deploymentRepo = mock(DeploymentRepository.class);
        provisioning = mock(ProvisioningService.class);
        deployments = mock(DeploymentService.class);

        orchestrator = new FleetRolloutOrchestrator(rolloutRepo, itemRepo, runRepo, stackRepo,
                orgRepo, deploymentRepo, provisioning, deployments);
        ReflectionTestUtils.setField(orchestrator, "enabled", true);

        rollout = FleetRollout.builder()
            .id(rolloutId).releaseId(releaseId).releaseVersion("2.4.0")
            .status(FleetRollout.Status.IN_PROGRESS)
            .canarySize(1).waveSize(5).autoApply(true).soakMinutes(15).currentWave(0)
            .createdBy("admin").build();
        item = FleetRolloutItem.builder()
            .id(UUID.randomUUID()).rolloutId(rolloutId).stackId(stackId).organizationId(orgId)
            .wave(0).status(FleetRolloutItem.Status.PENDING)
            .fromVersion("2.3.0").toVersion("2.4.0")
            .createdAt(LocalDateTime.now().minusMinutes(5))
            .build();

        when(rolloutRepo.findById(rolloutId)).thenReturn(Optional.of(rollout));
        when(rolloutRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.findByRolloutIdAndWave(rolloutId, 0)).thenReturn(List.of(item));
        when(deploymentRepo.save(any())).thenAnswer(i -> {
            Deployment d = i.getArgument(0);
            if (d.getId() == null) ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
            return d;
        });
    }

    private ProvisioningRun run(ProvisioningRun.Status status, ProvisioningRun.Action action) {
        ProvisioningRun r = ProvisioningRun.builder().build();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setStatus(status);
        r.setAction(action);
        r.setStackId(stackId);
        return r;
    }

    @Test
    @DisplayName("a PENDING item gets an upgrade plan; the run id is recorded")
    void pendingItemStartsPlan() {
        ProvisioningRun planRun = run(ProvisioningRun.Status.QUEUED, ProvisioningRun.Action.PLAN);
        when(provisioning.upgrade(eq(stackId), eq(releaseId), any())).thenReturn(planRun);

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.PLANNING);
        assertThat(item.getPlanRunId()).isEqualTo(planRun.getId());
    }

    @Test
    @DisplayName("an entitlement refusal SKIPS the stack — it never fails the rollout")
    void entitlementRefusalSkips() {
        when(provisioning.upgrade(eq(stackId), eq(releaseId), any())).thenThrow(
            new ControlCenterException("lapsed", "SUBSCRIPTION_LAPSED", HttpStatus.PAYMENT_REQUIRED));

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.SKIPPED);
        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.IN_PROGRESS);
    }

    @Test
    @DisplayName("a busy stack just waits — STACK_BUSY is not an error")
    void busyStackWaits() {
        when(provisioning.upgrade(eq(stackId), eq(releaseId), any())).thenThrow(
            new ControlCenterException("busy", "STACK_BUSY", HttpStatus.CONFLICT));

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.PENDING);
        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.IN_PROGRESS);
    }

    @Test
    @DisplayName("a failed plan fails the item AND pauses the rollout")
    void failedPlanPausesRollout() {
        item.setStatus(FleetRolloutItem.Status.PLANNING);
        ProvisioningRun failed = run(ProvisioningRun.Status.FAILED, ProvisioningRun.Action.PLAN);
        failed.setErrorMessage("terraform exploded");
        item.setPlanRunId(failed.getId());
        when(runRepo.findById(failed.getId())).thenReturn(Optional.of(failed));

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.FAILED);
        assertThat(item.getErrorMessage()).contains("terraform exploded");
        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.PAUSED);
        assertThat(rollout.getStatusReason()).contains("terraform exploded");
    }

    @Test
    @DisplayName("autoApply: a PLANNED item is applied and a Deployment history row opens")
    void plannedItemAppliesWhenAutoApply() {
        item.setStatus(FleetRolloutItem.Status.PLANNED);
        item.setPlanRunId(UUID.randomUUID());
        ProvisioningRun applyRun = run(ProvisioningRun.Status.QUEUED, ProvisioningRun.Action.APPLY);
        when(provisioning.apply(eq(stackId), any())).thenReturn(applyRun);

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.APPLYING);
        assertThat(item.getApplyRunId()).isEqualTo(applyRun.getId());
        assertThat(item.getDeploymentId()).isNotNull();
        verify(deploymentRepo).save(any());
    }

    @Test
    @DisplayName("manual mode: an operator's own successful apply is detected and adopted")
    void manualApplyDetected() {
        rollout.setAutoApply(false);
        rollout.setSoakMinutes(0);
        item.setStatus(FleetRolloutItem.Status.PLANNED);
        ProvisioningRun planRun = run(ProvisioningRun.Status.SUCCESS, ProvisioningRun.Action.PLAN);
        planRun.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        item.setPlanRunId(planRun.getId());
        when(runRepo.findById(planRun.getId())).thenReturn(Optional.of(planRun));

        ProvisioningRun manualApply = run(ProvisioningRun.Status.SUCCESS, ProvisioningRun.Action.APPLY);
        when(runRepo.findFirstByStackIdAndActionAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                eq(stackId), eq(ProvisioningRun.Action.APPLY), eq(ProvisioningRun.Status.SUCCESS), any()))
            .thenReturn(Optional.of(manualApply));

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.SUCCEEDED);
        assertThat(item.getApplyRunId()).isEqualTo(manualApply.getId());
        verify(deployments).updateStatus(any(), eq(Deployment.Status.SUCCESS), any());
    }

    @Test
    @DisplayName("soak passes only on a fresh heartbeat reporting the target version")
    void soakPassesOnHealthyHeartbeat() {
        item.setStatus(FleetRolloutItem.Status.SOAKING);
        item.setAppliedAt(LocalDateTime.now().minusMinutes(30));
        Organization org = Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .lastSeenAt(LocalDateTime.now().minusMinutes(1))
            .deployedVersion("2.4.0")
            .build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("soak: silence after the apply pauses the rollout and says which org")
    void soakSilencePauses() {
        item.setStatus(FleetRolloutItem.Status.SOAKING);
        item.setAppliedAt(LocalDateTime.now().minusMinutes(30));
        Organization org = Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .lastSeenAt(LocalDateTime.now().minusHours(2))   // before the apply
            .deployedVersion("2.3.0")
            .build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(stackRepo.findById(stackId)).thenReturn(Optional.empty());

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.SOAKING);
        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.PAUSED);
        assertThat(rollout.getStatusReason()).contains("acme").contains("no heartbeat");
    }

    @Test
    @DisplayName("soak: a wrong reported version pauses the rollout")
    void soakWrongVersionPauses() {
        item.setStatus(FleetRolloutItem.Status.SOAKING);
        item.setAppliedAt(LocalDateTime.now().minusMinutes(30));
        Organization org = Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .lastSeenAt(LocalDateTime.now().minusMinutes(1))
            .deployedVersion("2.3.0")                        // heartbeating, but on the OLD version
            .build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        when(stackRepo.findById(stackId)).thenReturn(Optional.empty());

        orchestrator.advance(rollout);

        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.PAUSED);
        assertThat(rollout.getStatusReason()).contains("2.3.0").contains("2.4.0");
    }

    @Test
    @DisplayName("an org that never phones home passes soak with an explicit 'unverified' note")
    void soakWithoutTelemetrySucceedsUnverified() {
        item.setStatus(FleetRolloutItem.Status.SOAKING);
        item.setAppliedAt(LocalDateTime.now().minusMinutes(30));
        Organization org = Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .lastSeenAt(null)
            .build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        orchestrator.advance(rollout);

        assertThat(item.getStatus()).isEqualTo(FleetRolloutItem.Status.SUCCEEDED);
        assertThat(item.getErrorMessage()).contains("no telemetry");
    }

    @Test
    @DisplayName("the wave advances only when every item in it is terminal")
    void waveAdvancesWhenAllTerminal() {
        item.setStatus(FleetRolloutItem.Status.SUCCEEDED);

        orchestrator.advance(rollout);

        assertThat(rollout.getCurrentWave()).isEqualTo(1);
        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.IN_PROGRESS);
    }

    @Test
    @DisplayName("an empty wave past the last item completes the rollout")
    void emptyWaveCompletes() {
        rollout.setCurrentWave(3);
        when(itemRepo.findByRolloutIdAndWave(rolloutId, 3)).thenReturn(List.of());

        orchestrator.advance(rollout);

        assertThat(rollout.getStatus()).isEqualTo(FleetRollout.Status.COMPLETED);
        assertThat(rollout.getCompletedAt()).isNotNull();
    }
}
