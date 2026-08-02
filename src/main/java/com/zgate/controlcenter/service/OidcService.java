package com.zgate.controlcenter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.service.provisioning.SecretCipher;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenID Connect sign-in for operators, so the console can sit behind the company IdP instead of
 * its own passwords.
 *
 * <p>Ported from {@code com.zgate.core.service.OidcClientService}, keeping its ID-token validation
 * (which is the part that decides whether to trust a caller) and simplifying the rest: the main
 * product supports a provider per tenant, whereas this console has exactly one vendor IdP, so the
 * provider comes from configuration rather than a database row.
 *
 * <p><b>Sign-in does not create operators.</b> The token must map to an account that already exists
 * here, unless auto-provisioning is explicitly enabled with a role. Without that rule, anyone your
 * IdP can authenticate — every employee, every contractor, every guest in the tenant — would obtain
 * access to the console that provisions and destroys customer production systems.
 *
 * <p>Local password sign-in stays available alongside it, deliberately: a misconfigured issuer or an
 * IdP outage must not lock the vendor out of their own fleet.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OidcService {

    private static final String STATE_PURPOSE = "oidc-state";

    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    private final WebhookUrlValidator urlValidator;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    /** jwksUri -> (kid -> key). Providers rotate; an unknown kid forces one refetch. */
    private final Map<String, Map<String, PublicKey>> jwksCache = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    @Value("${controlcenter.oidc.enabled:false}")
    private boolean enabled;

    @Value("${controlcenter.oidc.issuer:}")
    private String issuer;

    @Value("${controlcenter.oidc.clientId:}")
    private String clientId;

    @Value("${controlcenter.oidc.clientSecret:}")
    private String clientSecret;

    @Value("${controlcenter.oidc.redirectUri:}")
    private String redirectUri;

    @Value("${controlcenter.oidc.scopes:openid email profile}")
    private String scopes;

    @Value("${controlcenter.oidc.emailClaim:email}")
    private String emailClaim;

    /** Discovery result, cached for the process. */
    private volatile Discovery discovery;

    public record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint,
                            String jwksUri) {}

    public record OidcIdentity(String subject, String email, boolean emailVerified) {}

    /**
     * An authorization redirect plus the per-browser secret that must come back with the callback.
     * The caller sets {@code browserSecret} as an HttpOnly cookie; it never appears in a URL.
     */
    public record AuthorizationRequest(String authorizationUrl, String browserSecret) {}

    public boolean isEnabled() {
        return enabled && !issuer.isBlank() && !clientId.isBlank() && !redirectUri.isBlank();
    }

    // ── Authorization request ───────────────────────────────────────────────

    /**
     * The URL to send the operator to, plus the signed state that ties the callback to it.
     *
     * <p>State and nonce are carried in an HMAC-signed, short-lived blob rather than a server-side
     * session: this console is stateless and may run several replicas, and a state stored in one
     * replica's memory would fail whenever the callback landed on another.
     */
    public AuthorizationRequest authorizationRequest() {
        requireEnabled();
        Discovery d = discovery();

        // One random secret per sign-in, held ONLY in an HttpOnly cookie. Everything else that
        // needs to be unguessable is derived from it, so the redirect URL — which ends up in
        // browser history, referrers, proxy logs and the IdP's own logs — is never on its own
        // sufficient to complete a sign-in.
        String browserSecret = randomToken();
        String state = signState(browserSecret);

        String url = d.authorizationEndpoint()
            + (d.authorizationEndpoint().contains("?") ? "&" : "?")
            + "response_type=code"
            + "&client_id=" + enc(clientId)
            + "&redirect_uri=" + enc(redirectUri)
            + "&scope=" + enc(scopes)
            + "&state=" + enc(state)
            + "&nonce=" + enc(nonceFor(browserSecret))
            + "&code_challenge=" + enc(codeChallengeFor(browserSecret))
            + "&code_challenge_method=S256";
        return new AuthorizationRequest(url, browserSecret);
    }

    // ── Callback ────────────────────────────────────────────────────────────

    /** Exchange the code, validate the ID token, and return the verified identity. */
    public OidcIdentity exchangeCode(String code, String state, String browserSecret) {
        requireEnabled();
        if (code == null || code.isBlank()) {
            throw unauthorized("The identity provider returned no authorization code.");
        }
        // The cookie is what ties this callback to the browser that started the sign-in. Without
        // it, anyone who obtained a (code, state) pair — by observing the redirect, or by minting
        // a state themselves and mailing the link to an operator — could complete a sign-in.
        if (browserSecret == null || browserSecret.isBlank()) {
            throw unauthorized("This sign-in did not start in this browser. Start again from the "
                             + "sign-in page.");
        }
        requireStateMatchesBrowser(state, browserSecret);
        Discovery d = discovery();

        String form = "grant_type=authorization_code"
            + "&code=" + enc(code)
            + "&redirect_uri=" + enc(redirectUri)
            + "&client_id=" + enc(clientId)
            + "&code_verifier=" + enc(codeVerifierFor(browserSecret))
            + (clientSecret.isBlank() ? "" : "&client_secret=" + enc(clientSecret));

        JsonNode token;
        try {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(d.tokenEndpoint()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                // Never echo the provider's body: it can contain the code or client secret.
                log.warn("OIDC token exchange returned HTTP {}", resp.statusCode());
                throw unauthorized("The identity provider rejected the sign-in.");
            }
            token = mapper.readTree(resp.body());
        } catch (ControlCenterException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OIDC token exchange failed: {}", e.getClass().getSimpleName());
            throw unauthorized("Could not reach the identity provider.");
        }

        String idToken = token.path("id_token").asText(null);
        if (idToken == null || idToken.isBlank()) {
            throw unauthorized("The identity provider returned no ID token.");
        }
        return verifyIdToken(idToken, nonceFor(browserSecret));
    }

    /**
     * Validate an ID token and extract the identity. Each check is load-bearing:
     * <ol>
     *   <li><b>Signature</b> against the issuer's JWKS — without it every other claim is
     *       attacker-controlled. The key is chosen by {@code kid}; an unknown kid forces ONE
     *       refetch (providers rotate) and then fails, rather than falling back to "try any key".
     *   <li><b>Algorithm</b>: asymmetric only. Verifying with a PublicKey means an HMAC token
     *       signed with that public key — the classic algorithm-confusion attack — cannot verify,
     *       and jjwt refuses {@code alg: none} outright.
     *   <li><b>Issuer</b> matches the configured one, so another provider's token is not accepted.
     *   <li><b>Audience</b> contains our client id, so a token minted for a different relying party
     *       cannot be replayed here.
     *   <li><b>Expiry</b>, with a small clock-skew allowance.
     *   <li><b>Nonce</b> matches the one issued with this redirect, which is what stops an ID token
     *       from an earlier sign-in being replayed.
     * </ol>
     */
    public OidcIdentity verifyIdToken(String idToken, String expectedNonce) {
        String kid = unverifiedKid(idToken);
        PublicKey key = resolveKey(kid, false)
            .or(() -> {
                try {
                    return resolveKey(kid, true);
                } catch (RuntimeException e) {
                    log.warn("JWKS refetch failed: {}", e.getClass().getSimpleName());
                    return Optional.empty();
                }
            })
            .orElseThrow(() -> unauthorized("The identity provider's signing key could not be resolved."));

        Claims claims;
        try {
            claims = Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(idToken)
                .getPayload();
        } catch (Exception e) {
            log.warn("ID token rejected: {}", e.getClass().getSimpleName());
            throw unauthorized("The identity provider's token could not be verified.");
        }

        if (!issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
            throw unauthorized("Token issuer does not match the configured provider.");
        }
        if (!audienceContains(claims, clientId)) {
            throw unauthorized("Token was not issued for this application.");
        }
        // A multi-audience token MUST name us as the authorized party. On providers supporting
        // resource indicators another registered client can obtain a token that merely *contains*
        // our client id in `aud`; azp is what distinguishes "issued to us" from "mentions us".
        if (isMultiAudience(claims)) {
            Object azp = claims.get("azp");
            if (azp == null || !clientId.equals(String.valueOf(azp))) {
                throw unauthorized("Token was not issued for this application.");
            }
        }
        if (expectedNonce != null && !expectedNonce.equals(str(claims.get("nonce")))) {
            throw unauthorized("Token nonce does not match this sign-in attempt.");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw unauthorized("Token carries no subject.");
        }

        Object verified = claims.get("email_verified");
        return new OidcIdentity(subject, str(claims.get(emailClaim)),
            Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(str(verified)));
    }

    // ── State signing ───────────────────────────────────────────────────────

    private String signState(String browserSecret) {
        long ts = Instant.now().getEpochSecond();
        // Only the HASH of the secret travels in the URL, so a captured state does not yield the
        // secret (and therefore yields neither the PKCE verifier nor the nonce).
        String body = sha256Hex(browserSecret) + "|" + ts;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((body + "|" + hmac(body)).getBytes(StandardCharsets.UTF_8));
    }

    /** Verify the state is ours, is fresh, and belongs to the browser presenting this cookie. */
    void requireStateMatchesBrowser(String state, String browserSecret) {
        if (state == null || state.isBlank()) {
            throw unauthorized("Missing sign-in state.");
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized("Malformed sign-in state.");
        }
        String[] parts = decoded.split("\\|");
        if (parts.length != 3) {
            throw unauthorized("Malformed sign-in state.");
        }
        String body = parts[0] + "|" + parts[1];
        if (!constantTimeEquals(hmac(body), parts[2])) {
            throw unauthorized("Sign-in state failed verification.");
        }
        long age;
        try {
            age = Instant.now().getEpochSecond() - Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw unauthorized("Malformed sign-in state.");
        }
        if (age < -60 || age > 600) {
            throw unauthorized("This sign-in attempt expired — start again.");
        }
        if (!constantTimeEquals(sha256Hex(browserSecret), parts[0])) {
            throw unauthorized("This sign-in did not start in this browser. Start again from the "
                             + "sign-in page.");
        }
    }

    /** The nonce sent to the provider, derived so it is never guessable from the redirect URL. */
    private String nonceFor(String browserSecret) {
        return hmac("nonce|" + browserSecret);
    }

    /** RFC 7636 code_verifier: 64 hex chars, inside the required 43–128 unreserved range. */
    private String codeVerifierFor(String browserSecret) {
        return hmac("pkce|" + browserSecret);
    }

    private String codeChallengeFor(String browserSecret) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(codeVerifierFor(browserSecret).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String body) {
        if (cipher.isConfigured()) {
            return cipher.hmacHex(body, STATE_PURPOSE);
        }
        throw new ControlCenterException(
            "Single sign-on needs controlcenter.provisioning.encryptionKey configured — the "
          + "sign-in state is signed with a key derived from it.",
            "OIDC_KEY_REQUIRED", HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Discovery / JWKS ────────────────────────────────────────────────────

    Discovery discovery() {
        Discovery d = discovery;
        if (d != null) return d;
        synchronized (this) {
            if (discovery != null) return discovery;
            String base = issuer.replaceAll("/+$", "");
            requireSafeUrl(base, "issuer");
            String url = base + "/.well-known/openid-configuration";
            try {
                HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                JsonNode doc = mapper.readTree(resp.body());

                // OIDC Discovery 4.3: the document's own issuer MUST equal the one we asked about.
                // Skipping it is what lets a substituted document redirect the token exchange (and
                // with it our client secret) and the JWKS to an attacker's host.
                String declared = doc.path("issuer").asText(null);
                if (declared == null || !declared.replaceAll("/+$", "").equals(base)) {
                    throw new ControlCenterException(
                        "The identity provider's discovery document declares a different issuer.",
                        "OIDC_ISSUER_MISMATCH", HttpStatus.SERVICE_UNAVAILABLE);
                }

                String authz = doc.path("authorization_endpoint").asText(null);
                String tokenEp = doc.path("token_endpoint").asText(null);
                String jwks = doc.path("jwks_uri").asText(null);
                if (authz == null || tokenEp == null || jwks == null) {
                    throw new ControlCenterException(
                        "The issuer's discovery document is missing required endpoints.",
                        "OIDC_DISCOVERY_INCOMPLETE", HttpStatus.SERVICE_UNAVAILABLE);
                }
                // Endpoints come from the document, so they are only as trustworthy as it is. They
                // are NOT required to share the issuer's host — Google and Microsoft both split
                // them — so validate what actually matters: https, and not an internal address.
                requireSafeUrl(authz, "authorization_endpoint");
                requireSafeUrl(tokenEp, "token_endpoint");
                requireSafeUrl(jwks, "jwks_uri");

                discovery = new Discovery(declared, authz, tokenEp, jwks);
                return discovery;
            } catch (ControlCenterException e) {
                throw e;
            } catch (Exception e) {
                // Log the URL; do NOT return it — /authorize is anonymous, and the internal issuer
                // host is not something to hand to whoever asks.
                log.warn("OIDC discovery failed for {}: {}", url, e.toString());
                throw new ControlCenterException(
                    "Could not reach the identity provider.",
                    "OIDC_DISCOVERY_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }

    /**
     * https, publicly routable, and not this host. The console holds the Terraform state keys and
     * the licence signing key, so an issuer pointed at 169.254.169.254 or a loopback port is a
     * credential-exfiltration primitive, not a misconfiguration.
     */
    private void requireSafeUrl(String url, String what) {
        try {
            urlValidator.validateStrict(url);
        } catch (RuntimeException e) {
            log.error("OIDC {} rejected: {} — {}", what, url, e.getMessage());
            throw new ControlCenterException(
                "The identity provider's " + what + " is not an acceptable URL.",
                "OIDC_URL_REJECTED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private Optional<PublicKey> resolveKey(String kid, boolean forceRefresh) {
        String jwksUri = discovery().jwksUri();
        Map<String, PublicKey> keys = jwksCache.get(jwksUri);
        if (keys == null || forceRefresh) {
            // Fetch FIRST and replace only on success. Evicting up front meant a flaky JWKS
            // endpoint during a real key rotation left the replica with no keys at all — one
            // unknown-kid token turning a transient failure into "nobody can sign in".
            Map<String, PublicKey> fetched = fetchJwks(jwksUri);
            jwksCache.put(jwksUri, fetched);
            keys = fetched;
        }
        if (kid == null) {
            // No kid: only unambiguous when the provider publishes exactly one key.
            return keys.size() == 1 ? keys.values().stream().findFirst() : Optional.empty();
        }
        return Optional.ofNullable(keys.get(kid));
    }

    // Package-private so tests can stub it: reaching the real network from a unit test
    // turns a wildcard DNS resolver or captive portal into a hang or a false pass.
    Map<String, PublicKey> fetchJwks(String jwksUri) {
        Map<String, PublicKey> out = new HashMap<>();
        try {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(jwksUri)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            for (JsonNode k : mapper.readTree(resp.body()).path("keys")) {
                if (!"RSA".equals(k.path("kty").asText())) continue;
                // A key published for encryption is not a signing key; loading it as one would let
                // a token signed with it verify.
                String use = k.path("use").asText("");
                if (!use.isEmpty() && !"sig".equals(use)) continue;
                String kid = k.path("kid").asText("");
                if (kid.isEmpty()) continue;   // kid-less keys would all collapse onto one entry
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
                out.put(kid, KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
            }
        } catch (Exception e) {
            throw new ControlCenterException("Could not read the identity provider's signing keys.",
                "OIDC_JWKS_FAILED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return out;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Read `kid` from the header WITHOUT trusting the token — it only selects which key to verify with. */
    private String unverifiedKid(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return null;
            JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            String kid = header.path("kid").asText(null);
            return kid == null || kid.isBlank() ? null : kid;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMultiAudience(Claims claims) {
        Object aud = claims.get("aud");
        if (aud instanceof java.util.Collection<?> c) return c.size() > 1;
        if (aud instanceof Iterable<?> it) {
            int n = 0;
            for (Object ignored : it) if (++n > 1) return true;
        }
        return false;
    }

    private static boolean audienceContains(Claims claims, String clientId) {
        Object aud = claims.get("aud");
        if (aud instanceof String s) return s.equals(clientId);
        if (aud instanceof Iterable<?> it) {
            for (Object o : it) if (clientId.equals(String.valueOf(o))) return true;
        }
        return false;
    }

    private String randomToken() {
        byte[] b = new byte[24];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new ControlCenterException(
                "Single sign-on is not configured. Set controlcenter.oidc.issuer, .clientId and "
              + ".redirectUri, then enable it.", "OIDC_DISABLED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static ControlCenterException unauthorized(String message) {
        return new ControlCenterException(message, "OIDC_TOKEN_INVALID", HttpStatus.UNAUTHORIZED);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
