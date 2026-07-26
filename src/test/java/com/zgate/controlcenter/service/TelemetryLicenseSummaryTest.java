package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.TelemetryEvent;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.TelemetryEventRepository;
import com.zgate.controlcenter.service.TelemetryService.LicenseAnomalySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The fleet licensing dashboard sums open LICENSE anomalies by type across all orgs. */
class TelemetryLicenseSummaryTest {

    private TelemetryEventRepository repo;
    private TelemetryService svc;

    @BeforeEach
    void setUp() {
        repo = mock(TelemetryEventRepository.class);
        svc = new TelemetryService(repo, mock(OrganizationRepository.class), mock(AnomalyDetectionService.class));
    }

    @Test
    @DisplayName("aggregates per-code counts, total, affected orgs, and recent events")
    void summarizes() {
        when(repo.countUnacknowledgedByCode(eq(TelemetryEvent.Category.LICENSE))).thenReturn(List.of(
            new Object[]{"VERSION_BEYOND_ENTITLEMENT", 3L},
            new Object[]{"HA_NOT_ENTITLED", 2L}));
        when(repo.countDistinctAffectedOrgs(eq(TelemetryEvent.Category.LICENSE))).thenReturn(4L);
        Page<TelemetryEvent> page = new PageImpl<>(List.of(
            TelemetryEvent.builder().errorCode("HA_NOT_ENTITLED").category(TelemetryEvent.Category.LICENSE).build()));
        when(repo.search(any(), any(), eq("LICENSE"), any(), any(), eq(false), any())).thenReturn(page);

        LicenseAnomalySummary s = svc.licenseAnomalySummary();

        assertThat(s.total()).isEqualTo(5);
        assertThat(s.affectedOrgs()).isEqualTo(4);
        assertThat(s.byCode()).containsEntry("VERSION_BEYOND_ENTITLEMENT", 3L).containsEntry("HA_NOT_ENTITLED", 2L);
        assertThat(s.recent()).hasSize(1);
    }

    @Test
    @DisplayName("no anomalies → zeros and empty feed")
    void empty() {
        when(repo.countUnacknowledgedByCode(any())).thenReturn(List.of());
        when(repo.countDistinctAffectedOrgs(any())).thenReturn(0L);
        when(repo.search(any(), any(), any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        LicenseAnomalySummary s = svc.licenseAnomalySummary();
        assertThat(s.total()).isZero();
        assertThat(s.byCode()).isEmpty();
        assertThat(s.recent()).isEmpty();
    }
}
