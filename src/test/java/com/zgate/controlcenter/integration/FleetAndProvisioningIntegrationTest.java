package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.domain.CloudCredential;
import com.zgate.controlcenter.domain.FleetRolloutItem;
import com.zgate.controlcenter.domain.InfrastructureStack;
import com.zgate.controlcenter.repository.CloudCredentialRepository;
import com.zgate.controlcenter.repository.FleetRolloutItemRepository;
import com.zgate.controlcenter.repository.InfrastructureStackRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fleet operations and cloud provisioning over HTTP. The Terraform runner, the state bucket and the
 * rollout orchestrator are all OFF in the harness, so every path that would touch a customer cloud is
 * asserted at its guard (503 PROVISIONING_UNAVAILABLE).
 *
 * <p>Every scenario that needs an {@code infrastructure_stacks} row is {@code @Disabled}: the entity
 * persists {@code Target} by enum name ({@code AWS_ECS}) while {@code ck_infra_stack_target} only
 * admits the slugs ({@code aws-ecs}), so no stack can be inserted through JPA at all — see
 * {@link #STACK_MAPPING_BUG}. Those tests carry the correct expectations for when that is fixed.
 */
class FleetAndProvisioningIntegrationTest extends AbstractIntegrationTest {

    private static final String FLEET = "/api/v1/fleet";
    private static final String ROLLOUTS = FLEET + "/rollouts";
    private static final String PROV = "/api/v1/provisioning";
    private static final String CREDS = PROV + "/credentials";
    private static final String STACKS = PROV + "/stacks";

    static final String STACK_MAPPING_BUG =
        "BUG: InfrastructureStack.target is @Enumerated(EnumType.STRING) (InfrastructureStack.java:42-44) so Hibernate "
      + "writes 'AWS_ECS', but ck_infra_stack_target (V21__provisioning.sql:118, widened in V22__provisioning_baremetal.sql:46-47) "
      + "only admits the slugs 'aws-ecs'|'aws-ec2'|'azure-aca'|'gcp-cloudrun'|'baremetal' — every save of a stack row fails "
      + "with DataIntegrityViolationException (observed: insert rejected by the check constraint; expected: row persisted). "
      + "This also means ProvisioningService.provision can never persist a stack in production.";

    static final String CREDENTIAL_REDACT_BUG =
        "BUG: CloudCredentialService.redact (CloudCredentialService.java:63-64) nulls secretCiphertext/secretKeyId on the "
      + "MANAGED entity, and create()/setEnabled() call it inside their @Transactional (lines 164 / 179), so the dirty-check "
      + "flushes an UPDATE with a null ciphertext that ck_cloud_cred_secret (V22__provisioning_baremetal.sql:34-43) "
      + "rejects — every secret-bearing credential (AWS_STATIC_KEYS, AZURE_SERVICE_PRINCIPAL, GCP_SERVICE_ACCOUNT, SSH_KEY) "
      + "is 500 INTERNAL_ERROR on create (observed 500, expected 200 with the ciphertext persisted and the response redacted)";

    @Autowired InfrastructureStackRepository stackRepo;
    @Autowired CloudCredentialRepository credRepo;
    @Autowired FleetRolloutItemRepository itemRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private JsonNode createOrg(String token) throws Exception {
        MvcResult r = post("/api/v1/organizations", token, Map.of(
            "name", uniqueSlug("Fleet Org"), "slug", uniqueSlug("fleet-org"),
            "tier", "ENTERPRISE", "deploymentEnv", "PRODUCTION"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r);
    }

    /** A fresh release; approvalStatus defaults to APPROVED. Never flagged latest, so seed data is untouched. */
    private JsonNode approvedRelease(String token) throws Exception {
        String version = "9.9." + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = post("/api/v1/releases", token, Map.of(
            "version", version, "channel", "STABLE", "dockerTag", "zgate/backend:" + version));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(data(r).path("approvalStatus").asText()).isEqualTo("APPROVED");
        return data(r);
    }

    /** Only reachable from the @Disabled tests until STACK_MAPPING_BUG is fixed. */
    private InfrastructureStack insertStack(UUID orgId, InfrastructureStack.Status status,
                                            boolean applied, String releaseVersion) {
        return stackRepo.save(InfrastructureStack.builder()
            .organizationId(orgId)
            .environment("prod")
            .target(InfrastructureStack.Target.AWS_ECS)
            .status(status)
            .specJson("{}")
            .releaseVersion(releaseVersion)
            .lastAppliedAt(applied ? LocalDateTime.now().minusDays(1) : null)
            .createdBy("integration-test")
            .build());
    }

    private Map<String, String> rolloutTicket(String admin) throws Exception {
        return stepUpHeaders(admin, ADMIN_PW, "POST " + ROLLOUTS);
    }

    private Map<String, String> credentialTicket(String admin) throws Exception {
        return stepUpHeaders(admin, ADMIN_PW, "POST " + CREDS);
    }

    private Map<String, Object> assumeRoleBody(String orgId) {
        return Map.of(
            "organizationId", orgId, "authMode", "AWS_ASSUME_ROLE", "displayName", "customer-role",
            "defaultRegion", "eu-west-2", "awsAccountId", "123456789012",
            "awsRoleArn", "arn:aws:iam::123456789012:role/zgate-provisioner");
    }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode n : array) if (id.equals(n.path("id").asText())) return true;
        return false;
    }

    // ── fleet reads ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("overview reports the harness posture (no service-key enforcement, no cost tracking) over the seeded fleet; SLA is per org and readable by any role")
    void overviewAndSla() throws Exception {
        String admin = adminToken();
        MvcResult r = get(FLEET + "/overview", admin);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        JsonNode o = data(r);
        assertThat(o.path("totalOrgs").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(o.path("serviceKeyEnforced").asBoolean()).isFalse();
        assertThat(o.path("costTrackingEnabled").asBoolean()).isFalse();
        assertThat(o.path("orgsByStatus").isObject()).isTrue();
        assertThat(o.path("orgsByStatus").path("HEALTHY").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(o.path("versionSpread").isObject()).isTrue();
        assertThat(o.path("latestRelease").asText()).isNotBlank();
        assertThat(o.path("stacks").isArray()).isTrue();
        assertThat(o.path("subscriptionsLapsingSoon").isArray()).isTrue();
        assertThat(o.path("backups").isArray()).isTrue();
        assertThat(o.path("margins").isArray()).isTrue();
        assertThat(o.path("liveRollouts").asLong()).isGreaterThanOrEqualTo(0);

        MvcResult sla = get(FLEET + "/sla", admin);
        assertThat(status(sla)).isEqualTo(200);
        assertThat(data(sla).isArray()).isTrue();
        assertThat(data(sla).size()).isGreaterThanOrEqualTo(6);
        for (JsonNode org : data(sla)) {
            assertThat(org.path("organizationId").asText()).isNotBlank();
            assertThat(org.has("tracked")).isTrue();
            assertThat(org.path("incidents").isArray()).isTrue();
            if (!org.path("tracked").asBoolean()) assertThat(org.path("uptimePct").isNull()).isTrue();
        }
        // The window is capped at 365 days; an absurd value is still a 200.
        assertThat(status(get(FLEET + "/sla?windowDays=99999", admin))).isEqualTo(200);
        assertThat(status(get(FLEET + "/sla?windowDays=7", admin))).isEqualTo(200);

        String viewer = tokenFor(admin, "VIEWER");
        assertThat(status(get(FLEET + "/overview", viewer))).isEqualTo(200);
        assertThat(status(get(FLEET + "/sla", viewer))).isEqualTo(200);
        assertThat(status(get(ROLLOUTS, viewer))).isEqualTo(200);
    }

    @Test
    @DisplayName("rollout list is an array; an unknown rollout is 404 ROLLOUT_NOT_FOUND on read and on every control action")
    void rolloutListAndNotFound() throws Exception {
        String admin = adminToken();
        MvcResult list = get(ROLLOUTS, admin);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).isArray()).isTrue();

        String random = UUID.randomUUID().toString();
        MvcResult missing = get(ROLLOUTS + "/" + random, admin);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("ROLLOUT_NOT_FOUND");
        for (String action : new String[] {"approve", "pause", "resume", "cancel"}) {
            MvcResult r = post(ROLLOUTS + "/" + random + "/" + action, admin, null);
            assertThat(status(r)).as(action).isEqualTo(404);
            assertThat(code(r)).as(action).isEqualTo("ROLLOUT_NOT_FOUND");
        }
        String support = tokenFor(admin, "SUPPORT");
        assertThat(status(get(ROLLOUTS + "/" + random, support))).as("read is open").isEqualTo(404);
        assertThat(code(post(ROLLOUTS + "/" + random + "/pause", support, null))).isEqualTo("ACCESS_DENIED");
    }

    // ── rollouts ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("creating a rollout needs the role, then a single-use step-up ticket bound to POST /api/v1/fleet/rollouts; release and stack lookups are validated in that order")
    void rolloutCreateGates() throws Exception {
        String admin = adminToken();
        JsonNode release = approvedRelease(admin);
        String releaseId = release.path("id").asText();
        Map<String, Object> body = Map.of("releaseId", releaseId, "stackIds", List.of(UUID.randomUUID().toString()));

        MvcResult viewer = post(ROLLOUTS, tokenFor(admin, "VIEWER"), body);
        assertThat(status(viewer)).isEqualTo(403);
        assertThat(code(viewer)).as("role check before the step-up prompt").isEqualTo("ACCESS_DENIED");

        MvcResult noTicket = post(ROLLOUTS, admin, body);
        assertThat(status(noTicket)).isEqualTo(403);
        assertThat(code(noTicket)).isEqualTo("STEP_UP_REQUIRED");

        MvcResult otherAction = postWith(ROLLOUTS, admin, body, stepUpHeaders(admin, ADMIN_PW, "POST " + CREDS));
        assertThat(code(otherAction)).as("ticket bound to a different action").isEqualTo("STEP_UP_REQUIRED");

        // Every call below consumes a ticket — including the ones refused by validation.
        MvcResult noRelease = postWith(ROLLOUTS, admin, Map.of("stackIds", List.of()), rolloutTicket(admin));
        assertThat(status(noRelease)).isEqualTo(400);
        assertThat(code(noRelease)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(noRelease).path("fieldErrors").path("releaseId").asText()).isEqualTo("releaseId is required");

        MvcResult badRelease = postWith(ROLLOUTS, admin,
            Map.of("releaseId", UUID.randomUUID().toString()), rolloutTicket(admin));
        assertThat(status(badRelease)).isEqualTo(404);
        assertThat(code(badRelease)).isEqualTo("RELEASE_NOT_FOUND");

        MvcResult badStack = postWith(ROLLOUTS, admin, body, rolloutTicket(admin));
        assertThat(status(badStack)).as(text(badStack)).isEqualTo(404);
        assertThat(code(badStack)).isEqualTo("STACK_NOT_FOUND");

        // The release's approval is checked before any stack is resolved.
        JsonNode rejected = approvedRelease(admin);
        assertThat(status(post("/api/v1/releases/" + rejected.path("id").asText() + "/reject", admin, null))).isEqualTo(200);
        MvcResult notApproved = postWith(ROLLOUTS, admin,
            Map.of("releaseId", rejected.path("id").asText(), "stackIds", List.of(UUID.randomUUID().toString())),
            rolloutTicket(admin));
        assertThat(status(notApproved)).isEqualTo(409);
        assertThat(code(notApproved)).isEqualTo("RELEASE_NOT_APPROVED");

        // With no stack in the fleet at all, "every upgradable stack" is nothing. (Guarded: once
        // STACK_MAPPING_BUG is fixed, other tests may leave applied stacks behind.)
        if (data(get(STACKS, admin)).isEmpty()) {
            MvcResult nothing = postWith(ROLLOUTS, admin, Map.of("releaseId", releaseId), rolloutTicket(admin));
            assertThat(status(nothing)).as(text(nothing)).isEqualTo(409);
            assertThat(code(nothing)).isEqualTo("ROLLOUT_NOTHING_TO_DO");
        }
        assertThat(data(get(FLEET + "/overview", admin)).path("liveRollouts").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("an unapplied stack, or one already on the release, is 409 ROLLOUT_NOTHING_TO_DO")
    void rolloutNothingToDoOnUnappliedOrCurrentStacks() throws Exception {
        String admin = adminToken();
        JsonNode release = approvedRelease(admin);
        String releaseId = release.path("id").asText();

        InfrastructureStack draft = insertStack(UUID.fromString(createOrg(admin).path("id").asText()),
            InfrastructureStack.Status.DRAFT, false, null);
        MvcResult unapplied = postWith(ROLLOUTS, admin,
            Map.of("releaseId", releaseId, "stackIds", List.of(draft.getId().toString())), rolloutTicket(admin));
        assertThat(status(unapplied)).as(text(unapplied)).isEqualTo(409);
        assertThat(code(unapplied)).isEqualTo("ROLLOUT_NOTHING_TO_DO");

        InfrastructureStack onRelease = insertStack(UUID.fromString(createOrg(admin).path("id").asText()),
            InfrastructureStack.Status.ACTIVE, true, release.path("version").asText());
        MvcResult already = postWith(ROLLOUTS, admin,
            Map.of("releaseId", releaseId, "stackIds", List.of(onRelease.getId().toString())), rolloutTicket(admin));
        assertThat(code(already)).isEqualTo("ROLLOUT_NOTHING_TO_DO");
    }

    @Test
    @DisplayName("an applied stack on an older version gets a rollout with one wave-0 item; pause/resume/cancel drive the state machine and refuse the wrong transitions")
    void rolloutLifecycle() throws Exception {
        String admin = adminToken();
        JsonNode release = approvedRelease(admin);
        String releaseId = release.path("id").asText();
        String target = release.path("version").asText();
        UUID orgId = UUID.fromString(createOrg(admin).path("id").asText());
        InfrastructureStack stack = insertStack(orgId, InfrastructureStack.Status.ACTIVE, true, "0.0.1");
        String stackId = stack.getId().toString();

        MvcResult created = postWith(ROLLOUTS, admin, Map.of(
            "releaseId", releaseId, "stackIds", List.of(stackId),
            "canarySize", 1, "waveSize", 5, "autoApply", false, "soakMinutes", 0), rolloutTicket(admin));
        assertThat(status(created)).as(text(created)).isEqualTo(200);
        assertThat(code(created)).isEqualTo("ROLLOUT_CREATED");
        JsonNode r = data(created);
        String id = r.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(r.path("status").asText()).isEqualTo("PENDING");
        assertThat(r.path("approvalStatus").asText()).as("requireApproval is off").isEqualTo("APPROVED");
        assertThat(r.path("releaseId").asText()).isEqualTo(releaseId);
        assertThat(r.path("releaseVersion").asText()).isEqualTo(target);
        assertThat(r.path("createdBy").asText()).isEqualTo(ADMIN);
        assertThat(r.path("canarySize").asInt()).isEqualTo(1);
        assertThat(r.path("waveSize").asInt()).isEqualTo(5);
        assertThat(r.path("autoApply").asBoolean()).isFalse();
        assertThat(r.path("soakMinutes").asInt()).isZero();
        assertThat(r.path("currentWave").asInt()).isZero();
        assertThat(r.path("terminal").asBoolean()).isFalse();
        AuditLog audit = awaitAudit(a -> "FLEET_ROLLOUT_CREATED".equals(a.getAction()) && id.equals(a.getEntityId()));
        assertThat(audit.getDetails()).contains("release=" + target);

        MvcResult detail = get(ROLLOUTS + "/" + id, admin);
        assertThat(status(detail)).isEqualTo(200);
        assertThat(data(detail).path("rollout").path("id").asText()).isEqualTo(id);
        JsonNode items = data(detail).path("items");
        assertThat(items.size()).isEqualTo(1);
        JsonNode item = items.get(0);
        assertThat(item.path("rolloutId").asText()).isEqualTo(id);
        assertThat(item.path("stackId").asText()).isEqualTo(stackId);
        assertThat(item.path("organizationId").asText()).isEqualTo(orgId.toString());
        assertThat(item.path("wave").asInt()).isZero();
        assertThat(item.path("status").asText()).isEqualTo("PENDING");
        assertThat(item.path("fromVersion").asText()).isEqualTo("0.0.1");
        assertThat(item.path("toVersion").asText()).isEqualTo(target);
        assertThat(containsId(data(get(ROLLOUTS, admin)), id)).isTrue();
        assertThat(data(get(FLEET + "/overview", admin)).path("liveRollouts").asLong()).isGreaterThanOrEqualTo(1);

        // Wrong transitions from PENDING.
        MvcResult resumePending = post(ROLLOUTS + "/" + id + "/resume", admin, null);
        assertThat(status(resumePending)).isEqualTo(409);
        assertThat(code(resumePending)).isEqualTo("ROLLOUT_NOT_PAUSED");
        MvcResult approve = post(ROLLOUTS + "/" + id + "/approve", admin, null);
        assertThat(status(approve)).isEqualTo(409);
        assertThat(code(approve)).isEqualTo("ROLLOUT_NOT_PENDING_APPROVAL");

        // The stack is now inside a live rollout: a second rollout over it is refused.
        MvcResult second = postWith(ROLLOUTS, admin,
            Map.of("releaseId", releaseId, "stackIds", List.of(stackId)), rolloutTicket(admin));
        assertThat(status(second)).isEqualTo(409);
        assertThat(code(second)).isEqualTo("STACK_IN_LIVE_ROLLOUT");

        MvcResult paused = post(ROLLOUTS + "/" + id + "/pause", admin, Map.of("reason", "hold for change window"));
        assertThat(status(paused)).as(text(paused)).isEqualTo(200);
        assertThat(code(paused)).isEqualTo("ROLLOUT_PAUSED");
        assertThat(data(paused).path("status").asText()).isEqualTo("PAUSED");
        assertThat(data(paused).path("statusReason").asText()).isEqualTo("hold for change window");
        awaitAudit(a -> "FLEET_ROLLOUT_PAUSED".equals(a.getAction()) && id.equals(a.getEntityId()));

        // Resume moves to IN_PROGRESS (not back to PENDING) and clears the reason.
        MvcResult resumed = post(ROLLOUTS + "/" + id + "/resume", admin, null);
        assertThat(status(resumed)).isEqualTo(200);
        assertThat(code(resumed)).isEqualTo("ROLLOUT_RESUMED");
        assertThat(data(resumed).path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(data(resumed).path("statusReason").isNull()).isTrue();
        assertThat(code(post(ROLLOUTS + "/" + id + "/resume", admin, null))).isEqualTo("ROLLOUT_NOT_PAUSED");

        // Pause with no body derives a reason from the actor.
        MvcResult pausedAgain = post(ROLLOUTS + "/" + id + "/pause", admin, null);
        assertThat(data(pausedAgain).path("status").asText()).isEqualTo("PAUSED");
        assertThat(data(pausedAgain).path("statusReason").asText()).isEqualTo("Paused by " + ADMIN);

        MvcResult cancelled = post(ROLLOUTS + "/" + id + "/cancel", admin, null);
        assertThat(status(cancelled)).isEqualTo(200);
        assertThat(code(cancelled)).isEqualTo("ROLLOUT_CANCELLED");
        assertThat(data(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(data(cancelled).path("statusReason").asText()).isEqualTo("Cancelled by " + ADMIN);
        assertThat(data(cancelled).path("completedAt").isNull()).isFalse();
        assertThat(data(cancelled).path("terminal").asBoolean()).isTrue();
        List<FleetRolloutItem> after = itemRepo.findByRolloutIdOrderByWaveAscCreatedAtAsc(UUID.fromString(id));
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getStatus()).as("PENDING items are SKIPPED on cancel").isEqualTo(FleetRolloutItem.Status.SKIPPED);
        assertThat(after.get(0).getErrorMessage()).contains("cancelled");
        awaitAudit(a -> "FLEET_ROLLOUT_CANCELLED".equals(a.getAction()) && id.equals(a.getEntityId()));

        // Terminal: nothing more is allowed.
        assertThat(code(post(ROLLOUTS + "/" + id + "/pause", admin, null))).isEqualTo("ROLLOUT_FINISHED");
        assertThat(code(post(ROLLOUTS + "/" + id + "/cancel", admin, null))).isEqualTo("ROLLOUT_FINISHED");
        assertThat(code(post(ROLLOUTS + "/" + id + "/resume", admin, null))).isEqualTo("ROLLOUT_NOT_PAUSED");
        // …and the stack is free for a new rollout.
        MvcResult third = postWith(ROLLOUTS, admin,
            Map.of("releaseId", releaseId, "stackIds", List.of(stackId)), rolloutTicket(admin));
        assertThat(status(third)).as(text(third)).isEqualTo(200);
        post(ROLLOUTS + "/" + data(third).path("id").asText() + "/cancel", admin, null);

        // Control actions are SUPER_ADMIN/ADMIN only.
        String support = tokenFor(admin, "SUPPORT");
        assertThat(status(get(ROLLOUTS + "/" + id, support))).isEqualTo(200);
        assertThat(code(post(ROLLOUTS + "/" + id + "/pause", support, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(ROLLOUTS + "/" + id + "/cancel", support, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(ROLLOUTS + "/" + id + "/approve", support, null))).isEqualTo("ACCESS_DENIED");
    }

    // ── provisioning: readiness + credentials ───────────────────────────────

    @Test
    @DisplayName("readiness says exactly why provisioning is unavailable; SUPPORT may read it, VIEWER may not")
    void readiness() throws Exception {
        String admin = adminToken();
        MvcResult r = get(PROV + "/readiness", admin);
        assertThat(status(r)).isEqualTo(200);
        assertThat(data(r).path("available").asBoolean()).isFalse();
        assertThat(data(r).path("runnerReason").asText()).contains("Provisioning is disabled");
        assertThat(data(r).path("stateConfigured").asBoolean()).isFalse();
        assertThat(data(r).path("stateReason").asText()).contains("controlcenter.provisioning.state.bucket");

        assertThat(status(get(PROV + "/readiness", tokenFor(admin, "SUPPORT")))).isEqualTo(200);
        MvcResult viewer = get(PROV + "/readiness", tokenFor(admin, "VIEWER"));
        assertThat(status(viewer)).isEqualTo(403);
        assertThat(code(viewer)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("cloud credentials: SUPER_ADMIN + step-up to create; assume-role stores no secret and mints an external id; per-mode fields are validated; list/toggle/delete")
    void credentialsLifecycle() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin).path("id").asText();
        Map<String, Object> assumeRole = assumeRoleBody(orgId);

        MvcResult asAdminRole = post(CREDS, tokenFor(admin, "ADMIN"), assumeRole);
        assertThat(status(asAdminRole)).isEqualTo(403);
        assertThat(code(asAdminRole)).as("SUPER_ADMIN only, before any step-up").isEqualTo("ACCESS_DENIED");
        MvcResult noTicket = post(CREDS, admin, assumeRole);
        assertThat(status(noTicket)).isEqualTo(403);
        assertThat(code(noTicket)).isEqualTo("STEP_UP_REQUIRED");

        MvcResult role = postWith(CREDS, admin, assumeRole, credentialTicket(admin));
        assertThat(status(role)).as(text(role)).isEqualTo(200);
        assertThat(code(role)).isEqualTo("CLOUD_CREDENTIAL_CREATED");
        JsonNode c1 = data(role);
        String roleId = c1.path("id").asText();
        assertThat(c1.path("organizationId").asText()).isEqualTo(orgId);
        assertThat(c1.path("provider").asText()).isEqualTo("aws");
        assertThat(c1.path("authMode").asText()).isEqualTo("AWS_ASSUME_ROLE");
        assertThat(c1.path("awsRoleArn").asText()).isEqualTo("arn:aws:iam::123456789012:role/zgate-provisioner");
        assertThat(c1.path("awsAccountId").asText()).isEqualTo("123456789012");
        assertThat(c1.path("awsExternalId").asText()).as("generated, shown once").startsWith("zgate-");
        assertThat(c1.path("secretless").asBoolean()).isTrue();
        assertThat(c1.path("enabled").asBoolean()).isTrue();
        assertThat(c1.path("createdBy").asText()).isEqualTo(ADMIN);
        assertThat(c1.path("secretCiphertext").isMissingNode()).isTrue();
        assertThat(c1.path("secretKeyId").isMissingNode()).isTrue();
        AuditLog created = awaitAudit(a -> "CLOUD_CREDENTIAL_CREATED".equals(a.getAction()) && roleId.equals(a.getEntityId()));
        assertThat(created.getDetails()).contains("provider=aws").contains("mode=AWS_ASSUME_ROLE");
        assertThat(created.getOrganizationId()).isEqualTo(UUID.fromString(orgId));
        CloudCredential stored = credRepo.findById(UUID.fromString(roleId)).orElseThrow();
        assertThat(stored.getSecretCiphertext()).isNull();
        assertThat(stored.getAwsExternalId()).isEqualTo(c1.path("awsExternalId").asText());

        // A caller-supplied external id is honoured.
        MvcResult pinned = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "AWS_ASSUME_ROLE", "displayName", "pinned",
            "awsRoleArn", "arn:aws:iam::123456789012:role/other", "awsExternalId", "customer-chosen-id"),
            credentialTicket(admin));
        assertThat(status(pinned)).isEqualTo(200);
        assertThat(data(pinned).path("awsExternalId").asText()).isEqualTo("customer-chosen-id");
        String pinnedId = data(pinned).path("id").asText();

        // Per-mode required fields are checked before anything is written.
        MvcResult noSecret = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "AWS_STATIC_KEYS", "displayName", "x",
            "awsAccessKeyId", "AKIAIOSFODNN7EXAMPLE"), credentialTicket(admin));
        assertThat(status(noSecret)).isEqualTo(400);
        assertThat(code(noSecret)).isEqualTo("CLOUD_CREDENTIAL_FIELD_REQUIRED");
        assertThat(body(noSecret).path("message").asText()).contains("secret");
        MvcResult noAccessKey = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "AWS_STATIC_KEYS", "displayName", "x", "secret", "s"), credentialTicket(admin));
        assertThat(code(noAccessKey)).isEqualTo("CLOUD_CREDENTIAL_FIELD_REQUIRED");
        assertThat(body(noAccessKey).path("message").asText()).contains("awsAccessKeyId");
        MvcResult noArn = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "AWS_ASSUME_ROLE", "displayName", "x"), credentialTicket(admin));
        assertThat(code(noArn)).isEqualTo("CLOUD_CREDENTIAL_FIELD_REQUIRED");
        assertThat(body(noArn).path("message").asText()).contains("awsRoleArn");
        MvcResult noTenant = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "AZURE_SERVICE_PRINCIPAL", "displayName", "x",
            "azureSubscriptionId", "sub", "azureClientId", "c", "secret", "s"), credentialTicket(admin));
        assertThat(code(noTenant)).isEqualTo("CLOUD_CREDENTIAL_FIELD_REQUIRED");
        assertThat(body(noTenant).path("message").asText()).contains("azureTenantId");
        MvcResult noProject = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "GCP_SERVICE_ACCOUNT", "displayName", "x", "secret", "{}"), credentialTicket(admin));
        assertThat(code(noProject)).isEqualTo("CLOUD_CREDENTIAL_FIELD_REQUIRED");
        assertThat(body(noProject).path("message").asText()).contains("gcpProjectId");
        MvcResult noHost = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "SSH_KEY", "displayName", "x", "sshUser", "u", "secret", "k"), credentialTicket(admin));
        assertThat(code(noHost)).isEqualTo("CLOUD_CREDENTIAL_FIELD_REQUIRED");
        assertThat(body(noHost).path("message").asText()).contains("sshHost");
        MvcResult encryptedSsh = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "SSH_KEY", "displayName", "x", "sshHost", "h", "sshUser", "u",
            "secret", "-----BEGIN OPENSSH PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n-----END OPENSSH PRIVATE KEY-----"),
            credentialTicket(admin));
        assertThat(status(encryptedSsh)).isEqualTo(400);
        assertThat(code(encryptedSsh)).isEqualTo("SSH_KEY_ENCRYPTED");
        MvcResult notPem = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "SSH_KEY", "displayName", "x", "sshHost", "h", "sshUser", "u",
            "secret", "not a key"), credentialTicket(admin));
        assertThat(code(notPem)).isEqualTo("SSH_KEY_MALFORMED");
        MvcResult badMode = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "PASSWORD", "displayName", "x"), credentialTicket(admin));
        assertThat(status(badMode)).isEqualTo(400);
        assertThat(code(badMode)).isEqualTo("INVALID_ENUM_VALUE");
        assertThat(body(badMode).path("message").asText()).contains("AWS_ASSUME_ROLE");
        MvcResult noOrg = postWith(CREDS, admin, Map.of(
            "organizationId", UUID.randomUUID().toString(), "authMode", "AWS_ASSUME_ROLE",
            "displayName", "x", "awsRoleArn", "arn:aws:iam::1:role/r"), credentialTicket(admin));
        assertThat(status(noOrg)).isEqualTo(404);
        assertThat(code(noOrg)).isEqualTo("ORGANIZATION_NOT_FOUND");

        // List by org: exactly the two that were accepted.
        MvcResult list = get(CREDS + "/org/" + orgId, admin);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).size()).isEqualTo(2);
        assertThat(containsId(data(list), roleId)).isTrue();
        assertThat(containsId(data(list), pinnedId)).isTrue();
        for (JsonNode c : data(list)) assertThat(c.path("secretCiphertext").isMissingNode()).isTrue();
        assertThat(data(get(CREDS + "/org/" + UUID.randomUUID(), admin)).size()).isZero();
        assertThat(status(get(CREDS + "/org/" + orgId, tokenFor(admin, "SUPPORT")))).isEqualTo(200);
        assertThat(code(get(CREDS + "/org/" + orgId, tokenFor(admin, "VIEWER")))).isEqualTo("ACCESS_DENIED");

        MvcResult disabled = patch(CREDS + "/" + roleId + "/enabled", admin, Map.of("enabled", false));
        assertThat(status(disabled)).as(text(disabled)).isEqualTo(200);
        assertThat(code(disabled)).isEqualTo("CLOUD_CREDENTIAL_UPDATED");
        assertThat(data(disabled).path("enabled").asBoolean()).isFalse();
        assertThat(credRepo.findById(UUID.fromString(roleId)).orElseThrow().isEnabled()).isFalse();
        MvcResult reEnabled = patch(CREDS + "/" + roleId + "/enabled", admin, Map.of("enabled", true));
        assertThat(data(reEnabled).path("enabled").asBoolean()).isTrue();
        assertThat(code(patch(CREDS + "/" + roleId + "/enabled", tokenFor(admin, "ADMIN"), Map.of("enabled", true)))).isEqualTo("ACCESS_DENIED");
        assertThat(status(patch(CREDS + "/" + UUID.randomUUID() + "/enabled", admin, Map.of("enabled", true)))).isEqualTo(404);

        // Actual behaviour: the SSH probe checks controlcenter.provisioning.enabled BEFORE the
        // credential type (SshReachabilityService.check:56-63), so with provisioning off a non-SSH
        // credential is 503 PROVISIONING_UNAVAILABLE rather than 400 CREDENTIAL_NOT_SSH.
        MvcResult ssh = post(CREDS + "/" + roleId + "/ssh-check", admin, null);
        assertThat(status(ssh)).isEqualTo(503);
        assertThat(code(ssh)).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(code(post(CREDS + "/" + roleId + "/ssh-check", tokenFor(admin, "SUPPORT"), null))).isEqualTo("ACCESS_DENIED");

        MvcResult del = delete(CREDS + "/" + pinnedId, admin);
        assertThat(status(del)).isEqualTo(200);
        assertThat(credRepo.findById(UUID.fromString(pinnedId))).isEmpty();
        assertThat(data(get(CREDS + "/org/" + orgId, admin)).size()).isEqualTo(1);
        awaitAudit(a -> "CLOUD_CREDENTIAL_DELETED".equals(a.getAction()) && pinnedId.equals(a.getEntityId()));
        MvcResult delAgain = delete(CREDS + "/" + pinnedId, admin);
        assertThat(status(delAgain)).isEqualTo(404);
        assertThat(code(delAgain)).isEqualTo("CLOUD_CREDENTIAL_NOT_FOUND");
        assertThat(code(delete(CREDS + "/" + roleId, tokenFor(admin, "ADMIN")))).isEqualTo("ACCESS_DENIED");
        assertThat(credRepo.findById(UUID.fromString(roleId))).isPresent();
    }

    @Test
    @DisplayName("a static-key credential is accepted, encrypted at rest, and its secret never appears in any response")
    void secretBearingCredentialIsStoredEncrypted() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin).path("id").asText();
        String secret = "wJalrXUtnFEMI/K7MDENG/" + uniqueSlug("EXAMPLEKEY");

        MvcResult keys = postWith(CREDS, admin, Map.of(
            "organizationId", orgId, "authMode", "AWS_STATIC_KEYS", "displayName", "customer-keys",
            "awsAccountId", "123456789012", "awsAccessKeyId", "AKIAIOSFODNN7EXAMPLE", "secret", secret),
            credentialTicket(admin));
        assertThat(status(keys)).as(text(keys)).isEqualTo(200);
        assertThat(code(keys)).isEqualTo("CLOUD_CREDENTIAL_CREATED");
        JsonNode c = data(keys);
        String id = c.path("id").asText();
        assertThat(c.path("authMode").asText()).isEqualTo("AWS_STATIC_KEYS");
        assertThat(c.path("awsAccessKeyId").asText()).isEqualTo("AKIAIOSFODNN7EXAMPLE");
        assertThat(c.path("secretless").asBoolean()).isFalse();
        assertThat(c.path("secretCiphertext").isMissingNode()).as("secret fields are never serialised").isTrue();
        assertThat(c.path("secretKeyId").isMissingNode()).isTrue();
        assertThat(text(keys)).doesNotContain(secret);

        CloudCredential stored = credRepo.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getSecretCiphertext()).isNotBlank().doesNotContain(secret);
        assertThat(stored.getSecretKeyId()).isEqualTo("v1");

        MvcResult list = get(CREDS + "/org/" + orgId, admin);
        assertThat(containsId(data(list), id)).isTrue();
        assertThat(text(list)).doesNotContain(secret);
        for (JsonNode n : data(list)) assertThat(n.path("secretCiphertext").isMissingNode()).isTrue();

        // setEnabled has the same redact-inside-transaction shape and must keep the ciphertext.
        MvcResult disabled = patch(CREDS + "/" + id + "/enabled", admin, Map.of("enabled", false));
        assertThat(status(disabled)).as(text(disabled)).isEqualTo(200);
        assertThat(credRepo.findById(UUID.fromString(id)).orElseThrow().getSecretCiphertext()).isNotBlank();
    }

    // ── provisioning: stacks + runs ─────────────────────────────────────────

    @Test
    @DisplayName("stack reads without rows: fleet list, an org with no stacks, unknown/malformed ids, an empty runs page and an unknown run")
    void stackReadsWithoutRows() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin).path("id").asText();

        MvcResult all = get(STACKS, admin);
        assertThat(status(all)).isEqualTo(200);
        assertThat(data(all).isArray()).isTrue();

        MvcResult byOrg = get(STACKS + "/org/" + orgId, admin);
        assertThat(status(byOrg)).isEqualTo(200);
        assertThat(data(byOrg).size()).isZero();

        MvcResult missing = get(STACKS + "/" + UUID.randomUUID(), admin);
        assertThat(status(missing)).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("STACK_NOT_FOUND");
        MvcResult malformed = get(STACKS + "/not-a-uuid", admin);
        assertThat(status(malformed)).isEqualTo(400);
        assertThat(code(malformed)).isEqualTo("INVALID_PARAMETER");

        // Runs are a plain page over the stack id — no existence check, so an unknown stack is empty.
        MvcResult runs = get(STACKS + "/" + UUID.randomUUID() + "/runs?page=0&size=20", admin);
        assertThat(status(runs)).isEqualTo(200);
        assertThat(data(runs).path("content").isArray()).isTrue();
        assertThat(data(runs).path("content").size()).isZero();
        assertThat(data(runs).path("totalElements").asLong()).isZero();
        MvcResult run = get(PROV + "/runs/" + UUID.randomUUID(), admin);
        assertThat(status(run)).isEqualTo(404);
        assertThat(code(run)).isEqualTo("PROVISIONING_RUN_NOT_FOUND");

        assertThat(status(get(STACKS, tokenFor(admin, "SUPPORT")))).isEqualTo(200);
        MvcResult viewer = get(STACKS, tokenFor(admin, "VIEWER"));
        assertThat(status(viewer)).isEqualTo(403);
        assertThat(code(viewer)).isEqualTo("ACCESS_DENIED");
        assertThat(code(get(STACKS + "/org/" + orgId, tokenFor(admin, "VIEWER")))).isEqualTo("ACCESS_DENIED");
        assertThat(code(get(PROV + "/runs/" + UUID.randomUUID(), tokenFor(admin, "VIEWER")))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("a stack row is readable by org and by id, and mirrored in the fleet overview")
    void stackReadsWithARealRow() throws Exception {
        String admin = adminToken();
        UUID orgId = UUID.fromString(createOrg(admin).path("id").asText());
        InfrastructureStack stack = insertStack(orgId, InfrastructureStack.Status.ACTIVE, true, "2.5.0");
        String id = stack.getId().toString();

        assertThat(containsId(data(get(STACKS, admin)), id)).isTrue();
        MvcResult byOrg = get(STACKS + "/org/" + orgId, admin);
        assertThat(data(byOrg).size()).isEqualTo(1);
        assertThat(data(byOrg).get(0).path("id").asText()).isEqualTo(id);

        MvcResult one = get(STACKS + "/" + id, admin);
        assertThat(status(one)).isEqualTo(200);
        assertThat(data(one).path("organizationId").asText()).isEqualTo(orgId.toString());
        assertThat(data(one).path("environment").asText()).isEqualTo("prod");
        assertThat(data(one).path("target").asText()).isEqualTo("AWS_ECS");
        assertThat(data(one).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data(one).path("releaseVersion").asText()).isEqualTo("2.5.0");
        assertThat(data(one).path("busy").asBoolean()).isFalse();
        assertThat(data(one).path("driftDetected").asBoolean()).isFalse();

        boolean seen = false;
        for (JsonNode s : data(get(FLEET + "/overview", admin)).path("stacks")) {
            if (id.equals(s.path("stackId").asText())) {
                seen = true;
                assertThat(s.path("target").asText()).isEqualTo("aws-ecs");
                assertThat(s.path("status").asText()).isEqualTo("ACTIVE");
                assertThat(s.path("organizationId").asText()).isEqualTo(orgId.toString());
            }
        }
        assertThat(seen).isTrue();
        assertThat(data(get(STACKS + "/" + id + "/runs", admin)).path("totalElements").asLong()).isZero();
        assertThat(status(get(STACKS + "/" + id, tokenFor(admin, "SUPPORT")))).isEqualTo(200);
    }

    @Test
    @DisplayName("with the runner off: provision is 503 PROVISIONING_UNAVAILABLE after bean validation; apply/upgrade look the stack up first, refresh hits the gate first; destroy is role → step-up → validation → lookup")
    void mutationsUnavailableWithoutRunner() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin).path("id").asText();

        // requireReady() runs before the org / credential lookups, so an unknown credential is
        // never reached — the answer is the runner's, not the request's.
        MvcResult provision = post(PROV + "/provision", admin, Map.of(
            "organizationId", orgId, "target", "aws-ecs", "cloudCredentialId", UUID.randomUUID().toString()));
        assertThat(status(provision)).as(text(provision)).isEqualTo(503);
        assertThat(code(provision)).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(body(provision).path("message").asText()).contains("Provisioning is disabled");
        assertThat(data(get(STACKS + "/org/" + orgId, admin)).size()).as("nothing written").isZero();

        // Bean validation still runs first.
        MvcResult badTarget = post(PROV + "/provision", admin, Map.of(
            "organizationId", orgId, "target", "heroku", "cloudCredentialId", UUID.randomUUID().toString()));
        assertThat(status(badTarget)).isEqualTo(400);
        assertThat(code(badTarget)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(badTarget).path("fieldErrors").has("target")).isTrue();
        MvcResult missingFields = post(PROV + "/provision", admin, Map.of("target", "aws-ecs"));
        assertThat(code(missingFields)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(missingFields).path("fieldErrors").has("organizationId")).isTrue();
        assertThat(body(missingFields).path("fieldErrors").has("cloudCredentialId")).isTrue();
        MvcResult tooSmall = post(PROV + "/provision", admin, Map.of(
            "organizationId", orgId, "target", "aws-ecs", "cloudCredentialId", UUID.randomUUID().toString(),
            "databaseStorageGb", 5, "logRetentionDays", 1, "environment", "qa"));
        assertThat(code(tooSmall)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(tooSmall).path("fieldErrors").has("databaseStorageGb")).isTrue();
        assertThat(body(tooSmall).path("fieldErrors").has("logRetentionDays")).isTrue();
        assertThat(body(tooSmall).path("fieldErrors").has("environment")).isTrue();
        // Nested externalDatabase/externalCache/existingNetwork constraints: see
        // nestedProvisionBlocksAreValidated (currently not cascaded).

        String random = UUID.randomUUID().toString();
        // apply/upgrade resolve the stack in the controller before the service's runner gate…
        assertThat(code(post(STACKS + "/" + random + "/apply", admin, null))).isEqualTo("STACK_NOT_FOUND");
        assertThat(code(post(STACKS + "/" + random + "/upgrade", admin, null))).isEqualTo("STACK_NOT_FOUND");
        assertThat(code(post(STACKS + "/" + random + "/upgrade", admin, Map.of("releaseId", random)))).isEqualTo("STACK_NOT_FOUND");
        // …refresh goes straight to the service, where requireReady() answers first.
        MvcResult refresh = post(STACKS + "/" + random + "/refresh", admin, null);
        assertThat(status(refresh)).isEqualTo(503);
        assertThat(code(refresh)).isEqualTo("PROVISIONING_UNAVAILABLE");

        // Destroy: role → step-up → body validation → stack lookup.
        String destroyPath = STACKS + "/" + random + "/destroy";
        MvcResult asAdminRole = post(destroyPath, tokenFor(admin, "ADMIN"), Map.of("confirmation", "x"));
        assertThat(status(asAdminRole)).isEqualTo(403);
        assertThat(code(asAdminRole)).isEqualTo("ACCESS_DENIED");
        MvcResult noTicket = post(destroyPath, admin, Map.of("confirmation", "x"));
        assertThat(status(noTicket)).isEqualTo(403);
        assertThat(code(noTicket)).isEqualTo("STEP_UP_REQUIRED");
        MvcResult blank = postWith(destroyPath, admin, Map.of("confirmation", ""),
            stepUpHeaders(admin, ADMIN_PW, "POST " + destroyPath));
        assertThat(status(blank)).isEqualTo(400);
        assertThat(code(blank)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(blank).path("fieldErrors").path("confirmation").asText()).isEqualTo("confirmation is required");
        MvcResult unknown = postWith(destroyPath, admin, Map.of("confirmation", "x"),
            stepUpHeaders(admin, ADMIN_PW, "POST " + destroyPath));
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("STACK_NOT_FOUND");

        String support = tokenFor(admin, "SUPPORT");
        assertThat(code(post(PROV + "/provision", support, Map.of(
            "organizationId", orgId, "target", "aws-ecs", "cloudCredentialId", random)))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(STACKS + "/" + random + "/apply", support, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(STACKS + "/" + random + "/refresh", support, null))).isEqualTo("ACCESS_DENIED");
        assertThat(code(post(STACKS + "/" + random + "/upgrade", support, null))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("an external database block without a host is refused by bean validation")
    void nestedProvisionBlocksAreValidated() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin).path("id").asText();
        MvcResult nestedInvalid = post(PROV + "/provision", admin, Map.of(
            "organizationId", orgId, "target", "aws-ecs", "cloudCredentialId", UUID.randomUUID().toString(),
            "databaseMode", "external", "externalDatabase", Map.of("port", 5432)));
        assertThat(status(nestedInvalid)).isEqualTo(400);
        assertThat(code(nestedInvalid)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(nestedInvalid).path("fieldErrors").has("externalDatabase.host")).isTrue();
    }

    @Test
    @DisplayName("on a real stack, apply/refresh/upgrade are 503 PROVISIONING_UNAVAILABLE and destroy's runner gate precedes the slug confirmation")
    void stackActionsOnARealStackAre503() throws Exception {
        String admin = adminToken();
        String orgId = createOrg(admin).path("id").asText();
        InfrastructureStack stack = insertStack(UUID.fromString(orgId), InfrastructureStack.Status.ACTIVE, true, "2.4.5");
        String id = stack.getId().toString();

        for (String action : new String[] {"apply", "refresh", "upgrade"}) {
            MvcResult r = post(STACKS + "/" + id + "/" + action, admin, null);
            assertThat(status(r)).as(action + ": " + text(r)).isEqualTo(503);
            assertThat(code(r)).as(action).isEqualTo("PROVISIONING_UNAVAILABLE");
        }
        assertThat(code(post(STACKS + "/" + id + "/upgrade", admin, Map.of("releaseId", UUID.randomUUID().toString()))))
            .isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(stackRepo.findById(stack.getId()).orElseThrow().getStatus())
            .as("nothing was claimed").isEqualTo(InfrastructureStack.Status.ACTIVE);

        // Actual ordering: ProvisioningService.destroy calls requireReady() before comparing the
        // confirmation to the slug, so a wrong confirmation is still answered with the 503.
        String destroyPath = STACKS + "/" + id + "/destroy";
        MvcResult wrongSlug = postWith(destroyPath, admin, Map.of("confirmation", "definitely-not-the-slug"),
            stepUpHeaders(admin, ADMIN_PW, "POST " + destroyPath));
        assertThat(status(wrongSlug)).as(text(wrongSlug)).isEqualTo(503);
        assertThat(code(wrongSlug)).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(stackRepo.findById(stack.getId()).orElseThrow().getStatus()).isEqualTo(InfrastructureStack.Status.ACTIVE);
        assertThat(data(get(STACKS + "/" + id + "/runs", admin)).path("totalElements").asLong()).as("no run recorded").isZero();
    }
}
