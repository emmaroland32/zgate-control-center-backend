package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.LoginRequest;
import com.zgate.controlcenter.security.ClientIpResolver;
import com.zgate.controlcenter.security.JwtUtils;
import com.zgate.controlcenter.security.RateLimit;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.service.IdentityEvents;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Password sign-in and step-up re-authentication.
 *
 * <p>Every outcome is audited — success, wrong password, wrong code, an attempt against a locked
 * account, the attempt that tripped the lockout, and the refused default credential. Until now
 * only SSO sign-ins left a trace, so the activity monitor could not show a brute-force attempt
 * against a password account at all. Audit writes are asynchronous and never affect the result.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtUtils jwtUtils;
    private final ControlCenterUserService userService;
    private final com.zgate.controlcenter.service.StepUpTicketService stepUpTickets;
    private final AuditService audit;
    private final ClientIpResolver clientIpResolver;

    private void auditSignIn(String email, String action, HttpServletRequest http, String details,
                             AuditLog.Status status) {
        // The attempted email is attacker-supplied on the failure paths. Cap it to the audit
        // columns (entity_id is 100 chars) and strip control characters: an over-long or
        // newline-laden address would otherwise make the async write fail and the row vanish —
        // exactly the failed attempt the monitor is meant to show.
        String who = IdentityEvents.safeActor(email);
        audit.log(who, who, action, IdentityEvents.ENTITY, who, null,
                  clientIpResolver.resolve(http), details, status);
    }

    // Keyed by IP, not by account: the lockout in ControlCenterUserService already caps attempts
    // against ONE account, but nothing capped an attacker walking a password list across many
    // accounts from one source, or hammering the endpoint to enumerate which addresses lock out.
    @RateLimit(limit = 10, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.IP)
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String email = req.getEmail();

        // Lockout first: without it this endpoint is an unthrottled oracle — both for passwords
        // and, once a password is known, for brute-forcing the 6-digit second factor.
        try {
            userService.requireNotLockedOut(email);
        } catch (ControlCenterException e) {
            auditSignIn(email, IdentityEvents.LOGIN_LOCKED, http, "attempt while locked", AuditLog.Status.WARNING);
            throw e;
        }

        Authentication auth;
        try {
            // Let BadCredentialsException propagate to GlobalExceptionHandler so login errors use
            // the same coded envelope as the rest of the API (401 INVALID_CREDENTIALS).
            auth = authManager.authenticate(new UsernamePasswordAuthenticationToken(email, req.getPassword()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            recordFailure(email, IdentityEvents.LOGIN_FAILED, "bad credentials", http);
            throw e;
        }

        // The committed default password must never yield a session, even when it "matches".
        try {
            userService.requireNotDefaultPassword(email);
        } catch (ControlCenterException e) {
            auditSignIn(email, IdentityEvents.LOGIN_REFUSED_DEFAULT_PASSWORD, http,
                        "shipped default password still in place", AuditLog.Status.WARNING);
            throw e;
        }

        // Second factor AFTER the password check, so a wrong password never reveals whether the
        // account has MFA. MFA_REQUIRED (valid password, no/blank code) tells the UI to show the
        // code field; a present-but-wrong code is MFA_INVALID and counts toward the lockout.
        try {
            userService.requireMfaIfEnabled(email, req.getMfaCode());
        } catch (ControlCenterException e) {
            if ("MFA_INVALID".equals(e.getCode())) {
                recordFailure(email, IdentityEvents.LOGIN_MFA_FAILED, "wrong second-factor code", http);
            }
            throw e;
        }

        String role = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .findFirst().orElse("ROLE_VIEWER");

        String token = jwtUtils.generateToken(email, role, userService.tokenVersionOf(email));
        userService.recordSuccessfulLogin(email);
        userService.recordLogin(email);
        auditSignIn(email, IdentityEvents.LOGIN_SUCCESS, http, "role=" + role, AuditLog.Status.SUCCESS);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", email,
            "role", role
        ));
    }

    /**
     * Re-authenticate to obtain a short-lived step-up ticket for a destructive action.
     *
     * <p>Requires the CURRENT password (and MFA code when enabled) from the already-signed-in
     * operator — a valid session is not sufficient, which is the entire point: it proves the person
     * at the keyboard right now is the account holder, not someone who found an unlocked laptop.
     * Failures count toward the same lockout as a normal sign-in, so this cannot be used as an
     * unthrottled password oracle against a session you have already stolen.
     */
    // Per-operator: this is reached with a valid session, so IP keying would let one compromised
    // session burn another operator's quota.
    @RateLimit(limit = 10, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.USER)
    @PostMapping("/step-up")
    public ResponseEntity<?> stepUp(
            @RequestBody Map<String, String> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            HttpServletRequest http) {
        if (me == null) {
            throw new ControlCenterException("Sign in before requesting a step-up ticket.",
                "UNAUTHORIZED", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        String email = me.getUsername();
        try {
            userService.requireNotLockedOut(email);
        } catch (ControlCenterException e) {
            auditSignIn(email, IdentityEvents.LOGIN_LOCKED, http, "step-up attempt while locked", AuditLog.Status.WARNING);
            throw e;
        }
        try {
            authManager.authenticate(new UsernamePasswordAuthenticationToken(email, body.get("password")));
        } catch (org.springframework.security.core.AuthenticationException e) {
            recordFailure(email, IdentityEvents.STEP_UP_FAILED, "bad credentials", http);
            throw e;
        }
        try {
            userService.requireMfaIfEnabled(email, body.get("mfaCode"));
        } catch (ControlCenterException e) {
            if ("MFA_INVALID".equals(e.getCode())) {
                recordFailure(email, IdentityEvents.STEP_UP_FAILED, "wrong second-factor code", http);
            }
            throw e;
        }
        userService.recordSuccessfulLogin(email);
        // Bind the ticket to the one action the console is about to retry ("METHOD /path"). A ticket
        // issued without an action opens any step-up endpoint — once — for older API clients.
        String action = body.get("action");
        auditSignIn(email, IdentityEvents.STEP_UP_SUCCESS, http,
                    action == null || action.isBlank() ? "unbound" : "action=" + IdentityEvents.safeActor(action),
                    AuditLog.Status.SUCCESS);
        return ResponseEntity.ok(Map.of("ticket", stepUpTickets.issue(email, action), "expiresInSeconds", 300));
    }

    /** Count the failure toward the lockout, and audit both the failure and a lockout it trips. */
    private void recordFailure(String email, String action, String reason, HttpServletRequest http) {
        boolean lockedNow = userService.recordFailedLogin(email);
        auditSignIn(email, action, http, reason, AuditLog.Status.FAILURE);
        if (lockedNow) {
            auditSignIn(email, IdentityEvents.ACCOUNT_LOCKED, http,
                        "lockout threshold reached after repeated failures", AuditLog.Status.WARNING);
        }
    }
}
