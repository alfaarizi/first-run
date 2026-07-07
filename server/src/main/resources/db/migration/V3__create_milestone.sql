-- Milestone names double as completion event names, validated in Java against
-- the ingest grammar. Foreign-key checks bypass row-level security, so the
-- composite key pins every app reference inside one tenant.

ALTER TABLE app ADD CONSTRAINT app_id_tenant_id_key UNIQUE (id, tenant_id);

CREATE TABLE milestone (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    app_id uuid NOT NULL,
    name text NOT NULL,
    title text NOT NULL,
    position integer NOT NULL CHECK (position >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT milestone_app_fkey
        FOREIGN KEY (app_id, tenant_id) REFERENCES app (id, tenant_id),
    CONSTRAINT milestone_app_id_name_key UNIQUE (app_id, name),
    CONSTRAINT milestone_app_id_position_key UNIQUE (app_id, position)
);

ALTER TABLE milestone ENABLE ROW LEVEL SECURITY;
ALTER TABLE milestone FORCE ROW LEVEL SECURITY;
CREATE POLICY milestone_tenant_isolation ON milestone
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
