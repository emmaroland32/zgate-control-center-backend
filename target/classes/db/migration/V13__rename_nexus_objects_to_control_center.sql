-- ZGATE Control Center — physical rename of legacy "nexus" database objects.
--
-- Additive migration (V1–V12 are shipped and left untouched, so their Flyway
-- checksums and filenames stay valid — prod validate-on-migrate still passes).
-- This renames the two remaining nexus-named tables to match the renamed JPA
-- entities (ControlCenterUser -> control_center_users, ControlCenterConfig ->
-- control_center_config) so ddl-auto=validate succeeds at boot, and re-keys the
-- runtime key/value config store off the legacy nexus.* namespace.
--
-- NOTE: the standalone database itself (zgate_nexus) cannot be renamed from
-- inside a migration — Flyway is connected to it. The operator must run, once,
-- out of band:   ALTER DATABASE zgate_nexus RENAME TO zgate_control_center;
-- (DB_NAME / spring.datasource.url already point at zgate_control_center.)

-- 1. Rename tables. Postgres carries indexes, PK/unique constraints and owned
--    sequences across a RENAME automatically, so no index rebuild is needed.
ALTER TABLE nexus_users  RENAME TO control_center_users;
ALTER TABLE nexus_config RENAME TO control_center_config;

-- 2. Re-key the runtime config store: nexus.<rest> -> controlcenter.<rest>.
--    'nexus.' is 6 chars, so substring(... from 7) keeps everything after the dot.
UPDATE control_center_config
   SET config_key = 'controlcenter.' || substring(config_key FROM 7)
 WHERE config_key LIKE 'nexus.%';

-- 3. Rebrand the stored application display name.
UPDATE control_center_config
   SET value = 'ZGATE Control Center'
 WHERE config_key = 'controlcenter.app.name'
   AND value = 'ZGATE Nexus';
