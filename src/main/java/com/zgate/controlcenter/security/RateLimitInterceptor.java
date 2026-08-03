package com.zgate.controlcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zgate.controlcenter.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * Enforces {@link RateLimit} on controller methods.
 *
 * <p>Ported from the main product's interceptor, with the bucket store moved into the database —
 * its in-memory version is explicitly single-instance, and a per-replica bucket multiplies the
 * effective limit by the replica count.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimits;
    private final ClientIpResolver clientIpResolver;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RateLimit annotation = hm.getMethodAnnotation(RateLimit.class);
        if (annotation == null) return true;

        String key = buildKey(request, annotation, hm);
        if (key == null) {
            // USER strategy with nobody authenticated — fail closed rather than silently sharing
            // one anonymous bucket.
            writeJson(response, 401, "UNAUTHORIZED", "Sign in first.");
            return false;
        }

        RateLimitService.Decision d =
            rateLimits.record(key, annotation.limit(), annotation.windowSeconds());

        response.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));

        if (!d.allowed()) {
            response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            writeJson(response, 429, "RATE_LIMITED",
                      "Too many requests. Try again in " + d.retryAfterSeconds() + "s.");
            return false;
        }
        return true;
    }

    private String buildKey(HttpServletRequest request, RateLimit annotation, HandlerMethod hm) {
        String method = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        String principal = currentPrincipalName();
        // Trusted-proxy-aware: X-Forwarded-For is client-controlled, so honouring its leftmost
        // entry would let an attacker vary the header for a fresh bucket on every request and
        // defeat the limit entirely.
        String ip = normalize(clientIpResolver.resolve(request));

        return switch (annotation.keyBy()) {
            case IP -> method + "|ip|" + (ip == null ? "unknown" : ip);
            case USER -> principal == null ? null : method + "|user|" + principal;
            case AUTO -> principal != null
                    ? method + "|user|" + principal
                    : method + "|ip|" + (ip == null ? "unknown" : ip);
        };
    }

    /**
     * Reduce an address to one canonical spelling, so one client cannot hold two buckets.
     *
     * <p>A dual-stacked host sees the same caller as {@code 1.2.3.4} and {@code ::ffff:1.2.3.4},
     * and loopback as both {@code 127.0.0.1} and {@code 0:0:0:0:0:0:0:1} — which is how this
     * surfaced, in the bucket_key column of a live run. Two spellings mean two buckets and twice
     * the limit. {@code InetAddress} unwraps the IPv4-mapped form and collapses the many written
     * forms of an IPv6 address to one.
     *
     * <p>{@code ofLiteral} rather than {@code getByName}: this value can come from
     * X-Forwarded-For, which the client controls. {@code getByName} would treat a non-literal as a
     * HOSTNAME and resolve it, turning every request into an attacker-directed DNS lookup.
     * {@code ofLiteral} parses addresses only and never touches the network.
     */
    static String normalize(String ip) {
        if (ip == null || ip.isBlank()) return null;
        try {
            java.net.InetAddress addr = java.net.InetAddress.ofLiteral(ip.trim());
            // Loopback arrives as 127.0.0.1 or ::1 depending on which stack the client used;
            // they are the same machine, so they must share one bucket.
            return addr.isLoopbackAddress() ? "loopback" : addr.getHostAddress();
        } catch (IllegalArgumentException e) {
            return ip.trim();   // not an address literal: key on it verbatim, resolve nothing
        }
    }

    private static String currentPrincipalName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof UserDetails ud ? ud.getUsername() : auth.getName();
    }

    private void writeJson(HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        // Written directly rather than via sendError: on an anonymous request Spring rewrites a
        // pre-auth error into an empty 401, which the console reads as an expired session and
        // loops the operator through sign-in without ever saying why.
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            Map.of("status", status, "code", code, "message", message)));
    }
}
