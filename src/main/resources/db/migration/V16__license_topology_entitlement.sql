-- Topology entitlement carried into the signed license bundle so the org can self-enforce its
-- deployment tier locally (a SINGLE_NODE license booting under Kubernetes/ECS locks even if the
-- customer blocks Control-Center telemetry). Stamped from the organization at issue/renewal.
-- Additive only.

ALTER TABLE licenses ADD COLUMN IF NOT EXISTS deployment_tier VARCHAR(32);
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS max_instances   INTEGER;
