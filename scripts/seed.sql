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

-- The activation milestones Tasklet's own events complete, so the funnel
-- moves end to end out of the box. created_at is backdated because completion
-- matching binds to it, and scripts/seed-traffic.mjs replays journeys weeks old.
INSERT INTO milestone (id, tenant_id, app_id, name, title, position, created_at)
VALUES
  (
    '019813f2-0000-7000-8000-000000000003',
    '019813f2-0000-7000-8000-000000000001',
    '019813f2-0000-7000-8000-000000000002',
    'task_created',
    'Create your first task',
    1,
    '2026-04-01T00:00:00Z'
  ),
  (
    '019813f2-0000-7000-8000-000000000004',
    '019813f2-0000-7000-8000-000000000001',
    '019813f2-0000-7000-8000-000000000002',
    'task_completed',
    'Complete a task',
    2,
    '2026-04-01T00:00:00Z'
  ),
  (
    '019813f2-0000-7000-8000-000000000005',
    '019813f2-0000-7000-8000-000000000001',
    '019813f2-0000-7000-8000-000000000002',
    'completed_tasks_cleared',
    'Clear completed tasks',
    3,
    '2026-04-01T00:00:00Z'
  )
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  title = EXCLUDED.title,
  position = EXCLUDED.position,
  created_at = EXCLUDED.created_at;
