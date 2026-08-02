package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.CreateUserRequest;
import com.zgate.controlcenter.repository.ControlCenterUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ControlCenterUserService {

    private final ControlCenterUserRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final com.zgate.controlcenter.security.TotpService totp;
    private final com.zgate.controlcenter.service.provisioning.SecretCipher cipher;

    /** Consecutive failures before the account locks. */
    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.lockout.threshold:5}")
    private int lockoutThreshold;

    /** First lockout length; each further failure past the threshold doubles it. */
    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.lockout.baseMinutes:1}")
    private int lockoutBaseMinutes;

    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.lockout.maxMinutes:60}")
    private long lockoutMaxMinutes;

    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.password.minLength:12}")
    private int passwordMinLength;

    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.password.requireMixedCase:true}")
    private boolean passwordRequireMixedCase;

    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.password.requireDigit:true}")
    private boolean passwordRequireDigit;

    @org.springframework.beans.factory.annotation.Value("${controlcenter.auth.password.requireSymbol:false}")
    private boolean passwordRequireSymbol;

    /**
     * Enforce the password policy on any credential this console accepts. The settings screen used
     * to display these rules while nothing applied them — a control that only exists in the UI is
     * worse than none, because operators believe it is on.
     */
    void requirePasswordPolicy(String password) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (password == null || password.length() < passwordMinLength) {
            problems.add("at least " + passwordMinLength + " characters");
        }
        String p = password == null ? "" : password;
        if (passwordRequireMixedCase
                && !(p.chars().anyMatch(Character::isUpperCase) && p.chars().anyMatch(Character::isLowerCase))) {
            problems.add("upper and lower case letters");
        }
        if (passwordRequireDigit && p.chars().noneMatch(Character::isDigit)) {
            problems.add("a digit");
        }
        if (passwordRequireSymbol && p.chars().allMatch(Character::isLetterOrDigit)) {
            problems.add("a symbol");
        }
        if (!problems.isEmpty()) {
            throw new ControlCenterException(
                "That password does not meet the policy — it needs " + String.join(", ", problems) + ".",
                "PASSWORD_POLICY", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }

    /** The policy actually enforced, so the console can display it truthfully. */
    public java.util.Map<String, Object> securityPolicy() {
        return java.util.Map.of(
            "passwordMinLength", passwordMinLength,
            "passwordRequireMixedCase", passwordRequireMixedCase,
            "passwordRequireDigit", passwordRequireDigit,
            "passwordRequireSymbol", passwordRequireSymbol,
            "lockoutThreshold", lockoutThreshold,
            "lockoutBaseMinutes", lockoutBaseMinutes,
            "lockoutMaxMinutes", lockoutMaxMinutes,
            "mfaSecretsEncrypted", cipher.isConfigured());
    }

    public List<ControlCenterUser> findAll() { return repo.findAll(); }

    public ControlCenterUser findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ControlCenterException("User not found: " + id));
    }

    public ControlCenterUser create(CreateUserRequest req) {
        if (repo.existsByEmail(req.getEmail())) {
            throw new ControlCenterException("Email already in use: " + req.getEmail());
        }
        requirePasswordPolicy(req.getPassword());
        return repo.save(ControlCenterUser.builder()
            .name(req.getName())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .role(req.getRole())
            .active(true)
            .build());
    }

    public ControlCenterUser update(UUID id, CreateUserRequest req) {
        ControlCenterUser user = findById(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            user.setName(req.getName());
        }
        // A role change or password reset invalidates outstanding tokens: the JWT carries the OLD
        // role claim, so without this a demoted operator keeps their previous authority until
        // expiry, and a reset password leaves the attacker's session alive.
        boolean securityRelevantChange = false;
        if (req.getRole() != null && req.getRole() != user.getRole()) {
            user.setRole(req.getRole());
            securityRelevantChange = true;
        }
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            requirePasswordPolicy(req.getPassword());
            user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
            securityRelevantChange = true;
        }
        if (securityRelevantChange) {
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        return repo.save(user);
    }

    /**
     * Disable an account AND kill its live sessions. Deactivating alone revoked nothing: the
     * bearer-token path never re-checked {@code active}, so a fired or compromised operator kept
     * full access until their token expired (24h by default).
     */
    public void disable(UUID id) {
        ControlCenterUser user = findById(id);
        user.setActive(false);
        user.setTokenVersion(user.getTokenVersion() + 1);
        repo.save(user);
    }

    /**
     * Really revoke: bump the token version so every outstanding JWT for this operator fails the
     * filter's version check immediately. (The old implementation only nulled lastLoginAt, which
     * revoked nothing — tokens stayed valid until natural expiry.)
     */
    public void revokeSessions(UUID id) {
        ControlCenterUser user = findById(id);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setLastLoginAt(null);
        repo.save(user);
    }

    public int tokenVersionOf(String email) {
        return repo.findByEmail(email).map(ControlCenterUser::getTokenVersion).orElse(0);
    }

    /**
     * Enforce the second factor at login. Runs only AFTER the password check so a wrong password
     * never reveals whether an account has MFA. A code is accepted for its time step ONCE —
     * without that, an observed code stays replayable for up to 90 seconds.
     */
    public void requireMfaIfEnabled(String email, String mfaCode) {
        ControlCenterUser user = repo.findByEmail(email).orElse(null);
        if (user == null || !user.isMfaEnabled()) return;
        if (mfaCode == null || mfaCode.isBlank()) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "A verification code from your authenticator app is required.",
                "MFA_REQUIRED", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        Long step = totp.matchedStep(readSecret(user.getMfaSecret(), user.getMfaKeyId()),
                                     mfaCode.trim(), user.getMfaLastStep());
        if (step == null) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "That verification code is not valid.",
                "MFA_INVALID", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        user.setMfaLastStep(step);
        upgradeSecretStorage(user);
        repo.save(user);
    }

    /**
     * Start enrollment. The new secret is staged in {@code mfaPendingSecret} and the LIVE factor is
     * left untouched: previously this overwrote the active secret and set {@code mfaEnabled=false},
     * so anyone holding a session could disable an operator's MFA with one unauthenticated-by-code
     * request. Re-enrolling while MFA is already on additionally requires a current code.
     */
    public java.util.Map<String, String> mfaEnroll(String email, String currentCode) {
        ControlCenterUser user = repo.findByEmail(email).orElseThrow(() ->
            new com.zgate.controlcenter.exception.ControlCenterException("User not found"));

        if (user.isMfaEnabled()) {
            Long step = totp.matchedStep(readSecret(user.getMfaSecret(), user.getMfaKeyId()),
                currentCode == null ? "" : currentCode.trim(), user.getMfaLastStep());
            if (step == null) {
                throw new com.zgate.controlcenter.exception.ControlCenterException(
                    "Re-enrolling requires a current code from the authenticator you are replacing. "
                  + "If you have lost it, ask a super-admin to reset your two-factor authentication.",
                    "MFA_REQUIRED", org.springframework.http.HttpStatus.FORBIDDEN);
            }
            user.setMfaLastStep(step);
        }

        // One key id covers both columns, so stamping the active key for the pending secret would
        // orphan a live secret written under an older key (or a legacy plaintext one) — locking the
        // operator out if they abandon the enrollment. Re-stamp the live secret in the same step.
        String liveplain = readSecret(user.getMfaSecret(), user.getMfaKeyId());

        String secret = totp.generateSecret();
        user.setMfaPendingSecret(writeSecret(secret));
        if (liveplain != null) user.setMfaSecret(writeSecret(liveplain));
        user.setMfaKeyId(cipher.activeKeyId());
        repo.save(user);
        // The plaintext is returned exactly once, for the operator's authenticator app.
        return java.util.Map.of("secret", secret, "otpauthUri", totp.otpauthUri(secret, email));
    }

    /** Finish enrollment: a valid code proves the app holds the staged secret; it then goes live. */
    public void mfaActivate(String email, String code) {
        ControlCenterUser user = repo.findByEmail(email).orElseThrow(() ->
            new com.zgate.controlcenter.exception.ControlCenterException("User not found"));
        if (user.getMfaPendingSecret() == null) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "Start enrollment first.", "MFA_NOT_ENROLLING", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        Long step = totp.matchedStep(readSecret(user.getMfaPendingSecret(), user.getMfaKeyId()),
                                     code == null ? "" : code.trim(), null);
        if (step == null) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "That verification code is not valid — scan the secret again and retry.",
                "MFA_INVALID", org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        user.setMfaSecret(user.getMfaPendingSecret());
        user.setMfaPendingSecret(null);
        user.setMfaLastStep(step);
        user.setMfaEnabled(true);
        // The account's authentication requirements just changed; existing tokens predate that.
        user.setTokenVersion(user.getTokenVersion() + 1);
        repo.save(user);
    }

    // ── MFA secret storage ──────────────────────────────────────────────────

    /**
     * Read a stored MFA secret. A row written before encryption existed has no key id and holds
     * plaintext — it is still readable so an already-enrolled operator is never locked out by the
     * upgrade; {@link #upgradeSecretStorage} re-encrypts it on their next successful code.
     */
    private String readSecret(String stored, String keyId) {
        if (stored == null || stored.isBlank()) return null;
        if (keyId == null) return stored;                 // legacy plaintext
        return cipher.decrypt(stored, keyId,
            com.zgate.controlcenter.service.provisioning.SecretCipher.PURPOSE_MFA);
    }

    /**
     * Encrypt a secret for storage. Refuses when no key is configured rather than silently writing
     * plaintext: a second factor whose seed sits readable in the database is not a second factor,
     * and a security feature that quietly degrades is worse than one that says why it cannot run.
     */
    private String writeSecret(String plaintext) {
        if (!cipher.isConfigured()) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "Two-factor authentication needs secret encryption configured. Set "
              + "controlcenter.provisioning.encryptionKey (base64, 32+ bytes) — it also protects "
              + "MFA secrets, under a separately derived key.",
                "MFA_ENCRYPTION_NOT_CONFIGURED", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        }
        return cipher.encrypt(plaintext,
            com.zgate.controlcenter.service.provisioning.SecretCipher.PURPOSE_MFA);
    }

    /** Opportunistically move a legacy plaintext secret into an envelope. Never fails the login. */
    private void upgradeSecretStorage(ControlCenterUser user) {
        if (user.getMfaKeyId() != null || user.getMfaSecret() == null) return;
        if (!cipher.isConfigured()) return;
        try {
            user.setMfaSecret(writeSecret(user.getMfaSecret()));
            user.setMfaKeyId(cipher.activeKeyId());
            log.info("Re-encrypted the stored MFA secret for {}", user.getEmail());
        } catch (RuntimeException e) {
            log.warn("Could not re-encrypt the MFA secret for {}: {}",
                     user.getEmail(), e.getClass().getSimpleName());
        }
    }

    // ── Login throttling ────────────────────────────────────────────────────

    /** Refuse a login attempt while the account is locked out. Called before password auth. */
    public void requireNotLockedOut(String email) {
        ControlCenterUser user = repo.findByEmail(email).orElse(null);
        if (user == null || user.getLockedUntil() == null) return;
        if (user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new com.zgate.controlcenter.exception.ControlCenterException(
                "Too many failed sign-in attempts. Try again after " + user.getLockedUntil() + ".",
                "ACCOUNT_LOCKED", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /**
     * Record a failed attempt (wrong password OR wrong code) and lock the account with an
     * exponential backoff once the threshold is passed. Without this the second factor is
     * brute-forceable: the ±1 window makes 3 of a million codes live at any instant.
     */
    public void recordFailedLogin(String email) {
        repo.findByEmail(email).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= lockoutThreshold) {
                long minutes = Math.min(lockoutMaxMinutes,
                    (long) lockoutBaseMinutes << Math.min(10, attempts - lockoutThreshold));
                user.setLockedUntil(LocalDateTime.now().plusMinutes(minutes));
                log.warn("Operator {} locked out for {} min after {} failed sign-in attempts",
                         email, minutes, attempts);
            }
            repo.save(user);
        });
    }

    public void recordSuccessfulLogin(String email) {
        repo.findByEmail(email).ifPresent(user -> {
            if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                repo.save(user);
            }
        });
    }

    /** Break-glass for a lost authenticator. SUPER_ADMIN-gated at the controller. */
    public void mfaDisable(UUID id) {
        ControlCenterUser user = findById(id);
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaPendingSecret(null);
        user.setMfaKeyId(null);
        user.setMfaLastStep(null);
        // The account just lost a factor — kill existing sessions so a hijacked token can't ride it.
        user.setTokenVersion(user.getTokenVersion() + 1);
        repo.save(user);
    }

    public void recordLogin(String email) {
        repo.findByEmail(email).ifPresent(u -> {
            u.setLastLoginAt(LocalDateTime.now());
            repo.save(u);
        });
    }
}
