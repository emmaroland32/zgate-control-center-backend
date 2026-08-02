package com.zgate.controlcenter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The operator IP allow-list. Two properties matter more than the matching itself:
 * <ul>
 *   <li>machine-to-machine endpoints must stay open, because customer deployments phone home from
 *       arbitrary addresses — gating them stops licence renewal fleet-wide and locks paying
 *       customers out of their own system;</li>
 *   <li>a block must be a real 403 with a body, not a bare 401, or the console reads it as an
 *       expired session and loops the operator through sign-in without ever saying why.</li>
 * </ul>
 */
class IpAllowlistFilterTest {

    private HttpServletRequest req(String uri, String remoteAddr) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn(uri);
        when(r.getContextPath()).thenReturn("");
        when(r.getServletPath()).thenReturn(uri);
        when(r.getRemoteAddr()).thenReturn(remoteAddr);
        when(r.getMethod()).thenReturn("GET");
        return r;
    }

    private IpAllowlistFilter filter(String allowlist) {
        return new IpAllowlistFilter(new ClientIpResolver(""), allowlist);
    }

    @Test
    @DisplayName("disabled by default — an empty list lets everything through")
    void disabledWhenUnset() throws Exception {
        IpAllowlistFilter f = filter("");
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(req("/api/v1/fleet/overview", "8.8.8.8"), mock(HttpServletResponse.class), chain);
        assertThat(f.isEnabled()).isFalse();
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("an unlisted operator address is refused; a listed one passes")
    void blocksUnlistedOperator() throws Exception {
        IpAllowlistFilter f = filter("203.0.113.0/24, 198.51.100.7");
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(req("/api/v1/fleet/overview", "203.0.113.55"), mock(HttpServletResponse.class), chain);
        f.doFilter(req("/api/v1/fleet/overview", "198.51.100.7"), mock(HttpServletResponse.class), chain);
        verify(chain, times(2)).doFilter(any(), any());

        FilterChain blocked = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        f.doFilter(req("/api/v1/fleet/overview", "8.8.8.8"), resp, blocked);
        verify(blocked, never()).doFilter(any(), any());
        // 403 with a body — NOT sendError, which Spring rewrites to an empty 401 on an anonymous
        // request and the console then treats as "session expired", looping the operator.
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(resp, never()).sendError(anyInt());
        verify(resp, never()).sendError(anyInt(), anyString());
        assertThat(body.toString()).contains("ip_not_allowed").contains("8.8.8.8");
    }

    @Test
    @DisplayName("login IS gated — refusing a sign-in from an unlisted address is the point")
    void loginIsGated() throws Exception {
        IpAllowlistFilter f = filter("203.0.113.0/24");
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        f.doFilter(req("/api/v1/auth/login", "8.8.8.8"), resp, chain);
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("M2M endpoints stay open from ANY address — gating them would be a fleet outage")
    void m2mEndpointsExempt() throws Exception {
        IpAllowlistFilter f = filter("203.0.113.0/24");
        for (String path : new String[]{
                "/api/v1/telemetry/ingest",
                "/api/v1/licenses/bundle",
                "/api/v1/licenses/status-report",
                "/api/v1/deployments/pull-token",
                "/api/v1/shared-services/track",
                "/api/v1/backups/initiate",
                "/actuator/health"}) {
            FilterChain chain = mock(FilterChain.class);
            // A customer deployment on a random public address.
            f.doFilter(req(path, "8.8.8.8"), mock(HttpServletResponse.class), chain);
            verify(chain, description("must stay open: " + path)).doFilter(any(), any());
        }
    }

    @Test
    @DisplayName("an encoded dot segment cannot dodge the check onto a gated route")
    void normalisedPathCannotBeDodged() throws Exception {
        IpAllowlistFilter f = filter("203.0.113.0/24");
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn("/api/v1/%2e/fleet/overview");
        when(r.getContextPath()).thenReturn("");
        when(r.getServletPath()).thenReturn("/api/v1/./fleet/overview");
        when(r.getRemoteAddr()).thenReturn("8.8.8.8");
        when(r.getMethod()).thenReturn("GET");
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(r, resp, chain);
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("a malformed entry fails the boot rather than silently narrowing the list")
    void malformedEntryFailsFast() {
        assertThatThrownBy(() -> filter("203.0.113.0/24, not-an-ip"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not-an-ip");
        assertThatThrownBy(() -> filter("10.0.0.0/99"))
            .isInstanceOf(IllegalStateException.class);
    }
}
