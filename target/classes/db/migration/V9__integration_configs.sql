CREATE TABLE IF NOT EXISTS integration_configs (
    id UUID PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100),
    icon_url VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config_json TEXT,
    configured_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP
);

-- Seed default integrations
INSERT INTO integration_configs (id, code, name, description, category, enabled, created_at)
VALUES
    (gen_random_uuid(), 'slack', 'Slack', 'Send notifications and alerts to Slack channels', 'communication', false, NOW()),
    (gen_random_uuid(), 'teams', 'Microsoft Teams', 'Send notifications to Microsoft Teams channels', 'communication', false, NOW()),
    (gen_random_uuid(), 'jira', 'Jira', 'Create and track issues in Atlassian Jira', 'project_management', false, NOW()),
    (gen_random_uuid(), 'pagerduty', 'PagerDuty', 'Route alerts to PagerDuty for incident management', 'monitoring', false, NOW()),
    (gen_random_uuid(), 'datadog', 'Datadog', 'Forward metrics and logs to Datadog', 'monitoring', false, NOW()),
    (gen_random_uuid(), 'email', 'Email (SMTP)', 'Send notifications via email', 'communication', true, NOW())
ON CONFLICT (code) DO NOTHING;
