-- ZGATE Nexus Control Center — Integrations: Webhooks + API Keys

-- ============================================================
-- WEBHOOKS (Nexus → external system notifications)
-- ============================================================
CREATE TABLE webhooks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(200) NOT NULL,
    url           VARCHAR(1000) NOT NULL,
    secret_hash   VARCHAR(255),               -- HMAC signing secret (SHA-256 hashed)
    events        TEXT NOT NULL,              -- JSON array: ["DEPLOYMENT_SUCCESS","LICENSE_EXPIRY"]
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    headers       TEXT,                       -- JSON extra headers
    last_fired_at TIMESTAMPTZ,
    last_status   VARCHAR(50),               -- SUCCESS / FAILED / PENDING
    fire_count    BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- WEBHOOK DELIVERY LOG
-- ============================================================
CREATE TABLE webhook_deliveries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id  UUID NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
    event       VARCHAR(200) NOT NULL,
    payload     TEXT,
    status_code INTEGER,
    response    TEXT,
    fired_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    duration_ms INTEGER
);

CREATE INDEX idx_wh_deliveries_wh ON webhook_deliveries(webhook_id);
CREATE INDEX idx_wh_deliveries_ts ON webhook_deliveries(fired_at DESC);

-- ============================================================
-- API KEYS (external systems calling Nexus)
-- ============================================================
CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(200) NOT NULL,
    key_hash     VARCHAR(255) NOT NULL UNIQUE,  -- SHA-256 of the raw key
    key_prefix   VARCHAR(20)  NOT NULL,          -- e.g. "zgn_live_xxxx" (first 20 chars, shown in UI)
    scopes       TEXT NOT NULL,                  -- JSON array: ["read:orgs","write:deployments"]
    expires_at   TIMESTAMPTZ,
    revoked      BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at   TIMESTAMPTZ,
    revoked_by   VARCHAR(255),
    last_used_at TIMESTAMPTZ,
    created_by   VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_apikeys_hash    ON api_keys(key_hash);
CREATE INDEX idx_apikeys_revoked ON api_keys(revoked);
