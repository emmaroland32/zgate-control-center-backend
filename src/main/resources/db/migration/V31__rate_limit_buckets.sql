-- Rate-limit counters, shared across replicas.
--
-- The main product's RateLimitInterceptor keeps these in a ConcurrentHashMap and says so in its
-- own class comment: single-instance only. Per-replica buckets are worse than none here, because
-- the limit silently multiplies by the replica count and an attacker just reconnects until the
-- load balancer gives them a fresh one.
--
-- Fixed window rather than a sliding log: one atomic UPSERT per request instead of a
-- delete-count-insert, which matters because this sits in front of sign-in. The known trade-off is
-- that a burst straddling a window boundary can reach 2x the limit briefly; the account lockout in
-- control_center_users is the actual brute-force defence, and this is the coarse tap in front of it.
CREATE TABLE IF NOT EXISTS rate_limit_buckets (
    bucket_key   VARCHAR(400) NOT NULL,
    window_start TIMESTAMP    NOT NULL,
    hits         INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_key, window_start)
);

-- Supports the periodic sweep of elapsed windows.
CREATE INDEX IF NOT EXISTS ix_rate_limit_buckets_window
    ON rate_limit_buckets (window_start);
