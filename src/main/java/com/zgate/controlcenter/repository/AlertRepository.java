package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByStatus(Alert.Status status);
    List<Alert> findByStatusIn(List<Alert.Status> statuses);
    List<Alert> findByOrganizationIdAndStatus(UUID orgId, Alert.Status status);
    long countBySeverityAndStatus(AlertRule.Severity severity, Alert.Status status);
    List<Alert> findByRuleIdAndStatusIn(UUID ruleId, List<Alert.Status> statuses);
}
