-- ZGATE Nexus — Demo data seed (V8)
-- Partners, organizations, releases, users, deployments, licenses
-- Idempotent: all inserts use ON CONFLICT DO NOTHING

-- ============================================================
-- PARTNERS
-- ============================================================
INSERT INTO partners (id, company_name, tier, status, contact_name, contact_email, contact_phone,
                      country, region, website, revenue_share_percent, contract_expiry)
VALUES
  ('a0000001-0000-0000-0000-000000000001', 'Stellartech Solutions', 'PLATINUM', 'ACTIVE',
   'Amara Diallo', 'amara.diallo@stellartech.io', '+27 11 000 1234',
   'South Africa', 'Africa', 'https://stellartech.io', 20.00, '2027-12-31'),

  ('a0000002-0000-0000-0000-000000000002', 'FinAxis Europe GmbH', 'GOLD', 'ACTIVE',
   'Klaus Weber', 'k.weber@finaxis.eu', '+49 30 9876 5432',
   'Germany', 'Europe', 'https://finaxis.eu', 15.00, '2027-06-30'),

  ('a0000003-0000-0000-0000-000000000003', 'Meridian Tech Nigeria', 'GOLD', 'ACTIVE',
   'Chidi Okonkwo', 'c.okonkwo@meridiantech.ng', '+234 801 234 5678',
   'Nigeria', 'Africa', 'https://meridiantech.ng', 15.00, '2026-12-31'),

  ('a0000004-0000-0000-0000-000000000004', 'AfriaTech Partners Ltd', 'SILVER', 'ACTIVE',
   'Wanjiku Mwangi', 'w.mwangi@afriatech.co.ke', '+254 700 123456',
   'Kenya', 'Africa', 'https://afriatech.co.ke', 10.00, '2026-09-30')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- ORGANIZATIONS
-- ============================================================
INSERT INTO organizations (id, name, slug, contact_email, contact_name, country, tier,
                           deployment_status, deployment_env, deployed_version, active_users, partner_id)
VALUES
  ('b0000001-0000-0000-0000-000000000001', 'Apex Capital Management', 'apex-capital',
   'admin@apexcapital.co.za', 'Sipho Ndlovu', 'ZA', 'ENTERPRISE',
   'HEALTHY', 'PRODUCTION', '2.5.0', 214, 'a0000001-0000-0000-0000-000000000001'),

  ('b0000002-0000-0000-0000-000000000002', 'Coronation Fund Managers', 'coronation-fm',
   'ops@coronation.co.za', 'Taryn Botha', 'ZA', 'ENTERPRISE',
   'DEGRADED', 'PRODUCTION', '2.5.0', 89, 'a0000001-0000-0000-0000-000000000001'),

  ('b0000003-0000-0000-0000-000000000003', 'Sanlam Investments', 'sanlam-invest',
   'tech@sanlam.co.za', 'Pieter van der Merwe', 'ZA', 'ENTERPRISE',
   'HEALTHY', 'PRODUCTION', '2.5.0', 178, 'a0000001-0000-0000-0000-000000000001'),

  ('b0000004-0000-0000-0000-000000000004', 'Ninety One Asset Management', 'ninety-one',
   'devops@ninetyone.com', 'Karen Louw', 'ZA', 'STANDARD',
   'OFFLINE', 'STAGING', '2.5.0', 0, 'a0000002-0000-0000-0000-000000000002'),

  ('b0000005-0000-0000-0000-000000000005', 'First National Bank Corp', 'fnb-corp',
   'platform@fnb.co.za', 'Thabo Mahlangu', 'ZA', 'ENTERPRISE',
   'HEALTHY', 'PRODUCTION', '2.5.0', 341, 'a0000001-0000-0000-0000-000000000001'),

  ('dde5f0d1-75d6-48c0-8f3e-29852ae26f13', 'Demo Financial Services', 'demo-fs-v8',
   'admin@demo-fs.com', 'Demo Admin', 'ZA', 'ENTERPRISE',
   'HEALTHY', 'PRODUCTION', '2.4.5', 10, NULL)
ON CONFLICT (slug) DO NOTHING;

-- ============================================================
-- RELEASES
-- ============================================================
INSERT INTO releases (id, version, channel, docker_tag, docker_registry, release_notes,
                      has_breaking_changes, is_latest, migrations, published_by)
VALUES
  ('c0000001-0000-0000-0000-000000000001', '2.5.0', 'STABLE',
   'zgate/backend:2.5.0', 'registry.zgate.io',
   'Full portfolio rebalancing engine, improved NAV calculation performance, enhanced audit trail compression.',
   FALSE, TRUE,
   '["V2_5_0__portfolio_rebalancing.sql","V2_5_0__nav_perf_index.sql","V2_5_0__audit_compression.sql"]',
   'alice@zgate.io'),

  ('c0000002-0000-0000-0000-000000000002', '2.4.5', 'LTS',
   'zgate/backend:2.4.5', 'registry.zgate.io',
   'Security patch: JWT rotation hardened. Flyway repair improved for out-of-order migrations.',
   FALSE, FALSE,
   '["V2_4_5__jwt_rotation.sql"]',
   'bob@zgate.io'),

  ('c0000003-0000-0000-0000-000000000003', '2.5.1-beta.1', 'BETA',
   'zgate/backend:2.5.1-beta.1', 'registry.zgate.io',
   'Experimental: AI-powered anomaly detection for trade reconciliation. Not production-ready.',
   TRUE, FALSE,
   '["V2_5_1__recon_ai_schema.sql","V2_5_1__risk_signals.sql"]',
   'charlie@zgate.io'),

  ('c0000004-0000-0000-0000-000000000004', '2.4.6-hotfix', 'HOTFIX',
   'zgate/backend:2.4.6-hotfix', 'registry.zgate.io',
   'Critical fix: Division-by-zero in NAV calculation when fund has zero units outstanding.',
   FALSE, FALSE,
   '[]',
   'alice@zgate.io'),

  ('c0000005-0000-0000-0000-000000000005', '2.3.0', 'LTS',
   'zgate/backend:2.3.0', 'registry.zgate.io',
   'Long-term support baseline. Includes all patches up to 2.3.x series.',
   TRUE, FALSE,
   '["V2_3_0__schema_restructure.sql"]',
   'bob@zgate.io')
ON CONFLICT (version) DO NOTHING;

-- ============================================================
-- ADDITIONAL NEXUS USERS
-- ============================================================
INSERT INTO nexus_users (id, name, email, password_hash, role, active)
VALUES
  ('d0000001-0000-0000-0000-000000000001', 'Sarah Chen',    'sarah.chen@zgate.io',
   '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.', 'ADMIN',       TRUE),
  ('d0000002-0000-0000-0000-000000000002', 'Marcus Obi',    'marcus.obi@zgate.io',
   '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.', 'ADMIN',       TRUE),
  ('d0000003-0000-0000-0000-000000000003', 'Priya Naidoo',  'priya.naidoo@zgate.io',
   '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.', 'SUPPORT',     TRUE),
  ('d0000004-0000-0000-0000-000000000004', 'James Kweku',   'james.kweku@zgate.io',
   '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.', 'ADMIN',       TRUE),
  ('d0000005-0000-0000-0000-000000000005', 'Felix Mensah',  'felix.mensah@zgate.io',
   '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.', 'SUPPORT',     TRUE),
  ('d0000006-0000-0000-0000-000000000006', 'Lena Hofmann',  'lena.hofmann@zgate.io',
   '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.', 'VIEWER',      TRUE)
ON CONFLICT (email) DO NOTHING;

-- ============================================================
-- DEPLOYMENTS
-- ============================================================
INSERT INTO deployments (organization_id, release_id, status, deployed_by,
                         from_version, to_version, started_at, completed_at, logs)
VALUES
  -- Apex Capital: 2.4.5 → 2.5.0, SUCCESS
  ('b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001',
   'SUCCESS', 'alice@zgate.io', '2.4.5', '2.5.0',
   NOW() - INTERVAL '6 hours', NOW() - INTERVAL '5 hours 46 minutes',
   '[10:05:00] Starting deployment of v2.5.0
[10:05:12] Pulling image zgate/backend:2.5.0...
[10:07:30] Image pulled successfully
[10:07:32] Running Flyway migrations... (3 applied)
[10:09:20] Draining traffic from old container
[10:10:00] Starting new container
[10:12:00] Health check passed
[10:18:00] Deployment SUCCESS'),

  -- Coronation: 2.4.5 → 2.5.0, IN_PROGRESS
  ('b0000002-0000-0000-0000-000000000002', 'c0000001-0000-0000-0000-000000000001',
   'IN_PROGRESS', 'bob@zgate.io', '2.4.5', '2.5.0',
   NOW() - INTERVAL '12 minutes', NULL,
   '[09:30:00] Starting deployment of v2.5.0
[09:30:10] Pulling image zgate/backend:2.5.0...
[09:32:45] Image pulled successfully
[09:32:50] Running Flyway migrations...'),

  -- Sanlam: 2.4.5 → 2.5.0, SUCCESS
  ('b0000003-0000-0000-0000-000000000003', 'c0000001-0000-0000-0000-000000000001',
   'SUCCESS', 'alice@zgate.io', '2.4.5', '2.5.0',
   NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days' + INTERVAL '14 minutes',
   '[08:00:00] Starting deployment of v2.5.0
[08:14:00] Deployment SUCCESS'),

  -- Ninety One: 2.4.5 → 2.5.0, FAILED
  ('b0000004-0000-0000-0000-000000000004', 'c0000001-0000-0000-0000-000000000001',
   'FAILED', 'charlie@zgate.io', '2.4.5', '2.5.0',
   NOW() - INTERVAL '4 hours', NOW() - INTERVAL '3 hours 52 minutes',
   '[14:20:00] Starting deployment of v2.5.0
[14:20:08] Pulling image zgate/backend:2.5.0...
[14:22:50] Image pulled successfully
[14:24:10] ERROR: Migration V2_5_0__portfolio_rebalancing.sql failed
[14:24:10] Constraint violation: column already exists
[14:24:15] Rolling back Flyway state...
[14:28:00] Deployment FAILED — original version still running'),

  -- FNB: 2.4.5 → 2.5.0, SUCCESS
  ('b0000005-0000-0000-0000-000000000005', 'c0000001-0000-0000-0000-000000000001',
   'SUCCESS', 'marcus.obi@zgate.io', '2.4.5', '2.5.0',
   NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day' + INTERVAL '16 minutes',
   '[10:00:00] Starting deployment of v2.5.0
[10:16:00] Deployment SUCCESS'),

  -- Demo org: 2.3.0 → 2.4.5, SUCCESS (historical)
  ('dde5f0d1-75d6-48c0-8f3e-29852ae26f13', 'c0000002-0000-0000-0000-000000000002',
   'SUCCESS', 'admin@zgate.io', '2.3.0', '2.4.5',
   NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days' + INTERVAL '11 minutes',
   '[09:00:00] Starting deployment of v2.4.5
[09:11:00] Deployment SUCCESS');

-- ============================================================
-- LICENSES (demo org + new orgs)
-- ============================================================
INSERT INTO licenses (organization_id, module_name, status, expires_at, max_users,
                      activated_at, issued_by)
VALUES
  -- Demo org
  ('dde5f0d1-75d6-48c0-8f3e-29852ae26f13', 'MUTUAL_FUND',          'ACTIVE',
   NOW() + INTERVAL '365 days', 50,  NOW() - INTERVAL '365 days', 'admin@zgate.io'),
  ('dde5f0d1-75d6-48c0-8f3e-29852ae26f13', 'PORTFOLIO_MANAGEMENT', 'ACTIVE',
   NOW() + INTERVAL '365 days', 50,  NOW() - INTERVAL '365 days', 'admin@zgate.io'),
  ('dde5f0d1-75d6-48c0-8f3e-29852ae26f13', 'CORE',                 'ACTIVE',
   NOW() + INTERVAL '365 days', 100, NOW() - INTERVAL '365 days', 'admin@zgate.io'),

  -- Apex Capital
  ('b0000001-0000-0000-0000-000000000001', 'MUTUAL_FUND',          'ACTIVE',
   NOW() + INTERVAL '10 days', 200, NOW() - INTERVAL '355 days', 'admin@zgate.io'),
  ('b0000001-0000-0000-0000-000000000001', 'PORTFOLIO_MANAGEMENT', 'ACTIVE',
   NOW() + INTERVAL '10 days', 200, NOW() - INTERVAL '355 days', 'admin@zgate.io'),
  ('b0000001-0000-0000-0000-000000000001', 'TRADE_MANAGEMENT',     'ACTIVE',
   NOW() + INTERVAL '10 days', 200, NOW() - INTERVAL '355 days', 'admin@zgate.io'),

  -- Coronation
  ('b0000002-0000-0000-0000-000000000002', 'MUTUAL_FUND',          'ACTIVE',
   NOW() + INTERVAL '25 days', 100, NOW() - INTERVAL '340 days', 'admin@zgate.io'),
  ('b0000002-0000-0000-0000-000000000002', 'RISK_ENGINE',          'ACTIVE',
   NOW() + INTERVAL '25 days', 100, NOW() - INTERVAL '340 days', 'admin@zgate.io'),

  -- Sanlam
  ('b0000003-0000-0000-0000-000000000003', 'MUTUAL_FUND',          'ACTIVE',
   NOW() + INTERVAL '180 days', 200, NOW() - INTERVAL '185 days', 'admin@zgate.io'),
  ('b0000003-0000-0000-0000-000000000003', 'COMPLIANCE',           'ACTIVE',
   NOW() + INTERVAL '180 days', 200, NOW() - INTERVAL '185 days', 'admin@zgate.io'),

  -- FNB
  ('b0000005-0000-0000-0000-000000000005', 'MUTUAL_FUND',          'ACTIVE',
   NOW() + INTERVAL '90 days', 400, NOW() - INTERVAL '275 days', 'admin@zgate.io'),
  ('b0000005-0000-0000-0000-000000000005', 'PORTFOLIO_MANAGEMENT', 'ACTIVE',
   NOW() + INTERVAL '90 days', 400, NOW() - INTERVAL '275 days', 'admin@zgate.io'),
  ('b0000005-0000-0000-0000-000000000005', 'TRADE_MANAGEMENT',     'ACTIVE',
   NOW() + INTERVAL '90 days', 400, NOW() - INTERVAL '275 days', 'admin@zgate.io'),
  ('b0000005-0000-0000-0000-000000000005', 'RISK_ENGINE',          'EXPIRED',
   NOW() - INTERVAL '5 days',  400, NOW() - INTERVAL '370 days', 'admin@zgate.io');

-- ============================================================
-- AUDIT LOG ENTRIES
-- ============================================================
INSERT INTO audit_logs (actor, actor_email, action, entity_type, entity_id, details, status, created_at)
VALUES
  ('sarah.chen', 'sarah.chen@zgate.io', 'DEPLOYMENT_PUSHED',   'Deployment', NULL,
   'Pushed v2.5.0 to Apex Capital Management', 'SUCCESS', NOW() - INTERVAL '6 hours'),
  ('alice',       'alice@zgate.io',      'RELEASE_PUBLISHED',  'Release',    'c0000001-0000-0000-0000-000000000001',
   'Published release v2.5.0 (STABLE)', 'SUCCESS', NOW() - INTERVAL '7 hours'),
  ('marcus.obi',  'marcus.obi@zgate.io', 'LICENSE_ISSUED',     'License',    NULL,
   'Issued Mutual Fund license bundle to Apex Capital', 'SUCCESS', NOW() - INTERVAL '10 hours'),
  ('admin',       'admin@zgate.io',      'ORG_CREATED',        'Organization','b0000001-0000-0000-0000-000000000001',
   'New organization Apex Capital Management onboarded', 'SUCCESS', NOW() - INTERVAL '2 days'),
  ('charlie',     'charlie@zgate.io',    'DEPLOYMENT_FAILED',  'Deployment', NULL,
   'Migration error on v2.5.0 rollout to Ninety One AM', 'FAILURE', NOW() - INTERVAL '4 hours'),
  ('system',      'system@zgate.io',     'HEALTH_ALERT',       'Organization','b0000004-0000-0000-0000-000000000004',
   'Ninety One AM staging environment offline after failed deployment', 'FAILURE', NOW() - INTERVAL '3 hours 30 minutes'),
  ('sarah.chen',  'sarah.chen@zgate.io', 'LICENSE_RENEWED',    'License',    NULL,
   'Renewed expiring licenses for Apex Capital (10 days remaining)', 'SUCCESS', NOW() - INTERVAL '1 day'),
  ('marcus.obi',  'marcus.obi@zgate.io', 'USER_CREATED',       'NexusUser',  'd0000003-0000-0000-0000-000000000003',
   'Created support user priya.naidoo@zgate.io', 'SUCCESS', NOW() - INTERVAL '5 days');
