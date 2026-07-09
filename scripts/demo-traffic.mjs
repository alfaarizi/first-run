#!/usr/bin/env node
// Replays probabilistic Tasklet journeys with per-step drop-off through the
// real ingest path (PostHog's demo-environment pattern). Signing and batch
// shape follow api/openapi/ingest.yaml. End-user hashes are deterministic, so a
// re-run hits the same users and the pipeline's monotone per-user writes absorb
// the copies.

import { createHmac, randomBytes } from 'node:crypto'

const SERVER = process.env.FIRSTRUN_SERVER_URL

// The gateway gates on Origin, so the replay presents Tasklet's.
const ORIGIN = 'http://localhost:5174'
const SDK_KEY = process.env.VITE_FIRSTRUN_KEY
const HMAC_KEY = process.env.VITE_FIRSTRUN_HMAC_KEY
if (!SERVER || !SDK_KEY || !HMAC_KEY) {
  console.error('demo-traffic: missing env, run through `make seed`')
  process.exit(1)
}

const USERS = 60

// The ingest contract caps a batch at 50 events.
const BATCH_MAX = 50
const MINUTE_MS = 60_000
const HOUR_MS = 3_600_000
const DAY_MS = 86_400_000

const events = []
for (let user = 0; user < USERS; user++) {
  events.push(...journey(user))
}

// Sorting by time keeps each user's events in order, which is all the
// pipeline's per-user partitioning relies on.
events.sort((a, b) => a.timestamp.localeCompare(b.timestamp))

for (let at = 0; at < events.length; at += BATCH_MAX) {
  await post(events.slice(at, at + BATCH_MAX))
}

/**
 * Builds one user's event trail, deterministic in the user index but for the
 * "now" anchor. Each step drops some users, and a few complete a task first
 * because any-order funnels are legitimate.
 */
function journey(user) {
  const rng = mulberry32(user + 1)
  const hash = `demo_user_${String(user).padStart(3, '0')}`
  let at = Date.now() - (5 + rng() * 70) * DAY_MS
  const sessionId = uuidv7(at)
  const trail = []
  const add = (event, properties = {}) =>
    trail.push({
      id: uuidv7(at),
      event,
      end_user_hash: hash,
      session_id: sessionId,
      timestamp: new Date(at).toISOString(),
      properties,
    })

  if (rng() < 0.05) {
    // Completes a task with no prior creation, so the created step stays pending.
    add('task_completed', { task_count: 1 + Math.floor(rng() * 5) })
    return trail
  }

  add('task_list_viewed')
  if (rng() >= 0.85) return trail

  at += (1 + rng() * 120) * MINUTE_MS
  add('task_created', { task_count: 1 + Math.floor(rng() * 8) })
  if (rng() >= 0.75) return trail

  // A later visit opens the next step, and completing stays a separate act.
  at += (1 + rng() * 40) * HOUR_MS
  add('task_list_viewed')
  if (rng() >= 0.65) return trail

  at += (1 + rng() * 60) * HOUR_MS
  add('task_completed', { task_count: 1 + Math.floor(rng() * 8) })
  if (rng() >= 0.7) return trail

  at += (10 + rng() * 300) * MINUTE_MS
  add('task_list_viewed')
  if (rng() >= 0.55) return trail

  at += (5 + rng() * 600) * MINUTE_MS
  add('completed_tasks_cleared', { task_count: Math.floor(rng() * 4) })
  return trail
}

async function post(batch) {
  const sentAt = new Date().toISOString()
  const body = JSON.stringify({ sent_at: sentAt, events: batch })
  const signature = createHmac('sha256', HMAC_KEY).update(`${sentAt}.${body}`).digest('hex')
  const response = await fetch(`${SERVER}/v1/e`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Origin: ORIGIN,
      'X-FirstRun-Key': SDK_KEY,
      'X-FirstRun-Timestamp': sentAt,
      'X-FirstRun-Signature': signature,
    },
    body,
  })
  if (response.status !== 202) {
    throw new Error(`demo-traffic: gateway answered ${response.status}: ${await response.text()}`)
  }
}

/**
 * Builds a UUIDv7 (RFC 9562) at a past timestamp, hand-rolled because scripts/
 * resolves no npm packages. The masks set the version and variant bits.
 */
function uuidv7(atMs) {
  const bytes = randomBytes(16)
  let ms = BigInt(Math.floor(atMs))
  for (let index = 5; index >= 0; index--) {
    bytes[index] = Number(ms & 0xffn)
    ms >>= 8n
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Returns a seeded mulberry32 PRNG. */
function mulberry32(seed) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 2 ** 32
  }
}
