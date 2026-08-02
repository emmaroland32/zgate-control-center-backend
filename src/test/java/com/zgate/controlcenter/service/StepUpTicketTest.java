package com.zgate.controlcenter.service;

import com.zgate.controlcenter.service.provisioning.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step-up tickets gate the actions where a hijacked or unattended session does the most damage.
 * A ticket must be unforgeable, bound to ONE operator, and short-lived — a ticket that authorises
 * someone else's destroy, or that keeps working all afternoon, defeats the point.
 */
class StepUpTicketTest {

    private StepUpTicketService svc;

    @BeforeEach
    void setUp() {
        SecretCipher cipher = new SecretCipher(
            Base64.getEncoder().encodeToString(new byte[32]), "", "v1");
        ReflectionTestUtils.invokeMethod(cipher, "init");
        svc = new StepUpTicketService(cipher);
    }

    @Test
    @DisplayName("a fresh ticket verifies for its owner")
    void freshTicketVerifies() {
        String t = svc.issue("op@zgate.io");
        assertThat(svc.verify(t, "op@zgate.io", 300)).isTrue();
    }

    @Test
    @DisplayName("a ticket is bound to one operator — it cannot authorise another's action")
    void ticketIsBoundToUser() {
        String t = svc.issue("op@zgate.io");
        assertThat(svc.verify(t, "someone-else@zgate.io", 300)).isFalse();
    }

    @Test
    @DisplayName("an expired ticket is refused")
    void expiredTicketRefused() {
        String t = svc.issue("op@zgate.io");
        // maxAge 0: anything issued even a second ago is already too old.
        assertThat(svc.verify(t, "op@zgate.io", -1)).isFalse();
    }

    @Test
    @DisplayName("tampering with any part of a ticket invalidates it")
    void tamperedTicketRefused() {
        String t = svc.issue("op@zgate.io");
        String[] parts = t.split("\\.");
        // Forge a newer timestamp to extend its life — the signature covers it.
        assertThat(svc.verify((Long.parseLong(parts[0]) + 1000) + "." + parts[1] + "." + parts[2],
                              "op@zgate.io", 300)).isFalse();
        // Swap the signature.
        assertThat(svc.verify(parts[0] + "." + parts[1] + ".deadbeef", "op@zgate.io", 300)).isFalse();
        // Malformed shapes.
        assertThat(svc.verify("nonsense", "op@zgate.io", 300)).isFalse();
        assertThat(svc.verify(null, "op@zgate.io", 300)).isFalse();
        assertThat(svc.verify(t, null, 300)).isFalse();
    }

    @Test
    @DisplayName("tickets verify across instances when a master key is configured")
    void ticketsAreStableAcrossInstances() {
        // Two processes, same configured key: a ticket minted by one must verify on the other, or a
        // second replica behind a load balancer rejects tickets at random.
        SecretCipher other = new SecretCipher(
            Base64.getEncoder().encodeToString(new byte[32]), "", "v1");
        ReflectionTestUtils.invokeMethod(other, "init");
        StepUpTicketService secondInstance = new StepUpTicketService(other);

        assertThat(secondInstance.verify(svc.issue("op@zgate.io"), "op@zgate.io", 300)).isTrue();
    }
}
