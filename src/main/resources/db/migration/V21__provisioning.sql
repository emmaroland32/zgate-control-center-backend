-- ============================================================================
-- Cloud provisioning: credentials, deployment specs, and provisioned stacks
-- ============================================================================
-- Backs the Terraform-driven "deploy ZGATE into a customer's cloud" flow.
-- Additive only: no existing table is altered, so an installation that never
-- uses provisioning is unaffected.
-- ============================================================================

-- ── Cloud credentials ───────────────────────────────────────────────────────
-- How Control Center authenticates to a customer's cloud.
--
-- AWS_ASSUME_ROLE is the preferred mode and holds NO secret at all: the vendor's
-- own credentials assume a role the customer created, guarded by an external id.
-- The static-key modes store ciphertext only — AES-GCM under a key derived from
-- the Control Center master key, tagged with the key id that encrypted it so a
-- future rotation can tell old envelopes from new ones.
CREATE TABLE cloud_credentials (
    id                  UUID PRIMARY KEY,
    organization_id     UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    provider            VARCHAR(16)  NOT NULL,  -- aws | azure | gcp
    auth_mode           VARCHAR(32)  NOT NULL,  -- AWS_ASSUME_ROLE | AWS_STATIC_KEYS | AZURE_SERVICE_PRINCIPAL | GCP_SERVICE_ACCOUNT

    display_name        VARCHAR(120) NOT NULL,
    default_region      VARCHAR(64),

    -- Non-secret identifiers, stored in the clear so they can be shown in the UI
    -- and used to build a plan without a decrypt.
    aws_account_id      VARCHAR(32),
    aws_role_arn        VARCHAR(512),
    -- Unguessable, but not a password: it only has meaning inside the customer's
    -- own role trust policy, so it is stored in the clear and shown in the UI.
    aws_external_id     VARCHAR(128),
    -- The access key ID half of a static key pair. Not secret on its own; the
    -- secret access key is the encrypted half.
    aws_access_key_id   VARCHAR(128),
    azure_subscription_id VARCHAR(64),
    azure_tenant_id     VARCHAR(64),
    azure_client_id     VARCHAR(64),
    gcp_project_id      VARCHAR(64),
    gcp_client_email    VARCHAR(256),

    -- AES-GCM ciphertext (base64). NULL for AWS_ASSUME_ROLE, which holds nothing.
    secret_ciphertext   TEXT,
    -- Key id that produced the ciphertext. An unknown id must fail closed rather
    -- than silently decrypt with the wrong key.
    secret_key_id       VARCHAR(64),

    -- Result of the last credential check, so an operator sees a broken
    -- credential before starting a provision rather than three minutes into one.
    last_verified_at    TIMESTAMP,
    last_verify_status  VARCHAR(16),          -- OK | FAILED
    last_verify_message TEXT,

    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by          VARCHAR(120),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,

    CONSTRAINT ck_cloud_cred_provider  CHECK (provider IN ('aws', 'azure', 'gcp')),
    CONSTRAINT ck_cloud_cred_auth_mode CHECK (auth_mode IN (
        'AWS_ASSUME_ROLE', 'AWS_STATIC_KEYS', 'AZURE_SERVICE_PRINCIPAL', 'GCP_SERVICE_ACCOUNT')),
    -- Every mode except assume-role must actually carry ciphertext; a row with
    -- neither a role nor a secret cannot authenticate to anything.
    CONSTRAINT ck_cloud_cred_secret CHECK (
        (auth_mode = 'AWS_ASSUME_ROLE' AND aws_role_arn IS NOT NULL)
        OR (auth_mode <> 'AWS_ASSUME_ROLE' AND secret_ciphertext IS NOT NULL AND secret_key_id IS NOT NULL)
    )
);

CREATE INDEX idx_cloud_cred_org ON cloud_credentials(organization_id);

-- ── Infrastructure stacks ───────────────────────────────────────────────────
-- One row per (organization, environment, target) — the same key the Terraform
-- state object uses. The stored spec is replayed VERBATIM on every later update,
-- refresh and destroy, which is why the Terraform contract may only ever gain
-- optional attributes.
CREATE TABLE infrastructure_stacks (
    id                  UUID PRIMARY KEY,
    organization_id     UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    environment         VARCHAR(16)  NOT NULL,  -- dev | staging | prod
    target              VARCHAR(32)  NOT NULL,  -- aws-ecs | aws-ec2 | azure-aca | gcp-cloudrun
    cloud_credential_id UUID         REFERENCES cloud_credentials(id) ON DELETE SET NULL,

    status              VARCHAR(24)  NOT NULL,  -- DRAFT | PLANNING | PLANNED | APPLYING | ACTIVE | FAILED | DESTROYING | DESTROYED | DRIFTED

    -- The full DeploymentSpec as rendered into terraform.tfvars.json, MINUS
    -- secrets: generated values live in the customer's own secret manager and
    -- customer-supplied ones are resolved from the credential store at run time.
    -- Control Center deliberately never stores ZGATE_FIELD_ENCRYPTION_KEY.
    spec_json           JSONB        NOT NULL,

    -- `terraform output -json` from the last successful apply.
    outputs_json        JSONB,

    -- Denormalised from outputs for listing without parsing JSON.
    public_url          VARCHAR(512),
    web_url             VARCHAR(512),
    fingerprint         VARCHAR(64),

    -- Remote state location, so an operator can find the state object.
    state_bucket        VARCHAR(255),
    state_key           VARCHAR(512),

    last_plan_at        TIMESTAMP,
    last_applied_at     TIMESTAMP,
    last_drift_check_at TIMESTAMP,
    -- Set when a refresh finds reality no longer matches state.
    drift_detected      BOOLEAN      NOT NULL DEFAULT FALSE,
    drift_summary       TEXT,

    created_by          VARCHAR(120),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,

    CONSTRAINT ck_infra_stack_env    CHECK (environment IN ('dev', 'staging', 'prod')),
    CONSTRAINT ck_infra_stack_target CHECK (target IN ('aws-ecs', 'aws-ec2', 'azure-aca', 'gcp-cloudrun')),
    CONSTRAINT ck_infra_stack_status CHECK (status IN (
        'DRAFT', 'PLANNING', 'PLANNED', 'APPLYING', 'ACTIVE',
        'FAILED', 'DESTROYING', 'DESTROYED', 'DRIFTED')),

    -- One stack per org+environment+target. Re-provisioning the same triple
    -- must update the existing stack rather than orphan the first one's state,
    -- which would leave real infrastructure running with nothing tracking it.
    CONSTRAINT uq_infra_stack UNIQUE (organization_id, environment, target)
);

CREATE INDEX idx_infra_stack_org    ON infrastructure_stacks(organization_id);
CREATE INDEX idx_infra_stack_status ON infrastructure_stacks(status);

-- ── Provisioning runs ───────────────────────────────────────────────────────
-- An append-only record of every terraform invocation: who ran what, against
-- which stack, and the full log. This is the audit trail for infrastructure
-- changes, so rows are never updated after completion beyond their terminal
-- status and are never deleted.
CREATE TABLE provisioning_runs (
    id                  UUID PRIMARY KEY,
    stack_id            UUID         NOT NULL REFERENCES infrastructure_stacks(id) ON DELETE CASCADE,
    organization_id     UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- Links an infrastructure change back to the Deployment row that triggered it.
    deployment_id       UUID,

    action              VARCHAR(16)  NOT NULL,  -- PLAN | APPLY | DESTROY | REFRESH
    status              VARCHAR(16)  NOT NULL,  -- QUEUED | RUNNING | SUCCESS | FAILED | CANCELLED

    -- Resource counts parsed from the plan, so the UI can show "12 to add,
    -- 3 to change, 1 to destroy" without the operator reading the log.
    resources_to_add     INTEGER,
    resources_to_change  INTEGER,
    resources_to_destroy INTEGER,

    -- Full terraform stdout/stderr. Secrets never reach here: values are marked
    -- sensitive in the configuration, so Terraform redacts them itself.
    log                 TEXT,
    plan_json           JSONB,
    error_message       TEXT,
    exit_code           INTEGER,

    triggered_by        VARCHAR(120),
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_prov_run_action CHECK (action IN ('PLAN', 'APPLY', 'DESTROY', 'REFRESH')),
    CONSTRAINT ck_prov_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_prov_run_stack   ON provisioning_runs(stack_id, created_at DESC);
CREATE INDEX idx_prov_run_org     ON provisioning_runs(organization_id, created_at DESC);
CREATE INDEX idx_prov_run_status  ON provisioning_runs(status);
