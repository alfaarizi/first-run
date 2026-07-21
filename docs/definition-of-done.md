# Definition of Done

Date: 2026-07-01

Every change meets these criteria before it merges. A criterion that cites an ADR
or another doc keeps its reasoning there, and this file lists only what to check.
CI
enforces the machine-checkable criteria on every pull request. A change is done
when every applicable criterion passes, and none was skipped or weakened to get
there.

## Every change

These apply no matter what the change touches.

1. `make lint` passes with zero errors across spotless, ruff, mypy, eslint, and
   buf lint.
2. `make test` passes, covering ArchUnit and `ApplicationModules.verify()`
   boundary checks, Testcontainers integration tests, and the agent's
   fixture-only tests that never call a live model.
3. No test, ArchUnit rule, eval floor, or size budget was weakened to pass CI, and
   changing one requires its own ADR.
4. No secret enters the repository.
5. Docs are updated with the code, so a structural decision becomes an ADR, and a
   contract change is recorded in `api/CHANGELOG.md`.

## Contracts

The contract in `/api` is the single source of truth, and everything downstream
regenerates from it.

1. The change starts in `/api`, regenerates every stub with `make generate`, and
   updates every consumer in the same pull request.
2. `buf breaking` and `graphql-inspector diff` pass, and no generated stub was
   hand-edited.

## Tenancy, data, and privacy

Tenant isolation and the append-only ledger are absolute, so any change to storage
proves they still hold.

1. Every new tenant-scoped table carries a row-level security (RLS) policy and an
   isolation test that proves a cross-tenant read fails.
2. Ledger writes are inserts. No repository method or migration updates or deletes
   a ledger row.
3. Migrations are forward-only `V<n>__short_desc.sql` files. An applied migration
   is never edited, and a bad one gets a corrective migration.
4. Event capture is a default-deny allowlist, end-user IDs arrive pre-hashed, and
   the gateway truncates IPs before storage (ADR-007). Free-text capture does not
   exist.

## Streaming and the intervention loop

The loop delivers at least once and falls back to a hold rather than a wrong or
missing decision.

1. Every new Kafka consumer is idempotent, dedupes on the event's unique ID, and
   includes a dead-letter queue from its first commit.
2. Every candidate that clears the stuck gate produces exactly one ledgered
   decision, and an agent timeout is a hold with reason `agent_timeout`.
3. Suppression rules are checked at decision time and again at render time.
4. No webhook fires without a recorded user confirmation, and the server
   revalidates the action name and scope regardless of model output (ADR-008).
5. The webhook executor allows https only, denies private, loopback, link-local,
   and metadata address ranges, follows no redirects, and caps each call at a 5s
   timeout and a 64KB response.

## Agent and evaluation

Nothing agentic merges without passing the offline harness, and the floors are
limits, not targets.

1. A change under `agent/`, `api/`, or `evals/` passes `make eval` at or above
   every floor in `evals/baselines.json`.
2. The adversarial suite passes all 30 injection cases, with zero tool calls and
   zero unregistered proposals.
3. A baseline change goes in its own commit, with the reason in the body.

## Widget and dashboard

The two customer-facing surfaces each have a budget, one on bundle size and one on
honest numbers.

1. `make size` confirms the widget stays at or under 30KB gzipped with zero runtime
   dependencies, and the confirmation flow is never cut to fit (kill criterion 3).
2. Widget UI renders inside a shadow DOM with server-rendered nudge text and never
   writes an untrusted string through `innerHTML`.
3. The dashboard meets WCAG 2.2 AA, keeps interactive elements keyboard-reachable,
   and gives every chart a text alternative.
4. The dashboard renders numbers the server computes, never its own, and shows lift
   only with its confidence interval, never as a bare percentage (ADR-009).

## Latency budgets

A change on a hot path stays within the four budgets the README publishes, which
`make load` and the eval harness check.

1. The ingest gateway responds within 250ms at p99.
2. The stuck gate runs under 50ms, in-stream.
3. A policy decision returns within 2s at p95.
4. The first chat token arrives within 1.5s at p95.
