import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { DEMO_APP_ID, DEMO_TENANT_ID } from './lib/demo.mjs'

// No import crosses into SQL, so the seed and the catalog are held together
// here. Drifting ids would leave every harness querying rows that never seeded.
const SEED = readFileSync(new URL('seed.sql', import.meta.url), 'utf8')

test('the seed inserts the demo tenant and app the harnesses address', () => {
  assert.ok(SEED.includes(DEMO_TENANT_ID), 'seed.sql is missing the demo tenant id')
  assert.ok(SEED.includes(DEMO_APP_ID), 'seed.sql is missing the demo app id')
})
