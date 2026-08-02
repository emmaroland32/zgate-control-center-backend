package com.zgate.controlcenter.service.provisioning;

import com.zgate.controlcenter.exception.ControlCenterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The credential store is what makes holding a customer's cloud keys defensible, so its failure
 * modes matter more than its happy path: a wrong key must throw rather than return garbage, a
 * retired key must keep working for rows written under it, and tampered ciphertext must be rejected
 * rather than silently decrypted.
 */
class SecretCipherTest {

    private static final String KEY_A = b64(32, (byte) 0x11);
    private static final String KEY_B = b64(32, (byte) 0x22);

    private static String b64(int len, byte fill) {
        byte[] k = new byte[len];
        java.util.Arrays.fill(k, fill);
        return Base64.getEncoder().encodeToString(k);
    }

    private static SecretCipher cipher(String masterKey, String keyring, String activeKeyId) {
        SecretCipher c = new SecretCipher(masterKey, keyring, activeKeyId);
        c.init();
        return c;
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("decrypts what it encrypted")
        void roundTrips() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            String secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

            String envelope = c.encrypt(secret);

            assertThat(envelope).isNotNull().isNotEqualTo(secret);
            assertThat(c.decrypt(envelope, "v1")).isEqualTo(secret);
        }

        @Test
        @DisplayName("handles a multi-line GCP service-account JSON key")
        void handlesJsonKey() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            String json = """
                {
                  "type": "service_account",
                  "project_id": "kestrel-zgate-prod",
                  "private_key": "-----BEGIN PRIVATE KEY-----\\nMIIEvQ==\\n-----END PRIVATE KEY-----\\n"
                }""";

            assertThat(c.decrypt(c.encrypt(json), "v1")).isEqualTo(json);
        }

        @Test
        @DisplayName("produces a different envelope every time, so equal secrets are not linkable")
        void usesAFreshNonce() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            String secret = "same-secret";

            // Two orgs with the same credential must not produce identical ciphertext — that would
            // leak the fact that they share a key to anyone who can read the table.
            assertThat(c.encrypt(secret)).isNotEqualTo(c.encrypt(secret));
        }

        @Test
        @DisplayName("passes null through rather than encrypting the string \"null\"")
        void nullSafe() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            assertThat(c.encrypt(null)).isNull();
            assertThat(c.decrypt(null, "v1")).isNull();
        }
    }

    @Nested
    @DisplayName("key rotation")
    class Rotation {

        @Test
        @DisplayName("a retired key still decrypts the rows written while it was active")
        void retiredKeyStillDecrypts() {
            // v1 was active when this credential was stored.
            SecretCipher before = cipher("", "v1:" + KEY_A, "v1");
            String envelope = before.encrypt("aws-secret-access-key");

            // v2 is now active, but v1 remains in the keyring.
            SecretCipher after = cipher("", "v1:" + KEY_A + ",v2:" + KEY_B, "v2");

            assertThat(after.activeKeyId()).isEqualTo("v2");
            assertThat(after.decrypt(envelope, "v1")).isEqualTo("aws-secret-access-key");
        }

        @Test
        @DisplayName("new writes use the active key, not an older one")
        void newWritesUseActiveKey() {
            SecretCipher c = cipher("", "v1:" + KEY_A + ",v2:" + KEY_B, "v2");
            String envelope = c.encrypt("fresh");

            assertThat(c.decrypt(envelope, "v2")).isEqualTo("fresh");
            // Decrypting the v2 envelope with the v1 key must fail the GCM tag rather than succeed.
            assertThatThrownBy(() -> c.decrypt(envelope, "v1"))
                .isInstanceOf(ControlCenterException.class);
        }

        @Test
        @DisplayName("a dropped key fails closed and says so, instead of guessing")
        void droppedKeyFailsClosed() {
            SecretCipher before = cipher("", "v1:" + KEY_A, "v1");
            String envelope = before.encrypt("secret");

            // v1 was removed from the keyring — the operator error this guards against.
            SecretCipher after = cipher("", "v2:" + KEY_B, "v2");

            assertThatThrownBy(() -> after.decrypt(envelope, "v1"))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("not in the configured")
                .satisfies(e -> assertThat(((ControlCenterException) e).getCode())
                    .isEqualTo("CREDENTIAL_KEY_UNKNOWN"));
        }

        @Test
        @DisplayName("refuses to start when the active key id is not in the keyring")
        void activeKeyMustExist() {
            // Otherwise the app boots fine and every credential write fails later, at the worst moment.
            assertThatThrownBy(() -> cipher("", "v1:" + KEY_A, "v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not present in the configured keyring");
        }
    }

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        @DisplayName("rejects tampered ciphertext instead of returning corrupted plaintext")
        void rejectsTampering() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            byte[] raw = Base64.getDecoder().decode(c.encrypt("original-secret"));

            // Flip a bit in the ciphertext body. GCM is authenticated, so this must be caught.
            raw[raw.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> c.decrypt(tampered, "v1"))
                .isInstanceOf(ControlCenterException.class)
                .satisfies(e -> assertThat(((ControlCenterException) e).getCode())
                    .isEqualTo("CREDENTIAL_DECRYPT_FAILED"));
        }

        @Test
        @DisplayName("rejects an envelope with an unknown version byte")
        void rejectsUnknownVersion() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            byte[] raw = Base64.getDecoder().decode(c.encrypt("secret"));
            raw[0] = (byte) 0x7F;

            assertThatThrownBy(() -> c.decrypt(Base64.getEncoder().encodeToString(raw), "v1"))
                .isInstanceOf(ControlCenterException.class);
        }

        @Test
        @DisplayName("rejects a truncated envelope")
        void rejectsTruncated() {
            SecretCipher c = cipher(KEY_A, "", "v1");
            assertThatThrownBy(() -> c.decrypt(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), "v1"))
                .isInstanceOf(ControlCenterException.class);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("an unconfigured cipher refuses to store a secret rather than storing it in the clear")
        void unconfiguredRefuses() {
            // An installation that only ever uses AWS_ASSUME_ROLE needs no key, so this must not be
            // fatal at boot — but it must never silently fall back to plaintext.
            SecretCipher c = cipher("", "", "v1");

            assertThat(c.isConfigured()).isFalse();
            assertThatThrownBy(() -> c.encrypt("secret"))
                .isInstanceOf(ControlCenterException.class)
                .satisfies(e -> assertThat(((ControlCenterException) e).getCode())
                    .isEqualTo("CREDENTIAL_ENCRYPTION_NOT_CONFIGURED"));
        }

        @Test
        @DisplayName("rejects a key shorter than 256 bits")
        void rejectsWeakKey() {
            assertThatThrownBy(() -> cipher(b64(16, (byte) 0x33), "", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
        }

        @Test
        @DisplayName("rejects a malformed keyring entry rather than silently ignoring it")
        void rejectsMalformedKeyring() {
            // Silently skipping a bad entry would mean a rotation appears to succeed while the old
            // key is quietly absent — and nothing fails until a decrypt much later.
            assertThatThrownBy(() -> cipher("", "v1" + KEY_A, "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<keyId>:<base64Key>");
        }

        @Test
        @DisplayName("keys derived from different masters do not interoperate")
        void differentMastersDoNotInteroperate() {
            SecretCipher a = cipher(KEY_A, "", "v1");
            SecretCipher b = cipher(KEY_B, "", "v1");

            String envelope = a.encrypt("secret");

            assertThatThrownBy(() -> b.decrypt(envelope, "v1"))
                .isInstanceOf(ControlCenterException.class);
        }
    }
}
