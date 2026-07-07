-- Demo tenant and Tasklet app for local development. `make seed` pipes this
-- through psql in the postgres container. Idempotent, safe to rerun.
--
-- sdk_key and hmac_key come from .env via psql -v, the same values the tasklet
-- widget signs with, so the stored and signing keys never drift. IDs are UUIDv7,
-- fixed so reseeding never forks the demo data.

INSERT INTO tenant (id, name)
VALUES ('019813f2-0000-7000-8000-000000000001', 'Tasklet Inc')
ON CONFLICT (id) DO NOTHING;

-- DO UPDATE so a reseed converges an existing volume on the canonical demo
-- config, such as the allowlist for the task_count property Tasklet sends.
-- path rides fr.page_view and feeds the session backtracking feature.
INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key, allowed_origins, allowed_properties)
VALUES (
  '019813f2-0000-7000-8000-000000000002',
  '019813f2-0000-7000-8000-000000000001',
  'Tasklet',
  :'sdk_key',
  :'hmac_key',
  '{http://localhost:5174}',
  '{task_count,path}'
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  sdk_key = EXCLUDED.sdk_key,
  hmac_key = EXCLUDED.hmac_key,
  allowed_origins = EXCLUDED.allowed_origins,
  allowed_properties = EXCLUDED.allowed_properties;
