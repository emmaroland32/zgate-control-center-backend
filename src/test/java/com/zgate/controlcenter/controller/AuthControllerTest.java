package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.LoginRequest;
import com.zgate.controlcenter.security.ClientIpResolver;
import com.zgate.controlcenter.security.JwtUtils;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.service.IdentityEvents;
import com.zgate.controlcenter.service.StepUpTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Every sign-in outcome must leave an audit row: the activity monitor is only as good as what
 * the sign-in path records, and until now a password brute-force left no trace at all.
 */
class AuthControllerTest {

    private static final String EMAIL = "ops@example.com";
    private static final String IP = "203.0.113.9";

    private AuthenticationManager authManager;
    private ControlCenterUserService userService;
    private AuditService audit;
    private AuthController controller;
    private MockHttpServletRequest http;

    @BeforeEach
    void setUp() {
        authManager = mock(AuthenticationManager.class);
        userService = mock(ControlCenterUserService.class);
        audit = mock(AuditService.class);
        JwtUtils jwt = mock(JwtUtils.class);
        when(jwt.generateToken(any(), any(), anyInt())).thenReturn("issued-token");
        controller = new AuthController(authManager, jwt, userService, mock(StepUpTicketService.class),
                                        audit, new ClientIpResolver(""));
        http = new MockHttpServletRequest();
        http.setRemoteAddr(IP);
    }

    private static LoginRequest login(String mfaCode) {
        LoginRequest r = new LoginRequest();
        r.setEmail(EMAIL);
        r.setPassword("pw");
        r.setMfaCode(mfaCode);
        return r;
    }

    private void passwordAccepted() {
        when(authManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
            EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** Captures every audit row written, in order, as (action, status, details, ip). */
    private List<String[]> auditRows() {
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AuditLog.Status> status = ArgumentCaptor.forClass(AuditLog.Status.class);
        verify(audit, atLeast(0)).log(eq(EMAIL), eq(EMAIL), action.capture(), eq(IdentityEvents.ENTITY),
                                      eq(EMAIL), isNull(), ip.capture(), details.capture(), status.capture());
        List<String[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < action.getAllValues().size(); i++) {
            rows.add(new String[] { action.getAllValues().get(i), status.getAllValues().get(i).name(),
                                    String.valueOf(details.getAllValues().get(i)), ip.getAllValues().get(i) });
        }
        return rows;
    }

    @Test
    @DisplayName("a successful sign-in records LOGIN_SUCCESS with the role and the client IP")
    void successIsAudited() {
        passwordAccepted();
        when(userService.tokenVersionOf(EMAIL)).thenReturn(2);

        var response = controller.login(login(null), http);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String[]> rows = auditRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(IdentityEvents.LOGIN_SUCCESS, "SUCCESS", "role=ROLE_ADMIN", IP);
        verify(userService).recordSuccessfulLogin(EMAIL);
        verify(userService).recordLogin(EMAIL);
    }

    @Test
    @DisplayName("a wrong password records LOGIN_FAILED, and the attempt that locks the account also records ACCOUNT_LOCKED")
    void failureAndLockoutAreAudited() {
        when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));
        when(userService.recordFailedLogin(EMAIL)).thenReturn(false, true);

        assertThatThrownBy(() -> controller.login(login(null), http)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> controller.login(login(null), http)).isInstanceOf(BadCredentialsException.class);

        List<String[]> rows = auditRows();
        assertThat(rows).extracting(r -> r[0]).containsExactly(
            IdentityEvents.LOGIN_FAILED, IdentityEvents.LOGIN_FAILED, IdentityEvents.ACCOUNT_LOCKED);
        assertThat(rows.get(0)[1]).isEqualTo("FAILURE");
        assertThat(rows.get(2)[1]).isEqualTo("WARNING");
        verify(userService, never()).recordSuccessfulLogin(any());
    }

    @Test
    @DisplayName("an attempt against a locked account is recorded and never reaches the password check")
    void lockedAttemptIsAudited() {
        doThrow(new ControlCenterException("locked", "ACCOUNT_LOCKED", HttpStatus.TOO_MANY_REQUESTS))
            .when(userService).requireNotLockedOut(EMAIL);

        assertThatThrownBy(() -> controller.login(login(null), http))
            .isInstanceOf(ControlCenterException.class)
            .extracting("code").isEqualTo("ACCOUNT_LOCKED");

        verify(authManager, never()).authenticate(any());
        List<String[]> rows = auditRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(IdentityEvents.LOGIN_LOCKED);
        assertThat(rows.get(0)[1]).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("a wrong second-factor code is a recorded failure; a missing one is only a prompt")
    void mfaOutcomes() {
        passwordAccepted();
        doThrow(new ControlCenterException("bad code", "MFA_INVALID", HttpStatus.UNAUTHORIZED))
            .when(userService).requireMfaIfEnabled(EMAIL, "000000");
        doThrow(new ControlCenterException("code needed", "MFA_REQUIRED", HttpStatus.UNAUTHORIZED))
            .when(userService).requireMfaIfEnabled(EMAIL, null);

        assertThatThrownBy(() -> controller.login(login("000000"), http))
            .extracting("code").isEqualTo("MFA_INVALID");
        assertThatThrownBy(() -> controller.login(login(null), http))
            .extracting("code").isEqualTo("MFA_REQUIRED");

        List<String[]> rows = auditRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(IdentityEvents.LOGIN_MFA_FAILED);
        verify(userService, times(1)).recordFailedLogin(EMAIL);
    }

    @Test
    @DisplayName("the shipped default password is refused and the refusal is recorded")
    void defaultPasswordRefusalIsAudited() {
        passwordAccepted();
        doThrow(new ControlCenterException("default", "DEFAULT_PASSWORD_MUST_BE_CHANGED", HttpStatus.FORBIDDEN))
            .when(userService).requireNotDefaultPassword(EMAIL);

        assertThatThrownBy(() -> controller.login(login(null), http))
            .extracting("code").isEqualTo("DEFAULT_PASSWORD_MUST_BE_CHANGED");

        List<String[]> rows = auditRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo(IdentityEvents.LOGIN_REFUSED_DEFAULT_PASSWORD);
        assertThat(rows.get(0)[1]).isEqualTo("WARNING");
        verify(userService, never()).recordLogin(any());
    }
}
