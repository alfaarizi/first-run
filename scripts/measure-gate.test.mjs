import assert from 'node:assert/strict'
import test from 'node:test'

import { ERROR, PAGE_VIEW } from './lib/events.mjs'
import { MILESTONES } from './lib/milestones.mjs'
import {
  DEFAULT_THRESHOLDS,
  countConfusionCells,
  findGateFiring,
  loadReplays,
  meetsFloors,
  replaySession,
} from './measure-gate.mjs'

// Named in funnel order to match the shared catalog.
const [TASK_CREATED, TASK_COMPLETED, COMPLETED_TASKS_CLEARED] = MILESTONES

const TASKS_PATH = '/tasks'
const NEW_TASK_PATH = '/tasks/new'

const START_MS = Date.parse('2026-06-15T08:00:00Z')

test('skips missing, null, and non-string page paths', () => {
  const timeline = replaySession([
    event(PAGE_VIEW, 0, { path: TASKS_PATH }),
    event(PAGE_VIEW, 1),
    event(PAGE_VIEW, 2, null),
    event(PAGE_VIEW, 3, { path: 42 }),
    event(PAGE_VIEW, 4, { path: TASKS_PATH }),
  ])

  assert.deepEqual(
    timeline.map((snapshot) => snapshot.backtracks),
    [0, 0, 0, 0, 0]
  )
})

test('keeps counters session-cumulative across milestone progress', () => {
  const timeline = replaySession([
    event(ERROR, 0),
    event(ERROR, 1),
    event(TASK_CREATED, 2),
    event(ERROR, 3),
  ])

  assert.equal(timeline[2].errors, 2)
  assert.equal(timeline.at(-1).errors, 3)
  assert.equal(findGateFiring(timeline, DEFAULT_THRESHOLDS).rule, 'errors')
})

test('restarts the dwell clock only when the open step moves', () => {
  const inOrder = replaySession([
    event(PAGE_VIEW, 0, { path: TASKS_PATH }),
    event(TASK_CREATED, 300),
  ])
  const outOfPosition = replaySession([
    event(PAGE_VIEW, 0, { path: TASKS_PATH }),
    event(TASK_COMPLETED, 300),
  ])

  assert.equal(inOrder.at(-1).dwellSeconds, 0)
  assert.equal(outOfPosition.at(-1).dwellSeconds, 300)
})

test('keeps firings after the final milestone out of the gate scope', () => {
  const timeline = replaySession([
    event(TASK_CREATED, 0),
    event(TASK_COMPLETED, 1),
    event(COMPLETED_TASKS_CLEARED, 2),
    event(ERROR, 3),
    event(ERROR, 4),
    event(ERROR, 5),
    event(PAGE_VIEW, 6, { path: TASKS_PATH }),
    event(PAGE_VIEW, 7, { path: NEW_TASK_PATH }),
    event(PAGE_VIEW, 8, { path: TASKS_PATH }),
  ])

  assert.equal(timeline.at(-1).hasOpenStep, false)
  assert.equal(timeline.at(-1).errors, 3)
  assert.equal(timeline.at(-1).backtracks, 1)
  assert.equal(timeline.at(-1).dwellSeconds, 8)
  assert.equal(findGateFiring(timeline, { ...DEFAULT_THRESHOLDS, backtracks: 1 }), null)
})

test('preserves the pinned gate result', () => {
  assert.deepEqual(countConfusionCells(loadReplays(), DEFAULT_THRESHOLDS), { tp: 60, fp: 9, fn: 0, tn: 81 })
})

test('requires both metric floors', () => {
  assert.equal(meetsFloors({ tp: 60, fp: 9, fn: 0, tn: 81 }), true)
  assert.equal(meetsFloors({ tp: 7, fp: 2, fn: 3, tn: 8 }), false)
  assert.equal(meetsFloors({ tp: 7, fp: 1, fn: 4, tn: 8 }), false)
})

function event(name, seconds, properties) {
  return {
    event: name,
    timestamp: new Date(START_MS + seconds * 1000).toISOString(),
    ...(properties === undefined ? {} : { properties }),
  }
}
