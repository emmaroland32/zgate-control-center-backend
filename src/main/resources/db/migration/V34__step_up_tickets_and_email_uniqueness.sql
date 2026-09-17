-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Single-use step-up tickets.
--
-- A step-up ticket used to be stateless (the signature was the state), which made it replayable
-- for its whole five-minute life against every step-up-gated endpoint the operator's role allows.
-- Each issued ticket is now a row here, claimed with an atomic DELETE on first use, and bound to
-- the one action (method + path) it was requested for. Postgres rather than Redis: every Redis
-- consumer in this product fails open and deployments run without it — this must fail closed.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS step_up_tickets (
    nonce       VARCHAR(64)   PRIMARY KEY,
    email       VARCHAR(200)  NOT NULL,
    action      VARCHAR(300),
    issued_at   TIMESTAMP     NOT NULL,
    expires_at  TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_step_up_tickets_expires ON step_up_tickets (expires_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Case-insensitive operator email uniqueness.
--
-- The column's UNIQUE constraint is case-sensitive while sign-in, SSO linking and admin
-- management all look operators up case-insensitively, so two rows differing only by case would
-- make every such lookup fail. Enforce uniqueness on LOWER(email). If a deployment already holds
-- such duplicates the index is deliberately NOT created (boot must not fail on a data problem);
-- a warning is raised so the operator can dedupe and re-run this statement.
-- ─────────────────────────────────────────────────────────────────────────────

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM control_center_users GROUP BY LOWER(email) HAVING count(*) > 1) THEN
        RAISE WARNING 'control_center_users holds emails differing only by case; '
                      'ux_control_center_users_email_lower NOT created — dedupe, then create it by hand';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_control_center_users_email_lower
            ON control_center_users (LOWER(email));
    END IF;
END $$;
