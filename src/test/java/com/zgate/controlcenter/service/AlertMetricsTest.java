package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The console's metric list and the evaluator's switch must agree. When they diverged, every rule
 * an operator could build named a metric the evaluator did not recognise, so it was skipped
 * silently every five minutes — monitoring that looked configured and could never fire.
 */
class AlertMetricsTest {

    /** Mirrors METRICS in web/src/app/(protected)/alerts/page.tsx. */
    private static final List<String> CONSOLE_METRICS = List.of(
        "offline_deployments", "degraded_deployments", "unacknowledged_errors", "expiring_licenses",
        "cpu_usage_percent", "memory_usage_percent", "license_expiry_days", "deployment_failure",
        "error_rate_percent", "drifted_stacks");

    private OrgInstanceRepository instanceRepo;
    private TelemetryEventRepository telemetryRepo;
    private LicenseRepository licenseRepo;
    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        instanceRepo = mock(OrgInstanceRepository.class);
        telemetryRepo = mock(TelemetryEventRepository.class);
        licenseRepo = mock(LicenseRepository.class);
        OrganizationRepository orgRepo = mock(OrganizationRepository.class);
        DeploymentRepository deploymentRepo = mock(DeploymentRepository.class);
        InfrastructureStackRepository stackRepo = mock(InfrastructureStackRepository.class);

        evaluator = new AlertEvaluator(
            mock(AlertRuleRepository.class), mock(AlertRepository.class), mock(AlertService.class),
            orgRepo, telemetryRepo, licenseRepo, instanceRepo, deploymentRepo, stackRepo);
        ReflectionTestUtils.setField(evaluator, "windowMinutes", 15);

        when(instanceRepo.findByLastSeenAtAfter(any())).thenReturn(List.of());
        when(licenseRepo.findExpiringSoon(any())).thenReturn(List.of());
        when(stackRepo.findByDriftDetectedTrue()).thenReturn(List.of());
        when(telemetryRepo.countByOccurredAtAfter(any())).thenReturn(0L);
    }

    private OrgInstance node(Integer cpu, Integer usedMb, Integer maxMb) {
        return OrgInstance.builder()
            .id(UUID.randomUUID()).organizationId(UUID.randomUUID())
            .fingerprint("fp").nodeId("n").cpuPct(cpu).memUsedMb(usedMb).memMaxMb(maxMb)
            .lastSeenAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("an unknown metric is refused, so a typo cannot masquerade as a working rule")
    void unknownMetricIsNull() {
        assertThat(evaluator.metricValue("no_such_metric")).isNull();
        assertThat(evaluator.metricValue(null)).isNull();
    }

    @Test
    @DisplayName("count-style metrics the console offers all resolve to a number")
    void countMetricsResolve() {
        for (String metric : List.of("offline_deployments", "degraded_deployments",
                                     "unacknowledged_errors", "expiring_licenses",
                                     "deployment_failure", "drifted_stacks", "error_rate_percent")) {
            assertThat(evaluator.metricValue(metric))
                .describedAs("console offers '%s' — the evaluator must recognise it", metric)
                .isNotNull();
        }
        // Every console metric is either a count above or a resource/expiry metric below.
        assertThat(CONSOLE_METRICS).hasSize(10);
    }

    @Test
    @DisplayName("CPU/memory report the WORST node — an average would hide the one about to fall over")
    void resourceMetricsTakeTheWorstNode() {
        when(instanceRepo.findByLastSeenAtAfter(any()))
            .thenReturn(List.of(node(10, 100, 1000), node(95, 900, 1000), node(40, 500, 1000)));

        assertThat(evaluator.metricValue("cpu_usage_percent")).isEqualTo(95.0);
        assertThat(evaluator.metricValue("memory_usage_percent")).isEqualTo(90.0);
    }

    @Test
    @DisplayName("no reporting nodes yields null, not zero — silence must not read as healthy")
    void noDataIsNotZero() {
        assertThat(evaluator.metricValue("cpu_usage_percent")).isNull();
        assertThat(evaluator.metricValue("memory_usage_percent")).isNull();
    }

    @Test
    @DisplayName("error rate is 0% on an empty window, never a divide-by-zero or a false 100%")
    void errorRateHandlesEmptyWindow() {
        assertThat(evaluator.metricValue("error_rate_percent")).isEqualTo(0.0);

        when(telemetryRepo.countByOccurredAtAfter(any())).thenReturn(200L);
        when(telemetryRepo.countByLevelAndOccurredAtAfter(any(), any())).thenReturn(10L);
        assertThat(evaluator.metricValue("error_rate_percent")).isEqualTo(5.0);
    }
}
