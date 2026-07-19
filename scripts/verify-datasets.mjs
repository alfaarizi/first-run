#!/usr/bin/env node
// Verifies every dataset pinned in evals/baselines.json. The SHA-256 and row
// count must match the pin, and each row must pass its dataset's schema. An
// unpinned dataset could drift under the metrics measured on it, the risk Bazel
// closes by requiring sha256 on remote archives. `make eval` runs this in CI.

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'

import { BASELINES, resolveDatasetUrl } from './lib/baselines.mjs'
import { NOT_STUCK, STUCK } from './lib/labels.mjs'
import { MILESTONES } from './lib/milestones.mjs'
import { parseRfc3339Utc } from './lib/time.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const EVENT_NAME = /^(fr\.[a-z][a-z0-9_]*|[a-z][a-z0-9_]*)$/

// A session closes after 30 idle minutes, so a longer gap means two sessions.
const IDLE_LIMIT_MS = 30 * 60_000

// Per-event caps the gateway enforces, mirrored from the ingest contract.
const EVENT_NAME_LIMIT = 64
const END_USER_HASH_LIMIT = 128
const PROPERTIES_LIMIT = 20

// Validator factories by dataset name. A pin without one fails, so every
// dataset that gates CI is schema-checked, never only hashed.
const VALIDATORS = { sessions: createSessionValidator, qa: createGoldenQuestionValidator }

const failures = Object.entries(BASELINES.datasets ?? {}).flatMap(verifyDataset)

if (failures.length > 0) {
  for (const failure of failures) console.error(`verify-datasets: ${failure}`)
  process.exitCode = 1
} else {
  console.log('>> datasets verified against evals/baselines.json')
}

/** Verifies one pinned dataset, returning every hash, count, and schema failure. */
function verifyDataset([name, pin]) {
  const raw = readFileSync(resolveDatasetUrl(pin), 'utf8')
  const failures = []

  const sha256 = createHash('sha256').update(raw).digest('hex')
  if (sha256 !== pin.sha256) failures.push(`${name}: sha256 ${sha256} does not match the pin ${pin.sha256}`)

  const rows = raw.split('\n').filter((line) => line.length > 0)
  const pinnedRows = pin.sessions ?? pin.questions
  if (rows.length !== pinnedRows) failures.push(`${name}: ${rows.length} rows, pin says ${pinnedRows}`)

  const createValidator = VALIDATORS[name]
  if (!createValidator) {
    failures.push(`${name}: no row validator in VALIDATORS`)
    return failures
  }

  const validate = createValidator()
  rows.forEach((line, at) => {
    for (const problem of validate(line)) failures.push(`${name} line ${at + 1}: ${problem}`)
  })
  return failures
}

/** Returns a validator that checks one labeled-session row per call. */
function createSessionValidator() {
  const seenSessionIds = new Set()
  const seenEventIds = new Set()
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
    if (!matches(row.session_id, UUID)) problems.push('session_id is not a UUID')
    if (seenSessionIds.has(row.session_id)) problems.push('session_id duplicates an earlier row')

    seenSessionIds.add(row.session_id)

    if (!MILESTONES.includes(row.milestone)) {
      problems.push(`milestone ${row.milestone} is not in the catalog`)
    }
    if (row.label !== STUCK && row.label !== NOT_STUCK) problems.push(`label is ${row.label}`)
    if (!Array.isArray(row.events) || row.events.length === 0) {
      problems.push('events is empty')
      return problems
    }

    const firstHash = row.events[0].end_user_hash

    // Instants, not strings, because string order breaks across the mixed
    // fractional-second precision parseRfc3339Utc admits (RFC 3339, 5.1).
    let previousMs = -Infinity
    for (const [at, event] of row.events.entries()) {
      const where = `event ${at + 1}`
      const eventKeys = Object.keys(event).sort().join(',')

      if (eventKeys !== 'end_user_hash,event,id,properties,session_id,timestamp') {
        problems.push(`${where}: keys are [${eventKeys}], not the ingest event shape`)
        continue
      }
      if (!matches(event.id, UUID)) problems.push(`${where}: id is not a UUID`)
      if (seenEventIds.has(event.id)) problems.push(`${where}: id duplicates an earlier event`)

      seenEventIds.add(event.id)

      if (!matches(event.event, EVENT_NAME) || event.event.length > EVENT_NAME_LIMIT) {
        problems.push(`${where}: name ${event.event} is invalid`)
      }
      if (event.session_id !== row.session_id) problems.push(`${where}: session_id differs from the row`)

      const hash = event.end_user_hash

      if (typeof hash !== 'string' || hash.length === 0 || hash.length > END_USER_HASH_LIMIT) {
        problems.push(`${where}: end_user_hash is not a string of 1 to ${END_USER_HASH_LIMIT} chars`)
      }
      if (hash !== firstHash) problems.push(`${where}: end_user_hash differs within the session`)

      const timestampMs = parseRfc3339Utc(event.timestamp)

      if (Number.isNaN(timestampMs)) problems.push(`${where}: timestamp is not RFC 3339 UTC`)
      if (timestampMs < previousMs) problems.push(`${where}: timestamps go backward`)
      if (at > 0 && timestampMs - previousMs > IDLE_LIMIT_MS) {
        problems.push(`${where}: a gap over 30 minutes splits the session`)
      }

      previousMs = timestampMs

      if (!isScalarMap(event.properties)) {
        problems.push(`${where}: properties is not an object of at most ${PROPERTIES_LIMIT} scalars`)
      }
    }

    if (row.label === STUCK) {
      if (!row.events.some((event) => event.timestamp === row.stuck_at_ts)) {
        problems.push("stuck_at_ts does not match any event's timestamp")
      }
    } else if (row.stuck_at_ts !== null) {
      problems.push('not_stuck with a stuck_at_ts')
    }
    return problems
  }
}

/** Accepts a string matching the pattern, a bare regex test coerces arrays. */
function matches(value, pattern) {
  return typeof value === 'string' && pattern.test(value)
}

/** Accepts a plain object within the property limit, all values scalar. */
function isScalarMap(value) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false
  const entries = Object.values(value)
  return (
    entries.length <= PROPERTIES_LIMIT &&
    entries.every((entry) => ['string', 'number', 'boolean'].includes(typeof entry))
  )
}

/** Returns a validator that checks one golden-question row per call. */
function createGoldenQuestionValidator() {
  const pin = BASELINES.datasets.qa
  let at = 0
  let unanswerable = 0
  return (line) => {
    at += 1
    let row
    try {
      row = JSON.parse(line)
    } catch {
      return ['not valid JSON']
    }

    const problems = []
    const keys = Object.keys(row).sort().join(',')

    if (keys !== 'answerable,id,question,reference_answer,source_urls') {
      return [`keys are [${keys}], not the golden-question shape`]
    }
    // Dense ordered ids, so growth appends and diffs stay reviewable.
    if (row.id !== `qa-${String(at).padStart(3, '0')}`) problems.push(`id ${row.id} breaks the qa-NNN order`)
    if (typeof row.question !== 'string' || row.question.length === 0) problems.push('question is empty')
    if (typeof row.reference_answer !== 'string' || row.reference_answer.length === 0) {
      problems.push('reference_answer is empty')
    }
    if (typeof row.answerable !== 'boolean') problems.push('answerable is not a boolean')
    if (!Array.isArray(row.source_urls) || !row.source_urls.every((url) => /^https?:\/\//.test(url))) {
      problems.push('source_urls is not a list of http(s) URLs')
    } else if (row.answerable !== row.source_urls.length > 0) {
      problems.push('answerable must match whether source_urls is non-empty')
    }

    if (!row.answerable) unanswerable += 1
    if (at === pin.questions && unanswerable !== pin.unanswerable) {
      problems.push(`${unanswerable} unanswerable rows, pin says ${pin.unanswerable}`)
    }
    return problems
  }
}
