#!/usr/bin/env node
// Records stuck / not_stuck judgments on generated sessions, the Prodigy
// pattern: JSON Lines in and out, one answer per example, resumable. Each
// answer appends to the labeled file immediately, so a stopped run loses
// nothing and a rerun skips every session already labeled.

import { appendFileSync, existsSync } from 'node:fs'
import { createInterface } from 'node:readline'

import { readJsonl } from './lib/jsonl.mjs'
import { MILESTONES } from './lib/milestones.mjs'

const OUTPUT_DEFAULT = 'evals/datasets/sessions/samples.jsonl'
const ANSWER = /^(n|s (\d+))( m=([a-z][a-z0-9_]*))?$/

const args = process.argv.slice(2)
const printOnly = args.includes('--print')
const paths = args.filter((arg) => arg !== '--print')
const inputPath = paths[0]
const outputPath = paths[1] ?? OUTPUT_DEFAULT
if (!inputPath) {
  console.error('label-sessions: usage: label-sessions.mjs <unlabeled.jsonl> [labeled.jsonl] [--print]')
  process.exit(1)
}

const sessions = readJsonl(inputPath)
const done = new Set(existsSync(outputPath) ? readJsonl(outputPath).map((row) => row.session_id) : [])
const pending = sessions.filter((row) => !done.has(row.session_id))

console.error(`${sessions.length} sessions, ${done.size} labeled, ${pending.length} pending`)

if (printOnly) {
  for (const [index, row] of pending.entries()) {
    console.log(render(row, index, pending.length))
  }
  process.exit(0)
}

const reader = createInterface({ input: process.stdin })
const answers = reader[Symbol.asyncIterator]()
for (const [index, row] of pending.entries()) {
  console.log(render(row, index, pending.length))
  const answer = await ask(row)
  if (answer === null) break
  appendFileSync(outputPath, `${JSON.stringify(answer)}\n`)
}
reader.close()
console.error(`labeled file: ${outputPath}`)

/** Prompts until one session has a valid answer; null means quit. */
async function ask(row) {
  const proposed = proposeMilestone(row)
  for (;;) {
    process.stdout.write(`label [n | s <event#> | q] (milestone ${proposed})> `)
    const { value, done: closed } = await answers.next()
    if (closed) return null
    const line = value.trim()
    if (line === 'q') return null
    const match = line.match(ANSWER)
    if (!match) {
      console.log('answer with n, s <event#>, or q, plus m=<milestone> to override')
      continue
    }
    const milestone = match[4] ?? proposed
    if (!MILESTONES.includes(milestone)) {
      console.log(`milestone must be one of ${MILESTONES.join(', ')}`)
      continue
    }
    if (match[1] === 'n') {
      return { session_id: row.session_id, events: row.events, label: 'not_stuck', stuck_at_ts: null, milestone }
    }
    const eventAt = Number(match[2])
    if (eventAt < 1 || eventAt > row.events.length) {
      console.log(`event number must be 1..${row.events.length}`)
      continue
    }
    return {
      session_id: row.session_id,
      events: row.events,
      label: 'stuck',
      stuck_at_ts: row.events[eventAt - 1].timestamp,
      milestone,
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
      `+${clock(offsetMs)}`,
      at === 0 ? '      ' : `(${clock(gapMs)})`,
      event.event.padEnd(24),
      props,
    ].join('  ')
  })
  return [
    '',
    `── session ${index + 1}/${total}  ${row.session_id}`,
    `   ${events[0].end_user_hash}  ${events[0].timestamp.slice(0, 10)}  ` +
      `span ${clock(Date.parse(events.at(-1).timestamp) - startMs)}  ${events.length} events`,
    ...lines,
    `   signals: ${summarize(events)}`,
  ].join('\n')
}

/**
 * Sums the stall signals a reviewer weighs, with the funnel's own semantics:
 * a repeat is the same event name on the same page as the one before, and a
 * return is a page view of the page left two views ago. The summary advises;
 * the guide's rules decide.
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
    if (event.event === 'fr.error') errors++
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
    const path = event.event === 'fr.page_view' ? event.properties.path : null
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
    `path returns ${returns}, longest gap ${clock(longestGapMs)}, ` +
    `milestones [${milestones.join(', ')}]`
  )
}

/** Proposes the first milestone the session never reached, in funnel order. */
function proposeMilestone(row) {
  const seen = new Set(row.events.map((event) => event.event))
  return MILESTONES.find((name) => !seen.has(name)) ?? MILESTONES.at(-1)
}

/** Formats a duration as m:ss, hours folded into minutes. */
function clock(ms) {
  const seconds = Math.round(ms / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
