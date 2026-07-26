-- Commercial-enforcement columns for the licensing kill switch.
--
-- Additive only (V1–V13 shipped and untouched). Adds:
--   licenses.max_version / image_digest / grace_days — carried into the signed bundle and
--     enforced at runtime by the org-side LicenseEnforcementService (blocks self-upgrade,
--     binds the entitled image, sets the per-license grace window).
--   organizations.subscription_valid_until / entitled_version / license_ttl_days — the entitlement
--     levers the renewal job reads. subscription_valid_until NULL = unmanaged/perpetual (existing
--     orgs keep working); once set and lapsed, short-lived licenses stop renewing → org locks.

ALTER TABLE licenses ADD COLUMN IF NOT EXISTS max_version  VARCHAR(64);
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS image_digest VARCHAR(255);
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS grace_days   INTEGER;

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS subscription_valid_until TIMESTAMP;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS entitled_version         VARCHAR(64);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS license_ttl_days         INTEGER;
