-- ZGATE Nexus Control Center — Telemetry & Error Reporting

-- ============================================================
-- TELEMETRY EVENTS
-- Org instances ship errors, warnings, and system metrics here.
-- ============================================================
CREATE TABLE telemetry_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    app_version     VARCHAR(50),
    environment     VARCHAR(50),                    -- LOCAL, STAGING, PRODUCTION
    level           VARCHAR(20) NOT NULL,           -- ERROR, WARNING, INFO, METRIC
    category        VARCHAR(100) NOT NULL,          -- SYSTEM, DATABASE, AUTH, API, PERFORMANCE, LICENSE
    message         TEXT NOT NULL,
    stack_trace     TEXT,                           -- Full stack trace for errors
    error_code      VARCHAR(100),                   -- e.g. FLYWAY_MIGRATION_FAILED
    context         TEXT,                           -- JSON: extra key/value pairs
    host            VARCHAR(255),                   -- Hostname / pod name
    occurred_at     TIMESTAMPTZ NOT NULL,           -- When it happened on the org side
    received_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged    BOOLEAN NOT NULL DEFAULT FALSE,
    acknowledged_by VARCHAR(255),
    acknowledged_at TIMESTAMPTZ
);

CREATE INDEX idx_telemetry_org      ON telemetry_events(organization_id);
CREATE INDEX idx_telemetry_level    ON telemetry_events(level);
CREATE INDEX idx_telemetry_category ON telemetry_events(category);
CREATE INDEX idx_telemetry_occurred ON telemetry_events(occurred_at DESC);
CREATE INDEX idx_telemetry_ack      ON telemetry_events(acknowledged);
