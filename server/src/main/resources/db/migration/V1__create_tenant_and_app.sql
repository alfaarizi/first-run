-- Tenant is the isolation root and app holds the SDK key and allowlist, so
-- both get row-level security in their first migration. FORCE applies the
-- policy to the table owner too, since the server connects as the
-- migration-owning role, and NULLIF makes an unset app.tenant_id match no
-- rows instead of leaking every row.

CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    name text NOT NULL,
    sdk_key text NOT NULL UNIQUE,
    hmac_key text NOT NULL,
    allowed_origins text[] NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE app ENABLE ROW LEVEL SECURITY;
ALTER TABLE app FORCE ROW LEVEL SECURITY;
CREATE POLICY app_tenant_isolation ON app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
