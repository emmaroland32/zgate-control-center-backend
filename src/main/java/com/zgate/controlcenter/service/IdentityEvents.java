package com.zgate.controlcenter.service;

import java.util.List;

/**
 * Audit action names for everything that happens to an operator account. One place, so the
 * sign-in path, the admin-management endpoints and the activity monitor agree on spelling —
 * a misspelt action is an event the monitor silently never shows.
 */
public final class IdentityEvents {

    private IdentityEvents() {}

    /** Every row about an operator account carries this entity type. */
    public static final String ENTITY = "ControlCenterUser";

    // ── sign-in outcomes (actor = the attempted email; entityId = the same) ──
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    /** An attempt made while the account was already locked. */
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    /** The failed attempt that tripped the lockout threshold. */
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String LOGIN_MFA_FAILED = "LOGIN_MFA_FAILED";
    public static final String LOGIN_REFUSED_DEFAULT_PASSWORD = "LOGIN_REFUSED_DEFAULT_PASSWORD";
    public static final String STEP_UP_SUCCESS = "STEP_UP_SUCCESS";
    public static final String STEP_UP_FAILED = "STEP_UP_FAILED";

    // ── admin management (actor = the administrator; entityId = the target user's id) ──
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_ENABLED = "USER_ENABLED";
    public static final String USER_UNLOCKED = "USER_UNLOCKED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String USER_SESSIONS_REVOKED = "USER_SESSIONS_REVOKED";
    /** A management call refused by the privilege rules — worth seeing, it is an escalation attempt. */
    public static final String USER_ACTION_REFUSED = "USER_ACTION_REFUSED";
    public static final String MFA_ENROLL_STARTED = "MFA_ENROLL_STARTED";
    public static final String MFA_ENABLED = "MFA_ENABLED";
    public static final String MFA_DISABLED_BY_ADMIN = "MFA_DISABLED_BY_ADMIN";

    /** Endpoint-level denial by method security (a role calling something above its station). */
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    /** An operator changed their own password (current password + MFA proven). */
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    /** A self-service password change refused for a wrong current password or code. */
    public static final String PASSWORD_CHANGE_FAILED = "PASSWORD_CHANGE_FAILED";

    /** Longest value the audit {@code entity_id} column holds. */
    public static final int MAX_ACTOR_LENGTH = 100;

    /**
     * Make an attacker-supplied identifier safe to store as an audit actor/entity id: strip
     * control characters and cap the length, so the (asynchronous) write cannot fail on it.
     */
    public static String safeActor(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", "").trim();
        return cleaned.length() > MAX_ACTOR_LENGTH ? cleaned.substring(0, MAX_ACTOR_LENGTH) : cleaned;
    }

    /** Successful sign-ins, by any path. */
    public static final List<String> SIGN_IN_ACTIONS = List.of(LOGIN_SUCCESS, "SSO_LOGIN");

    /** Sign-in attempts that were refused, by any path. */
    public static final List<String> FAILED_SIGN_IN_ACTIONS = List.of(
        LOGIN_FAILED, LOGIN_LOCKED, LOGIN_MFA_FAILED, LOGIN_REFUSED_DEFAULT_PASSWORD, "SSO_LOGIN_DENIED");

    public static final List<String> LOCKOUT_ACTIONS = List.of(ACCOUNT_LOCKED);

    /** Changes an administrator made to operator accounts. */
    public static final List<String> ADMIN_ACTIONS = List.of(
        USER_CREATED, USER_UPDATED, USER_DISABLED, USER_ENABLED, USER_UNLOCKED,
        USER_PASSWORD_RESET, USER_SESSIONS_REVOKED, USER_ACTION_REFUSED, MFA_DISABLED_BY_ADMIN);
}
