package com.zgate.controlcenter.service;

import com.zgate.controlcenter.service.RegistryCredentialProvider.RegistryCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** The only non-AWS logic in the ECR provider is decoding the base64 "AWS:password" token. */
class EcrRegistryCredentialProviderTest {

    private String token(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("decodes AWS:<password> into username + password + expiry")
    void decodesToken() {
        LocalDateTime exp = LocalDateTime.now().plusHours(12);
        RegistryCredential c = EcrRegistryCredentialProvider.decode(token("AWS:s3cr3t-token"), exp);
        assertThat(c).isNotNull();
        assertThat(c.username()).isEqualTo("AWS");
        assertThat(c.password()).isEqualTo("s3cr3t-token");
        assertThat(c.expiresAt()).isEqualTo(exp);
    }

    @Test
    @DisplayName("password containing ':' is preserved (split on first colon only)")
    void passwordWithColon() {
        RegistryCredential c = EcrRegistryCredentialProvider.decode(token("AWS:ab:cd:ef"), null);
        assertThat(c.password()).isEqualTo("ab:cd:ef");
    }

    @Test
    @DisplayName("blank or malformed token → null")
    void malformedTokenNull() {
        assertThat(EcrRegistryCredentialProvider.decode(null, null)).isNull();
        assertThat(EcrRegistryCredentialProvider.decode("", null)).isNull();
        assertThat(EcrRegistryCredentialProvider.decode(token("no-colon-here"), null)).isNull();
    }
}
