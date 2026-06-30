# ADR-001: Modular monolith over microservices

Date: 2026-06-11

## Status

Accepted

## Context

Solo developer, single product, eleven bounded contexts (identity, apps, ingestion, funnel, decisioning, actions, ledger, knowledge, analytics, billing, notifications). Microservices solve team-coordination problems a solo project does not have, and every extra service multiplies the deploy, observability, and failure surface.

## Decision

Build the product side as a Spring Boot application with Spring Modulith modules. ArchUnit and `ApplicationModules.verify()` enforce boundaries in CI, so modules talk only through published interfaces or domain events. Extract a module into its own service only when a runtime force demands it (a different language, independent scaling, or isolation), which is why the Python agent is already separate.

## Consequences

A single deployable, a shared database, and transactions across modules where needed. Module discipline lives in tests, not network boundaries, so the ArchUnit suite is load-bearing and never weakened to merge a change.

The rejected alternatives were:

- Microservices: coordination cost with no second team.
- A plain layered monolith: boundaries decay without enforcement.
