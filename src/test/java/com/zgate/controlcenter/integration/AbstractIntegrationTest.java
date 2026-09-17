package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for HTTP-level integration tests: the real Spring context (security chain, interceptors,
 * Flyway V1–V34 with seed data, audit trail, step-up store) against ONE throwaway Postgres.
 *
 * <p>The container is a singleton started once per JVM rather than a JUnit-managed {@code @Container}
 * — a per-class container would be stopped while the cached Spring context still pointed at it.
 * Every subclass therefore shares one context and one database; tests must create their own
 * uniquely-named fixtures ({@link #unique}) and never assume an empty table.
 *
 * <p>The seeded super-admin ({@code admin@zgate.com}) is bootstrapped to {@link #ADMIN_PW}; the
 * seeded demo operators keep the refused default password. A throwaway RSA key is generated so
 * license signing works as in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "controlcenter.bootstrap.adminPassword=" + AbstractIntegrationTest.ADMIN_PW,
    "controlcenter.provisioning.encryptionKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "controlcenter.jwt.secret=integration-test-jwt-secret-that-is-long-enough-0123456789abcdef",
    "controlcenter.rateLimit.enabled=false",
    "controlcenter.fleet.rollout.enabled=false",
    "controlcenter.fleet.health.enabled=false",
    "controlcenter.alerts.dispatch.enabled=false",
    "controlcenter.billing.renewal.enabled=false",
    "controlcenter.license.autoRenew=false",
    "controlcenter.anomaly.enabled=false",
    "controlcenter.alerts.evaluator.enabled=false",
    "controlcenter.fleet.driftCheck.enabled=false",
    "controlcenter.cost.enabled=false",
    "controlcenter.telemetry.retentionDays=0",
    "controlcenter.audit.unknownActorRetentionDays=0",
    // Webhook targets are validated with a live DNS lookup; keep the suite hermetic.
    "controlcenter.webhooks.allowInsecureTargets=true",
})
public abstract class AbstractIntegrationTest {

    public static final String ADMIN = "admin@zgate.com";
    public static final String ADMIN_PW = "IntegrationAdm1nPass";
    public static final String GOOD_PW = "CorrectHorse9Battery";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Path LICENSE_KEY;

    static {
        POSTGRES.start();
        LICENSE_KEY = generateLicenseKey();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("controlcenter.license.privateKeyPath", LICENSE_KEY::toString);
    }

    private static Path generateLicenseKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                    .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
            Path file = Files.createTempFile("cc-it-license-", ".pem");
            Files.writeString(file, pem);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("could not generate a test license key", e);
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected AuditLogRepository auditRepo;

    // ── HTTP helpers ────────────────────────────────────────────────────────

    protected MvcResult call(MockHttpServletRequestBuilder req, String token, Object body,
                             Map<String, String> headers) throws Exception {
        req.contentType(MediaType.APPLICATION_JSON);
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.content(body instanceof String s ? s : json.writeValueAsString(body));
        if (headers != null) headers.forEach(req::header);
        return mvc.perform(req).andReturn();
    }

    protected MvcResult get(String path, String token) throws Exception {
        return call(MockMvcRequestBuilders.get(path), token, null, null);
    }

    protected MvcResult getWith(String path, String token, Map<String, String> headers) throws Exception {
        return call(MockMvcRequestBuilders.get(path), token, null, headers);
    }

    protected MvcResult post(String path, String token, Object body) throws Exception {
        return call(MockMvcRequestBuilders.post(path), token, body, null);
    }

    protected MvcResult postWith(String path, String token, Object body, Map<String, String> headers) throws Exception {
        return call(MockMvcRequestBuilders.post(path), token, body, headers);
    }

    protected MvcResult put(String path, String token, Object body) throws Exception {
        return call(MockMvcRequestBuilders.put(path), token, body, null);
    }

    protected MvcResult patch(String path, String token, Object body) throws Exception {
        return call(MockMvcRequestBuilders.patch(path), token, body, null);
    }

    protected MvcResult delete(String path, String token) throws Exception {
        return call(MockMvcRequestBuilders.delete(path), token, null, null);
    }

    protected MvcResult deleteWith(String path, String token, Map<String, String> headers) throws Exception {
        return call(MockMvcRequestBuilders.delete(path), token, null, headers);
    }

    protected int status(MvcResult r) { return r.getResponse().getStatus(); }

    protected JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? json.nullNode() : json.readTree(s);
    }

    protected String code(MvcResult r) throws Exception { return body(r).path("code").asText(); }

    /** The envelope's {@code data}; error responses have none (missing node). */
    protected JsonNode data(MvcResult r) throws Exception { return body(r).path("data"); }

    protected String text(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    // ── identity helpers ────────────────────────────────────────────────────

    protected MvcResult loginRaw(String email, String password) throws Exception {
        return post("/api/v1/auth/login", null, Map.of("email", email, "password", password));
    }

    protected String login(String email, String password) throws Exception {
        MvcResult r = loginRaw(email, password);
        assertThat(status(r)).as("login %s: %s", email, text(r)).isEqualTo(200);
        return data(r).path("token").asText();
    }

    protected String adminToken() throws Exception { return login(ADMIN, ADMIN_PW); }

    /** Create an operator with the given role and return its id. */
    protected String createOperator(String adminToken, String email, String role, String password) throws Exception {
        MvcResult r = post("/api/v1/users", adminToken,
            Map.of("name", email.split("@")[0], "email", email, "role", role, "password", password));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r).path("id").asText();
    }

    /** A signed-in token for a fresh operator of the given role. */
    protected String tokenFor(String adminToken, String role) throws Exception {
        String email = unique("it-" + role.toLowerCase());
        createOperator(adminToken, email, role, GOOD_PW);
        return login(email, GOOD_PW);
    }

    /** A single-use step-up ticket bound to {@code action} ("METHOD /path"). */
    protected String stepUp(String token, String password, String action) throws Exception {
        MvcResult r = post("/api/v1/auth/step-up", token, Map.of("password", password, "action", action));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        return data(r).path("ticket").asText();
    }

    protected Map<String, String> stepUpHeaders(String token, String password, String action) throws Exception {
        return Map.of("X-StepUp-Ticket", stepUp(token, password, action));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    /** A unique slug/name fragment for non-email fixtures. */
    protected static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── audit helpers ───────────────────────────────────────────────────────

    /** Audit writes are asynchronous; wait (briefly) for the row instead of racing it. */
    protected AuditLog awaitAudit(Predicate<AuditLog> match) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            List<AuditLog> rows = auditRepo.findAll();
            for (AuditLog row : rows) if (match.test(row)) return row;
            Thread.sleep(100);
        }
        throw new AssertionError("audit row not written within 5s");
    }

    protected static Predicate<AuditLog> audit(String action, String actorEmail) {
        return a -> action.equals(a.getAction()) && actorEmail.equalsIgnoreCase(a.getActorEmail());
    }

    protected static Predicate<AuditLog> auditAction(String action) {
        return a -> action.equals(a.getAction());
    }
}
