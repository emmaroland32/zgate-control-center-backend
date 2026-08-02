package com.zgate.controlcenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.service.WebhookUrlValidator;
import com.zgate.controlcenter.service.provisioning.SecretCipher;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ID-token validation, ported from the main product's {@code OidcIdTokenValidationTest}.
 *
 * <p>Every case here is a way an attacker gets a token accepted that should not be. This console
 * provisions and destroys customer production systems, so a forged sign-in is the highest-value
 * target it has — and the failure is silent, because a wrongly-accepted token looks exactly like a
 * legitimate one in the audit trail.
 */
class OidcServiceTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String CLIENT_ID = "zgate-control-center";
    private static final String KID = "test-key-1";

    private KeyPair keys;
    private KeyPair otherKeys;
    private OidcService oidc;
    private int fetches;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        otherKeys = gen.generateKeyPair();

        SecretCipher cipher = new SecretCipher(
            java.util.Base64.getEncoder().encodeToString("cc-oidc-test-key-32-bytes-long!!".getBytes()),
            "", "v1");
        ReflectionTestUtils.invokeMethod(cipher, "init");   // @PostConstruct builds the keyring
        oidc = new OidcService(new ObjectMapper(), cipher, new WebhookUrlValidator()) {
            @Override
            java.util.Map<String, java.security.PublicKey> fetchJwks(String jwksUri) {
                fetches++;
                return Map.of(KID, keys.getPublic());
            }
        };
        ReflectionTestUtils.setField(oidc, "enabled", true);
        ReflectionTestUtils.setField(oidc, "issuer", ISSUER);
        ReflectionTestUtils.setField(oidc, "clientId", CLIENT_ID);
        ReflectionTestUtils.setField(oidc, "redirectUri", "https://cc.example.com/callback");
        ReflectionTestUtils.setField(oidc, "emailClaim", "email");
        ReflectionTestUtils.setField(oidc, "scopes", "openid email profile");

        // Skip the network: seed discovery and the JWKS cache directly.
        ReflectionTestUtils.setField(oidc, "discovery", new OidcService.Discovery(
            ISSUER, ISSUER + "/authorize", ISSUER + "/token", ISSUER + "/jwks"));
        var cache = (ConcurrentHashMap<String, Map<String, java.security.PublicKey>>)
            ReflectionTestUtils.getField(oidc, "jwksCache");
        cache.put(ISSUER + "/jwks", Map.of(KID, keys.getPublic()));
    }

    private static String param(String url, String name) {
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] p = kv.split("=", 2);
            if (p[0].equals(name)) {
                return java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String stateFrom(String url) { return param(url, "state"); }
    private static String nonceFrom(String url) { return param(url, "nonce"); }

    private io.jsonwebtoken.JwtBuilder token() {
        return Jwts.builder()
            .header().keyId(KID).and()
            .issuer(ISSUER)
            .subject("operator-subject-123")
            .audience().add(CLIENT_ID).and()
            .claim("email", "ops@example.com")
            .claim("email_verified", true)
            .claim("nonce", "the-nonce")
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(300)));
    }

    @Nested
    @DisplayName("a token is accepted only when every claim checks out")
    class HappyPath {
        @Test
        @DisplayName("a correctly signed, addressed and nonced token yields the identity")
        void acceptsValidToken() {
            var id = oidc.verifyIdToken(token().signWith(keys.getPrivate()).compact(), "the-nonce");
            assertThat(id.subject()).isEqualTo("operator-subject-123");
            assertThat(id.email()).isEqualTo("ops@example.com");
            assertThat(id.emailVerified()).isTrue();
        }

        @Test
        @DisplayName("a multi-audience token is accepted only when azp names us")
        void acceptsMultiAudienceWithAzp() {
            // OIDC Core 3.1.3.7: with several audiences, azp MUST be present and MUST be us.
            String jwt = token().audience().add("some-other-app").and()
                .claim("azp", CLIENT_ID)
                .signWith(keys.getPrivate()).compact();
            assertThat(oidc.verifyIdToken(jwt, "the-nonce").subject()).isNotBlank();
        }

        @Test
        @DisplayName("email_verified=false is carried through, never assumed true")
        void carriesUnverifiedEmailThrough() {
            String jwt = token().claim("email_verified", false)
                .signWith(keys.getPrivate()).compact();
            // Verification passes — the CONTROLLER is what refuses to map an unverified address to
            // an operator. Silently upgrading it to true here would hide that decision.
            assertThat(oidc.verifyIdToken(jwt, "the-nonce").emailVerified()).isFalse();
        }
    }

    @Nested
    @DisplayName("forged and misdirected tokens are refused")
    class Rejections {
        @Test
        @DisplayName("signed with the wrong key — the signature is the whole basis of trust")
        void rejectsWrongSigningKey() {
            String jwt = token().signWith(otherKeys.getPrivate()).compact();
            // "could not be verified" is the SIGNATURE failure, distinct from "signing key could
            // not be resolved" — otherwise this test would pass on an unrelated JWKS problem.
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("could not be verified");
        }

        @Test
        @DisplayName("unsigned (alg=none) is refused")
        void rejectsUnsignedToken() {
            String jwt = token().compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("could not be verified");
        }

        @Test
        @DisplayName("algorithm confusion: HS256 signed with the RSA PUBLIC key is refused")
        void rejectsAlgorithmConfusion() {
            // The classic attack: take the provider's public key (which is public), use it as an
            // HMAC shared secret, and hope the verifier treats the token as symmetric. Verifying
            // with a PublicKey makes that structurally impossible.
            String jwt = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER).subject("attacker")
                .audience().add(CLIENT_ID).and()
                .claim("nonce", "the-nonce")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(new SecretKeySpec(keys.getPublic().getEncoded(), "HmacSHA256"))
                .compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("could not be verified");
        }

        @Test
        @DisplayName("a different issuer's token is refused even when correctly signed")
        void rejectsWrongIssuer() {
            String jwt = token().issuer("https://evil.example.com")
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("issuer does not match");
        }

        @Test
        @DisplayName("a token minted for another relying party cannot be replayed here")
        void rejectsWrongAudience() {
            String jwt = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER).subject("s")
                .audience().add("some-other-app").and()
                .claim("nonce", "the-nonce")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("not issued for this application");
        }

        @Test
        @DisplayName("a multi-audience token naming another authorized party is refused")
        void rejectsMultiAudienceWithForeignAzp() {
            // Another registered client obtained a token that merely CONTAINS our client id in aud.
            String jwt = token().audience().add("some-other-app").and()
                .claim("azp", "some-other-app")
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("not issued for this application");
        }

        @Test
        @DisplayName("a multi-audience token with no azp at all is refused")
        void rejectsMultiAudienceWithoutAzp() {
            String jwt = token().audience().add("some-other-app").and()
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("not issued for this application");
        }

        @Test
        @DisplayName("a token from an earlier sign-in is refused — the nonce must match")
        void rejectsReplayedNonce() {
            String jwt = token().claim("nonce", "an-older-nonce")
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("nonce does not match");
        }

        @Test
        @DisplayName("an expired token is refused")
        void rejectsExpiredToken() {
            String jwt = token().expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("could not be verified");
        }

        @Test
        @DisplayName("a token with no subject is refused — there is nobody to sign in")
        void rejectsMissingSubject() {
            String jwt = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ISSUER)
                .audience().add(CLIENT_ID).and()
                .claim("nonce", "the-nonce")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(keys.getPrivate()).compact();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("no subject");
        }

        @Test
        @DisplayName("an unknown kid is refused rather than retried against any available key")
        void rejectsUnknownKid() {
            String jwt = Jwts.builder()
                .header().keyId("some-other-kid").and()
                .issuer(ISSUER).subject("s")
                .audience().add(CLIENT_ID).and()
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(keys.getPrivate()).compact();
            // Exactly ONE refetch (providers rotate keys), and then it stops — it does NOT fall
            // back to trying whatever key happens to be cached, which would make kid meaningless.
            fetches = 0;
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, "the-nonce"))
                .hasMessageContaining("signing key could not be resolved");
            assertThat(fetches).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the sign-in state is bound to the browser that started it")
    class State {
        @Test
        @DisplayName("a state round-trips only when presented with ITS OWN browser secret")
        void roundTripsWithMatchingSecret() {
            var req = oidc.authorizationRequest();
            oidc.requireStateMatchesBrowser(stateFrom(req.authorizationUrl()), req.browserSecret());
        }

        @Test
        @DisplayName("another browser's secret is refused — this is what blocks forced sign-in")
        void rejectsMismatchedBrowser() {
            // The attack: an attacker signs in as themselves, stops at the redirect, and mails the
            // victim the callback URL. Their state is genuine and fresh — but the victim's browser
            // does not carry the attacker's cookie, so it cannot be completed there.
            var attacker = oidc.authorizationRequest();
            var victim = oidc.authorizationRequest();
            assertThatThrownBy(() -> oidc.requireStateMatchesBrowser(
                    stateFrom(attacker.authorizationUrl()), victim.browserSecret()))
                .hasMessageContaining("did not start in this browser");
        }

        @Test
        @DisplayName("the browser secret never appears in the redirect URL")
        void secretStaysOutOfTheUrl() {
            // Everything unguessable is DERIVED from the secret, so history, referrers, proxy logs
            // and the IdP's own logs never see enough to complete the sign-in.
            var req = oidc.authorizationRequest();
            assertThat(req.authorizationUrl()).doesNotContain(req.browserSecret());
        }

        @Test
        @DisplayName("the authorization URL carries state, nonce and a PKCE S256 challenge")
        void authorizationUrlIsComplete() {
            String url = oidc.authorizationRequest().authorizationUrl();
            assertThat(url)
                .startsWith(ISSUER + "/authorize?")
                .contains("response_type=code")
                .contains("client_id=" + CLIENT_ID)
                .contains("state=")
                .contains("nonce=")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256");
        }

        @Test
        @DisplayName("each sign-in gets a distinct secret, nonce and challenge")
        void everySignInIsDistinct() {
            var a = oidc.authorizationRequest();
            var b = oidc.authorizationRequest();
            assertThat(a.browserSecret()).isNotEqualTo(b.browserSecret());
            assertThat(nonceFrom(a.authorizationUrl())).isNotEqualTo(nonceFrom(b.authorizationUrl()));
            assertThat(a.authorizationUrl()).isNotEqualTo(b.authorizationUrl());
        }

        @Test
        @DisplayName("an ID token nonced for one browser is refused for another")
        void nonceIsBoundToTheBrowser() {
            // Pins the state->nonce plumbing: the nonce is derived from the browser secret, so a
            // token obtained in one sign-in cannot be completed against another.
            var a = oidc.authorizationRequest();
            var b = oidc.authorizationRequest();
            String jwt = token().claim("nonce", nonceFrom(a.authorizationUrl()))
                .signWith(keys.getPrivate()).compact();
            assertThat(oidc.verifyIdToken(jwt, nonceFrom(a.authorizationUrl())).subject()).isNotBlank();
            assertThatThrownBy(() -> oidc.verifyIdToken(jwt, nonceFrom(b.authorizationUrl())))
                .hasMessageContaining("nonce does not match");
        }

        @Test
        @DisplayName("a forged state is refused")
        void rejectsForgedState() {
            String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("deadbeef|" + Instant.now().getEpochSecond() + "|deadbeef")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> oidc.requireStateMatchesBrowser(forged, "whatever"))
                .hasMessageContaining("failed verification");
        }

        @Test
        @DisplayName("a stale state is refused, so an old redirect cannot be completed later")
        void rejectsStaleState() {
            SecretCipher cipher = (SecretCipher) ReflectionTestUtils.getField(oidc, "cipher");
            String body = "deadbeef|" + (Instant.now().getEpochSecond() - 7200);
            String stale = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (body + "|" + cipher.hmacHex(body, "oidc-state"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> oidc.requireStateMatchesBrowser(stale, "whatever"))
                .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("missing and malformed states are refused, not treated as absent-and-fine")
        void rejectsMissingState() {
            assertThatThrownBy(() -> oidc.requireStateMatchesBrowser(null, "s"))
                .hasMessageContaining("Missing");
            assertThatThrownBy(() -> oidc.requireStateMatchesBrowser("not-base64-!!!", "s"))
                .hasMessageContaining("Malformed");
        }

        @Test
        @DisplayName("a callback with no cookie is refused before the code is ever exchanged")
        void rejectsMissingBrowserSecret() {
            var req = oidc.authorizationRequest();
            assertThatThrownBy(() -> oidc.exchangeCode("some-code",
                    stateFrom(req.authorizationUrl()), null))
                .hasMessageContaining("did not start in this browser");
        }
    }

    @Nested
    @DisplayName("provider URLs are validated before anything is fetched from them")
    class UrlSafety {
        private OidcService withIssuer(String issuer) {
            OidcService svc = new OidcService(new ObjectMapper(), null, new WebhookUrlValidator());
            ReflectionTestUtils.setField(svc, "enabled", true);
            ReflectionTestUtils.setField(svc, "issuer", issuer);
            ReflectionTestUtils.setField(svc, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(svc, "redirectUri", "https://cc.example.com/callback");
            return svc;
        }

        @Test
        @DisplayName("a plain-http issuer is refused — a MITM could swap the discovery document")
        void rejectsHttpIssuer() {
            // Over http an attacker on the path substitutes the document, points token_endpoint at
            // their own host (receiving our client_secret) and jwks_uri at a key they hold — after
            // which they can mint a token that passes every check in verifyIdToken.
            assertThatThrownBy(() -> withIssuer("http://idp.example.com").discovery())
                .hasMessageContaining("not an acceptable URL");
        }

        @Test
        @DisplayName("an issuer pointing at the cloud metadata service is refused")
        void rejectsLinkLocalIssuer() {
            assertThatThrownBy(() -> withIssuer("https://169.254.169.254").discovery())
                .hasMessageContaining("not an acceptable URL");
        }

        @Test
        @DisplayName("an issuer pointing back at this host is refused")
        void rejectsLoopbackIssuer() {
            assertThatThrownBy(() -> withIssuer("https://127.0.0.1:8443").discovery())
                .hasMessageContaining("not an acceptable URL");
        }

        @Test
        @DisplayName("a private-range issuer is refused")
        void rejectsPrivateIssuer() {
            assertThatThrownBy(() -> withIssuer("https://10.0.0.5").discovery())
                .hasMessageContaining("not an acceptable URL");
        }

        @Test
        @DisplayName("the webhook 'allow insecure targets' escape hatch does NOT widen this")
        void webhookEscapeHatchDoesNotApply() {
            // That flag lets a developer point a webhook at a local receiver. Inheriting it here
            // would mean enabling local webhook testing silently disabled OIDC's SSRF protection.
            WebhookUrlValidator lax = new WebhookUrlValidator();
            ReflectionTestUtils.setField(lax, "allowInsecureTargets", true);
            OidcService svc = new OidcService(new ObjectMapper(), null, lax);
            ReflectionTestUtils.setField(svc, "enabled", true);
            ReflectionTestUtils.setField(svc, "issuer", "http://127.0.0.1:9999");
            ReflectionTestUtils.setField(svc, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(svc, "redirectUri", "https://cc.example.com/callback");
            assertThatThrownBy(svc::discovery).hasMessageContaining("not an acceptable URL");
        }
    }

    @Test
    @DisplayName("SSO reports itself disabled until issuer, client and redirect are all set")
    void disabledUntilFullyConfigured() {
        OidcService off = new OidcService(new ObjectMapper(), null, new WebhookUrlValidator());
        ReflectionTestUtils.setField(off, "enabled", true);
        ReflectionTestUtils.setField(off, "issuer", "");
        ReflectionTestUtils.setField(off, "clientId", "");
        ReflectionTestUtils.setField(off, "redirectUri", "");
        assertThat(off.isEnabled()).isFalse();
        assertThatThrownBy(off::authorizationRequest).hasMessageContaining("not configured");
        assertThat(oidc.isEnabled()).isTrue();
    }
}
