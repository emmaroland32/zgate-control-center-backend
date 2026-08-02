package com.zgate.controlcenter.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Matches a client IP against an allow-list entry — an exact address or an IPv4 CIDR range
 * (e.g. {@code 10.0.0.0/8}).
 *
 * <p>Ported from {@code com.zgate.core.security.IpMatcher} in the ZGATE backend so both products
 * use the same, already-exercised logic. A subtly-wrong CIDR match is a security bug in either
 * direction — it lets the wrong network in, or locks the right one out — so there should be one
 * implementation to reason about, not two that drift.
 *
 * <p>IPv6 and malformed input are treated as no-match: never a crash, and never an accidental allow.
 */
public final class IpMatcher {

    private IpMatcher() {
    }

    /** True if {@code clientIp} equals {@code entry}, or falls inside {@code entry} when it is a CIDR. */
    public static boolean matches(String clientIp, String entry) {
        if (entry == null || clientIp == null) {
            return false;
        }
        entry = entry.trim();
        if (!entry.contains("/")) {
            return sameAddress(entry, clientIp);
        }
        String[] parts = entry.split("/");
        if (parts.length != 2) {
            return false;
        }
        long network = ipv4ToLong(parts[0]);
        long ip = ipv4ToLong(clientIp);
        if (network < 0 || ip < 0) {
            return false;   // unparseable / IPv6 — CIDR match not supported here
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (ip & mask) == (network & mask);
    }

    /**
     * Exact-address comparison that survives formatting differences.
     *
     * <p>Raw string equality is not enough. A client on IPv6 loopback is reported by the servlet
     * container as {@code 0:0:0:0:0:0:0:1} while an operator writes {@code ::1} — the same address,
     * two spellings, and a string compare locks them out. Both sides are parsed to an
     * {@link InetAddress} so any valid spelling of one address matches.
     *
     * <p>Loopback is additionally treated as one family: allow-listing {@code 127.0.0.1} means
     * "from this machine", and being refused because the browser resolved localhost over IPv6 is a
     * lockout with no diagnostic value. Every other address is matched exactly.
     */
    private static boolean sameAddress(String entry, String clientIp) {
        if (entry.equals(clientIp)) {
            return true;
        }
        try {
            InetAddress a = InetAddress.getByName(entry);
            InetAddress b = InetAddress.getByName(clientIp);
            if (a.equals(b)) {
                return true;
            }
            return a.isLoopbackAddress() && b.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;   // unparseable — no match, never an accidental allow
        }
    }

    /** True when {@code clientIp} matches any entry. An empty list means "no restriction". */
    public static boolean matchesAny(String clientIp, java.util.Collection<String> allowList) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        for (String entry : allowList) {
            if (entry != null && !entry.isBlank() && matches(clientIp, entry.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an entry is syntactically usable. Callers validate at startup so a typo fails loudly
     * then, rather than silently narrowing the allow-list at request time.
     */
    public static boolean isValidEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String e = entry.trim();
        if (!e.contains("/")) {
            if (ipv4ToLong(e) >= 0) {
                return true;
            }
            // Accept IPv6 literals as exact entries. CIDR stays IPv4-only (documented above), but
            // refusing "::1" at boot would be wrong when the console is reached over IPv6.
            try {
                InetAddress.getByName(e);
                return e.contains(":");
            } catch (UnknownHostException ex) {
                return false;
            }
        }
        String[] parts = e.split("/");
        if (parts.length != 2 || ipv4ToLong(parts[0]) < 0) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(parts[1].trim());
            return prefix >= 0 && prefix <= 32;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** Dotted-quad to long, or -1 when the input is not a plain IPv4 address. */
    private static long ipv4ToLong(String ip) {
        if (ip == null) {
            return -1;
        }
        String[] octets = ip.trim().split("\\.");
        if (octets.length != 4) {
            return -1;
        }
        long value = 0;
        for (String octet : octets) {
            int part;
            try {
                part = Integer.parseInt(octet);
            } catch (NumberFormatException e) {
                return -1;
            }
            if (part < 0 || part > 255) {
                return -1;
            }
            value = (value << 8) | part;
        }
        return value;
    }
}
