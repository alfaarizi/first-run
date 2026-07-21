#!/usr/bin/env node
// Replays the golden set through the agent's Converse stream, judges every
// answer against evals/rubrics/groundedness.md, and prints groundedness, the
// unanswerable-subset honesty, and first-token latency. The replay needs the
// compose stack up with Tasklet's docs indexed and ANTHROPIC_API_KEY set for
// the judge. Without either it reports itself skipped and exits 0, because
// the CI gate over these numbers arrives with the eval job wiring.

import { readFileSync } from 'node:fs'

import Anthropic from '@anthropic-ai/sdk'
import grpc from '@grpc/grpc-js'
import protoLoader from '@grpc/proto-loader'

import { BASELINES, resolveDatasetUrl } from './lib/baselines.mjs'
import { DEMO_APP_ID, DEMO_TENANT_ID } from './lib/demo.mjs'

// Matches the host port compose publishes for the agent's gRPC. A default the
// stack does not expose would report every run skipped instead of replaying.
const AGENT_ADDRESS = process.env.AGENT_GRPC_ADDRESS ?? 'localhost:50052'
// The judge that measured the recorded baseline. A rerun under another judge
// produces numbers that cannot be compared to it.
const JUDGE_MODEL = process.env.QA_JUDGE_MODEL ?? BASELINES.baselines.qa.judge_model
// Caps a run for smoke tests and the per-run budget. The full set runs by default.
const SAMPLE = Number(process.env.QA_SAMPLE ?? Infinity)

const ANSWER_TIMEOUT_MS = 60_000

const PROTO_URL = new URL('../api/proto/firstrun/v1/conversation.proto', import.meta.url)
const RUBRIC_URL = new URL('../evals/rubrics/groundedness.md', import.meta.url)

main()

async function main() {
  if (!process.env.ANTHROPIC_API_KEY) {
    console.log('>> qa: skipped, ANTHROPIC_API_KEY is not set for the judge')
    return
  }
  const rows = readFileSync(resolveDatasetUrl(BASELINES.datasets.qa), 'utf8')
    .split('\n')
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line))
    .slice(0, SAMPLE)

  const client = createConversationClient()
  if (!(await reachable(client))) {
    console.log(`>> qa: skipped, no agent at ${AGENT_ADDRESS} (make up, then reindex)`)
    return
  }

  const judge = new Anthropic()
  const rubric = readFileSync(RUBRIC_URL, 'utf8')
  const results = []
  for (const row of rows) {
    const answer = await converse(client, row.question)
    results.push({ row, answer, ...(await scoreAnswer(judge, rubric, row, answer)) })
  }
  report(results)
}

function createConversationClient() {
  const definition = protoLoader.loadSync(PROTO_URL.pathname, {
    keepCase: true,
    longs: String,
    defaults: true,
  })
  const proto = grpc.loadPackageDefinition(definition)
  return new proto.firstrun.v1.ConversationService(
    AGENT_ADDRESS,
    grpc.credentials.createInsecure()
  )
}

function reachable(client) {
  return new Promise((resolve) => {
    client.waitForReady(Date.now() + 3_000, (error) => resolve(!error))
  })
}

/** Asks one question on a fresh conversation, collecting the streamed answer. */
function converse(client, question) {
  return new Promise((resolve, reject) => {
    const call = client.converse()
    const messageId = crypto.randomUUID()
    const started = performance.now()
    const answer = { text: '', citations: [], firstTokenMs: null, failed: false }
    const timeout = setTimeout(() => {
      // Text the deadline cut off is not a complete answer, so it reaches the
      // judge as a failure, never as an ordinary answer.
      answer.failed = true
      call.cancel()
      resolve(answer)
    }, ANSWER_TIMEOUT_MS)

    call.on('data', (frame) => {
      if (frame.answer_chunk) {
        answer.firstTokenMs ??= performance.now() - started
        answer.text += frame.answer_chunk.text
      } else if (frame.citation) {
        answer.citations.push(frame.citation)
      } else if (frame.answer_done) {
        // A failed done means the answer died or truncated mid-stream, so the
        // partial text is not a real answer to judge.
        answer.failed = frame.answer_done.failed
        clearTimeout(timeout)
        call.end()
        resolve(answer)
      }
    })
    call.on('error', (error) => {
      clearTimeout(timeout)
      reject(error)
    })

    call.write({
      context: {
        conversation_id: crypto.randomUUID(),
        tenant_id: DEMO_TENANT_ID,
        app_id: DEMO_APP_ID,
        end_user_hash: 'qa-harness',
        session_id: crypto.randomUUID(),
      },
    })
    call.write({ user_message: { message_id: messageId, text: question } })
  })
}

/**
 * Scores one replayed answer. An answer that never streamed or died mid-stream
 * is ungrounded without spending a judge call, so a partial that never
 * completed cannot inflate the groundedness rate.
 */
async function scoreAnswer(judge, rubric, row, answer) {
  if (answer.text.length === 0) {
    return { verdict: 'ungrounded', reason: 'the answer failed to stream' }
  }
  if (answer.failed) {
    return { verdict: 'ungrounded', reason: 'the answer failed mid-stream' }
  }
  return judgeAnswer(judge, rubric, row, answer)
}

async function judgeAnswer(judge, rubric, row, answer) {
  const citations = answer.citations
    .map((c) => `- ${c.title} (${c.source_url}): "${c.snippet}"`)
    .join('\n')
  const response = await judge.messages.create({
    model: JUDGE_MODEL,
    max_tokens: 256,
    system:
      `${rubric}\n\nApply the rubric to the case in the user message. ` +
      'Reply with only a JSON object: {"verdict": "grounded" | "ungrounded", "reason": "<one sentence>"}',
    messages: [
      {
        role: 'user',
        content:
          `Question: ${row.question}\n\n` +
          `Streamed answer:\n${answer.text}\n\n` +
          `Citations:\n${citations || '(none)'}\n\n` +
          `Reference row: answerable=${row.answerable}, ` +
          `reference_answer=${row.reference_answer}, ` +
          `source_urls=${row.source_urls.join(', ') || '(none)'}`,
      },
    ],
  })
  try {
    const text = response.content.find((block) => block.type === 'text')?.text ?? ''
    const parsed = JSON.parse(text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1))
    if (parsed.verdict === 'grounded' || parsed.verdict === 'ungrounded') return parsed
  } catch {
    // fall through to the judge-error verdict
  }
  return { verdict: 'judge_error', reason: 'the judge reply did not parse' }
}

function report(results) {
  const judged = results.filter((r) => r.verdict !== 'judge_error')
  const grounded = judged.filter((r) => r.verdict === 'grounded')
  const unanswerable = judged.filter((r) => !r.row.answerable)
  const honest = unanswerable.filter((r) => r.verdict === 'grounded')
  // An answer that never streamed counts at the timeout, so silent failures
  // widen the latency tail instead of vanishing from it.
  const neverStreamed = results.filter((r) => r.answer.firstTokenMs === null).length
  const latencies = results
    .map((r) => r.answer.firstTokenMs ?? ANSWER_TIMEOUT_MS)
    .sort((a, b) => a - b)
  const p95 = latencies[Math.min(latencies.length - 1, Math.ceil(latencies.length * 0.95) - 1)]

  for (const r of judged.filter((r) => r.verdict === 'ungrounded')) {
    console.log(`ungrounded ${r.row.id}: ${r.reason}`)
  }
  console.log(
    `questions ${results.length}, judged ${judged.length}, ` +
      `judge errors ${results.length - judged.length}`
  )
  console.log(
    `groundedness ${(grounded.length / judged.length).toFixed(3)} ` +
      '(floor 0.90 once the judge is calibrated)'
  )
  console.log(`unanswerable honesty ${honest.length}/${unanswerable.length}`)
  console.log(
    `first token p95 ${p95 === undefined ? 'n/a, no questions ran' : (p95 / 1000).toFixed(2) + 's'} ` +
      `(target 1.5s${neverStreamed > 0 ? `; ${neverStreamed} never streamed, counted at timeout` : ''})`
  )
  console.log('all numbers are SYNTHETIC because the set is drawn from demo docs')

  // Judge failures over 5% mean the numbers cannot be trusted at all.
  if (results.length - judged.length > results.length * 0.05) {
    console.error('measure-qa: too many judge errors to trust this run')
    process.exitCode = 1
  }
}
