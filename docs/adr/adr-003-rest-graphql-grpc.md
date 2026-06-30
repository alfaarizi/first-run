# ADR-003: REST for ingest, GraphQL for the web app, gRPC for the agent

Date: 2026-06-11

## Status

Accepted

## Context

Three interfaces carry three load profiles. Ingest is very high volume with a tiny fixed payload, called from a 30KB browser bundle. The web app is low volume with deeply nested reads (funnel to cohorts to interventions to outcomes). The server-to-agent boundary is internal, typed, streaming, and latency-sensitive.

## Decision

Versioned REST (`POST /v1/e`, defined in OpenAPI) serves ingest and outbound webhooks. Spring for GraphQL serves the web app. gRPC with protobuf serves the agent boundary. All three contracts live in `/api` as the single source of truth.

## Consequences

Each surface gets the cheapest adequate tool. Ingest carries no GraphQL runtime or client weight, the web app avoids over-fetching and under-fetching across nested reads, and the agent boundary gets generated types in both languages plus bidirectional streaming for chat. The cost is three toolchains, paid once in `make generate` and CI, which is why the `api/` directory exists.

The rejected alternatives were:

- GraphQL everywhere: a GraphQL ingest path adds parser weight and burns the widget budget.
- REST everywhere: the web app's nested reads become N+1 endpoints or a hand-built aggregate API, which is GraphQL rebuilt badly.
- GraphQL federation: a team-topology tool with no second team.
