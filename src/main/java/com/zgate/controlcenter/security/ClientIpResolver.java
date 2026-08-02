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
 * <p>{@code X-Forwarded-For} is a request header: any caller can set it. Trusting it blindly let an
 * operator write an arbitrary source address into the audit trail for a fleet-wide rollout or a
 * destroy — forging the evidence the console exists to produce. It is honoured only when the
 * request actually arrived from a configured proxy hop.
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies = new HashSet<>();

    public ClientIpResolver(
            @Value("${controlcenter.security.trustedProxies:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            Arrays.stream(configured.split("\\s*,\\s*"))
                  .filter(s -> !s.isBlank())
                  .forEach(trustedProxies::add);
        }
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (trustedProxies.contains(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Left-most entry is the original client as recorded by the trusted hop.
                return forwarded.split(",")[0].trim();
            }
        }
        return remote;
    }
}
