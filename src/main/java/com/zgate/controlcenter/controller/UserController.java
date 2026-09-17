package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.CreateUserRequest;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.service.ControlCenterUserService.OperatorView;
import com.zgate.controlcenter.service.IdentityEvents;
import com.zgate.controlcenter.web.ResponseMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin management: the operators who run Control Center, and what they do.
 *
 * <p>Two rules sit underneath every mutation here and are enforced in the service, not the UI:
 * an ADMIN can never reach a SUPER_ADMIN account or grant that role, and nobody can change their
 * own role, disable themselves, or remove the last active super-admin. A refused call is itself
 * audited ({@code USER_ACTION_REFUSED}) — an operator probing for escalation is an event an
 * incident review wants to see.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final ControlCenterUserService service;
    private final AuditService audit;
    private final com.zgate.controlcenter.security.ClientIpResolver clientIpResolver;

    /** Identity changes are exactly what an incident review asks about. */
    private void auditIdentity(String actor, String action, String targetId,
                               HttpServletRequest http, String details) {
        audit.log(actor, actor, action, IdentityEvents.ENTITY, targetId, null,
                  clientIpResolver.resolve(http), details, AuditLog.Status.SUCCESS);
    }

    /**
     * Run a management action; if the privilege rules refuse it, record the refusal (as a FAILURE
     * row naming the attempted action) and rethrow so the caller still gets the 403.
     */
    private <T> T guarded(String actor, String attempted, String targetId, HttpServletRequest http,
                          java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (ControlCenterException e) {
            if (ControlCenterUserService.PRIVILEGE_CODE.equals(e.getCode())) {
                audit.log(actor, actor, IdentityEvents.USER_ACTION_REFUSED, IdentityEvents.ENTITY,
                          IdentityEvents.safeActor(targetId), null, clientIpResolver.resolve(http),
                          "attempted=" + attempted + "; reason=" + e.getMessage(), AuditLog.Status.FAILURE);
            }
            throw e;
        }
    }

    /** The security policy this server actually enforces — so the console can stop guessing. */
    @GetMapping("/security-policy")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> securityPolicy() {
        return ResponseEntity.ok(service.securityPolicy());
    }

    /** The calling operator's own account, as the console shows it. */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<OperatorView> me(@AuthenticationPrincipal UserDetails me) {
        return ResponseEntity.ok(service.viewByEmail(me.getUsername()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<List<OperatorView>> findAll() {
        return ResponseEntity.ok(service.listOperators());
    }

    // ── Activity monitor ────────────────────────────────────────────────────

    /** Headline counts (sign-ins, failures, lockouts, admin actions) over the trailing window. */
    @GetMapping("/activity/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<AuditService.ActivitySummary> activitySummary(
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(audit.activitySummary(hours));
    }

    /** Everything one operator did, plus everything done to their account, newest first. */
    @GetMapping("/{id}/activity")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Page<AuditLog>> activity(@PathVariable UUID id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "25") int size) {
        OperatorView target = service.view(id);
        return ResponseEntity.ok(audit.operatorActivity(target.email(), id,
            PageRequest.of(Math.max(0, page), AuditController.clampSize(size))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<OperatorView> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.view(id));
    }

    // ── MFA (self-service enrollment; break-glass disable is SUPER_ADMIN) ───

    /**
     * Step 1: stage a TOTP secret for the CALLING operator. An account that already has MFA on
     * must send a current code ({@code currentCode}) — the live factor is never disabled here.
     */
    @PostMapping("/me/mfa/enroll")
    @PreAuthorize("isAuthenticated()")
    // Re-enrolment checks a current code: without a cap a stolen session could guess it.
    @com.zgate.controlcenter.security.RateLimit(limit = 5, windowSeconds = 60,
        keyBy = com.zgate.controlcenter.security.RateLimit.KeyStrategy.USER)
    public ResponseEntity<Map<String, String>> mfaEnroll(
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        var result = service.mfaEnroll(me.getUsername(), body == null ? null : body.get("currentCode"));
        auditIdentity(me.getUsername(), IdentityEvents.MFA_ENROLL_STARTED, me.getUsername(), http, null);
        return ResponseEntity.ok(result);
    }

    /** Step 2: a valid code proves the authenticator holds the staged secret — MFA turns on. */
    @PostMapping("/me/mfa/activate")
    @PreAuthorize("isAuthenticated()")
    @com.zgate.controlcenter.security.RateLimit(limit = 5, windowSeconds = 60,
        keyBy = com.zgate.controlcenter.security.RateLimit.KeyStrategy.USER)
    @ResponseMessage(code = "MFA_ENABLED", value = "Two-factor authentication is now required for your sign-in")
    public ResponseEntity<Void> mfaActivate(@RequestBody Map<String, String> body,
                                            @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        service.mfaActivate(me.getUsername(), body.get("code"));
        auditIdentity(me.getUsername(), IdentityEvents.MFA_ENABLED, me.getUsername(), http, null);
        return ResponseEntity.ok().build();
    }

    /** Lost-authenticator break-glass. Also revokes the account's outstanding sessions. */
    @PostMapping("/{id}/mfa/disable")
    @com.zgate.controlcenter.security.RequiresStepUp
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "MFA_DISABLED", value = "Two-factor authentication disabled; sessions revoked")
    public ResponseEntity<Void> mfaDisable(@PathVariable UUID id,
                                           @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        guarded(me.getUsername(), IdentityEvents.MFA_DISABLED_BY_ADMIN, id.toString(), http, () -> {
            service.mfaDisable(me.getUsername(), id);
            return null;
        });
        auditIdentity(me.getUsername(), IdentityEvents.MFA_DISABLED_BY_ADMIN, id.toString(), http,
                      "break-glass reset; sessions revoked");
        return ResponseEntity.ok().build();
    }

    // ── Account lifecycle ───────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "USER_CREATED", value = "Operator account created")
    public ResponseEntity<OperatorView> create(@Valid @RequestBody CreateUserRequest req,
                                               @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        ControlCenterUser created = guarded(me.getUsername(), IdentityEvents.USER_CREATED, req.getEmail(), http,
            () -> service.create(me.getUsername(), req));
        auditIdentity(me.getUsername(), IdentityEvents.USER_CREATED, created.getId().toString(), http,
                      "email=" + created.getEmail() + "; role=" + created.getRole());
        return ResponseEntity.status(HttpStatus.CREATED).body(ControlCenterUserService.toView(created));
    }

    /** Name and role. Passwords are reset through {@code /reset-password}, never through here. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    @ResponseMessage(code = "USER_UPDATED", value = "Operator account updated")
    public ResponseEntity<OperatorView> update(@PathVariable UUID id, @Valid @RequestBody CreateUserRequest req,
                                               @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        ControlCenterUserService.UpdateResult result = guarded(me.getUsername(), IdentityEvents.USER_UPDATED,
            id.toString(), http, () -> service.update(me.getUsername(), id, req));
        auditIdentity(me.getUsername(), IdentityEvents.USER_UPDATED, id.toString(), http, result.changes());
        return ResponseEntity.ok(ControlCenterUserService.toView(result.user()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_DISABLED", value = "Operator disabled; sessions revoked")
    public ResponseEntity<Map<String, String>> disable(@PathVariable UUID id,
                                                       @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        guarded(me.getUsername(), IdentityEvents.USER_DISABLED, id.toString(), http, () -> {
            service.disable(me.getUsername(), id);
            return null;
        });
        auditIdentity(me.getUsername(), IdentityEvents.USER_DISABLED, id.toString(), http, "sessions revoked");
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    /** Reinstate a disabled operator. Their previous sessions stay dead; they sign in afresh. */
    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_ENABLED", value = "Operator enabled")
    public ResponseEntity<Map<String, String>> enable(@PathVariable UUID id,
                                                      @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        guarded(me.getUsername(), IdentityEvents.USER_ENABLED, id.toString(), http, () -> {
            service.enable(me.getUsername(), id);
            return null;
        });
        auditIdentity(me.getUsername(), IdentityEvents.USER_ENABLED, id.toString(), http, null);
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    /**
     * Rotate your own password. Needs the current password (and the second factor when enabled);
     * every session of yours, this one included, is revoked — sign in again with the new one.
     */
    @PostMapping("/me/password")
    @PreAuthorize("isAuthenticated()")
    @com.zgate.controlcenter.security.RateLimit(limit = 5, windowSeconds = 60,
        keyBy = com.zgate.controlcenter.security.RateLimit.KeyStrategy.USER)
    @ResponseMessage(code = "PASSWORD_CHANGED", value = "Password changed — sign in again with the new one")
    public ResponseEntity<Void> changeMyPassword(@RequestBody Map<String, String> body,
                                                 @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        String email = me.getUsername();
        service.requireNotLockedOut(email);
        try {
            service.changeOwnPassword(email, body == null ? null : body.get("currentPassword"),
                                      body == null ? null : body.get("mfaCode"),
                                      body == null ? null : body.get("newPassword"));
        } catch (ControlCenterException e) {
            if ("INVALID_CREDENTIALS".equals(e.getCode()) || "MFA_INVALID".equals(e.getCode())) {
                audit.log(email, email, IdentityEvents.PASSWORD_CHANGE_FAILED, IdentityEvents.ENTITY, email, null,
                          clientIpResolver.resolve(http), e.getCode(), AuditLog.Status.FAILURE);
            }
            throw e;
        }
        auditIdentity(email, IdentityEvents.PASSWORD_CHANGED, email, http, "all sessions revoked");
        return ResponseEntity.ok().build();
    }

    /** Clear a sign-in lockout early, once the operator has been identified out of band. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    // Unlocks are cheap to call and undo a lockout; cap them so a compromised admin session
    // cannot turn the lockout into a no-op for everyone at wire speed.
    @com.zgate.controlcenter.security.RateLimit(limit = 10, windowSeconds = 60,
        keyBy = com.zgate.controlcenter.security.RateLimit.KeyStrategy.USER)
    @ResponseMessage(code = "USER_UNLOCKED", value = "Sign-in lockout cleared")
    public ResponseEntity<Map<String, String>> unlock(@PathVariable UUID id,
                                                      @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        guarded(me.getUsername(), IdentityEvents.USER_UNLOCKED, id.toString(), http, () -> {
            service.unlock(me.getUsername(), id);
            return null;
        });
        auditIdentity(me.getUsername(), IdentityEvents.USER_UNLOCKED, id.toString(), http, null);
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    /**
     * Set a new password for an operator who cannot sign in. Step-up: this is account takeover if
     * done from a stolen session. The new password is policy-checked, every outstanding session of
     * the target dies, and any lockout is cleared so they can use it immediately.
     */
    @PostMapping("/{id}/reset-password")
    @com.zgate.controlcenter.security.RequiresStepUp
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_PASSWORD_RESET", value = "Password reset; the operator's sessions were revoked")
    public ResponseEntity<Map<String, String>> resetPassword(@PathVariable UUID id,
                                                             @RequestBody Map<String, String> body,
                                                             @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        String password = body == null ? null : body.get("password");
        guarded(me.getUsername(), IdentityEvents.USER_PASSWORD_RESET, id.toString(), http, () -> {
            service.resetPassword(me.getUsername(), id, password);
            return null;
        });
        auditIdentity(me.getUsername(), IdentityEvents.USER_PASSWORD_RESET, id.toString(), http,
                      "sessions revoked; lockout cleared");
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "USER_SESSIONS_REVOKED", value = "Sessions revoked")
    public ResponseEntity<Map<String, String>> revokeSessions(@PathVariable UUID id,
                                                              @AuthenticationPrincipal UserDetails me, HttpServletRequest http) {
        guarded(me.getUsername(), IdentityEvents.USER_SESSIONS_REVOKED, id.toString(), http, () -> {
            service.revokeSessions(me.getUsername(), id);
            return null;
        });
        auditIdentity(me.getUsername(), IdentityEvents.USER_SESSIONS_REVOKED, id.toString(), http, null);
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }
}
