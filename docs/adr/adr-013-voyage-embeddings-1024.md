# ADR-013: Voyage embeddings at 1024 dimensions

Date: 2026-07-18

## Status

Accepted

## Context

The docs index (ADR-004) needs an embedding model, and Anthropic, the chat
provider, offers none; its embeddings guide points to Voyage AI. The vector
column's width is baked into a forward-only migration (IR-10), and an HNSW
index on the `vector` type caps at 2,000 dimensions, so the dimension must be
right before the first tenant indexes. Cost per MAU is guarded at $0.05
(SPIKE-003).

## Decision

Embed with Voyage `voyage-4-lite` at its default 1024 dimensions, stored in
`doc_chunk(embedding vector(1024))`. (ADR-004 wrote `doc_chunks`; the schema
uses the singular form every other table follows.) Voyage embeddings are
unit-length, so the HNSW index uses inner product (`vector_ip_ops`), which
ranks identically to cosine at lower cost, and retrieval queries with `<#>`.
The model name lives in agent settings, and all access goes through the
agent's single provider client.

## Consequences

Retrieval quality rides on a model tuned for cost and latency, upgradable to
`voyage-4` or `voyage-4-large` at the same 1024 width without a schema
change. Any dimension change is a new migration plus a full reindex of every
tenant, never a mixed index. A second vendor key now sits beside the chat
provider's in settings and SSM.

The rejected alternatives were:

- OpenAI `text-embedding-3-small`: comparable price, but a third vendor
  relationship for no quality evidence, and its 1536 default would either
  spend 50% more storage or need the non-default `dimensions` parameter.
- Self-hosted `voyage-4-nano` (open weights): no per-token cost, but GPU
  serving is an operations bill a solo 10-week build cannot carry.
- `voyage-context-4` contextualized chunks: better long-document retrieval,
  but ~3x the price against a groundedness target not yet measured.
