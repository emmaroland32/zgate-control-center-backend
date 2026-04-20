-- ZGATE Nexus Control Center — Standalone Database Schema
-- Database: zgate_nexus (separate PostgreSQL database)

-- Enable pgcrypto for gen_random_uuid() on PostgreSQL < 13
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- USERS (ZGATE internal staff only)
-- ============================================================
CREATE TABLE nexus_users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(200) NOT NULL,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role         VARCHAR(50) NOT NULL DEFAULT 'VIEWER',
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- PARTNERS
-- ============================================================
CREATE TABLE partners (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_name         VARCHAR(200) NOT NULL,
    tier                 VARCHAR(50) NOT NULL,
    status               VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    contact_name         VARCHAR(200),
    contact_email        VARCHAR(255),
    contact_phone        VARCHAR(50),
    country              VARCHAR(100),
    region               VARCHAR(200),
    website              VARCHAR(500),
    revenue_share_percent NUMERIC(5,2),
    contract_expiry      DATE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- ORGANIZATIONS (ZGATE customer deployments)
-- ============================================================
CREATE TABLE organizations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    slug              VARCHAR(100) NOT NULL UNIQUE,
    contact_email     VARCHAR(255),
    contact_name      VARCHAR(200),
    country           VARCHAR(100),
    region            VARCHAR(200),
    tier              VARCHAR(50) NOT NULL DEFAULT 'STARTER',
    deployment_status VARCHAR(50) NOT NULL DEFAULT 'PROVISIONING',
    deployment_env    VARCHAR(50) NOT NULL DEFAULT 'PRODUCTION',
    backend_url       VARCHAR(500),
    deployed_version  VARCHAR(50),
    service_api_key_hash VARCHAR(255),  -- hashed key for machine-to-machine calls
    partner_id        UUID REFERENCES partners(id) ON DELETE SET NULL,
    active_users      INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ
);

CREATE INDEX idx_organizations_status ON organizations(deployment_status);
CREATE INDEX idx_organizations_partner ON organizations(partner_id);
CREATE INDEX idx_organizations_env    ON organizations(deployment_env);

-- ============================================================
-- RELEASES
-- ============================================================
CREATE TABLE releases (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version             VARCHAR(50) NOT NULL UNIQUE,
    channel             VARCHAR(50) NOT NULL DEFAULT 'STABLE',
    docker_tag          VARCHAR(255) NOT NULL,
    docker_registry     VARCHAR(500),
    release_notes       TEXT,
    has_breaking_changes BOOLEAN NOT NULL DEFAULT FALSE,
    is_latest           BOOLEAN NOT NULL DEFAULT FALSE,
    migrations          TEXT,  -- JSON array of migration script names
    published_by        VARCHAR(255),
    published_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- DEPLOYMENTS (push updates to organizations)
-- ============================================================
CREATE TABLE deployments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    release_id      UUID REFERENCES releases(id),
    status          VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    deployed_by     VARCHAR(255),
    from_version    VARCHAR(50),
    to_version      VARCHAR(50),
    logs            TEXT,
    scheduled_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_deployments_org ON deployments(organization_id);
CREATE INDEX idx_deployments_status ON deployments(status);

-- ============================================================
-- LICENSES
-- ============================================================
CREATE TABLE licenses (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    module_name       VARCHAR(100) NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    expires_at        TIMESTAMPTZ,
    max_users         INTEGER,
    activated_at      TIMESTAMPTZ,
    license_file_hash VARCHAR(255),
    fingerprint       VARCHAR(500),
    features          TEXT,  -- JSON
    issued_by         VARCHAR(255),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_licenses_org    ON licenses(organization_id);
CREATE INDEX idx_licenses_status ON licenses(status);
CREATE INDEX idx_licenses_expiry ON licenses(expires_at);

-- ============================================================
-- SHARED SERVICES (centralized API services)
-- ============================================================
CREATE TABLE shared_services (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(200) NOT NULL,
    code           VARCHAR(100) NOT NULL UNIQUE,   -- e.g. NIN_VALIDATION
    category       VARCHAR(50) NOT NULL,           -- IDENTITY, SANCTIONS, KYC, CREDIT, COMMUNICATION
    description    TEXT,
    provider       VARCHAR(200),
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    price_per_call NUMERIC(10,4) NOT NULL DEFAULT 0.05,
    currency       VARCHAR(10) NOT NULL DEFAULT 'USD',
    pricing_model  VARCHAR(50) NOT NULL DEFAULT 'PER_CALL',
    volume_tiers   TEXT,  -- JSON
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- ORG SERVICE SUBSCRIPTIONS (which orgs have which services)
-- ============================================================
CREATE TABLE org_service_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      UUID NOT NULL REFERENCES shared_services(id) ON DELETE CASCADE,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    call_limit      BIGINT,  -- monthly cap; NULL = unlimited
    api_key_hash    VARCHAR(255),
    enabled_by      VARCHAR(255),
    enabled_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(organization_id, service_id)
);

CREATE INDEX idx_subs_org     ON org_service_subscriptions(organization_id);
CREATE INDEX idx_subs_service ON org_service_subscriptions(service_id);

-- ============================================================
-- SERVICE USAGE (per-call metering, aggregated monthly)
-- ============================================================
CREATE TABLE service_usage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    service_id      UUID NOT NULL REFERENCES shared_services(id) ON DELETE CASCADE,
    call_count      BIGINT NOT NULL DEFAULT 0,
    success_count   BIGINT NOT NULL DEFAULT 0,
    failure_count   BIGINT NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(12,4) NOT NULL DEFAULT 0,
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(organization_id, service_id, period_start)
);

CREATE INDEX idx_usage_org    ON service_usage(organization_id);
CREATE INDEX idx_usage_period ON service_usage(period_start, period_end);

-- ============================================================
-- INVOICES
-- ============================================================
CREATE TABLE invoices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    invoice_number  VARCHAR(50) NOT NULL UNIQUE,
    period_start    DATE NOT NULL,
    period_end      DATE NOT NULL,
    subtotal        NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_rate        NUMERIC(5,4) NOT NULL DEFAULT 0.15,
    tax_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency        VARCHAR(10) NOT NULL DEFAULT 'USD',
    status          VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    due_date        DATE,
    paid_at         TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    notes           TEXT,
    generated_by    VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoices_org    ON invoices(organization_id);
CREATE INDEX idx_invoices_status ON invoices(status);

-- ============================================================
-- INVOICE LINE ITEMS
-- ============================================================
CREATE TABLE invoice_line_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id  UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    description VARCHAR(500) NOT NULL,
    quantity    BIGINT,
    unit_price  NUMERIC(10,4) NOT NULL,
    total_price NUMERIC(12,2) NOT NULL,
    service_id  UUID REFERENCES shared_services(id)
);

-- ============================================================
-- ALERT RULES
-- ============================================================
CREATE TABLE alert_rules (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        VARCHAR(200) NOT NULL,
    description                 TEXT,
    severity                    VARCHAR(50) NOT NULL DEFAULT 'MEDIUM',
    metric                      VARCHAR(100) NOT NULL,
    operator                    VARCHAR(10) NOT NULL DEFAULT '>',
    threshold                   DOUBLE PRECISION NOT NULL,
    evaluation_window_minutes   INTEGER NOT NULL DEFAULT 5,
    cooldown_minutes            INTEGER NOT NULL DEFAULT 30,
    channels                    TEXT,  -- JSON: ["EMAIL","SLACK"]
    org_scope                   VARCHAR(50) NOT NULL DEFAULT 'ALL',
    org_id                      UUID REFERENCES organizations(id) ON DELETE CASCADE,
    enabled                     BOOLEAN NOT NULL DEFAULT TRUE,
    trigger_count               BIGINT NOT NULL DEFAULT 0,
    last_triggered_at           TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- ALERTS
-- ============================================================
CREATE TABLE alerts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id          UUID REFERENCES alert_rules(id) ON DELETE SET NULL,
    organization_id  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    status           VARCHAR(50) NOT NULL DEFAULT 'FIRING',
    severity         VARCHAR(50) NOT NULL,
    title            VARCHAR(500) NOT NULL,
    message          TEXT,
    metric_value     DOUBLE PRECISION,
    acknowledged_by  VARCHAR(255),
    acknowledged_at  TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    fired_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alerts_status ON alerts(status);
CREATE INDEX idx_alerts_org    ON alerts(organization_id);

-- ============================================================
-- AUDIT LOGS
-- ============================================================
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor           VARCHAR(200),
    actor_email     VARCHAR(255),
    action          VARCHAR(200) NOT NULL,
    entity_type     VARCHAR(100),
    entity_id       VARCHAR(100),
    organization_id UUID,
    ip_address      VARCHAR(50),
    details         TEXT,  -- JSON
    status          VARCHAR(50) NOT NULL DEFAULT 'SUCCESS',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_org    ON audit_logs(organization_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
CREATE INDEX idx_audit_ts     ON audit_logs(created_at DESC);
