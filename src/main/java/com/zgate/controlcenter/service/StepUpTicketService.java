package com.zgate.controlcenter.service;

import com.zgate.controlcenter.service.provisioning.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Issues and verifies short-lived, single-use step-up tickets.
 *
 * <p>A ticket is {@code <issuedAt>.<nonce>.<signature>}. The signature covers the operator, the
 * action the ticket was requested for, and the timestamp, so a ticket minted for one account or one
 * action cannot authorise another's, and it expires. The nonce is also recorded server-side and
 * consumed on first successful use — a captured ticket (proxy log, devtools export, a browser
 * extension) is worthless once the legitimate request has gone through, and worthless for any other
 * endpoint at any time.
 *
 * <p><b>Keyed by derivation, not by a per-instance random.</b> Deriving from the configured master
 * key under its own purpose makes tickets verify on any replica, and keeps this key material separate
 * from the credential, MFA and audit stores. With no key configured it falls back to a per-process
 * random so development still works, at the cost of tickets not surviving a restart.
 *
 * <p>An <em>unbound</em> ticket (issued with no action) is still single-use but opens any step-up
 * endpoint; it exists for API clients that predate binding. The console always binds.
 */
@Service
@Slf4j
public class StepUpTicketService {

    private static final String PURPOSE = "step-up-ticket";
    private static final SecureRandom RNG = new SecureRandom();
    /** Hard cap on a ticket's life regardless of the endpoint's own maxAge, and the purge horizon. */
    static final int MAX_LIFE_SECONDS = 900;
    static final int MAX_ACTION_LENGTH = 300;

    private final SecretCipher cipher;
    private final StepUpTicketStore store;

    /** Only used when no master key is configured — see the class comment. */
    private final String fallbackSecret = base64Url(randomBytes(32));

    public StepUpTicketService(SecretCipher cipher, StepUpTicketStore store) {
        this.cipher = cipher;
        this.store = store;
    }

    /** Issue an unbound ticket. Prefer {@link #issue(String, String)}. */
    public String issue(String username) {
        return issue(username, null);
    }

    /** Issue a ticket for {@code username} that opens only {@code action} ("METHOD /path"), once. */
    public String issue(String username, String action) {
        String bound = normaliseAction(action);
        long ts = Instant.now().getEpochSecond();
        String nonce = base64Url(randomBytes(16));
        LocalDateTime issuedAt = LocalDateTime.now();
        store.save(nonce, username, bound, issuedAt, issuedAt.plusSeconds(MAX_LIFE_SECONDS));
        return ts + "." + nonce + "." + sign(body(username, bound, ts, nonce));
    }

    /** Verify without naming an action — only an unbound ticket can pass. */
    public boolean verify(String ticket, String username, int maxAgeSeconds) {
        return verify(ticket, username, null, maxAgeSeconds);
    }

    /**
     * True exactly once: when the ticket was minted here, for this username, for this action (or
     * unbound), is younger than the limit, and has not been used. A mismatch on user or action is
     * refused WITHOUT consuming the ticket, so an unrelated request cannot burn a valid one.
     */
    public boolean verify(String ticket, String username, String action, int maxAgeSeconds) {
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
        if (age < -60 || age > Math.min(maxAgeSeconds, MAX_LIFE_SECONDS)) return false;

        Optional<StepUpTicketStore.Issued> issued = store.find(parts[1], LocalDateTime.now());
        if (issued.isEmpty()) return false;                 // never issued, expired, or already used
        StepUpTicketStore.Issued i = issued.get();
        if (!i.email().equalsIgnoreCase(username)) return false;
        if (i.action() != null && !i.action().equals(normaliseAction(action))) return false;

        String expected = sign(body(i.email(), i.action(), ts, parts[1]));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                   parts[2].getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        return store.claim(parts[1]);
    }

    /** Expired rows are dead weight; they can never verify, so removing them changes nothing. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "stepUpTicketPurge", lockAtMostFor = "PT5M")
    public void purgeExpired() {
        int n = store.purgeExpired(LocalDateTime.now());
        if (n > 0) log.debug("Purged {} expired step-up tickets", n);
    }

    static String normaliseAction(String action) {
        if (action == null || action.isBlank()) return null;
        String a = action.trim();
        return a.length() > MAX_ACTION_LENGTH ? a.substring(0, MAX_ACTION_LENGTH) : a;
    }

    private static String body(String username, String action, long ts, String nonce) {
        return username + "|" + (action == null ? "" : action) + "|" + ts + "|" + nonce;
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
