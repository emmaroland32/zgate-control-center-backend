package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.ControlCenterUserRepository;
import com.zgate.controlcenter.security.ClientIpResolver;
import com.zgate.controlcenter.security.JwtUtils;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.service.OidcService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The identity-to-operator mapping, which is where SSO actually decides who runs the fleet.
 *
 * <p>A valid ID token only proves the IdP authenticated somebody. An IdP will authenticate every
 * employee, contractor and guest in the tenant; only the people with an account HERE may provision
 * and destroy customer production systems. Each test below is a way that distinction could quietly
 * stop holding.
 */
class OidcControllerTest {

    private OidcService oidc;
    private ControlCenterUserRepository users;
    private ControlCenterUserService userService;
    private JwtUtils jwt;
    private AuditService audit;
    private OidcController controller;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        oidc = mock(OidcService.class);
        users = mock(ControlCenterUserRepository.class);
        userService = mock(ControlCenterUserService.class);
        jwt = mock(JwtUtils.class);
        audit = mock(AuditService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");
        when(jwt.generateToken(any(), any(), anyInt())).thenReturn("issued-token");

        controller = new OidcController(oidc, users, userService, jwt, audit,
                                        new ClientIpResolver(""), encoder);
        ReflectionTestUtils.setField(controller, "autoProvisionRole", "");
        ReflectionTestUtils.setField(controller, "cookieSecure", true);

        request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.setCookies(new Cookie(OidcController.SSO_COOKIE, "browser-secret"));
        response = new MockHttpServletResponse();
    }

    private void providerReturns(String subject, String email, boolean verified) {
        when(oidc.exchangeCode(any(), any(), any()))
            .thenReturn(new OidcService.OidcIdentity(subject, email, verified));
    }

    private ControlCenterUser operator(ControlCenterUser.Role role, boolean active, String subject) {
        return ControlCenterUser.builder()
            .id(UUID.randomUUID()).name("Ops").email("ops@example.com")
            .passwordHash("x").role(role).active(active).tokenVersion(7).oidcSubject(subject)
            .build();
    }

    private Map<String, String> body() {
        return Map.of("code", "the-code", "state", "the-state");
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown identity is REFUSED, not welcomed — SSO does not create operators")
    void unknownIdentityIsRefused() {
        providerReturns("sub-1", "stranger@example.com", true);
        when(users.findByOidcSubject(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.callback(body(), request, response))
            .isInstanceOf(ControlCenterException.class)
            .hasMessageContaining("No Control Center account exists");

        verify(users, never()).save(any());
        verify(jwt, never()).generateToken(any(), any(), anyInt());
    }

    @Test
    @DisplayName("an unverified email is refused — on some IdPs the user picks it themselves")
    void unverifiedEmailIsRefused() {
        // Without this, an attacker sets their profile email to a real operator's address and
        // inherits that operator's role.
        providerReturns("sub-attacker", "ops@example.com", false);

        assertThatThrownBy(() -> controller.callback(body(), request, response))
            .hasMessageContaining("not verified");
        verify(users, never()).findByEmailIgnoreCase(any());
        verify(jwt, never()).generateToken(any(), any(), anyInt());
    }

    @Test
    @DisplayName("a deactivated account is refused even with a perfectly valid token")
    void deactivatedAccountIsRefused() {
        providerReturns("sub-1", "ops@example.com", true);
        when(users.findByOidcSubject("sub-1"))
            .thenReturn(Optional.of(operator(ControlCenterUser.Role.ADMIN, false, "sub-1")));

        assertThatThrownBy(() -> controller.callback(body(), request, response))
            .hasMessageContaining("deactivated");
        verify(jwt, never()).generateToken(any(), any(), anyInt());
    }

    @Test
    @DisplayName("a different IdP subject for a linked account is refused, never silently relinked")
    void changedSubjectIsRefused() {
        // The reassigned-address case: the operator left, someone new holds ops@, and relinking
        // would hand them the departed operator's role.
        providerReturns("sub-NEW-PERSON", "ops@example.com", true);
        when(users.findByOidcSubject("sub-NEW-PERSON")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ops@example.com"))
            .thenReturn(Optional.of(operator(ControlCenterUser.Role.SUPER_ADMIN, true, "sub-ORIGINAL")));

        assertThatThrownBy(() -> controller.callback(body(), request, response))
            .hasMessageContaining("linked to a different identity");
        verify(jwt, never()).generateToken(any(), any(), anyInt());
    }

    // ── the successful path ─────────────────────────────────────────────────

    @Test
    @DisplayName("role and tokenVersion come from the DATABASE row, never from the token")
    void roleAndTokenVersionComeFromTheRow() {
        providerReturns("sub-1", "ops@example.com", true);
        when(users.findByOidcSubject("sub-1"))
            .thenReturn(Optional.of(operator(ControlCenterUser.Role.SUPPORT, true, "sub-1")));

        var result = controller.callback(body(), request, response);

        // tokenVersion 7 is what makes "revoke sessions" work for an SSO session too.
        verify(jwt).generateToken("ops@example.com", "ROLE_SUPPORT", 7);
        assertThat(result.getBody()).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("role", "ROLE_SUPPORT");
    }

    @Test
    @DisplayName("first sign-in links the subject to the existing account")
    void firstSignInLinksSubject() {
        providerReturns("sub-1", "ops@example.com", true);
        ControlCenterUser unlinked = operator(ControlCenterUser.Role.ADMIN, true, null);
        when(users.findByOidcSubject("sub-1")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ops@example.com")).thenReturn(Optional.of(unlinked));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));

        controller.callback(body(), request, response);

        ArgumentCaptor<ControlCenterUser> saved = ArgumentCaptor.forClass(ControlCenterUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getOidcSubject()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("an uppercase stored address still matches — case must not lock an operator out")
    void matchesRegardlessOfStoredCase() {
        providerReturns("sub-1", "OPS@Example.COM", true);
        when(users.findByOidcSubject("sub-1")).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("ops@example.com"))
            .thenReturn(Optional.of(operator(ControlCenterUser.Role.ADMIN, true, "sub-1")));

        controller.callback(body(), request, response);
        verify(jwt).generateToken(eq("ops@example.com"), eq("ROLE_ADMIN"), anyInt());
    }

    // ── auto-provisioning ───────────────────────────────────────────────────

    @Test
    @DisplayName("auto-provisioning refuses to grant SUPER_ADMIN, however it is configured")
    void autoProvisioningCannotGrantSuperAdmin() {
        // One environment variable must not be able to hand full fleet control to everyone the
        // IdP can authenticate.
        ReflectionTestUtils.setField(controller, "autoProvisionRole", "SUPER_ADMIN");
        providerReturns("sub-new", "newcomer@example.com", true);
        when(users.findByOidcSubject(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.callback(body(), request, response))
            .hasMessageContaining("misconfigured");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("auto-provisioning creates a VIEWER when explicitly configured to")
    void autoProvisioningCreatesViewer() {
        ReflectionTestUtils.setField(controller, "autoProvisionRole", "VIEWER");
        providerReturns("sub-new", "newcomer@example.com", true);
        when(users.findByOidcSubject(any())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(i -> {
            ControlCenterUser u = i.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        controller.callback(body(), request, response);

        ArgumentCaptor<ControlCenterUser> saved = ArgumentCaptor.forClass(ControlCenterUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(ControlCenterUser.Role.VIEWER);
        assertThat(saved.getValue().getOidcSubject()).isEqualTo("sub-new");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");   // no usable password
    }

    // ── browser binding and audit ───────────────────────────────────────────

    @Test
    @DisplayName("the browser cookie is passed to the exchange and cleared afterwards")
    void cookieIsPassedThenCleared() {
        providerReturns("sub-1", "ops@example.com", true);
        when(users.findByOidcSubject("sub-1"))
            .thenReturn(Optional.of(operator(ControlCenterUser.Role.ADMIN, true, "sub-1")));

        controller.callback(body(), request, response);

        verify(oidc).exchangeCode("the-code", "the-state", "browser-secret");
        Cookie cleared = response.getCookie(OidcController.SSO_COOKIE);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();      // one sign-in per redirect
        assertThat(cleared.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("authorize sets an HttpOnly cookie and keeps the secret out of the response body")
    void authorizeSetsHttpOnlyCookie() {
        when(oidc.authorizationRequest()).thenReturn(
            new OidcService.AuthorizationRequest("https://idp/authorize?state=s", "the-secret"));

        var result = controller.authorize(response);

        Cookie set = response.getCookie(OidcController.SSO_COOKIE);
        assertThat(set).isNotNull();
        assertThat(set.getValue()).isEqualTo("the-secret");
        assertThat(set.isHttpOnly()).isTrue();
        assertThat(set.getSecure()).isTrue();
        // Anything the page can read, a malicious script on the page can exfiltrate.
        assertThat(String.valueOf(result.getBody())).doesNotContain("the-secret");
    }

    @Test
    @DisplayName("a refusal is audited as a FAILURE with the client IP, and CRLF is stripped")
    void refusalsAreAudited() {
        // A self-asserted value is attacker-controlled at this point; a newline in it would forge
        // log lines and split the audit row's actor field.
        providerReturns("sub-x\nFORGED: admin logged in", "ops@example.com", false);

        assertThatThrownBy(() -> controller.callback(body(), request, response));

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(audit).log(actor.capture(), any(), eq("SSO_LOGIN_DENIED"), any(), any(), any(),
                          eq("203.0.113.9"), any(), eq(AuditLog.Status.FAILURE));
        assertThat(actor.getValue()).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("a successful sign-in is audited and clears any stale password lockout")
    void successIsAuditedAndClearsLockout() {
        providerReturns("sub-1", "ops@example.com", true);
        when(users.findByOidcSubject("sub-1"))
            .thenReturn(Optional.of(operator(ControlCenterUser.Role.ADMIN, true, "sub-1")));

        controller.callback(body(), request, response);

        verify(audit).log(eq("ops@example.com"), eq("ops@example.com"), eq("SSO_LOGIN"),
                          eq("ControlCenterUser"), any(), any(), eq("203.0.113.9"), any(),
                          eq(AuditLog.Status.SUCCESS));
        verify(userService).recordSuccessfulLogin("ops@example.com");
        verify(userService).recordLogin("ops@example.com");
    }
}
