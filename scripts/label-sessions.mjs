#!/usr/bin/env node
// Records stuck / not_stuck judgments on generated sessions following the
// Prodigy pattern of JSON Lines in and out, one answer per example, and is
// resumable. Each answer appends to the labeled file immediately, so a
// stopped run loses nothing and a rerun skips every session already labeled.

import { appendFileSync, existsSync } from 'node:fs'
import { createInterface } from 'node:readline'

import { ERROR, PAGE_VIEW } from './lib/events.mjs'
import { readJsonl } from './lib/jsonl.mjs'
import { NOT_STUCK, STUCK } from './lib/labels.mjs'
import { MILESTONES } from './lib/milestones.mjs'
import { formatDuration } from './lib/time.mjs'

const DEFAULT_OUTPUT_PATH = 'evals/datasets/sessions/samples.jsonl'
const ANSWER = /^(n|s (\d+))( m=([a-z][a-z0-9_]*))?$/

const { inputPath, outputPath, isPrintOnly } = parseArgs(process.argv.slice(2))
const pendingSessions = loadPendingSessions(inputPath, outputPath)

if (isPrintOnly) {
  printSessions(pendingSessions)
}
else {
  await labelSessions(pendingSessions, outputPath)
}

/** Reads argv into the input path, the output path, and the print-only flag. */
function parseArgs(args) {
  const isPrintOnly = args.includes('--print')
  const [inputPath, outputPath = DEFAULT_OUTPUT_PATH] = args.filter((arg) => arg !== '--print')
  if (!inputPath) {
    console.error('label-sessions: usage: label-sessions.mjs <unlabeled.jsonl> [labeled.jsonl] [--print]')
    process.exit(1)
  }
  return { inputPath, outputPath, isPrintOnly }
}

/** Loads the sessions not yet in the output file, reporting the counts. */
function loadPendingSessions(inputPath, outputPath) {
  const sessions = readJsonl(inputPath)
  const labeledIds = new Set(existsSync(outputPath) ? readJsonl(outputPath).map((row) => row.session_id) : [])
  const pendingSessions = sessions.filter((row) => !labeledIds.has(row.session_id))
  console.error(`${sessions.length} sessions, ${labeledIds.size} labeled, ${pendingSessions.length} pending`)
  return pendingSessions
}

/** Prints each pending session's timeline for review. */
function printSessions(pendingSessions) {
  for (const [index, row] of pendingSessions.entries()) {
    console.log(render(row, index, pendingSessions.length))
  }
}

/** Prompts for each pending session and appends every answer the moment it is given. */
async function labelSessions(pendingSessions, outputPath) {
  const reader = createInterface({ input: process.stdin })
  const answers = reader[Symbol.asyncIterator]()
  for (const [index, row] of pendingSessions.entries()) {
    console.log(render(row, index, pendingSessions.length))
    const answer = await ask(row, answers)
    if (answer === null) break
    appendFileSync(outputPath, `${JSON.stringify(answer)}\n`)
    console.error(`saved ${answer.label}, milestone ${answer.milestone}`)
  }
  reader.close()
  console.error(`labeled file: ${outputPath}`)
}

/** Prompts until one session has a valid answer, and null means quit. */
async function ask(row, answers) {
  const proposed = proposeMilestone(row.events)
  for (;;) {
    process.stdout.write(`label [n | s <event#> | q] (milestone ${proposed})> `)
    const { value, done } = await answers.next()
    if (done) return null
    const line = value.trim()
    if (line === 'q') return null

    const match = line.match(ANSWER)
    if (!match) {
      console.log('answer with n, s <event#>, or q, plus m=<milestone> to override')
      continue
    }

    const override = match[4]
    if (override && !MILESTONES.includes(override)) {
      console.log(`milestone must be one of ${MILESTONES.join(', ')}`)
      continue
    }

    if (match[1] === 'n') {
      return {
        session_id: row.session_id,
        events: row.events,
        label: NOT_STUCK,
        stuck_at_ts: null,
        milestone: override ?? proposed,
      }
    }

    const eventNumber = Number(match[2])
    if (eventNumber < 1 || eventNumber > row.events.length) {
      console.log(`event number must be 1..${row.events.length}`)
      continue
    }

    // Defaults to the step in play at the stall.
    return {
      session_id: row.session_id,
      events: row.events,
      label: STUCK,
      stuck_at_ts: row.events[eventNumber - 1].timestamp,
      milestone: override ?? proposeMilestone(row.events.slice(0, eventNumber)),
    }
  }
}

/** Renders one session as a numbered timeline with a signal summary. */
function render(row, index, total) {
  const events = row.events
  const startMs = Date.parse(events[0].timestamp)
  const lines = events.map((event, at) => {
    const offsetMs = Date.parse(event.timestamp) - startMs
    const gapMs = at === 0 ? 0 : Date.parse(event.timestamp) - Date.parse(events[at - 1].timestamp)
    const props = Object.entries(event.properties)
      .map(([key, value]) => `${key}=${value}`)
      .join(' ')
    return [
      String(at + 1).padStart(4),
      `+${formatDuration(offsetMs / 1000)}`,
      at === 0 ? '      ' : `(${formatDuration(gapMs / 1000)})`,
      event.event.padEnd(24),
      props,
    ].join('  ')
  })
  return [
    '',
    `── session ${index + 1}/${total}  ${row.session_id}`,
    `   ${events[0].end_user_hash}  ${events[0].timestamp.slice(0, 10)}  ` +
      `span ${formatDuration((Date.parse(events.at(-1).timestamp) - startMs) / 1000)}  ${events.length} events`,
    ...lines,
    `   signals: ${summarize(events)}`,
  ].join('\n')
}

/**
 * Sums the stall signals a reviewer weighs, using the funnel's own semantics.
 * A repeat is the same event name on the same page as the one before, and a
 * return is a page view of the page left two views ago. The summary advises
 * and the guide's rules decide.
 */
function summarize(events) {
  let errors = 0
  let repeats = 0
  let returns = 0
  let longestRun = 1
  let run = 1
  let longestGapMs = 0
  let lastPath = null
  let prevPath = null
  events.forEach((event, at) => {
    if (event.event === ERROR) errors++
    if (at > 0) {
      const previous = events[at - 1]
      longestGapMs = Math.max(longestGapMs, Date.parse(event.timestamp) - Date.parse(previous.timestamp))
      if (event.event === previous.event && event.properties.path === previous.properties.path) {
        repeats++
        run++
        longestRun = Math.max(longestRun, run)
      } else {
        run = 1
      }
    }
    const path = event.event === PAGE_VIEW ? event.properties.path : null
    if (path !== null && path !== lastPath) {
      if (path === prevPath) returns++
      else prevPath = lastPath
      lastPath = path
    }
  })
  const seen = new Set(events.map((event) => event.event))
  const milestones = MILESTONES.filter((name) => seen.has(name))
  return (
    `errors ${errors}, repeats ${repeats} (longest run ${longestRun}), ` +
    `path returns ${returns}, longest gap ${formatDuration(longestGapMs / 1000)}, ` +
    `milestones [${milestones.join(', ')}]`
  )
}

/** Proposes the first milestone the events never reach, in funnel order. */
function proposeMilestone(events) {
  const seen = new Set(events.map((event) => event.event))
  return MILESTONES.find((name) => !seen.has(name)) ?? MILESTONES.at(-1)
}
