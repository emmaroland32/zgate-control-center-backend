-- Add type and required columns to nexus_config for frontend config editor
ALTER TABLE nexus_config ADD COLUMN config_type VARCHAR(20) NOT NULL DEFAULT 'string';
ALTER TABLE nexus_config ADD COLUMN required    BOOLEAN     NOT NULL DEFAULT FALSE;

-- Classify existing rows
UPDATE nexus_config SET config_type = 'boolean' WHERE value IN ('true', 'false');
UPDATE nexus_config SET config_type = 'number'  WHERE value ~ '^\d+(\.\d+)?$' AND config_type = 'string';
UPDATE nexus_config SET required = TRUE         WHERE config_key IN (
    'nexus.app.name', 'nexus.billing.currency', 'nexus.security.max_login_attempts',
    'nexus.security.session_timeout_hours'
);
