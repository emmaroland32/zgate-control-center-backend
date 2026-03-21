package com.zgate.nexus.controller;

import com.zgate.nexus.payload.request.LoginRequest;
import com.zgate.nexus.security.JwtUtils;
import com.zgate.nexus.service.NexusUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
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
    private final NexusUserService userService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        Authentication auth;
        try {
            auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", 401, "message", "Invalid email or password"));
        }

        String role = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .findFirst().orElse("ROLE_VIEWER");

        String token = jwtUtils.generateToken(req.getEmail(), role);
        userService.recordLogin(req.getEmail());

        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", req.getEmail(),
            "role", role
        ));
    }
}
