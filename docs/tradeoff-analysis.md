# Tradeoff Analysis

Date: 2026-07-06

Each matrix below weighs the decision in one Architecture Decision Record (ADR)
against the alternatives it rejected. The columns are the accepted option and those
alternatives, the rows are the forces `architecture.md` and the ADR consider, and
each score runs from 1 (worst) to 10 (best) against weights that sum to 1.0. A
column total is the weighted sum of its scores. The ADR holds the full rationale,
so the note under each matrix gives only the decisive tradeoff.

## Server architecture (ADR-001)

The server is a modular monolith, and the agent runs as a separate service.

| Criterion | Weight | Modulith | Microservices | Layered monolith |
|---|:---:|:---:|:---:|:---:|
| Deploy and ops surface for one developer | 0.30 | 9 | 2 | 9 |
| Boundary enforcement | 0.25 | 9 | 8 | 3 |
| Cross-module transactions | 0.20 | 9 | 3 | 9 |
| Extraction path later | 0.15 | 8 | 10 | 4 |
| Build speed in ten weeks | 0.10 | 8 | 3 | 9 |
| **Weighted total** | | **8.75** | **5.00** | **6.75** |

The modular monolith is a single deployable on a shared database, so a solo
developer keeps ordinary cross-module transactions, and ArchUnit with
`ApplicationModules.verify()` supplies the boundary enforcement microservices
otherwise pay for in network hops. A layered monolith deploys just as cheaply but
guards nothing, so its boundaries decay. Microservices solve a coordination problem
a solo project does not have.

## Event broker (ADR-002)

The broker is Redpanda, and every client speaks the Kafka protocol.

| Criterion | Weight | Redpanda | MSK | RabbitMQ | Postgres queue |
|---|:---:|:---:|:---:|:---:|:---:|
| Replay (eval harness requirement) | 0.30 | 10 | 10 | 2 | 4 |
| Monthly cost at v1 traffic | 0.25 | 9 | 2 | 8 | 10 |
| Ops burden for one person | 0.20 | 8 | 9 | 6 | 9 |
| Kafka semantics, swappable broker | 0.15 | 10 | 10 | 3 | 2 |
| Local dev parity | 0.10 | 10 | 3 | 8 | 10 |
| **Weighted total** | | **9.35** | **7.10** | **5.05** | **6.80** |

Redpanda keeps full Kafka replay, consumer groups, and per-user ordering for the
cost of one container, and replay is the one thing the eval harness cannot lose.
Amazon Managed Streaming for Apache Kafka (MSK) offers the same semantics but costs
an order of magnitude more than v1 traffic warrants. RabbitMQ is a queue, not a
replayable log, and a Postgres queue serves jobs, not a multi-consumer stream with
replay.

## API protocols (ADR-003)

REST carries ingest, GraphQL serves the dashboard, and gRPC connects the agent.

| Criterion | Weight | Per surface | GraphQL everywhere | REST everywhere | GraphQL federation |
|---|:---:|:---:|:---:|:---:|:---:|
| Widget bundle cost | 0.30 | 9 | 2 | 9 | 2 |
| Nested dashboard reads | 0.25 | 9 | 9 | 3 | 9 |
| Agent typing and streaming | 0.20 | 10 | 5 | 4 | 5 |
| Toolchain count | 0.15 | 4 | 8 | 9 | 3 |
| Codegen in both languages | 0.10 | 9 | 7 | 6 | 7 |
| **Weighted total** | | **8.45** | **5.75** | **6.20** | **5.00** |

REST keeps the ingest path, called from a 30KB browser bundle, clear of any GraphQL
parser. GraphQL answers the dashboard's nested reads in one request, and gRPC gives
the agent typed bidirectional streaming. Together they cost three toolchains, and
`make generate` pays that cost once. GraphQL everywhere burns the widget budget on
that parser, REST everywhere rebuilds GraphQL badly for the nested reads, and
GraphQL federation is a team-topology tool with no second team.

## Vector store (ADR-004)

Embeddings live in pgvector inside the main Postgres.

| Criterion | Weight | pgvector | Dedicated vector database |
|---|:---:|:---:|:---:|
| Tenant isolation reuse | 0.30 | 10 | 4 |
| Ops and backup surface | 0.25 | 9 | 5 |
| Cost at thousands of chunks per tenant | 0.20 | 10 | 4 |
| Scale ceiling | 0.15 | 5 | 10 |
| SQL joins to sources | 0.10 | 10 | 3 |
| **Weighted total** | | **9.00** | **5.05** |

pgvector stores embeddings beside the rest of the data, so tenant isolation falls
out of the same row-level security (RLS) policies as every other table, and
chunk-to-source lookups stay plain SQL joins. A dedicated vector database stands up
a second system with its own tenancy, auth, and bill. It leads only on scale
ceiling, the trigger ADR-004 names for a revisit once a corpus reaches millions of
chunks.

## Intervention pipeline (ADR-005)

A deterministic gate runs first, and the model policy runs only on the candidates it
passes.

| Criterion | Weight | Gate + policy | Model per event | Gate only |
|---|:---:|:---:|:---:|:---:|
| Cost per monthly active user | 0.30 | 9 | 1 | 10 |
| Decision quality and context | 0.25 | 8 | 9 | 3 |
| Hot-path latency | 0.20 | 9 | 2 | 10 |
| Evaluability and tuning | 0.15 | 8 | 5 | 7 |
| Implementation effort | 0.10 | 5 | 7 | 9 |
| **Weighted total** | | **8.20** | **4.40** | **7.70** |

The gate keeps model spend proportional to stuck users, not raw traffic. A tenant
with 5,000 monthly active users (MAU) emits roughly 6M events a month, so calling
the model on every event breaks the $0.05-per-MAU guardrail, while gating on 1 to 3%
of events holds cost under $0.015 per user. A gate alone stays cheap but
context-blind, unable to weigh the history, dismissals, and suppression the policy
reads before it acts.

## Widget push channel (ADR-006)

The widget takes pushes over Server-Sent Events (SSE) and sends upstream traffic as
batched POST.

| Criterion | Weight | SSE | WebSocket | Long polling |
|---|:---:|:---:|:---:|:---:|
| Client code inside the 30KB budget | 0.30 | 9 | 5 | 7 |
| Proxy and firewall traversal | 0.25 | 9 | 5 | 9 |
| Reconnect and resume | 0.20 | 9 | 4 | 6 |
| Bridge to the agent's gRPC stream | 0.15 | 9 | 7 | 4 |
| Bidirectional multiplexing | 0.10 | 2 | 10 | 1 |
| **Weighted total** | | **8.30** | **5.60** | **6.25** |

SSE is a one-way channel over plain HTTP, so it clears corporate proxies, stays
small in the widget, and resumes a dropped stream through `Last-Event-ID` on its
own. WebSocket would hand-roll that reconnect and heartbeat inside the same 30KB
budget, and its one real edge, bidirectional multiplexing, carries the lowest weight
because upstream traffic already travels as batched POST. Long polling is only what
SSE falls back to already, at worse latency.

## Event data capture (ADR-007)

Capture is a default-deny allowlist, and end-user IDs arrive as customer-supplied
hashes.

| Criterion | Weight | Allowlist + hashes | Capture + redact | Configurable free text |
|---|:---:|:---:|:---:|:---:|
| Buyer trust, auditable claim | 0.30 | 9 | 3 | 2 |
| Compliance surface | 0.25 | 9 | 4 | 2 |
| Analytics expressiveness | 0.20 | 6 | 9 | 10 |
| Failure-mode safety | 0.15 | 9 | 3 | 2 |
| Debugging ease | 0.10 | 5 | 8 | 9 |
| **Weighted total** | | **8.00** | **4.95** | **4.30** |

A system that structurally cannot hold sensitive payloads makes a stronger buyer
claim than a promise not to look, so the default-deny allowlist beats
capture-then-redact, which leaks on the first missed pattern. The cost is analytics
reach, the one criterion where configurable free-text capture scores higher. ADR-007
pays it on purpose.

## Action execution control (ADR-008)

Every action clears registry validation and a ledgered user confirmation, with no
auto-confirm.

| Criterion | Weight | Registry + confirm | Auto-execute "safe" | Prompt guardrails only |
|---|:---:|:---:|:---:|:---:|
| Injection blast radius | 0.30 | 10 | 4 | 2 |
| Auditable trust artifact | 0.25 | 9 | 6 | 3 |
| End-user friction | 0.20 | 5 | 9 | 9 |
| Engineering simplicity | 0.15 | 8 | 6 | 9 |
| Liability of a wrong write | 0.10 | 9 | 3 | 1 |
| **Weighted total** | | **8.35** | **5.70** | **4.60** |

A registry plus a user click forms a capability firewall outside the model, so even
fully steered output cannot fire an unregistered or unconfirmed action, and every
proposal and confirmation lands in the append-only ledger for the founder to audit.
Auto-executing "safe" actions cuts friction but lets the webhook's author vouch for
its own safety, and prompt-level guardrails are defense that lives inside the thing
being attacked.

## Lift measurement (ADR-009)

Lift is measured against a per-tenant 10% holdout, reported with a bootstrap
confidence interval (CI).

| Criterion | Weight | Holdout + CI | Before/after | Per-intervention A/B |
|---|:---:|:---:|:---:|:---:|
| Causal validity | 0.35 | 9 | 2 | 9 |
| Works at small MAU | 0.25 | 6 | 8 | 3 |
| Implementation cost | 0.20 | 8 | 9 | 4 |
| Answers the v1 question | 0.20 | 10 | 4 | 5 |
| **Weighted total** | | **8.25** | **5.30** | **5.70** |

A control group makes lift causally valid, so both the holdout and the
per-intervention A/B test beat before-and-after, which releases and seasonality
confound. The holdout wins because it answers v1's first question, whether
intervening drives activation at all, and every figure carries its CI. An A/B test
matches the holdout on validity but splits the small samples a low-MAU tenant has no
room to divide.

## Product scope (ADR-010)

FirstRun skips the persona agent and defers vision. ADR-010 records these as scope
abstentions rather than a technology choice, so neither is a scored option and this
section carries no matrix. It rules out a persona because nothing in the domain
needs a character, and it defers vision until a canvas-heavy customer app leaves
Document Object Model (DOM) events unable to name the screen. ADR-010 holds the
triggers that would reopen either.

## Licensing (ADR-011)

The platform is licensed AGPL-3.0 and the widget MIT.

| Criterion | Weight | AGPL + MIT widget | MIT everything | Open core (/ee) | Source-available | No license |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Blocks a hosted clone | 0.30 | 9 | 1 | 7 | 9 | 10 |
| Embeds cleanly in customer bundles | 0.25 | 9 | 10 | 8 | 5 | 1 |
| Open-audit sales claim | 0.20 | 9 | 10 | 7 | 5 | 2 |
| Licensing upkeep for one developer | 0.15 | 9 | 10 | 4 | 6 | 9 |
| Future monetization flexibility | 0.10 | 7 | 5 | 9 | 8 | 9 |
| **Weighted total** | | **8.80** | **6.80** | **7.00** | **6.65** | **5.90** |

AGPL-3.0 on the platform blocks a competitor from hosting this code as its own
service, while the MIT widget still drops into customer bundles and keeps the
ADR-007 audit claim enforceable. No single license does both. MIT everywhere invites
the clone with no recourse, an unlicensed repo blocks the very embedding the widget
needs, open core is licensing machinery with no enterprise buyer to charge, and
source-available terms are not approved by the Open Source Initiative (OSI), which
weakens the open-audit claim and drags out legal review.

## Null safety (ADR-012)

Nullness is declared with JSpecify annotations and enforced by NullAway inside build.

| Criterion | Weight | NullAway + JSpecify | Checker Framework | IDE-only JDT | No checking |
|---|:---:|:---:|:---:|:---:|:---:|
| CI guarantee for every editor | 0.30 | 9 | 9 | 2 | 1 |
| NPE coverage and soundness | 0.25 | 8 | 10 | 5 | 1 |
| Build-time overhead | 0.20 | 8 | 4 | 10 | 10 |
| Annotation and adoption burden | 0.15 | 8 | 4 | 6 | 10 |
| Ecosystem alignment with Spring | 0.10 | 10 | 6 | 4 | 3 |
| **Weighted total** | | **8.50** | **7.20** | **5.15** | **4.35** |

NullAway turns a null-safety promise into a compile error inside `./mvnw verify`,
so the guarantee holds for CI and every editor at a cheaper build cost. Checker Framework wins only on soundness, where its heavier annotation burden buys. 
An IDE-only analysis guards a single editor and gates nothing, and no checking 
at all leaves the NPE class to production.
