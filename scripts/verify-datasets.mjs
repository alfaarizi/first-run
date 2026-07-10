#!/usr/bin/env node
// Verifies every dataset pinned in evals/baselines.json: the SHA-256 and row
// count match the pin, and each row passes its dataset's schema. An unpinned
// dataset could drift under the metrics measured on it, the risk Bazel closes
// by requiring sha256 on remote archives. `make eval` runs this in CI.

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const EVALS_DIR = 'evals'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const EVENT_NAME = /^(fr\.[a-z][a-z0-9_]*|[a-z][a-z0-9_]*)$/
const MILESTONE_NAME = /^[a-z][a-z0-9_]*$/

// A session closes after 30 idle minutes, so a longer gap means two sessions.
const IDLE_LIMIT_MS = 30 * 60_000

// Row validators by dataset name. A pin without one fails, so every dataset
// that gates CI is schema-checked, never only hashed.
const VALIDATORS = { sessions: validateSession }

const baselines = JSON.parse(readFileSync(join(EVALS_DIR, 'baselines.json'), 'utf8'))
const failures = []

for (const [name, pin] of Object.entries(baselines.datasets ?? {})) {
  const raw = readFileSync(join(EVALS_DIR, pin.path), 'utf8')
  const sha256 = createHash('sha256').update(raw).digest('hex')
  if (sha256 !== pin.sha256) {
    failures.push(`${name}: sha256 ${sha256} does not match the pin ${pin.sha256}`)
  }
  const rows = raw.split('\n').filter((line) => line.length > 0)
  if (rows.length !== pin.sessions) {
    failures.push(`${name}: ${rows.length} rows, pin says ${pin.sessions}`)
  }
  const validate = VALIDATORS[name]
  if (!validate) {
    failures.push(`${name}: no row validator in VALIDATORS`)
    continue
  }
  rows.forEach((line, at) => {
    for (const problem of validate(line)) {
      failures.push(`${name} line ${at + 1}: ${problem}`)
    }
  })
}

if (failures.length > 0) {
  for (const failure of failures) console.error(`verify-datasets: ${failure}`)
  process.exit(1)
}
console.log(`>> datasets verified against ${join(EVALS_DIR, 'baselines.json')}`)

/** Validates one labeled-session row and returns every problem found. */
function validateSession(line) {
  let row
  try {
    row = JSON.parse(line)
  } catch {
    return ['not valid JSON']
  }
  const problems = []
  const keys = Object.keys(row).sort().join(',')
  if (keys !== 'events,label,milestone,session_id,stuck_at_ts') {
    return [`keys are [${keys}], not the labeled-session shape`]
  }
  if (!UUID.test(row.session_id)) problems.push('session_id is not a UUID')
  if (!MILESTONE_NAME.test(row.milestone ?? '')) problems.push('milestone is not snake_case')
  if (row.label !== 'stuck' && row.label !== 'not_stuck') problems.push(`label is ${row.label}`)
  if (!Array.isArray(row.events) || row.events.length === 0) {
    problems.push('events is empty')
    return problems
  }

  let previousTs = ''
  for (const [at, event] of row.events.entries()) {
    const where = `event ${at + 1}`
    const eventKeys = Object.keys(event).sort().join(',')
    if (eventKeys !== 'end_user_hash,event,id,properties,session_id,timestamp') {
      problems.push(`${where}: keys are [${eventKeys}], not the ingest event shape`)
      continue
    }
    if (!UUID.test(event.id)) problems.push(`${where}: id is not a UUID`)
    if (!EVENT_NAME.test(event.event)) problems.push(`${where}: name ${event.event} is invalid`)
    if (event.session_id !== row.session_id) problems.push(`${where}: session_id differs from the row`)
    if (!rfc3339Utc(event.timestamp)) problems.push(`${where}: timestamp is not RFC 3339 UTC`)
    if (event.timestamp < previousTs) problems.push(`${where}: timestamps go backward`)
    if (at > 0 && Date.parse(event.timestamp) - Date.parse(previousTs) > IDLE_LIMIT_MS) {
      problems.push(`${where}: a gap over 30 minutes splits the session`)
    }
    previousTs = event.timestamp
    const scalar = Object.values(event.properties ?? {}).every(
      (value) => ['string', 'number', 'boolean'].includes(typeof value),
    )
    if (!scalar) problems.push(`${where}: properties must hold scalars`)
  }

  if (row.label === 'stuck') {
    if (!row.events.some((event) => event.timestamp === row.stuck_at_ts)) {
      problems.push("stuck_at_ts does not match any event's timestamp")
    }
  } else if (row.stuck_at_ts !== null) {
    problems.push('not_stuck with a stuck_at_ts')
  }
  return problems
}

/** Accepts an RFC 3339 UTC timestamp, the only wire form timestamps take. */
function rfc3339Utc(value) {
  return typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
}
