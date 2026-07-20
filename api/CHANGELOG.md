# API changelog

Each entry records the date, the change, the rationale, and whether it is
additive or breaking.

## 2026-07-20

### Changed

- `openapi/messages.yaml` documents `413` for a body over 16 KB, aligning
  the endpoint with the ingest contract and RFC 9110's Content Too Large.
  The gateway answers it before the signature check, because the HMAC
  covers the full body and a capped read could never verify, which had
  misreported honest oversized requests as `401`.

Additive. A response code clients already had to survive gains its correct
value. Nothing that verified before answers differently.

## 2026-07-19

### Added

- `openapi/messages.yaml`: `POST /v1/messages` accepts one end-user chat
  message from the widget, signed with the ingest headers over
  `{timestamp}.{raw body}`. The body carries a client-generated UUIDv7 `id`
  (the Segment `messageId` pattern the ingest events already follow) that
  ties the answer's stream frames to the message, and an optional `ref`
  naming the nudge whose expansion opened the conversation, mirroring the
  `ref` on intervention events. The answer never rides the response: it
  streams over `/v1/stream` as `token` frames, then one `done` frame with
  citations, at most one `action` frame per answer, all frame names the
  stream contract already reserved. A holdout user's question is answered
  like any other, because the holdout suppresses proactive nudges, not a
  question the user asks first.

Additive. The stream contract keeps its shape, and its `token`, `done`, and
`action` frames gain their producer.

### Changed

- `openapi/stream.yaml` documents that `token` and `done` frame data carries
  the `message_id` of the message it answers, making the binding
  `messages.yaml` promises on `id` explicit on the wire. The widget uses it
  to render only the answer to the question it sent, since frames fan out to
  every tab the user has open.
- `proto/firstrun/v1/conversation.proto` `AnswerDone` gains `failed`, set
  when an answer died mid-stream. The gateway degrades already-streamed
  tokens to the retry line instead of closing them as a complete cited
  answer, and the agent keeps the truncated turn out of conversation
  history.

Additive. The frames and the done message gain fields no client read
before.

### Added

- `proto/firstrun/v1/knowledge.proto` `Reindex` semantics the proto cannot
  carry. A call for a source whose crawl is still running answers
  `ALREADY_RUNNING`. A finished crawl sweeps every older crawl's chunks in
  one delete, so retrieval mixes old and new chunks only while a crawl runs.
  A crawl that fails or writes no chunks discards its own rows, marks the
  source `FAILED`, and keeps the previous index live, and one transient page
  failure fails the crawl rather than publishing an incomplete index. Rows
  from a crawl a killed agent never finished persist only until the next
  crawl starts and sweeps every generation but the recorded live one.
  Chunks embed at 1024 dimensions under an inner-product HNSW index
  (ADR-013).

### Changed

- `api/openapi/stream.yaml` gives `last_event_id` replay semantics: a
  reconnect carrying it replays the `nudge` frames a bounded per-user buffer
  held while the stream was down, an unknown id or the reserved `earliest`
  replays the whole buffer, and the `Last-Event-ID` request header takes
  precedence over the query parameter (all three from the Mercure spec). The
  buffer holds 8 frames for 30 minutes, matching the widget's session idle
  window, so a nudge never resurfaces in a later session. Delivery stays
  at-most-once: a nudge is claimed once buffered, and one that expires
  unreplayed is dropped, not retried.
- `api/openapi/stream.yaml` documents that the widget sends `earliest` on a
  first connect, so a nudge buffered before the stream opened replays instead
  of waiting for a later reconnect, and that the cursor rides in the query
  string so the browser's own reconnect (WHATWG) carries it before any frame
  seeds `Last-Event-ID`. A cursor outside `^[0-9a-z-]{1,64}$` can never name
  a frame and reads as absent.

Additive. The parameter existed and was ignored, so clients that never send
it keep a live-only stream. The widget's choice to always send it is a
client behavior, not a wire change. The knowledge entry pins behavior of an RPC
nothing calls yet, so no wire shape changes there either.

## 2026-07-17

### Added

- `api/openapi/stream.yaml` adds the terminal `retired` frame. When a newer
  stream for the same end user passes the per-user cap, the retired stream
  receives it before closing, and the client must not reconnect. Without it,
  `EventSource` reconnects on every close (WHATWG), so nine open tabs evict
  each other in an endless rotation (the Firebase REST streaming `cancel` /
  `auth_revoked` pattern).

Additive. Existing clients ignore unknown named events.

## 2026-07-16

### Added

- `api/openapi/stream.yaml` documents `GET /v1/stream`, the widget's SSE
  channel the 2026-07-15 entry pinned. The gateway serves the `nudge` frame,
  whose data carries `id` and `text`, and accepts `last_event_id`. It answers
  429 past the app's concurrent stream budget, because the signing key ships
  in the page and cannot gate connections.

Additive. The ingest schema already carried `ref`, and the server envelope
now carries it too, so intervention events keep their nudge or execution
link through `events.raw`.

## 2026-07-15

### Added

- `intervention.candidates` record contract. The stuck gate emits at most one
  candidate per session, snake_case and keyed like `events.raw`, carrying the
  open step, the firing rule, and the session's stuck signals at flag time.
  A redelivery can emit a copy, so consumers dedupe on `event_id`, which
  stays fixed across copies. Failed consumption dead-letters to
  `intervention.candidates.dlq`.
- Contracts the widget size spike assumed. `GET /v1/stream` pushes the SSE
  events `nudge`, `token`, `done`, and `action`, keyed by `end_user_hash`
  alone so a rotated session never strands the channel. It authenticates by
  query string because `EventSource` cannot set headers, a `ts` and `sig`
  HMAC-SHA256 pair over the hash, and a manual reopen resumes from a
  `last_event_id` query parameter (the Mercure pattern). Chat
  posts to `/v1/messages` and confirmations to `/v1/confirmations` keyed by
  `execution_id`. The widget emits `fr.nudge_dismissed`, `fr.nudge_engaged`,
  and `fr.action_cancelled`.

### Changed

- `FunnelStep.stuckSignals` still reads zero as the gate only writes to Kafka,
  and the count fills once decisioning ledgers one decision per candidate.
- The ingest description names the page-hide flush a keepalive fetch, because
  `sendBeacon` cannot carry the signature headers the contract requires.

Additive. The SDL is unchanged, and this pins what candidate consumers may
rely on before the first one ships.

## 2026-07-09

### Added

- The server serves `Query.app` and `App.funnel`. A step counts the end users
  who entered inside the half-open range and every completion by that cohort,
  so conversion never exceeds 100% (Amplitude groups funnel users by when they
  entered). A backwards range is a bad request, an unknown or foreign app id
  resolves to null, and a tenant-less request is unauthorized.
- `FunnelStep.stuckSignals` reads zero until the stuck gate ships, keeping its
  non-null shape so filling it later is not a schema change.

Additive. The SDL is unchanged, and this pins the read semantics the dashboard
renders.

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
- `events.raw` consumer semantics: the funnel's stream processor completes the
  milestone an event names when that milestone predates the event, reads the
  reserved `path` property on `fr.page_view` (the Segment Page field) for the
  session backtracking feature, and scopes session features to `session_id`,
  falling back to `end_user_hash` when a batch omits it.
- `events.raw` consumer redelivery: the processor applies an event id once and
  its completions are idempotent, so a redelivery inside the gateway's 24-hour
  window neither double-counts a session feature nor recompletes a milestone.

Additive. The mutation, payload, and envelope keep their recorded shapes, and
this pins what stream consumers may rely on before the agent reads them.

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
