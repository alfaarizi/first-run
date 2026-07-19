# ADR-013: Voyage embeddings at 1024 dimensions

Date: 2026-07-18

## Status

Accepted

## Context

The docs index needs an embedding model, and Anthropic, the chat provider,
offers none. Its embeddings guide points to Voyage AI. The vector column
bakes its width into a forward-only migration, and an HNSW index caps at
2,000 dimensions, so the width must be right before the first tenant
indexes. Cost per MAU is guarded at $0.05.

## Decision

Embed with Voyage `voyage-4-lite` at its default 1024 dimensions. Voyage
vectors are unit-length, so the HNSW index uses inner product, which ranks
like cosine at lower cost, and retrieval queries with `<#>`. The model name
lives in agent settings behind the agent's single provider client.

## Consequences

A model tuned for cost and latency carries retrieval quality, upgradable to
`voyage-4` or `voyage-4-large` at the same width without a schema change.
Changing the dimension means a new migration plus a full reindex of every
tenant. A second vendor key sits beside the chat provider's in settings and
SSM.

The rejected alternatives were:

- OpenAI `text-embedding-3-small`: a third vendor for no quality evidence,
  and its 1536 default spends 50% more storage or needs a non-default
  `dimensions` parameter.
- Self-hosted `voyage-4-nano` (open weights): no per-token cost, but GPU
  serving is an operations bill a solo ten-week build cannot carry.
- `voyage-context-4` contextualized chunks: better long-document retrieval
  at about 3x the price, against a groundedness target not yet measured.
