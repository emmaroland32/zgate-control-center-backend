-- Managed backup module ("ZGATE Cloud"): a paid, entitlement-gated, zero-knowledge database-backup
-- service. Each org instance encrypts its dump with a customer-held key and streams the CIPHERTEXT
-- straight to S3 via a presigned URL; Control Center only ever stores metadata + gates/bills it.
-- Additive; inert until controlcenter.backup.enabled=true and a plan is provisioned for an org.

-- The org's backup subscription: quota, retention, pricing, and paid-through date.
CREATE TABLE IF NOT EXISTS backup_plan (
    id                       UUID PRIMARY KEY,
    organization_id          UUID NOT NULL UNIQUE,
    enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
    storage_quota_gb         INTEGER NOT NULL DEFAULT 10,
    retention_days           INTEGER NOT NULL DEFAULT 30,
    max_retained_backups     INTEGER,                         -- null = unlimited (quota still applies)
    price_per_month          NUMERIC(12,2) NOT NULL DEFAULT 0,
    price_per_gb_month       NUMERIC(12,4) NOT NULL DEFAULT 0,
    currency                 VARCHAR(3) NOT NULL DEFAULT 'USD',
    subscription_valid_until TIMESTAMP,                       -- null = active until cancelled
    created_at               TIMESTAMP NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP
);

-- One row per backup. size/sha256/completed_at/expires_at are filled on completion. The bytes live
-- in S3 (s3_key), never in this DB; client_encrypted records that the payload is customer-encrypted.
CREATE TABLE IF NOT EXISTS backup_record (
    id               UUID PRIMARY KEY,
    organization_id  UUID NOT NULL,
    node_id          VARCHAR(255),                            -- which instance produced it (optional)
    s3_key           VARCHAR(512) NOT NULL,
    label            VARCHAR(255),
    size_bytes       BIGINT,
    sha256           VARCHAR(128),
    client_encrypted BOOLEAN NOT NULL DEFAULT TRUE,
    status           VARCHAR(32) NOT NULL DEFAULT 'INITIATED',-- INITIATED|COMPLETED|FAILED|EXPIRED|DELETED
    failure_reason   VARCHAR(512),
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    completed_at     TIMESTAMP,
    expires_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_backup_record_org    ON backup_record (organization_id, status);
CREATE INDEX IF NOT EXISTS idx_backup_record_expiry ON backup_record (expires_at);
