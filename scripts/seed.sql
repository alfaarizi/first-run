-- Demo tenant and Tasklet app for local development. `make seed` pipes this
-- through psql in the postgres container. Idempotent, safe to rerun.
--
-- The credentials match the tasklet service in compose.yaml. IDs are UUIDv7,
-- fixed so reseeding never forks the demo data.

INSERT INTO tenant (id, name)
VALUES ('019813f2-0000-7000-8000-000000000001', 'Tasklet Inc')
ON CONFLICT (id) DO NOTHING;

INSERT INTO app (id, tenant_id, name, sdk_key, hmac_key, allowed_origins)
VALUES (
  '019813f2-0000-7000-8000-000000000002',
  '019813f2-0000-7000-8000-000000000001',
  'Tasklet',
  'fr_pk_demo_tasklet',
  'fr_sk_demo_tasklet',
  '{http://localhost:5174}'
)
ON CONFLICT (id) DO NOTHING;
