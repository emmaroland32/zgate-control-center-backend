package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ControlCenterUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ControlCenterUserRepository extends JpaRepository<ControlCenterUser, UUID> {
    Optional<ControlCenterUser> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * Emails are stored as typed, and Postgres' default collation is case-sensitive — so an
     * operator created as {@code Ops@Example.com} is invisible to an exact-match lookup of the
     * lower-cased address an IdP returns. Matching case-insensitively is what stops SSO either
     * refusing a real operator or (with auto-provisioning on) creating a SECOND row for the same
     * human, with its own role and its own tokenVersion.
     */
    Optional<ControlCenterUser> findByEmailIgnoreCase(String email);

    Optional<ControlCenterUser> findByOidcSubject(String oidcSubject);
}
