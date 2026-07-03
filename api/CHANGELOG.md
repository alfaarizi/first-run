# API changelog

Each entry records the date, the change, the rationale, and whether it is
additive or breaking.

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
