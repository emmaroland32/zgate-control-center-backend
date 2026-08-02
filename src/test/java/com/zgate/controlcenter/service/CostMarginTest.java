package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.OrgCloudCost;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Margin is a money number an operator prices deals from — the rule under test is that an unknown
 * side yields a NULL margin, never a fabricated zero (a customer with no cost data must not look
 * like a 100%-margin customer).
 */
class CostMarginTest {

    private OrganizationRepository orgRepo;
    private CloudCostService costService;
    private FleetOverviewService svc;

    private final UUID feeAndCost = UUID.randomUUID();
    private final UUID feeOnly = UUID.randomUUID();
    private final UUID costOnly = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrganizationRepository.class);
        costService = mock(CloudCostService.class);
        svc = new FleetOverviewService(orgRepo, mock(InfrastructureStackRepository.class),
            mock(ReleaseRepository.class), mock(BackupPlanRepository.class),
            mock(BackupRecordRepository.class), mock(FleetRolloutRepository.class), costService);
        ReflectionTestUtils.setField(svc, "backupStaleHours", 26);
        ReflectionTestUtils.setField(svc, "lapseWarnDays", 30);
        ReflectionTestUtils.setField(svc, "serviceKeyEnforced", true);
    }

    private Organization org(UUID id, String slug, String fee) {
        return Organization.builder()
            .id(id).name(slug).slug(slug)
            .tier(Organization.Tier.ENTERPRISE)
            .deploymentStatus(Organization.DeploymentStatus.HEALTHY)
            .deploymentEnv(Organization.DeploymentEnv.PRODUCTION)
            .subscriptionMonthlyFee(fee == null ? null : new BigDecimal(fee))
            .build();
    }

    @Test
    @DisplayName("margin = fee - cost; either side missing -> null margin, never a fabricated zero")
    void marginMath() {
        when(orgRepo.findAll()).thenReturn(List.of(
            org(feeAndCost, "both", "1000.00"),
            org(feeOnly, "feeonly", "500.00"),
            org(costOnly, "costonly", null),
            org(UUID.randomUUID(), "neither", null)));
        when(costService.currentMonth()).thenReturn(List.of(
            OrgCloudCost.builder().organizationId(feeAndCost).amount(new BigDecimal("312.45")).build(),
            OrgCloudCost.builder().organizationId(costOnly).amount(new BigDecimal("99.00")).build()));
        when(costService.isEnabled()).thenReturn(true);

        var overview = svc.overview();

        assertThat(overview.costTrackingEnabled()).isTrue();
        // "neither" has no fee and no cost -> not listed at all.
        assertThat(overview.margins()).hasSize(3);

        var both = marginOf(overview, "both");
        assertThat(both.margin()).isEqualByComparingTo("687.55");

        var fee = marginOf(overview, "feeonly");
        assertThat(fee.monthlyFee()).isEqualByComparingTo("500.00");
        assertThat(fee.currentMonthCost()).isNull();
        assertThat(fee.margin()).isNull();

        var cost = marginOf(overview, "costonly");
        assertThat(cost.currentMonthCost()).isEqualByComparingTo("99.00");
        assertThat(cost.margin()).isNull();
    }

    private FleetOverviewService.OrgMargin marginOf(FleetOverviewService.FleetOverview o, String slug) {
        return o.margins().stream().filter(m -> m.orgSlug().equals(slug)).findFirst().orElseThrow();
    }
}
