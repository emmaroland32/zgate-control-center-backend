package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.AuditLog;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Organizations end to end: create/read/update, status and entitlement patches, the service-key
 * lifecycle (proved against the always-enforced managed-backup M2M route), role gating, validation
 * and not-found handling — through the real security chain against the shared Postgres.
 */
class OrganizationsIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/organizations";
    private static final String APEX = "b0000001-0000-0000-0000-000000000001";

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> orgBody(String slug) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "IT Org " + slug);
        m.put("slug", slug);
        m.put("tier", "STANDARD");
        m.put("deploymentEnv", "PRODUCTION");
        m.put("contactEmail", slug + "@example.test");
        m.put("contactName", "Ops " + slug);
        m.put("country", "NG");
        m.put("region", "af-west");
        return m;
    }

    private JsonNode createOrg(String token, String slug) throws Exception {
        MvcResult r = post(BASE, token, orgBody(slug));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r);
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Map<String, String> m2m(String orgId, String key) {
        Map<String, String> h = new HashMap<>();
        h.put("X-Control-Center-Org-Id", orgId);
        if (key != null) h.put("X-Control-Center-Service-Key", key);
        return h;
    }

    // ── create / read ───────────────────────────────────────────────────────

    @Test
    @DisplayName("create returns 201 with the plaintext service key exactly once; the stored hash matches; reads never echo it")
    void createRevealsKeyOnce() throws Exception {
        String token = adminToken();
        String slug = uniqueSlug("it-org");
        MvcResult r = post(BASE, token, orgBody(slug));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        assertThat(code(r)).isEqualTo("ORG_CREATED");
        assertThat(body(r).path("message").asText()).isEqualTo("Organization created successfully");
        assertThat(r.getResponse().getHeader("X-Api-Envelope")).isEqualTo("1");

        JsonNode org = data(r);
        String id = org.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(org.path("slug").asText()).isEqualTo(slug);
        assertThat(org.path("name").asText()).isEqualTo("IT Org " + slug);
        assertThat(org.path("tier").asText()).isEqualTo("STANDARD");
        assertThat(org.path("deploymentEnv").asText()).isEqualTo("PRODUCTION");
        assertThat(org.path("deploymentStatus").asText()).isEqualTo("PROVISIONING");
        assertThat(org.path("createdAt").isTextual()).isTrue();
        assertThat(org.path("deployedVersion").isNull()).isTrue();
        assertThat(org.path("subscriptionValidUntil").isNull()).as("unmanaged until entitled").isTrue();

        String key = org.path("serviceApiKey").textValue();
        assertThat(key).startsWith("zgn_").hasSizeGreaterThan(40);
        assertThat(org.path("serviceApiKeyHash").asText()).isEqualTo(sha256Hex(key));

        // The plaintext is transient: every later read carries the hash only.
        JsonNode read = data(get(BASE + "/" + id, token));
        assertThat(read.path("serviceApiKey").textValue()).isNull();
        assertThat(read.path("serviceApiKeyHash").asText()).isEqualTo(sha256Hex(key));
        assertThat(read.path("slug").asText()).isEqualTo(slug);

        // create evicts the 5-minute list cache, so membership is visible immediately.
        MvcResult list = get(BASE, token);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).isArray()).isTrue();
        boolean present = false;
        for (JsonNode o : data(list)) if (id.equals(o.path("id").asText())) present = true;
        assertThat(present).as("new org in the list right after create").isTrue();

        AuditLog row = awaitAudit(a -> "ORG_CREATED".equals(a.getAction()) && id.equals(a.getEntityId()));
        assertThat(row.getActorEmail()).isEqualToIgnoringCase(ADMIN);
        assertThat(row.getStatus()).isEqualTo(AuditLog.Status.SUCCESS);
    }

    @Test
    @DisplayName("a second organization with the same slug is refused with 409 ORG_SLUG_TAKEN")
    void duplicateSlug() throws Exception {
        String token = adminToken();
        String slug = uniqueSlug("it-dup");
        createOrg(token, slug);
        Map<String, Object> again = orgBody(slug);
        again.put("name", "Different name, same slug");
        MvcResult r = post(BASE, token, again);
        assertThat(status(r)).isEqualTo(409);
        assertThat(code(r)).isEqualTo("ORG_SLUG_TAKEN");
        assertThat(body(r).path("message").asText()).contains(slug);
    }

    @Test
    @DisplayName("seeded organizations are readable by id and the dashboard counters reflect the seed")
    void seedReadsAndDashboard() throws Exception {
        String token = adminToken();
        JsonNode apex = data(get(BASE + "/" + APEX, token));
        assertThat(apex.path("slug").asText()).isEqualTo("apex-capital");
        assertThat(apex.path("name").asText()).isEqualTo("Apex Capital Management");
        assertThat(apex.path("tier").asText()).isEqualTo("ENTERPRISE");
        assertThat(apex.path("partnerId").asText()).isEqualTo("a0000001-0000-0000-0000-000000000001");

        MvcResult d = get(BASE + "/dashboard", token);
        assertThat(status(d)).isEqualTo(200);
        JsonNode dash = data(d);
        assertThat(dash.path("total").asLong()).as("6 seeded orgs plus test fixtures").isGreaterThanOrEqualTo(6);
        assertThat(dash.path("production").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(dash.path("healthy").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(dash.path("degraded").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(dash.path("offline").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(dash.path("expiringSoon").asLong()).as("Apex (3) + Coronation (2) licences within 30 days")
            .isGreaterThanOrEqualTo(5);
        assertThat(dash.path("healthy").asLong() + dash.path("degraded").asLong() + dash.path("offline").asLong())
            .isLessThanOrEqualTo(dash.path("total").asLong());
    }

    // ── update ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update rewrites the profile fields but never the slug, and still validates the body")
    void update() throws Exception {
        String token = adminToken();
        String slug = uniqueSlug("it-upd");
        String id = createOrg(token, slug).path("id").asText();

        Map<String, Object> upd = orgBody(uniqueSlug("it-upd-new-slug"));
        upd.put("name", "Renamed Org");
        upd.put("tier", "ENTERPRISE");
        upd.put("deploymentEnv", "STAGING");
        upd.put("backendUrl", "https://renamed.example.test");
        upd.put("partnerId", "a0000002-0000-0000-0000-000000000002");
        MvcResult r = put(BASE + "/" + id, token, upd);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ORG_UPDATED");
        JsonNode o = data(r);
        assertThat(o.path("name").asText()).isEqualTo("Renamed Org");
        assertThat(o.path("tier").asText()).isEqualTo("ENTERPRISE");
        assertThat(o.path("deploymentEnv").asText()).isEqualTo("STAGING");
        assertThat(o.path("backendUrl").asText()).isEqualTo("https://renamed.example.test");
        assertThat(o.path("partnerId").asText()).isEqualTo("a0000002-0000-0000-0000-000000000002");
        // Documented quirk: PUT requires a slug in the body but OrganizationService.update never applies it.
        assertThat(o.path("slug").asText()).as("slug is immutable through PUT").isEqualTo(slug);
        assertThat(o.path("deploymentStatus").asText()).as("status untouched by a profile update").isEqualTo("PROVISIONING");
        assertThat(o.path("serviceApiKey").textValue()).isNull();

        JsonNode reread = data(get(BASE + "/" + id, token));
        assertThat(reread.path("name").asText()).isEqualTo("Renamed Org");
        assertThat(reread.path("slug").asText()).isEqualTo(slug);

        Map<String, Object> noSlug = new HashMap<>(upd);
        noSlug.remove("slug");
        MvcResult bad = put(BASE + "/" + id, token, noSlug);
        assertThat(status(bad)).isEqualTo(400);
        assertThat(code(bad)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(bad).path("fieldErrors").has("slug")).isTrue();

        MvcResult missing = put(BASE + "/" + UUID.randomUUID(), token, upd);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("ORG_NOT_FOUND");
    }

    // ── status ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status is a required enum query parameter: valid values apply, bad ones name the allowed set")
    void statusPatch() throws Exception {
        String token = adminToken();
        String id = createOrg(token, uniqueSlug("it-status")).path("id").asText();

        MvcResult r = patch(BASE + "/" + id + "/status?status=SUSPENDED", token, null);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ORG_STATUS_UPDATED");
        assertThat(data(r).path("deploymentStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(data(get(BASE + "/" + id, token)).path("deploymentStatus").asText()).isEqualTo("SUSPENDED");

        assertThat(data(patch(BASE + "/" + id + "/status?status=HEALTHY", token, null))
            .path("deploymentStatus").asText()).isEqualTo("HEALTHY");

        MvcResult bad = patch(BASE + "/" + id + "/status?status=BROKEN", token, null);
        assertThat(status(bad)).isEqualTo(400);
        assertThat(code(bad)).isEqualTo("INVALID_PARAMETER");
        assertThat(body(bad).path("message").asText())
            .contains("BROKEN").contains("HEALTHY").contains("DEGRADED").contains("OFFLINE")
            .contains("PROVISIONING").contains("SUSPENDED");

        MvcResult none = patch(BASE + "/" + id + "/status", token, null);
        assertThat(status(none)).isEqualTo(400);
        assertThat(code(none)).isEqualTo("MISSING_PARAMETER");

        MvcResult unknown = patch(BASE + "/" + UUID.randomUUID() + "/status?status=HEALTHY", token, null);
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("ORG_NOT_FOUND");
    }

    // ── entitlements ────────────────────────────────────────────────────────

    @Test
    @DisplayName("entitlements are a full replace: every field lands, an empty body clears them all, and it is audited")
    void entitlementsFullReplace() throws Exception {
        String token = adminToken();
        String id = createOrg(token, uniqueSlug("it-ent")).path("id").asText();

        Map<String, Object> ent = new HashMap<>();
        ent.put("subscriptionValidUntil", "2031-01-31T00:00:00");
        ent.put("entitledVersion", "2.5.0");
        ent.put("licenseTtlDays", 14);
        ent.put("maxInstances", 3);
        ent.put("deploymentTier", "HIGH_AVAILABILITY");
        ent.put("subscriptionMonthlyFee", "1234.50");
        ent.put("maintenanceWindowStart", "22:00:00");
        ent.put("maintenanceWindowEnd", "02:00:00");
        ent.put("maintenanceTimezone", "Africa/Lagos");
        MvcResult r = patch(BASE + "/" + id + "/entitlements", token, ent);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ORG_ENTITLEMENT_UPDATED");
        JsonNode o = data(r);
        assertThat(o.path("subscriptionValidUntil").asText()).startsWith("2031-01-31T00:00");
        assertThat(o.path("entitledVersion").asText()).isEqualTo("2.5.0");
        assertThat(o.path("licenseTtlDays").asInt()).isEqualTo(14);
        assertThat(o.path("maxInstances").asInt()).isEqualTo(3);
        assertThat(o.path("deploymentTier").asText()).isEqualTo("HIGH_AVAILABILITY");
        assertThat(o.path("subscriptionMonthlyFee").decimalValue()).isEqualByComparingTo("1234.50");
        assertThat(o.path("maintenanceWindowStart").asText()).startsWith("22:00");
        assertThat(o.path("maintenanceWindowEnd").asText()).startsWith("02:00");
        assertThat(o.path("maintenanceTimezone").asText()).isEqualTo("Africa/Lagos");

        JsonNode reread = data(get(BASE + "/" + id, token));
        assertThat(reread.path("deploymentTier").asText()).isEqualTo("HIGH_AVAILABILITY");
        assertThat(reread.path("subscriptionMonthlyFee").decimalValue()).isEqualByComparingTo("1234.50");

        AuditLog row = awaitAudit(a -> "ORG_ENTITLEMENT_UPDATED".equals(a.getAction()) && id.equals(a.getEntityId()));
        assertThat(row.getDetails()).contains("tier=HIGH_AVAILABILITY").contains("entitledVersion=2.5.0")
            .contains("maxInstances=3");

        // Partial body = full replace: omitted fields are cleared, not preserved.
        JsonNode partial = data(patch(BASE + "/" + id + "/entitlements", token, Map.of("maxInstances", 1)));
        assertThat(partial.path("maxInstances").asInt()).isEqualTo(1);
        assertThat(partial.path("entitledVersion").isNull()).isTrue();
        assertThat(partial.path("deploymentTier").isNull()).isTrue();

        JsonNode cleared = data(patch(BASE + "/" + id + "/entitlements", token, Map.of()));
        for (String f : new String[] {"subscriptionValidUntil", "entitledVersion", "licenseTtlDays", "maxInstances",
                                      "deploymentTier", "subscriptionMonthlyFee", "maintenanceWindowStart",
                                      "maintenanceWindowEnd", "maintenanceTimezone"}) {
            assertThat(cleared.path(f).isNull()).as("%s cleared by empty body", f).isTrue();
        }

        MvcResult badTier = patch(BASE + "/" + id + "/entitlements", token, Map.of("deploymentTier", "MEGA"));
        assertThat(status(badTier)).isEqualTo(400);
        assertThat(code(badTier)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(badTier).path("message").asText()).contains("SINGLE_NODE").contains("MULTI_REGION");

        MvcResult unknown = patch(BASE + "/" + UUID.randomUUID() + "/entitlements", token, Map.of());
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("ORG_NOT_FOUND");
    }

    @Test
    @DisplayName("an unknown maintenance timezone is a 400 validation failure, not a 500")
    void entitlementsBadTimezoneIs400() throws Exception {
        String token = adminToken();
        String id = createOrg(token, uniqueSlug("it-tz")).path("id").asText();
        MvcResult r = patch(BASE + "/" + id + "/entitlements", token, Map.of("maintenanceTimezone", "Mars/Olympus"));
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r).path("message").asText()).contains("Mars/Olympus");
        // Nothing should have been written.
        assertThat(data(get(BASE + "/" + id, token)).path("maintenanceTimezone").isNull()).isTrue();
    }

    // ── service key lifecycle ───────────────────────────────────────────────

    @Test
    @DisplayName("regenerate-key mints a new plaintext; the old key stops authenticating M2M calls and the new one starts")
    void regenerateKeyRotates() throws Exception {
        String token = adminToken();
        JsonNode created = createOrg(token, uniqueSlug("it-key"));
        String id = created.path("id").asText();
        String oldKey = created.path("serviceApiKey").textValue();

        // The managed-backup agent route is service-key-enforced regardless of the global flag —
        // the most direct proof that a key is (or is no longer) live.
        MvcResult okOld = getWith("/api/v1/backups", null, m2m(id, oldKey));
        assertThat(status(okOld)).as(text(okOld)).isEqualTo(200);
        assertThat(body(okOld).isArray()).as("raw M2M array, no envelope").isTrue();
        assertThat(okOld.getResponse().getHeader("X-Api-Envelope")).isNull();

        MvcResult noKey = getWith("/api/v1/backups", null, m2m(id, null));
        assertThat(status(noKey)).isEqualTo(401);
        assertThat(body(noKey).path("error").asText()).isEqualTo("invalid_service_key");

        MvcResult r = post(BASE + "/" + id + "/regenerate-key", token, null);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("ORG_KEY_REGENERATED");
        String newKey = data(r).path("serviceApiKey").textValue();
        assertThat(newKey).startsWith("zgn_").isNotEqualTo(oldKey);
        assertThat(data(r).path("serviceApiKeyHash").asText()).isEqualTo(sha256Hex(newKey));

        MvcResult stale = getWith("/api/v1/backups", null, m2m(id, oldKey));
        assertThat(status(stale)).as("old key revoked").isEqualTo(401);
        assertThat(body(stale).path("error").asText()).isEqualTo("invalid_service_key");
        assertThat(stale.getResponse().getContentType()).contains("application/json");

        MvcResult fresh = getWith("/api/v1/backups", null, m2m(id, newKey));
        assertThat(status(fresh)).as("new key live").isEqualTo(200);
        assertThat(body(fresh).isArray()).isTrue();

        // A valid key for org A does not authenticate as org B.
        MvcResult crossOrg = getWith("/api/v1/backups", null, m2m(APEX, newKey));
        assertThat(status(crossOrg)).isEqualTo(401);

        assertThat(data(get(BASE + "/" + id, token)).path("serviceApiKeyHash").asText()).isEqualTo(sha256Hex(newKey));
        AuditLog row = awaitAudit(a -> "ORG_SERVICE_KEY_REGENERATED".equals(a.getAction()) && id.equals(a.getEntityId()));
        assertThat(row.getDetails() == null || !row.getDetails().contains(newKey)).as("raw key never logged").isTrue();

        MvcResult unknown = post(BASE + "/" + UUID.randomUUID() + "/regenerate-key", token, null);
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("ORG_NOT_FOUND");
    }

    // ── role gating ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a VIEWER can read organizations but every mutation is 403 ACCESS_DENIED and audited")
    void viewerReadOnly() throws Exception {
        String root = adminToken();
        String id = createOrg(root, uniqueSlug("it-viewer")).path("id").asText();
        String email = unique("it-org-viewer");
        createOperator(root, email, "VIEWER", GOOD_PW);
        String viewer = login(email, GOOD_PW);

        assertThat(status(get(BASE, viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/" + id, viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/dashboard", viewer))).isEqualTo(200);

        MvcResult create = post(BASE, viewer, orgBody(uniqueSlug("it-viewer-denied")));
        assertThat(status(create)).isEqualTo(403);
        assertThat(code(create)).isEqualTo("ACCESS_DENIED");
        assertThat(status(put(BASE + "/" + id, viewer, orgBody("x")))).isEqualTo(403);
        assertThat(status(patch(BASE + "/" + id + "/status?status=HEALTHY", viewer, null))).isEqualTo(403);
        assertThat(status(patch(BASE + "/" + id + "/entitlements", viewer, Map.of()))).isEqualTo(403);
        MvcResult regen = post(BASE + "/" + id + "/regenerate-key", viewer, null);
        assertThat(status(regen)).isEqualTo(403);
        assertThat(code(regen)).isEqualTo("ACCESS_DENIED");

        AuditLog denied = awaitAudit(a -> "ACCESS_DENIED".equals(a.getAction())
            && email.equalsIgnoreCase(a.getActorEmail()) && a.getEntityId() != null
            && a.getEntityId().contains("/regenerate-key"));
        assertThat(denied.getStatus()).isEqualTo(AuditLog.Status.FAILURE);
        assertThat(denied.getDetails()).contains("ROLE_VIEWER");

        // An ADMIN (not only SUPER_ADMIN) may create.
        String admin = tokenFor(root, "ADMIN");
        assertThat(status(post(BASE, admin, orgBody(uniqueSlug("it-admin-ok"))))).isEqualTo(201);

        // A SUPPORT operator is read-only here too.
        String support = tokenFor(root, "SUPPORT");
        assertThat(status(post(BASE, support, orgBody(uniqueSlug("it-support-denied"))))).isEqualTo(403);
    }

    // ── validation / not found ──────────────────────────────────────────────

    @Test
    @DisplayName("bean validation reports every bad field at once; an unknown enum names the allowed values")
    void validation() throws Exception {
        String token = adminToken();

        MvcResult empty = post(BASE, token, Map.of());
        assertThat(status(empty)).isEqualTo(400);
        assertThat(code(empty)).isEqualTo("VALIDATION_ERROR");
        JsonNode fe = body(empty).path("fieldErrors");
        assertThat(fe.has("name")).isTrue();
        assertThat(fe.has("slug")).isTrue();
        assertThat(fe.has("tier")).isTrue();
        assertThat(fe.has("deploymentEnv")).isTrue();
        assertThat(body(empty).path("message").asText()).contains("name").contains("slug");

        Map<String, Object> badEmail = orgBody(uniqueSlug("it-bademail"));
        badEmail.put("contactEmail", "not-an-email");
        MvcResult be = post(BASE, token, badEmail);
        assertThat(status(be)).isEqualTo(400);
        assertThat(code(be)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(be).path("fieldErrors").has("contactEmail")).isTrue();
        assertThat(body(be).path("fieldErrors").size()).isEqualTo(1);

        Map<String, Object> badTier = orgBody(uniqueSlug("it-badtier"));
        badTier.put("tier", "PLATINUM");
        MvcResult bt = post(BASE, token, badTier);
        assertThat(status(bt)).isEqualTo(400);
        assertThat(code(bt)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(bt).path("message").asText())
            .contains("'PLATINUM'").contains("'tier'").contains("FREE, STARTER, STANDARD, ENTERPRISE");

        MvcResult malformed = post(BASE, token, "{not json");
        assertThat(status(malformed)).isEqualTo(400);
        assertThat(code(malformed)).isEqualTo("MALFORMED_REQUEST");

        // Nothing above created a row.
        MvcResult list = get(BASE, token);
        for (JsonNode o : data(list)) {
            assertThat(o.path("slug").asText()).doesNotStartWith("it-bademail").doesNotStartWith("it-badtier");
        }
    }

    @Test
    @DisplayName("an unknown id is 404 ORG_NOT_FOUND; a non-UUID id is 400 INVALID_PARAMETER; no token is 401")
    void notFoundAndBadIds() throws Exception {
        String token = adminToken();
        UUID missing = UUID.randomUUID();
        MvcResult r = get(BASE + "/" + missing, token);
        assertThat(status(r)).isEqualTo(404);
        assertThat(code(r)).isEqualTo("ORG_NOT_FOUND");
        assertThat(body(r).path("message").asText()).contains(missing.toString());
        assertThat(body(r).path("status").asInt()).isEqualTo(404);
        assertThat(body(r).has("data")).as("error envelope, not the success envelope").isFalse();

        MvcResult bad = get(BASE + "/not-a-uuid", token);
        assertThat(status(bad)).isEqualTo(400);
        assertThat(code(bad)).isEqualTo("INVALID_PARAMETER");

        MvcResult anon = get(BASE, null);
        assertThat(status(anon)).isEqualTo(401);
        assertThat(code(anon)).isEqualTo("UNAUTHORIZED");
        MvcResult anonCreate = post(BASE, null, orgBody(uniqueSlug("it-anon")));
        assertThat(status(anonCreate)).isEqualTo(401);
    }
}
