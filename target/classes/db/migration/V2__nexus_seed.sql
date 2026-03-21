-- ZGATE Nexus Control Center — Seed Data

-- ============================================================
-- DEFAULT SUPER ADMIN
-- Password: Admin@123 (bcrypt)
-- ============================================================
INSERT INTO nexus_users (id, name, email, password_hash, role, active)
VALUES (
    gen_random_uuid(),
    'ZGATE Administrator',
    'admin@zgate.com',
    '$2b$10$KjHJpT53N1iTFymVsLhkv.MRLLYKGbR4SF0eRHzaJGZ9ASrxjW2V.',
    'SUPER_ADMIN',
    TRUE
);

-- ============================================================
-- SHARED SERVICES CATALOG
-- ============================================================

-- IDENTITY VERIFICATION
INSERT INTO shared_services (name, code, category, description, provider, price_per_call, currency)
VALUES
('NIN Validation',          'NIN_VALIDATION',       'IDENTITY',   'Nigerian National Identification Number verification against NIMC database',    'NIMC',           0.0500, 'USD'),
('BVN Validation',          'BVN_VALIDATION',       'IDENTITY',   'Bank Verification Number validation against CBN/NIBSS registry',                'NIBSS',          0.0500, 'USD'),
('RSA ID Verification',     'RSA_ID_VERIFICATION',  'IDENTITY',   'South African national ID number verification against Home Affairs DHA-HANIS',  'DHA',            0.0800, 'USD'),
('Passport Verification',   'PASSPORT_VERIFICATION','IDENTITY',   'Passport authenticity check against ICAO-compliant national databases',         'INTERPOL',       0.1000, 'USD'),
('Driver''s License Check', 'DRIVERS_LICENSE',      'IDENTITY',   'Driver''s license validation against national transport authority records',     'NationalTA',     0.0600, 'USD'),
('Ghana Card Validation',   'GHANA_CARD',           'IDENTITY',   'Ghana National Identification Authority card validation',                        'NIA Ghana',      0.0500, 'USD');

-- SANCTIONS SCREENING
INSERT INTO shared_services (name, code, category, description, provider, price_per_call, currency)
VALUES
('OFAC Sanctions Check',    'OFAC_SCREENING',       'SANCTIONS',  'US Treasury Office of Foreign Assets Control consolidated sanctions list check', 'US Treasury',    0.0300, 'USD'),
('UN Sanctions Screening',  'UN_SANCTIONS',         'SANCTIONS',  'United Nations consolidated sanctions list screening',                            'UN DESA',        0.0300, 'USD'),
('EU Sanctions Screening',  'EU_SANCTIONS',         'SANCTIONS',  'European Union financial sanctions list screening',                               'EU FISMA',       0.0300, 'USD'),
('UK HMT Sanctions',        'UK_HMT_SANCTIONS',     'SANCTIONS',  'UK His Majesty''s Treasury financial sanctions list check',                      'UK HMT',         0.0300, 'USD'),
('PEP Screening',           'PEP_SCREENING',        'SANCTIONS',  'Politically Exposed Persons database screening (global coverage)',                'WorldCheck',     0.0500, 'USD'),
('Adverse Media Check',     'ADVERSE_MEDIA',        'SANCTIONS',  'Automated adverse media and negative news screening',                             'RDC',            0.0800, 'USD');

-- KYC / AML
INSERT INTO shared_services (name, code, category, description, provider, price_per_call, currency)
VALUES
('AML Risk Score',          'AML_RISK_SCORE',       'KYC',        'Anti-money laundering behavioral risk scoring using transaction patterns',        'NICE Actimize',  0.1500, 'USD'),
('Document Verification',   'DOCUMENT_VERIFY',      'KYC',        'AI-powered ID document authenticity and MRZ verification',                       'Onfido',         0.2000, 'USD'),
('Face Match / Liveness',   'FACE_LIVENESS',        'KYC',        'Biometric face matching and liveness detection against ID document photo',       'Onfido',         0.2500, 'USD'),
('Address Verification',    'ADDRESS_VERIFY',       'KYC',        'Residential address verification against utility and postal records',              'Loqate',         0.1000, 'USD');

-- CREDIT BUREAU
INSERT INTO shared_services (name, code, category, description, provider, price_per_call, currency)
VALUES
('CRC Credit Bureau',       'CRC_CREDIT',           'CREDIT',     'Nigerian Credit Risk Check (CRC) credit report lookup',                           'CRC Bureau',     0.3000, 'USD'),
('TransUnion Credit',       'TRANSUNION_CREDIT',     'CREDIT',     'TransUnion Africa credit score and report retrieval',                             'TransUnion',     0.3000, 'USD'),
('Experian Credit',         'EXPERIAN_CREDIT',       'CREDIT',     'Experian credit score and bureau data lookup',                                    'Experian',       0.3000, 'USD'),
('XDS Credit Check',        'XDS_CREDIT',            'CREDIT',     'XDS (South Africa) credit bureau search and report',                              'XDS',            0.2500, 'USD');

-- COMMUNICATION
INSERT INTO shared_services (name, code, category, description, provider, price_per_call, currency)
VALUES
('SMS OTP',                 'SMS_OTP',              'COMMUNICATION', 'One-time password delivery via SMS',                                           'Twilio',         0.0200, 'USD'),
('Email Verification',      'EMAIL_VERIFY',         'COMMUNICATION', 'Email address deliverability and domain reputation check',                     'ZeroBounce',     0.0100, 'USD'),
('Phone Validation',        'PHONE_VALIDATE',       'COMMUNICATION', 'Phone number format, carrier, and line type validation',                       'Numverify',      0.0050, 'USD');

-- ============================================================
-- DEFAULT ALERT RULES
-- ============================================================
INSERT INTO alert_rules (name, description, severity, metric, operator, threshold, evaluation_window_minutes, cooldown_minutes, channels, org_scope, enabled)
VALUES
('High Response Time',      'Backend API response time exceeds threshold',     'HIGH',     'response_time_ms',  '>',  2000,  5,  30, '["EMAIL","SLACK"]', 'ALL', TRUE),
('Critical Response Time',  'Backend API response extremely slow',             'CRITICAL', 'response_time_ms',  '>',  5000,  2,  15, '["EMAIL","SLACK","PAGERDUTY"]', 'ALL', TRUE),
('Low Uptime',              'Instance uptime falls below 99%',                 'HIGH',     'uptime_percent',    '<',  99.0,  10, 60, '["EMAIL","SLACK"]', 'ALL', TRUE),
('Disk Space Critical',     'Disk usage above 85%',                            'CRITICAL', 'disk_percent',      '>',  85.0,  5,  30, '["EMAIL","SLACK","PAGERDUTY"]', 'ALL', TRUE),
('License Expiring Soon',   'Module license expires within 30 days',           'MEDIUM',   'license_days_left', '<',  30.0,  1440, 86400, '["EMAIL"]', 'ALL', TRUE),
('License Expired',         'Module license has expired',                      'HIGH',     'license_days_left', '<=', 0.0,  60,  3600, '["EMAIL","SLACK"]', 'ALL', TRUE),
('Deployment Failed',       'Deployment status changed to FAILED',             'HIGH',     'deployment_failed', '=',  1.0,  1,  60, '["EMAIL","SLACK"]', 'ALL', TRUE),
('Service Offline',         'ZGATE instance has not reported in 15 minutes',   'CRITICAL', 'last_seen_minutes', '>',  15.0, 5,  30, '["EMAIL","SLACK","PAGERDUTY"]', 'ALL', TRUE);
