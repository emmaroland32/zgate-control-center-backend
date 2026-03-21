CREATE TABLE config_snapshots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id VARCHAR(255),
    taken_by        VARCHAR(255) NOT NULL,
    note            VARCHAR(500),
    entry_count     INT NOT NULL DEFAULT 0,
    snapshot_data   TEXT NOT NULL,
    taken_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_snapshots_taken_at ON config_snapshots(taken_at DESC);
