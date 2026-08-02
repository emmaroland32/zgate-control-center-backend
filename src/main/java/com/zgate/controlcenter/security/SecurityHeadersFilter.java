package com.zgate.controlcenter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security response headers for the operator console.
 *
 * <p>Ported from {@code com.zgate.core.security.SecurityHeadersFilter} in the ZGATE backend, which
 * had these and Control Center had none — despite this being the console that can destroy a
 * customer's production system. Framing protection matters most here: without
 * {@code X-Frame-Options}, a page an operator visits while signed in can embed this console and
 * drive it, and the actions available are provision, upgrade and destroy.
 *
 * <p>{@code Strict-Transport-Security} is emitted only on an already-secure request so local
 * development over http is unaffected — pinning HSTS from a plain-http dev server would make the
 * browser refuse to reach it afterwards.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Refuse to be framed — clickjacking an operator into a destroy is the risk.
        response.setHeader("X-Frame-Options", "DENY");

        // Let the declared MIME type stand; no sniffing a JSON body into something executable.
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Don't leak console URLs (which carry org and stack ids) to third parties.
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Browser APIs this console never needs.
        response.setHeader("Permissions-Policy",
                "geolocation=(), microphone=(), camera=(), payment=(), usb=()");

        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains; preload");
        }

        chain.doFilter(request, response);
    }
}
