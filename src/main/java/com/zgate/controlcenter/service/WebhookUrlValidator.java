package com.zgate.controlcenter.service;

import com.zgate.controlcenter.exception.ControlCenterException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Keeps the webhook registry from becoming an SSRF primitive.
 *
 * <p>Control Center delivers alerts from a host that holds cloud credentials, the Terraform state
 * bucket keys and the license signing key. An operator-supplied URL pointing at loopback, link-local
 * (including the cloud metadata endpoint at 169.254.169.254) or an internal RFC1918 address would
 * turn "add a webhook" into "read the operator network from the most privileged box in the fleet".
 *
 * <p>Checked at write time (fail fast, visible to the operator) AND at send time, because DNS can
 * be re-pointed at an internal address between the two.
 */
@Component
@Slf4j
public class WebhookUrlValidator {

    /** Allow plain http + private targets. Off in production; useful against a local test receiver. */
    @Value("${controlcenter.webhooks.allowInsecureTargets:false}")
    private boolean allowInsecureTargets;

    /** @throws ControlCenterException when the URL is not a legitimate external https endpoint. */
    public void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ControlCenterException("A webhook URL is required.",
                "WEBHOOK_URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ControlCenterException("That is not a valid URL.",
                "WEBHOOK_URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (allowInsecureTargets ? !(scheme.equals("https") || scheme.equals("http"))
                                 : !scheme.equals("https")) {
            throw new ControlCenterException(
                allowInsecureTargets ? "A webhook URL must be http or https."
                                     : "A webhook URL must use https.",
                "WEBHOOK_URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ControlCenterException("That URL has no host.",
                "WEBHOOK_URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (allowInsecureTargets) return;

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new ControlCenterException("That host could not be resolved: " + uri.getHost(),
                "WEBHOOK_URL_UNRESOLVABLE", HttpStatus.BAD_REQUEST);
        }
        for (InetAddress addr : resolved) {
            if (isInternal(addr)) {
                throw new ControlCenterException(
                    "That URL resolves to an internal address (" + addr.getHostAddress()
                    + "). Webhooks may only target external endpoints.",
                    "WEBHOOK_URL_INTERNAL", HttpStatus.BAD_REQUEST);
            }
        }
    }

    /** Send-time gate: same rules, but never throws — a bad target is skipped and logged. */
    public boolean isDeliverable(String rawUrl) {
        try {
            validate(rawUrl);
            return true;
        } catch (ControlCenterException e) {
            log.warn("Refusing webhook delivery to {}: {}", rawUrl, e.getMessage());
            return false;
        }
    }

    /**
     * The same rules, but with {@code allowInsecureTargets} deliberately ignored.
     *
     * <p>That flag exists so a developer can point a webhook at a local test receiver. Callers who
     * are validating something an ATTACKER could influence — an OIDC issuer, whose discovery
     * document then chooses where this host sends its client secret — must not inherit it: turning
     * on a local webhook receiver would otherwise silently disable their SSRF protection too.
     */
    public void validateStrict(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ControlCenterException("A URL is required.",
                "URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new ControlCenterException("That is not a valid URL.",
                "URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https")) {
            throw new ControlCenterException("That URL must use https.",
                "URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ControlCenterException("That URL has no host.",
                "URL_INVALID", HttpStatus.BAD_REQUEST);
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new ControlCenterException("That host could not be resolved: " + uri.getHost(),
                "URL_UNRESOLVABLE", HttpStatus.BAD_REQUEST);
        }
        for (InetAddress addr : resolved) {
            if (isInternal(addr)) {
                throw new ControlCenterException(
                    "That URL resolves to an internal address (" + addr.getHostAddress() + ").",
                    "URL_INTERNAL", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private boolean isInternal(InetAddress addr) {
        return addr.isLoopbackAddress()        // 127.0.0.0/8, ::1
            || addr.isLinkLocalAddress()       // 169.254.0.0/16 (cloud metadata), fe80::/10
            || addr.isSiteLocalAddress()       // 10/8, 172.16/12, 192.168/16
            || addr.isAnyLocalAddress()        // 0.0.0.0, ::
            || addr.isMulticastAddress();
    }
}
