package com.zgate.controlcenter.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zgate.controlcenter.domain.License;
import com.zgate.controlcenter.repository.LicenseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Licensing end to end: issue (single and bulk) with real RSA signing, bundle verification with the
 * matching public key, deactivate, fingerprints, stats/expiring views, integrity checks, and the two
 * org-facing M2M routes (bundle pull and activation status report).
 */
class LicensesIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/licenses";
    private static final String ORGS = "/api/v1/organizations";
    private static final String APEX = "b0000001-0000-0000-0000-000000000001";
    private static final String CORONATION = "b0000002-0000-0000-0000-000000000002";

    @Autowired LicenseRepository licenseRepo;

    // ── helpers ─────────────────────────────────────────────────────────────

    private JsonNode createOrg(String token, String prefix) throws Exception {
        String slug = uniqueSlug(prefix);
        MvcResult r = post(ORGS, token, Map.of("name", "Lic Org " + slug, "slug", slug,
            "tier", "ENTERPRISE", "deploymentEnv", "PRODUCTION"));
        assertThat(status(r)).as(text(r)).isEqualTo(201);
        return data(r);
    }

    private JsonNode issue(String token, String orgId, String module, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("organizationId", orgId, "moduleName", module));
        if (extra != null) body.putAll(extra);
        MvcResult r = post(BASE + "/issue", token, body);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("LICENSE_ISSUED");
        return data(r);
    }

    private static Map<String, String> orgHeader(String orgId) {
        return Map.of("X-Control-Center-Org-Id", orgId);
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** The public half of the throwaway PKCS#8 RSA key the harness wired into the signing service. */
    private static PublicKey signingPublicKey() throws Exception {
        String pem = Files.readString(LICENSE_KEY)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        return kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
    }

    private static boolean signatureValid(String payloadB64, String signatureB64) throws Exception {
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(signingPublicKey());
        v.update(payloadB64.getBytes(StandardCharsets.UTF_8));
        return v.verify(Base64.getDecoder().decode(signatureB64));
    }

    /** Checks the bundle envelope + RSA signature and returns the decoded payload. */
    private JsonNode verifiedPayload(String bundleJson) throws Exception {
        JsonNode bundle = json.readTree(bundleJson);
        assertThat(bundle.path("format").asText()).isEqualTo("zgate-license-v2");
        String payloadB64 = bundle.path("payload").asText();
        String sig = bundle.path("signature").asText();
        assertThat(payloadB64).isNotBlank();
        assertThat(sig).isNotBlank();
        assertThat(signatureValid(payloadB64, sig)).as("SHA256withRSA over the base64 payload bytes").isTrue();
        return json.readTree(Base64.getDecoder().decode(payloadB64));
    }

    private JsonNode licensesOf(String token, String orgId) throws Exception {
        MvcResult r = get(BASE + "/org/" + orgId, token);
        assertThat(status(r)).isEqualTo(200);
        return data(r);
    }

    private static JsonNode byId(JsonNode list, String id) {
        for (JsonNode l : list) if (id.equals(l.path("id").asText())) return l;
        throw new AssertionError("license " + id + " not in list");
    }

    // ── issue ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue signs a bundle the public key verifies; the entity never serialises it; a re-issue suspends the old one")
    void issueSignedBundle() throws Exception {
        String token = adminToken();
        JsonNode org = createOrg(token, "it-lic");
        String orgId = org.path("id").asText();
        String orgName = org.path("name").asText();

        LocalDateTime before = LocalDateTime.now();
        JsonNode lic = issue(token, orgId, "CORE", null);
        String id = lic.path("id").asText();
        assertThat(lic.path("organizationId").asText()).isEqualTo(orgId);
        assertThat(lic.path("moduleName").asText()).isEqualTo("CORE");
        assertThat(lic.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(lic.path("deliveryStatus").asText()).isEqualTo("PENDING");
        assertThat(lic.path("issuedBy").asText()).isEqualTo(ADMIN);
        assertThat(lic.path("maxVersion").isNull()).as("org has no entitledVersion").isTrue();
        assertThat(lic.path("licenseFileHash").isNull()).isTrue();
        assertThat(lic.has("bundleJson")).as("signed material is @JsonIgnore on the entity").isFalse();
        LocalDateTime expires = LocalDateTime.parse(lic.path("expiresAt").asText());
        assertThat(expires).as("default 30-day TTL").isAfter(before.plusDays(29)).isBefore(before.plusDays(31));

        License row = licenseRepo.findById(UUID.fromString(id)).orElseThrow();
        assertThat(row.getBundleJson()).isNotBlank();
        JsonNode payload = verifiedPayload(row.getBundleJson());
        assertThat(payload.path("licenseId").asText()).isEqualTo(id);
        assertThat(payload.path("customer").asText()).isEqualTo(orgName);
        assertThat(payload.path("expiresAt").asText()).startsWith(lic.path("expiresAt").asText().substring(0, 16)).endsWith("Z");
        assertThat(payload.path("fingerprint").isNull()).isTrue();
        assertThat(payload.path("maxUsers").asInt()).isEqualTo(9999);
        assertThat(payload.path("features").asText()).isEqualTo("all");
        assertThat(payload.has("maxVersion")).isFalse();
        assertThat(payload.has("graceDays")).isFalse();
        assertThat(payload.path("modules").size()).isEqualTo(1);
        assertThat(payload.path("modules").get(0).path("moduleId").asText()).isEqualTo("CORE");

        // Tampering with the payload invalidates the signature (the verification is not a no-op).
        JsonNode bundle = json.readTree(row.getBundleJson());
        String tampered = Base64.getEncoder().encodeToString(
            payload.toString().replace(orgName, "Someone Else").getBytes(StandardCharsets.UTF_8));
        assertThat(signatureValid(tampered, bundle.path("signature").asText())).isFalse();

        // Explicit fields flow into both the row and the payload.
        JsonNode second = issue(token, orgId, "CORE", Map.of(
            "expiresAt", "2031-06-30T12:00:00", "maxUsers", 25, "features", "{\"ai\":true}",
            "fingerprint", "fp-" + id.substring(0, 8), "maxVersion", "2.5.0", "graceDays", 7,
            "imageDigest", "sha256:" + "a".repeat(64)));
        assertThat(second.path("expiresAt").asText()).startsWith("2031-06-30T12:00");
        assertThat(second.path("maxUsers").asInt()).isEqualTo(25);
        assertThat(second.path("maxVersion").asText()).isEqualTo("2.5.0");
        assertThat(second.path("graceDays").asInt()).isEqualTo(7);
        JsonNode p2 = verifiedPayload(licenseRepo.findById(UUID.fromString(second.path("id").asText()))
            .orElseThrow().getBundleJson());
        assertThat(p2.path("expiresAt").asText()).isEqualTo("2031-06-30T12:00:00Z");
        assertThat(p2.path("maxUsers").asInt()).isEqualTo(25);
        assertThat(p2.path("features").asText()).isEqualTo("{\"ai\":true}");
        assertThat(p2.path("fingerprint").asText()).isEqualTo("fp-" + id.substring(0, 8));
        assertThat(p2.path("maxVersion").asText()).isEqualTo("2.5.0");
        assertThat(p2.path("graceDays").asInt()).isEqualTo(7);
        assertThat(p2.path("imageDigest").asText()).startsWith("sha256:");
        assertThat(p2.path("modules").get(0).path("maxUsers").asInt()).isEqualTo(25);

        // The earlier CORE licence is suspended by the re-issue; the org now has exactly two rows.
        JsonNode list = licensesOf(token, orgId);
        assertThat(list.size()).isEqualTo(2);
        assertThat(byId(list, id).path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(byId(list, second.path("id").asText()).path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("issue stamps the org's commercial entitlements (TTL, version cap, topology) into the licence and bundle")
    void issueCarriesEntitlements() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-lic-ent").path("id").asText();
        assertThat(status(patch(ORGS + "/" + orgId + "/entitlements", token, Map.of(
            "entitledVersion", "2.4.0", "licenseTtlDays", 7, "maxInstances", 2,
            "deploymentTier", "SINGLE_NODE")))).isEqualTo(200);

        LocalDateTime before = LocalDateTime.now();
        JsonNode lic = issue(token, orgId, "PORTFOLIO_MANAGEMENT", null);
        assertThat(lic.path("maxVersion").asText()).isEqualTo("2.4.0");
        assertThat(lic.path("deploymentTier").asText()).isEqualTo("SINGLE_NODE");
        assertThat(lic.path("maxInstances").asInt()).isEqualTo(2);
        LocalDateTime expires = LocalDateTime.parse(lic.path("expiresAt").asText());
        assertThat(expires).isAfter(before.plusDays(6)).isBefore(before.plusDays(8));

        JsonNode payload = verifiedPayload(licenseRepo.findById(UUID.fromString(lic.path("id").asText()))
            .orElseThrow().getBundleJson());
        assertThat(payload.path("maxVersion").asText()).isEqualTo("2.4.0");
        assertThat(payload.path("deploymentTier").asText()).isEqualTo("SINGLE_NODE");
        assertThat(payload.path("maxInstances").asInt()).isEqualTo(2);

        // An explicit maxVersion on the request overrides the org default.
        JsonNode override = issue(token, orgId, "TRADE_MANAGEMENT", Map.of("maxVersion", "2.3.9"));
        assertThat(override.path("maxVersion").asText()).isEqualTo("2.3.9");
    }

    @Test
    @DisplayName("issue validates its body and refuses an unknown organization")
    void issueValidation() throws Exception {
        String token = adminToken();
        MvcResult empty = post(BASE + "/issue", token, Map.of());
        assertThat(status(empty)).isEqualTo(400);
        assertThat(code(empty)).isEqualTo("VALIDATION_ERROR");
        assertThat(body(empty).path("fieldErrors").has("organizationId")).isTrue();
        assertThat(body(empty).path("fieldErrors").has("moduleName")).isTrue();

        MvcResult blank = post(BASE + "/issue", token, Map.of("organizationId", APEX, "moduleName", "  "));
        assertThat(status(blank)).isEqualTo(400);
        assertThat(body(blank).path("fieldErrors").has("moduleName")).isTrue();

        UUID ghost = UUID.randomUUID();
        MvcResult unknown = post(BASE + "/issue", token, Map.of("organizationId", ghost, "moduleName", "CORE"));
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("Organization not found").contains(ghost.toString());
    }

    // ── issue-bulk ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue-bulk writes one row per module and returns a single verified multi-module bundle with its SHA-256")
    void issueBulk() throws Exception {
        String token = adminToken();
        JsonNode org = createOrg(token, "it-bulk");
        String orgId = org.path("id").asText();

        MvcResult r = post(BASE + "/issue-bulk", token, Map.of(
            "organizationId", orgId, "moduleNames", List.of("MUTUAL_FUND", "RISK_ENGINE"),
            "expiresAt", "2030-12-31T00:00:00", "maxUsers", 40, "graceDays", 3));
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("LICENSES_ISSUED");
        JsonNode bundle = data(r);
        assertThat(bundle.path("orgId").asText()).isEqualTo(orgId);
        assertThat(bundle.path("moduleName").asText()).isEqualTo("MUTUAL_FUND,RISK_ENGINE");
        assertThat(bundle.path("generatedAt").isTextual()).isTrue();
        String payloadJson = bundle.path("payload").asText();
        assertThat(bundle.path("integrity").asText()).isEqualTo(sha256Hex(payloadJson));

        JsonNode payload = verifiedPayload(payloadJson);
        assertThat(payload.path("licenseId").asText()).isEqualTo(bundle.path("licenseId").asText());
        assertThat(payload.path("customer").asText()).isEqualTo(org.path("name").asText());
        assertThat(payload.path("expiresAt").asText()).isEqualTo("2030-12-31T00:00:00Z");
        assertThat(payload.path("maxUsers").asInt()).isEqualTo(40);
        assertThat(payload.path("graceDays").asInt()).isEqualTo(3);
        assertThat(payload.path("modules").size()).isEqualTo(2);
        assertThat(payload.path("modules").get(0).path("moduleId").asText()).isEqualTo("MUTUAL_FUND");
        assertThat(payload.path("modules").get(1).path("moduleId").asText()).isEqualTo("RISK_ENGINE");

        JsonNode list = licensesOf(token, orgId);
        assertThat(list.size()).isEqualTo(2);
        for (JsonNode l : list) {
            assertThat(l.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(l.path("deliveryStatus").asText()).isEqualTo("PENDING");
            assertThat(l.path("expiresAt").asText()).startsWith("2030-12-31T00:00");
            assertThat(l.path("issuedBy").asText()).isEqualTo(ADMIN);
            // Every row carries the same combined bundle.
            assertThat(licenseRepo.findById(UUID.fromString(l.path("id").asText())).orElseThrow().getBundleJson())
                .isEqualTo(payloadJson);
        }
        JsonNode primary = byId(list, bundle.path("licenseId").asText());
        assertThat(primary.path("moduleName").asText()).isEqualTo("MUTUAL_FUND");

        UUID ghost = UUID.randomUUID();
        MvcResult unknown = post(BASE + "/issue-bulk", token, Map.of("organizationId", ghost, "moduleNames", List.of("CORE")));
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("Organization not found");
    }

    @Test
    @DisplayName("issue-bulk with an empty module list is refused with a 400 before any row is written")
    void issueBulkEmptyModulesIs400() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-bulk-empty").path("id").asText();
        MvcResult r = post(BASE + "/issue-bulk", token, Map.of("organizationId", orgId, "moduleNames", List.of()));
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r).path("message").asText()).contains("No licenses provided");
        assertThat(licensesOf(token, orgId).size()).isZero();
    }

    // ── deactivate / stats / expiring ───────────────────────────────────────

    @Test
    @DisplayName("deactivate suspends the licence and the stats counters move with it")
    void deactivateAndStats() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-deact").path("id").asText();

        JsonNode s0 = data(get(BASE + "/stats", token));
        long active0 = s0.path("active").asLong(), suspended0 = s0.path("suspended").asLong(),
             expiring0 = s0.path("expiringSoon").asLong();
        assertThat(s0.path("expired").asLong()).as("FNB RISK_ENGINE is seeded EXPIRED").isGreaterThanOrEqualTo(1);
        assertThat(expiring0).as("Apex + Coronation seeds").isGreaterThanOrEqualTo(5);

        String soon = LocalDateTime.now().plusDays(5).withNano(0).toString();
        JsonNode lic = issue(token, orgId, "COMPLIANCE", Map.of("expiresAt", soon));
        JsonNode s1 = data(get(BASE + "/stats", token));
        assertThat(s1.path("active").asLong()).isEqualTo(active0 + 1);
        assertThat(s1.path("expiringSoon").asLong()).isEqualTo(expiring0 + 1);

        MvcResult r = post(BASE + "/" + lic.path("id").asText() + "/deactivate", token, null);
        assertThat(status(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("LICENSE_DEACTIVATED");
        assertThat(data(r).path("status").asText()).isEqualTo("SUSPENDED");
        assertThat(byId(licensesOf(token, orgId), lic.path("id").asText()).path("status").asText()).isEqualTo("SUSPENDED");

        JsonNode s2 = data(get(BASE + "/stats", token));
        assertThat(s2.path("active").asLong()).isEqualTo(active0);
        assertThat(s2.path("suspended").asLong()).isEqualTo(suspended0 + 1);
        assertThat(s2.path("expiringSoon").asLong()).as("suspended rows are not 'expiring'").isEqualTo(expiring0);

        MvcResult unknown = post(BASE + "/" + UUID.randomUUID() + "/deactivate", token, null);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("License not found");
    }

    @Test
    @DisplayName("expiring-soon lists ACTIVE licences due within 30 days — the Apex and Coronation seeds — and nothing later")
    void expiringSoon() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-expiring").path("id").asText();
        String in5d = LocalDateTime.now().plusDays(5).withNano(0).toString();
        String in60d = LocalDateTime.now().plusDays(60).withNano(0).toString();
        String soonId = issue(token, orgId, "CORE", Map.of("expiresAt", in5d)).path("id").asText();
        String laterId = issue(token, orgId, "RISK_ENGINE", Map.of("expiresAt", in60d)).path("id").asText();

        MvcResult r = get(BASE + "/expiring-soon", token);
        assertThat(status(r)).isEqualTo(200);
        JsonNode list = data(r);
        LocalDateTime cutoff = LocalDateTime.now().plusDays(30).plusMinutes(1);
        int apex = 0, coronation = 0;
        boolean soonPresent = false, laterPresent = false;
        for (JsonNode l : list) {
            assertThat(l.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(LocalDateTime.parse(l.path("expiresAt").asText())).isBefore(cutoff);
            String org = l.path("organizationId").asText();
            if (APEX.equals(org)) apex++;
            if (CORONATION.equals(org)) coronation++;
            if (soonId.equals(l.path("id").asText())) soonPresent = true;
            if (laterId.equals(l.path("id").asText())) laterPresent = true;
        }
        assertThat(apex).as("Apex: MUTUAL_FUND, PORTFOLIO_MANAGEMENT, TRADE_MANAGEMENT at +10d").isGreaterThanOrEqualTo(3);
        assertThat(coronation).as("Coronation: MUTUAL_FUND, RISK_ENGINE at +25d").isGreaterThanOrEqualTo(2);
        assertThat(soonPresent).isTrue();
        assertThat(laterPresent).isFalse();

        MvcResult all = get(BASE, token);
        assertThat(status(all)).isEqualTo(200);
        assertThat(data(all).size()).as("14 seeded rows plus fixtures").isGreaterThanOrEqualTo(list.size());
        for (JsonNode l : data(all)) assertThat(l.has("bundleJson")).isFalse();
    }

    // ── fingerprint ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("fingerprint is the base64url SHA-256 of 'orgId:module', deterministic, and both params are required")
    void fingerprint() throws Exception {
        String token = adminToken();
        MvcResult a = get(BASE + "/fingerprint?orgId=" + APEX + "&moduleName=CORE", token);
        assertThat(status(a)).isEqualTo(200);
        assertThat(data(a).path("orgId").asText()).isEqualTo(APEX);
        assertThat(data(a).path("moduleName").asText()).isEqualTo("CORE");
        String fp = data(a).path("fingerprint").asText();
        byte[] h = MessageDigest.getInstance("SHA-256").digest((APEX + ":CORE").getBytes(StandardCharsets.UTF_8));
        assertThat(fp).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(h));

        MvcResult b = get(BASE + "/fingerprint?orgId=" + APEX + "&moduleName=CORE", token);
        assertThat(data(b).path("fingerprint").asText()).isEqualTo(fp);
        MvcResult other = get(BASE + "/fingerprint?orgId=" + APEX + "&moduleName=RISK_ENGINE", token);
        assertThat(data(other).path("fingerprint").asText()).isNotEqualTo(fp);

        MvcResult noModule = get(BASE + "/fingerprint?orgId=" + APEX, token);
        assertThat(status(noModule)).isEqualTo(400);
        assertThat(code(noModule)).isEqualTo("MISSING_PARAMETER");
        assertThat(body(noModule).path("message").asText()).contains("moduleName");
        MvcResult noOrg = get(BASE + "/fingerprint?moduleName=CORE", token);
        assertThat(status(noOrg)).isEqualTo(400);
        assertThat(code(noOrg)).isEqualTo("MISSING_PARAMETER");
        MvcResult badOrg = get(BASE + "/fingerprint?orgId=nope&moduleName=CORE", token);
        assertThat(status(badOrg)).isEqualTo(400);
        assertThat(code(badOrg)).isEqualTo("INVALID_PARAMETER");
    }

    // ── integrity / activation ──────────────────────────────────────────────

    @Test
    @DisplayName("verify-integrity tracks the activation hash; activate-json records it; verify-all totals reconcile")
    void integrityAndActivation() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-integrity").path("id").asText();

        JsonNode all0 = data(post(BASE + "/verify-all", token, null));
        long total0 = all0.path("total").asLong(), valid0 = all0.path("valid").asLong();
        assertThat(total0).isEqualTo(valid0 + all0.path("missing").asLong());
        assertThat(total0).isGreaterThanOrEqualTo(14);

        String id = issue(token, orgId, "CORE", null).path("id").asText();
        JsonNode all1 = data(post(BASE + "/verify-all", token, null));
        assertThat(all1.path("total").asLong()).isEqualTo(total0 + 1);
        assertThat(all1.path("valid").asLong()).as("signed at issue, so it has a bundle").isEqualTo(valid0 + 1);

        MvcResult v0 = post(BASE + "/" + id + "/verify-integrity", token, null);
        assertThat(status(v0)).isEqualTo(200);
        assertThat(data(v0).path("licenseId").asText()).isEqualTo(id);
        assertThat(data(v0).path("valid").asBoolean()).isFalse();
        assertThat(data(v0).path("message").asText()).isEqualTo("No license file hash recorded");

        String licenseJson = "{\"format\":\"zgate-license-v2\",\"payload\":\"abc\",\"signature\":\"def\"}";
        MvcResult act = post(BASE + "/activate-json", token,
            Map.of("orgId", orgId, "moduleName", "CORE", "licenseJson", licenseJson));
        assertThat(status(act)).as(text(act)).isEqualTo(200);
        assertThat(code(act)).isEqualTo("LICENSE_ACTIVATED");
        assertThat(data(act).path("id").asText()).isEqualTo(id);
        assertThat(data(act).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(data(act).path("licenseFileHash").asText()).isEqualTo(sha256Hex(licenseJson));

        JsonNode v1 = data(post(BASE + "/" + id + "/verify-integrity", token, null));
        assertThat(v1.path("valid").asBoolean()).isTrue();
        assertThat(v1.path("message").asText()).isEqualTo("License integrity verified");

        MvcResult unknown = post(BASE + "/" + UUID.randomUUID() + "/verify-integrity", token, null);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("License not found");
    }

    @Test
    @DisplayName("activate-json for an org/module with no licence row is a 400")
    void activateJsonUnknownLicense() throws Exception {
        String token = adminToken();
        UUID ghost = UUID.randomUUID();
        MvcResult r = post(BASE + "/activate-json", token,
            Map.of("orgId", ghost, "moduleName", "NOPE", "licenseJson", "{}"));
        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r).path("message").asText()).contains("No license found").contains(ghost.toString()).contains("NOPE");

        // A real org, a module it does not hold.
        String orgId = createOrg(token, "it-act-none").path("id").asText();
        MvcResult none = post(BASE + "/activate-json", token,
            Map.of("orgId", orgId, "moduleName", "CORE", "licenseJson", "{}"));
        assertThat(status(none)).isEqualTo(400);
    }

    // ── generate-bundle ─────────────────────────────────────────────────────

    @Test
    @DisplayName("generate-bundle re-signs the licence, resets delivery to PENDING and returns the bundle with its digest")
    void generateBundle() throws Exception {
        String token = adminToken();
        JsonNode org = createOrg(token, "it-genbundle");
        String orgId = org.path("id").asText();
        String id = issue(token, orgId, "CORE", null).path("id").asText();

        // Pull once so delivery becomes DELIVERED, then prove regeneration resets it.
        assertThat(status(getWith(BASE + "/bundle", null, orgHeader(orgId)))).isEqualTo(200);
        assertThat(byId(licensesOf(token, orgId), id).path("deliveryStatus").asText()).isEqualTo("DELIVERED");

        MvcResult r = post(BASE + "/" + id + "/generate-bundle", token, null);
        assertThat(status(r)).as(text(r)).isEqualTo(200);
        assertThat(code(r)).isEqualTo("LICENSE_BUNDLE_GENERATED");
        JsonNode b = data(r);
        assertThat(b.path("licenseId").asText()).isEqualTo(id);
        assertThat(b.path("orgId").asText()).isEqualTo(orgId);
        assertThat(b.path("moduleName").asText()).isEqualTo("CORE");
        String payloadJson = b.path("payload").asText();
        assertThat(b.path("integrity").asText()).isEqualTo(sha256Hex(payloadJson));
        JsonNode payload = verifiedPayload(payloadJson);
        assertThat(payload.path("licenseId").asText()).isEqualTo(id);
        assertThat(payload.path("customer").asText()).isEqualTo(org.path("name").asText());

        assertThat(licenseRepo.findById(UUID.fromString(id)).orElseThrow().getBundleJson()).isEqualTo(payloadJson);
        assertThat(byId(licensesOf(token, orgId), id).path("deliveryStatus").asText()).isEqualTo("PENDING");

        MvcResult unknown = post(BASE + "/" + UUID.randomUUID() + "/generate-bundle", token, null);
        assertThat(status(unknown)).isEqualTo(400);
        assertThat(body(unknown).path("message").asText()).contains("License not found");
    }

    // ── M2M: bundle pull ────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /bundle is a raw, tokenless array for the header org: it flips PENDING to DELIVERED and stamps fetchedAt")
    void bundlePull() throws Exception {
        String token = adminToken();
        String orgId = createOrg(token, "it-pull").path("id").asText();

        MvcResult empty = getWith(BASE + "/bundle", null, orgHeader(orgId));
        assertThat(status(empty)).isEqualTo(200);
        assertThat(body(empty).isArray()).isTrue();
        assertThat(body(empty).size()).isZero();
        assertThat(empty.getResponse().getHeader("X-Api-Envelope")).as("@RawResponse").isNull();

        String coreId = issue(token, orgId, "CORE", null).path("id").asText();
        String riskId = issue(token, orgId, "RISK_ENGINE", null).path("id").asText();
        assertThat(status(post(BASE + "/" + riskId + "/deactivate", token, null))).isEqualTo(200);

        MvcResult r = getWith(BASE + "/bundle", null, orgHeader(orgId));
        assertThat(status(r)).isEqualTo(200);
        JsonNode arr = body(r);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).as("suspended licence excluded").isEqualTo(1);
        JsonNode d = arr.get(0);
        assertThat(d.path("licenseId").asText()).isEqualTo(coreId);
        assertThat(d.path("moduleName").asText()).isEqualTo("CORE");
        assertThat(d.path("deliveryStatus").asText()).isEqualTo("DELIVERED");
        assertThat(d.path("expiresAt").isTextual()).isTrue();
        JsonNode payload = verifiedPayload(d.path("bundleJson").asText());
        assertThat(payload.path("licenseId").asText()).isEqualTo(coreId);

        JsonNode row = byId(licensesOf(token, orgId), coreId);
        assertThat(row.path("deliveryStatus").asText()).isEqualTo("DELIVERED");
        assertThat(row.path("fetchedAt").isTextual()).isTrue();
        String fetchedAt = row.path("fetchedAt").asText();

        // A second poll still returns it (DELIVERED != ACTIVATED) without re-stamping fetchedAt.
        MvcResult again = getWith(BASE + "/bundle", null, orgHeader(orgId));
        assertThat(body(again).size()).isEqualTo(1);
        assertThat(body(again).get(0).path("deliveryStatus").asText()).isEqualTo("DELIVERED");
        assertThat(byId(licensesOf(token, orgId), coreId).path("fetchedAt").asText()).isEqualTo(fetchedAt);

        // Another org's poll sees nothing of ours; a bad header is a 400.
        assertThat(body(getWith(BASE + "/bundle", null, orgHeader(UUID.randomUUID().toString()))).size()).isZero();
        MvcResult bad = getWith(BASE + "/bundle", null, orgHeader("not-a-uuid"));
        assertThat(status(bad)).isEqualTo(400);
    }

    // ── M2M: status report ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /status-report binds to the header org (a body orgId is ignored), flips delivery and hides the bundle")
    void statusReport() throws Exception {
        String token = adminToken();
        String orgA = createOrg(token, "it-report-a").path("id").asText();
        String orgB = createOrg(token, "it-report-b").path("id").asText();
        String licA = issue(token, orgA, "CORE", null).path("id").asText();
        String licB = issue(token, orgB, "CORE", null).path("id").asText();

        // The body names org B; the header names org A. Only A must change.
        MvcResult r = postWith(BASE + "/status-report", null,
            Map.of("orgId", orgB, "moduleName", "CORE", "success", true, "orgModulesJson", "[{\"module\":\"CORE\",\"status\":\"ACTIVE\"}]"),
            orgHeader(orgA));
        assertThat(status(r)).isEqualTo(200);
        // ResponseEntity<Void> without @RawResponse: the advice still wraps it, so the body is an envelope with null data.
        assertThat(data(r).isNull()).as(text(r)).isTrue();

        JsonNode a = byId(licensesOf(token, orgA), licA);
        assertThat(a.path("deliveryStatus").asText()).isEqualTo("ACTIVATED");
        assertThat(a.path("orgActivatedAt").isTextual()).isTrue();
        assertThat(a.path("orgModules").asText()).contains("\"module\":\"CORE\"");
        JsonNode b = byId(licensesOf(token, orgB), licB);
        assertThat(b.path("deliveryStatus").asText()).as("body orgId ignored").isEqualTo("PENDING");
        assertThat(b.path("orgActivatedAt").isNull()).isTrue();

        // An activated licence is no longer offered on the pull route; B's still is.
        assertThat(body(getWith(BASE + "/bundle", null, orgHeader(orgA))).size()).isZero();
        assertThat(body(getWith(BASE + "/bundle", null, orgHeader(orgB))).size()).isEqualTo(1);

        // A failure report is recorded as FAILED; an unknown module is a silent no-op.
        assertThat(status(postWith(BASE + "/status-report", null,
            Map.of("moduleName", "CORE", "success", false), orgHeader(orgB)))).isEqualTo(200);
        assertThat(byId(licensesOf(token, orgB), licB).path("deliveryStatus").asText()).isEqualTo("FAILED");
        assertThat(status(postWith(BASE + "/status-report", null,
            Map.of("moduleName", "NOT_A_MODULE", "success", true), orgHeader(orgB)))).isEqualTo(200);
        assertThat(status(postWith(BASE + "/status-report", null,
            Map.of("moduleName", "CORE", "success", true), orgHeader(UUID.randomUUID().toString())))).isEqualTo(200);
    }

    // ── role gating ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("any operator may read licences and run integrity checks; issuing, deactivating and bundling need ADMIN")
    void roleGating() throws Exception {
        String root = adminToken();
        String orgId = createOrg(root, "it-lic-roles").path("id").asText();
        String id = issue(root, orgId, "CORE", null).path("id").asText();
        String viewer = tokenFor(root, "VIEWER");

        assertThat(status(get(BASE, viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/org/" + orgId, viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/stats", viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/expiring-soon", viewer))).isEqualTo(200);
        assertThat(status(get(BASE + "/fingerprint?orgId=" + orgId + "&moduleName=CORE", viewer))).isEqualTo(200);
        assertThat(status(post(BASE + "/" + id + "/verify-integrity", viewer, null))).isEqualTo(200);
        assertThat(status(post(BASE + "/verify-all", viewer, null))).isEqualTo(200);

        MvcResult issue = post(BASE + "/issue", viewer, Map.of("organizationId", orgId, "moduleName", "RISK_ENGINE"));
        assertThat(status(issue)).isEqualTo(403);
        assertThat(code(issue)).isEqualTo("ACCESS_DENIED");
        assertThat(status(post(BASE + "/issue-bulk", viewer, Map.of("organizationId", orgId, "moduleNames", List.of("X"))))).isEqualTo(403);
        assertThat(status(post(BASE + "/" + id + "/deactivate", viewer, null))).isEqualTo(403);
        assertThat(status(post(BASE + "/" + id + "/generate-bundle", viewer, null))).isEqualTo(403);
        assertThat(status(post(BASE + "/activate-json", viewer, Map.of("orgId", orgId, "moduleName", "CORE", "licenseJson", "{}")))).isEqualTo(403);
        assertThat(byId(licensesOf(root, orgId), id).path("status").asText()).isEqualTo("ACTIVE");

        assertThat(status(get(BASE, null))).isEqualTo(401);
    }
}
