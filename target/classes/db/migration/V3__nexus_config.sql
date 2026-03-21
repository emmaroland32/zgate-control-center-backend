CREATE TABLE nexus_config (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key  VARCHAR(200) NOT NULL UNIQUE,
    value       TEXT,
    description VARCHAR(500),
    category    VARCHAR(100) NOT NULL DEFAULT 'GENERAL',
    is_secret   BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by  VARCHAR(255),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_config_category ON nexus_config(category);
CREATE INDEX idx_config_key ON nexus_config(config_key);

INSERT INTO nexus_config (config_key, value, description, category, is_secret) VALUES
('nexus.app.name', 'ZGATE Nexus', 'Application display name', 'APP', false),
('nexus.app.support_email', 'support@zgate.io', 'Support contact email', 'APP', false),
('nexus.billing.auto_invoice', 'true', 'Auto-generate invoices on billing cycle', 'BILLING', false),
('nexus.billing.tax_rate', '0.15', 'Default tax rate for invoices', 'BILLING', false),
('nexus.billing.invoice_due_days', '30', 'Days until invoice is due', 'BILLING', false),
('nexus.billing.currency', 'USD', 'Default billing currency', 'BILLING', false),
('nexus.security.max_login_attempts', '5', 'Max failed login attempts before lockout', 'SECURITY', false),
('nexus.security.session_timeout_hours', '24', 'JWT token expiry in hours', 'SECURITY', false),
('nexus.alerts.global_email', '', 'Global email for all alert notifications', 'ALERTS', false),
('nexus.deployments.auto_rollback', 'false', 'Auto rollback failed deployments', 'DEPLOYMENTS', false),
('nexus.shared_services.default_call_limit', '10000', 'Default monthly call limit for new subscriptions', 'SHARED_SERVICES', false);
