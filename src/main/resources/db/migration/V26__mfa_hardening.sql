-- ============================================================================
-- MFA hardening + login throttling (security review remediation)
-- ============================================================================
-- Three holes closed:
--   1. Re-enrolling silently DISABLED an already-active MFA with no code — a
--      one-request downgrade for anyone holding a session. A new secret now
--      stages in mfa_pending_secret and only replaces the live one on a valid
--      code, so enrollment can never turn the factor off.
--   2. TOTP codes were replayable for up to 90s; mfa_last_step records the
--      last accepted time step so a code is single-use (RFC 6238 §5.2).
--   3. No lockout existed anywhere: failed_login_attempts / locked_until back
--      an exponential backoff on the console's login endpoint.
-- ============================================================================

ALTER TABLE control_center_users ADD COLUMN mfa_pending_secret     VARCHAR(64);
ALTER TABLE control_center_users ADD COLUMN mfa_last_step          BIGINT;
ALTER TABLE control_center_users ADD COLUMN failed_login_attempts  INT NOT NULL DEFAULT 0;
ALTER TABLE control_center_users ADD COLUMN locked_until           TIMESTAMP;

-- Existing tokens were issued before token_version existed and carry no `tv`
-- claim, which reads as 0 and would match the column default forever. Moving
-- every operator to 1 invalidates that grandfathered population once.
UPDATE control_center_users SET token_version = 1 WHERE token_version = 0;
