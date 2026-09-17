package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.ApiKey;
import com.zgate.controlcenter.domain.Webhook;
import com.zgate.controlcenter.repository.ApiKeyRepository;
import com.zgate.controlcenter.repository.WebhookRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbound webhooks, operator API keys and the third-party integration catalogue. The harness sets
 * {@code controlcenter.webhooks.allowInsecureTargets=true}, so webhook URLs are validated for shape
 * only (no DNS), which keeps the suite hermetic; the delivery test deliberately targets a closed
 * loopback port so it fails fast and deterministically.
 */
class IntegrationsIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/integrations";
    private static final String WEBHOOKS = BASE + "/webhooks";
    private static final String API_KEYS = BASE + "/api-keys";
    private static final List<String> SEEDED_INTEGRATIONS =
        List.of("slack", "teams", "jira", "pagerduty", "datadog", "email");

    @Autowired WebhookRepository webhookRepo;
    @Autowired ApiKeyRepository apiKeyRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> webhookBody(String name, String url, String secret) {
        return Map.of("name", name, "url", url, "secret", secret,
                      "events", "[\"DEPLOYMENT_SUCCESS\"]", "enabled", true,
                      "headers", "{\"X-Extra\":\"third-party-token\"}");
    }

    private JsonNode createWebhook(String token, String url, String secret) throws Exception {
        MvcResult r = post(WEBHOOKS, token, webhookBody(uniqueSlug("it-hook"), url, secret));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        assertThat(code(r)).isEqualTo("WEBHOOK_CREATED");
        return data(r);
    }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode n : array) if (id.equals(n.path("id").asText())) return true;
        return false;
    }

    private static JsonNode byCode(JsonNode array, String code) {
        for (JsonNode n : array) if (code.equals(n.path("code").asText())) return n;
        throw new AssertionError("no integration with code " + code);
    }

    // ── webhooks ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("creating a webhook stores a SHA-256 of the secret and never echoes the secret, its hash or the headers")
    void webhookCreateHashesSecret() throws Exception {
        String admin = adminToken();
        String secret = "s3cret-" + uniqueSlug("hook");
        String url = "https://hooks.example.test/" + uniqueSlug("path");
        JsonNode hook = createWebhook(admin, url, secret);

        String id = hook.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(hook.path("url").asText()).isEqualTo(url);
        assertThat(hook.path("enabled").asBoolean()).isTrue();
        assertThat(hook.path("fireCount").asLong()).isZero();
        assertThat(hook.path("successCount").asLong()).isZero();
        assertThat(hook.path("successRate").asDouble()).isEqualTo(100.0);
        assertThat(hook.path("createdBy").asText()).isEqualTo(ADMIN);
        // secretHash and headers are @JsonIgnore on the entity: neither the raw secret, its hash,
        // nor the third-party header values appear anywhere in the response.
        assertThat(hook.has("secretHash")).isFalse();
        assertThat(hook.has("headers")).isFalse();
        MvcResult list = get(WEBHOOKS, admin);
        assertThat(text(list)).doesNotContain(secret).doesNotContain("third-party-token");

        Webhook stored = webhookRepo.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getSecretHash()).isEqualTo(sha256Hex(secret));
        assertThat(stored.getSecretHash()).isNotEqualTo(secret);
        assertThat(stored.getHeaders()).contains("third-party-token");
        assertThat(stored.getEvents()).isEqualTo("[\"DEPLOYMENT_SUCCESS\"]");
    }

    @Test
    @DisplayName("a webhook URL must be http(s) with a host: blank, ftp and host-less URLs are refused")
    void webhookUrlValidation() throws Exception {
        String admin = adminToken();

        MvcResult blank = post(WEBHOOKS, admin, webhookBody("x", "", "s"));
        assertThat(status(blank)).isEqualTo(400);
        assertThat(code(blank)).isEqualTo("WEBHOOK_URL_INVALID");

        MvcResult ftp = post(WEBHOOKS, admin, webhookBody("x", "ftp://hooks.example.test/x", "s"));
        assertThat(status(ftp)).isEqualTo(400);
        assertThat(code(ftp)).isEqualTo("WEBHOOK_URL_INVALID");

        MvcResult noHost = post(WEBHOOKS, admin, webhookBody("x", "https:///x", "s"));
        assertThat(status(noHost)).isEqualTo(400);
        assertThat(code(noHost)).isEqualTo("WEBHOOK_URL_INVALID");
    }

    @Test
    @DisplayName("list, update (blank secret keeps the hash, a new one replaces it), toggle and delete")
    void webhookListUpdateToggleDelete() throws Exception {
        String admin = adminToken();
        String secret = "first-" + uniqueSlug("s");
        JsonNode hook = createWebhook(admin, "https://hooks.example.test/" + uniqueSlug("a"), secret);
        String id = hook.path("id").asText();
        UUID uuid = UUID.fromString(id);

        assertThat(containsId(data(get(WEBHOOKS, admin)), id)).isTrue();

        String newUrl = "https://hooks.example.test/" + uniqueSlug("b");
        MvcResult upd = put(WEBHOOKS + "/" + id, admin, Map.of(
            "name", "renamed", "url", newUrl, "secret", "",
            "events", "[\"LICENSE_EXPIRY\"]", "enabled", false));
        assertThat(status(upd)).as(text(upd)).isEqualTo(200);
        assertThat(code(upd)).isEqualTo("WEBHOOK_UPDATED");
        assertThat(data(upd).path("name").asText()).isEqualTo("renamed");
        assertThat(data(upd).path("url").asText()).isEqualTo(newUrl);
        assertThat(data(upd).path("enabled").asBoolean()).isFalse();
        assertThat(webhookRepo.findById(uuid).orElseThrow().getSecretHash())
            .as("blank secret leaves the stored hash alone").isEqualTo(sha256Hex(secret));
        assertThat(webhookRepo.findById(uuid).orElseThrow().getEvents()).isEqualTo("[\"LICENSE_EXPIRY\"]");

        String rotated = "second-" + uniqueSlug("s");
        put(WEBHOOKS + "/" + id, admin, Map.of(
            "name", "renamed", "url", newUrl, "secret", rotated,
            "events", "[\"LICENSE_EXPIRY\"]", "enabled", false));
        assertThat(webhookRepo.findById(uuid).orElseThrow().getSecretHash()).isEqualTo(sha256Hex(rotated));

        MvcResult badUrl = put(WEBHOOKS + "/" + id, admin, Map.of(
            "name", "renamed", "url", "mailto:x", "events", "[]", "enabled", true));
        assertThat(status(badUrl)).isEqualTo(400);
        assertThat(code(badUrl)).isEqualTo("WEBHOOK_URL_INVALID");

        MvcResult toggle = patch(WEBHOOKS + "/" + id + "/toggle", admin, null);
        assertThat(status(toggle)).isEqualTo(200);
        assertThat(code(toggle)).isEqualTo("WEBHOOK_TOGGLED");
        assertThat(data(toggle).path("id").asText()).isEqualTo(id);
        assertThat(webhookRepo.findById(uuid).orElseThrow().isEnabled()).isTrue();

        MvcResult del = delete(WEBHOOKS + "/" + id, admin);
        assertThat(status(del)).isEqualTo(200);
        assertThat(code(del)).isEqualTo("WEBHOOK_DELETED");
        assertThat(data(del).path("id").asText()).isEqualTo(id);
        assertThat(containsId(data(get(WEBHOOKS, admin)), id)).isFalse();

        MvcResult again = delete(WEBHOOKS + "/" + id, admin);
        assertThat(status(again)).isEqualTo(400);
        assertThat(body(again).path("message").asText()).startsWith("Webhook not found");
        assertThat(status(patch(WEBHOOKS + "/" + id + "/toggle", admin, null))).isEqualTo(400);
        assertThat(status(put(WEBHOOKS + "/" + id, admin, webhookBody("x", newUrl, "s")))).isEqualTo(400);
    }

    @Test
    @DisplayName("test-delivery never throws: an unreachable target is a 200 {ok:false, detail:'Delivery failed: …'}")
    void webhookTestDeliveryIsDeterministicFailure() throws Exception {
        String admin = adminToken();
        // A closed loopback port: allowed by allowInsecureTargets, refused instantly by the kernel.
        JsonNode hook = createWebhook(admin, "http://127.0.0.1:9/hook", "s");
        String id = hook.path("id").asText();

        MvcResult r = post(WEBHOOKS + "/" + id + "/test", admin, null);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(data(r).path("ok").asBoolean()).isFalse();
        assertThat(data(r).path("detail").asText()).startsWith("Delivery failed:");
        assertThat(data(r).has("statusCode")).as("no HTTP exchange happened").isFalse();

        // No delivery row is written when the exchange never happened.
        assertThat(data(get(WEBHOOKS + "/" + id + "/logs", admin)).size()).isZero();
        assertThat(webhookRepo.findById(UUID.fromString(id)).orElseThrow().getFireCount()).isZero();

        MvcResult missing = post(WEBHOOKS + "/" + UUID.randomUUID() + "/test", admin, null);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).startsWith("Webhook not found");
    }

    @Test
    @DisplayName("delivery logs: the global feed and the per-webhook feed are arrays; an unknown webhook is refused")
    void webhookLogs() throws Exception {
        String admin = adminToken();
        JsonNode hook = createWebhook(admin, "https://hooks.example.test/" + uniqueSlug("logs"), "s");
        String id = hook.path("id").asText();

        MvcResult global = get(WEBHOOKS + "/logs", admin);
        assertThat(status(global)).isEqualTo(200);
        assertThat(data(global).isArray()).isTrue();
        assertThat(data(global).size()).isLessThanOrEqualTo(50);

        MvcResult mine = get(WEBHOOKS + "/" + id + "/logs", admin);
        assertThat(status(mine)).isEqualTo(200);
        assertThat(data(mine).isArray()).isTrue();
        assertThat(data(mine).size()).isZero();

        MvcResult missing = get(WEBHOOKS + "/" + UUID.randomUUID() + "/logs", admin);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).startsWith("Webhook not found");
    }

    // ── api keys ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an API key's raw value is shown exactly once with a zgn_live_ prefix; list and revoke never leak it; revoking twice is refused")
    void apiKeyLifecycle() throws Exception {
        String admin = adminToken();
        String name = uniqueSlug("it-key");

        MvcResult r = post(API_KEYS, admin, Map.of("name", name, "scopes", List.of("read:orgs", "write:deployments")));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        assertThat(code(r)).isEqualTo("API_KEY_CREATED");
        String rawKey = data(r).path("rawKey").asText();
        assertThat(rawKey).startsWith("zgn_live_").hasSize("zgn_live_".length() + 64);
        JsonNode key = data(r).path("apiKey");
        String id = key.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(key.path("name").asText()).isEqualTo(name);
        assertThat(key.path("keyPrefix").asText()).isEqualTo(rawKey.substring(0, 20));
        assertThat(key.path("scopes").asText()).isEqualTo("[\"read:orgs\",\"write:deployments\"]");
        assertThat(key.path("revoked").asBoolean()).isFalse();
        assertThat(key.path("createdBy").asText()).isEqualTo(ADMIN);
        assertThat(key.path("keyHash").asText()).isEqualTo(sha256Hex(rawKey)).isNotEqualTo(rawKey);

        ApiKey stored = apiKeyRepo.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getKeyHash()).isEqualTo(sha256Hex(rawKey));

        MvcResult list = get(API_KEYS, admin);
        assertThat(status(list)).isEqualTo(200);
        assertThat(containsId(data(list), id)).isTrue();
        assertThat(text(list)).doesNotContain(rawKey);
        for (JsonNode k : data(list)) assertThat(k.has("rawKey")).isFalse();

        MvcResult revoke = delete(API_KEYS + "/" + id + "/revoke", admin);
        assertThat(status(revoke)).isEqualTo(200);
        assertThat(code(revoke)).isEqualTo("API_KEY_REVOKED");
        assertThat(data(revoke).path("id").asText()).isEqualTo(id);
        ApiKey revoked = apiKeyRepo.findById(UUID.fromString(id)).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.getRevokedBy()).isEqualTo(ADMIN);
        assertThat(revoked.getRevokedAt()).isNotNull();

        MvcResult again = delete(API_KEYS + "/" + id + "/revoke", admin);
        assertThat(status(again)).isEqualTo(400);
        assertThat(body(again).path("message").asText()).startsWith("API key is already revoked");

        MvcResult missing = delete(API_KEYS + "/" + UUID.randomUUID() + "/revoke", admin);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).startsWith("API key not found");

        // No scopes at all serialises as an empty array, not null.
        MvcResult noScopes = post(API_KEYS, admin, Map.of("name", uniqueSlug("it-key-empty")));
        assertThat(status(noScopes)).isEqualTo(201);
        assertThat(data(noScopes).path("apiKey").path("scopes").asText()).isEqualTo("[]");
    }

    // ── integration catalogue ───────────────────────────────────────────────

    @Test
    @DisplayName("the catalogue has the six seeded integrations; toggle flips enabled and PUT config stores the JSON")
    void integrationCatalogue() throws Exception {
        String admin = adminToken();
        MvcResult list = get(BASE, admin);
        assertThat(status(list)).isEqualTo(200);
        JsonNode all = data(list);
        assertThat(all.isArray()).isTrue();
        for (String code : SEEDED_INTEGRATIONS) {
            JsonNode n = byCode(all, code);
            assertThat(n.path("id").asText()).isNotBlank();
            assertThat(n.path("name").asText()).isNotBlank();
        }

        // Toggle twice so the seed row ends where it started, whatever another test left it at.
        JsonNode datadog = byCode(all, "datadog");
        String id = datadog.path("id").asText();
        boolean before = datadog.path("enabled").asBoolean();
        MvcResult flipped = post(BASE + "/" + id + "/toggle", admin, null);
        assertThat(status(flipped)).as(text(flipped)).isEqualTo(200);
        assertThat(code(flipped)).isEqualTo("INTEGRATION_TOGGLED");
        assertThat(data(flipped).path("enabled").asBoolean()).isEqualTo(!before);
        assertThat(data(flipped).path("code").asText()).isEqualTo("datadog");
        MvcResult back = post(BASE + "/" + id + "/toggle", admin, null);
        assertThat(data(back).path("enabled").asBoolean()).isEqualTo(before);

        String config = "{\"apiKey\":\"" + uniqueSlug("dd") + "\",\"site\":\"datadoghq.eu\"}";
        MvcResult cfg = put(BASE + "/" + id + "/config", admin, Map.of("config", config));
        assertThat(status(cfg)).isEqualTo(200);
        assertThat(code(cfg)).isEqualTo("INTEGRATION_UPDATED");
        assertThat(data(cfg).path("configJson").asText()).isEqualTo(config);
        assertThat(byCode(data(get(BASE, admin)), "datadog").path("configJson").asText()).isEqualTo(config);

        MvcResult missing = post(BASE + "/" + UUID.randomUUID() + "/toggle", admin, null);
        assertThat(status(missing)).isEqualTo(400);
        assertThat(body(missing).path("message").asText()).startsWith("Integration not found");
        assertThat(status(put(BASE + "/" + UUID.randomUUID() + "/config", admin, Map.of("config", "{}")))).isEqualTo(400);
    }

    // ── roles ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("webhooks and API keys are ADMIN-only; VIEWER and SUPPORT are ACCESS_DENIED on read and write")
    void roleGating() throws Exception {
        String admin = adminToken();
        String viewer = tokenFor(admin, "VIEWER");
        String support = tokenFor(admin, "SUPPORT");
        String hookId = createWebhook(admin, "https://hooks.example.test/" + uniqueSlug("role"), "s")
            .path("id").asText();

        for (String token : List.of(viewer, support)) {
            MvcResult listHooks = get(WEBHOOKS, token);
            assertThat(status(listHooks)).isEqualTo(403);
            assertThat(code(listHooks)).isEqualTo("ACCESS_DENIED");
            assertThat(code(post(WEBHOOKS, token, webhookBody("x", "https://hooks.example.test/x", "s")))).isEqualTo("ACCESS_DENIED");
            assertThat(code(put(WEBHOOKS + "/" + hookId, token, webhookBody("x", "https://hooks.example.test/x", "s")))).isEqualTo("ACCESS_DENIED");
            assertThat(code(patch(WEBHOOKS + "/" + hookId + "/toggle", token, null))).isEqualTo("ACCESS_DENIED");
            assertThat(code(post(WEBHOOKS + "/" + hookId + "/test", token, null))).isEqualTo("ACCESS_DENIED");
            assertThat(code(delete(WEBHOOKS + "/" + hookId, token))).isEqualTo("ACCESS_DENIED");
            assertThat(code(get(WEBHOOKS + "/logs", token))).isEqualTo("ACCESS_DENIED");
            assertThat(code(get(WEBHOOKS + "/" + hookId + "/logs", token))).isEqualTo("ACCESS_DENIED");
            assertThat(code(get(API_KEYS, token))).isEqualTo("ACCESS_DENIED");
            assertThat(code(post(API_KEYS, token, Map.of("name", "x")))).isEqualTo("ACCESS_DENIED");
            assertThat(code(delete(API_KEYS + "/" + UUID.randomUUID() + "/revoke", token))).isEqualTo("ACCESS_DENIED");
        }
        // The catalogue itself is readable by any signed-in operator.
        assertThat(status(get(BASE, viewer))).isEqualTo(200);
        // The refused calls changed nothing.
        assertThat(webhookRepo.findById(UUID.fromString(hookId))).isPresent();
        awaitAudit(auditAction("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("a VIEWER cannot toggle an integration or overwrite its configuration")
    void viewerCannotMutateIntegrationCatalogue() throws Exception {
        String admin = adminToken();
        String viewer = tokenFor(admin, "VIEWER");
        String id = byCode(data(get(BASE, admin)), "jira").path("id").asText();

        MvcResult toggle = post(BASE + "/" + id + "/toggle", viewer, null);
        assertThat(status(toggle)).isEqualTo(403);
        assertThat(code(toggle)).isEqualTo("ACCESS_DENIED");

        MvcResult cfg = put(BASE + "/" + id + "/config", viewer, Map.of("config", "{\"hacked\":true}"));
        assertThat(status(cfg)).isEqualTo(403);
        assertThat(code(cfg)).isEqualTo("ACCESS_DENIED");
    }
}
