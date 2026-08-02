package com.zgate.controlcenter.service;

import com.zgate.controlcenter.exception.ControlCenterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Control Center delivers webhooks from the host holding cloud credentials, the Terraform state
 * keys and the license signing key. An operator-supplied URL pointing inward would make "add a
 * webhook" a read-capable SSRF against the operator network, so these targets must be refused.
 */
class WebhookUrlValidatorTest {

    private WebhookUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookUrlValidator();
        ReflectionTestUtils.setField(validator, "allowInsecureTargets", false);
    }

    @Test
    @DisplayName("loopback, metadata and RFC1918 targets are refused")
    void internalTargetsRefused() {
        for (String url : new String[]{
                "https://127.0.0.1/hook",
                "https://localhost/hook",
                "https://169.254.169.254/latest/meta-data/",   // cloud metadata
                "https://10.0.0.5/hook",
                "https://192.168.1.10/hook",
                "https://172.16.4.4/hook"}) {
            assertThatThrownBy(() -> validator.validate(url))
                .describedAs("must refuse %s", url)
                .isInstanceOfSatisfying(ControlCenterException.class,
                    e -> assertThat(e.getCode()).isIn("WEBHOOK_URL_INTERNAL", "WEBHOOK_URL_UNRESOLVABLE"));
            assertThat(validator.isDeliverable(url)).isFalse();
        }
    }

    @Test
    @DisplayName("plain http and non-http schemes are refused")
    void schemeEnforced() {
        assertThatThrownBy(() -> validator.validate("http://example.com/hook"))
            .isInstanceOfSatisfying(ControlCenterException.class,
                e -> assertThat(e.getCode()).isEqualTo("WEBHOOK_URL_INVALID"));
        assertThatThrownBy(() -> validator.validate("file:///etc/passwd"))
            .isInstanceOf(ControlCenterException.class);
        assertThatThrownBy(() -> validator.validate(""))
            .isInstanceOf(ControlCenterException.class);
    }

    @Test
    @DisplayName("the escape hatch relaxes both rules — it must stay off outside local testing")
    void insecureModeAllowsLocalReceiver() {
        ReflectionTestUtils.setField(validator, "allowInsecureTargets", true);
        validator.validate("http://127.0.0.1:8080/hook");
        assertThat(validator.isDeliverable("http://127.0.0.1:8080/hook")).isTrue();
    }
}
