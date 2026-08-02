package com.zgate.controlcenter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Restricts the OPERATOR surface of Control Center to a configured set of addresses.
 *
 * <p>This console provisions, upgrades and destroys customers' production systems, so limiting
 * where an operator can reach it from is worth having in the application and not only on a load
 * balancer — the app is the last hop that cannot be bypassed by someone who reaches the container
 * directly. The matching logic is {@link IpMatcher}, ported from the ZGATE backend so both products
 * share one already-exercised implementation.
 *
 * <p><b>Machine-to-machine paths are exempt, and that is not optional.</b> Customer deployments
 * phone home from arbitrary, changing addresses: telemetry, licence-bundle pull, activation
 * callbacks, pull-token and the backup agent. Gating those would stop licence renewal fleet-wide,
 * which expires licences, which locks paying customers out of their own system — the allow-list
 * would become an outage. Those endpoints are authenticated by service key instead
 * ({@link ServiceKeyAuthFilter}).
 *
 * <p>The operator surface INCLUDES {@code /api/v1/auth/login}: refusing a sign-in attempt from an
 * unlisted address is the main thing this is for.
 *
 * <p><b>Off unless configured</b> ({@code controlcenter.security.ipAllowlist} empty). Entries are
 * validated at startup, so a typo fails the boot with a clear message rather than silently
 * narrowing the list and locking every operator out at request time. It is a property rather than a
 * database setting deliberately: a bad value is fixed by editing config and restarting, never
 * requiring access to the very console it just blocked.
 */
@Component
@Slf4j
public class IpAllowlistFilter extends OncePerRequestFilter {

    /**
     * Paths reached by customer deployments rather than operators. Kept in sync with the permitAll
     * M2M list in {@code SecurityConfig} and the guarded list in {@link ServiceKeyAuthFilter}.
     */
    private static final List<String> M2M_EXEMPT = List.of(
        "/api/v1/telemetry/ingest",
        "/api/v1/licenses/bundle",
        "/api/v1/licenses/status-report",
        "/api/v1/deployments/pull-token",
        "/api/v1/shared-services/track",
        "/api/v1/backups",
        "/actuator/health",
        "/actuator/info");

    private final List<String> allowList = new ArrayList<>();
    private final ClientIpResolver clientIpResolver;

    public IpAllowlistFilter(ClientIpResolver clientIpResolver,
                             @Value("${controlcenter.security.ipAllowlist:}") String configured) {
        this.clientIpResolver = clientIpResolver;
        if (configured != null && !configured.isBlank()) {
            List<String> bad = new ArrayList<>();
            for (String raw : configured.split("[,\\s]+")) {
                String entry = raw.trim();
                if (entry.isEmpty()) continue;
                if (!IpMatcher.isValidEntry(entry)) {
                    bad.add(entry);
                } else {
                    allowList.add(entry);
                }
            }
            if (!bad.isEmpty()) {
                // Fail the boot rather than run with a list that silently means something else.
                throw new IllegalStateException(
                    "controlcenter.security.ipAllowlist contains entries that are not an IPv4 "
                  + "address or CIDR range: " + bad + ". Fix or remove them; an empty list disables "
                  + "the allow-list entirely.");
            }
            log.info("Operator IP allow-list ACTIVE with {} entr{} — the console is reachable only "
                   + "from these addresses. Machine-to-machine endpoints remain open (service-key "
                   + "authenticated).", allowList.size(), allowList.size() == 1 ? "y" : "ies");
        }
    }

    boolean isEnabled() {
        return !allowList.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = RequestPath.lookup(request);
        // An unresolvable path is NOT treated as exempt — the safe reading of "I cannot tell which
        // route this is" is to apply the restriction.
        boolean exempt = path != null && M2M_EXEMPT.stream().anyMatch(path::startsWith);
        if (exempt || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(request);
        if (IpMatcher.matchesAny(clientIp, allowList)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("Blocked {} {} from {} — not in the operator IP allow-list",
                 request.getMethod(), path, clientIp);
        deny(response, clientIp);
    }

    /**
     * Refuse with a 403 the caller can act on.
     *
     * <p>Deliberately not {@code sendError}: this filter runs before authentication, so the security
     * context is empty and Spring Security's {@code ExceptionTranslationFilter} rewrites a 403 on an
     * anonymous request into an empty <b>401</b> — an authentication challenge. The console treats
     * 401 as "session expired" and redirects to sign-in, so a blocked operator would sign in
     * successfully, be blocked again, and loop, never learning that their address was the problem.
     * (This exact trap is documented in the ZGATE backend's equivalent filter.) Writing the status
     * and body directly and returning leaves the response untouched.
     */
    private static void deny(HttpServletResponse response, String clientIp) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
            "{\"status\":403,\"code\":\"IP_NOT_ALLOWED\",\"error\":\"ip_not_allowed\",\"message\":"
          + "\"Your network address (" + clientIp + ") is not permitted to reach Control Center. "
          + "Signing in again will not help — ask an administrator to add it to the operator IP "
          + "allow-list.\"}");
    }
}
