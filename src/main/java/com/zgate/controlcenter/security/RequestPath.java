package com.zgate.controlcenter.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Resolves the request path the way the dispatcher resolves routes: context-path stripped,
 * percent-decoded, dot segments collapsed.
 *
 * <p>Shared by every filter that makes a decision based on the path. Matching on the raw
 * {@code getRequestURI()} lets the filter and Spring disagree — a deployment with a
 * {@code server.servlet.context-path} silently stops matching anything, and an encoded dot segment
 * ({@code /api/v1/%2e/backups/initiate}) normalises onto a mapped route while dodging a
 * {@code startsWith} check. That was a real bypass in {@link ServiceKeyAuthFilter}; one helper
 * means it cannot be reintroduced independently in each filter.
 */
final class RequestPath {

    private RequestPath() {
    }

    /** The dispatcher-equivalent path, or null when it cannot be resolved (callers fail closed). */
    static String lookup(HttpServletRequest request) {
        try {
            String withinApp = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            if (withinApp == null || withinApp.isBlank()) {
                return "/";
            }
            String normalized = java.net.URI.create("/" + withinApp.replaceFirst("^/+", ""))
                .normalize().getPath();
            return normalized == null || normalized.isBlank() ? "/" : normalized;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
