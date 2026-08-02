package com.zgate.controlcenter.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "control_center_users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ControlCenterUser {

    @Id @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * The identity provider's immutable subject, linked on first SSO sign-in.
     *
     * <p>Matching on email alone is not stable: when an operator leaves and their address is
     * reassigned — a new hire, or a shared alias like {@code ops@} turned into a mailbox — the new
     * holder would inherit the departed operator's role. Once linked, the subject is what must
     * match; a DIFFERENT subject arriving for a known email is refused, not silently relinked.
     */
    @Column(length = 255, unique = true)
    private String oidcSubject;

    // @Builder.Default ensures builder().build() gets true, not the primitive default false
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    /**
     * Stamped into every issued JWT as the {@code tv} claim; a token whose claim doesn't match is
     * rejected. Incrementing this is what "revoke sessions" actually does — every outstanding token
     * dies at once.
     */
    @Column(nullable = false)
    @Builder.Default
    private int tokenVersion = 0;

    /**
     * The LIVE TOTP secret, as an AES-GCM envelope (see {@code SecretCipher}, purpose
     * {@code mfa-secret}). Replaced only by a successful activation. Never handled raw outside
     * {@code ControlCenterUserService} — a plaintext secret here would let anyone who can read the
     * database mint valid second factors.
     */
    @JsonIgnore
    @Column(length = 512)
    private String mfaSecret;

    /**
     * A newly-issued secret awaiting proof that the authenticator holds it, same envelope format.
     * Kept separate so starting an enrollment can never disable a factor already protecting the
     * account.
     */
    @JsonIgnore
    @Column(length = 512)
    private String mfaPendingSecret;

    /**
     * Key id both MFA envelopes were written with. NULL means the row predates encryption and holds
     * plaintext — still accepted at login, and re-encrypted on the next successful verification.
     */
    @JsonIgnore
    @Column(length = 64)
    private String mfaKeyId;

    /**
     * Last accepted TOTP time step. A code is valid for its step only — without this a captured
     * code is replayable for up to 90 seconds (RFC 6238 §5.2 requires single use).
     */
    @JsonIgnore
    private Long mfaLastStep;

    @Column(nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    /** Consecutive failed logins; drives the lockout backoff. Reset on success. */
    @JsonIgnore
    @Column(nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @JsonIgnore
    private LocalDateTime lockedUntil;

    private LocalDateTime lastLoginAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public enum Role { SUPER_ADMIN, ADMIN, SUPPORT, VIEWER }
}
