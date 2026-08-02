package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.OrgCloudCost;
import com.zgate.controlcenter.repository.OrgCloudCostRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The cost half of margin-per-customer. Revenue is already known (subscription fee + usage
 * invoices); this pulls what each customer's infrastructure actually COSTS from AWS Cost Explorer,
 * grouped by the {@code zgate:org-id} tag every provisioned stack applies.
 *
 * <p>Only meaningful for VENDOR-hosted stacks (single-tenant SaaS): a stack provisioned into the
 * customer's own cloud account is billed to the customer directly and never shows up here — which
 * is correct, its infra cost is zero to the vendor. AWS credentials come from the standard
 * provider chain (instance role / env), the same posture as the ECR and S3 integrations.
 *
 * <p><b>Off by default</b> ({@code controlcenter.cost.enabled=false}): calling Cost Explorer costs
 * money (per request) and needs the {@code ce:GetCostAndUsage} permission — an operator opts in.
 * Cost Explorer tag grouping also requires {@code zgate:org-id} to be activated as a cost
 * allocation tag in the AWS billing console (it takes ~24h to start flowing).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CloudCostService {

    static final String TAG_KEY = "zgate:org-id";
    private static final String SOURCE = "AWS_CE";

    private final OrgCloudCostRepository costRepo;
    private final OrganizationRepository orgRepo;

    @Value("${controlcenter.cost.enabled:false}")
    private boolean enabled;

    @Value("${controlcenter.cost.aws.region:eu-west-2}")
    private String region;

    /** How many months back each sync re-fetches — bills firm up for a while after month end. */
    @Value("${controlcenter.cost.monthsBack:2}")
    private int monthsBack;

    /** The currency margins are computed in — rows reported in anything else are refused. */
    @Value("${controlcenter.billing.currencyDefault:USD}")
    private String expectedCurrency;

    @Scheduled(cron = "${controlcenter.cost.cron:0 30 5 * * *}")
    @SchedulerLock(name = "cloudCostSync", lockAtMostFor = "PT30M")
    public void sync() {
        if (!enabled) return;
        try (CostExplorerClient ce = CostExplorerClient.builder().region(Region.of(region)).build()) {
            LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(Math.max(0, monthsBack));
            LocalDate end = LocalDate.now().plusDays(1);

            GetCostAndUsageResponse resp = ce.getCostAndUsage(GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder()
                    .start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost")
                .groupBy(GroupDefinition.builder()
                    .type(GroupDefinitionType.TAG).key(TAG_KEY).build())
                .build());

            int rows = 0;
            for (ResultByTime period : resp.resultsByTime()) {
                LocalDate month = LocalDate.parse(period.timePeriod().start()).withDayOfMonth(1);
                for (Group group : period.groups()) {
                    rows += upsert(month, group) ? 1 : 0;
                }
            }
            log.info("Cloud cost sync: {} org-month rows updated ({} → {})", rows, start, end);
        } catch (Exception e) {
            log.warn("Cloud cost sync failed: {}", e.getMessage());
        }
    }

    private boolean upsert(LocalDate month, Group group) {
        // Cost Explorer renders the tag group as "zgate:org-id$<value>"; untagged spend has an
        // empty value and is not attributable to a customer.
        String raw = group.keys().isEmpty() ? "" : group.keys().get(0);
        String value = raw.contains("$") ? raw.substring(raw.indexOf('$') + 1) : "";
        if (value.isBlank()) return false;

        UUID orgId;
        try {
            orgId = UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (orgRepo.findById(orgId).isEmpty()) return false;

        MetricValue metric = group.metrics().get("UnblendedCost");
        if (metric == null) return false;
        BigDecimal amount = new BigDecimal(metric.amount()).setScale(2, java.math.RoundingMode.HALF_UP);
        String currency = metric.unit() == null ? "USD" : metric.unit();
        // Margin later subtracts this from the (defaultCurrency) subscription fee. A row in any
        // other currency must be refused HERE — subtracting across currencies silently corrupts
        // the number, and a wrong margin is worse than a missing one.
        if (!expectedCurrency.equalsIgnoreCase(currency)) {
            log.warn("Cloud cost for org {} month {} is in {} (expected {}) — row skipped. "
                   + "Align controlcenter.billing.currencyDefault or the payer account currency.",
                     orgId, month, currency, expectedCurrency);
            return false;
        }

        OrgCloudCost row = costRepo
            .findByOrganizationIdAndMonthAndSource(orgId, month, SOURCE)
            .orElseGet(() -> OrgCloudCost.builder()
                .organizationId(orgId).month(month).source(SOURCE).amount(BigDecimal.ZERO).build());
        row.setAmount(amount);
        row.setCurrency(currency);
        costRepo.save(row);
        return true;
    }

    /** Current-month cost rows, for the fleet overview's margin column. */
    public List<OrgCloudCost> currentMonth() {
        return costRepo.findByMonth(LocalDate.now().withDayOfMonth(1));
    }

    public List<OrgCloudCost> historyForOrg(UUID orgId) {
        return costRepo.findByOrganizationIdOrderByMonthDesc(orgId);
    }

    public boolean isEnabled() { return enabled; }
}
