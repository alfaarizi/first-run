#!/usr/bin/env node
// Replays labeled sessions through the deterministic stuck gate and reports
// precision, recall, and every confusion case. A metric under its
// evals/baselines.json floor fails the run. --sweep instead tabulates both
// metrics per threshold combination. Features mirror the in-stream session
// store, so numbers transfer to the live gate.

import { fileURLToPath } from 'node:url'

import { BASELINES, resolveDatasetUrl } from './lib/baselines.mjs'
import { ERROR, PAGE_VIEW } from './lib/events.mjs'
import { readJsonl } from './lib/jsonl.mjs'
import { STUCK } from './lib/labels.mjs'
import { MILESTONES } from './lib/milestones.mjs'
import { formatDuration } from './lib/time.mjs'

const SAMPLES_URL = resolveDatasetUrl(BASELINES.datasets.sessions)

const {
  precision: PRECISION_FLOOR,
  recall: RECALL_FLOOR
} = BASELINES.floors.gate

export const DEFAULT_THRESHOLDS = {
  errors: 3,
  dwellSeconds: 600,
  backtracks: 3
}

const SWEEP_THRESHOLDS = {
  errors: [2, 3, 4],
  dwellSeconds: [360, 480, 600, 900],
  backtracks: [2, 3, 4, 5],
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2))
}

function main(args) {
  if (args.some((arg) => arg !== '--sweep')) {
    console.error('measure-gate: usage: measure-gate.mjs [--sweep]')
    process.exit(1)
  }

  const replays = loadReplays()

  if (args.includes('--sweep')) {
    printSweep(replays)
  } else {
    const cells = printReport(replays, DEFAULT_THRESHOLDS)
    if (!meetsFloors(cells)) {
      console.error(`measure-gate: precision ${precision(cells).toFixed(3)} or recall ${recall(cells).toFixed(3)} misses a baselines.json floor`)
      process.exitCode = 1
    }
  }
  console.log('all numbers are SYNTHETIC because the set oversamples stall shapes')
}

/** Loads the pinned labeled sessions, each paired with its feature timeline. */
export function loadReplays() {
  return readJsonl(SAMPLES_URL).map((row) => ({ row, timeline: replaySession(row.events) }))
}

/**
 * Replays one session into per-event feature snapshots mirroring the
 * in-stream store, whose counters accumulate for the whole session with no
 * per-step reset. Labeled rows hold events in timestamp order, so redelivery
 * has no equivalent.
 */
export function replaySession(events) {
  const completedMilestones = new Set()
  const sessionStartedAtMs = Date.parse(events[0].timestamp)
  let errors = 0
  let backtracks = 0
  let lastPath = null
  let prevPath = null
  let openStep = 0
  let stepStartedAtMs = sessionStartedAtMs

  return events.map((event) => {
    const eventAtMs = Date.parse(event.timestamp)
    if (event.event === ERROR) errors++

    // The store reads a path only when properties holds a string.
    const path =
      event.event === PAGE_VIEW && typeof event.properties?.path === 'string'
        ? event.properties.path
        : null

    if (path !== null && path !== lastPath) {
      if (path === prevPath) backtracks++
      if (lastPath !== null) prevPath = lastPath
      lastPath = path
    }

    if (MILESTONES.includes(event.event)) {
      completedMilestones.add(event.event)
      const position = MILESTONES.findIndex((name) => !completedMilestones.has(name))
      const nextOpenStep = position === -1 ? null : position
      // The store restarts the step clock only when the open step moves.
      if (nextOpenStep !== openStep) {
        openStep = nextOpenStep
        stepStartedAtMs = eventAtMs
      }
    }

    // The store anchors dwell at the session's start once every milestone is complete.
    const hasOpenStep = openStep !== null
    const dwellFromMs = hasOpenStep ? stepStartedAtMs : sessionStartedAtMs
    const dwellSeconds = Math.floor((eventAtMs - dwellFromMs) / 1000)

    return { errors, backtracks, dwellSeconds, hasOpenStep, timestamp: event.timestamp }
  })
}

/**
 * Finds the first snapshot where the gate fires, or null when it never
 * fires. Rules check in a fixed order, so a tie reports the rule a reviewer
 * would cite first.
 */
export function findGateFiring(timeline, thresholds) {
  for (const [eventIndex, snapshot] of timeline.entries()) {
    if (!snapshot.hasOpenStep) continue
    if (snapshot.errors >= thresholds.errors) {
      return { rule: 'errors', eventIndex, timestamp: snapshot.timestamp }
    }
    if (snapshot.dwellSeconds >= thresholds.dwellSeconds) {
      return { rule: 'dwell', eventIndex, timestamp: snapshot.timestamp }
    }
    if (snapshot.backtracks >= thresholds.backtracks) {
      return { rule: 'backtracks', eventIndex, timestamp: snapshot.timestamp }
    }
  }
  return null
}

/** Names the confusion cell for one session from the gate's firing and the human label. */
function confusionCell(gateFiring, label) {
  const isStuck = label === STUCK
  if (gateFiring === null) return isStuck ? 'fn' : 'tn'
  return isStuck ? 'tp' : 'fp'
}

/** Counts the confusion cells for one threshold setting. */
export function countConfusionCells(replays, thresholds) {
  const cells = { tp: 0, fp: 0, fn: 0, tn: 0 }
  for (const { row, timeline } of replays) {
    cells[confusionCell(findGateFiring(timeline, thresholds), row.label)]++
  }
  return cells
}

function precision(cells) {
  return cells.tp + cells.fp === 0 ? 0 : cells.tp / (cells.tp + cells.fp)
}

function recall(cells) {
  return cells.tp + cells.fn === 0 ? 0 : cells.tp / (cells.tp + cells.fn)
}

/** Reports whether precision and recall both meet their baselines.json floors. */
export function meetsFloors(cells) {
  return precision(cells) >= PRECISION_FLOOR && recall(cells) >= RECALL_FLOOR
}

/** Prints one full report and returns its confusion cells. */
function printReport(replays, thresholds) {
  const cells = { tp: 0, fp: 0, fn: 0, tn: 0 }
  const anchors = { exact: 0, early: 0, late: 0 }
  const caseLines = []

  for (const { row, timeline } of replays) {
    const gateFiring = findGateFiring(timeline, thresholds)
    const isLabeledStuck = row.label === STUCK
    cells[confusionCell(gateFiring, row.label)]++
    if (gateFiring !== null && isLabeledStuck) {
      const offsetMs = Date.parse(gateFiring.timestamp) - Date.parse(row.stuck_at_ts)
      anchors[offsetMs === 0 ? 'exact' : offsetMs < 0 ? 'early' : 'late']++
    }
    if (gateFiring !== null && !isLabeledStuck) caseLines.push(formatConfusionCase('fp', row, timeline, gateFiring))
    if (gateFiring === null && isLabeledStuck) caseLines.push(formatConfusionCase('fn', row, timeline, null))
  }

  console.log(`gate thresholds     errors >= ${thresholds.errors}, dwell >= ${thresholds.dwellSeconds}s on an open step, backtracks >= ${thresholds.backtracks}`)
  console.log(`sessions            ${replays.length} (${cells.tp + cells.fn} stuck, ${cells.fp + cells.tn} not_stuck)`)
  console.log(`confusion           tp ${cells.tp}  fp ${cells.fp}  fn ${cells.fn}  tn ${cells.tn}`)
  console.log(`precision           ${precision(cells).toFixed(3)} (floor ${PRECISION_FLOOR.toFixed(2)})`)
  console.log(`recall              ${recall(cells).toFixed(3)} (floor ${RECALL_FLOOR.toFixed(2)})`)
  console.log(`anchor agreement    ${anchors.exact}/${cells.tp} at stuck_at_ts exactly, ${anchors.early} early, ${anchors.late} late`)
  for (const line of caseLines.sort()) console.log(line)

  return cells
}

/** Formats one confusion case as one line, peaks read from open-step snapshots only. */
function formatConfusionCase(cellName, row, timeline, gateFiring) {
  const spanSeconds = (Date.parse(timeline.at(-1).timestamp) - Date.parse(row.events[0].timestamp)) / 1000
  const peak = peakSignals(timeline)
  const where =
    gateFiring === null
      ? `stuck_at event ${1 + row.events.findIndex((event) => event.timestamp === row.stuck_at_ts)}`
      : `fired ${gateFiring.rule} at event ${gateFiring.eventIndex + 1}/${row.events.length}`
  return (
    `${cellName}  ${row.session_id}  ${row.milestone.padEnd(23)} ${where.padEnd(28)} ` +
    `peak errors ${peak.errors}, backtracks ${peak.backtracks}, dwell ${formatDuration(peak.dwellSeconds)}, ` +
    `span ${formatDuration(spanSeconds)}`
  )
}

/** Returns the highest errors, backtracks, and dwell reached while a step was open. */
function peakSignals(timeline) {
  const peak = { errors: 0, backtracks: 0, dwellSeconds: 0 }
  for (const snapshot of timeline) {
    if (!snapshot.hasOpenStep) continue
    peak.errors = Math.max(peak.errors, snapshot.errors)
    peak.backtracks = Math.max(peak.backtracks, snapshot.backtracks)
    peak.dwellSeconds = Math.max(peak.dwellSeconds, snapshot.dwellSeconds)
  }
  return peak
}

function printSweep(replays) {
  console.log('errors   dwell backtracks   tp  fp  fn  tn  precision  recall')
  for (const errors of SWEEP_THRESHOLDS.errors) {
    for (const backtracks of SWEEP_THRESHOLDS.backtracks) {
      for (const dwellSeconds of SWEEP_THRESHOLDS.dwellSeconds) {
        const cells = countConfusionCells(replays, { errors, dwellSeconds, backtracks })
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
          ].join(' ') + (meetsFloors(cells) ? '  pass' : '')
        )
      }
    }
  }
}
