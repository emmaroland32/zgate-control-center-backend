package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.AlertRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Liveness transitions decide what an operator sees when a customer's production system dies.
 * Before this sweep existed, NOTHING moved an org off HEALTHY when heartbeats stopped — these tests
 * pin each transition and, as importantly, the ones that must never happen (SUSPENDED stays put,
 * silent-by-design orgs are left alone).
 */
class FleetHealthSweepTest {

    private OrganizationRepository orgRepo;
    private AlertRepository alertRepo;
    private FleetHealthService svc;

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        alertRepo = mock(AlertRepository.class);
        svc = new FleetHealthService(orgRepo, alertRepo);
        ReflectionTestUtils.setField(svc, "enabled", true);
        ReflectionTestUtils.setField(svc, "offlineAfterMinutes", 15);
        when(orgRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(alertRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(alertRepo.findByOrganizationIdAndStatus(any(), any())).thenReturn(List.of());
    }

    private Organization org(Organization.DeploymentStatus status, LocalDateTime lastSeen) {
        Organization o = Organization.builder()
            .id(UUID.randomUUID()).name("Acme").slug("acme")
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(status)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .lastSeenAt(lastSeen)
            .build();
        when(orgRepo.findAll()).thenReturn(List.of(o));
        return o;
    }

    @Test
    @DisplayName("a HEALTHY org silent past the threshold goes OFFLINE with a critical alert")
    void silentHealthyOrgGoesOffline() {
        Organization o = org(Organization.DeploymentStatus.HEALTHY, LocalDateTime.now().minusMinutes(30));

        svc.sweep();

        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.OFFLINE);
        ArgumentCaptor<Alert> alert = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepo).save(alert.capture());
        assertThat(alert.getValue().getTitle()).isEqualTo("Deployment offline");
        assertThat(alert.getValue().getStatus()).isEqualTo(Alert.Status.FIRING);
    }

    @Test
    @DisplayName("an OFFLINE org that heartbeats again recovers to HEALTHY and resolves its alert")
    void offlineOrgRecovers() {
        Organization o = org(Organization.DeploymentStatus.OFFLINE, LocalDateTime.now().minusMinutes(1));
        Alert firing = Alert.builder()
            .id(UUID.randomUUID()).organizationId(o.getId())
            .status(Alert.Status.FIRING).severity(com.zgate.controlcenter.domain.AlertRule.Severity.CRITICAL)
            .title("Deployment offline").build();
        when(alertRepo.findByOrganizationIdAndStatus(eq(o.getId()), eq(Alert.Status.FIRING)))
            .thenReturn(List.of(firing));

        svc.sweep();

        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.HEALTHY);
        assertThat(firing.getStatus()).isEqualTo(Alert.Status.RESOLVED);
        assertThat(firing.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("a PROVISIONING org's first heartbeat proves the boot — it becomes HEALTHY")
    void provisioningOrgBecomesHealthyOnHeartbeat() {
        Organization o = org(Organization.DeploymentStatus.PROVISIONING, LocalDateTime.now().minusMinutes(1));

        svc.sweep();

        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.HEALTHY);
    }

    @Test
    @DisplayName("an org that never phoned home is out of scope — silence is its normal")
    void neverSeenOrgUntouched() {
        Organization o = org(Organization.DeploymentStatus.HEALTHY, null);

        svc.sweep();

        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.HEALTHY);
        verify(orgRepo, never()).save(any());
    }

    @Test
    @DisplayName("SUSPENDED is operator-owned — the sweep never touches it")
    void suspendedOrgUntouched() {
        Organization o = org(Organization.DeploymentStatus.SUSPENDED, LocalDateTime.now().minusHours(5));

        svc.sweep();

        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.SUSPENDED);
        verify(orgRepo, never()).save(any());
    }

    @Test
    @DisplayName("no duplicate alert while one is already firing")
    void noDuplicateOfflineAlert() {
        Organization o = org(Organization.DeploymentStatus.DEGRADED, LocalDateTime.now().minusMinutes(30));
        Alert firing = Alert.builder()
            .id(UUID.randomUUID()).organizationId(o.getId())
            .status(Alert.Status.FIRING).severity(com.zgate.controlcenter.domain.AlertRule.Severity.CRITICAL)
            .title("Deployment offline").build();
        when(alertRepo.findByOrganizationIdAndStatus(eq(o.getId()), eq(Alert.Status.FIRING)))
            .thenReturn(List.of(firing));

        svc.sweep();

        assertThat(o.getDeploymentStatus()).isEqualTo(Organization.DeploymentStatus.OFFLINE);
        verify(alertRepo, never()).save(any());
    }
}
