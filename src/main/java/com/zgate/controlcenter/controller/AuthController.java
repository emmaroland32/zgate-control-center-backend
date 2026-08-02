package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.payload.request.LoginRequest;
import com.zgate.controlcenter.security.JwtUtils;
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
}
