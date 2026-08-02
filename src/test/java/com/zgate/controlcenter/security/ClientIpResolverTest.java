package com.zgate.controlcenter.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The audited source IP. X-Forwarded-For is caller-controlled, so the selection rule is the whole
 * security property: a conforming proxy APPENDS the peer it saw, which makes the leftmost entries
 * attacker-supplied. Taking {@code split(",")[0]} let an operator forge any address into the audit
 * record for a destroy or a fleet-wide rollout — the same bug the ZGATE backend found and fixed.
 */
class ClientIpResolverTest {

    private HttpServletRequest req(String remoteAddr, String xff) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(remoteAddr);
        when(r.getHeader("X-Forwarded-For")).thenReturn(xff);
        return r;
    }

    @Test
    @DisplayName("a forged leftmost hop cannot become the audited address")
    void forgedLeftmostIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("");
        // The caller claims to be 8.8.8.8; our proxy appended the peer it actually saw
        // (203.0.113.9). The rightmost non-proxy hop is the truth.
        assertThat(resolver.resolve(req("10.0.0.5", "8.8.8.8, 203.0.113.9")))
            .isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("XFF from an untrusted peer is ignored entirely")
    void untrustedPeerIgnoresHeader() {
        ClientIpResolver resolver = new ClientIpResolver("");
        // A public address is not a proxy we run, so its header claim means nothing.
        assertThat(resolver.resolve(req("203.0.113.9", "8.8.8.8"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("an explicitly configured proxy is trusted; a missing header falls back to the peer")
    void configuredProxyAndNoHeader() {
        ClientIpResolver resolver = new ClientIpResolver("198.51.100.7");
        assertThat(resolver.resolve(req("198.51.100.7", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(req("10.0.0.5", null))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(req("10.0.0.5", "   "))).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("an all-internal chain records a real hop rather than inventing one")
    void allInternalChain() {
        ClientIpResolver resolver = new ClientIpResolver("");
        assertThat(resolver.resolve(req("10.0.0.5", "10.1.1.1, 10.2.2.2"))).isEqualTo("10.1.1.1");
    }

    @Test
    @DisplayName("172.16-31 is private, 172.32+ is public — the boundary must not leak trust")
    void private172Boundary() {
        ClientIpResolver resolver = new ClientIpResolver("");
        assertThat(resolver.isTrustedProxy("172.16.0.1")).isTrue();
        assertThat(resolver.isTrustedProxy("172.31.255.254")).isTrue();
        assertThat(resolver.isTrustedProxy("172.32.0.1")).isFalse();
        assertThat(resolver.isTrustedProxy("172.15.0.1")).isFalse();
    }
}
