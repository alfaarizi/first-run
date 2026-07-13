#!/usr/bin/env node
// Generates unlabeled Tasklet sessions for the stuck-detection dataset, one
// JSON Lines session per line on stdout, in the event shape of
// api/openapi/ingest.yaml.

import { CLICK, ERROR, PAGE_VIEW } from './lib/events.mjs'
import { MILESTONES, TASK_LIST_VIEWED } from './lib/milestones.mjs'
import { mulberry32, uuidv7 } from './lib/random.mjs'

// Named in funnel order to match the shared catalog.
const [TASK_CREATED, TASK_COMPLETED, COMPLETED_TASKS_CLEARED] = MILESTONES

const COUNT = Number(process.argv[2] ?? 150)
if (!Number.isInteger(COUNT) || COUNT < 1) {
  console.error('generate-sessions: count must be a positive integer')
  process.exit(1)
}

// A downstream reader such as `head` may close the pipe early.
process.stdout.on('error', (error) => {
  if (error.code === 'EPIPE') process.exit(0)
  throw error
})

const ANCHOR_MS = Date.UTC(2026, 5, 15, 8)
const SECOND_MS = 1_000
const MINUTE_MS = 60_000
const HOUR_MS = 3_600_000
const DAY_MS = 86_400_000

// The pages Tasklet's traffic. `path` is on the app's allowlist
// and allows auto-captured events.
const HOME_PATH = '/'
const TASKS_PATH = '/tasks'
const NEW_TASK_PATH = '/tasks/new'
const HELP_PATH = '/help'
const SIDE_PATHS = ['/archive', '/settings', HELP_PATH]

// The journey mixture, a sampling weight per journey type. Clean progress
// mixes with stall signals the funnel reads (dwell, retries, backtracking,
// errors), and stall patterns is roughly two fifths of sessions,
// oversampled because measuring precision and recall needs positives.
const JOURNEYS = [
  [0.18, smoothCompleter],
  [0.12, quickBounce],
  [0.15, explorer],
  [0.13, errorLooper],
  [0.13, longDweller],
  [0.12, backtracker],
  [0.09, recoveredStruggler],
  [0.08, mixedSignals],
]

for (let index = 0; index < COUNT; index++) {
  process.stdout.write(`${JSON.stringify(buildSession(index))}\n`)
}

/** Builds one session, deterministic in its index. */
function buildSession(index) {
  const rng = mulberry32(index + 1)
  const startAt = ANCHOR_MS + Math.floor(index / 5) * DAY_MS + Math.floor(rng() * 12 * HOUR_MS)
  const sessionId = seededUuid(startAt, rng)
  const endUserHash = `eval_user_${String(index).padStart(3, '0')}`

  let at = startAt
  const events = []
  const trail = {
    rng,
    add(event, properties = {}) {
      events.push({
        id: seededUuid(at, rng),
        event,
        end_user_hash: endUserHash,
        session_id: sessionId,
        timestamp: new Date(at).toISOString(),
        properties,
      })
    },
    // Gaps stay under the 30-minute idle cutoff so the trail is one session.
    wait(minSeconds, maxSeconds) {
      at += (minSeconds + rng() * (maxSeconds - minSeconds)) * SECOND_MS
    },
  }

  sampleJourney(rng())(trail)
  return { session_id: sessionId, events }
}

/** Samples a journey type by mixture weight, and the last absorbs rounding leftover. */
function sampleJourney(draw) {
  for (const [weight, journey] of JOURNEYS) {
    draw -= weight
    if (draw < 0) return journey
  }
  return JOURNEYS.at(-1)[1]
}

/** Opens the app on the landing page and settles on the task list. */
function arrive(trail) {
  trail.add(PAGE_VIEW, { path: HOME_PATH })
  trail.wait(2, 10)
  trail.add(PAGE_VIEW, { path: TASKS_PATH })
  trail.add(TASK_LIST_VIEWED)
  trail.wait(5, 40)
}

/** Progresses through creation and completion with short, easy gaps. */
function smoothCompleter(trail) {
  arrive(trail)
  trail.add(CLICK, { path: TASKS_PATH })
  trail.wait(5, 30)
  trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
  trail.wait(10, 90)
  const count = 1 + Math.floor(trail.rng() * 5)
  trail.add(TASK_CREATED, { task_count: count })
  trail.wait(60, 600)
  trail.add(PAGE_VIEW, { path: TASKS_PATH })
  trail.add(TASK_LIST_VIEWED)
  trail.wait(10, 120)
  trail.add(TASK_COMPLETED, { task_count: count })
  if (trail.rng() < 0.4) {
    trail.wait(30, 300)
    trail.add(CLICK, { path: TASKS_PATH })
    trail.add(COMPLETED_TASKS_CLEARED, { task_count: Math.floor(trail.rng() * count) })
  }
}

/** Looks around briefly and leaves. Too little evidence to call anything. */
function quickBounce(trail) {
  trail.add(PAGE_VIEW, { path: HOME_PATH })
  trail.wait(3, 20)
  if (trail.rng() < 0.7) {
    trail.add(PAGE_VIEW, { path: TASKS_PATH })
    trail.add(TASK_LIST_VIEWED)
  }
  if (trail.rng() < 0.4) {
    trail.wait(5, 60)
    trail.add(CLICK, { path: TASKS_PATH })
  }
}

/** Wanders side pages at an unhurried pace, sometimes creating late. */
function explorer(trail) {
  arrive(trail)
  const stops = 2 + Math.floor(trail.rng() * 3)
  for (let stop = 0; stop < stops; stop++) {
    const path = SIDE_PATHS[Math.floor(trail.rng() * SIDE_PATHS.length)]
    trail.add(PAGE_VIEW, { path })
    trail.wait(20, 180)
    if (trail.rng() < 0.5) {
      trail.add(CLICK, { path })
      trail.wait(10, 120)
    }
  }
  trail.add(PAGE_VIEW, { path: TASKS_PATH })
  trail.add(TASK_LIST_VIEWED)
  if (trail.rng() < 0.5) {
    trail.wait(15, 120)
    trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
    trail.wait(10, 90)
    trail.add(TASK_CREATED, { task_count: 1 + Math.floor(trail.rng() * 3) })
  }
}

/** Hits the same failure over and over on the creation form. */
function errorLooper(trail) {
  arrive(trail)
  trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
  trail.wait(10, 60)
  const attempts = 3 + Math.floor(trail.rng() * 5)
  for (let attempt = 0; attempt < attempts; attempt++) {
    trail.add(CLICK, { path: NEW_TASK_PATH })
    trail.wait(2, 15)
    trail.add(ERROR, { path: NEW_TASK_PATH })
    trail.wait(20, 180)
  }
  if (trail.rng() < 0.3) {
    trail.add(PAGE_VIEW, { path: HELP_PATH })
  }
}

/** Stays active on one step for a long stretch without progressing. */
function longDweller(trail) {
  arrive(trail)
  const page = trail.rng() < 0.5 ? TASKS_PATH : NEW_TASK_PATH
  if (page === NEW_TASK_PATH) trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
  const clicks = 4 + Math.floor(trail.rng() * 6)
  for (let click = 0; click < clicks; click++) {
    trail.wait(120, 420)
    trail.add(CLICK, { path: page })
  }
}

/** Ping-pongs between the list and the form without ever committing. */
function backtracker(trail) {
  arrive(trail)
  const loops = 3 + Math.floor(trail.rng() * 4)
  for (let loop = 0; loop < loops; loop++) {
    trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
    trail.wait(15, 120)
    if (trail.rng() < 0.3) trail.add(CLICK, { path: NEW_TASK_PATH })
    trail.add(PAGE_VIEW, { path: TASKS_PATH })
    trail.wait(15, 150)
  }
}

/** Stalls hard on the form, then breaks through and creates the task. */
function recoveredStruggler(trail) {
  arrive(trail)
  trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
  const attempts = 2 + Math.floor(trail.rng() * 4)
  for (let attempt = 0; attempt < attempts; attempt++) {
    trail.add(CLICK, { path: NEW_TASK_PATH })
    trail.wait(2, 15)
    trail.add(ERROR, { path: NEW_TASK_PATH })
    trail.wait(60, 300)
  }
  trail.add(CLICK, { path: NEW_TASK_PATH })
  trail.wait(5, 30)
  trail.add(TASK_CREATED, { task_count: 1 + Math.floor(trail.rng() * 3) })
  if (trail.rng() < 0.5) {
    trail.wait(120, 900)
    trail.add(PAGE_VIEW, { path: TASKS_PATH })
    trail.add(TASK_LIST_VIEWED)
    trail.add(TASK_COMPLETED, { task_count: 1 })
  }
}

/** Shows one faint signal of each kind, then progresses or leaves. */
function mixedSignals(trail) {
  arrive(trail)
  trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
  trail.wait(10, 90)
  if (trail.rng() < 0.5) {
    trail.add(CLICK, { path: NEW_TASK_PATH })
    trail.wait(2, 10)
    trail.add(ERROR, { path: NEW_TASK_PATH })
    trail.wait(30, 120)
  }
  trail.add(PAGE_VIEW, { path: TASKS_PATH })
  trail.wait(30, 240)
  trail.add(PAGE_VIEW, { path: NEW_TASK_PATH })
  trail.wait(10, 90)
  if (trail.rng() < 0.6) {
    trail.add(TASK_CREATED, { task_count: 1 + Math.floor(trail.rng() * 3) })
  } else {
    trail.add(CLICK, { path: NEW_TASK_PATH })
  }
}

/** Builds a UUIDv7 whose random bits come from the seeded rng, so reruns stay byte-identical. */
function seededUuid(atMs, rng) {
  const bits = new Uint8Array(16)
  for (let index = 6; index < 16; index++) {
    bits[index] = Math.floor(rng() * 256)
  }
  return uuidv7(atMs, bits)
}
