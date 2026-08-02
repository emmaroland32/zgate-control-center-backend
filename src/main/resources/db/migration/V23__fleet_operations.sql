-- ============================================================================
-- Fleet operations: staged version rollouts, release digests, subscription
-- renewal invoices
-- ============================================================================
-- Turns Control Center from a one-stack-at-a-time deployment tool into a
-- fleet-operations plane for vendor-hosted (single-tenant SaaS) customers.
-- Additive only: no existing column is altered or dropped.
-- ============================================================================

-- ── Releases: image digests ─────────────────────────────────────────────────
-- The cosign-signed digest of the published images. When present it is rendered
-- into the provisioning spec, so stacks pull by immutable digest rather than by
-- mutable tag, and the runtime image-digest gate (Layer 5) has a value to check.
ALTER TABLE releases ADD COLUMN image_digest     VARCHAR(80);
ALTER TABLE releases ADD COLUMN web_image_digest VARCHAR(80);

-- ── Stacks: the release each stack currently runs ───────────────────────────
-- Denormalised from the spec's image tag so fleet views need no JSON parsing.
-- Set at provision and at upgrade; reflects the last release WRITTEN INTO the
-- spec (what the next apply will run), while organizations.deployed_version
-- remains what telemetry last OBSERVED running.
ALTER TABLE infrastructure_stacks ADD COLUMN release_version VARCHAR(40);

-- ── Fleet rollouts ──────────────────────────────────────────────────────────
-- One row per staged fleet upgrade: a target release rolled across a chosen set
-- of stacks in waves (a canary wave first), halting when anything fails.
CREATE TABLE fleet_rollouts (
    id                 UUID PRIMARY KEY,
    release_id         UUID         NOT NULL REFERENCES releases(id),
    release_version    VARCHAR(40)  NOT NULL,

    status             VARCHAR(24)  NOT NULL,  -- PENDING | IN_PROGRESS | PAUSED | COMPLETED | FAILED | CANCELLED

    -- Wave shape. The canary wave runs first and is deliberately small; later
    -- waves take wave_size stacks at a time.
    canary_size        INT          NOT NULL DEFAULT 1,
    wave_size          INT          NOT NULL DEFAULT 5,

    -- true  = each wave plans AND applies unattended, then soaks
    -- false = the rollout stops at PLANNED per stack and an operator applies
    auto_apply         BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Minutes to wait after a wave's last apply before declaring the wave good.
    -- During the soak each upgraded org must heartbeat on the target version;
    -- silence or a wrong version pauses the rollout instead of advancing it.
    soak_minutes       INT          NOT NULL DEFAULT 15,

    -- Why the rollout is paused/failed, so the operator does not have to dig
    -- through per-item errors to learn what stopped it.
    status_reason      TEXT,

    current_wave       INT          NOT NULL DEFAULT 0,
    created_by         VARCHAR(120) NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP,
    completed_at       TIMESTAMP,

    CONSTRAINT ck_fleet_rollout_status CHECK (status IN
        ('PENDING', 'IN_PROGRESS', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_fleet_rollout_canary CHECK (canary_size >= 1),
    CONSTRAINT ck_fleet_rollout_wave   CHECK (wave_size >= 1),
    CONSTRAINT ck_fleet_rollout_soak   CHECK (soak_minutes >= 0)
);

CREATE INDEX idx_fleet_rollouts_status ON fleet_rollouts(status);

-- One stack in one rollout. A stack appears at most once per rollout; the
-- unique constraint is what stops a double-listed stack being upgraded twice.
CREATE TABLE fleet_rollout_items (
    id                 UUID PRIMARY KEY,
    rollout_id         UUID         NOT NULL REFERENCES fleet_rollouts(id) ON DELETE CASCADE,
    stack_id           UUID         NOT NULL REFERENCES infrastructure_stacks(id),
    organization_id    UUID         NOT NULL REFERENCES organizations(id),

    wave               INT          NOT NULL,

    -- PENDING     not started
    -- PLANNING    upgrade plan queued/running
    -- PLANNED     plan succeeded; waiting for apply (operator, or orchestrator when auto_apply)
    -- APPLYING    apply queued/running
    -- SOAKING     applied; inside the soak window awaiting a healthy heartbeat
    -- SUCCEEDED   applied (and soak-verified when auto_apply)
    -- FAILED      plan or apply failed — halts the rollout
    -- SKIPPED     refused up front (lapsed subscription / beyond entitlement / stack not upgradable)
    status             VARCHAR(16)  NOT NULL,

    from_version       VARCHAR(40),
    to_version         VARCHAR(40)  NOT NULL,

    -- The provisioning runs this item triggered, and the deployment history row
    -- it maintains, so the audit chain is walkable from either end.
    plan_run_id        UUID,
    apply_run_id       UUID,
    deployment_id      UUID,

    applied_at         TIMESTAMP,
    error_message      TEXT,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP,

    CONSTRAINT uq_fleet_rollout_stack UNIQUE (rollout_id, stack_id),
    CONSTRAINT ck_fleet_item_status CHECK (status IN
        ('PENDING', 'PLANNING', 'PLANNED', 'APPLYING', 'SOAKING', 'SUCCEEDED',
         'FAILED', 'SKIPPED')),
    CONSTRAINT ck_fleet_item_wave CHECK (wave >= 0)
);

CREATE INDEX idx_fleet_items_rollout ON fleet_rollout_items(rollout_id);
CREATE INDEX idx_fleet_items_stack   ON fleet_rollout_items(stack_id);

-- ── Invoices: usage vs subscription ─────────────────────────────────────────
-- USAGE         the existing monthly shared-services + backup invoice
-- SUBSCRIPTION  a renewal of the org's platform subscription; marking it PAID
--               extends organizations.subscription_valid_until to period_end
ALTER TABLE invoices ADD COLUMN invoice_type VARCHAR(16) NOT NULL DEFAULT 'USAGE';
ALTER TABLE invoices ADD CONSTRAINT ck_invoice_type
    CHECK (invoice_type IN ('USAGE', 'SUBSCRIPTION'));

-- What a month of the platform subscription costs this org. NULL = no automatic
-- renewal invoicing (the org is billed out of band or is unmanaged).
ALTER TABLE organizations ADD COLUMN subscription_monthly_fee NUMERIC(12, 2);
