-- Real runtime metrics reported by each node on its telemetry heartbeat (JVM heap + uptime), so the
-- infrastructure/health views show measured values instead of fabricated ones. Additive.

ALTER TABLE org_instances ADD COLUMN IF NOT EXISTS mem_used_mb     INTEGER;
ALTER TABLE org_instances ADD COLUMN IF NOT EXISTS mem_max_mb      INTEGER;
ALTER TABLE org_instances ADD COLUMN IF NOT EXISTS uptime_seconds  BIGINT;
