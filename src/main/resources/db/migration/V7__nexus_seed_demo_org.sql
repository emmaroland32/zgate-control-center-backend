-- Demo organization for development — lets the licenses page work out of the box
-- without requiring manual org creation first.

INSERT INTO organizations (id, name, slug, contact_email, contact_name, country, tier,
                           deployment_status, deployment_env)
VALUES (
    'dde5f0d1-75d6-48c0-8f3e-29852ae26f13',
    'Demo Financial Services',
    'demo-fs',
    'admin@demo-fs.com',
    'Demo Admin',
    'ZA',
    'ENTERPRISE',
    'HEALTHY',
    'PRODUCTION'
) ON CONFLICT (id) DO NOTHING;
