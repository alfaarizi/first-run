#!/usr/bin/env node
// Compares two labeled session files on their shared session_ids and prints
// percent agreement and Cohen's kappa for the stuck / not_stuck label.
// Secondary lines report stuck_at_ts and milestone agreement.

import { readJsonl } from './lib/jsonl.mjs'
import { STUCK } from './lib/labels.mjs'

const [pathA, pathB] = process.argv.slice(2)
if (!pathA || !pathB) {
  console.error('compare-labels: usage: compare-labels.mjs <a.jsonl> <b.jsonl>')
  process.exit(1)
}

const readRowsById = (path) => new Map(readJsonl(path).map((row) => [row.session_id, row]))

const rowsA = readRowsById(pathA)
const rowsB = readRowsById(pathB)
const sharedIds = [...rowsA.keys()].filter((id) => rowsB.has(id))
if (sharedIds.length === 0) {
  console.error('compare-labels: no shared session_ids')
  process.exit(1)
}

let agree = 0
let bothStuck = 0
let sameStuckAt = 0
let sameMilestone = 0
let stuckA = 0
let stuckB = 0
for (const id of sharedIds) {
  const rowA = rowsA.get(id)
  const rowB = rowsB.get(id)
  if (rowA.label === rowB.label) agree++
  if (rowA.label === STUCK) stuckA++
  if (rowB.label === STUCK) stuckB++
  if (rowA.label === STUCK && rowB.label === STUCK) {
    bothStuck++
    if (rowA.stuck_at_ts === rowB.stuck_at_ts) sameStuckAt++
  }
  if (rowA.milestone === rowB.milestone) sameMilestone++
}

// Cohen's kappa corrects observed label agreement for the chance agreement
// implied by each annotator's stuck/not_stuck rate.
const n = sharedIds.length
const po = agree / n
const pe = (stuckA / n) * (stuckB / n) + ((n - stuckA) / n) * ((n - stuckB) / n)
const kappa = (po - pe) / (1 - pe)

console.log(`sessions compared   ${n}`)
console.log(`label agreement     ${agree}/${n} (${(100 * po).toFixed(1)}%)`)
console.log(`cohen's kappa       ${kappa.toFixed(3)}`)
console.log(`stuck_at_ts match   ${sameStuckAt}/${bothStuck} of both-stuck`)
console.log(`milestone agreement ${sameMilestone}/${n}`)
