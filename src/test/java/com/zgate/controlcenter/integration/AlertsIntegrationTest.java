package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.Alert;
import com.zgate.controlcenter.domain.AlertRule;
import com.zgate.controlcenter.repository.AlertRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alert rules and the alert inbox over HTTP: the seeded rule catalogue, the raw-entity create /
 * update / toggle / delete paths, and acknowledge / resolve on alert rows. No alerts are seeded and
 * the evaluator + dispatcher are off in the harness, so every alert row here is inserted directly.
 */
class AlertsIntegrationTest extends AbstractIntegrationTest {

    private static final String RULES = "/api/v1/alerts/rules";
    private static final String ALERTS = "/api/v1/alerts";

    private static final List<String> SEEDED_RULES = List.of(
        "High Response Time", "Critical Response Time", "Low Uptime", "Disk Space Critical",
        "License Expiring Soon", "License Expired", "Deployment Failed", "Service Offline");

    @Autowired AlertRepository alertRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> minimalRule(String name) {
        return Map.of("name", name, "severity", "HIGH", "metric", "response_time_ms",
                      "operator", ">", "threshold", 2000, "enabled", true);
    }

    private JsonNode createRule(String token, Map<String, Object> body) throws Exception {
        MvcResult r = post(RULES, token, body);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ALERT_RULE_CREATED");
        return data(r);
    }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode n : array) if (id.equals(n.path("id").asText())) return true;
        return false;
    }

    /** A fresh organization so alert rows never point at (or mutate) a seeded one. */
    private UUID createOrgId(String token) throws Exception {
        MvcResult r = post("/api/v1/organizations", token, Map.of(
            "name", uniqueSlug("Alerts Org"), "slug", uniqueSlug("alerts-org"),
            "tier", "STANDARD", "deploymentEnv", "PRODUCTION"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return UUID.fromString(data(r).path("id").asText());
    }

    private Alert insertFiringAlert(UUID orgId, String title) {
        return alertRepo.save(Alert.builder()
            .organizationId(orgId)
            .status(Alert.Status.FIRING)
            .severity(AlertRule.Severity.HIGH)
            .title(title)
            .message("integration test alert")
            .metricValue(42.0)
            .build());
    }

    // ── rules ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the rule list carries the eight seeded rules and is readable by any role")
    void seededRulesListed() throws Exception {
        String admin = adminToken();
        MvcResult r = get(RULES, admin);
        assertThat(status(r)).isEqualTo(200);
        JsonNode list = data(r);
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isGreaterThanOrEqualTo(8);

        List<String> names = new ArrayList<>();
        list.forEach(n -> names.add(n.path("name").asText()));
        assertThat(names).containsAll(SEEDED_RULES);
        for (JsonNode n : list) {
            if (SEEDED_RULES.contains(n.path("name").asText())) {
                assertThat(n.path("orgScope").asText()).isEqualTo("ALL");
                assertThat(n.path("severity").asText()).isIn("CRITICAL", "HIGH", "MEDIUM", "LOW");
            }
        }

        assertThat(status(get(RULES, tokenFor(admin, "VIEWER")))).isEqualTo(200);
    }

    @Test
    @DisplayName("a minimal rule body is accepted and the entity defaults fill the rest")
    void createRuleMinimal() throws Exception {
        String admin = adminToken();
        String name = uniqueSlug("it-rule");
        JsonNode rule = createRule(admin, minimalRule(name));

        assertThat(rule.path("id").asText()).isNotBlank();
        assertThat(rule.path("name").asText()).isEqualTo(name);
        assertThat(rule.path("severity").asText()).isEqualTo("HIGH");
        assertThat(rule.path("metric").asText()).isEqualTo("response_time_ms");
        assertThat(rule.path("operator").asText()).isEqualTo(">");
        assertThat(rule.path("threshold").asDouble()).isEqualTo(2000.0);
        assertThat(rule.path("enabled").asBoolean()).isTrue();
        // Java-side field defaults, not sent in the body.
        assertThat(rule.path("evaluationWindowMinutes").asInt()).isEqualTo(5);
        assertThat(rule.path("cooldownMinutes").asInt()).isEqualTo(30);
        assertThat(rule.path("orgScope").asText()).isEqualTo("ALL");
        assertThat(rule.path("triggerCount").asLong()).isZero();
        assertThat(rule.path("createdAt").isMissingNode()).isFalse();

        assertThat(containsId(data(get(RULES, admin)), rule.path("id").asText())).isTrue();
    }

    @Test
    @DisplayName("PUT copies only the core fields: window, cooldown and org scope in the body are ignored")
    void updateRuleIsPartialCopy() throws Exception {
        String admin = adminToken();
        // alert_rules.org_id is a foreign key (V1:244), so a SPECIFIC scope needs a real org.
        UUID scopedOrg = createOrgId(admin);
        JsonNode created = createRule(admin, Map.ofEntries(
            Map.entry("name", uniqueSlug("it-upd")),
            Map.entry("description", "before"),
            Map.entry("severity", "HIGH"),
            Map.entry("metric", "disk_percent"),
            Map.entry("operator", ">"),
            Map.entry("threshold", 85),
            Map.entry("enabled", true),
            Map.entry("evaluationWindowMinutes", 10),
            Map.entry("cooldownMinutes", 60),
            Map.entry("channels", "[\"EMAIL\"]"),
            Map.entry("orgScope", "SPECIFIC"),
            Map.entry("orgId", scopedOrg.toString())));
        String id = created.path("id").asText();
        assertThat(created.path("evaluationWindowMinutes").asInt()).isEqualTo(10);
        assertThat(created.path("orgScope").asText()).isEqualTo("SPECIFIC");

        String renamed = uniqueSlug("it-upd-renamed");
        MvcResult r = put(RULES + "/" + id, admin, Map.ofEntries(
            Map.entry("name", renamed),
            Map.entry("description", "after"),
            Map.entry("severity", "LOW"),
            Map.entry("metric", "uptime_percent"),
            Map.entry("operator", "<"),
            Map.entry("threshold", 99.5),
            Map.entry("enabled", false),
            Map.entry("channels", "[\"SLACK\"]"),
            // AlertService.updateRule never reads these four from the body.
            Map.entry("evaluationWindowMinutes", 99),
            Map.entry("cooldownMinutes", 99),
            Map.entry("orgScope", "ALL")));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ALERT_RULE_UPDATED");
        JsonNode u = data(r);
        assertThat(u.path("name").asText()).isEqualTo(renamed);
        assertThat(u.path("description").asText()).isEqualTo("after");
        assertThat(u.path("severity").asText()).isEqualTo("LOW");
        assertThat(u.path("metric").asText()).isEqualTo("uptime_percent");
        assertThat(u.path("operator").asText()).isEqualTo("<");
        assertThat(u.path("threshold").asDouble()).isEqualTo(99.5);
        assertThat(u.path("enabled").asBoolean()).isFalse();
        assertThat(u.path("channels").asText()).isEqualTo("[\"SLACK\"]");
        // Actual behaviour: the stored values survive, the body's replacements are dropped
        // (AlertService.updateRule copies name/description/severity/metric/operator/threshold/
        // channels/enabled only). Toggle exists precisely so enabling never wipes these.
        assertThat(u.path("evaluationWindowMinutes").asInt()).as("window kept").isEqualTo(10);
        assertThat(u.path("cooldownMinutes").asInt()).as("cooldown kept").isEqualTo(60);
        assertThat(u.path("orgScope").asText()).as("scope kept").isEqualTo("SPECIFIC");
        assertThat(u.path("orgId").asText()).as("orgId kept").isEqualTo(scopedOrg.toString());

        MvcResult missing = put(RULES + "/" + UUID.randomUUID(), admin, minimalRule("x"));
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).isEqualTo("Rule not found");
    }

    @Test
    @DisplayName("toggle flips only the enabled flag and leaves every other column alone")
    void toggleRule() throws Exception {
        String admin = adminToken();
        JsonNode created = createRule(admin, Map.ofEntries(
            Map.entry("name", uniqueSlug("it-toggle")),
            Map.entry("severity", "MEDIUM"),
            Map.entry("metric", "license_days_left"),
            Map.entry("operator", "<"),
            Map.entry("threshold", 30),
            Map.entry("enabled", true),
            Map.entry("evaluationWindowMinutes", 1440),
            Map.entry("cooldownMinutes", 86400)));
        String id = created.path("id").asText();

        MvcResult off = patch(RULES + "/" + id + "/toggle", admin, null);
        assertThat(status(off)).as(text(off)).isEqualTo(200);
        assertThat(code(off)).isEqualTo("ALERT_RULE_TOGGLED");
        assertThat(data(off).path("enabled").asBoolean()).isFalse();
        assertThat(data(off).path("evaluationWindowMinutes").asInt()).isEqualTo(1440);
        assertThat(data(off).path("cooldownMinutes").asInt()).isEqualTo(86400);

        MvcResult on = patch(RULES + "/" + id + "/toggle", admin, null);
        assertThat(data(on).path("enabled").asBoolean()).isTrue();

        MvcResult missing = patch(RULES + "/" + UUID.randomUUID() + "/toggle", admin, null);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).isEqualTo("Rule not found");
    }

    @Test
    @DisplayName("delete removes the rule; a missing id is still a 200 (deleteById is silent)")
    void deleteRule() throws Exception {
        String admin = adminToken();
        String id = createRule(admin, minimalRule(uniqueSlug("it-del"))).path("id").asText();

        MvcResult r = delete(RULES + "/" + id, admin);
        assertThat(status(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ALERT_RULE_DELETED");
        assertThat(data(r).path("id").asText()).isEqualTo(id);
        assertThat(containsId(data(get(RULES, admin)), id)).isFalse();

        // Actual behaviour: AlertService.deleteRule calls deleteById, which is a no-op for an
        // unknown id — no 404, the same {"id"} echo comes back.
        MvcResult missing = delete(RULES + "/" + UUID.randomUUID(), admin);
        assertThat(status(missing)).isEqualTo(200);
        assertThat(code(missing)).isEqualTo("ALERT_RULE_DELETED");
    }

    // ── alerts ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a firing alert shows in /active and /, SUPPORT acknowledges it, resolve drops it from /active")
    void acknowledgeAndResolve() throws Exception {
        String admin = adminToken();
        String supportEmail = unique("it-alert-support");
        createOperator(admin, supportEmail, "SUPPORT", GOOD_PW);
        String support = login(supportEmail, GOOD_PW);

        UUID orgId = createOrgId(admin);
        Alert alert = insertFiringAlert(orgId, uniqueSlug("it-alert"));
        String id = alert.getId().toString();

        assertThat(containsId(data(get(ALERTS + "/active", admin)), id)).isTrue();
        assertThat(containsId(data(get(ALERTS, admin)), id)).isTrue();

        MvcResult ack = post(ALERTS + "/" + id + "/acknowledge", support, null);
        assertThat(status(ack)).as(text(ack)).isEqualTo(200);
        assertThat(code(ack)).isEqualTo("ALERT_ACKNOWLEDGED");
        assertThat(data(ack).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(data(ack).path("acknowledgedBy").asText()).isEqualTo(supportEmail);
        assertThat(data(ack).path("acknowledgedAt").isNull()).isFalse();
        assertThat(data(ack).path("organizationId").asText()).isEqualTo(orgId.toString());
        // ACKNOWLEDGED still counts as active.
        assertThat(containsId(data(get(ALERTS + "/active", admin)), id)).isTrue();

        MvcResult res = post(ALERTS + "/" + id + "/resolve", support, null);
        assertThat(status(res)).isEqualTo(200);
        assertThat(code(res)).isEqualTo("ALERT_RESOLVED");
        assertThat(data(res).path("status").asText()).isEqualTo("RESOLVED");
        assertThat(data(res).path("resolvedAt").isNull()).isFalse();
        assertThat(containsId(data(get(ALERTS + "/active", admin)), id)).isFalse();
        assertThat(containsId(data(get(ALERTS, admin)), id)).isTrue();

        Alert stored = alertRepo.findById(alert.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(Alert.Status.RESOLVED);
        assertThat(stored.getAcknowledgedBy()).isEqualTo(supportEmail);
        assertThat(stored.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("acknowledge and resolve on an unknown alert are a 400 'Alert not found'")
    void unknownAlertIs400() throws Exception {
        String admin = adminToken();
        String random = UUID.randomUUID().toString();

        MvcResult ack = post(ALERTS + "/" + random + "/acknowledge", admin, null);
        assertThat(status(ack)).isEqualTo(400);
        assertThat(body(ack).path("message").asText()).isEqualTo("Alert not found");

        MvcResult res = post(ALERTS + "/" + random + "/resolve", admin, null);
        assertThat(status(res)).isEqualTo(400);
        assertThat(body(res).path("message").asText()).isEqualTo("Alert not found");

        MvcResult notUuid = post(ALERTS + "/not-a-uuid/acknowledge", admin, null);
        assertThat(status(notUuid)).isEqualTo(400);
        assertThat(code(notUuid)).isEqualTo("INVALID_PARAMETER");
    }

    // ── roles ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VIEWER reads everything but every mutation is ACCESS_DENIED; SUPPORT may acknowledge but not manage rules")
    void roleGating() throws Exception {
        String admin = adminToken();
        String viewer = tokenFor(admin, "VIEWER");
        String support = tokenFor(admin, "SUPPORT");
        String ruleId = createRule(admin, minimalRule(uniqueSlug("it-role"))).path("id").asText();
        Alert alert = insertFiringAlert(createOrgId(admin), uniqueSlug("it-role-alert"));
        String alertId = alert.getId().toString();

        assertThat(status(get(RULES, viewer))).isEqualTo(200);
        assertThat(status(get(ALERTS + "/active", viewer))).isEqualTo(200);
        assertThat(status(get(ALERTS, viewer))).isEqualTo(200);

        MvcResult create = post(RULES, viewer, minimalRule("viewer"));
        assertThat(status(create)).isEqualTo(403);
        assertThat(code(create)).isEqualTo("ACCESS_DENIED");
        assertThat(code(put(RULES + "/" + ruleId, viewer, minimalRule("viewer")))).isEqualTo("ACCESS_DENIED");
        assertThat(code(patch(RULES + "/" + ruleId + "/toggle", viewer, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(delete(RULES + "/" + ruleId, viewer))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(ALERTS + "/" + alertId + "/acknowledge", viewer, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(ALERTS + "/" + alertId + "/resolve", viewer, null))).isEqualTo("ACCESS_DENIED");

        // SUPPORT: inbox actions yes, rule management no.
        assertThat(code(post(RULES, support, minimalRule("support")))).isEqualTo("ACCESS_DENIED");
        assertThat(code(patch(RULES + "/" + ruleId + "/toggle", support, null))).isEqualTo("ACCESS_DENIED");
        MvcResult ack = post(ALERTS + "/" + alertId + "/acknowledge", support, null);
        assertThat(status(ack)).isEqualTo(200);
        assertThat(data(ack).path("status").asText()).isEqualTo("ACKNOWLEDGED");

        // Nothing was touched by the refused calls.
        assertThat(containsId(data(get(RULES, admin)), ruleId)).isTrue();
        assertThat(data(get(RULES, admin)).findValuesAsText("id")).contains(ruleId);
        awaitAudit(auditAction("ACCESS_DENIED"));
    }
}
