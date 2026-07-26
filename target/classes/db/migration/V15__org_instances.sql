-- Instance registry for copied-license / concurrent-use detection.
--
-- Each distinct machine fingerprint an org reports (via the telemetry X-Control-Center-Fingerprint
-- header) is one row, refreshed on every heartbeat. Counting rows seen within a recent window gives
-- the number of live installs for that org; comparing to organizations.max_instances (the entitled
-- instance count) surfaces a license copied onto more machines than paid for.
--
-- max_instances NULL = unmanaged (no concurrent-use check), consistent with the other entitlement
-- fields, so existing orgs are never flagged.

CREATE TABLE IF NOT EXISTS org_instances (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL,
    fingerprint     VARCHAR(255) NOT NULL,
    app_version     VARCHAR(64),
    first_seen_at   TIMESTAMP    NOT NULL,
    last_seen_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_org_instance UNIQUE (organization_id, fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_org_instances_org_lastseen
    ON org_instances (organization_id, last_seen_at);

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS max_instances INTEGER;
