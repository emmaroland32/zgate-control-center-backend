package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.payload.request.LoginRequest;
import com.zgate.controlcenter.security.JwtUtils;
import com.zgate.controlcenter.security.RateLimit;
import com.zgate.controlcenter.service.ControlCenterUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtUtils jwtUtils;
    private final ControlCenterUserService userService;
    private final com.zgate.controlcenter.service.StepUpTicketService stepUpTickets;

    // Keyed by IP, not by account: the lockout in ControlCenterUserService already caps attempts
    // against ONE account, but nothing capped an attacker walking a password list across many
    // accounts from one source, or hammering the endpoint to enumerate which addresses lock out.
    @RateLimit(limit = 10, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.IP)
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        // Lockout first: without it this endpoint is an unthrottled oracle — both for passwords
        // and, once a password is known, for brute-forcing the 6-digit second factor.
        userService.requireNotLockedOut(req.getEmail());

        Authentication auth;
        try {
            // Let BadCredentialsException propagate to GlobalExceptionHandler so login errors use
            // the same coded envelope as the rest of the API (401 INVALID_CREDENTIALS).
            auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            userService.recordFailedLogin(req.getEmail());
            throw e;
        }

        // Second factor AFTER the password check, so a wrong password never reveals whether the
        // account has MFA. MFA_REQUIRED (valid password, no/blank code) tells the UI to show the
        // code field; a present-but-wrong code is MFA_INVALID and counts toward the lockout.
        try {
            userService.requireMfaIfEnabled(req.getEmail(), req.getMfaCode());
        } catch (com.zgate.controlcenter.exception.ControlCenterException e) {
            if ("MFA_INVALID".equals(e.getCode())) userService.recordFailedLogin(req.getEmail());
            throw e;
        }

        String role = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .findFirst().orElse("ROLE_VIEWER");

        String token = jwtUtils.generateToken(req.getEmail(), role,
            userService.tokenVersionOf(req.getEmail()));
        userService.recordSuccessfulLogin(req.getEmail());
        userService.recordLogin(req.getEmail());

        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", req.getEmail(),
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
            org.springframework.security.core.userdetails.UserDetails me) {
        if (me == null) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "Sign in before requesting a step-up ticket.",
                "UNAUTHORIZED", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        String email = me.getUsername();
        userService.requireNotLockedOut(email);
        try {
            authManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, body.get("password")));
        } catch (org.springframework.security.core.AuthenticationException e) {
            userService.recordFailedLogin(email);
            throw e;
        }
        try {
            userService.requireMfaIfEnabled(email, body.get("mfaCode"));
        } catch (com.zgate.controlcenter.exception.ControlCenterException e) {
            if ("MFA_INVALID".equals(e.getCode())) userService.recordFailedLogin(email);
            throw e;
        }
        userService.recordSuccessfulLogin(email);
        return ResponseEntity.ok(Map.of("ticket", stepUpTickets.issue(email), "expiresInSeconds", 300));
    }
}
