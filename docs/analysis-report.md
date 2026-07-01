# Analysis Report

Date: 2026-07-01

## 1 Tradeoff Analysis

Each Architecture Decision Record (ADR) in `docs/adr/` is weighed here against the
alternatives it rejected. The columns are the accepted option plus the rejected
alternatives named in that ADR, and the criteria are the forces `architecture.md`
and the ADR weigh. Scores run from 1 (worst) to 10 (best), weights sum up to 1.0,
and each total is the weighted sum of its column. The ADR holds the full
rationale, so the note under each matrix explains the decisive tradeoff rather
than restating the decision.

### 1.1 Server Architecture (ADR-001)

**Decision:** Build the server as a modular monolith, with the agent as a separate service.

| Criterion | Weight | Modulith | Microservices | Layered monolith |
|---|---|---|---|---|
| Deploy and ops surface for one developer | 0.30 | 9 | 2 | 9 |
| Boundary enforcement | 0.25 | 9 | 8 | 3 |
| Cross-module transactions | 0.20 | 9 | 3 | 9 |
| Extraction path later | 0.15 | 8 | 10 | 4 |
| Build speed in ten weeks | 0.10 | 8 | 3 | 9 |
| **Weighted total** | | **8.75** | **5.00** | **6.75** |

As a single deployable backed by a shared database, the modular monolith gives a
solo developer ordinary cross-module transactions, with the ArchUnit's
`ApplicationModules.verify()` supplying the boundary enforcement that microservices
otherwise charge network hops for. A layered monolith deploys just as cheaply but leaves nothing guarding
those boundaries, so they decay, while microservices answer a coordination
problem a solo project does not have.

### 1.2 Event Broker (ADR-002)

**Decision:** Run Redpanda, speaking the Kafka protocol everywhere.

| Criterion | Weight | Redpanda | MSK | RabbitMQ | Postgres queue |
|---|---|---|---|---|---|
| Replay (eval harness requirement) | 0.30 | 10 | 10 | 2 | 4 |
| Monthly cost at v1 traffic | 0.25 | 9 | 2 | 8 | 10 |
| Ops burden for one person | 0.20 | 8 | 9 | 6 | 9 |
| Kafka semantics, swappable broker | 0.15 | 10 | 10 | 3 | 2 |
| Local dev parity | 0.10 | 10 | 3 | 8 | 10 |
| **Weighted total** | | **9.35** | **7.10** | **5.05** | **6.80** |

Redpanda keeps full Kafka replay, consumer groups, and per-user ordering for the
cost of a single container, and replay is the line the eval harness cannot give
up. Amazon Managed Streaming for Apache Kafka (MSK) offers the same semantics but
costs an order of magnitude more than v1 traffic warrants. RabbitMQ is a queue,
not a replayable log, and a Postgres queue serves jobs, not a multi-consumer
stream with replay.

### 1.3 API Protocols (ADR-003)

**Decision:** Use REST for ingest, GraphQL for the dashboard, gRPC for the agent.

| Criterion | Weight | Per surface | GraphQL everywhere | REST everywhere | GraphQL federation |
|---|---|---|---|---|---|
| Widget bundle cost | 0.30 | 9 | 2 | 9 | 2 |
| Nested dashboard reads | 0.25 | 9 | 9 | 3 | 9 |
| Agent typing and streaming | 0.20 | 10 | 5 | 4 | 5 |
| Toolchain count | 0.15 | 4 | 8 | 9 | 3 |
| Codegen in both languages | 0.10 | 9 | 7 | 6 | 7 |
| **Weighted total** | | **8.45** | **5.75** | **6.20** | **5.00** |

REST keeps the ingest path, called from a 30KB browser bundle, clear of any
GraphQL parser. GraphQL answers the dashboard's nested reads in a single request,
and gRPC gives the agent typed bidirectional streaming. Together they cost three
toolchains, paid once in `make generate`. GraphQL everywhere burns the widget
budget on that parser, REST everywhere rebuilds GraphQL badly for the nested
reads, and GraphQL federation is a team-topology tool with no second team.

### 1.4 Vector Store (ADR-004)

**Decision:** Store embeddings in pgvector inside the main Postgres.

| Criterion | Weight | pgvector | Dedicated vector database |
|---|---|---|---|
| Tenant isolation reuse | 0.30 | 10 | 4 |
| Ops and backup surface | 0.25 | 9 | 5 |
| Cost at thousands of chunks per tenant | 0.20 | 10 | 4 |
| Scale ceiling | 0.15 | 5 | 10 |
| SQL joins to sources | 0.10 | 10 | 3 |
| **Weighted total** | | **9.00** | **5.05** |

pgvector stores embeddings beside the rest of the data, so tenant isolation falls
out of the same row-level security (RLS) policies as every other table, and
chunk-to-source lookups stay plain SQL joins. A dedicated vector database stands
up a second system with its own tenancy, auth, and bill. It pulls ahead only on
scale ceiling, the trigger ADR-004 names for revisiting once a corpus climbs
into the millions of chunks.

### 1.5 Intervention Pipeline (ADR-005)

**Decision:** Run a deterministic gate, then a model policy on the candidates it passes.

| Criterion | Weight | Gate + policy | Model per event | Gate only |
|---|---|---|---|---|
| Cost per monthly active user | 0.30 | 9 | 1 | 10 |
| Decision quality and context | 0.25 | 8 | 9 | 3 |
| Hot-path latency | 0.20 | 9 | 2 | 10 |
| Evaluability and tuning | 0.15 | 8 | 5 | 7 |
| Implementation effort | 0.10 | 5 | 7 | 9 |
| **Weighted total** | | **8.20** | **4.40** | **7.70** |

The gate-and-policy keeps model spend proportional to stuck users rather than raw traffic. A
tenant with 5,000 monthly active users (MAU) emits roughly 6M events a month, so
calling the model on every event kills the $0.05-per-MAU guardrail, while gating
on 1 to 3% of events holds cost under $0.015 per user. Gate-only stays cheap but
context-blind, unable to weigh the history, dismissals, and suppression the
policy reads before it acts.

### 1.6 Widget Push Channel (ADR-006)

**Decision:** Push to the widget over Server-Sent Events (SSE), and send upstream traffic as batched POST.

| Criterion | Weight | SSE | WebSocket | Long polling |
|---|---|---|---|---|
| Client code inside the 30KB budget | 0.30 | 9 | 5 | 7 |
| Proxy and firewall traversal | 0.25 | 9 | 5 | 9 |
| Reconnect and resume | 0.20 | 9 | 4 | 6 |
| Bridge to the agent's gRPC stream | 0.15 | 9 | 7 | 4 |
| Bidirectional multiplexing | 0.10 | 2 | 10 | 1 |
| **Weighted total** | | **8.30** | **5.60** | **6.25** |

SSE is a one-way channel over plain HTTP, so it clears corporate proxies, stays
small in the widget, and resumes a dropped stream through `Last-Event-ID` on its
own. WebSocket would hand-roll that reconnect and heartbeat inside the same 30KB
budget, and its only real edge, bidirectional multiplexing, carries the lowest
weight because upstream traffic already travels as batched POST. Long polling is
only what SSE already falls back to, at worse latency.

### 1.7 Event Data Capture (ADR-007)

**Decision:** Capture only default-deny allowlisted properties, with customer-hashed IDs.

| Criterion | Weight | Allowlist + hashes | Capture + redact | Configurable free text |
|---|---|---|---|---|
| Buyer trust, auditable claim | 0.30 | 9 | 3 | 2 |
| Compliance surface | 0.25 | 9 | 4 | 2 |
| Analytics expressiveness | 0.20 | 6 | 9 | 10 |
| Failure-mode safety | 0.15 | 9 | 3 | 2 |
| Debugging ease | 0.10 | 5 | 8 | 9 |
| **Weighted total** | | **8.00** | **4.95** | **4.30** |

A system that structurally cannot hold sensitive payloads makes a stronger buyer
claim than a promise not to look, so the default-deny allowlist beats
capture-then-redact, which leaks on the first missed pattern. The cost is
analytics reach, the criterion where configurable free-text capture scores
higher. ADR-007 pays it on purpose.

### 1.8 Action Execution Control (ADR-008)

**Decision:** Require registry validation and a ledgered user confirmation, with no auto-confirm.

| Criterion | Weight | Registry + confirm | Auto-execute "safe" | Prompt guardrails only |
|---|---|---|---|---|
| Injection blast radius | 0.30 | 10 | 4 | 2 |
| Auditable trust artifact | 0.25 | 9 | 6 | 3 |
| End-user friction | 0.20 | 5 | 9 | 9 |
| Engineering simplicity | 0.15 | 8 | 6 | 9 |
| Liability of a wrong write | 0.10 | 9 | 3 | 1 |
| **Weighted total** | | **8.35** | **5.70** | **4.60** |

A registry with user clicks forms a capability firewall outside the model, so
even fully steered output cannot fire an unregistered or unconfirmed action, and
every proposal and confirmation lands in the append-only ledger for the founder
to audit. Auto-executing "safe" actions cuts friction but lets the webhook's
author vouch for its own safety, and prompt-level guardrails are defense living
inside the thing being attacked.

### 1.9 Lift Measurement (ADR-009)

**Decision:** Measure lift against a per-tenant 10% holdout with bootstrap confidence intervals.

| Criterion | Weight | Holdout + CI | Before/after | Per-intervention A/B |
|---|---|---|---|---|
| Causal validity | 0.35 | 9 | 2 | 9 |
| Works at small MAU | 0.25 | 6 | 8 | 3 |
| Implementation cost | 0.20 | 8 | 9 | 4 |
| Answers the v1 question | 0.20 | 10 | 4 | 5 |
| **Weighted total** | | **8.25** | **5.30** | **5.70** |

A control group makes the lift causally valid, so the holdout and
per-intervention A/B tests clear a bar that before-and-after, confounded by
releases and seasonality, cannot. The holdout wins because it answers v1's first
question, whether intervening drives activation at all. Every
figure ships with a bootstrap confidence interval (CI). An A/B test ties on
validity but splits the small samples a low-MAU tenant has no room to divide.

### 1.10 Product Scope (ADR-010)

**Decision:** Skip the persona agent, and defer vision.

ADR-010 records scope abstentions rather than a technology choice. It rules out a
product persona because nothing in the domain needs a character, and it defers
vision until a canvas-heavy customer app leaves Document Object Model (DOM)
events unable to name the screen. Neither is a scored option, so this decision
carries no matrix, and ADR-010 holds the triggers that would reopen either.

### 1.11 Licensing (ADR-011)

**Decision:** License the platform AGPL-3.0, and the widget MIT.

| Criterion | Weight | AGPL + MIT widget | MIT everything | Open core (/ee) | Source-available | No license |
|---|---|---|---|---|---|---|
| Blocks a hosted clone | 0.30 | 9 | 1 | 7 | 9 | 10 |
| Embeds cleanly in customer bundles | 0.25 | 9 | 10 | 8 | 5 | 1 |
| Open-audit sales claim | 0.20 | 9 | 10 | 7 | 5 | 2 |
| Licensing upkeep for one developer | 0.15 | 9 | 10 | 4 | 6 | 9 |
| Future monetization flexibility | 0.10 | 7 | 5 | 9 | 8 | 9 |
| **Weighted total** | | **8.80** | **6.80** | **7.00** | **6.65** | **5.90** |

AGPL-3.0 on the platform blocks competitors from hosting this code as their own
service, while the MIT widget still drops into customer bundles and keeps the
ADR-007 audit claim enforceable. No single license
does both. MIT everywhere invites the clone with no recourse, an unlicensed repo
blocks the very embedding the widget needs, open core is licensing machinery with
no enterprise buyer to charge, and source-available terms are not approved by the
Open Source Initiative (OSI), which weakens the open-audit claim and drags out
legal review.

## 2 Risk Register

Risks are ordered by impact, each with a mitigation and a current status. The
first three are the project's falsifiable kill criteria.

| ID | Risk | Probability | Impact | Mitigation | Status |
|---|---|---|---|---|---|
| RSK-001 | Ten design-partner conversations within four weeks of v1, by 2026-09-18, produce zero installs (kill criterion 1). | Medium | High | Start outreach at the beginning of the build rather than at launch, and run through an early funnel screenshot, a public eval methodology post, and a first external install attempt. | Open |
| RSK-002 | An incumbent ships the full trio, stall detection with a timing policy, confirmed action execution, and holdout-measured lift, under $100/month before v1 (kill criterion 2). | Low | High | Track incumbent pricing. Reactive chat under $100 already exists (Intercom Fin from ~$49.50/month, Product Fruits Elvin from ~$96/month) without triggering the criterion, and the fallback reuses the pipeline, eval harness, and Java spine. | Open |
| RSK-003 | The widget cannot hold 30KB gzipped once the confirmation flow ships (kill criterion 3). | Low | High | Keep zero runtime dependencies, gate bundle size in continuous integration from the first widget build, and never cut the confirmation flow to fit. | Open |
| RSK-004 | The LLM judge drifts from human judgment (Cohen's kappa below 0.7), so groundedness numbers cannot be trusted. | Medium | High | Hand-verify 100 samples after each rubric or model change, and publish kappa and its date in the README. | Open |
| RSK-005 | Injected instructions in crawled docs steer the agent into an unwanted action. | Medium | High | Re-validate the action name and scope on the server regardless of model output, require a recorded confirmation before any webhook fires, and gate continuous integration on the 30-case adversarial suite. | Open |
| RSK-006 | A row-level security (RLS) gap leaks a customer's data to another. | Low | High | Cover every customer table with RLS, give the application role no BYPASSRLS, and block merge until an isolation test proves a cross-customer read fails. | Open |
| RSK-007 | The single Redpanda node fails and drops in-flight events. | Medium | Medium | Batch and retry client-side in the widget, dedupe on each event's unique ID to make redelivery safe, and document recovery and the MSK trigger in the runbook. | Accepted (ADR-002) |
| RSK-008 | Token use or provider pricing drifts past $0.05 per MAU. | Medium | Medium | Cap model calls at 1 to 3% of events with the gate, run a small model in the policy node, and graph cost per decision from Langfuse traces. | Open |
