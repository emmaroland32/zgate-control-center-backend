package com.zgate.controlcenter.security;

import com.zgate.controlcenter.service.OrganizationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates the org-facing (machine-to-machine) endpoints with the organization's service API key,
 * closing the gap where they were identified by the {@code X-Control-Center-Org-Id} header alone (any
 * caller who knows an org UUID could pull its bundle or post telemetry as it).
 *
 * <p>Opt-in: only enforces when {@code controlcenter.serviceKey.enforce=true}. Default off so existing
 * installs — which don't yet send the key — keep working; turn it on once installs are configured with
 * {@code CONTROLCENTER_SERVICE_KEY} (issued via org create / regenerate-key).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceKeyAuthFilter extends OncePerRequestFilter {

    private final OrganizationService organizationService;

    @Value("${controlcenter.serviceKey.enforce:false}")
    private boolean enforce;

    /** The machine-to-machine endpoints an install calls; each already carries the org-id header. */
    private static final List<String> GUARDED = List.of(
            "/api/v1/telemetry/ingest",
            "/api/v1/licenses/bundle",
            "/api/v1/licenses/status-report",
            "/api/v1/deployments/pull-token",
            "/api/v1/shared-services/track",
            "/api/v1/backups");   // managed-backup agent (initiate/complete/fail/list/restore-url)

    /**
     * Endpoints that ALWAYS require a valid service key, even when the global {@code enforce} flag is
     * off. The opt-in default exists only for backward compatibility with installs that predate this
     * filter; managed backup is a new feature with no such installs, and its endpoints are destructive
     * and data-bearing (list metadata, download ciphertext, delete objects). A {@code permitAll} path
     * whose only authentication is an opt-in filter would otherwise be wide open by default — so these
     * are fail-closed unconditionally. The legitimate agent always sends the key.
     */
    private static final List<String> ALWAYS_ENFORCED = List.of("/api/v1/backups");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = lookupPath(request);
        // An unresolvable path is treated as guarded: the only safe reading of "I cannot tell which
        // route this is" is to demand the key, not to wave it through.
        boolean guarded = path == null || GUARDED.stream().anyMatch(path::startsWith);
        boolean alwaysEnforced = path == null || ALWAYS_ENFORCED.stream().anyMatch(path::startsWith);
        boolean mustEnforce = guarded && (enforce || alwaysEnforced);
        if (!mustEnforce || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        UUID orgId = parseUuid(request.getHeader("X-Control-Center-Org-Id"));
        String key = request.getHeader("X-Control-Center-Service-Key");
        if (orgId == null || !organizationService.serviceKeyValid(orgId, key)) {
            log.warn("Rejected M2M call to {} — invalid/missing service key (org={})", path, orgId);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_service_key\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The path this filter makes its decision on, resolved the way the dispatcher resolves routes:
     * context-path stripped, percent-decoded, dot segments collapsed.
     *
     * <p>Matching on the raw {@code getRequestURI()} let the two engines disagree. A deployment with
     * a {@code server.servlet.context-path} would have failed every prefix test and silently stopped
     * enforcing anything, and an encoded dot segment ({@code /api/v1/%2e/backups/initiate}) could
     * normalise onto a mapped route while dodging {@code startsWith} — reaching the backup agent API
     * (including the ciphertext download) with no service key.
     *
     * <p>Returns null when the path cannot be resolved at all; the caller treats that as guarded and
     * demands a service key (fail closed).
     */
    private static String lookupPath(HttpServletRequest request) {
        try {
            String withinApp = org.springframework.web.util.UrlPathHelper.defaultInstance
                .getPathWithinApplication(request);
            if (withinApp == null || withinApp.isBlank()) return "/";
            String normalized = java.net.URI.create("/" + withinApp.replaceFirst("^/+", ""))
                .normalize().getPath();
            return normalized == null || normalized.isBlank() ? "/" : normalized;
        } catch (RuntimeException e) {
            // Unresolvable path: return null so the caller treats it as NOT matching a guarded
            // prefix but ALSO cannot be used to reach one — Spring will reject it downstream.
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
