package com.zgate.controlcenter.service.provisioning;

import com.zgate.controlcenter.exception.ControlCenterException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AES-256-GCM envelope encryption for the cloud-credential store.
 *
 * <p><b>Threat model.</b> This protects customer cloud credentials against disclosure of the Control
 * Center database — a dump, a backup, a read-only replica, a compromised query. It does NOT protect
 * against compromise of the Control Center process itself, which necessarily holds the master key in
 * order to launch a provisioning run. That is why {@code AWS_ASSUME_ROLE} is the preferred mode: it
 * stores nothing to steal.
 *
 * <p><b>Envelope format</b>, base64 of:
 * <pre>
 *   [ 1 byte version | 12 byte IV | ciphertext+GCM tag ]
 * </pre>
 * The key id is stored alongside the ciphertext in its own column rather than inside the envelope,
 * so a rotation can be reasoned about with a SQL query instead of by decrypting every row.
 *
 * <p><b>Key derivation.</b> Per-purpose keys are derived from the master via HMAC-SHA-256, mirroring
 * what the ZGATE backend does for its PII column keys, so the master never encrypts anything directly
 * and a future second purpose cannot share key material with this one.
 *
 * <p><b>Rotation.</b> {@code controlcenter.provisioning.encryptionKeys} accepts
 * {@code <kid>:<base64key>} entries. Every key ever used must stay in that list — a retired key still
 * decrypts the rows written while it was active. An unknown key id throws rather than attempting a
 * decrypt with the wrong key, which would surface as a corrupt credential at provision time instead
 * of a clear error here.
 */
@Component
@Slf4j
public class SecretCipher {

    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;      // GCM standard nonce size
    private static final int TAG_LENGTH_BITS = 128;
    /** Default purpose — the cloud-credential store this class was built for. */
    private static final String PURPOSE = "cloud-credential";

    /** MFA secrets: a distinct purpose, so the two stores never share derived key material. */
    public static final String PURPOSE_MFA = "mfa-secret";

    /** Audit-row signing. Separate again: a leak of one purpose's key must not forge the others. */
    public static final String PURPOSE_AUDIT = "audit-integrity";

    /** Org M2M service key, stored reversibly so provisioning can re-inject it on every terraform run. */
    public static final String PURPOSE_SERVICE_KEY = "service-key";

    private final String masterKeyB64;
    private final String keyringSpec;
    private final String activeKeyId;

    /** kid -> master bytes. Contains every key ever used, so old rows stay readable. */
    private final Map<String, byte[]> keyring = new HashMap<>();

    /** (kid, purpose) -> derived AES key. Derivation is deterministic; this is just a cache. */
    private final Map<String, SecretKey> derived = new java.util.concurrent.ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    public SecretCipher(
            @Value("${controlcenter.provisioning.encryptionKey:}") String masterKeyB64,
            @Value("${controlcenter.provisioning.encryptionKeys:}") String keyringSpec,
            @Value("${controlcenter.provisioning.activeKeyId:v1}") String activeKeyId) {
        this.masterKeyB64 = masterKeyB64 == null ? "" : masterKeyB64.trim();
        this.keyringSpec = keyringSpec == null ? "" : keyringSpec.trim();
        this.activeKeyId = activeKeyId == null || activeKeyId.isBlank() ? "v1" : activeKeyId.trim();
    }

    @PostConstruct
    void init() {
        // Multi-key form wins: "v1:<base64>,v2:<base64>".
        if (!keyringSpec.isBlank()) {
            for (String entry : keyringSpec.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw new IllegalStateException(
                        "controlcenter.provisioning.encryptionKeys entries must be '<keyId>:<base64Key>' — got: " + entry);
                }
                keyring.put(parts[0].trim(), decodeKey(parts[1].trim()));
            }
        } else if (!masterKeyB64.isBlank()) {
            // Single-key form: the whole keyring is the active id.
            keyring.put(activeKeyId, decodeKey(masterKeyB64));
        }

        if (keyring.isEmpty()) {
            // Not fatal: an installation that only ever uses AWS_ASSUME_ROLE stores no secret and
            // needs no key. Storing a static credential without one is refused at write time.
            log.info("Provisioning secret encryption is NOT configured. "
                   + "Credentials that carry a stored secret (static keys, service principals, "
                   + "service-account JSON) will be refused; AWS_ASSUME_ROLE still works. "
                   + "Set controlcenter.provisioning.encryptionKey to enable them.");
        } else if (!keyring.containsKey(activeKeyId)) {
            throw new IllegalStateException(
                "controlcenter.provisioning.activeKeyId = '" + activeKeyId
                + "' is not present in the configured keyring " + keyring.keySet()
                + ". New credentials could not be encrypted.");
        } else {
            log.info("Provisioning secret encryption enabled (activeKeyId={}, keyring={})",
                     activeKeyId, keyring.keySet());
        }
    }

    public boolean isConfigured() {
        return !keyring.isEmpty();
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * Encrypt with the ACTIVE key. The caller must persist {@link #activeKeyId()} alongside the
     * returned ciphertext — without it the value cannot be read back after a rotation.
     */
    public String encrypt(String plaintext) {
        return encrypt(plaintext, PURPOSE);
    }

    /** As {@link #encrypt(String)}, under a named purpose's derived subkey. */
    public String encrypt(String plaintext, String purpose) {
        if (plaintext == null) return null;
        requireConfigured();

        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(activeKeyId, purpose), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(1 + IV_LENGTH + ct.length);
            buf.put(VERSION).put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            // Never log the plaintext or the exception's message verbatim — a JCE failure can echo
            // key material in some providers.
            log.error("Failed to encrypt a provisioning secret: {}", e.getClass().getSimpleName());
            throw new ControlCenterException("Could not encrypt the credential.",
                "CREDENTIAL_ENCRYPT_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Decrypt a stored envelope.
     *
     * @param keyId the id recorded when the value was written. An id that is not in the keyring
     *              throws — decrypting with a different key would either fail the GCM tag check or,
     *              worse, be mistaken for a corrupt credential much later.
     */
    public String decrypt(String ciphertextB64, String keyId) {
        return decrypt(ciphertextB64, keyId, PURPOSE);
    }

    /** As {@link #decrypt(String, String)}, under a named purpose's derived subkey. */
    public String decrypt(String ciphertextB64, String keyId, String purpose) {
        if (ciphertextB64 == null) return null;
        requireConfigured();

        SecretKey key = keyFor(keyId, purpose);
        if (key == null) {
            throw new ControlCenterException(
                "This credential was encrypted with key '" + keyId + "', which is not in the configured "
                + "keyring " + keyring.keySet() + ". A retired key must stay in "
                + "controlcenter.provisioning.encryptionKeys for as long as any row still references it.",
                "CREDENTIAL_KEY_UNKNOWN", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        try {
            byte[] raw = Base64.getDecoder().decode(ciphertextB64);
            if (raw.length < 1 + IV_LENGTH + 16) {
                throw new IllegalArgumentException("envelope too short");
            }

            ByteBuffer buf = ByteBuffer.wrap(raw);
            byte version = buf.get();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported envelope version " + version);
            }

            byte[] iv = new byte[IV_LENGTH];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (ControlCenterException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to decrypt a provisioning secret (keyId={}): {}", keyId, e.getClass().getSimpleName());
            throw new ControlCenterException(
                "Could not decrypt the stored credential. The encryption key may have changed.",
                "CREDENTIAL_DECRYPT_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * HMAC-SHA-256 over {@code content} under a purpose's derived subkey, hex-encoded.
     *
     * <p>For signing rather than encryption: an audit row must stay readable in the database (that
     * is the point of an audit trail) while still being verifiable, so it is signed, not encrypted.
     */
    public String hmacHex(String content, String purpose) {
        requireConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keyFor(activeKeyId, purpose));
            byte[] out = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to sign content for purpose {}: {}", purpose, e.getClass().getSimpleName());
            throw new ControlCenterException("Could not sign the record.",
                "SIGNING_FAILED", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ControlCenterException(
                "Provisioning secret encryption is not configured. Set "
                + "controlcenter.provisioning.encryptionKey (base64, 32+ bytes) before storing cloud "
                + "credentials that carry a secret, or use the AWS assume-role mode, which stores none.",
                "CREDENTIAL_ENCRYPTION_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private byte[] decodeKey(String b64) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Provisioning encryption key is not valid base64.");
        }
        if (key.length < 32) {
            throw new IllegalStateException(
                "Provisioning encryption key must be at least 32 bytes (256 bits); got " + key.length
                + ". Generate one with: openssl rand -base64 32");
        }
        return key;
    }

    /** Derived subkey for (keyId, purpose), or null when the key id is not in the keyring. */
    private SecretKey keyFor(String keyId, String purpose) {
        byte[] master = keyring.get(keyId);
        if (master == null) return null;
        return derived.computeIfAbsent(keyId + "\u0000" + purpose, k -> derive(master, purpose));
    }

    /**
     * Per-purpose subkey via HMAC-SHA-256, so the master never encrypts directly and a second
     * purpose added later cannot share key material with the credential store.
     */
    private SecretKey derive(byte[] master, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive the credential encryption key", e);
        }
    }
}
