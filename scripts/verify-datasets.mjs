#!/usr/bin/env node
// Verifies every dataset pinned in evals/baselines.json: the SHA-256 and row
// count match the pin, and each row passes its dataset's schema. An unpinned
// dataset could drift under the metrics measured on it, the risk Bazel closes
// by requiring sha256 on remote archives. `make eval` runs this in CI.

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

import { MILESTONES } from './lib/milestones.mjs'

const EVALS_DIR = 'evals'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const EVENT_NAME = /^(fr\.[a-z][a-z0-9_]*|[a-z][a-z0-9_]*)$/

// A session closes after 30 idle minutes, so a longer gap means two sessions.
const IDLE_LIMIT_MS = 30 * 60_000

// Validator factories by dataset name. A pin without one fails, so every
// dataset that gates CI is schema-checked, never only hashed. Each dataset
// gets a fresh validator, so cross-row state like seen IDs never leaks.
const VALIDATORS = { sessions: createSessionValidator }

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
  const createValidator = VALIDATORS[name]
  if (!createValidator) {
    failures.push(`${name}: no row validator in VALIDATORS`)
    continue
  }
  const validate = createValidator()
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

/** Returns a validator that checks one labeled-session row per call. */
function createSessionValidator() {
  const seenIds = new Set()
  return (line) => {
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
    if (seenIds.has(row.session_id)) problems.push('session_id duplicates an earlier row')
    seenIds.add(row.session_id)
    if (!MILESTONES.includes(row.milestone)) {
      problems.push(`milestone ${row.milestone} is not in the catalog`)
    }
    if (row.label !== 'stuck' && row.label !== 'not_stuck') problems.push(`label is ${row.label}`)
    if (!Array.isArray(row.events) || row.events.length === 0) {
      problems.push('events is empty')
      return problems
    }

    // Timestamps compare as parsed instants: string order breaks across the
    // mixed fractional-second precision rfc3339Utc admits (RFC 3339, 5.1).
    let previousMs = -Infinity
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
      const timestampMs = Date.parse(event.timestamp)
      if (timestampMs < previousMs) problems.push(`${where}: timestamps go backward`)
      if (at > 0 && timestampMs - previousMs > IDLE_LIMIT_MS) {
        problems.push(`${where}: a gap over 30 minutes splits the session`)
      }
      previousMs = timestampMs
      if (!isScalarMap(event.properties)) problems.push(`${where}: properties is not an object of scalars`)
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
}

/** Accepts a plain object whose values are all scalars, the ingest shape. */
function isScalarMap(value) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  return Object.values(value).every((entry) => ['string', 'number', 'boolean'].includes(typeof entry))
}

/** Accepts an RFC 3339 UTC timestamp, the only wire form timestamps take. */
function rfc3339Utc(value) {
  return typeof value === 'string' && value.endsWith('Z') && Number.isFinite(Date.parse(value))
}
