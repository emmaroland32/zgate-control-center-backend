package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Rollout creation decides which production systems get touched and in what order — the wave plan
 * must be deterministic, stacks already on the target release must drop out, and a stack can never
 * sit in two live rollouts at once.
 */
class FleetRolloutServiceTest {

    private FleetRolloutRepository rolloutRepo;
    private FleetRolloutItemRepository itemRepo;
    private InfrastructureStackRepository stackRepo;
    private OrganizationRepository orgRepo;
    private ReleaseRepository releaseRepo;
    private FleetRolloutService svc;

    private final UUID releaseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rolloutRepo = mock(FleetRolloutRepository.class);
        itemRepo = mock(FleetRolloutItemRepository.class);
        stackRepo = mock(InfrastructureStackRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        releaseRepo = mock(ReleaseRepository.class);
        svc = new FleetRolloutService(rolloutRepo, itemRepo, stackRepo, orgRepo, releaseRepo);

        Release release = Release.builder()
            .id(releaseId).version("2.4.0").channel(Release.Channel.STABLE)
            .dockerTag("2.4.0").approvalStatus(Release.ApprovalStatus.APPROVED).build();
        when(releaseRepo.findById(releaseId)).thenReturn(Optional.of(release));
        when(rolloutRepo.save(any())).thenAnswer(i -> {
            FleetRollout r = i.getArgument(0);
            if (r != null && r.getId() == null)
                org.springframework.test.util.ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(itemRepo.existsByStackIdAndStatusIn(any(), any())).thenReturn(false);
    }

    private InfrastructureStack stack(String slug, String env, String version, boolean applied) {
        UUID orgId = UUID.randomUUID();
        Organization org = Organization.builder()
            .id(orgId).name(slug).slug(slug)
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION).build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));
        return InfrastructureStack.builder()
            .id(UUID.randomUUID()).organizationId(orgId)
            .environment(env).target(InfrastructureStack.Target.AWS_ECS)
            .status(InfrastructureStack.Status.ACTIVE)
            .specJson("{}").releaseVersion(version)
            .lastAppliedAt(applied ? LocalDateTime.now().minusDays(10) : null)
            .build();
    }

    private FleetRolloutService.CreateRolloutRequest request(Integer canary, Integer waveSize) {
        FleetRolloutService.CreateRolloutRequest req = new FleetRolloutService.CreateRolloutRequest();
        req.setReleaseId(releaseId);
        req.setCanarySize(canary);
        req.setWaveSize(waveSize);
        return req;
    }

    @Test
    @DisplayName("waves: canary first, then chunks of waveSize, ordered by org slug")
    void waveAssignmentIsDeterministic() {
        List<InfrastructureStack> stacks = new ArrayList<>();
        for (String slug : List.of("delta", "alpha", "echo", "bravo", "charlie")) {
            stacks.add(stack(slug, "prod", "2.3.0", true));
        }
        when(stackRepo.findAll()).thenReturn(stacks);

        svc.create(request(1, 2), "admin");

        ArgumentCaptor<FleetRolloutItem> captor = ArgumentCaptor.forClass(FleetRolloutItem.class);
        verify(itemRepo, times(5)).save(captor.capture());
        List<FleetRolloutItem> items = captor.getAllValues();

        // alpha is the canary (wave 0); then bravo+charlie (wave 1); then delta+echo (wave 2).
        assertThat(items).extracting(FleetRolloutItem::getWave).containsExactly(0, 1, 1, 2, 2);
        assertThat(orgSlug(items.get(0))).isEqualTo("alpha");
        assertThat(List.of(orgSlug(items.get(1)), orgSlug(items.get(2))))
            .containsExactly("bravo", "charlie");
    }

    @Test
    @DisplayName("stacks already on the target release drop out of the rollout")
    void alreadyCurrentStacksExcluded() {
        InfrastructureStack behind = stack("acme", "prod", "2.3.0", true);
        InfrastructureStack current = stack("zen", "prod", "2.4.0", true);
        when(stackRepo.findAll()).thenReturn(List.of(behind, current));

        svc.create(request(1, 5), "admin");

        ArgumentCaptor<FleetRolloutItem> captor = ArgumentCaptor.forClass(FleetRolloutItem.class);
        verify(itemRepo, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStackId()).isEqualTo(behind.getId());
    }

    @Test
    @DisplayName("a stack already in a live rollout is refused")
    void overlappingRolloutRefused() {
        InfrastructureStack s = stack("acme", "prod", "2.3.0", true);
        when(stackRepo.findAll()).thenReturn(List.of(s));
        when(itemRepo.existsByStackIdAndStatusIn(eq(s.getId()), any())).thenReturn(true);

        assertThatThrownBy(() -> svc.create(request(1, 5), "admin"))
            .isInstanceOfSatisfying(ControlCenterException.class,
                e -> assertThat(e.getCode()).isEqualTo("STACK_IN_LIVE_ROLLOUT"));
    }

    @Test
    @DisplayName("nothing to do (everyone current / unapplied) is an explicit refusal, not an empty rollout")
    void nothingToDoRefused() {
        InfrastructureStack current = stack("acme", "prod", "2.4.0", true);
        InfrastructureStack unapplied = stack("zen", "prod", null, false);
        when(stackRepo.findAll()).thenReturn(List.of(current, unapplied));

        assertThatThrownBy(() -> svc.create(request(1, 5), "admin"))
            .isInstanceOfSatisfying(ControlCenterException.class,
                e -> assertThat(e.getCode()).isEqualTo("ROLLOUT_NOTHING_TO_DO"));
    }

    @Test
    @DisplayName("resume resets FAILED items of the current wave to PENDING — the retry path")
    void resumeRetriesFailedItems() {
        UUID rolloutId = UUID.randomUUID();
        FleetRollout rollout = FleetRollout.builder()
            .id(rolloutId).releaseId(releaseId).releaseVersion("2.4.0")
            .status(FleetRollout.Status.PAUSED).statusReason("boom")
            .canarySize(1).waveSize(5).currentWave(1).createdBy("admin").build();
        when(rolloutRepo.findById(rolloutId)).thenReturn(Optional.of(rollout));

        FleetRolloutItem failed = FleetRolloutItem.builder()
            .id(UUID.randomUUID()).rolloutId(rolloutId).stackId(UUID.randomUUID())
            .organizationId(UUID.randomUUID()).wave(1)
            .status(FleetRolloutItem.Status.FAILED).errorMessage("plan failed")
            .toVersion("2.4.0").planRunId(UUID.randomUUID()).build();
        FleetRolloutItem succeeded = FleetRolloutItem.builder()
            .id(UUID.randomUUID()).rolloutId(rolloutId).stackId(UUID.randomUUID())
            .organizationId(UUID.randomUUID()).wave(1)
            .status(FleetRolloutItem.Status.SUCCEEDED).toVersion("2.4.0").build();
        when(itemRepo.findByRolloutIdAndWave(rolloutId, 1)).thenReturn(List.of(failed, succeeded));

        FleetRollout resumed = svc.resume(rolloutId, "admin");

        assertThat(resumed.getStatus()).isEqualTo(FleetRollout.Status.IN_PROGRESS);
        assertThat(resumed.getStatusReason()).isNull();
        assertThat(failed.getStatus()).isEqualTo(FleetRolloutItem.Status.PENDING);
        assertThat(failed.getErrorMessage()).isNull();
        assertThat(failed.getPlanRunId()).isNull();
        assertThat(succeeded.getStatus()).isEqualTo(FleetRolloutItem.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("cancel skips pending items and is terminal")
    void cancelSkipsPending() {
        UUID rolloutId = UUID.randomUUID();
        FleetRollout rollout = FleetRollout.builder()
            .id(rolloutId).releaseId(releaseId).releaseVersion("2.4.0")
            .status(FleetRollout.Status.IN_PROGRESS)
            .canarySize(1).waveSize(5).currentWave(0).createdBy("admin").build();
        when(rolloutRepo.findById(rolloutId)).thenReturn(Optional.of(rollout));

        FleetRolloutItem pending = FleetRolloutItem.builder()
            .id(UUID.randomUUID()).rolloutId(rolloutId).stackId(UUID.randomUUID())
            .organizationId(UUID.randomUUID()).wave(1)
            .status(FleetRolloutItem.Status.PENDING).toVersion("2.4.0").build();
        when(itemRepo.findByRolloutIdOrderByWaveAscCreatedAtAsc(rolloutId)).thenReturn(List.of(pending));

        FleetRollout cancelled = svc.cancel(rolloutId, "admin");

        assertThat(cancelled.getStatus()).isEqualTo(FleetRollout.Status.CANCELLED);
        assertThat(cancelled.getCompletedAt()).isNotNull();
        assertThat(pending.getStatus()).isEqualTo(FleetRolloutItem.Status.SKIPPED);
    }

    private String orgSlug(FleetRolloutItem item) {
        return orgRepo.findById(item.getOrganizationId()).map(Organization::getSlug).orElse(null);
    }
}
