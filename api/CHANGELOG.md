# API changelog

Each entry records the date, the change, the rationale, and whether it is
additive or breaking.

## 2026-07-07

### Added

- `CreateMilestoneInput.name` describes its grammar: the ingest contract's
  custom-event form (snake_case past tense, at most 64 characters), with
  `fr.` reserved for auto-captured events.
- `createMilestone` semantics the SDL cannot carry: names and positions are
  unique per app, an app holds at most 10 milestones (the glossary's upper
  bound), and an app outside the requesting tenant resolves to not found.
  Violations arrive as top-level GraphQL errors, never inside the payload.
- The dashboard GraphQL endpoint reads its tenant from the
  `X-FirstRun-Tenant` header until login ships, and a request without it
  fails as unauthorized.

Additive. The mutation and payload keep the shape defined on 2026-07-02.

## 2026-07-06

### Added

- `events.raw` record contract: the gateway wraps each accepted event in an
  envelope that adds `tenant_id`, `app_id`, `received_at`, and the truncated
  client `ip`, serialized snake_case and keyed by the hex SHA-256 of
  `tenant_id:end_user_hash`. The envelope `timestamp` carries the event time
  corrected by the client clock skew `sent_at` reveals, the recipe the
  ingest contract already promised. Failed consumption dead-letters to
  `<topic>.dlq`.

Additive. `POST /v1/e` keeps its shape, and this pins what stream consumers
may rely on before the first one ships.

## 2026-07-02

### Added

- `proto/firstrun/v1/`: `InterventionPolicyService`, `ConversationService`
  (bidirectional streaming), and `KnowledgeService`.
- `graphql/`: dashboard queries and mutations, one `.graphqls` file per
  server module with the roots in `schema.graphqls`.
- `openapi/ingest.yaml`: `POST /v1/e` for widget event batches.
- `openapi/webhooks.yaml`: signed outbound action deliveries.

Defines every surface before its first consumer, so stubs generate against a
contract that already passes lint and breaking checks. Java stubs share the
server's `com.firstrunhq` root, reversed from firstrunhq.com.
