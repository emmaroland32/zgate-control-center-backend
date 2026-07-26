package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.TelemetryEvent;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrgInstanceRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The vendor-side radar: even if a customer patches out the client gate, phoning home must surface
 * over-version and running-while-unpaid as auditable LICENSE anomalies — while never flagging an
 * unmanaged/perpetual org.
 */
class AnomalyDetectionTest {

    private TelemetryEventRepository telemetryRepo;
    private OrganizationRepository orgRepo;
    private LicenseRepository licenseRepo;
    private OrgInstanceRepository instanceRepo;
    private AnomalyDetectionService svc;

    @BeforeEach
    void setUp() {
        telemetryRepo = mock(TelemetryEventRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        licenseRepo = mock(LicenseRepository.class);
        instanceRepo = mock(OrgInstanceRepository.class);
        svc = new AnomalyDetectionService(telemetryRepo, orgRepo, licenseRepo, instanceRepo);
        ReflectionTestUtils.setField(svc, "enabled", true);
        ReflectionTestUtils.setField(svc, "dedupeWindowHours", 12);
        ReflectionTestUtils.setField(svc, "versionGranularity", "minor");
        ReflectionTestUtils.setField(svc, "instanceWindowHours", 6);
        when(telemetryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Organization org(String entitledVersion, LocalDateTime subscriptionValidUntil) {
        return Organization.builder()
            .id(java.util.UUID.randomUUID()).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .entitledVersion(entitledVersion)
            .subscriptionValidUntil(subscriptionValidUntil)
            .build();
    }

    private List<TelemetryEvent> heartbeat(String version) {
        return List.of(TelemetryEvent.builder()
            .appVersion(version)
            .level(TelemetryEvent.Level.INFO)
            .category(TelemetryEvent.Category.SYSTEM)
            .build());
    }

    @Test
    @DisplayName("running newer than entitled → VERSION_BEYOND_ENTITLEMENT + org DEGRADED")
    void overVersionFlagged() {
        Organization o = org("1.0.0", null);
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("2.0.0"));
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).getErrorCode()).isEqualTo("VERSION_BEYOND_ENTITLEMENT");
        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.DEGRADED);
    }

    @Test
    @DisplayName("patch bump within entitled minor line → no anomaly (granularity=minor)")
    void patchBumpNotFlagged() {
        Organization o = org("1.1.0", null);
        assertThat(svc.inspect(o, heartbeat("1.1.9"))).isEmpty();
    }

    @Test
    @DisplayName("lapsed subscription → RUNNING_WHILE_UNENTITLED")
    void lapsedSubscriptionFlagged() {
        Organization o = org(null, LocalDateTime.now().minusDays(1));
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("1.0.0"));
        assertThat(raised).extracting(TelemetryEvent::getErrorCode).contains("RUNNING_WHILE_UNENTITLED");
    }

    @Test
    @DisplayName("unmanaged org (no entitledVersion, no subscription) → nothing flagged")
    void unmanagedOrgNeverFlagged() {
        Organization o = org(null, null);
        assertThat(svc.inspect(o, heartbeat("9.9.9"))).isEmpty();
    }

    @Test
    @DisplayName("deployedVersion is refreshed from the heartbeat")
    void deployedVersionRefreshed() {
        Organization o = org(null, null);
        svc.inspect(o, heartbeat("3.4.5"));
        assertThat(o.getDeployedVersion()).isEqualTo("3.4.5");
    }

    @Test
    @DisplayName("dedupe: an already-flagged anomaly within the window is not re-raised")
    void dedupeSuppressesRepeat() {
        Organization o = org("1.0.0", null);
        when(telemetryRepo.existsByOrganizationIdAndErrorCodeAndReceivedAtAfter(
                eq(o.getId()), eq("VERSION_BEYOND_ENTITLEMENT"), any())).thenReturn(true);
        assertThat(svc.inspect(o, heartbeat("2.0.0"))).isEmpty();
    }

    private void boundLicense(Organization o, String fingerprint) {
        License l = License.builder()
            .id(java.util.UUID.randomUUID()).organizationId(o.getId()).moduleName("core")
            .status(License.Status.ACTIVE).fingerprint(fingerprint).build();
        when(licenseRepo.findByOrganizationId(o.getId())).thenReturn(java.util.List.of(l));
    }

    @Test
    @DisplayName("reported fingerprint != license binding → FINGERPRINT_MISMATCH (copied license)")
    void copiedLicenseFlagged() {
        Organization o = org(null, null);
        boundLicense(o, "FP-BOUND");
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("1.0.0"), "FP-OTHER-MACHINE");
        assertThat(raised).extracting(TelemetryEvent::getErrorCode).contains("FINGERPRINT_MISMATCH");
    }

    @Test
    @DisplayName("reported fingerprint matches binding → no anomaly")
    void matchingFingerprintOk() {
        Organization o = org(null, null);
        boundLicense(o, "FP-BOUND");
        assertThat(svc.inspect(o, heartbeat("1.0.0"), "FP-BOUND")).isEmpty();
    }

    @Test
    @DisplayName("unbound license (no fingerprint) → fingerprint-binding check skipped")
    void unboundLicenseNotFlagged() {
        Organization o = org(null, null);
        boundLicense(o, null);
        assertThat(svc.inspect(o, heartbeat("1.0.0"), "FP-ANYTHING")).isEmpty();
    }

    @Test
    @DisplayName("more live environments than entitled → MULTIPLE_INSTANCES (copy detected even if unbound)")
    void overDeployedFlagged() {
        Organization o = org(null, null);
        o.setMaxInstances(1);
        when(instanceRepo.countDistinctFingerprints(eq(o.getId()), any())).thenReturn(2L);
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("1.0.0"), "FP-MACHINE-2");
        assertThat(raised).extracting(TelemetryEvent::getErrorCode).contains("MULTIPLE_INSTANCES");
    }

    @Test
    @DisplayName("live environments within entitlement → no MULTIPLE_INSTANCES (e.g. prod + DR)")
    void withinInstanceLimitOk() {
        Organization o = org(null, null);
        o.setMaxInstances(2);
        when(instanceRepo.countDistinctFingerprints(eq(o.getId()), any())).thenReturn(2L);
        assertThat(svc.inspect(o, heartbeat("1.0.0"), "FP-MACHINE-2")).isEmpty();
    }

    @Test
    @DisplayName("unmanaged org (maxInstances + tier null) raises nothing for topology")
    void unmanagedInstancesNotFlagged() {
        Organization o = org(null, null); // maxInstances + deploymentTier null
        when(instanceRepo.countDistinctFingerprints(eq(o.getId()), any())).thenReturn(5L);
        assertThat(svc.inspect(o, heartbeat("1.0.0"), "FP-MACHINE-2")).isEmpty();
    }

    @Test
    @DisplayName("each heartbeat records/refreshes the reporting node in the registry")
    void heartbeatRecordsInstance() {
        Organization o = org(null, null);
        svc.inspect(o, heartbeat("1.2.3"), "FP-MACHINE-1", "pod-1", "kubernetes");
        org.mockito.Mockito.verify(instanceRepo).save(any());
    }

    // ---- deployment-tier (K8s/ECS/failover pricing) ----

    private Organization tieredOrg(Organization.DeploymentTier tier) {
        Organization o = org(null, null);
        o.setDeploymentTier(tier);
        return o;
    }

    @Test
    @DisplayName("SINGLE_NODE tier running on Kubernetes → HA_NOT_ENTITLED")
    void k8sOnSingleNodeFlagged() {
        Organization o = tieredOrg(Organization.DeploymentTier.SINGLE_NODE);
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("1.0.0"), "FP", "pod-1", "kubernetes");
        assertThat(raised).extracting(TelemetryEvent::getErrorCode).contains("HA_NOT_ENTITLED");
    }

    @Test
    @DisplayName("SINGLE_NODE tier with multiple replica nodes → HA_NOT_ENTITLED")
    void replicasOnSingleNodeFlagged() {
        Organization o = tieredOrg(Organization.DeploymentTier.SINGLE_NODE);
        when(instanceRepo.maxNodesPerFingerprint(eq(o.getId()), any())).thenReturn(3L);
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("1.0.0"), "FP", "node-a", "bare");
        assertThat(raised).extracting(TelemetryEvent::getErrorCode).contains("HA_NOT_ENTITLED");
    }

    @Test
    @DisplayName("HIGH_AVAILABILITY tier on Kubernetes → allowed")
    void k8sOnHaAllowed() {
        Organization o = tieredOrg(Organization.DeploymentTier.HIGH_AVAILABILITY);
        assertThat(svc.inspect(o, heartbeat("1.0.0"), "FP", "pod-1", "kubernetes")).isEmpty();
    }

    @Test
    @DisplayName("HIGH_AVAILABILITY tier running in two environments → FAILOVER_NOT_ENTITLED")
    void multiEnvOnHaFlagged() {
        Organization o = tieredOrg(Organization.DeploymentTier.HIGH_AVAILABILITY);
        when(instanceRepo.countDistinctFingerprints(eq(o.getId()), any())).thenReturn(2L);
        List<TelemetryEvent> raised = svc.inspect(o, heartbeat("1.0.0"), "FP-ENV2", "pod-1", "kubernetes");
        assertThat(raised).extracting(TelemetryEvent::getErrorCode).contains("FAILOVER_NOT_ENTITLED");
    }

    @Test
    @DisplayName("MULTI_REGION tier across two environments → allowed")
    void multiEnvOnMultiRegionAllowed() {
        Organization o = tieredOrg(Organization.DeploymentTier.MULTI_REGION);
        when(instanceRepo.countDistinctFingerprints(eq(o.getId()), any())).thenReturn(2L);
        assertThat(svc.inspect(o, heartbeat("1.0.0"), "FP-ENV2", "pod-1", "kubernetes")).isEmpty();
    }

    @Test
    @DisplayName("disabled → no detection at all")
    void disabledDoesNothing() {
        ReflectionTestUtils.setField(svc, "enabled", false);
        Organization o = org("1.0.0", LocalDateTime.now().minusDays(5));
        assertThat(svc.inspect(o, heartbeat("2.0.0"))).isEmpty();
        verify(telemetryRepo, never()).save(any());
    }
}
