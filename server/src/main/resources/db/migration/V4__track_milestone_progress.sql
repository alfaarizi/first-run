-- PENDING is the absence of a milestone_progress row. Materializing it would
-- write one row per user per milestone at first sight and backfill every user
-- whenever a founder defines a milestone. Foreign-key checks bypass row-level
-- security, so composite keys pin every reference inside one tenant.

ALTER TABLE milestone ADD CONSTRAINT milestone_id_tenant_id_key UNIQUE (id, tenant_id);

CREATE TABLE end_user (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    app_id uuid NOT NULL,
    external_hash text NOT NULL,
    first_seen_at timestamptz NOT NULL,
    CONSTRAINT end_user_app_fkey
        FOREIGN KEY (app_id, tenant_id) REFERENCES app (id, tenant_id),
    CONSTRAINT end_user_app_id_external_hash_key UNIQUE (app_id, external_hash),
    CONSTRAINT end_user_id_tenant_id_key UNIQUE (id, tenant_id)
);

CREATE TABLE milestone_progress (
    tenant_id uuid NOT NULL,
    end_user_id uuid NOT NULL,
    milestone_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    PRIMARY KEY (end_user_id, milestone_id),
    CONSTRAINT milestone_progress_end_user_fkey
        FOREIGN KEY (end_user_id, tenant_id) REFERENCES end_user (id, tenant_id),
    CONSTRAINT milestone_progress_milestone_fkey
        FOREIGN KEY (milestone_id, tenant_id) REFERENCES milestone (id, tenant_id),
    CONSTRAINT milestone_progress_completed_at_check
        CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL))
);

ALTER TABLE end_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE end_user FORCE ROW LEVEL SECURITY;
CREATE POLICY end_user_tenant_isolation ON end_user
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE milestone_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE milestone_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY milestone_progress_tenant_isolation ON milestone_progress
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
