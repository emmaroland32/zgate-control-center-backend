-- Instance / topology registry for copied-license, concurrent-use, and deployment-tier detection.
--
-- One row per live NODE (pod / task / process), keyed on (org, fingerprint, node_id):
--   * distinct fingerprints  = separate deployments/environments (DBs)  → failover / multi-region
--   * distinct node_ids under one fingerprint = replicas of one deployment → K8s/ECS horizontal scale
--   * platform               = the reported orchestrator (bare|docker|kubernetes|ecs)
-- Refreshed on every telemetry heartbeat; a node aging out of the window means it stopped reporting.
--
-- Organization entitlements:
--   max_instances    — entitled number of concurrent deployments (environments). NULL = unmanaged.
--   deployment_tier  — SINGLE_NODE | HIGH_AVAILABILITY | MULTI_REGION. NULL = unmanaged (no topology
--                      check). Sets the price point: HA allows K8s/ECS replicas; MULTI_REGION allows
--                      failover to a second environment.

CREATE TABLE IF NOT EXISTS org_instances (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL,
    fingerprint     VARCHAR(255) NOT NULL,
    node_id         VARCHAR(255) NOT NULL,
    platform        VARCHAR(32),
    app_version     VARCHAR(64),
    first_seen_at   TIMESTAMP    NOT NULL,
    last_seen_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_org_instance_node UNIQUE (organization_id, fingerprint, node_id)
);

CREATE INDEX IF NOT EXISTS idx_org_instances_org_lastseen
    ON org_instances (organization_id, last_seen_at);

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS max_instances   INTEGER;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS deployment_tier VARCHAR(32);
