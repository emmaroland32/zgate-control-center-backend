package com.zgate.controlcenter.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP, implemented directly (HMAC-SHA1, 30-second step, 6 digits — what Google
 * Authenticator / 1Password / Authy expect) so operator MFA adds no dependency.
 *
 * <p>Verification accepts the previous/current/next time step (±30 s) to absorb clock skew between
 * the server and the operator's phone — the standard tolerance, and no wider.
 */
@Component
public class TotpService {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A fresh 160-bit secret, base32-encoded — the size RFC 4226 recommends for HMAC-SHA1. */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        return sb.toString();
    }

    /** The otpauth:// URI an authenticator app enrolls from (paste or QR-encode client-side). */
    public String otpauthUri(String secret, String accountEmail) {
        String label = java.net.URLEncoder.encode(
            accountEmail == null ? "" : accountEmail, java.nio.charset.StandardCharsets.UTF_8);
        return "otpauth://totp/ZGATE%20Control%20Center:" + label
             + "?secret=" + secret + "&issuer=ZGATE%20Control%20Center&digits=6&period=30";
    }

    public boolean verify(String secret, String code) {
        return matchedStep(secret, code, null) != null;
    }

    /**
     * The time step a code matches, or null if none does.
     *
     * <p>{@code minStep} enforces single use: a caller passes the last step it already accepted so
     * a replayed code (valid for up to 90s across the ±1 window) is refused. Comparison is
     * constant-time — the timing signal is weak here, but removing the question costs one call.
     */
    public Long matchedStep(String secret, String code, Long minStep) {
        if (secret == null || secret.isBlank() || code == null || !code.matches("\\d{6}")) return null;
        long step = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            long candidate = step + offset;
            if (minStep != null && candidate <= minStep) continue;
            byte[] expected = generateCode(secret, candidate).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (java.security.MessageDigest.isEqual(
                    expected, code.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return candidate;
            }
        }
        return null;
    }

    public String generateCode(String secret, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array());
            int off = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[off] & 0x7f) << 24) | ((hash[off + 1] & 0xff) << 16)
                       | ((hash[off + 2] & 0xff) << 8) | (hash[off + 3] & 0xff);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static byte[] base32Decode(String s) {
        String clean = s.trim().toUpperCase().replace("=", "");
        int buffer = 0, bitsLeft = 0, idx = 0;
        byte[] out = new byte[clean.length() * 5 / 8];
        for (char c : clean.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not base32: " + c);
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
