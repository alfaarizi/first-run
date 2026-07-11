#!/usr/bin/env node
// Replays labeled sessions through the deterministic stuck gate and prints
// precision (tp / (tp + fp)), recall (tp / (tp + fn)), and every confusion
// case for review. --sweep instead tabulates both metrics per threshold
// combination, the shape of scikit-learn's precision_recall_curve. Features
// mirror the in-stream session store, so numbers transfer to the live gate.

import { readJsonl } from './lib/jsonl.mjs'
import { MILESTONES } from './lib/milestones.mjs'

const SAMPLES_DEFAULT = 'evals/datasets/sessions/samples.jsonl'

// The floors the gate must clear on labeled sessions.
const PRECISION_FLOOR = 0.8
const RECALL_FLOOR = 0.7

// The labeling guide's human anchors: three repeated failures, ten minutes
// of continued activity on one step, a third return to an abandoned page.
const DEFAULTS = { errors: 3, dwellSeconds: 600, backtracks: 3 }

// Values around each default, so the sweep shows which direction a
// threshold should move.
const SWEEP = {
  errors: [2, 3, 4],
  dwellSeconds: [360, 480, 600, 900],
  backtracks: [2, 3, 4, 5],
}

const args = process.argv.slice(2)
const inputPath = args.filter((arg) => arg !== '--sweep')[0] ?? SAMPLES_DEFAULT
const replays = readJsonl(inputPath).map((row) => ({ row, timeline: replay(row.events) }))

if (args.includes('--sweep')) printSweep(replays)
else printRun(replays, DEFAULTS)
console.log('all numbers SYNTHETIC because the set oversamples stall shapes')

/**
 * Replays one session into per-event feature snapshots, mirroring the
 * in-stream store: a backtrack is a page view of the page before the last
 * distinct one, and dwell runs from the open funnel step's entry. Labeled
 * rows hold events in timestamp order, so redelivery has no equivalent.
 */
function replay(events) {
  const seen = new Set()
  let errors = 0
  let backtracks = 0
  let lastPath = null
  let prevPath = null
  let step = 0
  let stepStartedAtMs = Date.parse(events[0].timestamp)

  return events.map((event) => {
    const eventMs = Date.parse(event.timestamp)
    if (event.event === 'fr.error') errors++

    const path = event.event === 'fr.page_view' ? (event.properties.path ?? null) : null
    if (path !== null && path !== lastPath) {
      if (path === prevPath) backtracks++
      if (lastPath !== null) prevPath = lastPath
      lastPath = path
    }

    if (MILESTONES.includes(event.event)) {
      seen.add(event.event)
      const position = MILESTONES.findIndex((name) => !seen.has(name))
      const open = position === -1 ? null : position
      if (open !== step) {
        step = open
        stepStartedAtMs = eventMs
      }
    }

    // A user past the last milestone has no step to be stuck on.
    const dwellSeconds = step === null ? 0 : Math.floor((eventMs - stepStartedAtMs) / 1000)
    return { errors, backtracks, dwellSeconds, timestamp: event.timestamp }
  })
}

/**
 * Finds the first snapshot crossing a threshold, checking rules in the
 * labeling guide's order so a tie reports the rule a human would cite.
 * Null means no rule ever fires.
 */
function findFiring(timeline, thresholds) {
  for (const [at, snapshot] of timeline.entries()) {
    if (snapshot.errors >= thresholds.errors) return { rule: 'errors', at, timestamp: snapshot.timestamp }
    if (snapshot.dwellSeconds >= thresholds.dwellSeconds) return { rule: 'dwell', at, timestamp: snapshot.timestamp }
    if (snapshot.backtracks >= thresholds.backtracks) return { rule: 'backtracks', at, timestamp: snapshot.timestamp }
  }
  return null
}

/** Counts the confusion cells for one threshold setting. */
function score(replays, thresholds) {
  const cells = { tp: 0, fp: 0, fn: 0, tn: 0 }
  for (const { row, timeline } of replays) {
    const fired = findFiring(timeline, thresholds) !== null
    const stuck = row.label === 'stuck'
    cells[fired ? (stuck ? 'tp' : 'fp') : stuck ? 'fn' : 'tn']++
  }
  return cells
}

/** Prints one full run: metrics, anchor agreement, and the confusion cases. */
function printRun(replays, thresholds) {
  const cells = { tp: 0, fp: 0, fn: 0, tn: 0 }
  const anchors = { exact: 0, early: 0, late: 0 }
  const cases = []

  for (const { row, timeline } of replays) {
    const fired = findFiring(timeline, thresholds)
    const stuck = row.label === 'stuck'
    cells[fired !== null ? (stuck ? 'tp' : 'fp') : stuck ? 'fn' : 'tn']++
    if (fired !== null && stuck) {
      const offsetMs = Date.parse(fired.timestamp) - Date.parse(row.stuck_at_ts)
      anchors[offsetMs === 0 ? 'exact' : offsetMs < 0 ? 'early' : 'late']++
    }
    if (fired !== null && !stuck) cases.push(describe('fp', row, timeline, fired))
    if (fired === null && stuck) cases.push(describe('fn', row, timeline, null))
  }

  console.log(`gate thresholds     errors >= ${thresholds.errors}, dwell >= ${thresholds.dwellSeconds}s on an open step, backtracks >= ${thresholds.backtracks}`)
  console.log(`sessions            ${replays.length} (${cells.tp + cells.fn} stuck, ${cells.fp + cells.tn} not_stuck)`)
  console.log(`confusion           tp ${cells.tp}  fp ${cells.fp}  fn ${cells.fn}  tn ${cells.tn}`)
  console.log(`precision           ${precision(cells).toFixed(3)} (floor ${PRECISION_FLOOR.toFixed(2)})`)
  console.log(`recall              ${recall(cells).toFixed(3)} (floor ${RECALL_FLOOR.toFixed(2)})`)
  console.log(`anchor agreement    ${anchors.exact}/${cells.tp} at stuck_at_ts exactly, ${anchors.early} early, ${anchors.late} late`)
  for (const line of cases.sort()) console.log(line)
}

/** Renders one confusion case as a single scannable line. */
function describe(kind, row, timeline, fired) {
  const last = timeline.at(-1)
  const spanSeconds = (Date.parse(last.timestamp) - Date.parse(row.events[0].timestamp)) / 1000
  const peakDwell = Math.max(...timeline.map((snapshot) => snapshot.dwellSeconds))
  const where =
    fired === null
      ? `stuck_at event ${1 + row.events.findIndex((event) => event.timestamp === row.stuck_at_ts)}`
      : `fired ${fired.rule} at event ${fired.at + 1}/${row.events.length}`
  return (
    `${kind}  ${row.session_id}  ${row.milestone.padEnd(23)} ${where.padEnd(28)} ` +
    `errors ${last.errors}, backtracks ${last.backtracks}, peak dwell ${clock(peakDwell)}, span ${clock(spanSeconds)}`
  )
}

/** Prints precision and recall per threshold combination, floors marked. */
function printSweep(replays) {
  console.log('errors   dwell backtracks   tp  fp  fn  tn  precision  recall')
  for (const errors of SWEEP.errors) {
    for (const backtracks of SWEEP.backtracks) {
      for (const dwellSeconds of SWEEP.dwellSeconds) {
        const cells = score(replays, { errors, dwellSeconds, backtracks })
        const passes = precision(cells) >= PRECISION_FLOOR && recall(cells) >= RECALL_FLOOR
        console.log(
          [
            String(errors).padStart(6),
            `${dwellSeconds}s`.padStart(7),
            String(backtracks).padStart(10),
            String(cells.tp).padStart(4),
            String(cells.fp).padStart(3),
            String(cells.fn).padStart(3),
            String(cells.tn).padStart(3),
            precision(cells).toFixed(3).padStart(10),
            recall(cells).toFixed(3).padStart(7),
          ].join(' ') + (passes ? '  pass' : '')
        )
      }
    }
  }
}

function precision(cells) {
  return cells.tp + cells.fp === 0 ? 0 : cells.tp / (cells.tp + cells.fp)
}

function recall(cells) {
  return cells.tp + cells.fn === 0 ? 0 : cells.tp / (cells.tp + cells.fn)
}

/** Formats a duration as m:ss, hours folded into minutes. */
function clock(seconds) {
  const whole = Math.round(seconds)
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}
