package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.OrgInstance;
import com.zgate.controlcenter.repository.OrgInstanceRepository;
import com.zgate.controlcenter.repository.ReleaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Releases and deployments end to end: publishing with digest validation and the global latest flag,
 * approval transitions, push-update to self-hosted (stackless) orgs, status side-effects on the org,
 * rollback, the org-facing image pull-token M2M contract, and the live-instance registry view.
 */
class ReleasesAndDeploymentsIntegrationTest extends AbstractIntegrationTest {

    private static final String RELEASES = "/api/v1/releases";
    private static final String DEPLOYMENTS = "/api/v1/deployments";
    private static final String ORGS = "/api/v1/organizations";
    private static final String APEX = "b0000001-0000-0000-0000-000000000001";
    private static final UUID SEED_LATEST = UUID.fromString("c0000001-0000-0000-0000-000000000001"); // 2.5.0

    @Autowired ReleaseRepository releaseRepo;
    @Autowired OrgInstanceRepository instanceRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String version(String tag) {
        // Unique, semver-shaped, and never "greater" than the seed line so entitlement tests stay predictable.
        return "0.0." + (System.nanoTime() % 1_000_000_000L) + "-" + tag;
    }

    private static Map<String, Object> releaseBody(String version) {
        Map<String, Object> m = new HashMap<>();
        m.put("version", version);
        m.put("channel", "STABLE");
        m.put("dockerTag", "zgate/backend:" + version);
        m.put("dockerRegistry", "registry.example.test");
        m.put("releaseNotes", "integration fixture");
        return m;
    }

    private JsonNode publish(String token, Map<String, Object> body) throws Exception {
        MvcResult r = post(RELEASES, token, body);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("RELEASE_PUBLISHED");
        return data(r);
    }

    private JsonNode publish(String token, String tag) throws Exception {
        return publish(token, releaseBody(version(tag)));
    }

    private String createOrg(String token, String prefix) throws Exception {
        String slug = uniqueSlug(prefix);
        MvcResult r = post(ORGS, token, Map.of("name", "Dep Org " + slug, "slug", slug,
            "tier", "ENTERPRISE", "deploymentEnv", "PRODUCTION"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r).path("id").asText();
    }

    private JsonNode org(String token, String id) throws Exception {
        return data(get(ORGS + "/" + id, token));
    }

    private JsonNode pushTo(String token, String releaseId, String... orgIds) throws Exception {
        MvcResult r = post(DEPLOYMENTS + "/push-update", token,
            Map.of("organizationIds", List.of(orgIds), "releaseId", releaseId));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("UPDATE_PUSHED");
        return data(r);
    }

    private JsonNode setStatus(String token, String deploymentId, String st, String logs) throws Exception {
        Map<String, Object> b = new HashMap<>();
        b.put("status", st);
        if (logs != null) b.put("logs", logs);
        MvcResult r = patch(DEPLOYMENTS + "/" + deploymentId + "/status", token, b);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        return data(r);
    }

    private JsonNode orgDeployments(String token, String orgId) throws Exception {
        MvcResult r = get(DEPLOYMENTS + "/org/" + orgId + "?page=0&size=50", token);
        assertThat(status(r)).isEqualTo(200);
        return data(r);
    }

    private static JsonNode byId(JsonNode page, String id) {
        for (JsonNode d : page.path("content")) if (id.equals(d.path("id").asText())) return d;
        throw new AssertionError("deployment " + id + " not in page");
    }

    private MvcResult pullToken(String orgId, String version) throws Exception {
        return postWith(DEPLOYMENTS + "/pull-token", null,
            version == null ? null : Map.of("version", version), Map.of("X-Control-Center-Org-Id", orgId));
    }

    /** Put the seed 2.5.0 back as the fleet's latest so other classes see the shipped seed state. */
    private void restoreSeedLatest() {
        releaseRepo.findByIsLatestTrue().ifPresent(r -> {
            if (!SEED_LATEST.equals(r.getId())) { r.setLatest(false); releaseRepo.save(r); }
        });
        releaseRepo.findById(SEED_LATEST).ifPresent(r -> {
            if (!r.isLatest()) { r.setLatest(true); releaseRepo.save(r); }
        });
    }

    // ── releases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("publish returns 200 (not 201): APPROVED by default, not latest, blank digests stored as null, visible in the list")
    void publishRelease() throws Exception {
        String token = adminToken();
        String v = version("pub");
        Map<String, Object> body = releaseBody(v);
        body.put("imageDigest", "");
        body.put("webImageDigest", "");
        body.put("hasBreakingChanges", true);
        body.put("migrations", "[\"V99__x.sql\"]");
        MvcResult r = post(RELEASES, token, body);
        assertThat(status(r)).as("publish is ResponseEntity.ok").isEqualTo(200);
        assertThat(code(r)).isEqualTo("RELEASE_PUBLISHED");
        JsonNode rel = data(r);
        String id = rel.path("id").asText();
        assertThat(rel.path("version").asText()).isEqualTo(v);
        assertThat(rel.path("channel").asText()).isEqualTo("STABLE");
        assertThat(rel.path("dockerTag").asText()).isEqualTo("zgate/backend:" + v);
        assertThat(rel.path("dockerRegistry").asText()).isEqualTo("registry.example.test");
        assertThat(rel.path("approvalStatus").asText()).isEqualTo("APPROVED");
        assertThat(rel.path("latest").asBoolean()).isFalse();
        assertThat(rel.path("hasBreakingChanges").asBoolean()).isTrue();
        assertThat(rel.path("imageDigest").isNull()).as("blank digest normalised to null").isTrue();
        assertThat(rel.path("webImageDigest").isNull()).isTrue();
        assertThat(rel.path("publishedBy").asText()).isEqualTo(ADMIN);
        assertThat(rel.path("publishedAt").isTextual()).isTrue();
        assertThat(rel.path("migrations").asText()).isEqualTo("[\"V99__x.sql\"]");

        assertThat(data(get(RELEASES + "/" + id, token)).path("version").asText()).isEqualTo(v);
        boolean listed = false;
        for (JsonNode x : data(get(RELEASES, token))) if (id.equals(x.path("id").asText())) listed = true;
        assertThat(listed).as("publish evicts the releases cache").isTrue();

        MvcResult unknown = get(RELEASES + "/" + UUID.randomUUID(), token);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("Release not found");
    }

    @Test
    @DisplayName("imageDigest must be empty or sha256:<64 hex>; other required fields and the channel enum are validated")
    void publishValidation() throws Exception {
        String token = adminToken();
        Map<String, Object> bad = releaseBody(version("digest"));
        bad.put("imageDigest", "sha256:abc");
        bad.put("webImageDigest", "md5:" + "0".repeat(32));
        MvcResult r = post(RELEASES, token, bad);
        assertThat(status(r)).isEqualTo(400);
        assertThat(code(r)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(r).path("fieldErrors").path("imageDigest").asText()).contains("sha256:<64 hex chars>");
        assertThat(body(r).path("fieldErrors").has("webImageDigest")).isTrue();
        assertThat(releaseRepo.findByVersion(bad.get("version").toString())).isEmpty();

        Map<String, Object> good = releaseBody(version("digest-ok"));
        String digest = "sha256:" + "0123456789abcdef".repeat(4);
        good.put("imageDigest", digest);
        JsonNode ok = publish(token, good);
        assertThat(ok.path("imageDigest").asText()).isEqualTo(digest);

        MvcResult empty = post(RELEASES, token, Map.of());
        assertThat(status(empty)).isEqualTo(400);
        JsonNode fe = body(empty).path("fieldErrors");
        assertThat(fe.has("version")).isTrue();
        assertThat(fe.has("channel")).isTrue();
        assertThat(fe.has("dockerTag")).isTrue();

        Map<String, Object> badChannel = releaseBody(version("chan"));
        badChannel.put("channel", "NIGHTLY");
        MvcResult bc = post(RELEASES, token, badChannel);
        assertThat(status(bc)).isEqualTo(400);
        assertThat(code(bc)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(bc).path("message").asText()).contains("STABLE, LTS, BETA, HOTFIX");
    }

    @Test
    @DisplayName("a version can be published once; the seed 2.5.0 and a fresh fixture are both refused on re-publish")
    void duplicateVersion() throws Exception {
        String token = adminToken();
        MvcResult seed = post(RELEASES, token, releaseBody("2.5.0"));
        assertThat(status(seed)).isEqualTo(400);
        assertThat(body(seed).path("message").asText()).contains("Version already exists").contains("2.5.0");

        String v = version("dup");
        publish(token, releaseBody(v));
        MvcResult again = post(RELEASES, token, releaseBody(v));
        assertThat(status(again)).isEqualTo(400);
        assertThat(body(again).path("message").asText()).contains("Version already exists");
    }

    @Test
    @DisplayName("latest=true clears the previous latest globally and GET /latest follows the flag")
    void latestFlagFlips() throws Exception {
        String token = adminToken();
        try {
            Map<String, Object> a = releaseBody(version("latest-a"));
            a.put("latest", true);
            JsonNode relA = publish(token, a);
            assertThat(relA.path("latest").asBoolean()).isTrue();
            assertThat(data(get(RELEASES + "/latest", token)).path("id").asText()).isEqualTo(relA.path("id").asText());
            assertThat(data(get(RELEASES + "/" + SEED_LATEST, token)).path("latest").asBoolean())
                .as("seed 2.5.0 lost the flag").isFalse();

            Map<String, Object> b = releaseBody(version("latest-b"));
            b.put("channel", "LTS"); // the flag is global, not per channel
            b.put("latest", true);
            JsonNode relB = publish(token, b);
            assertThat(relB.path("latest").asBoolean()).isTrue();
            assertThat(data(get(RELEASES + "/" + relA.path("id").asText(), token)).path("latest").asBoolean()).isFalse();
            MvcResult latest = get(RELEASES + "/latest", token);
            assertThat(status(latest)).isEqualTo(200);
            assertThat(data(latest).path("id").asText()).isEqualTo(relB.path("id").asText());

            // A plain publish leaves the flag where it is.
            publish(token, "not-latest");
            assertThat(data(get(RELEASES + "/latest", token)).path("id").asText()).isEqualTo(relB.path("id").asText());
        } finally {
            restoreSeedLatest();
        }
        assertThat(data(get(RELEASES + "/latest", adminToken())).path("version").asText()).isEqualTo("2.5.0");
    }

    @Test
    @DisplayName("reject and approve move approvalStatus; unknown ids are 400")
    void approveReject() throws Exception {
        String token = adminToken();
        JsonNode rel = publish(token, "approval");
        String id = rel.path("id").asText();

        MvcResult rej = post(RELEASES + "/" + id + "/reject", token, null);
        assertThat(status(rej)).isEqualTo(200);
        assertThat(code(rej)).isEqualTo("RELEASE_REJECTED");
        assertThat(data(rej).path("approvalStatus").asText()).isEqualTo("REJECTED");
        assertThat(data(get(RELEASES + "/" + id, token)).path("approvalStatus").asText()).isEqualTo("REJECTED");

        MvcResult app = post(RELEASES + "/" + id + "/approve", token, null);
        assertThat(status(app)).isEqualTo(200);
        assertThat(code(app)).isEqualTo("RELEASE_APPROVED");
        assertThat(data(app).path("approvalStatus").asText()).isEqualTo("APPROVED");

        assertThat(status(post(RELEASES + "/" + UUID.randomUUID() + "/approve", token, null))).isEqualTo(400);
        assertThat(status(post(RELEASES + "/" + UUID.randomUUID() + "/reject", token, null))).isEqualTo(400);
    }

    // ── deployments: reads ──────────────────────────────────────────────────

    @Test
    @DisplayName("deployment list, stats and the per-org page reflect the seeded history")
    void deploymentReads() throws Exception {
        String token = adminToken();
        MvcResult list = get(DEPLOYMENTS, token);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).isArray()).isTrue();
        assertThat(data(list).size()).isGreaterThanOrEqualTo(6);

        JsonNode stats = data(get(DEPLOYMENTS + "/stats", token));
        assertThat(stats.path("total").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(stats.path("successful").asLong()).as("Apex, Sanlam, FNB, Demo").isGreaterThanOrEqualTo(4);
        assertThat(stats.path("failed").asLong()).as("Ninety One").isGreaterThanOrEqualTo(1);
        assertThat(stats.path("inProgress").asLong()).as("Coronation").isGreaterThanOrEqualTo(1);
        assertThat(stats.path("total").asLong()).isEqualTo(data(list).size());

        JsonNode page = orgDeployments(token, APEX);
        assertThat(page.path("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        boolean seeded = false;
        for (JsonNode d : page.path("content")) {
            assertThat(d.path("organizationId").asText()).isEqualTo(APEX);
            if ("2.4.5".equals(d.path("fromVersion").asText()) && "2.5.0".equals(d.path("toVersion").asText())
                    && "SUCCESS".equals(d.path("status").asText())) seeded = true;
        }
        assertThat(seeded).isTrue();

        JsonNode small = data(get(DEPLOYMENTS + "/org/" + APEX + "?page=0&size=1", token));
        assertThat(small.path("content").size()).isEqualTo(1);
        assertThat(small.path("size").asInt()).isEqualTo(1);
        assertThat(orgDeployments(token, UUID.randomUUID().toString()).path("totalElements").asLong()).isZero();
    }

    // ── push-update ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("push-update to orgs without a provisioned stack records one PENDING deployment each and no rollout")
    void pushUpdateSelfHosted() throws Exception {
        String token = adminToken();
        String orgA = createOrg(token, "it-push-a");
        String orgB = createOrg(token, "it-push-b");
        JsonNode rel = publish(token, "push");
        String relId = rel.path("id").asText();
        String v = rel.path("version").asText();

        JsonNode res = pushTo(token, relId, orgA, orgB);
        assertThat(res.path("rolloutId").isNull()).as("nothing to roll out: no stacks").isTrue();
        assertThat(res.path("rolloutStackCount").asInt()).isZero();
        assertThat(res.path("selfHostedCount").asInt()).isEqualTo(2);
        assertThat(res.path("deployments").size()).isEqualTo(2);
        for (JsonNode d : res.path("deployments")) {
            assertThat(d.path("status").asText()).isEqualTo("PENDING");
            assertThat(d.path("releaseId").asText()).isEqualTo(relId);
            assertThat(d.path("toVersion").asText()).isEqualTo(v);
            assertThat(d.path("fromVersion").isNull()).as("fresh org has no deployed version").isTrue();
            assertThat(d.path("deployedBy").asText()).isEqualTo(ADMIN);
            assertThat(d.path("logs").asText()).contains("No Control-Center-provisioned stack");
            assertThat(d.path("organizationId").asText()).isIn(orgA, orgB);
        }
        assertThat(orgDeployments(token, orgA).path("totalElements").asLong()).isEqualTo(1);
        assertThat(orgDeployments(token, orgB).path("totalElements").asLong()).isEqualTo(1);
        // Recording the intent does not touch the org itself.
        assertThat(org(token, orgA).path("deployedVersion").isNull()).isTrue();
        assertThat(org(token, orgA).path("deploymentStatus").asText()).isEqualTo("PROVISIONING");

        MvcResult empty = post(DEPLOYMENTS + "/push-update", token, Map.of());
        assertThat(status(empty)).isEqualTo(400);
        assertThat(code(empty)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(empty).path("fieldErrors").has("organizationIds")).isTrue();
        assertThat(body(empty).path("fieldErrors").has("releaseId")).isTrue();
        MvcResult noOrgs = post(DEPLOYMENTS + "/push-update", token, Map.of("organizationIds", List.of(), "releaseId", relId));
        assertThat(status(noOrgs)).isEqualTo(400);
        assertThat(body(noOrgs).path("fieldErrors").has("organizationIds")).isTrue();

        MvcResult noRelease = post(DEPLOYMENTS + "/push-update", token,
            Map.of("organizationIds", List.of(orgA), "releaseId", UUID.randomUUID()));
        assertThat(status(noRelease)).isEqualTo(400);
        assertThat(body(noRelease).path("message").asText()).contains("Release not found");
        UUID ghost = UUID.randomUUID();
        MvcResult noOrg = post(DEPLOYMENTS + "/push-update", token,
            Map.of("organizationIds", List.of(ghost), "releaseId", relId));
        assertThat(status(noOrg)).isEqualTo(400);
        assertThat(body(noOrg).path("message").asText()).contains("Organization not found").contains(ghost.toString());
    }

    // ── status side-effects ─────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH status: SUCCESS stamps the org's deployedVersion and HEALTHY; FAILED marks it DEGRADED; IN_PROGRESS starts the clock")
    void statusSideEffects() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-status");
        JsonNode rel = publish(token, "status");
        String v = rel.path("version").asText();
        String depId = pushTo(token, rel.path("id").asText(), orgId).path("deployments").get(0).path("id").asText();

        JsonNode running = setStatus(token, depId, "IN_PROGRESS", "[1] pulling");
        assertThat(running.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(running.path("startedAt").isTextual()).isTrue();
        assertThat(running.path("completedAt").isNull()).isTrue();
        assertThat(running.path("logs").asText()).isEqualTo("[1] pulling");
        String startedAt = running.path("startedAt").asText();
        assertThat(org(token, orgId).path("deploymentStatus").asText()).as("not final yet").isEqualTo("PROVISIONING");

        JsonNode ok = setStatus(token, depId, "SUCCESS", null);
        assertThat(ok.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(ok.path("completedAt").isTextual()).isTrue();
        assertThat(ok.path("startedAt").asText()).as("startedAt is set once").isEqualTo(startedAt);
        assertThat(ok.path("logs").asText()).as("null logs keeps the previous text").isEqualTo("[1] pulling");
        JsonNode o = org(token, orgId);
        assertThat(o.path("deployedVersion").asText()).isEqualTo(v);
        assertThat(o.path("deploymentStatus").asText()).isEqualTo("HEALTHY");

        String dep2 = pushTo(token, publish(token, "status-2").path("id").asText(), orgId)
            .path("deployments").get(0).path("id").asText();
        JsonNode failed = setStatus(token, dep2, "FAILED", "boom");
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("completedAt").isTextual()).isTrue();
        JsonNode o2 = org(token, orgId);
        assertThat(o2.path("deploymentStatus").asText()).isEqualTo("DEGRADED");
        assertThat(o2.path("deployedVersion").asText()).as("a failed deploy does not move the version").isEqualTo(v);

        MvcResult bad = patch(DEPLOYMENTS + "/" + dep2 + "/status", token, Map.of("status", "EXPLODED"));
        assertThat(status(bad)).isEqualTo(400);
        assertThat(code(bad)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(bad).path("message").asText()).contains("PENDING, IN_PROGRESS, SUCCESS, FAILED, ROLLED_BACK, CANCELLED");
        MvcResult unknown = patch(DEPLOYMENTS + "/" + UUID.randomUUID() + "/status", token, Map.of("status", "SUCCESS"));
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("Deployment not found");
    }

    // ── rollback ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rollback opens a new IN_PROGRESS deployment with the versions swapped, marks the original ROLLED_BACK and degrades the org")
    void rollback() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-rollback");
        JsonNode v1 = publish(token, "rb-1");
        JsonNode v2 = publish(token, "rb-2");
        String dep1 = pushTo(token, v1.path("id").asText(), orgId).path("deployments").get(0).path("id").asText();

        // No previous version yet: nothing to roll back to.
        MvcResult nothing = post(DEPLOYMENTS + "/" + dep1 + "/rollback", token, null);
        assertThat(status(nothing)).isEqualTo(400);
        assertThat(body(nothing).path("message").asText()).contains("Cannot rollback");

        setStatus(token, dep1, "SUCCESS", null);
        JsonNode dep2 = pushTo(token, v2.path("id").asText(), orgId).path("deployments").get(0);
        assertThat(dep2.path("fromVersion").asText()).isEqualTo(v1.path("version").asText());
        String dep2Id = dep2.path("id").asText();
        setStatus(token, dep2Id, "SUCCESS", null);
        assertThat(org(token, orgId).path("deployedVersion").asText()).isEqualTo(v2.path("version").asText());

        MvcResult r = post(DEPLOYMENTS + "/" + dep2Id + "/rollback", token, null);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("DEPLOYMENT_ROLLED_BACK");
        JsonNode rb = data(r);
        assertThat(rb.path("id").asText()).isNotEqualTo(dep2Id);
        assertThat(rb.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(rb.path("fromVersion").asText()).isEqualTo(v2.path("version").asText());
        assertThat(rb.path("toVersion").asText()).isEqualTo(v1.path("version").asText());
        assertThat(rb.path("deployedBy").asText()).isEqualTo(ADMIN);
        assertThat(rb.path("startedAt").isTextual()).isTrue();
        assertThat(rb.path("organizationId").asText()).isEqualTo(orgId);

        JsonNode page = orgDeployments(token, orgId);
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(byId(page, dep2Id).path("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(byId(page, dep1).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(byId(page, rb.path("id").asText()).path("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode o = org(token, orgId);
        assertThat(o.path("deployedVersion").asText()).isEqualTo(v1.path("version").asText());
        assertThat(o.path("deploymentStatus").asText()).isEqualTo("DEGRADED");

        assertThat(status(post(DEPLOYMENTS + "/" + UUID.randomUUID() + "/rollback", token, null))).isEqualTo(400);
    }

    // ── pull-token (M2M) ────────────────────────────────────────────────────

    @Test
    @DisplayName("pull-token is raw and tokenless: 200 authorized with null credentials (ECR off); unknown org is 402 org_not_found")
    void pullTokenHappyAndUnknownOrg() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-pull");

        MvcResult latest = pullToken(orgId, null);
        assertThat(status(latest)).as(text(latest)).isEqualTo(200);
        assertThat(latest.getResponse().getHeader("X-Api-Envelope")).isNull();
        JsonNode a = body(latest);
        assertThat(a.has("data")).as("no envelope").isFalse();
        assertThat(a.path("authorized").asBoolean()).isTrue();
        assertThat(a.path("reason").asText()).isEqualTo("authorized");
        JsonNode current = data(get(RELEASES + "/latest", token));
        assertThat(a.path("version").asText()).isEqualTo(current.path("version").asText());
        assertThat(a.path("dockerTag").asText()).isEqualTo(current.path("dockerTag").asText());
        assertThat(a.path("message").asText()).contains("Authorized to pull");
        assertThat(a.path("credentialUsername").isNull()).isTrue();
        assertThat(a.path("credentialPassword").isNull()).isTrue();
        assertThat(a.path("credentialExpiresAt").isNull()).isTrue();

        MvcResult explicit = pullToken(orgId, "2.4.5");
        assertThat(status(explicit)).isEqualTo(200);
        assertThat(body(explicit).path("version").asText()).isEqualTo("2.4.5");
        assertThat(body(explicit).path("dockerTag").asText()).isNotBlank();

        MvcResult ghost = pullToken(UUID.randomUUID().toString(), null);
        assertThat(status(ghost)).isEqualTo(402);
        assertThat(ghost.getResponse().getHeader("X-Api-Envelope")).isNull();
        assertThat(body(ghost).path("authorized").asBoolean()).isFalse();
        assertThat(body(ghost).path("reason").asText()).isEqualTo("org_not_found");
        assertThat(body(ghost).path("registry").isNull()).isTrue();
        assertThat(body(ghost).path("dockerTag").isNull()).isTrue();
    }

    @Test
    @DisplayName("pull-token denies with 402 for an unknown, rejected, or over-entitlement release and for a lapsed subscription")
    void pullTokenDenials() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-pull-deny");

        MvcResult missing = pullToken(orgId, "0.0.0-does-not-exist");
        assertThat(status(missing)).isEqualTo(402);
        assertThat(body(missing).path("reason").asText()).isEqualTo("release_not_found");

        JsonNode rejected = publish(token, "pull-rejected");
        assertThat(status(post(RELEASES + "/" + rejected.path("id").asText() + "/reject", token, null))).isEqualTo(200);
        MvcResult notApproved = pullToken(orgId, rejected.path("version").asText());
        assertThat(status(notApproved)).isEqualTo(402);
        assertThat(body(notApproved).path("reason").asText()).isEqualTo("release_not_approved");

        // Entitled up to 1.0.x: 2.5.0 is beyond it, 2.4.5 too; the granularity is minor.
        assertThat(status(patch(ORGS + "/" + orgId + "/entitlements", token, Map.of("entitledVersion", "1.0.0")))).isEqualTo(200);
        MvcResult beyond = pullToken(orgId, "2.5.0");
        assertThat(status(beyond)).isEqualTo(402);
        assertThat(body(beyond).path("reason").asText()).isEqualTo("version_not_entitled");
        assertThat(body(beyond).path("message").asText()).contains("2.5.0").contains("1.0.0");
        // A release within the entitlement (0.0.x fixtures are below 1.0) is still authorized.
        JsonNode within = publish(token, "pull-within");
        MvcResult okWithin = pullToken(orgId, within.path("version").asText());
        assertThat(status(okWithin)).as(text(okWithin)).isEqualTo(200);
        assertThat(body(okWithin).path("version").asText()).isEqualTo(within.path("version").asText());

        // Subscription lapsed: refused before any release lookup.
        assertThat(status(patch(ORGS + "/" + orgId + "/entitlements", token,
            Map.of("subscriptionValidUntil", "2020-01-01T00:00:00")))).isEqualTo(200);
        MvcResult lapsed = pullToken(orgId, null);
        assertThat(status(lapsed)).isEqualTo(402);
        assertThat(body(lapsed).path("reason").asText()).isEqualTo("subscription_lapsed");
        assertThat(body(lapsed).path("message").asText()).contains("lapsed");

        // Renewed: back to authorized.
        assertThat(status(patch(ORGS + "/" + orgId + "/entitlements", token,
            Map.of("subscriptionValidUntil", "2040-01-01T00:00:00")))).isEqualTo(200);
        assertThat(status(pullToken(orgId, null))).isEqualTo(200);

        MvcResult badHeader = postWith(DEPLOYMENTS + "/pull-token", null, null, Map.of("X-Control-Center-Org-Id", "nope"));
        assertThat(status(badHeader)).isEqualTo(400);
    }

    // ── instances ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the org instance view lists nodes seen inside the window and nothing for an unknown org")
    void orgInstances() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-instances");
        assertThat(data(get(DEPLOYMENTS + "/org/" + orgId + "/instances", token)).size()).isZero();

        // Anomaly detection is off in the harness, so telemetry never registers a node; seed one directly.
        instanceRepo.save(OrgInstance.builder()
            .organizationId(UUID.fromString(orgId)).fingerprint("fp-it").nodeId("node-1")
            .platform("kubernetes").appVersion("2.5.0").memUsedMb(512).memMaxMb(2048).uptimeSeconds(3600L).cpuPct(12)
            .lastSeenAt(LocalDateTime.now()).build());
        instanceRepo.save(OrgInstance.builder()
            .organizationId(UUID.fromString(orgId)).fingerprint("fp-it").nodeId("node-stale")
            .firstSeenAt(LocalDateTime.now().minusDays(2)).lastSeenAt(LocalDateTime.now().minusDays(1)).build());

        MvcResult r = get(DEPLOYMENTS + "/org/" + orgId + "/instances", token);
        assertThat(status(r)).isEqualTo(200);
        JsonNode list = data(r);
        assertThat(list.size()).as("only the node seen inside the window").isEqualTo(1);
        JsonNode inst = list.get(0);
        assertThat(inst.path("organizationId").asText()).isEqualTo(orgId);
        assertThat(inst.path("fingerprint").asText()).isEqualTo("fp-it");
        assertThat(inst.path("nodeId").asText()).isEqualTo("node-1");
        assertThat(inst.path("platform").asText()).isEqualTo("kubernetes");
        assertThat(inst.path("appVersion").asText()).isEqualTo("2.5.0");
        assertThat(inst.path("memUsedMb").asInt()).isEqualTo(512);
        assertThat(inst.path("cpuPct").asInt()).isEqualTo(12);
        assertThat(inst.path("lastSeenAt").isTextual()).isTrue();

        assertThat(data(get(DEPLOYMENTS + "/org/" + UUID.randomUUID() + "/instances", token)).size()).isZero();
        assertThat(status(get(DEPLOYMENTS + "/org/" + orgId + "/instances", null))).isEqualTo(401);
    }

    // ── role gating ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a VIEWER reads releases and deployments but cannot publish, approve, push, patch or roll back")
    void viewerGating() throws Exception {
        String root = adminToken();
        String viewer = tokenFor(root, "VIEWER");
        String orgId = createOrg(root, "it-dep-roles");
        JsonNode rel = publish(root, "roles");
        String depId = pushTo(root, rel.path("id").asText(), orgId).path("deployments").get(0).path("id").asText();

        assertThat(status(get(RELEASES, viewer))).isEqualTo(200);
        assertThat(status(get(RELEASES + "/latest", viewer))).isEqualTo(200);
        assertThat(status(get(DEPLOYMENTS, viewer))).isEqualTo(200);
        assertThat(status(get(DEPLOYMENTS + "/stats", viewer))).isEqualTo(200);
        assertThat(status(get(DEPLOYMENTS + "/org/" + orgId, viewer))).isEqualTo(200);
        assertThat(status(get(DEPLOYMENTS + "/org/" + orgId + "/instances", viewer))).isEqualTo(200);

        MvcResult pub = post(RELEASES, viewer, releaseBody(version("viewer")));
        assertThat(status(pub)).isEqualTo(403);
        assertThat(code(pub)).isEqualTo("ACCESS_DENIED");
        assertThat(status(post(RELEASES + "/" + rel.path("id").asText() + "/approve", viewer, null))).isEqualTo(403);
        assertThat(status(post(RELEASES + "/" + rel.path("id").asText() + "/reject", viewer, null))).isEqualTo(403);
        assertThat(status(post(DEPLOYMENTS + "/push-update", viewer,
            Map.of("organizationIds", List.of(orgId), "releaseId", rel.path("id").asText())))).isEqualTo(403);
        assertThat(status(patch(DEPLOYMENTS + "/" + depId + "/status", viewer, Map.of("status", "SUCCESS")))).isEqualTo(403);
        assertThat(status(post(DEPLOYMENTS + "/" + depId + "/rollback", viewer, null))).isEqualTo(403);

        assertThat(byId(orgDeployments(root, orgId), depId).path("status").asText()).isEqualTo("PENDING");
        assertThat(data(get(RELEASES + "/" + rel.path("id").asText(), root)).path("approvalStatus").asText()).isEqualTo("APPROVED");

        // ADMIN suffices for all of them.
        String admin = tokenFor(root, "ADMIN");
        assertThat(status(post(RELEASES, admin, releaseBody(version("admin"))))).isEqualTo(200);
        assertThat(status(patch(DEPLOYMENTS + "/" + depId + "/status", admin, Map.of("status", "IN_PROGRESS")))).isEqualTo(200);
    }
}
