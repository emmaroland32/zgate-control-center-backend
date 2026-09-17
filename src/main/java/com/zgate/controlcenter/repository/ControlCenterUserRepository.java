package com.zgate.controlcenter.repository;

import com.zgate.controlcenter.domain.ControlCenterUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** How many operators of a role can still sign in — guards the "last super-admin" rule. */
    long countByRoleAndActiveTrue(ControlCenterUser.Role role);

    /**
     * Serialise changes to the operator roster for the current transaction. The "last active
     * super-admin" check is count-then-write; two concurrent demotions of the two remaining
     * super-admins would both count 2, both pass, and leave nobody able to manage the console.
     * A transaction-scoped advisory lock makes the second one wait and then see the new count.
     * Returns true (the void function IS NULL) purely so JPA has something to map.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(7311) IS NULL", nativeQuery = true)
    Boolean lockOperatorRoster();
}
