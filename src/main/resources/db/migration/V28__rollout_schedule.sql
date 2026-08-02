-- ============================================================================
-- Scheduled rollouts + the deployments screen's "schedule for later"
-- ============================================================================
-- The console let an operator schedule an update; DeploymentService stored the
-- timestamp and NOTHING ever read it, so the row sat PENDING forever and no
-- update was ever applied. Push-update now creates a real fleet rollout, and a
-- rollout can carry a start time the orchestrator honours.
-- ============================================================================

ALTER TABLE fleet_rollouts ADD COLUMN scheduled_for TIMESTAMP;

-- Webhook delivery success counter. The console rendered a successRate the backend never produced,
-- which threw a TypeError as soon as one webhook existed; it is now derived from these two counts.
ALTER TABLE webhooks ADD COLUMN success_count BIGINT NOT NULL DEFAULT 0;
