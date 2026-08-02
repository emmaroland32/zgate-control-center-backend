-- ============================================================================
-- Fleet hardening: operator MFA + real session revocation, alert delivery,
-- rollout approvals, maintenance windows, scheduled-job locking
-- ============================================================================
-- Additive only. Every default preserves current behaviour: MFA off, approvals
-- auto-approved, no maintenance window, alerts pending notification.
-- ============================================================================

-- ── Operators: TOTP MFA + token-version session revocation ──────────────────
-- token_version is stamped into every issued JWT; bumping it invalidates every
-- outstanding token for that operator (the previous "revoke sessions" only
-- nulled lastLoginAt and revoked nothing).
ALTER TABLE control_center_users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE control_center_users ADD COLUMN mfa_secret    VARCHAR(64);
ALTER TABLE control_center_users ADD COLUMN mfa_enabled   BOOLEAN NOT NULL DEFAULT FALSE;

-- ── Alerts: delivery tracking ───────────────────────────────────────────────
-- An alert nobody sees does not exist. The dispatcher marks notified_at once
-- any channel accepted it, and gives up after a bounded number of attempts.
ALTER TABLE alerts ADD COLUMN notified_at     TIMESTAMP;
ALTER TABLE alerts ADD COLUMN notify_attempts INT NOT NULL DEFAULT 0;

-- ── Organizations: maintenance windows ──────────────────────────────────────
-- When set, orchestrator-initiated applies for this org run only inside the
-- window (in the org's own timezone). NULL = no restriction. Manual operator
-- applies are never gated — an incident fix must not wait for a window.
ALTER TABLE organizations ADD COLUMN maintenance_window_start TIME;
ALTER TABLE organizations ADD COLUMN maintenance_window_end   TIME;
ALTER TABLE organizations ADD COLUMN maintenance_timezone     VARCHAR(64);

-- ── Fleet rollouts: maker-checker approval ──────────────────────────────────
-- Default APPROVED so existing behaviour is unchanged until
-- controlcenter.fleet.rollout.requireApproval is turned on.
ALTER TABLE fleet_rollouts ADD COLUMN approval_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE fleet_rollouts ADD COLUMN approved_by     VARCHAR(120);
ALTER TABLE fleet_rollouts ADD COLUMN approved_at     TIMESTAMP;
ALTER TABLE fleet_rollouts ADD CONSTRAINT ck_fleet_rollout_approval
    CHECK (approval_status IN ('PENDING', 'APPROVED'));

-- ── ShedLock ────────────────────────────────────────────────────────────────
-- Coordination table for scheduled-job locking across Control Center replicas.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
