-- ZGATE Nexus — License delivery tracking
-- Tracks whether a signed bundle has been fetched and activated by the org.

ALTER TABLE licenses
    ADD COLUMN IF NOT EXISTS delivery_status  VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS bundle_json       TEXT,          -- cached signed .lic bundle (JSON)
    ADD COLUMN IF NOT EXISTS fetched_at        TIMESTAMPTZ,   -- when org first pulled the bundle
    ADD COLUMN IF NOT EXISTS org_activated_at  TIMESTAMPTZ,   -- when org confirmed activation
    ADD COLUMN IF NOT EXISTS org_modules       TEXT;          -- JSON array: modules org reported back

-- PENDING   = issued, bundle not yet fetched by org
-- DELIVERED = org pulled the bundle (fetched_at set)
-- ACTIVATED = org confirmed successful activation (org_activated_at set)
-- FAILED    = org reported it could not activate

CREATE INDEX idx_licenses_delivery ON licenses(delivery_status);
