package com.zgate.controlcenter.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.InfrastructureStack;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.ProvisioningRun;
import com.zgate.controlcenter.domain.Release;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.InfrastructureStackRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.ProvisioningRunRepository;
import com.zgate.controlcenter.repository.ReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The upgrade primitive's whole reason to exist: move a stack to a new release while preserving
 * every other decision in the stored spec. A wholesale re-render here (the old behaviour of
 * re-provisioning) silently reverted DNS, sizing and backup choices an operator made months ago —
 * so these tests pin that ONLY the image block changes, and that the entitlement gate holds.
 */
class StackUpgradeTest {

    private InfrastructureStackRepository stackRepo;
    private ProvisioningRunRepository runRepo;
    private OrganizationRepository orgRepo;
    private ReleaseRepository releaseRepo;
    private TerraformRunner runner;
    private ProvisioningExecutor executor;
    private SpecRenderer renderer;
    private ProvisioningService svc;

    private final UUID stackId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID releaseId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        stackRepo = mock(InfrastructureStackRepository.class);
        runRepo = mock(ProvisioningRunRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        releaseRepo = mock(ReleaseRepository.class);
        runner = mock(TerraformRunner.class);
        executor = mock(ProvisioningExecutor.class);

        renderer = new SpecRenderer(mapper);
        ReflectionTestUtils.setField(renderer, "controlCenterPublicUrl", "https://control.zgate.example");
        ReflectionTestUtils.setField(renderer, "defaultRegistry", "123456789012.dkr.ecr.eu-west-2.amazonaws.com");
        ReflectionTestUtils.setField(renderer, "cosignPublicKey", "");
        ReflectionTestUtils.setField(renderer, "webImageRepository", "");

        svc = new ProvisioningService(stackRepo, runRepo, orgRepo, releaseRepo,
                mock(CloudCredentialService.class), renderer, runner, executor,
                mock(com.zgate.controlcenter.service.OrganizationService.class));
        ReflectionTestUtils.setField(svc, "stateBucket", "vendor-tf-state");

        when(runner.unavailableReason()).thenReturn(null);
        when(stackRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(runRepo.save(any())).thenAnswer(i -> {
            ProvisioningRun r = i.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
    }

    private InfrastructureStack stack(String specJson, String releaseVersion) {
        InfrastructureStack s = InfrastructureStack.builder()
            .id(stackId).organizationId(orgId)
            .environment("prod").target(InfrastructureStack.Target.AWS_ECS)
            .status(InfrastructureStack.Status.ACTIVE)
            .specJson(specJson)
            .releaseVersion(releaseVersion)
            .lastAppliedAt(LocalDateTime.now().minusDays(30))
            .build();
        // The claim paths read the row FOR UPDATE, so that is the mock they exercise. Without
        // the lock, two replicas both see the stack idle and both launch Terraform against the
        // same remote state.
        when(stackRepo.findByIdForUpdate(stackId)).thenReturn(Optional.of(s));
        when(stackRepo.findById(stackId)).thenReturn(Optional.of(s));
        return s;
    }

    private Organization org(String entitledVersion) {
        Organization o = Organization.builder()
            .id(orgId).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .entitledVersion(entitledVersion)
            .build();
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(o));
        return o;
    }

    private Release release(String version, String tag, String digest) {
        Release r = Release.builder()
            .id(releaseId).version(version)
            .channel(Release.Channel.STABLE)
            .dockerTag(tag)
            .imageDigest(digest)
            .approvalStatus(Release.ApprovalStatus.APPROVED)
            .build();
        when(releaseRepo.findById(releaseId)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    @DisplayName("upgrade rewrites the image block and NOTHING else in the stored spec")
    void upgradePreservesEverythingButImage() throws Exception {
        String spec = mapper.writeValueAsString(Map.of(
            "image", Map.of(
                "backend", Map.of("repository", "old/zgate", "tag", "2.3.0", "digest", ""),
                "web", Map.of("repository", "old/zgate-web", "tag", "2.3.0", "digest", ""),
                "source", "mirror", "mirror_repository", "", "cosign_public_key", ""),
            "dns", Map.of("mode", "managed", "domain_name", "zgate.acme.example"),
            "database", Map.of("mode", "managed", "multi_az", true, "deletion_protection", true),
            "compute", Map.of("size", "large"),
            "guards", Map.of("allow_destroy", false)));
        InfrastructureStack s = stack(spec, "2.3.0");
        org("3.0.0");
        release("2.4.0", "2.4.0", "sha256:" + "ab".repeat(32));

        svc.upgrade(stackId, releaseId, "operator");

        Map<String, Object> after = renderer.fromJson(s.getSpecJson());

        // Untouched blocks survive verbatim — the operator's choices from provisioning day.
        assertThat(after.get("dns")).isEqualTo(Map.of("mode", "managed", "domain_name", "zgate.acme.example"));
        assertThat(after.get("database"))
            .isEqualTo(Map.of("mode", "managed", "multi_az", true, "deletion_protection", true));
        assertThat(after.get("compute")).isEqualTo(Map.of("size", "large"));
        assertThat(after.get("guards")).isEqualTo(Map.of("allow_destroy", false));

        // The image block IS rewritten — new tag, new digest, current registry.
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) after.get("image");
        @SuppressWarnings("unchecked")
        Map<String, Object> backend = (Map<String, Object>) image.get("backend");
        assertThat(backend.get("tag")).isEqualTo("2.4.0");
        assertThat(backend.get("digest")).isEqualTo("sha256:" + "ab".repeat(32));

        assertThat(s.getReleaseVersion()).isEqualTo("2.4.0");
        assertThat(s.getStatus()).isEqualTo(InfrastructureStack.Status.PLANNING);
    }

    @Test
    @DisplayName("upgrade starts a PLAN run, never an apply")
    void upgradeRunsPlanOnly() throws Exception {
        stack(mapper.writeValueAsString(Map.of("image", Map.of())), "2.3.0");
        org(null);
        release("2.4.0", "2.4.0", null);

        ProvisioningRun run = svc.upgrade(stackId, releaseId, "operator");

        assertThat(run.getAction()).isEqualTo(ProvisioningRun.Action.PLAN);
        ArgumentCaptor<TerraformRunner.Request> req = ArgumentCaptor.forClass(TerraformRunner.Request.class);
        org.mockito.Mockito.verify(executor).executeAsync(any(), req.capture(), any());
        assertThat(req.getValue().action()).isEqualTo(ProvisioningRun.Action.PLAN);
    }

    @Test
    @DisplayName("a release beyond the org's entitlement is refused with 402")
    void upgradeBeyondEntitlementRefused() throws Exception {
        stack(mapper.writeValueAsString(Map.of("image", Map.of())), "2.3.0");
        org("2.3.9");
        release("2.4.0", "2.4.0", null);

        assertThatThrownBy(() -> svc.upgrade(stackId, releaseId, "operator"))
            .isInstanceOfSatisfying(ControlCenterException.class,
                e -> assertThat(e.getCode()).isEqualTo("VERSION_NOT_ENTITLED"));
    }

    @Test
    @DisplayName("upgrading to the release the stack already runs is refused")
    void upgradeToSameReleaseRefused() throws Exception {
        stack(mapper.writeValueAsString(Map.of("image", Map.of())), "2.4.0");
        org(null);
        release("2.4.0", "2.4.0", null);

        assertThatThrownBy(() -> svc.upgrade(stackId, releaseId, "operator"))
            .isInstanceOfSatisfying(ControlCenterException.class,
                e -> assertThat(e.getCode()).isEqualTo("STACK_ALREADY_ON_RELEASE"));
    }

    @Test
    @DisplayName("a never-applied stack cannot be upgraded — provision is the right tool")
    void upgradeUnappliedStackRefused() throws Exception {
        InfrastructureStack s = stack(mapper.writeValueAsString(Map.of()), null);
        s.setLastAppliedAt(null);
        org(null);
        release("2.4.0", "2.4.0", null);

        assertThatThrownBy(() -> svc.upgrade(stackId, releaseId, "operator"))
            .isInstanceOfSatisfying(ControlCenterException.class,
                e -> assertThat(e.getCode()).isEqualTo("STACK_NEVER_APPLIED"));
    }
}
