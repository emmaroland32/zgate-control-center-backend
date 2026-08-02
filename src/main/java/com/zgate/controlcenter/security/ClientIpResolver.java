package com.zgate.controlcenter.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The source IP written into audit records.
 *
 * <p>{@code X-Forwarded-For} is a request header: any caller can set it. It is honoured only when
 * the request actually arrived from a proxy we trust, and even then the <b>rightmost</b> hop that
 * is not itself a proxy is taken — never the leftmost.
 *
 * <p>That distinction is the whole point. A conforming proxy <em>appends</em> the peer it observed,
 * so the leftmost entries are attacker-supplied: taking {@code split(",")[0]} lets an operator send
 * {@code X-Forwarded-For: 10.9.9.9} and write any address they like into the audit trail for a
 * fleet-wide rollout or a destroy — forging the evidence this console exists to produce. The same
 * mistake was found and fixed in the ZGATE backend's resolver; this is the matching logic.
 *
 * <p>Trust is configured explicitly via {@code controlcenter.security.trustedProxies}. When that is
 * empty the header is ignored entirely and the direct peer is recorded, which is the safe default
 * for a console reached without a load balancer.
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies = new HashSet<>();

    public ClientIpResolver(
            @Value("${controlcenter.security.trustedProxies:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            Arrays.stream(configured.split("\\s*,\\s*"))
                  .filter(s -> !s.isBlank())
                  .map(String::trim)
                  .forEach(trustedProxies::add);
        }
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrustedProxy(remote)) {
            return remote;
        }

        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (!candidate.isEmpty() && !isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        // Every hop is a proxy we trust: the origin is inside our own estate. Record the first
        // entry rather than inventing one.
        return hops[0].trim();
    }

    /**
     * A hop we trust to have appended an honest entry: one explicitly configured, or a
     * loopback/RFC1918 address (a reverse proxy on our own network).
     */
    boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if (trustedProxies.contains(ip)) return true;
        return "127.0.0.1".equals(ip)
            || "::1".equals(ip)
            || "0:0:0:0:0:0:0:1".equals(ip)
            || ip.startsWith("10.")
            || ip.startsWith("192.168.")
            || isPrivate172(ip);
    }

    /** True only for 172.16.0.0 – 172.31.255.255 — 172.32+ is public space. */
    private static boolean isPrivate172(String ip) {
        if (!ip.startsWith("172.")) return false;
        int end = ip.indexOf('.', 4);
        if (end < 0) return false;
        try {
            int second = Integer.parseInt(ip.substring(4, end));
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
