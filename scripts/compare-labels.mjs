#!/usr/bin/env node
// Compares two labeled session files on their shared session_ids and prints
// percent agreement and Cohen's kappa for the stuck / not_stuck label.
// Secondary lines report stuck_at_ts and milestone agreement.

import { readJsonl } from './lib/jsonl.mjs'

const [pathA, pathB] = process.argv.slice(2)
if (!pathA || !pathB) {
  console.error('compare-labels: usage: compare-labels.mjs <a.jsonl> <b.jsonl>')
  process.exit(1)
}

const byId = (path) => new Map(readJsonl(path).map((row) => [row.session_id, row]))

const a = byId(pathA)
const b = byId(pathB)
const shared = [...a.keys()].filter((id) => b.has(id))
if (shared.length === 0) {
  console.error('compare-labels: no shared session_ids')
  process.exit(1)
}

let agree = 0
let bothStuck = 0
let sameStuckAt = 0
let sameMilestone = 0
let stuckA = 0
let stuckB = 0
for (const id of shared) {
  const rowA = a.get(id)
  const rowB = b.get(id)
  if (rowA.label === rowB.label) agree++
  if (rowA.label === 'stuck') stuckA++
  if (rowB.label === 'stuck') stuckB++
  if (rowA.label === 'stuck' && rowB.label === 'stuck') {
    bothStuck++
    if (rowA.stuck_at_ts === rowB.stuck_at_ts) sameStuckAt++
  }
  if (rowA.milestone === rowB.milestone) sameMilestone++
}

// Cohen's kappa over the two label classes: observed agreement corrected by
// the agreement two annotators with these marginals would reach by chance.
const n = shared.length
const po = agree / n
const pe = (stuckA / n) * (stuckB / n) + ((n - stuckA) / n) * ((n - stuckB) / n)
const kappa = (po - pe) / (1 - pe)

console.log(`sessions compared   ${n}`)
console.log(`label agreement     ${agree}/${n} (${(100 * po).toFixed(1)}%)`)
console.log(`cohen's kappa       ${kappa.toFixed(3)}`)
console.log(`stuck_at_ts match   ${sameStuckAt}/${bothStuck} of both-stuck`)
console.log(`milestone agreement ${sameMilestone}/${n}`)
