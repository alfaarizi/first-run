#!/usr/bin/env node
// Generates unlabeled Tasklet sessions for the stuck-detection dataset, one
// JSON Lines session per line on stdout, in the event shape of
// api/openapi/ingest.yaml. Every byte derives from the session index and a
// fixed time anchor, so a larger count appends sessions without rewriting
// earlier lines and the labeled file stays hash-stable.

import { mulberry32, uuidv7 } from './lib/random.mjs'

const COUNT = Number(process.argv[2] ?? 150)
if (!Number.isInteger(COUNT) || COUNT < 1) {
  console.error('generate-sessions: count must be a positive integer')
  process.exit(1)
}

// A downstream reader such as `head` may close the pipe early, which is an
// end of demand, not a failure.
process.stdout.on('error', (error) => {
  if (error.code === 'EPIPE') process.exit(0)
  throw error
})

const ANCHOR_MS = Date.UTC(2026, 5, 15, 8)
const SECOND_MS = 1_000
const MINUTE_MS = 60_000
const HOUR_MS = 3_600_000
const DAY_MS = 86_400_000

// The pages Tasklet's traffic touches. `path` is on the app's allowlist and
// rides the auto-captured events.
const HOME = '/'
const TASKS = '/tasks'
const NEW_TASK = '/tasks/new'
const SIDE_PATHS = ['/archive', '/settings', '/help']

// The journey mixture, a sampling weight per journey type. Clean progress
// mixes with stall signals the funnel reads (dwell, retries, backtracking,
// errors), and stall patterns land in roughly two fifths of sessions,
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
  process.stdout.write(`${JSON.stringify(session(index))}\n`)
}

/** Builds one session, deterministic in its index. */
function session(index) {
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

/** Samples a journey type by mixture weight; the last absorbs rounding leftover. */
function sampleJourney(draw) {
  for (const [weight, journey] of JOURNEYS) {
    draw -= weight
    if (draw < 0) return journey
  }
  return JOURNEYS.at(-1)[1]
}

/** Opens the app on the landing page and settles on the task list. */
function arrive(t) {
  t.add('fr.page_view', { path: HOME })
  t.wait(2, 10)
  t.add('fr.page_view', { path: TASKS })
  t.add('task_list_viewed')
  t.wait(5, 40)
}

/** Progresses through creation and completion with short, easy gaps. */
function smoothCompleter(t) {
  arrive(t)
  t.add('fr.click', { path: TASKS })
  t.wait(5, 30)
  t.add('fr.page_view', { path: NEW_TASK })
  t.wait(10, 90)
  const count = 1 + Math.floor(t.rng() * 5)
  t.add('task_created', { task_count: count })
  t.wait(60, 600)
  t.add('fr.page_view', { path: TASKS })
  t.add('task_list_viewed')
  t.wait(10, 120)
  t.add('task_completed', { task_count: count })
  if (t.rng() < 0.4) {
    t.wait(30, 300)
    t.add('fr.click', { path: TASKS })
    t.add('completed_tasks_cleared', { task_count: Math.floor(t.rng() * count) })
  }
}

/** Looks around briefly and leaves. Too little evidence to call anything. */
function quickBounce(t) {
  t.add('fr.page_view', { path: HOME })
  t.wait(3, 20)
  if (t.rng() < 0.7) {
    t.add('fr.page_view', { path: TASKS })
    t.add('task_list_viewed')
  }
  if (t.rng() < 0.4) {
    t.wait(5, 60)
    t.add('fr.click', { path: TASKS })
  }
}

/** Wanders side pages at an unhurried pace, sometimes creating late. */
function explorer(t) {
  arrive(t)
  const stops = 2 + Math.floor(t.rng() * 3)
  for (let stop = 0; stop < stops; stop++) {
    const path = SIDE_PATHS[Math.floor(t.rng() * SIDE_PATHS.length)]
    t.add('fr.page_view', { path })
    t.wait(20, 180)
    if (t.rng() < 0.5) {
      t.add('fr.click', { path })
      t.wait(10, 120)
    }
  }
  t.add('fr.page_view', { path: TASKS })
  t.add('task_list_viewed')
  if (t.rng() < 0.5) {
    t.wait(15, 120)
    t.add('fr.page_view', { path: NEW_TASK })
    t.wait(10, 90)
    t.add('task_created', { task_count: 1 + Math.floor(t.rng() * 3) })
  }
}

/** Hits the same failure over and over on the creation form. */
function errorLooper(t) {
  arrive(t)
  t.add('fr.page_view', { path: NEW_TASK })
  t.wait(10, 60)
  const attempts = 3 + Math.floor(t.rng() * 5)
  for (let attempt = 0; attempt < attempts; attempt++) {
    t.add('fr.click', { path: NEW_TASK })
    t.wait(2, 15)
    t.add('fr.error', { path: NEW_TASK })
    t.wait(20, 180)
  }
  if (t.rng() < 0.3) {
    t.add('fr.page_view', { path: '/help' })
  }
}

/** Stays active on one step for a long stretch without progressing. */
function longDweller(t) {
  arrive(t)
  const page = t.rng() < 0.5 ? TASKS : NEW_TASK
  if (page === NEW_TASK) t.add('fr.page_view', { path: NEW_TASK })
  const clicks = 4 + Math.floor(t.rng() * 6)
  for (let click = 0; click < clicks; click++) {
    t.wait(120, 420)
    t.add('fr.click', { path: page })
  }
}

/** Ping-pongs between the list and the form without ever committing. */
function backtracker(t) {
  arrive(t)
  const loops = 3 + Math.floor(t.rng() * 4)
  for (let loop = 0; loop < loops; loop++) {
    t.add('fr.page_view', { path: NEW_TASK })
    t.wait(15, 120)
    if (t.rng() < 0.3) t.add('fr.click', { path: NEW_TASK })
    t.add('fr.page_view', { path: TASKS })
    t.wait(15, 150)
  }
}

/** Stalls hard on the form, then breaks through and creates the task. */
function recoveredStruggler(t) {
  arrive(t)
  t.add('fr.page_view', { path: NEW_TASK })
  const attempts = 2 + Math.floor(t.rng() * 4)
  for (let attempt = 0; attempt < attempts; attempt++) {
    t.add('fr.click', { path: NEW_TASK })
    t.wait(2, 15)
    t.add('fr.error', { path: NEW_TASK })
    t.wait(60, 300)
  }
  t.add('fr.click', { path: NEW_TASK })
  t.wait(5, 30)
  t.add('task_created', { task_count: 1 + Math.floor(t.rng() * 3) })
  if (t.rng() < 0.5) {
    t.wait(120, 900)
    t.add('fr.page_view', { path: TASKS })
    t.add('task_list_viewed')
    t.add('task_completed', { task_count: 1 })
  }
}

/** Shows one faint signal of each kind, then progresses or leaves. */
function mixedSignals(t) {
  arrive(t)
  t.add('fr.page_view', { path: NEW_TASK })
  t.wait(10, 90)
  if (t.rng() < 0.5) {
    t.add('fr.click', { path: NEW_TASK })
    t.wait(2, 10)
    t.add('fr.error', { path: NEW_TASK })
    t.wait(30, 120)
  }
  t.add('fr.page_view', { path: TASKS })
  t.wait(30, 240)
  t.add('fr.page_view', { path: NEW_TASK })
  t.wait(10, 90)
  if (t.rng() < 0.6) {
    t.add('task_created', { task_count: 1 + Math.floor(t.rng() * 3) })
  } else {
    t.add('fr.click', { path: NEW_TASK })
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
