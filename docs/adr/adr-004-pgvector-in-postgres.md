# ADR-004: pgvector in Postgres over a dedicated vector database

Date: 2026-06-11

## Status

Accepted

## Context

Per-tenant document corpora are small, thousands of chunks per docs site, not millions. Retrieval must respect tenant isolation, and the server already runs Postgres with row-level security (RLS).

## Decision

Store embeddings in `doc_chunks(embedding vector)` inside the main Postgres, indexed with HNSW, namespaced per tenant, and covered by the same RLS policies as everything else.

## Consequences

A single database to operate, back up, and secure. Vector isolation is inherited, not reimplemented, and joins between chunks and their sources are plain SQL. The cost is a ceiling. At tens of millions of vectors or heavy approximate-nearest-neighbor concurrency, pgvector on a shared instance will strain. Revisit when retrieval p95 goes over budget at production load, or a tenant corpus runs two orders of magnitude above today's design point.

The rejected alternative was:

- A dedicated vector database such as Pinecone, Weaviate, or Qdrant: a second datastore with its own tenancy, auth, backups, and bill, for a scale problem this product does not have.

That tradeoff flips at scale, and the seam (`agent.retrieval`) is one module.
