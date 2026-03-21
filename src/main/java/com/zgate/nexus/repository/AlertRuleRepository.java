package com.zgate.nexus.repository;

import com.zgate.nexus.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {
    List<AlertRule> findByEnabled(boolean enabled);
    List<AlertRule> findByOrgIdOrOrgScope(UUID orgId, AlertRule.OrgScope scope);
}
