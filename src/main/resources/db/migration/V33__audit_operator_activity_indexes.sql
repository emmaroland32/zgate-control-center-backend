-- ─────────────────────────────────────────────────────────────────────────────
-- Operator activity monitor: index the audit trail by WHO acted and WHAT was acted on.
--
-- The admin-management screen shows, per operator, everything they did and everything that was
-- done to their account (sign-ins, lockouts, role changes, password resets, MFA changes). Those
-- lookups filter on actor_email and on (entity_type, entity_id); until now only organization_id,
-- action and created_at were indexed, so each operator drawer would have scanned the whole table.
-- Purely additive; no data changes.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_audit_actor_email ON audit_logs (actor_email, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_entity      ON audit_logs (entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_status_ts   ON audit_logs (status, created_at DESC);
