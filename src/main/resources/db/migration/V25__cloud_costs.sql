-- ============================================================================
-- Per-customer cloud cost ingestion (vendor-hosted stacks)
-- ============================================================================
-- One row per (org, month): what that customer's infrastructure actually cost,
-- pulled from AWS Cost Explorer grouped by the zgate:org-id tag every stack
-- already applies. Revenue is already known (subscription fee + usage
-- invoices), so this is the missing half of margin-per-customer.
-- ============================================================================

CREATE TABLE org_cloud_costs (
    id              UUID PRIMARY KEY,
    organization_id UUID          NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- First day of the month the amount covers.
    month           DATE          NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    currency        VARCHAR(8)    NOT NULL DEFAULT 'USD',
    -- Where the number came from (AWS_CE today; other providers later).
    source          VARCHAR(16)   NOT NULL DEFAULT 'AWS_CE',
    fetched_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_org_cloud_cost UNIQUE (organization_id, month, source)
);

CREATE INDEX idx_org_cloud_costs_month ON org_cloud_costs(month);
