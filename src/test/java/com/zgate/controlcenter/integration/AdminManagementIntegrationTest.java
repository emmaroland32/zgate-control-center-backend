package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin management end to end at the HTTP layer: the real security chain, interceptors, Flyway
 * migrations (including the seeded default super-admin and demo operators), the audit trail and
 * the step-up ticket store, against a throwaway Postgres. Nothing is mocked.
 */
class AdminManagementIntegrationTest extends AbstractIntegrationTest {

    // ── sign-in ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bootstrap rotates the seeded super-admin; a demo operator still on the shipped default is refused")
    void bootstrapAndDefaultPasswordRefusal() throws Exception {
        assertThat(adminToken()).isNotBlank();

        MvcResult refused = loginRaw("sarah.chen@zgate.io", "Admin@123");
        assertThat(status(refused)).isEqualTo(403);
        assertThat(code(refused)).isEqualTo("DEFAULT_PASSWORD_MUST_BE_CHANGED");
        AuditLog row = awaitAudit(audit("LOGIN_REFUSED_DEFAULT_PASSWORD", "sarah.chen@zgate.io"));
        assertThat(row.getStatus()).isEqualTo(AuditLog.Status.WARNING);
        assertThat(row.getIpAddress()).isNotBlank();
    }

    @Test
    @DisplayName("every sign-in outcome leaves an audit row, and the email is matched case-insensitively")
    void signInOutcomesAudited() throws Exception {
        String token = adminToken();
        String id = createOperator(token, unique("it-signin"), "VIEWER", GOOD_PW);
        String email = data(get("/api/v1/users/" + id, token)).path("email").asText();

        MvcResult wrong = loginRaw(email, "not-the-password-1");
        assertThat(status(wrong)).isEqualTo(401);
        assertThat(code(wrong)).isEqualTo("INVALID_CREDENTIALS");
        assertThat(awaitAudit(audit("LOGIN_FAILED", email)).getStatus()).isEqualTo(AuditLog.Status.FAILURE);

        String upper = login(email.toUpperCase(), GOOD_PW);
        assertThat(awaitAudit(audit("LOGIN_SUCCESS", email)).getDetails()).contains("ROLE_VIEWER");
        MvcResult me = get("/api/v1/users/me", upper);
        assertThat(status(me)).isEqualTo(200);
        assertThat(data(me).path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("unauthenticated calls get a 401 envelope, not a bodiless 403")
    void unauthenticatedIs401() throws Exception {
        MvcResult r = get("/api/v1/users", null);
        assertThat(status(r)).isEqualTo(401);
        assertThat(code(r)).isEqualTo("UNAUTHORIZED");
    }

    // ── views ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the operator list shows security state and never a secret")
    void operatorViewShape() throws Exception {
        String token = adminToken();
        MvcResult r = get("/api/v1/users", token);
        assertThat(status(r)).isEqualTo(200);
        JsonNode list = data(r);
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).as("seeded admin + demo operators").isGreaterThanOrEqualTo(7);
        for (JsonNode u : list) {
            assertThat(u.has("locked")).isTrue();
            assertThat(u.has("mfaEnabled")).isTrue();
            assertThat(u.has("ssoLinked")).isTrue();
            assertThat(u.has("failedLoginAttempts")).isTrue();
            assertThat(u.has("passwordHash")).isFalse();
            assertThat(u.has("mfaSecret")).isFalse();
            assertThat(u.has("oidcSubject")).isFalse();
            assertThat(u.has("tokenVersion")).isFalse();
        }
        assertThat(data(get("/api/v1/users/me", token)).path("role").asText()).isEqualTo("SUPER_ADMIN");
    }

    // ── privilege rules ─────────────────────────────────────────────────────

    @Test
    @DisplayName("an ADMIN cannot escalate: self-promotion and touching a super-admin are refused and audited")
    void adminCannotEscalate() throws Exception {
        String root = adminToken();
        String adminId = data(get("/api/v1/users/me", root)).path("id").asText();
        String email = unique("it-admin");
        String id = createOperator(root, email, "ADMIN", GOOD_PW);
        String admin = login(email, GOOD_PW);

        MvcResult promote = put("/api/v1/users/" + id, admin, Map.of("name", "x", "email", email, "role", "SUPER_ADMIN"));
        assertThat(status(promote)).isEqualTo(403);
        assertThat(code(promote)).isEqualTo("OPERATOR_PRIVILEGE");

        MvcResult unlockRoot = post("/api/v1/users/" + adminId + "/unlock", admin, null);
        assertThat(status(unlockRoot)).isEqualTo(403);
        assertThat(code(unlockRoot)).isEqualTo("OPERATOR_PRIVILEGE");
        assertThat(awaitAudit(audit("USER_ACTION_REFUSED", email)).getStatus()).isEqualTo(AuditLog.Status.FAILURE);

        // A SUPER_ADMIN-only endpoint is refused by method security before the controller runs.
        MvcResult disableRoot = post("/api/v1/users/" + adminId + "/disable", admin, null);
        assertThat(status(disableRoot)).isEqualTo(403);
        assertThat(code(disableRoot)).isEqualTo("ACCESS_DENIED");
        AuditLog denied = awaitAudit(audit("ACCESS_DENIED", email));
        assertThat(denied.getEntityId()).contains("/disable");

        JsonNode rootView = data(get("/api/v1/users/" + adminId, root));
        assertThat(rootView.path("active").asBoolean()).isTrue();
        assertThat(rootView.path("role").asText()).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("nobody can disable themselves or clear their own lockout")
    void noSelfService() throws Exception {
        String root = adminToken();
        String adminId = data(get("/api/v1/users/me", root)).path("id").asText();
        assertThat(code(post("/api/v1/users/" + adminId + "/disable", root, null))).isEqualTo("OPERATOR_PRIVILEGE");
        assertThat(code(post("/api/v1/users/" + adminId + "/unlock", root, null))).isEqualTo("OPERATOR_PRIVILEGE");
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("repeated failures lock the account; the admin can clear it; the message names no deadline")
    void lockoutAndUnlock() throws Exception {
        String root = adminToken();
        String email = unique("it-locked");
        String id = createOperator(root, email, "VIEWER", GOOD_PW);

        for (int i = 0; i < 5; i++) loginRaw(email, "wrong-" + i + "-Aa1");
        MvcResult locked = loginRaw(email, GOOD_PW);
        assertThat(status(locked)).isEqualTo(429);
        assertThat(code(locked)).isEqualTo("ACCOUNT_LOCKED");
        assertThat(body(locked).path("message").asText())
            .contains("about")
            .as("no exact deadline disclosed")
            .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
        assertThat(awaitAudit(audit("ACCOUNT_LOCKED", email)).getStatus()).isEqualTo(AuditLog.Status.WARNING);
        awaitAudit(audit("LOGIN_LOCKED", email));

        JsonNode view = data(get("/api/v1/users/" + id, root));
        assertThat(view.path("locked").asBoolean()).isTrue();
        assertThat(view.path("failedLoginAttempts").asInt()).isEqualTo(5);
        assertThat(view.path("lockedUntil").isNull()).isFalse();

        assertThat(status(post("/api/v1/users/" + id + "/unlock", root, null))).isEqualTo(200);
        view = data(get("/api/v1/users/" + id, root));
        assertThat(view.path("locked").asBoolean()).isFalse();
        assertThat(view.path("failedLoginAttempts").asInt()).isZero();
        assertThat(login(email, GOOD_PW)).isNotBlank();
        awaitAudit(audit("USER_UNLOCKED", ADMIN));
    }

    @Test
    @DisplayName("disable kills live sessions and refuses sign-in with a plain 401; enable restores")
    void disableAndEnable() throws Exception {
        String root = adminToken();
        String email = unique("it-disable");
        String id = createOperator(root, email, "SUPPORT", GOOD_PW);
        String session = login(email, GOOD_PW);

        assertThat(status(post("/api/v1/users/" + id + "/disable", root, null))).isEqualTo(200);
        assertThat(status(get("/api/v1/users/me", session))).as("old session").isEqualTo(401);
        MvcResult refused = loginRaw(email, GOOD_PW);
        assertThat(status(refused)).as("a disabled operator is a plain 401, not a 500").isEqualTo(401);
        assertThat(code(refused)).isEqualTo("INVALID_CREDENTIALS");
        assertThat(data(get("/api/v1/users/" + id, root)).path("active").asBoolean()).isFalse();

        assertThat(status(post("/api/v1/users/" + id + "/enable", root, null))).isEqualTo(200);
        assertThat(login(email, GOOD_PW)).isNotBlank();
        awaitAudit(audit("USER_ENABLED", ADMIN));
    }

    @Test
    @DisplayName("edit is name and role only: a password or an email change in the body is refused")
    void updateShape() throws Exception {
        String root = adminToken();
        String email = unique("it-edit");
        String id = createOperator(root, email, "VIEWER", GOOD_PW);

        assertThat(status(put("/api/v1/users/" + id, root,
            Map.of("name", "n", "email", email, "role", "VIEWER", "password", GOOD_PW)))).isEqualTo(400);
        assertThat(status(put("/api/v1/users/" + id, root,
            Map.of("name", "n", "email", "other@example.test", "role", "VIEWER")))).isEqualTo(400);

        MvcResult ok = put("/api/v1/users/" + id, root, Map.of("name", "Renamed", "email", email, "role", "SUPPORT"));
        assertThat(status(ok)).isEqualTo(200);
        assertThat(data(ok).path("name").asText()).isEqualTo("Renamed");
        assertThat(data(ok).path("role").asText()).isEqualTo("SUPPORT");
        assertThat(awaitAudit(audit("USER_UPDATED", ADMIN)).getDetails()).contains("role: VIEWER -> SUPPORT");
    }

    // ── step-up and passwords ───────────────────────────────────────────────

    @Test
    @DisplayName("reset-password needs a step-up ticket bound to that action, usable once; the role check comes first")
    void resetPasswordStepUp() throws Exception {
        String root = adminToken();
        String email = unique("it-reset");
        String id = createOperator(root, email, "VIEWER", GOOD_PW);
        String oldSession = login(email, GOOD_PW);
        String newPw = "FreshPassw0rd" + UUID.randomUUID().toString().substring(0, 4);

        MvcResult noTicket = post("/api/v1/users/" + id + "/reset-password", root, Map.of("password", newPw));
        assertThat(status(noTicket)).isEqualTo(403);
        assertThat(code(noTicket)).isEqualTo("STEP_UP_REQUIRED");

        Map<String, String> h = stepUpHeaders(root, ADMIN_PW, "POST /api/v1/users/" + id + "/reset-password");

        MvcResult otherAction = postWith("/api/v1/users/" + id + "/mfa/disable", root, null, h);
        assertThat(status(otherAction)).isEqualTo(403);
        assertThat(code(otherAction)).as("bound to reset-password").isEqualTo("STEP_UP_REQUIRED");

        MvcResult reset = postWith("/api/v1/users/" + id + "/reset-password", root, Map.of("password", newPw), h);
        assertThat(status(reset)).as("mismatch did not consume the ticket").isEqualTo(200);

        MvcResult replay = postWith("/api/v1/users/" + id + "/reset-password", root, Map.of("password", newPw + "x"), h);
        assertThat(status(replay)).isEqualTo(403);
        assertThat(code(replay)).as("single use").isEqualTo("STEP_UP_REQUIRED");

        assertThat(status(get("/api/v1/users/me", oldSession))).as("sessions revoked").isEqualTo(401);
        String viewer = login(email, newPw);

        MvcResult viewerTries = post("/api/v1/users/" + id + "/reset-password", viewer, Map.of("password", GOOD_PW));
        assertThat(status(viewerTries)).isEqualTo(403);
        assertThat(code(viewerTries)).as("role check before the step-up prompt").isEqualTo("ACCESS_DENIED");

        awaitAudit(audit("USER_PASSWORD_RESET", ADMIN));
        // Other suites also step up as the admin; wait for THIS action's row, not the first one.
        awaitAudit(a -> "STEP_UP_SUCCESS".equals(a.getAction()) && ADMIN.equalsIgnoreCase(a.getActorEmail())
            && a.getDetails() != null && a.getDetails().contains(id + "/reset-password"));
    }

    @Test
    @DisplayName("an operator changes their own password with the current one; every session dies")
    void changeOwnPassword() throws Exception {
        String root = adminToken();
        String email = unique("it-self");
        createOperator(root, email, "SUPPORT", GOOD_PW);
        String session = login(email, GOOD_PW);
        String newPw = "SelfChosen1Passw0rd";

        assertThat(status(post("/api/v1/users/me/password", session,
            Map.of("currentPassword", "nope-1Aa", "newPassword", newPw)))).isEqualTo(401);
        assertThat(status(post("/api/v1/users/me/password", session,
            Map.of("currentPassword", GOOD_PW, "newPassword", "short")))).isEqualTo(400);
        assertThat(status(post("/api/v1/users/me/password", session,
            Map.of("currentPassword", GOOD_PW, "newPassword", newPw)))).isEqualTo(200);

        assertThat(status(get("/api/v1/users/me", session))).isEqualTo(401);
        assertThat(login(email, newPw)).isNotBlank();
        awaitAudit(audit("PASSWORD_CHANGE_FAILED", email));
        awaitAudit(audit("PASSWORD_CHANGED", email));
    }

    // ── activity monitor ────────────────────────────────────────────────────

    @Test
    @DisplayName("per-operator activity, the summary counters and the audit filters see what just happened")
    void activityFeeds() throws Exception {
        String root = adminToken();
        String email = unique("it-activity");
        String id = createOperator(root, email, "VIEWER", GOOD_PW);
        loginRaw(email, "wrong-1-Aa");
        loginRaw(email, "wrong-2-Aa");
        login(email, GOOD_PW);
        awaitAudit(audit("LOGIN_SUCCESS", email));

        JsonNode feed = data(get("/api/v1/users/" + id + "/activity?size=10", root));
        assertThat(feed.path("totalElements").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(feed.path("content").get(0).path("action").asText()).isEqualTo("LOGIN_SUCCESS");

        JsonNode summary = data(get("/api/v1/users/activity/summary?hours=1", root));
        assertThat(summary.path("signIns").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.path("failedSignIns").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(summary.path("adminActions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.path("activeOperators").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode filtered = data(get("/api/v1/audit?actor=" + email + "&action=LOGIN_FAILED&status=FAILURE&entityType=ControlCenterUser", root));
        assertThat(filtered.path("totalElements").asInt()).isEqualTo(2);
        assertThat(status(get("/api/v1/audit?status=BOGUS", root))).isEqualTo(400);
    }
}
