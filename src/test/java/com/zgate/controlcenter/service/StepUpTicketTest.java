package com.zgate.controlcenter.service;

import com.zgate.controlcenter.service.provisioning.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step-up tickets gate the actions where a hijacked or unattended session does the most damage.
 * A ticket must be unforgeable, bound to ONE operator and ONE action, short-lived, and usable
 * ONCE — a captured ticket that authorises someone else's destroy, a different action, or keeps
 * working all afternoon defeats the point.
 */
class StepUpTicketTest {

    private static final String RESET = "POST /api/v1/users/42/reset-password";
    private static final String MFA_OFF = "POST /api/v1/users/42/mfa/disable";

    /** In-memory stand-in for the JDBC store: same contract, including single-claim semantics. */
    static class MemoryStore implements StepUpTicketStore {
        private record Row(String email, String action, LocalDateTime expiresAt) {}
        private final Map<String, Row> rows = new ConcurrentHashMap<>();
        @Override public void save(String nonce, String email, String action, LocalDateTime issuedAt, LocalDateTime expiresAt) {
            rows.put(nonce, new Row(email, action, expiresAt));
        }
        @Override public Optional<Issued> find(String nonce, LocalDateTime now) {
            Row r = rows.get(nonce);
            return r == null || !r.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(new Issued(r.email(), r.action()));
        }
        @Override public boolean claim(String nonce) { return rows.remove(nonce) != null; }
        @Override public int purgeExpired(LocalDateTime now) {
            int before = rows.size();
            rows.values().removeIf(r -> r.expiresAt().isBefore(now));
            return before - rows.size();
        }
        int size() { return rows.size(); }
    }

    private MemoryStore store;
    private StepUpTicketService svc;

    private static SecretCipher cipher() {
        SecretCipher c = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]), "", "v1");
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        svc = new StepUpTicketService(cipher(), store);
    }

    @Test
    @DisplayName("a fresh ticket verifies for its owner and its action")
    void freshTicketVerifies() {
        String t = svc.issue("op@zgate.io", RESET);
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).isTrue();
    }

    @Test
    @DisplayName("a ticket is single-use: the second presentation is refused")
    void singleUse() {
        String t = svc.issue("op@zgate.io", RESET);
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).isTrue();
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).as("replay").isFalse();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("a ticket is bound to one operator — it cannot authorise another's action")
    void ticketIsBoundToUser() {
        String t = svc.issue("op@zgate.io", RESET);
        assertThat(svc.verify(t, "someone-else@zgate.io", RESET, 300)).isFalse();
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).as("a refused mismatch does not consume it").isTrue();
    }

    @Test
    @DisplayName("a ticket is bound to one action — it opens nothing else, and is not consumed by the attempt")
    void ticketIsBoundToAction() {
        String t = svc.issue("op@zgate.io", RESET);
        assertThat(svc.verify(t, "op@zgate.io", MFA_OFF, 300)).isFalse();
        assertThat(svc.verify(t, "op@zgate.io", null, 300)).as("no action named").isFalse();
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).isTrue();
    }

    @Test
    @DisplayName("an unbound ticket opens any single action, once")
    void unboundTicket() {
        String t = svc.issue("op@zgate.io");
        assertThat(svc.verify(t, "op@zgate.io", MFA_OFF, 300)).isTrue();
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).isFalse();
    }

    @Test
    @DisplayName("the owner comparison is case-insensitive, like every other operator lookup")
    void ownerCaseInsensitive() {
        String t = svc.issue("Op@Zgate.io", RESET);
        assertThat(svc.verify(t, "op@zgate.io", RESET, 300)).isTrue();
    }

    @Test
    @DisplayName("an expired ticket is refused")
    void expiredTicketRefused() {
        String t = svc.issue("op@zgate.io", RESET);
        // maxAge -1: anything issued even a second ago is already too old.
        assertThat(svc.verify(t, "op@zgate.io", RESET, -1)).isFalse();
        assertThat(store.size()).as("not consumed").isEqualTo(1);
    }

    @Test
    @DisplayName("tampering with any part of a ticket invalidates it")
    void tamperedTicketRefused() {
        String t = svc.issue("op@zgate.io", RESET);
        String[] parts = t.split("\\.");
        // Forge a newer timestamp to extend its life — the signature covers it.
        assertThat(svc.verify((Long.parseLong(parts[0]) + 1000) + "." + parts[1] + "." + parts[2],
                              "op@zgate.io", RESET, 300)).isFalse();
        // Swap the signature.
        assertThat(svc.verify(parts[0] + "." + parts[1] + ".deadbeef", "op@zgate.io", RESET, 300)).isFalse();
        // Malformed shapes.
        assertThat(svc.verify("nonsense", "op@zgate.io", RESET, 300)).isFalse();
        assertThat(svc.verify(null, "op@zgate.io", RESET, 300)).isFalse();
        assertThat(svc.verify(t, null, RESET, 300)).isFalse();
        assertThat(store.size()).as("nothing above consumed the real ticket").isEqualTo(1);
    }

    @Test
    @DisplayName("tickets verify across instances when a master key is configured and the store is shared")
    void ticketsAreStableAcrossInstances() {
        // Two replicas, same configured key, same database: a ticket minted by one must verify on
        // the other, or a second replica behind a load balancer rejects tickets at random.
        StepUpTicketService secondInstance = new StepUpTicketService(cipher(), store);
        assertThat(secondInstance.verify(svc.issue("op@zgate.io", RESET), "op@zgate.io", RESET, 300)).isTrue();
    }

    @Test
    @DisplayName("the purge removes only expired rows")
    void purgeExpired() {
        store.save("old", "op@zgate.io", RESET, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        svc.issue("op@zgate.io", RESET);
        svc.purgeExpired();
        assertThat(store.size()).isEqualTo(1);
    }
}
