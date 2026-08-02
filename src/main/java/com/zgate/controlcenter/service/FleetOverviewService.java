package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.*;
import com.zgate.controlcenter.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single fleet-wide answer to "how is every customer doing right now": health rollup, version
 * spread against the latest release, per-stack state, subscriptions about to lapse, backup
 * freshness, and live rollouts — one read, no JSON parsing, built entirely from columns the rest of
 * the system already maintains.
 */
@Service
@RequiredArgsConstructor
public class FleetOverviewService {

    private final OrganizationRepository orgRepo;
    private final InfrastructureStackRepository stackRepo;
    private final ReleaseRepository releaseRepo;
    private final BackupPlanRepository backupPlanRepo;
    private final BackupRecordRepository backupRecordRepo;
    private final FleetRolloutRepository rolloutRepo;
    private final CloudCostService cloudCostService;

    /** A managed backup older than this is stale — just over a daily cycle, with slack for a slow run. */
    @Value("${controlcenter.fleet.backupStaleHours:26}")
    private int backupStaleHours;

    @Value("${controlcenter.fleet.subscriptionLapseWarnDays:30}")
    private int lapseWarnDays;

    /**
     * Surfaced so the console can warn loudly while machine-to-machine auth is org-UUID-only.
     * The default stays false (flipping it would cut off phone-home for instances not yet
     * configured with their service key, and lapsed license sync eventually locks paying
     * customers) — but running a fleet with it off deserves a permanent banner.
     */
    @Value("${controlcenter.serviceKey.enforce:false}")
    private boolean serviceKeyEnforced;

    public FleetOverview overview() {
        List<Organization> orgs = orgRepo.findAll();
        Map<UUID, Organization> orgById = orgs.stream()
            .collect(Collectors.toMap(Organization::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now();

        Map<String, Long> orgsByStatus = orgs.stream().collect(Collectors.groupingBy(
            o -> o.getDeploymentStatus().name(), TreeMap::new, Collectors.counting()));

        Map<String, Long> versionSpread = orgs.stream()
            .filter(o -> o.getDeployedVersion() != null && !o.getDeployedVersion().isBlank())
            .collect(Collectors.groupingBy(Organization::getDeployedVersion, TreeMap::new,
                                           Collectors.counting()));

        String latestRelease = releaseRepo.findByIsLatestTrue().map(Release::getVersion).orElse(null);

        List<StackSummary> stacks = stackRepo.findAll().stream()
            .filter(s -> s.getStatus() != InfrastructureStack.Status.DESTROYED)
            .map(s -> {
                Organization org = orgById.get(s.getOrganizationId());
                return new StackSummary(
                    s.getId(), s.getOrganizationId(),
                    org == null ? null : org.getName(), org == null ? null : org.getSlug(),
                    s.getEnvironment(), s.getTarget().slug(), s.getReleaseVersion(),
                    s.getStatus().name(), s.isDriftDetected(), s.getLastAppliedAt(), s.getPublicUrl());
            })
            .sorted(Comparator.comparing(StackSummary::orgSlug, Comparator.nullsLast(String::compareTo))
                              .thenComparing(StackSummary::environment))
            .toList();

        List<SubscriptionLapse> lapsingSoon = orgs.stream()
            .filter(o -> o.getSubscriptionValidUntil() != null)
            .filter(o -> o.getSubscriptionValidUntil().isBefore(now.plusDays(lapseWarnDays)))
            .map(o -> new SubscriptionLapse(o.getId(), o.getName(), o.getSlug(),
                o.getSubscriptionValidUntil(), o.getSubscriptionValidUntil().isBefore(now)))
            .sorted(Comparator.comparing(SubscriptionLapse::validUntil))
            .toList();

        List<BackupHealth> backups = backupPlanRepo.findAll().stream()
            .filter(BackupPlan::isEnabled)
            .map(plan -> {
                Organization org = orgById.get(plan.getOrganizationId());
                LocalDateTime last = backupRecordRepo
                    .findFirstByOrganizationIdAndStatusOrderByCompletedAtDesc(
                        plan.getOrganizationId(), BackupRecord.Status.COMPLETED)
                    .map(BackupRecord::getCompletedAt).orElse(null);
                boolean stale = last == null || Duration.between(last, now).toHours() >= backupStaleHours;
                return new BackupHealth(plan.getOrganizationId(),
                    org == null ? null : org.getName(), org == null ? null : org.getSlug(),
                    last, stale);
            })
            .sorted(Comparator.comparing(BackupHealth::orgSlug, Comparator.nullsLast(String::compareTo)))
            .toList();

        long liveRollouts = rolloutRepo.findByStatusIn(List.of(
            FleetRollout.Status.PENDING, FleetRollout.Status.IN_PROGRESS,
            FleetRollout.Status.PAUSED)).size();

        // Margin per customer: monthly subscription fee vs what their infrastructure cost the
        // vendor this month (vendor-hosted stacks only — customer-account stacks cost us nothing).
        Map<UUID, java.math.BigDecimal> costByOrg = cloudCostService.currentMonth().stream()
            .collect(Collectors.toMap(OrgCloudCost::getOrganizationId, OrgCloudCost::getAmount,
                                      java.math.BigDecimal::add));
        List<OrgMargin> margins = orgs.stream()
            .filter(o -> o.getSubscriptionMonthlyFee() != null || costByOrg.containsKey(o.getId()))
            .map(o -> {
                java.math.BigDecimal fee = o.getSubscriptionMonthlyFee();
                java.math.BigDecimal cost = costByOrg.get(o.getId());
                java.math.BigDecimal margin = fee != null && cost != null ? fee.subtract(cost) : null;
                return new OrgMargin(o.getId(), o.getName(), o.getSlug(), fee, cost, margin);
            })
            .sorted(Comparator.comparing(OrgMargin::orgSlug, Comparator.nullsLast(String::compareTo)))
            .toList();

        return new FleetOverview(orgs.size(), orgsByStatus, versionSpread, latestRelease,
                                 stacks, lapsingSoon, backups, liveRollouts, serviceKeyEnforced,
                                 cloudCostService.isEnabled(), margins);
    }

    // ── Payload ─────────────────────────────────────────────────────────────

    public record FleetOverview(
        long totalOrgs,
        Map<String, Long> orgsByStatus,
        Map<String, Long> versionSpread,
        String latestRelease,
        List<StackSummary> stacks,
        List<SubscriptionLapse> subscriptionsLapsingSoon,
        List<BackupHealth> backups,
        long liveRollouts,
        boolean serviceKeyEnforced,
        boolean costTrackingEnabled,
        List<OrgMargin> margins) {}

    /** fee/cost/margin are null when unknown — never silently zero (a zero margin is a real number). */
    public record OrgMargin(UUID organizationId, String orgName, String orgSlug,
                            java.math.BigDecimal monthlyFee, java.math.BigDecimal currentMonthCost,
                            java.math.BigDecimal margin) {}

    public record StackSummary(UUID stackId, UUID organizationId, String orgName, String orgSlug,
                               String environment, String target, String releaseVersion,
                               String status, boolean driftDetected, LocalDateTime lastAppliedAt,
                               String publicUrl) {}

    public record SubscriptionLapse(UUID organizationId, String orgName, String orgSlug,
                                    LocalDateTime validUntil, boolean lapsed) {}

    public record BackupHealth(UUID organizationId, String orgName, String orgSlug,
                               LocalDateTime lastSuccessfulBackupAt, boolean stale) {}
}
