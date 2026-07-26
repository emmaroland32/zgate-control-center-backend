package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import com.zgate.controlcenter.domain.Organization;
import com.zgate.controlcenter.domain.TelemetryEvent;
import com.zgate.controlcenter.repository.AlertRepository;
import com.zgate.controlcenter.repository.AlertRuleRepository;
import com.zgate.controlcenter.repository.LicenseRepository;
import com.zgate.controlcenter.repository.OrganizationRepository;
import com.zgate.controlcenter.repository.TelemetryEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Evaluates alert rules against real fleet metrics on a schedule and fires/resolves alerts. Nothing
 * previously called {@link AlertService#fire}, so the alerts and notifications screens were always
 * empty; this is what populates them.
 *
 * <p>Supported metrics (rule.metric, case-insensitive): {@code offline_deployments},
 * {@code degraded_deployments}, {@code unacknowledged_errors}, {@code expiring_licenses}. Operators:
 * {@code > >= < <= == !=} (or GT/GTE/LT/LTE/EQ/NEQ). A rule fires at most one open alert (dedup) and
 * auto-resolves when the condition clears.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "controlcenter.alerts.evaluator.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEvaluator {

    private final AlertRuleRepository ruleRepo;
    private final AlertRepository alertRepo;
    private final AlertService alertService;
    private final OrganizationRepository orgRepo;
    private final TelemetryEventRepository telemetryRepo;
    private final LicenseRepository licenseRepo;

    @Scheduled(fixedDelayString = "${controlcenter.alerts.evaluator.intervalMs:300000}", initialDelay = 60_000)
    public void evaluate() {
        List<AlertRule> rules = ruleRepo.findAll();
        for (AlertRule rule : rules) {
            if (!rule.isEnabled()) continue;
            try {
                evaluateRule(rule);
            } catch (Exception e) {
                log.warn("Alert rule {} evaluation failed: {}", rule.getId(), e.getMessage());
            }
        }
    }

    private void evaluateRule(AlertRule rule) {
        Double value = metricValue(rule.getMetric());
        if (value == null) return; // unknown metric — skip rather than firing spuriously

        boolean breached = compare(value, rule.getOperator(), rule.getThreshold());
        List<Alert> open = alertRepo.findByRuleIdAndStatusIn(
                rule.getId(), List.of(Alert.Status.FIRING, Alert.Status.ACKNOWLEDGED));

        if (breached && open.isEmpty()) {
            alertService.fire(rule.getId(), null, value, rule.getName(),
                    rule.getMetric() + " is " + fmt(value) + " (" + rule.getOperator() + " " + fmt(rule.getThreshold()) + ")");
            log.info("Alert fired: rule='{}' metric={} value={}", rule.getName(), rule.getMetric(), value);
        } else if (!breached && !open.isEmpty()) {
            open.forEach(a -> alertService.resolve(a.getId())); // condition cleared → auto-resolve
        }
    }

    /** Current value of a supported metric, or null if the metric key isn't recognized. */
    Double metricValue(String metric) {
        if (metric == null) return null;
        return switch (metric.trim().toLowerCase()) {
            case "offline_deployments", "offline_orgs", "deployments_offline" ->
                    (double) orgRepo.countByDeploymentStatus(Organization.DeploymentStatus.OFFLINE);
            case "degraded_deployments", "degraded_orgs" ->
                    (double) orgRepo.countByDeploymentStatus(Organization.DeploymentStatus.DEGRADED);
            case "unacknowledged_errors", "error_count", "errors" ->
                    (double) telemetryRepo.countByLevelAndAcknowledgedFalse(TelemetryEvent.Level.ERROR);
            case "expiring_licenses", "licenses_expiring" ->
                    (double) licenseRepo.findExpiringSoon(LocalDateTime.now().plusDays(30)).size();
            default -> null;
        };
    }

    static boolean compare(double value, String operator, Double threshold) {
        if (operator == null || threshold == null) return false;
        double t = threshold;
        return switch (operator.trim().toUpperCase()) {
            case ">", "GT", "GREATER_THAN" -> value > t;
            case ">=", "GTE", "GREATER_THAN_OR_EQUAL" -> value >= t;
            case "<", "LT", "LESS_THAN" -> value < t;
            case "<=", "LTE", "LESS_THAN_OR_EQUAL" -> value <= t;
            case "==", "EQ", "EQUALS" -> value == t;
            case "!=", "NEQ", "NOT_EQUALS" -> value != t;
            default -> false;
        };
    }

    private static String fmt(Double d) {
        if (d == null) return "—";
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }
}
