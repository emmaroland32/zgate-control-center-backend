-- ============================================================================
-- Bare-metal provisioning: deploy to a customer-supplied Ubuntu server over SSH
-- ============================================================================
-- Additive. Widens two CHECK constraints and adds the SSH connection columns.
-- An installation that only ever deploys to a cloud is unaffected.
-- ============================================================================

-- ── SSH connection details ──────────────────────────────────────────────────
-- All non-secret. The private key itself is encrypted into secret_ciphertext,
-- exactly like every other stored credential secret.
ALTER TABLE cloud_credentials ADD COLUMN IF NOT EXISTS ssh_host            VARCHAR(255);
ALTER TABLE cloud_credentials ADD COLUMN IF NOT EXISTS ssh_port            INTEGER;
ALTER TABLE cloud_credentials ADD COLUMN IF NOT EXISTS ssh_user            VARCHAR(64);
-- The server's host public key. Pinning it is what stops a man in the middle
-- impersonating the customer's server and receiving their database credentials.
ALTER TABLE cloud_credentials ADD COLUMN IF NOT EXISTS ssh_host_public_key TEXT;

-- ── Widen the provider + auth-mode constraints ──────────────────────────────
ALTER TABLE cloud_credentials DROP CONSTRAINT IF EXISTS ck_cloud_cred_provider;
ALTER TABLE cloud_credentials ADD  CONSTRAINT ck_cloud_cred_provider
    CHECK (provider IN ('aws', 'azure', 'gcp', 'baremetal'));

ALTER TABLE cloud_credentials DROP CONSTRAINT IF EXISTS ck_cloud_cred_auth_mode;
ALTER TABLE cloud_credentials ADD  CONSTRAINT ck_cloud_cred_auth_mode
    CHECK (auth_mode IN (
        'AWS_ASSUME_ROLE', 'AWS_STATIC_KEYS',
        'AZURE_SERVICE_PRINCIPAL', 'GCP_SERVICE_ACCOUNT',
        'SSH_KEY'));

-- SSH_KEY always carries a secret (the private key) and needs somewhere to
-- connect, so it joins the "must have ciphertext" side and adds its own
-- host/user requirement. AWS_ASSUME_ROLE remains the only secretless mode.
ALTER TABLE cloud_credentials DROP CONSTRAINT IF EXISTS ck_cloud_cred_secret;
ALTER TABLE cloud_credentials ADD  CONSTRAINT ck_cloud_cred_secret
    CHECK (
        (auth_mode = 'AWS_ASSUME_ROLE' AND aws_role_arn IS NOT NULL)
        OR (auth_mode = 'SSH_KEY'
            AND secret_ciphertext IS NOT NULL AND secret_key_id IS NOT NULL
            AND ssh_host IS NOT NULL AND ssh_user IS NOT NULL)
        OR (auth_mode NOT IN ('AWS_ASSUME_ROLE', 'SSH_KEY')
            AND secret_ciphertext IS NOT NULL AND secret_key_id IS NOT NULL)
    );

-- ── Widen the stack target constraint ───────────────────────────────────────
ALTER TABLE infrastructure_stacks DROP CONSTRAINT IF EXISTS ck_infra_stack_target;
ALTER TABLE infrastructure_stacks ADD  CONSTRAINT ck_infra_stack_target
    CHECK (target IN ('aws-ecs', 'aws-ec2', 'azure-aca', 'gcp-cloudrun', 'baremetal'));
