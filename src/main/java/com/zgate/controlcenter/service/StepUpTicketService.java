package com.zgate.controlcenter.service;

import com.zgate.controlcenter.service.provisioning.SecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues and verifies short-lived step-up tickets.
 *
 * <p>A ticket is {@code <issuedAt>.<nonce>.<signature>}, signed over the operator's username so a
 * ticket minted for one account cannot authorise another's action, and over the timestamp so it
 * expires. Nothing is stored server-side: the signature is the state.
 *
 * <p><b>Keyed by derivation, not by a per-instance random.</b> The equivalent service in the ZGATE
 * backend generates a random secret per process, which is fine for a single node but means a ticket
 * minted by one replica is rejected by another — and Control Center is now safe to run multiple
 * replicas (scheduled jobs are ShedLock-coordinated). Deriving from the configured master key under
 * its own purpose makes tickets verify anywhere, and keeps this key material separate from the
 * credential, MFA and audit stores. With no key configured it falls back to a per-process random so
 * development still works, at the cost of tickets not surviving a restart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StepUpTicketService {

    private static final String PURPOSE = "step-up-ticket";
    private static final SecureRandom RNG = new SecureRandom();

    private final SecretCipher cipher;

    /** Only used when no master key is configured — see the class comment. */
    private final String fallbackSecret = base64Url(randomBytes(32));

    public String issue(String username) {
        long ts = Instant.now().getEpochSecond();
        String nonce = base64Url(randomBytes(16));
        return ts + "." + nonce + "." + sign(username + "|" + ts + "|" + nonce);
    }

    /** True when the ticket was minted here, for this username, and is younger than the limit. */
    public boolean verify(String ticket, String username, int maxAgeSeconds) {
        if (ticket == null || username == null) return false;
        String[] parts = ticket.split("\\.");
        if (parts.length != 3) return false;

        long ts;
        try {
            ts = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        long age = Instant.now().getEpochSecond() - ts;
        // Reject a future-dated ticket too: a clock skew large enough to produce one is also large
        // enough to make the age check meaningless.
        if (age < -60 || age > maxAgeSeconds) return false;

        String expected = sign(username + "|" + ts + "|" + parts[1]);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                     parts[2].getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String body) {
        if (cipher.isConfigured()) {
            return cipher.hmacHex(body, PURPOSE);
        }
        // Dev fallback: still unforgeable, just not stable across restarts or replicas.
        return sha256Hex(fallbackSecret + "|" + body);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
