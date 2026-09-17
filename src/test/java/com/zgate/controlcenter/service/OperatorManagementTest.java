package com.zgate.controlcenter.service;

import com.zgate.controlcenter.domain.ControlCenterUser;
import com.zgate.controlcenter.domain.ControlCenterUser.Role;
import com.zgate.controlcenter.exception.ControlCenterException;
import com.zgate.controlcenter.payload.request.CreateUserRequest;
import com.zgate.controlcenter.repository.ControlCenterUserRepository;
import com.zgate.controlcenter.security.TotpService;
import com.zgate.controlcenter.service.provisioning.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The privilege rules behind admin management. Each of these is a way an ADMIN could quietly
 * become a SUPER_ADMIN, or a console could end up with nobody able to manage it.
 */
class OperatorManagementTest {

    private ControlCenterUserRepository repo;
    private PasswordEncoder encoder;
    private ControlCenterUserService service;

    private ControlCenterUser superAdmin;
    private ControlCenterUser admin;
    private ControlCenterUser support;

    @BeforeEach
    void setUp() {
        repo = mock(ControlCenterUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        service = new ControlCenterUserService(repo, encoder, mock(TotpService.class), mock(SecretCipher.class));
        ReflectionTestUtils.setField(service, "passwordMinLength", 12);
        ReflectionTestUtils.setField(service, "passwordRequireMixedCase", true);
        ReflectionTestUtils.setField(service, "passwordRequireDigit", true);
        ReflectionTestUtils.setField(service, "passwordRequireSymbol", false);
        ReflectionTestUtils.setField(service, "lockoutThreshold", 3);
        ReflectionTestUtils.setField(service, "lockoutBaseMinutes", 1);
        ReflectionTestUtils.setField(service, "lockoutMaxMinutes", 60L);

        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        superAdmin = operator("root@example.com", Role.SUPER_ADMIN);
        admin = operator("admin@example.com", Role.ADMIN);
        support = operator("support@example.com", Role.SUPPORT);
        when(repo.countByRoleAndActiveTrue(Role.SUPER_ADMIN)).thenReturn(2L);
    }

    private ControlCenterUser operator(String email, Role role) {
        ControlCenterUser u = ControlCenterUser.builder()
            .id(UUID.randomUUID()).name(email.split("@")[0]).email(email)
            .passwordHash("hash").role(role).active(true).tokenVersion(3)
            .build();
        when(repo.findByEmailIgnoreCase(email)).thenReturn(Optional.of(u));
        when(repo.findByEmail(email)).thenReturn(Optional.of(u));
        when(repo.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private static CreateUserRequest edit(ControlCenterUser u, Role role) {
        CreateUserRequest r = new CreateUserRequest();
        r.setName(u.getName());
        r.setEmail(u.getEmail());
        r.setRole(role);
        return r;
    }

    private static void assertRefused(Runnable r, String fragment) {
        assertThatThrownBy(r::run)
            .isInstanceOf(ControlCenterException.class)
            .hasMessageContaining(fragment)
            .extracting("code").isEqualTo(ControlCenterUserService.PRIVILEGE_CODE);
    }

    // ── an ADMIN cannot reach a SUPER_ADMIN ─────────────────────────────────

    @Nested
    class AdminVersusSuperAdmin {

        @Test
        @DisplayName("an ADMIN cannot disable, unlock, reset or revoke a SUPER_ADMIN")
        void adminCannotTouchSuperAdmin() {
            assertRefused(() -> service.disable(admin.getEmail(), superAdmin.getId()), "super-admin");
            assertRefused(() -> service.unlock(admin.getEmail(), superAdmin.getId()), "super-admin");
            assertRefused(() -> service.resetPassword(admin.getEmail(), superAdmin.getId(), "GoodPassw0rd123"), "super-admin");
            assertRefused(() -> service.revokeSessions(admin.getEmail(), superAdmin.getId()), "super-admin");
            assertRefused(() -> service.mfaDisable(admin.getEmail(), superAdmin.getId()), "super-admin");
            assertRefused(() -> service.update(admin.getEmail(), superAdmin.getId(), edit(superAdmin, Role.VIEWER)), "super-admin");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("an ADMIN cannot grant SUPER_ADMIN on create or on edit")
        void adminCannotGrantSuperAdmin() {
            CreateUserRequest create = new CreateUserRequest();
            create.setName("New"); create.setEmail("new@example.com");
            create.setPassword("GoodPassw0rd123"); create.setRole(Role.SUPER_ADMIN);
            assertRefused(() -> service.create(admin.getEmail(), create), "grant the super-admin role");
            assertRefused(() -> service.update(admin.getEmail(), support.getId(), edit(support, Role.SUPER_ADMIN)),
                          "grant the super-admin role");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("a SUPER_ADMIN can do all of it")
        void superAdminMay() {
            service.update(superAdmin.getEmail(), support.getId(), edit(support, Role.ADMIN));
            assertThat(support.getRole()).isEqualTo(Role.ADMIN);
            assertThat(support.getTokenVersion()).as("role change kills old tokens").isEqualTo(4);

            service.disable(superAdmin.getEmail(), admin.getId());
            assertThat(admin.isActive()).isFalse();
            assertThat(admin.getTokenVersion()).isEqualTo(4);
        }
    }

    // ── self-protection and the last super-admin ────────────────────────────

    @Nested
    class SelfAndLastSuperAdmin {

        @Test
        @DisplayName("nobody can change their own role or disable themselves")
        void noSelfService() {
            assertRefused(() -> service.update(superAdmin.getEmail(), superAdmin.getId(), edit(superAdmin, Role.ADMIN)),
                          "own account");
            assertRefused(() -> service.disable(superAdmin.getEmail(), superAdmin.getId()), "own account");
            assertRefused(() -> service.update(admin.getEmail(), admin.getId(), edit(admin, Role.VIEWER)), "own account");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("the last active SUPER_ADMIN can be neither demoted nor disabled")
        void lastSuperAdminIsProtected() {
            ControlCenterUser other = operator("other-root@example.com", Role.SUPER_ADMIN);
            when(repo.countByRoleAndActiveTrue(Role.SUPER_ADMIN)).thenReturn(1L);
            assertRefused(() -> service.update(superAdmin.getEmail(), other.getId(), edit(other, Role.ADMIN)),
                          "last active super-admin");
            assertRefused(() -> service.disable(superAdmin.getEmail(), other.getId()), "last active super-admin");
            verify(repo, never()).save(any());

            when(repo.countByRoleAndActiveTrue(Role.SUPER_ADMIN)).thenReturn(2L);
            service.disable(superAdmin.getEmail(), other.getId());
            assertThat(other.isActive()).isFalse();
        }
    }

    // ── update is name + role only ──────────────────────────────────────────

    @Nested
    class UpdateShape {

        @Test
        @DisplayName("a password in the edit body is refused, not silently applied")
        void updateRefusesPassword() {
            CreateUserRequest r = edit(support, Role.SUPPORT);
            r.setPassword("GoodPassw0rd123");
            assertThatThrownBy(() -> service.update(superAdmin.getEmail(), support.getId(), r))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("reset-password");
            assertThat(support.getPasswordHash()).isEqualTo("hash");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("email is not editable")
        void updateRefusesEmailChange() {
            CreateUserRequest r = edit(support, Role.SUPPORT);
            r.setEmail("someone-else@example.com");
            assertThatThrownBy(() -> service.update(superAdmin.getEmail(), support.getId(), r))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("email");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("the audit diff names what changed, and a no-op says so")
        void updateReportsChanges() {
            CreateUserRequest r = edit(support, Role.ADMIN);
            r.setName("Renamed");
            var result = service.update(superAdmin.getEmail(), support.getId(), r);
            assertThat(result.changes()).contains("name: 'support' -> 'Renamed'").contains("role: SUPPORT -> ADMIN");

            var noop = service.update(superAdmin.getEmail(), support.getId(), edit(support, Role.ADMIN));
            assertThat(noop.changes()).isEqualTo("no changes");
            assertThat(support.getTokenVersion()).as("a no-op must not revoke sessions").isEqualTo(4);
        }
    }

    // ── reset, unlock, enable ───────────────────────────────────────────────

    @Nested
    class ResetUnlockEnable {

        @Test
        @DisplayName("reset-password is policy-checked, revokes sessions and clears the lockout")
        void resetPassword() {
            support.setFailedLoginAttempts(5);
            support.setLockedUntil(LocalDateTime.now().plusMinutes(30));

            assertThatThrownBy(() -> service.resetPassword(superAdmin.getEmail(), support.getId(), "short"))
                .isInstanceOf(ControlCenterException.class)
                .extracting("code").isEqualTo("PASSWORD_POLICY");
            assertThat(support.getPasswordHash()).isEqualTo("hash");

            service.resetPassword(superAdmin.getEmail(), support.getId(), "GoodPassw0rd123");
            assertThat(support.getPasswordHash()).isEqualTo("enc:GoodPassw0rd123");
            assertThat(support.getTokenVersion()).isEqualTo(4);
            assertThat(support.getFailedLoginAttempts()).isZero();
            assertThat(support.getLockedUntil()).isNull();
        }

        @Test
        @DisplayName("reset-password refuses the current password")
        void resetPasswordMustDiffer() {
            when(encoder.matches("GoodPassw0rd123", "hash")).thenReturn(true);
            assertThatThrownBy(() -> service.resetPassword(superAdmin.getEmail(), support.getId(), "GoodPassw0rd123"))
                .isInstanceOf(ControlCenterException.class)
                .hasMessageContaining("differ");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("unlock clears the counter and the deadline without touching the password")
        void unlock() {
            support.setFailedLoginAttempts(4);
            support.setLockedUntil(LocalDateTime.now().plusMinutes(30));
            service.unlock(admin.getEmail(), support.getId());
            assertThat(support.getFailedLoginAttempts()).isZero();
            assertThat(support.getLockedUntil()).isNull();
            assertThat(support.getPasswordHash()).isEqualTo("hash");
            assertThat(support.getTokenVersion()).as("unlock is not a revocation").isEqualTo(3);
        }

        @Test
        @DisplayName("enable reinstates a disabled operator and is a no-op on an active one")
        void enable() {
            support.setActive(false);
            service.enable(superAdmin.getEmail(), support.getId());
            assertThat(support.isActive()).isTrue();
            verify(repo, times(1)).save(any());

            service.enable(superAdmin.getEmail(), support.getId());
            verify(repo, times(1)).save(any());
        }
    }

    // ── self-service and self-unlock ────────────────────────────────────────

    @Nested
    class SelfService {

        @Test
        @DisplayName("nobody can clear their own lockout — that would make step-up a password oracle")
        void noSelfUnlock() {
            superAdmin.setFailedLoginAttempts(5);
            superAdmin.setLockedUntil(LocalDateTime.now().plusMinutes(30));
            assertRefused(() -> service.unlock(superAdmin.getEmail(), superAdmin.getId()), "own account");
            assertThat(superAdmin.getLockedUntil()).isNotNull();
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("changing your own password proves the current one and revokes every session")
        void changeOwnPassword() {
            when(encoder.matches("current-pw", "hash")).thenReturn(true);

            assertThatThrownBy(() -> service.changeOwnPassword(support.getEmail(), "wrong", null, "GoodPassw0rd123"))
                .isInstanceOf(ControlCenterException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIALS");
            assertThat(support.getFailedLoginAttempts()).as("a wrong current password counts toward lockout").isEqualTo(1);
            assertThat(support.getPasswordHash()).isEqualTo("hash");

            assertThatThrownBy(() -> service.changeOwnPassword(support.getEmail(), "current-pw", null, "weak"))
                .extracting("code").isEqualTo("PASSWORD_POLICY");

            service.changeOwnPassword(support.getEmail(), "current-pw", null, "GoodPassw0rd123");
            assertThat(support.getPasswordHash()).isEqualTo("enc:GoodPassw0rd123");
            assertThat(support.getTokenVersion()).isEqualTo(4);
            assertThat(support.getFailedLoginAttempts()).isZero();
        }
    }

    // ── lockout reporting and the view ──────────────────────────────────────

    @Test
    @DisplayName("recordFailedLogin reports exactly the attempt that locks the account")
    void failedLoginReportsLock() {
        assertThat(service.recordFailedLogin(support.getEmail())).isFalse();
        assertThat(service.recordFailedLogin(support.getEmail())).isFalse();
        assertThat(service.recordFailedLogin(support.getEmail())).as("third failure trips threshold 3").isTrue();
        assertThat(support.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(service.recordFailedLogin("nobody@example.com")).isFalse();
    }

    @Test
    @DisplayName("the operator view derives lock state and never carries secrets")
    void viewDerivesLockState() {
        support.setLockedUntil(LocalDateTime.now().plusMinutes(5));
        support.setFailedLoginAttempts(3);
        support.setOidcSubject("idp|123");
        var locked = ControlCenterUserService.toView(support);
        assertThat(locked.locked()).isTrue();
        assertThat(locked.lockedUntil()).isNotNull();
        assertThat(locked.failedLoginAttempts()).isEqualTo(3);
        assertThat(locked.ssoLinked()).isTrue();

        support.setLockedUntil(LocalDateTime.now().minusMinutes(5));
        var expired = ControlCenterUserService.toView(support);
        assertThat(expired.locked()).isFalse();
        assertThat(expired.lockedUntil()).as("an expired deadline is not shown").isNull();

        assertThat(ControlCenterUserService.OperatorView.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("passwordHash", "mfaSecret", "mfaPendingSecret", "mfaKeyId", "oidcSubject");
    }
}
