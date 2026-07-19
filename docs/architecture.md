# FirstRun Architecture

Date: 2026-07-01

FirstRun reads the event stream from a customer's product, finds users stalled in
onboarding, and answers them with nudges and chat grounded in the product's own
docs, running a setup action only after the user confirms. A Java modular monolith
(`server/`) owns the product logic, and a single Python service (`agent/`) owns the
model loop. A vanilla TypeScript widget embeds in the customer's app, and Redpanda
carries the event stream. Every structural choice traces to a decision in
`docs/adr/`, so read those before changing one.

## System Context

```mermaid
---
config:
  theme: base
  flowchart:
    curve: linear
---
flowchart LR
  subgraph T["System Context for FirstRun"]
    direction LR
    EU("End User<br/>Onboards inside the customer's app.")
    FO("Founder<br/>Configures milestones, reads the dashboard.")
    FR("FirstRun<br/>Detects stuck users, nudges with cited answers.")
    LLM("LLM Provider<br/><i>Anthropic chat, Voyage embeddings</i><br/>Scores policy, writes answers.")
    CUST("Customer API<br/>Runs confirmed actions.")
    STR("Stripe<br/>Meters usage for billing.")
    LF("Langfuse<br/>Stores agent traces.")
    EU -->|"Receives nudges, asks questions"| FR
    FO -->|"Configures, reads dashboard"| FR
    FR -->|"Requests policy, answers"| LLM
    FR -->|"Posts confirmed actions"| CUST
    FR -->|"Reports usage"| STR
    FR -->|"Sends traces"| LF
  end
  classDef box fill:#438DD5,stroke:#3C7FC0,color:#FFFFFF
  classDef external fill:#999999,stroke:#8A8A8A,color:#FFFFFF
  class EU,FO,FR box
  class LLM,CUST,STR,LF external
  style T fill:#FFFFFF,stroke:#FFFFFF,color:#000000
```

Two kinds of people use FirstRun. End users only ever see it as the widget embedded
in the product and never hold an account with us, while founders sign in to the
dashboard to define milestones and read activation lift. The other four systems are
dependencies the platform calls out to for model inference, action webhooks,
billing, and tracing.

## Containers

```mermaid
---
config:
  theme: base
  flowchart:
    curve: linear
---
flowchart LR
  subgraph T["Containers for FirstRun"]
    direction LR
    subgraph FR[" FirstRun "]
      WID("Widget SDK<br/><i>vanilla TS, 30KB</i><br/>Captures events, renders nudges.")
      WEB("Web App<br/><i>React</i><br/>Shows the founder dashboard.")
      SRV("Server<br/><i>Java, modular monolith</i><br/>Runs ingest, funnel, ledger, actions, billing.")
      AGT("Agent<br/><i>Python, FastAPI + LangGraph</i><br/>Runs policy, chat, retrieval, and indexing.")
      PG[("Postgres + pgvector<br/><i>Row-level security (RLS) by tenant</i><br/>Stores all data and embeddings.")]
      RDS[("Redis<br/>Holds session state and dedupe keys.")]
      RP[("Redpanda<br/><i>Kafka protocol</i><br/>Carries the event stream.")]
    end
    LLM("LLM Provider<br/><i>Anthropic chat, Voyage embeddings</i><br/>Scores policy, writes answers.")
    CUST("Customer API<br/>Runs confirmed actions.")
    STR("Stripe<br/>Meters usage for billing.")
    LF("Langfuse<br/>Stores agent traces.")
    WEB -->|"Queries, GraphQL"| SRV
    WID -->|"Sends events, POST /v1/e"| SRV
    SRV -->|"Pushes nudges, SSE"| WID
    SRV <-->|"Calls, gRPC"| AGT
    SRV -->|"Reads/writes, JDBC"| PG
    SRV -->|"Caches state"| RDS
    SRV <-->|"Streams, Kafka"| RP
    SRV -->|"Posts webhook"| CUST
    SRV -->|"Reports usage"| STR
    AGT -->|"Reads/writes, pgvector"| PG
    AGT -->|"Calls, HTTPS"| LLM
    AGT -->|"Sends traces"| LF
  end
  classDef box fill:#438DD5,stroke:#3C7FC0,color:#FFFFFF
  classDef external fill:#999999,stroke:#8A8A8A,color:#FFFFFF
  class WID,WEB,SRV,AGT,PG,RDS,RP box
  class LLM,CUST,STR,LF external
  style T fill:#FFFFFF,stroke:#FFFFFF,color:#000000
  style FR fill:none,stroke:#444444,stroke-dasharray:5 5,color:#000000
```

The split between the server and the agent follows runtime forces, not architectural
layers. The server is a single deployable that owns every piece of stateful product
logic, and the agent is separate only because its model work needs Python (ADR-001).
Each boundary then uses the cheapest protocol that fits its load (ADR-003):

- REST carries high-volume ingest and outbound webhooks.
- GraphQL serves the dashboard's nested reads.
- gRPC connects the server and agent over a typed, streaming channel.

## Components

```mermaid
---
config:
  theme: base
  flowchart:
    curve: linear
---
flowchart LR
  subgraph T["Components for FirstRun"]
    direction LR
    subgraph SRV[" Server (Java, modular monolith) "]
      direction TB
      API("API layer<br/><i>GraphQL + REST</i><br/>Single entry point for clients.")
      IDN("identity<br/>Manages tenants, login, and role-based access control (RBAC).")
      APP("apps<br/>Holds SDK keys and allowlists.")
      ING("ingestion<br/>Validates and produces events.")
      FUN("funnel<br/>Tracks milestones and the stuck gate.")
      DEC("decisioning<br/>Sends candidates to the agent.")
      ACT("actions<br/>Registers, confirms, runs actions.")
      LED("ledger<br/>Stores decisions and outcomes.")
      KNW("knowledge<br/>Manages doc sources, triggers reindex.")
      ANA("analytics<br/>Computes outcomes, holdout, lift.")
      BIL("billing<br/>Meters usage to Stripe.")
      NOT("notifications<br/>Sends Slack and email alerts.")
    end
    subgraph AGT[" Agent (Python, FastAPI + LangGraph) "]
      direction TB
      GAPI("API layer<br/><i>gRPC</i><br/>Serves Decide, Converse, Reindex.")
      GRAPH("graph<br/><i>LangGraph</i><br/>policy, retrieve, answer, propose_action, escalate.")
      IDX("indexing<br/>Crawls, chunks, embeds docs.")
      RET("retrieval<br/>Searches pgvector for chunks.")
      MCP("mcp<br/>Exposes graph tools over MCP.")
    end
    API -->|"Calls"| IDN & APP & FUN & LED & ANA & ACT & KNW
    API ~~~ ING
    API ~~~ NOT
    ING -.->|"Publishes events"| FUN
    FUN -.->|"Publishes candidates"| DEC
    DEC -->|"Appends decisions"| LED
    ACT -->|"Appends outcomes"| LED
    ANA -->|"Feeds usage"| BIL
    ANA -->|"Reads facts"| LED
    NOT -.->|"Listens for outcomes"| ANA
    DEC -->|"gRPC"| GAPI
    GAPI -->|"Invokes"| GRAPH
    GAPI -->|"Reindexes"| IDX
    GRAPH -->|"Retrieves chunks"| RET
    GRAPH -.->|"Shares tools"| MCP
  end
  classDef box fill:#438DD5,stroke:#3C7FC0,color:#FFFFFF
  class API,IDN,APP,ING,FUN,DEC,ACT,LED,KNW,ANA,BIL,NOT,GAPI,GRAPH,IDX,RET,MCP box
  style T fill:#FFFFFF,stroke:#FFFFFF,color:#000000
  style SRV fill:none,stroke:#444444,stroke-dasharray:5 5,color:#000000
  style AGT fill:none,stroke:#444444,stroke-dasharray:5 5,color:#000000
```

Inside the server, modules see each other only through published interfaces or
domain events, and ArchUnit with `ApplicationModules.verify()` enforces that in
continuous integration, where a broken boundary is a design defect. Three
constraints carry the most weight:

- The `ledger` is append-only, so no repository method may update or delete its
  rows.
- Only `analytics` and the API layer may import `billing`.
- `ingestion` stays allocation-light and never touches GraphQL types.

The Agent group is the other deployable, where the policy node uses a small model
and the answer node the stronger one, both at the external provider and swappable by
configuration. Because the agent holds the only provider access point, it also owns
the document index. On `Reindex` it crawls, chunks, and embeds a customer's docs
into pgvector, and retrieval reads them back at answer time. It parses every output
into a Pydantic model before returning it, so a malformed response or a provider
outage becomes a hold, and the product degrades to silence rather than a wrong
answer.

## Runtime

```mermaid
---
config:
  theme: base
  themeVariables:
    background: '#ffffff'
    titleColor: '#000000'
    actorBkg: '#438DD5'
    actorBorder: '#3C7FC0'
    actorTextColor: '#FFFFFF'
---
sequenceDiagram
  autonumber
  box rgb(255,255,255) Runtime for FirstRun
    participant U as End User
    participant W as Widget SDK
    participant S as Server
    participant K as Redpanda
    participant A as Agent
    participant C as Customer API
  end

  U->>W: Clicks, pageviews, fr.track()
  W->>S: POST /v1/e (HMAC, batched)
  S->>K: Produce events.raw (dedupe)
  K->>S: Consume
  S->>S: Advance milestones, run stuck gate (under 50ms)
  alt Gate fires
    S->>A: Decide(candidate, features, suppression)
    A-->>S: intervene | hold (+ reason)
    S->>S: Append decision to ledger (holds too)
    opt intervene
      S-->>W: Push nudge (SSE)
      U->>W: Expand nudge, ask question
      W->>A: Converse (Server bridges SSE to gRPC)
      A-->>W: Answer + citations (+ action proposal)
      U->>W: Click Confirm
      W->>S: Confirm action
      S->>S: Validate name and scope
      S->>C: Signed webhook (retry x5, breaker)
      S->>S: Append outcome to ledger
    end
  end
```

A deterministic gate, not the model, decides which events are worth a policy call,
so cost stays tied to stuck users instead of raw traffic (ADR-005). Every candidate
that clears the gate produces exactly one ledgered decision, intervene or hold, and
no webhook fires until the user clicks Confirm. Four budgets bound the path, and the
README publishes them while `make load` and the eval harness check them:

- The ingest gateway responds within 250ms at p99.
- The stuck gate runs under 50ms, in-stream.
- A policy decision returns within 2s at p95.
- The first chat token arrives within 1.5s at p95.

## Event Stream

The loop is asynchronous underneath, and each topic feeds its own consumer group
with its own dead-letter queue:

| Topic | Producer | Consumers (groups) | Key |
|---|---|---|---|
| `events.raw` | ingestion | stream-processor, archival, eval-capture | `hash(tenant_id, end_user_id)`, for per-user ordering |
| `events.enriched` | stream processor | analytics | adds session and milestone context |
| `intervention.candidates` | stuck gate | decisioning | only gated events, so model calls stay rare (ADR-005) |
| `intervention.outcomes` | server | analytics, billing-metering | engaged, dismissed, ignored, or attributed |
| `*.dlq` | each consumer | replay tooling | one per consumer |

Because delivery is at-least-once, every handler is idempotent and dedupes on the
event's unique ID by holding a Redis key for 24 hours. Replay tooling reprocesses
any dead-letter queue after a fix, and the eval harness replays `events.raw`
through its own group.

## Data Model

```mermaid 
---
title: Data Model for FirstRun
config:
  theme: base
  themeVariables:
    lineColor: '#FFFFFF'
    textColor: '#FFFFFF'
---
erDiagram
  TENANTS ||--o{ APPS : owns
  TENANTS ||--o{ SUBSCRIPTIONS : has
  SUBSCRIPTIONS ||--o{ USAGE_RECORDS : meters
  APPS ||--o{ END_USERS : has
  APPS ||--o{ MILESTONES : defines
  APPS ||--o{ DOC_SOURCES : indexes
  APPS ||--o{ ACTIONS : registers
  END_USERS ||--o{ SESSIONS : opens
  END_USERS ||--o{ MILESTONE_PROGRESS : tracks
  END_USERS ||--o{ MEMORY_SUMMARIES : remembers
  SESSIONS ||--o{ EVENTS : contains
  MILESTONES ||--o{ MILESTONE_PROGRESS : measured_by
  DOC_SOURCES ||--o{ DOC_CHUNKS : chunked_into
  END_USERS ||--o{ INTERVENTIONS : receives
  INTERVENTIONS ||--o| INTERVENTION_OUTCOMES : resolves_to
  INTERVENTIONS ||--o{ CONVERSATIONS : may_open
  CONVERSATIONS ||--o{ MESSAGES : contains
  ACTIONS ||--o{ ACTION_EXECUTIONS : runs
  INTERVENTIONS ||--o{ ACTION_EXECUTIONS : proposed_in

  APPS {
    uuid id PK
    uuid tenant_id FK
    string sdk_public_key
    jsonb allowlist
  }
  END_USERS {
    uuid id PK
    uuid app_id FK
    string external_hash
    bool holdout
  }
  EVENTS {
    uuid id PK
    uuid session_id FK
    string name
    jsonb props
    timestamptz ts
  }
  MILESTONE_PROGRESS {
    uuid end_user_id FK
    uuid milestone_id FK
    string state
    timestamptz ts
  }
  DOC_CHUNKS {
    uuid id PK
    uuid source_id FK
    vector embedding
    text content
    string source_url
  }
  INTERVENTIONS {
    uuid id PK
    string decision
    string reason
    timestamptz shown_at
  }
  ACTION_EXECUTIONS {
    uuid id PK
    string status
    int attempts
  }

classDef default fill:#438DD5,stroke:#3C7FC0,color:#000000
```

The whole schema lives in one Postgres instance, embeddings included, to avoid a
second datastore (ADR-004). A few conventions hold throughout:

- Keys are UUIDv7 (RFC 9562), and timestamps are UTC `timestamptz`.
- The `events` table is partitioned by day.
- Every tenant-scoped table carries a row-level security (RLS) policy,
  `USING (tenant_id = current_setting('app.tenant_id')::uuid)`.
- The `end_users.holdout` flag is a deterministic 10% hash bucket, set once at
  first sight and never changed (ADR-009).

## Deployment

```mermaid
---
config:
  theme: base
  flowchart:
    curve: linear
---
flowchart LR
  subgraph T["Deployment for FirstRun"]
    direction LR
    subgraph BR[" Browser "]
      WID("Widget SDK<br/><i>vanilla TS, 30KB</i>")
      WEB("Web App<br/><i>React SPA</i>")
    end
    subgraph AWS[" AWS (Terraform, infra/) "]
      CDN("CloudFront + S3<br/>Static bundles")
      subgraph ECS[" ECS Fargate "]
        SRV("Server<br/><i>Java, modular monolith</i>")
        AGT("Agent<br/><i>Python, FastAPI + LangGraph</i>")
      end
      PG[("RDS Postgres<br/>+ pgvector")]
      RP[("Redpanda node")]
      RDS[("Redis")]
    end
    CDN -->|"Serves bundles"| WID & WEB
    WID <-->|"HTTPS, SSE"| SRV
    WEB -->|"HTTPS, GraphQL"| SRV
    SRV <-->|"gRPC"| AGT
    SRV --> PG
    SRV --> RDS
    SRV --> RP
    AGT --> PG
  end
  classDef box fill:#438DD5,stroke:#3C7FC0,color:#FFFFFF
  classDef boundary fill:none,stroke:#444444,stroke-dasharray:5 5,color:#000000
  class WID,WEB,CDN,SRV,AGT,PG,RP,RDS box
  class AWS,ECS,BR boundary
  style T fill:#FFFFFF,stroke:#FFFFFF,color:#000000
```

The same code runs in two places:

- Locally, a single `docker compose up` brings up Postgres with pgvector, Redis,
  Redpanda, Langfuse, and the server, agent, web, and Tasklet.
- In production, ECS Fargate runs the server and agent beside RDS Postgres,
  Redis, and a single Redpanda node, while CloudFront and S3 serve the widget
  and dashboard bundles. `infra/` Terraform defines it all, and GitHub Actions
  deploys from main.

A request keeps one trace from the widget through to the agent over W3C Trace
Context in Kafka headers, and Prometheus and Grafana run in both environments.
Two guards keep the single-node footprint safe:

- The gateway sheds traffic per tenant with a Redis token bucket before anything
  reaches Kafka, so one noisy tenant cannot starve the rest.
- On shutdown, the services drain their Kafka consumers and in-flight server-sent
  event streams within the 30-second ECS stop grace period.
