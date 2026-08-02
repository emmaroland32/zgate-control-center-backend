package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.payload.request.CreateUserRequest;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.web.ResponseMessage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final ControlCenterUserService service;
    private final com.zgate.controlcenter.service.AuditService audit;
    private final com.zgate.controlcenter.security.ClientIpResolver clientIpResolver;

    /** Identity changes are exactly what an incident review asks about — none were audited before. */
    private void auditIdentity(String actor, String action, String targetId,
                               jakarta.servlet.http.HttpServletRequest http, String details) {
        audit.log(actor, actor, action, "ControlCenterUser", targetId, null,
                  clientIpResolver.resolve(http), details,
                  com.zgate.controlcenter.domain.AuditLog.Status.SUCCESS);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<ControlCenterUser>> findAll() { return ResponseEntity.ok(service.findAll()); }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<ControlCenterUser> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }

    // ── MFA (self-service enrollment; break-glass disable is SUPER_ADMIN) ───

    /**
     * Step 1: stage a TOTP secret for the CALLING operator. An account that already has MFA on
     * must send a current code ({@code currentCode}) — the live factor is never disabled here.
     */
    @PostMapping("/me/mfa/enroll")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<java.util.Map<String, String>> mfaEnroll(
            @RequestBody(required = false) java.util.Map<String, String> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        var result = service.mfaEnroll(me.getUsername(), body == null ? null : body.get("currentCode"));
        auditIdentity(me.getUsername(), "MFA_ENROLL_STARTED", me.getUsername(), http, null);
        return ResponseEntity.ok(result);
    }

    /** Step 2: a valid code proves the authenticator holds the staged secret — MFA turns on. */
    @PostMapping("/me/mfa/activate")
    @PreAuthorize("isAuthenticated()")
    @ResponseMessage(code = "MFA_ENABLED", value = "Two-factor authentication is now required for your sign-in")
    public ResponseEntity<Void> mfaActivate(
            @RequestBody java.util.Map<String, String> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        service.mfaActivate(me.getUsername(), body.get("code"));
        auditIdentity(me.getUsername(), "MFA_ENABLED", me.getUsername(), http, null);
        return ResponseEntity.ok().build();
    }

    /** Lost-authenticator break-glass. Also revokes the account's outstanding sessions. */
    @PostMapping("/{id}/mfa/disable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "MFA_DISABLED", value = "Two-factor authentication disabled; sessions revoked")
    public ResponseEntity<Void> mfaDisable(@PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        service.mfaDisable(id);
        auditIdentity(me.getUsername(), "MFA_DISABLED_BY_ADMIN", id.toString(), http,
                      "break-glass reset; sessions revoked");
        return ResponseEntity.ok().build();
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "USER_CREATED", value = "User created")
    public ResponseEntity<ControlCenterUser> create(@Valid @RequestBody CreateUserRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        ControlCenterUser created = service.create(req);
        auditIdentity(me.getUsername(), "USER_CREATED", created.getId().toString(), http,
                      "role=" + created.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "USER_UPDATED", value = "User updated")
    public ResponseEntity<ControlCenterUser> update(@PathVariable UUID id,
                                            @Valid @RequestBody CreateUserRequest req,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        ControlCenterUser updated = service.update(id, req);
        auditIdentity(me.getUsername(), "USER_UPDATED", id.toString(), http,
                      "role=" + updated.getRole());
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_DISABLED", value = "User disabled")
    public ResponseEntity<?> disable(@PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        service.disable(id);
        auditIdentity(me.getUsername(), "USER_DISABLED", id.toString(), http, "sessions revoked");
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_SESSIONS_REVOKED", value = "Sessions revoked")
    public ResponseEntity<?> revokeSessions(@PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails me,
            jakarta.servlet.http.HttpServletRequest http) {
        service.revokeSessions(id);
        auditIdentity(me.getUsername(), "USER_SESSIONS_REVOKED", id.toString(), http, null);
        return ResponseEntity.ok(java.util.Map.of("id", id.toString()));
    }
}
