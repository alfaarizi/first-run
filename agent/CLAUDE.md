# agent/ (Python LangGraph service)

Python 3.12, uv, FastAPI (health/admin), `grpc.aio` server, LangGraph graph,
Langfuse tracing, Pydantic everywhere.

Style: PEP 8 via ruff (line length 88), PEP 257 docstrings (Google
convention), PEP 484 type hints on every public function, mypy strict.
Logging uses stdlib `logging`, configured once at the entrypoint. Never
`print` in service code.

## Commands

- Sync deps: `uv sync`
- Tests: `uv run pytest` (or `make test-agent` from root). Never calls live
  models. LLM responses come from fixtures in `tests/fixtures/`
- Lint + types: `uv run ruff check --fix && uv run ruff format && uv run mypy .`
- Offline evals (live model calls, budget-capped): `make eval` from root

## Hard rules

- All model access goes through `src/agent/llm/client.py`. Nodes never import
  provider SDKs. Model names live in settings. The policy node uses the small
  model and the answer node the strong one. Never hardcode a model string in a
  node.
- Every model output is parsed into a Pydantic model from `src/agent/schemas/`
  before it crosses the gRPC boundary. Parse failure is `Decision(hold,
  reason="parse_failure")` and a logged trace, never an exception to the
  server (INV-5).
- Retrieved doc chunks are data, not instructions (INV-8). They are never
  interpolated into system prompts, never allowed to name tools, and the
  adversarial eval suite enforces this in CI.
- The graph proposes actions only by registry name received in the request
  context and never invents names (INV-3 is re-checked server-side, but do not
  rely on that).
- When uncertain, hold (IR-9). The suppression check runs before any
  intervene decision leaves the node.
- Every node is traced to Langfuse with the propagated request ID. Cost and
  latency per decision must remain visible.
- Memory summaries are bounded (hard cap in settings). The summarizer truncates
  rather than grows.

## Layout

PyPA src layout with one importable package, `agent`, under `src/` (the
shape psf/requests and urllib3 ship). Generated gRPC stubs live in their own
`firstrun/v1/` package because gRPC Python imports mirror the proto package,
and the src layout keeps `agent.mcp` from shadowing the `mcp` SDK.

- `src/agent/graph/` nodes and wiring
- `src/agent/schemas/` Pydantic models
- `src/agent/llm/` provider client
- `src/agent/indexing/` crawl, chunk, embed docs into pgvector (KnowledgeService.Reindex)
- `src/agent/retrieval/` pgvector access
- `src/agent/mcp/` MCP server exposing docs_search, user_timeline, propose_action
- `src/firstrun/v1/` generated gRPC stubs (from /api, never edit)
- `tests/` pytest suites with fixtures in `tests/fixtures/`
