package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.domain.BackupRecord;
import com.zgate.controlcenter.repository.BackupPlanRepository;
import com.zgate.controlcenter.repository.BackupRecordRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Managed backups on both faces: the operator admin API under {@code /api/v1/admin/backups} and the
 * service-key-authenticated agent API under {@code /api/v1/backups}. Backup storage (S3) is OFF in
 * the harness, so every path that would mint a presigned URL is asserted at its guard; the metadata
 * lifecycle (complete / verify / fail) is driven on rows inserted directly.
 */
class BackupsIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_API = "/api/v1/admin/backups";
    private static final String AGENT_API = "/api/v1/backups";
    private static final String ORG_HEADER = "X-Control-Center-Org-Id";
    private static final String KEY_HEADER = "X-Control-Center-Service-Key";
    private static final long GIB = 1024L * 1024 * 1024;

    @Autowired BackupRecordRepository recordRepo;
    @Autowired BackupPlanRepository planRepo;

    /** An org plus the plaintext service key that is only ever visible in the create response. */
    private record Org(String id, String key) {}

    // ── helpers ─────────────────────────────────────────────────────────────

    private Org createOrg(String token) throws Exception {
        MvcResult r = post("/api/v1/organizations", token, Map.of(
            "name", uniqueSlug("Backup Org"), "slug", uniqueSlug("backup-org"),
            "tier", "ENTERPRISE", "deploymentEnv", "PRODUCTION"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        String key = data(r).path("serviceApiKey").asText();
        assertThat(key).startsWith("zgn_");
        return new Org(data(r).path("id").asText(), key);
    }

    private static Map<String, String> agentHeaders(Org org) {
        return Map.of(ORG_HEADER, org.id(), KEY_HEADER, org.key());
    }

    private BackupRecord insertRecord(Org org, BackupRecord.Status status) {
        UUID backupId = UUID.randomUUID();
        BackupRecord.BackupRecordBuilder b = BackupRecord.builder()
            .organizationId(UUID.fromString(org.id()))
            .s3Key("org/" + org.id() + "/" + backupId + ".enc")
            .label(uniqueSlug("it-backup"))
            .nodeId("node-1")
            .status(status);
        if (status == BackupRecord.Status.COMPLETED) {
            b.sizeBytes(2048L).sha256("cafe").completedAt(LocalDateTime.now())
             .expiresAt(LocalDateTime.now().plusDays(30));
        }
        return recordRepo.save(b.build());
    }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode n : array) if (id.equals(n.path("id").asText())) return true;
        return false;
    }

    // ── admin ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("admin list is paged, stats are non-negative counters, and a fresh org has no backups and zero usage")
    void adminListStatsAndUsage() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);

        MvcResult page = get(ADMIN_API + "?page=0&size=5", admin);
        assertThat(status(page)).as(text(page)).isEqualTo(200);
        assertThat(data(page).path("content").isArray()).isTrue();
        assertThat(data(page).path("content").size()).isLessThanOrEqualTo(5);
        assertThat(data(page).path("totalElements").asLong()).isGreaterThanOrEqualTo(0);

        MvcResult stats = get(ADMIN_API + "/stats", admin);
        assertThat(status(stats)).isEqualTo(200);
        for (String f : new String[] {"plans", "activePlans", "backups", "completed", "failed", "totalStoredBytes"}) {
            assertThat(data(stats).path(f).asLong()).as(f).isGreaterThanOrEqualTo(0);
        }
        assertThat(data(stats).path("activePlans").asLong()).isLessThanOrEqualTo(data(stats).path("plans").asLong());

        MvcResult list = get(ADMIN_API + "/org/" + org.id(), admin);
        assertThat(status(list)).isEqualTo(200);
        assertThat(data(list).isArray()).isTrue();
        assertThat(data(list).size()).isZero();

        MvcResult usage = get(ADMIN_API + "/org/" + org.id() + "/usage", admin);
        assertThat(status(usage)).isEqualTo(200);
        assertThat(data(usage).path("usedBytes").asLong()).isZero();
        assertThat(data(usage).path("quotaBytes").asLong()).as("no plan = no quota").isZero();
        assertThat(data(usage).path("completedCount").asLong()).isZero();
        assertThat(data(usage).path("active").asBoolean()).isFalse();
        assertThat(data(usage).path("estimatedMonthlyCharge").decimalValue()).isEqualByComparingTo("0");
        assertThat(data(usage).path("currency").asText()).isEqualTo("USD");

        // Class-level guard: SUPPORT and VIEWER see nothing here.
        MvcResult support = get(ADMIN_API + "/stats", tokenFor(admin, "SUPPORT"));
        assertThat(status(support)).isEqualTo(403);
        assertThat(code(support)).isEqualTo("ACCESS_DENIED");
        assertThat(code(get(ADMIN_API, tokenFor(admin, "VIEWER")))).isEqualTo("ACCESS_DENIED");
        assertThat(status(get(ADMIN_API + "/stats", tokenFor(admin, "ADMIN")))).isEqualTo(200);
    }

    @Test
    @DisplayName("plan: absent reads as null data; PUT upserts and is reflected in usage; negative quota clamps to 0 and retention to 1")
    void planUpsertAndClamping() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        String planPath = ADMIN_API + "/org/" + org.id() + "/plan";

        MvcResult absent = get(planPath, admin);
        assertThat(status(absent)).isEqualTo(200);
        JsonNode d = body(absent).get("data");
        assertThat(d == null || d.isNull()).as("orElse(null) → data null: " + text(absent)).isTrue();

        MvcResult put = put(planPath, admin, Map.of(
            "enabled", true, "storageQuotaGb", 10, "retentionDays", 30, "maxRetainedBackups", 7,
            "pricePerMonth", 25.00, "pricePerGbMonth", 0.5, "currency", "USD"));
        assertThat(status(put)).as(text(put)).isEqualTo(200);
        assertThat(code(put)).isEqualTo("BACKUP_PLAN_UPDATED");
        JsonNode plan = data(put);
        assertThat(plan.path("id").asText()).isNotBlank();
        assertThat(plan.path("organizationId").asText()).isEqualTo(org.id());
        assertThat(plan.path("enabled").asBoolean()).isTrue();
        assertThat(plan.path("storageQuotaGb").asInt()).isEqualTo(10);
        assertThat(plan.path("retentionDays").asInt()).isEqualTo(30);
        assertThat(plan.path("maxRetainedBackups").asInt()).isEqualTo(7);
        assertThat(plan.path("pricePerMonth").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(plan.path("pricePerGbMonth").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(plan.path("active").asBoolean()).isTrue();

        JsonNode read = data(get(planPath, admin));
        assertThat(read.path("id").asText()).isEqualTo(plan.path("id").asText());
        assertThat(read.path("storageQuotaGb").asInt()).isEqualTo(10);

        JsonNode usage = data(get(ADMIN_API + "/org/" + org.id() + "/usage", admin));
        assertThat(usage.path("quotaBytes").asLong()).isEqualTo(10 * GIB);
        assertThat(usage.path("active").asBoolean()).isTrue();
        assertThat(usage.path("estimatedMonthlyCharge").decimalValue()).as("base fee, nothing stored").isEqualByComparingTo("25.00");

        // Second PUT updates the SAME row (organization_id is UNIQUE): clamps apply, and an explicit
        // JSON null price leaves the stored one alone (upsertPlan's null-guard). An OMITTED price does
        // not — see omittedPlanPriceIsRetained.
        Map<String, Object> clampBody = new HashMap<>();
        clampBody.put("enabled", false);
        clampBody.put("storageQuotaGb", -5);
        clampBody.put("retentionDays", 0);
        clampBody.put("pricePerMonth", null);
        clampBody.put("pricePerGbMonth", null);
        clampBody.put("currency", null);
        MvcResult clamped = put(planPath, admin, clampBody);
        assertThat(status(clamped)).as(text(clamped)).isEqualTo(200);
        JsonNode c = data(clamped);
        assertThat(c.path("id").asText()).isEqualTo(plan.path("id").asText());
        assertThat(c.path("storageQuotaGb").asInt()).as("Math.max(0, …)").isZero();
        assertThat(c.path("retentionDays").asInt()).as("Math.max(1, …)").isEqualTo(1);
        assertThat(c.path("enabled").asBoolean()).isFalse();
        assertThat(c.path("active").asBoolean()).isFalse();
        assertThat(c.path("maxRetainedBackups").isNull()).as("null in body clears the cap").isTrue();
        assertThat(c.path("pricePerMonth").decimalValue()).as("explicit null keeps the stored price").isEqualByComparingTo("25.00");
        assertThat(c.path("pricePerGbMonth").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(c.path("currency").asText()).isEqualTo("USD");
        assertThat(planRepo.findByOrganizationId(UUID.fromString(org.id())).orElseThrow().getStorageQuotaGb()).isZero();

        // A paid-through date in the past makes an enabled plan inactive.
        MvcResult lapsed = put(planPath, admin, Map.of("enabled", true, "storageQuotaGb", 1, "retentionDays", 1,
            "subscriptionValidUntil", LocalDateTime.now().minusDays(1).withNano(0).toString()));
        assertThat(data(lapsed).path("enabled").asBoolean()).isTrue();
        assertThat(data(lapsed).path("active").asBoolean()).isFalse();

        assertThat(data(get(ADMIN_API + "/stats", admin)).path("plans").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(code(put(planPath, tokenFor(admin, "SUPPORT"), Map.of("enabled", true)))).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("a plan PUT that omits the prices keeps the stored pricing")
    void omittedPlanPriceIsRetained() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        String planPath = ADMIN_API + "/org/" + org.id() + "/plan";
        assertThat(status(put(planPath, admin, Map.of("enabled", true, "storageQuotaGb", 10, "retentionDays", 30,
            "pricePerMonth", 25.00, "pricePerGbMonth", 0.5)))).isEqualTo(200);

        MvcResult r = put(planPath, admin, Map.of("enabled", true, "storageQuotaGb", 20, "retentionDays", 30));
        assertThat(status(r)).isEqualTo(200);
        assertThat(data(r).path("pricePerMonth").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(data(r).path("pricePerGbMonth").decimalValue()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("admin restore-url: role gate, then step-up gate, then the audit row lands before BACKUP_NOT_FOUND / BACKUP_NOT_RESTORABLE")
    void adminRestoreUrlGates() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        String randomBackup = UUID.randomUUID().toString();
        String path = ADMIN_API + "/org/" + org.id() + "/" + randomBackup + "/restore-url";

        // Role check comes first: an ADMIN is refused by method security, never asked to step up.
        MvcResult asAdminRole = post(path, tokenFor(admin, "ADMIN"), null);
        assertThat(status(asAdminRole)).isEqualTo(403);
        assertThat(code(asAdminRole)).isEqualTo("ACCESS_DENIED");

        MvcResult noTicket = post(path, admin, null);
        assertThat(status(noTicket)).isEqualTo(403);
        assertThat(code(noTicket)).isEqualTo("STEP_UP_REQUIRED");

        // A ticket bound to a different backup id is a different action.
        Map<String, String> wrong = stepUpHeaders(admin, ADMIN_PW,
            "POST " + ADMIN_API + "/org/" + org.id() + "/" + UUID.randomUUID() + "/restore-url");
        MvcResult wrongTicket = postWith(path, admin, null, wrong);
        assertThat(status(wrongTicket)).isEqualTo(403);
        assertThat(code(wrongTicket)).isEqualTo("STEP_UP_REQUIRED");

        MvcResult notFound = postWith(path, admin, null, stepUpHeaders(admin, ADMIN_PW, "POST " + path));
        assertThat(status(notFound)).as(text(notFound)).isEqualTo(404);
        assertThat(code(notFound)).isEqualTo("BACKUP_NOT_FOUND");
        // The audit row is written BEFORE the lookup, so it lands even on failure.
        AuditLog issued = awaitAudit(a -> "BACKUP_RESTORE_URL_ISSUED".equals(a.getAction())
                                       && randomBackup.equals(a.getEntityId()));
        assertThat(issued.getActorEmail()).isEqualTo(ADMIN);
        assertThat(issued.getOrganizationId()).isEqualTo(UUID.fromString(org.id()));

        BackupRecord initiated = insertRecord(org, BackupRecord.Status.INITIATED);
        String initiatedPath = ADMIN_API + "/org/" + org.id() + "/" + initiated.getId() + "/restore-url";
        MvcResult notRestorable = postWith(initiatedPath, admin, null,
            stepUpHeaders(admin, ADMIN_PW, "POST " + initiatedPath));
        assertThat(status(notRestorable)).isEqualTo(409);
        assertThat(code(notRestorable)).isEqualTo("BACKUP_NOT_RESTORABLE");

        // A record that belongs to another org is invisible through this org's path.
        Org other = createOrg(admin);
        String foreignPath = ADMIN_API + "/org/" + other.id() + "/" + initiated.getId() + "/restore-url";
        MvcResult foreign = postWith(foreignPath, admin, null, stepUpHeaders(admin, ADMIN_PW, "POST " + foreignPath));
        assertThat(status(foreign)).isEqualTo(404);
        assertThat(code(foreign)).isEqualTo("BACKUP_NOT_FOUND");
    }

    @Test
    @DisplayName("admin restore-url on a COMPLETED backup with storage off is a clean 503, not a 500")
    void adminRestoreUrlWithoutStorageIs503() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        BackupRecord completed = insertRecord(org, BackupRecord.Status.COMPLETED);
        String path = ADMIN_API + "/org/" + org.id() + "/" + completed.getId() + "/restore-url";

        MvcResult r = postWith(path, admin, null, stepUpHeaders(admin, ADMIN_PW, "POST " + path));
        assertThat(status(r)).isEqualTo(503);
        assertThat(code(r)).isEqualTo("BACKUP_UNAVAILABLE");
    }

    // ── agent (M2M) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the agent API is permitAll but always service-key enforced: no key, wrong key or wrong org is a raw 401 invalid_service_key")
    void agentRejectsMissingOrBadKey() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);

        MvcResult bare = get(AGENT_API, null);
        assertThat(status(bare)).isEqualTo(401);
        assertThat(text(bare)).isEqualTo("{\"error\":\"invalid_service_key\"}");
        assertThat(bare.getResponse().getHeader("X-Api-Envelope")).isNull();

        assertThat(status(getWith(AGENT_API, null, Map.of(ORG_HEADER, org.id())))).as("org id alone").isEqualTo(401);
        assertThat(status(getWith(AGENT_API, null, Map.of(ORG_HEADER, org.id(), KEY_HEADER, "zgn_wrong")))).isEqualTo(401);
        assertThat(status(getWith(AGENT_API, null, Map.of(ORG_HEADER, "not-a-uuid", KEY_HEADER, org.key())))).isEqualTo(401);
        assertThat(status(getWith(AGENT_API, null, Map.of(ORG_HEADER, UUID.randomUUID().toString(), KEY_HEADER, org.key()))))
            .as("a valid key for a different org").isEqualTo(401);
        // An operator JWT is not a service key either.
        assertThat(status(get(AGENT_API, admin))).isEqualTo(401);
        assertThat(status(postWith(AGENT_API + "/initiate", null, Map.of(), Map.of(ORG_HEADER, org.id())))).isEqualTo(401);
        assertThat(status(postWith(AGENT_API + "/" + UUID.randomUUID() + "/complete", null, Map.of(), Map.of()))).isEqualTo(401);

        // A regenerated key retires the old one.
        MvcResult regen = post("/api/v1/organizations/" + org.id() + "/regenerate-key", admin, null);
        assertThat(status(regen)).isEqualTo(200);
        String fresh = data(regen).path("serviceApiKey").asText();
        assertThat(status(getWith(AGENT_API, null, agentHeaders(org)))).as("old key").isEqualTo(401);
        assertThat(status(getWith(AGENT_API, null, Map.of(ORG_HEADER, org.id(), KEY_HEADER, fresh)))).isEqualTo(200);
    }

    @Test
    @DisplayName("with a real key: list is a raw JSON array, and initiate is 503 BACKUP_UNAVAILABLE before any plan/entitlement check")
    void agentListAndInitiate() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);

        MvcResult list = getWith(AGENT_API, null, agentHeaders(org));
        assertThat(status(list)).as(text(list)).isEqualTo(200);
        assertThat(body(list).isArray()).as("@RawResponse: no envelope").isTrue();
        assertThat(body(list).size()).isZero();
        assertThat(list.getResponse().getHeader("X-Api-Envelope")).isNull();

        // Storage check runs first (BackupService.initiate:237-241) — the org has no plan at all,
        // yet the answer is BACKUP_UNAVAILABLE rather than BACKUP_NOT_ENTITLED.
        MvcResult noPlan = postWith(AGENT_API + "/initiate", null,
            Map.of("sizeBytes", 1024, "nodeId", "node-1", "label", "nightly"), agentHeaders(org));
        assertThat(status(noPlan)).as(text(noPlan)).isEqualTo(503);
        assertThat(code(noPlan)).isEqualTo("BACKUP_UNAVAILABLE");

        // Same answer with an active plan: the entitlement gates are never reached with storage off.
        assertThat(status(put(ADMIN_API + "/org/" + org.id() + "/plan", admin,
            Map.of("enabled", true, "storageQuotaGb", 10, "retentionDays", 30)))).isEqualTo(200);
        MvcResult withPlan = postWith(AGENT_API + "/initiate", null, null, agentHeaders(org));
        assertThat(status(withPlan)).isEqualTo(503);
        assertThat(code(withPlan)).isEqualTo("BACKUP_UNAVAILABLE");
        assertThat(recordRepo.findByOrganizationIdOrderByCreatedAtDesc(UUID.fromString(org.id()))).isEmpty();
    }

    @Test
    @DisplayName("agent lifecycle on inserted rows: complete stamps size/sha/expiry, verify-report records the outcome, fail only from INITIATED")
    void agentCompleteVerifyFail() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        // A plan with a 7-day retention so the expiry stamp is provably plan-driven.
        assertThat(status(put(ADMIN_API + "/org/" + org.id() + "/plan", admin,
            Map.of("enabled", true, "storageQuotaGb", 10, "retentionDays", 7)))).isEqualTo(200);

        BackupRecord rec = insertRecord(org, BackupRecord.Status.INITIATED);
        String id = rec.getId().toString();

        MvcResult done = postWith(AGENT_API + "/" + id + "/complete", null,
            Map.of("sha256", "deadbeef", "sizeBytes", 4096), agentHeaders(org));
        assertThat(status(done)).as(text(done)).isEqualTo(200);
        JsonNode b = body(done);
        assertThat(b.path("id").asText()).isEqualTo(id);
        assertThat(b.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(b.path("sizeBytes").asLong()).isEqualTo(4096);
        assertThat(b.path("sha256").asText()).isEqualTo("deadbeef");
        assertThat(b.path("completedAt").isNull()).isFalse();
        assertThat(b.path("expiresAt").isNull()).isFalse();
        assertThat(b.path("clientEncrypted").asBoolean()).isTrue();
        assertThat(b.path("verified").asBoolean()).isFalse();
        assertThat(b.has("s3Key")).as("storage layout is @JsonIgnore").isFalse();
        BackupRecord stored = recordRepo.findById(rec.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BackupRecord.Status.COMPLETED);
        assertThat(stored.getExpiresAt()).isEqualTo(stored.getCompletedAt().plusDays(7));

        MvcResult again = postWith(AGENT_API + "/" + id + "/complete", null, null, agentHeaders(org));
        assertThat(status(again)).isEqualTo(409);
        assertThat(code(again)).isEqualTo("BACKUP_BAD_STATE");

        MvcResult bad = postWith(AGENT_API + "/" + id + "/verify-report", null,
            Map.of("verified", false, "error", "pg_restore --list failed"), agentHeaders(org));
        assertThat(status(bad)).isEqualTo(200);
        assertThat(body(bad).path("verified").asBoolean()).isFalse();
        assertThat(body(bad).path("verifyError").asText()).isEqualTo("pg_restore --list failed");
        assertThat(body(bad).path("verifiedAt").isNull()).isFalse();
        MvcResult good = postWith(AGENT_API + "/" + id + "/verify-report", null,
            Map.of("verified", true), agentHeaders(org));
        assertThat(body(good).path("verified").asBoolean()).isTrue();
        assertThat(body(good).path("verifyError").isNull()).as("cleared on success").isTrue();
        assertThat(recordRepo.findById(rec.getId()).orElseThrow().isVerified()).isTrue();
        MvcResult noBody = postWith(AGENT_API + "/" + id + "/verify-report", null, null, agentHeaders(org));
        assertThat(status(noBody)).as("the report body is required").isEqualTo(400);

        MvcResult failCompleted = postWith(AGENT_API + "/" + id + "/fail", null,
            Map.of("reason", "too late"), agentHeaders(org));
        assertThat(status(failCompleted)).isEqualTo(409);
        assertThat(code(failCompleted)).isEqualTo("BACKUP_BAD_STATE");
        assertThat(recordRepo.findById(rec.getId()).orElseThrow().getStatus()).isEqualTo(BackupRecord.Status.COMPLETED);

        BackupRecord inflight = insertRecord(org, BackupRecord.Status.INITIATED);
        MvcResult failed = postWith(AGENT_API + "/" + inflight.getId() + "/fail", null,
            Map.of("reason", "upload aborted"), agentHeaders(org));
        assertThat(status(failed)).as(text(failed)).isEqualTo(200);
        assertThat(body(failed).path("status").asText()).isEqualTo("failed");
        assertThat(body(failed).path("id").asText()).isEqualTo(inflight.getId().toString());
        BackupRecord failedRow = recordRepo.findById(inflight.getId()).orElseThrow();
        assertThat(failedRow.getStatus()).isEqualTo(BackupRecord.Status.FAILED);
        assertThat(failedRow.getFailureReason()).isEqualTo("upload aborted");

        // Both faces now see both rows; the fleet stats and org usage count only the completed one.
        JsonNode agentList = body(getWith(AGENT_API, null, agentHeaders(org)));
        assertThat(agentList.size()).isEqualTo(2);
        assertThat(containsId(agentList, id)).isTrue();
        assertThat(containsId(agentList, inflight.getId().toString())).isTrue();
        JsonNode adminList = data(get(ADMIN_API + "/org/" + org.id(), admin));
        assertThat(adminList.size()).isEqualTo(2);
        JsonNode usage = data(get(ADMIN_API + "/org/" + org.id() + "/usage", admin));
        assertThat(usage.path("usedBytes").asLong()).isEqualTo(4096);
        assertThat(usage.path("completedCount").asLong()).isEqualTo(1);
        JsonNode stats = data(get(ADMIN_API + "/stats", admin));
        assertThat(stats.path("completed").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("failed").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("totalStoredBytes").asLong()).isGreaterThanOrEqualTo(4096);

        MvcResult unknown = postWith(AGENT_API + "/" + UUID.randomUUID() + "/complete", null, null, agentHeaders(org));
        assertThat(status(unknown)).isEqualTo(404);
        assertThat(code(unknown)).isEqualTo("BACKUP_NOT_FOUND");
    }

    @Test
    @DisplayName("a backup that belongs to another org is a clean 404 on every agent path, and never listed")
    void agentCrossOrgIs404() throws Exception {
        String admin = adminToken();
        Org owner = createOrg(admin);
        Org intruder = createOrg(admin);
        BackupRecord rec = insertRecord(owner, BackupRecord.Status.INITIATED);
        String id = rec.getId().toString();

        for (String action : new String[] {"complete", "fail", "restore-url"}) {
            MvcResult r = postWith(AGENT_API + "/" + id + "/" + action, null, null, agentHeaders(intruder));
            assertThat(status(r)).as(action).isEqualTo(404);
            assertThat(code(r)).as(action).isEqualTo("BACKUP_NOT_FOUND");
        }
        MvcResult verify = postWith(AGENT_API + "/" + id + "/verify-report", null,
            Map.of("verified", true), agentHeaders(intruder));
        assertThat(status(verify)).isEqualTo(404);
        assertThat(code(verify)).isEqualTo("BACKUP_NOT_FOUND");

        assertThat(containsId(body(getWith(AGENT_API, null, agentHeaders(intruder))), id)).isFalse();
        assertThat(containsId(body(getWith(AGENT_API, null, agentHeaders(owner))), id)).isTrue();
        assertThat(recordRepo.findById(rec.getId()).orElseThrow().getStatus())
            .as("nothing changed").isEqualTo(BackupRecord.Status.INITIATED);
    }

    @Test
    @DisplayName("agent restore-url on an INITIATED backup is 409 BACKUP_NOT_RESTORABLE")
    void agentRestoreUrlNotRestorable() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        BackupRecord rec = insertRecord(org, BackupRecord.Status.INITIATED);

        MvcResult r = postWith(AGENT_API + "/" + rec.getId() + "/restore-url", null, null, agentHeaders(org));
        assertThat(status(r)).isEqualTo(409);
        assertThat(code(r)).isEqualTo("BACKUP_NOT_RESTORABLE");
    }

    @Test
    @DisplayName("agent restore-url on a COMPLETED backup with storage off is a clean 503, not a 500")
    void agentRestoreUrlWithoutStorageIs503() throws Exception {
        String admin = adminToken();
        Org org = createOrg(admin);
        BackupRecord rec = insertRecord(org, BackupRecord.Status.COMPLETED);

        MvcResult r = postWith(AGENT_API + "/" + rec.getId() + "/restore-url", null, null, agentHeaders(org));
        assertThat(status(r)).isEqualTo(503);
        assertThat(code(r)).isEqualTo("BACKUP_UNAVAILABLE");
    }
}
