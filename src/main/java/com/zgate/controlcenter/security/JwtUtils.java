package com.zgate.controlcenter.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
@Slf4j
public class JwtUtils {

    @Value("${controlcenter.jwt.secret}")
    private String jwtSecret;

    @Value("${controlcenter.jwt.expirationMs:86400000}")
    private long jwtExpirationMs;

    /**
     * Values that have ever been shipped in the repo. A deployment signing with one of these is
     * forgeable by anyone who can read the source, so booting with one is refused outright rather
     * than merely warned about — the length check alone happily accepted them.
     */
    private static final java.util.Set<String> KNOWN_WEAK_SECRETS = java.util.Set.of(
        "controlcenter-dev-secret-change-in-production-32c",
        "change-me-in-production",
        "secret");

    /** Generated once per boot when no secret is configured — dev convenience, never production. */
    private volatile SecretKey ephemeralKey;

    private SecretKey key() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            return ephemeralKey();
        }
        if (KNOWN_WEAK_SECRETS.contains(jwtSecret.trim())) {
            throw new IllegalStateException(
                "controlcenter.jwt.secret is set to a value published in the source tree. "
              + "Anyone can forge a SUPER_ADMIN token with it. Set JWT_SECRET to a fresh random "
              + "value of at least 32 characters.");
        }
        byte[] keyBytes = jwtSecret.getBytes();
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "JWT secret is too short (" + keyBytes.length + " bytes). " +
                "Set the JWT_SECRET environment variable to at least 32 characters.");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private SecretKey ephemeralKey() {
        SecretKey k = ephemeralKey;
        if (k == null) {
            synchronized (this) {
                k = ephemeralKey;
                if (k == null) {
                    k = Jwts.SIG.HS256.key().build();
                    ephemeralKey = k;
                    log.warn("No controlcenter.jwt.secret configured — signing with a random "
                           + "per-boot key. Sessions will not survive a restart and will not work "
                           + "across replicas. Set JWT_SECRET for any real deployment.");
                }
            }
        }
        return k;
    }

    public String generateToken(String email, String role, int tokenVersion) {
        return Jwts.builder()
            .subject(email)
            .claim("role", role)
            // Stamped so bumping the user's tokenVersion invalidates every outstanding token.
            .claim("tv", tokenVersion)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
            .signWith(key())
            .compact();
    }

    public String getEmailFromToken(String token) {
        return Jwts.parser().verifyWith(key()).build()
            .parseSignedClaims(token).getPayload().getSubject();
    }

    /** The token's {@code tv} claim. Tokens issued before revocation existed carry none → 0. */
    public int getTokenVersion(String token) {
        Integer tv = Jwts.parser().verifyWith(key()).build()
            .parseSignedClaims(token).getPayload().get("tv", Integer.class);
        return tv == null ? 0 : tv;
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }
}
