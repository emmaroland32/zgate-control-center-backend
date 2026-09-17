package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telemetry end to end: the tokenless ingest contract (header-bound org, batch ceiling, heartbeat),
 * the operator query surface (filters, stats, acknowledgements), the raw CSV export, and the
 * licensing-anomaly summary.
 */
class TelemetryIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/telemetry";
    private static final String ORGS = "/api/v1/organizations";
    private static final String APEX = "b0000001-0000-0000-0000-000000000001";

    // ── helpers ─────────────────────────────────────────────────────────────

    private String createOrg(String token, String prefix) throws Exception {
        String slug = uniqueSlug(prefix);
        MvcResult r = post(ORGS, token, Map.of("name", "Tel Org " + slug, "slug", slug,
            "tier", "STANDARD", "deploymentEnv", "PRODUCTION"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r).path("id").asText();
    }

    private static Map<String, Object> event(String level, String category, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("level", level);
        m.put("category", category);
        m.put("message", message);
        return m;
    }

    private static Map<String, String> orgHeader(String orgId) {
        return Map.of("X-Control-Center-Org-Id", orgId);
    }

    private MvcResult ingest(String orgId, List<Map<String, Object>> events) throws Exception {
        return postWith(BASE + "/ingest", null, events, orgHeader(orgId));
    }

    private void ingestOk(String orgId, List<Map<String, Object>> events) throws Exception {
        MvcResult r = ingest(orgId, events);
        assertThat(status(r)).as(text(r)).isEqualTo(202);
        // ResponseEntity<Void> still passes through ApiResponseAdvice: the body is the envelope with a null payload.
        assertThat(data(r).isNull()).as(text(r)).isTrue();
    }

    private JsonNode search(String token, String query) throws Exception {
        MvcResult r = get(BASE + (query.isEmpty() ? "" : "?" + query), token);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        return data(r);
    }

    private JsonNode orgStats(String token, String orgId) throws Exception {
        return data(get(BASE + "/stats/org/" + orgId, token));
    }

    // ── ingest ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ingest is tokenless and 202: events are stamped with the header org (body organizationId ignored) and the org's heartbeat moves")
    void ingestStampsHeaderOrg() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-tel");
        assertThat(data(get(ORGS + "/" + orgId, token)).path("lastSeenAt").isNull()).as("never seen").isTrue();

        Map<String, Object> spoofed = event("ERROR", "SYSTEM", "boom");
        spoofed.put("organizationId", APEX);          // must be overwritten by the header
        spoofed.put("errorCode", "E-IT");
        spoofed.put("appVersion", "2.5.0");
        spoofed.put("host", "node-1");
        spoofed.put("occurredAt", "2026-01-02T03:04:05");
        Map<String, Object> warn = event("WARNING", "API", "slow");
        Map<String, Object> info = event("INFO", "AUTH", "login");
        ingestOk(orgId, List.of(spoofed, warn, info));

        JsonNode page = search(token, "orgId=" + orgId);
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        for (JsonNode e : page.path("content")) {
            assertThat(e.path("organizationId").asText()).as("header wins over body").isEqualTo(orgId);
            assertThat(e.path("acknowledged").asBoolean()).isFalse();
            assertThat(e.path("occurredAt").isTextual()).isTrue();
            assertThat(e.path("receivedAt").isTextual()).isTrue();
            assertThat(e.path("id").isTextual()).isTrue();
        }
        JsonNode err = null;
        for (JsonNode e : page.path("content")) if ("boom".equals(e.path("message").asText())) err = e;
        assertThat(err).isNotNull();
        assertThat(err.path("level").asText()).isEqualTo("ERROR");
        assertThat(err.path("category").asText()).isEqualTo("SYSTEM");
        assertThat(err.path("errorCode").asText()).isEqualTo("E-IT");
        assertThat(err.path("appVersion").asText()).isEqualTo("2.5.0");
        assertThat(err.path("occurredAt").asText()).as("a supplied occurredAt is kept").startsWith("2026-01-02T03:04:05");

        // Nothing leaked onto the org named in the body.
        for (JsonNode e : search(token, "orgId=" + APEX).path("content")) {
            assertThat(e.path("message").asText()).isNotEqualTo("boom");
        }

        JsonNode org = data(get(ORGS + "/" + orgId, token));
        assertThat(org.path("lastSeenAt").isTextual()).as("heartbeat stamped").isTrue();

        // Runtime headers are optional and harmless.
        Map<String, String> h = new HashMap<>(orgHeader(orgId));
        h.put("X-Control-Center-Fingerprint", "fp-1");
        h.put("X-Control-Center-Node-Id", "node-1");
        h.put("X-Control-Center-Platform", "docker");
        h.put("X-Control-Center-Mem-Used-Mb", "256");
        h.put("X-Control-Center-Mem-Max-Mb", "1024");
        h.put("X-Control-Center-Uptime-Sec", "99");
        h.put("X-Control-Center-Cpu-Pct", "5");
        assertThat(status(postWith(BASE + "/ingest", null, List.of(event("METRIC", "PERFORMANCE", "m")), h))).isEqualTo(202);
        assertThat(search(token, "orgId=" + orgId).path("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("ingest for an unknown org is a 400 and writes nothing; a malformed org header is a 400; a bad level names the allowed values")
    void ingestRejections() throws Exception {
        String token = adminToken();
        String ghost = UUID.randomUUID().toString();
        MvcResult r = ingest(ghost, List.of(event("ERROR", "SYSTEM", "orphan")));
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r).path("message").asText()).contains("Organization not found").contains(ghost);
        assertThat(search(token, "orgId=" + ghost).path("totalElements").asLong()).isZero();

        MvcResult badHeader = ingest("not-a-uuid", List.of(event("ERROR", "SYSTEM", "x")));
        assertThat(status(badHeader)).isEqualTo(400);

        String orgId = createOrg(token, "it-tel-bad");
        MvcResult badLevel = ingest(orgId, List.of(event("FATAL", "SYSTEM", "x")));
        assertThat(status(badLevel)).isEqualTo(400);
        assertThat(code(badLevel)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(badLevel).path("message").asText()).contains("ERROR, WARNING, INFO, METRIC");
        assertThat(search(token, "orgId=" + orgId).path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("ingest without the org header is a 400, not a 500")
    void ingestMissingHeaderIs400() throws Exception {
        MvcResult r = post(BASE + "/ingest", null, List.of(event("ERROR", "SYSTEM", "x")));
        assertThat(status(r)).isEqualTo(400);
    }

    @Test
    @DisplayName("a batch above the ceiling is 413 and nothing from it is written; the ceiling itself is accepted")
    void batchCeiling() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-tel-batch");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) tooMany.add(event("INFO", "SYSTEM", "bulk-" + i));
        MvcResult r = ingest(orgId, tooMany);
        assertThat(status(r)).isEqualTo(413);
        assertThat(data(r).isNull()).as("no events echoed back").isTrue();
        assertThat(search(token, "orgId=" + orgId).path("totalElements").asLong()).as("all-or-nothing").isZero();
        assertThat(data(get(ORGS + "/" + orgId, token)).path("lastSeenAt").isNull()).as("no heartbeat either").isTrue();

        ingestOk(orgId, tooMany.subList(0, 1000));
        assertThat(search(token, "orgId=" + orgId + "&size=1").path("totalElements").asLong()).isEqualTo(1000);
    }

    // ── query ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the list filters by org, level, category and acknowledged, pages, and rejects a bad level")
    void listFilters() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-tel-filter");
        ingestOk(orgId, List.of(
            event("ERROR", "SYSTEM", "e1"), event("ERROR", "DATABASE", "e2"),
            event("WARNING", "API", "w1"), event("INFO", "AUTH", "i1")));

        JsonNode all = search(token, "orgId=" + orgId);
        assertThat(all.path("totalElements").asLong()).isEqualTo(4);
        assertThat(all.path("content").size()).isEqualTo(4);

        JsonNode errors = search(token, "orgId=" + orgId + "&level=ERROR");
        assertThat(errors.path("totalElements").asLong()).isEqualTo(2);
        for (JsonNode e : errors.path("content")) assertThat(e.path("level").asText()).isEqualTo("ERROR");

        JsonNode db = search(token, "orgId=" + orgId + "&category=DATABASE");
        assertThat(db.path("totalElements").asLong()).isEqualTo(1);
        assertThat(db.path("content").get(0).path("message").asText()).isEqualTo("e2");

        JsonNode paged = search(token, "orgId=" + orgId + "&page=0&size=3");
        assertThat(paged.path("content").size()).isEqualTo(3);
        assertThat(paged.path("totalPages").asInt()).isEqualTo(2);
        assertThat(search(token, "orgId=" + orgId + "&page=1&size=3").path("content").size()).isEqualTo(1);

        String w1 = db.path("content").get(0).path("id").asText();
        assertThat(status(post(BASE + "/" + w1 + "/acknowledge", token, null))).isEqualTo(200);
        assertThat(search(token, "orgId=" + orgId + "&acknowledged=true").path("totalElements").asLong()).isEqualTo(1);
        assertThat(search(token, "orgId=" + orgId + "&acknowledged=false").path("totalElements").asLong()).isEqualTo(3);

        // A window that excludes everything, then one that includes it.
        assertThat(search(token, "orgId=" + orgId + "&to=2000-01-01T00:00:00").path("totalElements").asLong()).isZero();
        assertThat(search(token, "orgId=" + orgId + "&from=2000-01-01T00:00:00").path("totalElements").asLong()).isEqualTo(4);

        MvcResult bad = get(BASE + "?level=FATAL", token);
        assertThat(status(bad)).isEqualTo(400);
        assertThat(code(bad)).isEqualTo("INVALID_PARAMETER");

        // The unfiltered list is the fleet-wide view and includes ours.
        assertThat(search(token, "size=1").path("totalElements").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(status(get(BASE, null))).isEqualTo(401);
    }

    // ── stats + acknowledgements ────────────────────────────────────────────

    @Test
    @DisplayName("stats count unacknowledged errors/warnings and last-24h errors; acknowledge and acknowledge-all move them")
    void statsAndAcknowledge() throws Exception {
        String root = adminToken();
        String orgId = createOrg(root, "it-tel-stats");
        String supportEmail = unique("it-tel-support");
        createOperator(root, supportEmail, "SUPPORT", GOOD_PW);
        String support = login(supportEmail, GOOD_PW);

        JsonNode g0 = data(get(BASE + "/stats", root));
        long gErr0 = g0.path("unacknowledgedErrors").asLong(), gWarn0 = g0.path("unacknowledgedWarnings").asLong(),
             g24h0 = g0.path("errorsLast24h").asLong();

        Map<String, Object> old = event("ERROR", "SYSTEM", "old");
        old.put("occurredAt", "2020-01-01T00:00:00");   // outside the 24h window
        ingestOk(orgId, List.of(event("ERROR", "SYSTEM", "e1"), event("ERROR", "API", "e2"),
            event("WARNING", "API", "w1"), event("INFO", "AUTH", "i1"), old));

        JsonNode s = orgStats(root, orgId);
        assertThat(s.path("unacknowledgedErrors").asLong()).isEqualTo(3);
        assertThat(s.path("unacknowledgedWarnings").asLong()).isEqualTo(1);
        assertThat(s.path("errorsLast24h").asLong()).as("the 2020 event is outside the window").isEqualTo(2);
        JsonNode g1 = data(get(BASE + "/stats", root));
        assertThat(g1.path("unacknowledgedErrors").asLong()).isEqualTo(gErr0 + 3);
        assertThat(g1.path("unacknowledgedWarnings").asLong()).isEqualTo(gWarn0 + 1);
        assertThat(g1.path("errorsLast24h").asLong()).isEqualTo(g24h0 + 2);

        String e1 = search(root, "orgId=" + orgId + "&category=SYSTEM&from=2025-01-01T00:00:00").path("content").get(0).path("id").asText();
        MvcResult ack = post(BASE + "/" + e1 + "/acknowledge", support, null);
        assertThat(status(ack)).as(text(ack)).isEqualTo(200);
        assertThat(code(ack)).isEqualTo("EVENT_ACKNOWLEDGED");
        assertThat(data(ack).path("acknowledged").asBoolean()).isTrue();
        assertThat(data(ack).path("acknowledgedBy").asText()).isEqualTo(supportEmail);
        assertThat(data(ack).path("acknowledgedAt").isTextual()).isTrue();
        assertThat(orgStats(root, orgId).path("unacknowledgedErrors").asLong()).isEqualTo(2);
        assertThat(orgStats(root, orgId).path("errorsLast24h").asLong()).as("acknowledging does not change the 24h count").isEqualTo(2);

        MvcResult unknown = post(BASE + "/" + UUID.randomUUID() + "/acknowledge", support, null);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("Telemetry event not found");

        MvcResult warnOnly = post(BASE + "/acknowledge-all?orgId=" + orgId + "&level=WARNING", support, null);
        assertThat(status(warnOnly)).isEqualTo(200);
        assertThat(code(warnOnly)).isEqualTo("EVENTS_ACKNOWLEDGED");
        assertThat(data(warnOnly).path("ok").asBoolean()).isTrue();
        JsonNode s2 = orgStats(root, orgId);
        assertThat(s2.path("unacknowledgedWarnings").asLong()).isZero();
        assertThat(s2.path("unacknowledgedErrors").asLong()).as("level filter respected").isEqualTo(2);

        assertThat(status(post(BASE + "/acknowledge-all?orgId=" + orgId, support, null))).isEqualTo(200);
        assertThat(orgStats(root, orgId).path("unacknowledgedErrors").asLong()).isZero();
        assertThat(search(root, "orgId=" + orgId + "&acknowledged=false").path("totalElements").asLong()).isZero();
        for (JsonNode e : search(root, "orgId=" + orgId).path("content")) {
            assertThat(e.path("acknowledgedBy").asText()).isEqualTo(supportEmail);
        }

        String viewer = tokenFor(root, "VIEWER");
        assertThat(status(get(BASE + "/stats", viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/stats/org/" + orgId, viewer))).isEqualTo(200);
        MvcResult denied = post(BASE + "/" + e1 + "/acknowledge", viewer, null);
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
        assertThat(status(post(BASE + "/acknowledge-all?orgId=" + orgId, viewer, null))).isEqualTo(403);
    }

    // ── export ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV export is raw text/csv with the documented header, quoted messages and no envelope; SUPPORT may, VIEWER may not")
    void exportCsv() throws Exception {
        String root = adminToken();
        String orgId = createOrg(root, "it-tel-csv");
        ingestOk(orgId, List.of(event("ERROR", "SYSTEM", "say \"hi\", twice"), event("WARNING", "API", "plain")));
        String e = search(root, "orgId=" + orgId + "&level=WARNING").path("content").get(0).path("id").asText();
        assertThat(status(post(BASE + "/" + e + "/acknowledge", root, null))).isEqualTo(200);

        String support = tokenFor(root, "SUPPORT");
        MvcResult r = get(BASE + "/export/csv?orgId=" + orgId, support);
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(r.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=telemetry-export.csv");
        assertThat(r.getResponse().getHeader("X-Api-Envelope")).isNull();
        String csv = text(r);
        assertThat(csv).doesNotStartWith("{");
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("Timestamp,Organization,Level,Category,Message,Acknowledged");
        assertThat(lines).hasSize(3);
        String joined = csv;
        assertThat(joined).contains("," + orgId + ",ERROR,SYSTEM,\"say \"\"hi\"\", twice\",false");
        assertThat(joined).contains("," + orgId + ",WARNING,API,\"plain\",true");

        MvcResult errorsOnly = get(BASE + "/export/csv?orgId=" + orgId + "&level=ERROR", root);
        assertThat(text(errorsOnly).split("\n")).hasSize(2);

        MvcResult none = get(BASE + "/export/csv?orgId=" + UUID.randomUUID(), root);
        assertThat(status(none)).isEqualTo(200);
        assertThat(text(none)).isEqualTo("Timestamp,Organization,Level,Category,Message,Acknowledged\n");

        String viewer = tokenFor(root, "VIEWER");
        MvcResult denied = get(BASE + "/export/csv", viewer);
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
    }

    // ── license summary ─────────────────────────────────────────────────────

    @Test
    @DisplayName("license-summary aggregates open LICENSE anomalies by code and org and lists the recent ones")
    void licenseSummary() throws Exception {
        String root = adminToken();
        JsonNode s0 = data(get(BASE + "/license-summary", root));
        assertThat(s0.path("total").isNumber()).isTrue();
        assertThat(s0.path("affectedOrgs").isNumber()).isTrue();
        assertThat(s0.path("byCode").isObject()).isTrue();
        assertThat(s0.path("recent").isArray()).isTrue();
        long total0 = s0.path("total").asLong();
        long overVersion0 = s0.path("byCode").path("OVER_VERSION").asLong(0);
        long affected0 = s0.path("affectedOrgs").asLong();

        String orgId = createOrg(root, "it-tel-lic");
        Map<String, Object> a = event("WARNING", "LICENSE", "running 2.6.0 beyond entitlement");
        a.put("errorCode", "OVER_VERSION");
        Map<String, Object> b = event("ERROR", "LICENSE", "no active licence");
        b.put("errorCode", "IT_UNLICENSED_" + orgId.substring(0, 8));
        Map<String, Object> notLicense = event("ERROR", "SYSTEM", "unrelated");
        notLicense.put("errorCode", "OVER_VERSION");   // wrong category: must not count
        ingestOk(orgId, List.of(a, b, notLicense));

        JsonNode s1 = data(get(BASE + "/license-summary", root));
        assertThat(s1.path("total").asLong()).isEqualTo(total0 + 2);
        assertThat(s1.path("byCode").path("OVER_VERSION").asLong()).isEqualTo(overVersion0 + 1);
        assertThat(s1.path("byCode").path(b.get("errorCode").toString()).asLong()).isEqualTo(1);
        assertThat(s1.path("affectedOrgs").asLong()).isEqualTo(affected0 + 1);
        boolean seen = false;
        for (JsonNode e : s1.path("recent")) {
            assertThat(e.path("category").asText()).isEqualTo("LICENSE");
            assertThat(e.path("acknowledged").asBoolean()).isFalse();
            if (orgId.equals(e.path("organizationId").asText())) seen = true;
        }
        assertThat(seen).isTrue();

        // Acknowledging closes them.
        assertThat(status(post(BASE + "/acknowledge-all?orgId=" + orgId, root, null))).isEqualTo(200);
        JsonNode s2 = data(get(BASE + "/license-summary", root));
        assertThat(s2.path("total").asLong()).isEqualTo(total0);
        assertThat(s2.path("affectedOrgs").asLong()).isEqualTo(affected0);
        assertThat(s2.path("byCode").has(b.get("errorCode").toString())).isFalse();

        assertThat(status(get(BASE + "/license-summary", tokenFor(root, "VIEWER")))).isEqualTo(200);
    }
}
