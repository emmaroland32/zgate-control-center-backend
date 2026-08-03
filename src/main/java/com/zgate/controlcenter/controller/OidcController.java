package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.domain.AuditLog;
import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.repository.ControlCenterUserRepository;
import com.zgate.controlcenter.security.ClientIpResolver;
import com.zgate.controlcenter.security.JwtUtils;
import com.zgate.controlcenter.security.RateLimit;
import com.zgate.controlcenter.service.AuditService;
import com.zgate.controlcenter.service.ControlCenterUserService;
import com.zgate.controlcenter.service.OidcService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Single sign-on for operators, so the console can sit behind the company IdP.
 *
 * <p>Left alongside password sign-in on purpose: a wrong issuer, an expired client secret or an IdP
 * outage must not lock the vendor out of the console that runs every customer's system.
 */
@RestController
@RequestMapping("/api/v1/auth/oidc")
@RequiredArgsConstructor
@Slf4j
public class OidcController {

    /** Holds the per-sign-in secret. HttpOnly, so script on the page cannot read it. */
    static final String SSO_COOKIE = "cc_sso_state";

    /**
     * Auto-provisioning may only grant a read-mostly role. Handing SUPER_ADMIN to everyone the IdP
     * can authenticate — which in most tenants is every employee, contractor and guest — is not a
     * configuration anyone should be able to reach by setting one environment variable.
     */
    private static final Set<ControlCenterUser.Role> PROVISIONABLE =
        EnumSet.of(ControlCenterUser.Role.VIEWER, ControlCenterUser.Role.SUPPORT);

    private final OidcService oidc;
    private final ControlCenterUserRepository users;
    private final ControlCenterUserService userService;
    private final JwtUtils jwtUtils;
    private final AuditService audit;
    private final ClientIpResolver clientIp;
    private final PasswordEncoder passwordEncoder;

    /**
     * Role granted to an operator created on first SSO sign-in. Blank — the default — means SSO
     * never creates anyone, and the account must already exist here.
     */
    @Value("${controlcenter.oidc.autoProvisionRole:}")
    private String autoProvisionRole;

    @Value("${controlcenter.oidc.cookieSecure:true}")
    private boolean cookieSecure;

    @PostConstruct
    void announceProvisioning() {
        if (autoProvisionRole != null && !autoProvisionRole.isBlank()) {
            log.warn("SSO AUTO-PROVISIONING IS ON: every identity {} can authenticate will receive "
                   + "a Control Center account with role {}.", "your IdP", autoProvisionRole);
        }
    }

    /** Whether SSO is available, so the sign-in page knows whether to offer the button. */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("enabled", oidc.isEnabled()));
    }

    /** Start sign-in: returns the provider URL, and binds this sign-in to this browser. */
    // Anonymous, and each call mints signed state and may reach the provider — an unthrottled
    // oracle otherwise.
    @RateLimit(limit = 20, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.IP)
    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(HttpServletResponse response) {
        OidcService.AuthorizationRequest req = oidc.authorizationRequest();

        Cookie cookie = new Cookie(SSO_COOKIE, req.browserSecret());
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(600);                      // matches the state's own freshness window
        cookie.setAttribute("SameSite", "Lax");     // Lax, not Strict: this survives the IdP redirect
        response.addCookie(cookie);

        // The state stays out of the response body: the browser carries it in the redirect, and
        // anything the page can read, a malicious script on the page can exfiltrate.
        return ResponseEntity.ok(Map.of("authorizationUrl", req.authorizationUrl()));
    }

    /**
     * Finish sign-in. Validates the ID token, maps it to an operator, and issues a console token.
     *
     * <p>The mapping is the security boundary. An IdP will happily authenticate every employee,
     * contractor and guest in the tenant; only the ones with an account here may run the fleet. So
     * a verified token is necessary but not sufficient — unless auto-provisioning is deliberately
     * turned on with a role, an unknown subject is refused rather than welcomed.
     */
    @RateLimit(limit = 20, windowSeconds = 60, keyBy = RateLimit.KeyStrategy.IP)
    @PostMapping("/callback")
    public ResponseEntity<?> callback(@RequestBody Map<String, String> body,
                                      HttpServletRequest http,
                                      HttpServletResponse response) {
        String ip = clientIp.resolve(http);
        String browserSecret = cookieValue(http);
        clearCookie(response);   // one sign-in per redirect, whatever the outcome

        OidcService.OidcIdentity id =
            oidc.exchangeCode(body.get("code"), body.get("state"), browserSecret);

        // An unverified address is an impersonation primitive: on providers that let a user set
        // their own email, it would map an attacker's token onto someone else's operator account.
        if (!id.emailVerified()) {
            // Audit the SUBJECT, not the address — the address is attacker-chosen at this point.
            deny(id.subject(), "email not verified by the provider", ip);
            throw new ControlCenterException(
                "Your identity provider has not verified this email address.",
                "OIDC_EMAIL_UNVERIFIED", HttpStatus.FORBIDDEN);
        }
        // Locale.ROOT: the default locale's Turkish-I rule maps ADMIN@ZGATE.IO to admın@zgate.ıo,
        // which would silently fail to match — or match the wrong row.
        String email = id.email() == null ? null : id.email().trim().toLowerCase(Locale.ROOT);
        if (email == null || email.isBlank()) {
            deny(id.subject(), "provider returned no email", ip);
            throw new ControlCenterException(
                "The identity provider returned no email address to match an operator against.",
                "OIDC_NO_EMAIL", HttpStatus.FORBIDDEN);
        }

        // Subject first: it is immutable, whereas an email address can be reassigned to a new
        // person. Falling back to email is only for the first sign-in, which links the two.
        ControlCenterUser user = users.findByOidcSubject(id.subject())
            .orElseGet(() -> users.findByEmailIgnoreCase(email).orElse(null));

        if (user == null) {
            if (autoProvisionRole == null || autoProvisionRole.isBlank()) {
                deny(email, "no operator account", ip);
                throw new ControlCenterException(
                    "No Control Center account exists for " + email + ". Ask an administrator to "
                  + "create one before signing in with SSO.",
                    "OIDC_NO_ACCOUNT", HttpStatus.FORBIDDEN);
            }
            user = provision(email, id.subject(), ip);
        } else if (user.getOidcSubject() == null) {
            user.setOidcSubject(id.subject());
            user = users.save(user);
        } else if (!user.getOidcSubject().equals(id.subject())) {
            // Same address, different person at the IdP. Relinking here would hand a departed
            // operator's role to whoever now holds their address.
            deny(email, "subject changed for a linked account", ip);
            throw new ControlCenterException(
                "This account is linked to a different identity. Contact an administrator.",
                "OIDC_SUBJECT_MISMATCH", HttpStatus.FORBIDDEN);
        }

        if (!user.isActive()) {
            deny(email, "account deactivated", ip);
            throw new ControlCenterException("This account has been deactivated.",
                "ACCOUNT_DISABLED", HttpStatus.FORBIDDEN);
        }

        String role = "ROLE_" + user.getRole().name();
        String token = jwtUtils.generateToken(user.getEmail(), role, user.getTokenVersion());
        // Clears any password-lockout backoff: the operator has just proved themselves at the IdP,
        // so leaving a stale lock in place would block their next password sign-in for no reason.
        userService.recordSuccessfulLogin(user.getEmail());
        userService.recordLogin(user.getEmail());
        audit.log(user.getEmail(), user.getEmail(), "SSO_LOGIN", "ControlCenterUser",
                  user.getId().toString(), null, ip, "subject=" + safe(id.subject()),
                  AuditLog.Status.SUCCESS);

        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", user.getEmail(),
            "role", role
        ));
    }

    private ControlCenterUser provision(String email, String subject, String ip) {
        ControlCenterUser.Role role;
        try {
            role = ControlCenterUser.Role.valueOf(autoProvisionRole.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Refuse rather than guess: falling back to a default role here would silently grant
            // whatever that default is to everyone the IdP can authenticate.
            log.error("controlcenter.oidc.autoProvisionRole='{}' is not a valid role", autoProvisionRole);
            throw new ControlCenterException(
                "SSO auto-provisioning is misconfigured; contact an administrator.",
                "OIDC_PROVISION_MISCONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!PROVISIONABLE.contains(role)) {
            log.error("controlcenter.oidc.autoProvisionRole={} is not auto-provisionable; allowed: {}",
                      role, PROVISIONABLE);
            throw new ControlCenterException(
                "SSO auto-provisioning is misconfigured; contact an administrator.",
                "OIDC_PROVISION_MISCONFIGURED", HttpStatus.SERVICE_UNAVAILABLE);
        }
        ControlCenterUser created;
        try {
            created = users.save(ControlCenterUser.builder()
                .name(email)
                .email(email)
                // No usable local password: this account signs in through the IdP. A random hash
                // beats a blank or fixed one, which would make every provisioned account share a
                // credential.
                .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                .role(role)
                .oidcSubject(subject)
                .active(true)
                .build());
        } catch (DataIntegrityViolationException e) {
            // Two first sign-ins raced onto the unique email or subject constraint. The other one
            // won; use its row rather than failing a legitimate sign-in with a 500.
            return users.findByOidcSubject(subject)
                .or(() -> users.findByEmailIgnoreCase(email))
                .orElseThrow(() -> e);
        }
        audit.log(email, email, "SSO_USER_PROVISIONED", "ControlCenterUser",
                  created.getId().toString(), null, ip,
                  "auto-provisioned via SSO as " + role.name() + "; subject=" + safe(subject),
                  AuditLog.Status.SUCCESS);
        return created;
    }

    private String cookieValue(HttpServletRequest http) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (SSO_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(SSO_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void deny(String actor, String reason, String ip) {
        String who = safe(actor);
        log.warn("SSO sign-in refused for {}: {}", who, reason);
        audit.log(who, who, "SSO_LOGIN_DENIED", "ControlCenterUser", null, null, ip,
                  reason, AuditLog.Status.FAILURE);
    }

    /**
     * Strip line breaks and cap the length before anything provider-supplied reaches a log line or
     * the audit trail. At the point {@link #deny} is called the value can still be a self-asserted,
     * unverified claim — a newline in it would forge log entries and split the audit row's actor.
     */
    private static String safe(String value) {
        if (value == null) return "unknown";
        String cleaned = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
