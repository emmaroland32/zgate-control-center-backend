package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime configuration (keys, batches, snapshots, the registry probe) and the database admin
 * surface (health, Flyway history, connection/schema checks, and the control-plane backup gates in
 * their unconfigured state) — through the real role and step-up chain.
 */
class ConfigAndDatabaseIntegrationTest extends AbstractIntegrationTest {

    private static final String CONFIG = "/api/v1/config";
    private static final String DB = "/api/v1/database";

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String key(String suffix) {
        return "it." + uniqueSlug(suffix).replace('-', '.');
    }

    private JsonNode putValue(String token, String key, String value) throws Exception {
        MvcResult r = put(CONFIG + "/" + key, token, Map.of("value", value));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("CONFIG_SAVED");
        return data(r);
    }

    private JsonNode getKey(String token, String key) throws Exception {
        MvcResult r = get(CONFIG + "/" + key, token);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        return data(r);
    }

    private static JsonNode byKey(JsonNode list, String key) {
        for (JsonNode c : list) if (key.equals(c.path("key").asText())) return c;
        return null;
    }

    // ── config: reads ───────────────────────────────────────────────────────

    @Test
    @DisplayName("seeded keys are readable by key, list and category; the value field is 'value'/'key'/'type'/'lastUpdated'; VIEWER is refused")
    void seededReads() throws Exception {
        String token = adminToken();
        JsonNode name = getKey(token, "controlcenter.app.name");
        assertThat(name.path("key").asText()).isEqualTo("controlcenter.app.name");
        assertThat(name.path("value").asText()).isEqualTo("ZGATE Control Center");
        assertThat(name.path("category").asText()).isEqualTo("APP");
        assertThat(name.path("type").asText()).isEqualTo("string");
        assertThat(name.path("lastUpdated").isTextual()).isTrue();
        assertThat(name.has("configKey")).as("JSON name is 'key'").isFalse();
        assertThat(name.has("updatedAt")).as("JSON name is 'lastUpdated'").isFalse();

        MvcResult list = get(CONFIG, token);
        assertThat(status(list)).isEqualTo(200);
        Set<String> keys = new HashSet<>();
        for (JsonNode c : data(list)) keys.add(c.path("key").asText());
        assertThat(keys).contains("controlcenter.app.name", "controlcenter.app.support_email",
            "controlcenter.billing.auto_invoice", "controlcenter.billing.tax_rate", "controlcenter.billing.invoice_due_days",
            "controlcenter.billing.currency", "controlcenter.security.max_login_attempts",
            "controlcenter.security.session_timeout_hours", "controlcenter.alerts.global_email",
            "controlcenter.deployments.auto_rollback", "controlcenter.shared_services.default_call_limit");
        for (String k : keys) assertThat(k).as("V13 re-keyed nexus.* to controlcenter.*").doesNotStartWith("nexus.");

        MvcResult billing = get(CONFIG + "/category/BILLING", token);
        assertThat(status(billing)).isEqualTo(200);
        Set<String> billingKeys = new HashSet<>();
        for (JsonNode c : data(billing)) {
            assertThat(c.path("category").asText()).isEqualTo("BILLING");
            billingKeys.add(c.path("key").asText());
        }
        assertThat(billingKeys).contains("controlcenter.billing.tax_rate", "controlcenter.billing.currency");
        assertThat(byKey(data(billing), "controlcenter.billing.currency").path("value").asText()).isEqualTo("USD");
        assertThat(data(get(CONFIG + "/category/NO_SUCH_CATEGORY", token)).size()).isZero();

        MvcResult missing = get(CONFIG + "/it.no.such.key", token);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).isEqualTo("Config key not found: it.no.such.key");

        String viewer = tokenFor(token, "VIEWER");
        for (String p : new String[] {CONFIG, CONFIG + "/controlcenter.app.name", CONFIG + "/category/APP"}) {
            MvcResult denied = get(p, viewer);
            assertThat(status(denied)).as(p).isEqualTo(403);
            assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
        }
        String support = tokenFor(token, "SUPPORT");
        assertThat(status(get(CONFIG, support))).isEqualTo(403);
        assertThat(status(get(CONFIG, null))).isEqualTo(401);
    }

    // ── config: writes ──────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT upserts a key with updatedBy derived from the principal — a body-supplied updatedBy is ignored — and the list sees it")
    void putValueServerDerivedActor() throws Exception {
        String root = adminToken();
        String k = key("put");

        MvcResult r = put(CONFIG + "/" + k, root, Map.of("value", "v1", "updatedBy", "forged@example.test"));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("CONFIG_SAVED");
        assertThat(body(r).path("message").asText()).isEqualTo("Setting saved");
        JsonNode c = data(r);
        assertThat(c.path("key").asText()).isEqualTo(k);
        assertThat(c.path("value").asText()).isEqualTo("v1");
        assertThat(c.path("updatedBy").asText()).as("actor comes from the JWT, not the body").isEqualTo(ADMIN);
        assertThat(c.path("category").asText()).isEqualTo("GENERAL");
        assertThat(c.path("type").asText()).isEqualTo("string");
        assertThat(c.path("required").asBoolean()).isFalse();
        assertThat(c.path("id").isTextual()).isTrue();

        assertThat(byKey(data(get(CONFIG, root)), k)).as("upsert evicts the config cache").isNotNull();
        assertThat(getKey(root, k).path("value").asText()).isEqualTo("v1");

        // A second PUT by a different admin updates in place and re-attributes.
        String adminEmail = unique("it-cfg-admin");
        createOperator(root, adminEmail, "ADMIN", GOOD_PW);
        String admin = login(adminEmail, GOOD_PW);
        JsonNode again = data(put(CONFIG + "/" + k, admin, Map.of("value", "v2", "updatedBy", ADMIN)));
        assertThat(again.path("id").asText()).isEqualTo(c.path("id").asText());
        assertThat(again.path("value").asText()).isEqualTo("v2");
        assertThat(again.path("updatedBy").asText()).isEqualTo(adminEmail);
        assertThat(data(get(CONFIG + "/category/GENERAL", root)).size()).isGreaterThanOrEqualTo(1);

        // Updating a seeded key keeps its category and changes only the value.
        String before = getKey(root, "controlcenter.billing.invoice_due_days").path("value").asText();
        try {
            JsonNode seeded = putValue(root, "controlcenter.billing.invoice_due_days", "45");
            assertThat(seeded.path("category").asText()).isEqualTo("BILLING");
            assertThat(seeded.path("value").asText()).isEqualTo("45");
        } finally {
            putValue(root, "controlcenter.billing.invoice_due_days", before);
        }

        String viewer = tokenFor(root, "VIEWER");
        MvcResult denied = put(CONFIG + "/" + k, viewer, Map.of("value", "x"));
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
        assertThat(getKey(root, k).path("value").asText()).isEqualTo("v2");
    }

    @Test
    @DisplayName("batch saves every entry with the caller as actor and refuses an entry without a key")
    void batch() throws Exception {
        String token = adminToken();
        String k1 = key("batch1"), k2 = key("batch2");
        MvcResult r = post(CONFIG + "/batch", token, List.of(
            Map.of("key", k1, "value", "a"), Map.of("key", k2, "value", "b")));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("CONFIG_SAVED");
        assertThat(body(r).path("message").asText()).isEqualTo("Settings saved");
        assertThat(data(r).size()).isEqualTo(2);
        assertThat(data(r).get(0).path("key").asText()).isEqualTo(k1);
        assertThat(data(r).get(1).path("value").asText()).isEqualTo("b");
        for (JsonNode c : data(r)) assertThat(c.path("updatedBy").asText()).isEqualTo(ADMIN);
        assertThat(getKey(token, k1).path("value").asText()).isEqualTo("a");
        assertThat(getKey(token, k2).path("value").asText()).isEqualTo("b");

        String k3 = key("batch3");
        MvcResult bad = post(CONFIG + "/batch", token, List.of(Map.of("value", "orphan"), Map.of("key", k3, "value", "c")));
        assertThat(status(bad)).isEqualTo(400);
        assertThat(body(bad).path("message").asText()).isEqualTo("Batch entry missing required field: key");
        assertThat(status(get(CONFIG + "/" + k3, token))).as("nothing after the bad entry was written").isEqualTo(400);

        assertThat(status(post(CONFIG + "/batch", token, List.of()))).isEqualTo(200);
        assertThat(status(post(CONFIG + "/batch", tokenFor(token, "VIEWER"), List.of(Map.of("key", k1, "value", "z"))))).isEqualTo(403);
        assertThat(getKey(token, k1).path("value").asText()).isEqualTo("a");
    }

    @Test
    @DisplayName("delete is SUPER_ADMIN only: an ADMIN is refused (audited), the super-admin removes the key, a second delete is 400")
    void deleteSuperAdminOnly() throws Exception {
        String root = adminToken();
        String k = key("delete");
        putValue(root, k, "bye");
        String adminEmail = unique("it-cfg-del");
        createOperator(root, adminEmail, "ADMIN", GOOD_PW);
        String admin = login(adminEmail, GOOD_PW);

        MvcResult denied = delete(CONFIG + "/" + k, admin);
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
        AuditLog row = awaitAudit(a -> "ACCESS_DENIED".equals(a.getAction())
            && adminEmail.equalsIgnoreCase(a.getActorEmail()) && a.getEntityId() != null && a.getEntityId().contains(k));
        assertThat(row.getDetails()).contains("ROLE_ADMIN");
        assertThat(getKey(root, k).path("value").asText()).as("still there").isEqualTo("bye");

        MvcResult ok = delete(CONFIG + "/" + k, root);
        assertThat(status(ok)).as(text(ok)).isEqualTo(200);
        assertThat(code(ok)).isEqualTo("CONFIG_DELETED");
        assertThat(data(ok).path("key").asText()).isEqualTo(k);
        assertThat(status(get(CONFIG + "/" + k, root))).isEqualTo(400);
        assertThat(byKey(data(get(CONFIG, root)), k)).as("delete evicts the cache").isNull();

        MvcResult again = delete(CONFIG + "/" + k, root);
        assertThat(status(again)).isEqualTo(400);
        assertThat(body(again).path("message").asText()).isEqualTo("Config key not found: " + k);
    }

    // ── snapshots ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("any operator can take and list snapshots; restore and delete are SUPER_ADMIN only; restore puts values back")
    void snapshots() throws Exception {
        String root = adminToken();
        String k = key("snap");
        putValue(root, k, "v1");

        String viewerEmail = unique("it-snap-viewer");
        createOperator(root, viewerEmail, "VIEWER", GOOD_PW);
        String viewer = login(viewerEmail, GOOD_PW);
        MvcResult taken = post(CONFIG + "/snapshots", viewer, Map.of("organizationId", "org-label", "note", "before change"));
        assertThat(status(taken)).as(text(taken)).isEqualTo(200);
        assertThat(code(taken)).isEqualTo("SNAPSHOT_CREATED");
        JsonNode snap = data(taken);
        String snapId = snap.path("id").asText();
        assertThat(snap.path("takenBy").asText()).isEqualTo(viewerEmail);
        assertThat(snap.path("note").asText()).isEqualTo("before change");
        assertThat(snap.path("organizationId").asText()).isEqualTo("org-label");
        assertThat(snap.path("entryCount").asInt()).isGreaterThanOrEqualTo(12);
        assertThat(snap.path("takenAt").isTextual()).isTrue();
        assertThat(snap.path("snapshotData").asText()).contains("\"key\":\"" + k + "\",\"value\":\"v1\"");

        JsonNode defaulted = data(post(CONFIG + "/snapshots", root, Map.of()));
        assertThat(defaulted.path("note").asText()).isEqualTo("Manual snapshot");
        assertThat(defaulted.path("organizationId").isNull()).isTrue();

        MvcResult list = get(CONFIG + "/snapshots", viewer);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).get(0).path("id").asText()).as("newest first").isEqualTo(defaulted.path("id").asText());
        boolean present = false;
        for (JsonNode s : data(list)) if (snapId.equals(s.path("id").asText())) present = true;
        assertThat(present).isTrue();

        putValue(root, k, "v2");
        assertThat(getKey(root, k).path("value").asText()).isEqualTo("v2");

        String admin = tokenFor(root, "ADMIN");
        MvcResult adminRestore = post(CONFIG + "/snapshots/" + snapId + "/restore", admin, null);
        assertThat(status(adminRestore)).isEqualTo(403);
        assertThat(code(adminRestore)).isEqualTo("ACCESS_DENIED");
        assertThat(status(post(CONFIG + "/snapshots/" + snapId + "/restore", viewer, null))).isEqualTo(403);
        assertThat(getKey(root, k).path("value").asText()).isEqualTo("v2");

        MvcResult restored = post(CONFIG + "/snapshots/" + snapId + "/restore", root, null);
        assertThat(status(restored)).as(text(restored)).isEqualTo(200);
        assertThat(code(restored)).isEqualTo("SNAPSHOT_RESTORED");
        assertThat(data(restored).path("id").asText()).isEqualTo(snapId);
        JsonNode after = getKey(root, k);
        assertThat(after.path("value").asText()).isEqualTo("v1");
        assertThat(after.path("updatedBy").asText()).as("restore is attributed to the restorer").isEqualTo(ADMIN);

        MvcResult unknownRestore = post(CONFIG + "/snapshots/" + UUID.randomUUID() + "/restore", root, null);
        assertThat(status(unknownRestore)).isEqualTo(400);
        assertThat(body(unknownRestore).path("message").asText()).contains("Snapshot not found");

        MvcResult adminDelete = delete(CONFIG + "/snapshots/" + snapId, admin);
        assertThat(status(adminDelete)).isEqualTo(403);
        assertThat(code(adminDelete)).isEqualTo("ACCESS_DENIED");
        MvcResult deleted = delete(CONFIG + "/snapshots/" + snapId, root);
        assertThat(status(deleted)).isEqualTo(200);
        assertThat(code(deleted)).isEqualTo("SNAPSHOT_DELETED");
        assertThat(data(deleted).path("id").asText()).isEqualTo(snapId);
        for (JsonNode s : data(get(CONFIG + "/snapshots", root))) assertThat(s.path("id").asText()).isNotEqualTo(snapId);
        MvcResult gone = delete(CONFIG + "/snapshots/" + snapId, root);
        assertThat(status(gone)).isEqualTo(400);
        assertThat(body(gone).path("message").asText()).contains("Snapshot not found");
        assertThat(status(delete(CONFIG + "/snapshots/" + defaulted.path("id").asText(), root))).isEqualTo(200);
    }

    // ── registry probe ──────────────────────────────────────────────────────

    @Test
    @DisplayName("test-registry with a blank or absent url answers ok:false with the reason and never throws")
    void testRegistryBlankUrl() throws Exception {
        String token = adminToken();
        MvcResult blank = post(CONFIG + "/test-registry", token, Map.of("url", "   "));
        assertThat(status(blank)).isEqualTo(200);
        assertThat(data(blank).path("ok").asBoolean()).isFalse();
        assertThat(data(blank).path("error").asText()).isEqualTo("Registry URL is required");
        MvcResult absent = post(CONFIG + "/test-registry", token, Map.of("username", "u", "password", "p"));
        assertThat(status(absent)).isEqualTo(200);
        assertThat(data(absent).path("ok").asBoolean()).isFalse();
        assertThat(data(absent).path("error").asText()).isEqualTo("Registry URL is required");
        assertThat(status(post(CONFIG + "/test-registry", tokenFor(token, "VIEWER"), Map.of("url", "")))).isEqualTo(403);
    }

    // ── database ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("database health is UP with the Postgres version, connection limits, no pending migrations and the public schema")
    void health() throws Exception {
        String token = adminToken();
        MvcResult r = get(DB + "/health", token);
        assertThat(status(r)).isEqualTo(200);
        JsonNode h = data(r);
        assertThat(h.path("status").asText()).isEqualTo("UP");
        assertThat(h.path("version").asText()).startsWith("PostgreSQL 16");
        assertThat(h.path("sizeBytes").asLong()).isGreaterThan(0);
        assertThat(h.path("activeConnections").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(h.path("maxConnections").asInt()).isGreaterThan(0);
        assertThat(h.path("pendingMigrations").asInt()).isZero();
        JsonNode pub = null;
        for (JsonNode s : h.path("schemas")) if ("public".equals(s.path("name").asText())) pub = s;
        assertThat(pub).isNotNull();
        assertThat(pub.path("tableCount").asInt()).as("every Flyway'd table").isGreaterThanOrEqualTo(30);
        assertThat(pub.path("sizeBytes").asLong()).isGreaterThan(0);
        assertThat(status(get(DB + "/health", tokenFor(token, "VIEWER")))).isEqualTo(200);
        assertThat(status(get(DB + "/health", null))).isEqualTo(401);
    }

    @Test
    @DisplayName("the migration history lists V1 through V34, all SUCCESS SQL migrations with an install time")
    void migrations() throws Exception {
        String token = adminToken();
        MvcResult r = get(DB + "/migrations", token);
        assertThat(status(r)).isEqualTo(200);
        Map<String, JsonNode> byVersion = new HashMap<>();
        for (JsonNode m : data(r)) byVersion.put(m.path("version").asText(), m);
        for (int v = 1; v <= 34; v++) {
            JsonNode m = byVersion.get(String.valueOf(v));
            assertThat(m).as("V" + v).isNotNull();
            assertThat(m.path("state").asText()).as("V" + v).isEqualTo("SUCCESS");
            assertThat(m.path("type").asText()).as("V" + v).isEqualTo("SQL");
            assertThat(m.path("installedOn").isTextual()).as("V" + v).isTrue();
            assertThat(m.path("description").asText()).isNotBlank();
            assertThat(m.path("schema").asText()).isEqualTo("public");
        }
        assertThat(byVersion.get("1").path("description").asText()).isEqualTo("nexus schema");
        assertThat(byVersion.get("34").path("description").asText()).isEqualTo("step up tickets and email uniqueness");
        for (JsonNode m : data(r)) assertThat(m.path("state").asText()).isNotIn("PENDING", "FAILED");
        assertThat(status(get(DB + "/migrations", tokenFor(token, "VIEWER")))).isEqualTo(200);
    }

    @Test
    @DisplayName("test-connection succeeds and schema validation is clean; validation needs ADMIN")
    void connectionAndSchema() throws Exception {
        String token = adminToken();
        MvcResult c = post(DB + "/test-connection", token, null);
        assertThat(status(c)).isEqualTo(200);
        assertThat(data(c).path("ok").asBoolean()).isTrue();
        assertThat(data(c).path("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(data(c).has("error")).isFalse();

        MvcResult v = post(DB + "/schema/validate", token, null);
        assertThat(status(v)).as(text(v)).isEqualTo(200);
        assertThat(data(v).path("valid").asBoolean()).isTrue();
        assertThat(data(v).path("errorCount").asInt()).isZero();
        assertThat(data(v).path("warnings").isArray()).isTrue();

        String viewer = tokenFor(token, "VIEWER");
        assertThat(status(post(DB + "/test-connection", viewer, null))).isEqualTo(200);
        MvcResult denied = post(DB + "/schema/validate", viewer, null);
        assertThat(status(denied)).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("ACCESS_DENIED");
        assertThat(status(post(DB + "/schema/validate", tokenFor(token, "ADMIN"), null))).isEqualTo(200);
    }

    @Test
    @DisplayName("with no backup directory configured, listing is a clean 200 'unavailable' and creating is 503 BACKUP_NOT_CONFIGURED")
    void backupsUnconfigured() throws Exception {
        String root = adminToken();
        MvcResult list = get(DB + "/backups", root);
        assertThat(status(list)).isEqualTo(200);
        JsonNode b = data(list);
        assertThat(b.path("available").asBoolean()).isFalse();
        assertThat(b.path("unavailableReason").asText()).contains("controlcenter.database.backup.directory is not set");
        assertThat(b.path("restoreEnabled").asBoolean()).isFalse();
        assertThat(b.path("storageWarning").asText()).isEmpty();
        assertThat(b.path("backups").isArray()).isTrue();
        assertThat(b.path("backups").size()).isZero();
        assertThat(status(get(DB + "/backups", tokenFor(root, "ADMIN")))).isEqualTo(200);
        MvcResult viewerList = get(DB + "/backups", tokenFor(root, "VIEWER"));
        assertThat(status(viewerList)).isEqualTo(403);
        assertThat(code(viewerList)).isEqualTo("ACCESS_DENIED");

        MvcResult create = post(DB + "/backups", root, Map.of("name", "it"));
        assertThat(status(create)).isEqualTo(503);
        assertThat(code(create)).isEqualTo("BACKUP_NOT_CONFIGURED");
        assertThat(body(create).path("message").asText()).contains("is not set");
        assertThat(status(post(DB + "/backups", root, null))).isEqualTo(503);
        MvcResult adminCreate = post(DB + "/backups", tokenFor(root, "ADMIN"), null);
        assertThat(status(adminCreate)).isEqualTo(403);
        assertThat(code(adminCreate)).isEqualTo("ACCESS_DENIED");

        MvcResult del = delete(DB + "/backups/cc-nothing.dump", root);
        assertThat(status(del)).isEqualTo(503);
        assertThat(code(del)).isEqualTo("BACKUP_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("restore needs a step-up ticket bound to the action, then the backup id as confirmation; the role check comes first")
    void restoreGates() throws Exception {
        String root = adminToken();
        String id = "cc-20260101-000000-it.dump";
        String path = DB + "/backups/" + id + "/restore";

        MvcResult noTicket = post(path, root, Map.of("confirmation", id));
        assertThat(status(noTicket)).isEqualTo(403);
        assertThat(code(noTicket)).isEqualTo("STEP_UP_REQUIRED");

        // Wrong role: method security's 403 rather than a pointless step-up prompt.
        MvcResult admin = post(path, tokenFor(root, "ADMIN"), Map.of("confirmation", id));
        assertThat(status(admin)).isEqualTo(403);
        assertThat(code(admin)).isEqualTo("ACCESS_DENIED");

        // A ticket for a different backup's restore is not accepted here.
        Map<String, String> other = stepUpHeaders(root, ADMIN_PW, "POST " + DB + "/backups/other.dump/restore");
        MvcResult wrongAction = postWith(path, root, Map.of("confirmation", id), other);
        assertThat(status(wrongAction)).isEqualTo(403);
        assertThat(code(wrongAction)).isEqualTo("STEP_UP_REQUIRED");

        // Correct ticket, wrong confirmation: the confirmation check runs before any backup plumbing.
        Map<String, String> h = stepUpHeaders(root, ADMIN_PW, "POST " + path);
        MvcResult wrongConfirmation = postWith(path, root, Map.of("confirmation", "something-else"), h);
        assertThat(status(wrongConfirmation)).isEqualTo(400);
        assertThat(code(wrongConfirmation)).isEqualTo("RESTORE_CONFIRMATION_REQUIRED");
        assertThat(body(wrongConfirmation).path("message").asText()).contains(id);

        // The ticket was consumed by that attempt (single use), so the same headers no longer pass.
        MvcResult replay = postWith(path, root, Map.of("confirmation", id), h);
        assertThat(status(replay)).isEqualTo(403);
        assertThat(code(replay)).isEqualTo("STEP_UP_REQUIRED");

        // Fresh ticket + right confirmation: everything is satisfied except the backup directory,
        // so the service refuses with 503 and pg_restore is never reached.
        Map<String, String> h2 = stepUpHeaders(root, ADMIN_PW, "POST " + path);
        MvcResult unconfigured = postWith(path, root, Map.of("confirmation", id), h2);
        assertThat(status(unconfigured)).isEqualTo(503);
        assertThat(code(unconfigured)).isEqualTo("BACKUP_NOT_CONFIGURED");
        assertThat(status(get(DB + "/health", root))).as("the database is untouched").isEqualTo(200);
        // Other suites also step up as the admin; wait for THIS action's row, not the first one.
        awaitAudit(a -> "STEP_UP_SUCCESS".equals(a.getAction()) && ADMIN.equalsIgnoreCase(a.getActorEmail())
            && a.getDetails() != null && a.getDetails().contains(id + "/restore"));
    }

    @Test
    @DisplayName("download is SUPER_ADMIN only; with no backup directory even a malformed id is refused by the 503 configuration gate")
    void downloadGates() throws Exception {
        String root = adminToken();
        MvcResult viewer = get(DB + "/backups/x.dump/download", tokenFor(root, "VIEWER"));
        assertThat(status(viewer)).isEqualTo(403);
        assertThat(code(viewer)).isEqualTo("ACCESS_DENIED");
        MvcResult admin = get(DB + "/backups/x.dump/download", tokenFor(root, "ADMIN"));
        assertThat(status(admin)).isEqualTo(403);
        assertThat(code(admin)).isEqualTo("ACCESS_DENIED");

        // ControlCenterBackupService.resolve() calls requireAvailable() before validating the id, so in
        // the unconfigured state the invalid-id 400 is unreachable: the configuration gate answers first.
        MvcResult invalid = get(DB + "/backups/" + "a".repeat(121) + "/download", root); // 121 chars: outside SAFE_ID
        assertThat(status(invalid)).isEqualTo(503);
        assertThat(code(invalid)).isEqualTo("BACKUP_NOT_CONFIGURED");
        MvcResult wellFormed = get(DB + "/backups/cc-20260101-000000-it.dump/download", root);
        assertThat(status(wellFormed)).isEqualTo(503);
        assertThat(code(wellFormed)).isEqualTo("BACKUP_NOT_CONFIGURED");
    }
}
