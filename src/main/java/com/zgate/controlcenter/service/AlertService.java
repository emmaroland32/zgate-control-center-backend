package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.AlertRepository;
import com.zgate.controlcenter.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRuleRepository ruleRepo;
    private final AlertRepository alertRepo;

    public List<AlertRule> findAllRules() { return ruleRepo.findAll(); }
    public List<Alert> findActive() { return alertRepo.findByStatusIn(List.of(Alert.Status.FIRING, Alert.Status.ACKNOWLEDGED)); }
    public List<Alert> findAll() { return alertRepo.findAll(); }

    public AlertRule createRule(AlertRule rule) { return ruleRepo.save(rule); }

    public AlertRule updateRule(UUID id, AlertRule updated) {
        AlertRule rule = ruleRepo.findById(id).orElseThrow(() -> new ControlCenterException("Rule not found"));
        rule.setName(updated.getName());
        rule.setDescription(updated.getDescription());
        rule.setSeverity(updated.getSeverity());
        rule.setMetric(updated.getMetric());
        rule.setOperator(updated.getOperator());
        rule.setThreshold(updated.getThreshold());
        rule.setChannels(updated.getChannels());
        rule.setEnabled(updated.isEnabled());
        return ruleRepo.save(rule);
    }

    public void deleteRule(UUID id) { ruleRepo.deleteById(id); }

    /** Flip a rule's enabled flag without touching (and wiping) its other required columns. */
    public AlertRule toggleRule(UUID id) {
        AlertRule rule = ruleRepo.findById(id).orElseThrow(() -> new ControlCenterException("Rule not found"));
        rule.setEnabled(!rule.isEnabled());
        return ruleRepo.save(rule);
    }

    public Alert fire(UUID ruleId, UUID orgId, Double value, String title, String message) {
        return alertRepo.save(Alert.builder()
            .ruleId(ruleId)
            .organizationId(orgId)
            .status(Alert.Status.FIRING)
            .severity(ruleRepo.findById(ruleId)
                .map(AlertRule::getSeverity).orElse(AlertRule.Severity.MEDIUM))
            .title(title)
            .message(message)
            .metricValue(value)
            .build());
    }

    public Alert acknowledge(UUID id, String by) {
        Alert alert = alertRepo.findById(id).orElseThrow(() -> new ControlCenterException("Alert not found"));
        alert.setStatus(Alert.Status.ACKNOWLEDGED);
        alert.setAcknowledgedBy(by);
        alert.setAcknowledgedAt(LocalDateTime.now());
        return alertRepo.save(alert);
    }

    public Alert resolve(UUID id) {
        Alert alert = alertRepo.findById(id).orElseThrow(() -> new ControlCenterException("Alert not found"));
        alert.setStatus(Alert.Status.RESOLVED);
        alert.setResolvedAt(LocalDateTime.now());
        return alertRepo.save(alert);
    }
}
