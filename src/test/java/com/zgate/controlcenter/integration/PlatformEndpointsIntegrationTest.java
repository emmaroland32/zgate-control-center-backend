package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform surface that is not a business module: actuator, SSO status, the docker-backed
 * infrastructure READS, the service-key posture on the M2M prefixes, and the security chain's
 * baseline answers (401 envelope, VIEWER boundaries, allow-list off). Nothing here mutates the host:
 * container start/stop/restart and volume delete are deliberately never called.
 */
class PlatformEndpointsIntegrationTest extends AbstractIntegrationTest {

    private static final String INFRA = "/api/v1/infrastructure";

    private String createOrgId(String token) throws Exception {
        MvcResult r = post("/api/v1/organizations", token, Map.of(
            "name", uniqueSlug("Platform Org"), "slug", uniqueSlug("platform-org"),
            "tier", "STANDARD", "deploymentEnv", "STAGING"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r).path("id").asText();
    }

    // ── actuator ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("actuator health and info are permitAll; health is UP and hides component detail from an anonymous caller")
    void actuatorHealthAndInfo() throws Exception {
        MvcResult health = get("/actuator/health", null);
        assertThat(status(health)).as(text(health)).isEqualTo(200);
        assertThat(body(health).path("status").asText()).isEqualTo("UP");
        assertThat(body(health).has("components")).as("show-details=when-authorized").isFalse();
        assertThat(health.getResponse().getHeader("X-Api-Envelope")).as("not a controller response").isNull();

        MvcResult info = get("/actuator/info", null);
        assertThat(status(info)).isEqualTo(200);
        assertThat(body(info).isObject()).isTrue();

        // Anything else under /actuator is not exposed.
        assertThat(status(get("/actuator/env", null))).isIn(401, 404);
    }

    // ── SSO ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OIDC is off: status says so and authorize is a clean 503 OIDC_DISABLED with no state cookie")
    void oidcDisabled() throws Exception {
        MvcResult st = get("/api/v1/auth/oidc/status", null);
        assertThat(status(st)).isEqualTo(200);
        assertThat(data(st).path("enabled").asBoolean()).isFalse();

        MvcResult authorize = get("/api/v1/auth/oidc/authorize", null);
        assertThat(status(authorize)).as(text(authorize)).isEqualTo(503);
        assertThat(code(authorize)).isEqualTo("OIDC_DISABLED");
        assertThat(authorize.getResponse().getCookie("cc_sso_state")).isNull();

        MvcResult callback = post("/api/v1/auth/oidc/callback", null, Map.of("code", "x", "state", "y"));
        assertThat(status(callback)).isEqualTo(503);
        assertThat(code(callback)).isEqualTo("OIDC_DISABLED");
    }

    // ── infrastructure (read-only) ──────────────────────────────────────────

    @Test
    @DisplayName("infrastructure READ endpoints answer 200 with benign payloads whether or not docker is present; readable by any role")
    void infrastructureReadsAreBenign() throws Exception {
        String admin = adminToken();
        String viewer = tokenFor(admin, "VIEWER");

        MvcResult containers = get(INFRA + "/containers", viewer);
        assertThat(status(containers)).as(text(containers)).isEqualTo(200);
        assertThat(data(containers).isArray()).isTrue();
        for (JsonNode c : data(containers)) {
            assertThat(c.has("id")).isTrue();
            assertThat(c.has("name")).isTrue();
            assertThat(c.has("state")).isTrue();
        }

        MvcResult stats = get(INFRA + "/containers/stats", viewer);
        assertThat(status(stats)).isEqualTo(200);
        assertThat(data(stats).isArray()).isTrue();

        MvcResult volumes = get(INFRA + "/volumes", viewer);
        assertThat(status(volumes)).isEqualTo(200);
        assertThat(data(volumes).isArray()).isTrue();

        MvcResult compose = get(INFRA + "/compose/config", viewer);
        assertThat(status(compose)).isEqualTo(200);
        assertThat(data(compose).path("config").isTextual()).isTrue();

        MvcResult resources = get(INFRA + "/resources", viewer);
        assertThat(status(resources)).isEqualTo(200);
        assertThat(data(resources).path("systemDf").isTextual()).isTrue();

        // A container id that cannot exist: docker's own error text (or "" without docker) comes
        // back as the log body, never an error status.
        MvcResult logs = get(INFRA + "/containers/" + uniqueSlug("no-such-container") + "/logs?tail=5", viewer);
        assertThat(status(logs)).isEqualTo(200);
        assertThat(data(logs).path("logs").isTextual()).isTrue();

        // Mutations are role-gated (asserted on the refusal only — never invoked with a real role).
        MvcResult stop = post(INFRA + "/containers/" + uniqueSlug("never") + "/stop", viewer, null);
        assertThat(status(stop)).isEqualTo(403);
        assertThat(code(stop)).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(INFRA + "/containers/" + uniqueSlug("never") + "/restart", viewer, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(INFRA + "/containers/" + uniqueSlug("never") + "/start", viewer, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(delete(INFRA + "/volumes/" + uniqueSlug("never"), viewer))).isEqualTo("ACCESS_DENIED");
        assertThat(code(delete(INFRA + "/volumes/" + uniqueSlug("never"), tokenFor(admin, "ADMIN"))))
            .as("volume delete is SUPER_ADMIN only").isEqualTo("ACCESS_DENIED");
    }

    // ── service-key posture ─────────────────────────────────────────────────

    @Test
    @DisplayName("with enforce=false telemetry ingest needs only the org-id header; an unknown org is refused and nothing is stored")
    void telemetryIngestWithoutServiceKey() throws Exception {
        String admin = adminToken();
        String orgId = createOrgId(admin);
        String marker = uniqueSlug("it-telemetry");

        MvcResult accepted = postWith("/api/v1/telemetry/ingest", null,
            List.of(Map.of("level", "ERROR", "category", "SYSTEM", "message", marker),
                    Map.of("level", "INFO", "category", "API", "message", marker + "-2")),
            Map.of("X-Control-Center-Org-Id", orgId, "X-Control-Center-Node-Id", "node-a"));
        assertThat(status(accepted)).as(text(accepted)).isEqualTo(202);
        // Actual shape: the ResponseBodyAdvice still wraps the empty 202, so the body is the
        // envelope with a null payload rather than nothing at all.
        assertThat(code(accepted)).isEqualTo("OK");
        assertThat(body(accepted).path("data").isNull()).isTrue();

        JsonNode page = data(get("/api/v1/telemetry?orgId=" + orgId + "&size=10", admin));
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("content").findValuesAsText("message")).contains(marker, marker + "-2");
        for (JsonNode e : page.path("content")) {
            assertThat(e.path("organizationId").asText()).as("org comes from the header").isEqualTo(orgId);
            assertThat(e.path("occurredAt").isNull()).isFalse();
        }
        JsonNode stats = data(get("/api/v1/telemetry/stats/org/" + orgId, admin));
        assertThat(stats.path("unacknowledgedErrors").asLong()).isEqualTo(1);
        assertThat(data(get("/api/v1/organizations/" + orgId, admin)).path("lastSeenAt").isNull())
            .as("heartbeat").isFalse();

        MvcResult unknownOrg = postWith("/api/v1/telemetry/ingest", null,
            List.of(Map.of("level", "INFO", "category", "SYSTEM", "message", "x")),
            Map.of("X-Control-Center-Org-Id", UUID.randomUUID().toString()));
        assertThat(status(unknownOrg)).isEqualTo(400);
        assertThat(body(unknownOrg).path("message").asText()).startsWith("Organization not found");

        // A wrong service key is irrelevant while enforcement is off.
        MvcResult wrongKey = postWith("/api/v1/telemetry/ingest", null,
            List.of(Map.of("level", "INFO", "category", "SYSTEM", "message", marker + "-3")),
            Map.of("X-Control-Center-Org-Id", orgId, "X-Control-Center-Service-Key", "zgn_not_the_key"));
        assertThat(status(wrongKey)).isEqualTo(202);
    }

    @Test
    @DisplayName("the /api/v1/backups prefix is enforced regardless of the global flag: no headers is a raw 401 invalid_service_key")
    void backupsPrefixAlwaysEnforced() throws Exception {
        MvcResult r = get("/api/v1/backups", null);
        assertThat(status(r)).isEqualTo(401);
        assertThat(text(r)).isEqualTo("{\"error\":\"invalid_service_key\"}");
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
        // The other M2M prefixes are not enforced by the filter: an org-id header alone reaches the
        // controller, which answers a raw (un-enveloped) empty bundle list for a keyless fresh org.
        String orgId = createOrgId(adminToken());
        MvcResult bundle = getWith("/api/v1/licenses/bundle", null, Map.of("X-Control-Center-Org-Id", orgId));
        assertThat(status(bundle)).as(text(bundle)).isEqualTo(200);
        assertThat(body(bundle).isArray()).isTrue();
        assertThat(bundle.getResponse().getHeader("X-Api-Envelope")).isNull();
    }

    // ── security chain baseline ─────────────────────────────────────────────

    @Test
    @DisplayName("an anonymous or garbage-token call to a protected route is the 401 UNAUTHORIZED envelope")
    void unauthenticatedIs401Envelope() throws Exception {
        for (String path : List.of("/api/v1/fleet/overview", "/api/v1/organizations", "/api/v1/alerts/rules",
                                   "/api/v1/integrations", "/api/v1/admin/backups/stats", "/api/v1/provisioning/readiness")) {
            MvcResult r = get(path, null);
            assertThat(status(r)).as(path).isEqualTo(401);
            assertThat(code(r)).as(path).isEqualTo("UNAUTHORIZED");
            assertThat(body(r).path("message").asText()).isEqualTo("Sign in to continue.");
            assertThat(body(r).path("status").asInt()).isEqualTo(401);
        }
        MvcResult garbage = get("/api/v1/organizations", "not.a.jwt");
        assertThat(status(garbage)).isEqualTo(401);
        assertThat(code(garbage)).isEqualTo("UNAUTHORIZED");
        // Step-up is authenticated() even though it lives under /auth/**.
        MvcResult stepUp = post("/api/v1/auth/step-up", null, Map.of("password", "x", "action", "POST /x"));
        assertThat(status(stepUp)).isEqualTo(401);
    }

    @Test
    @DisplayName("a VIEWER reads any un-annotated route but is ACCESS_DENIED (and audited) on ADMIN routes; the IP allow-list is off so loopback is admitted")
    void viewerBoundaries() throws Exception {
        String admin = adminToken();
        String viewerEmail = unique("it-platform-viewer");
        createOperator(admin, viewerEmail, "VIEWER", GOOD_PW);
        String viewer = login(viewerEmail, GOOD_PW);

        MvcResult orgs = get("/api/v1/organizations", viewer);
        assertThat(status(orgs)).isEqualTo(200);
        assertThat(data(orgs).isArray()).isTrue();
        assertThat(data(orgs).size()).isGreaterThanOrEqualTo(6);
        assertThat(orgs.getResponse().getHeader("X-Api-Envelope")).isEqualTo("1");
        for (JsonNode o : data(orgs)) {
            assertThat(o.path("serviceApiKey").isNull()).as("plaintext key only on create/regenerate").isTrue();
        }
        assertThat(status(get("/api/v1/organizations/dashboard", viewer))).isEqualTo(200);
        assertThat(status(get("/api/v1/releases", viewer))).isEqualTo(200);
        assertThat(status(get("/api/v1/reports/summary", viewer))).isEqualTo(200);
        assertThat(status(get("/api/v1/database/health", viewer))).isEqualTo(200);

        MvcResult create = post("/api/v1/organizations", viewer, Map.of(
            "name", "v", "slug", uniqueSlug("v"), "tier", "FREE", "deploymentEnv", "LOCAL"));
        assertThat(status(create)).isEqualTo(403);
        assertThat(code(create)).isEqualTo("ACCESS_DENIED");
        assertThat(code(get("/api/v1/config", viewer))).isEqualTo("ACCESS_DENIED");
        assertThat(code(get("/api/v1/users", viewer))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post("/api/v1/organizations/" + UUID.randomUUID() + "/regenerate-key", viewer, null))).isEqualTo("ACCESS_DENIED");
        AuditLog denied = awaitAudit(audit("ACCESS_DENIED", viewerEmail));
        assertThat(denied.getStatus()).isEqualTo(AuditLog.Status.FAILURE);
        assertThat(denied.getDetails()).contains("ROLE_VIEWER");

        // controlcenter.security.ipAllowlist is blank → the filter is inert; a loopback MockMvc
        // caller signs in and is served (a blocked address would be 403 IP_NOT_ALLOWED before auth).
        assertThat(data(get("/api/v1/users/me", viewer)).path("email").asText()).isEqualTo(viewerEmail);
    }
}
