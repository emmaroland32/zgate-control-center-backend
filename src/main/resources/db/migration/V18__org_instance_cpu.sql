-- Process CPU load (%) reported by each node on its heartbeat, completing the real runtime metrics.
ALTER TABLE org_instances ADD COLUMN IF NOT EXISTS cpu_pct INTEGER;
