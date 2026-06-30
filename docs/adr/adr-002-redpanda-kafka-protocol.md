# ADR-002: Kafka protocol via Redpanda

Date: 2026-06-11

## Status

Accepted

## Context
-
The event stream is Kafka-shaped, with unbounded multi-tenant input, several independent consumer groups (state, analytics, archival, eval capture), and replay required by the eval harness. Managed Kafka (MSK) costs more per month than this product's entire infrastructure budget, and ZooKeeper-era self-hosting is an operations tax a solo builder should not pay.

## Decision

Speak the Kafka protocol everywhere and run Redpanda as the broker, a single binary in Compose locally and a small node on ECS for v1. Client code, topic naming, and semantics stay Kafka-standard, so the broker is swappable.

## Consequences

This buys replay, consumer groups, and per-user ordering (keyed partitions) for about the cost of one container. A single node has no broker high availability in v1, which is acceptable because ingest is buffered client-side, dedupe makes redelivery safe, and the runbook documents recovery. Move to MSK or a multi-node cluster when throughput exceeds one node, a paying tenant needs a durability SLA, or compliance demands managed infrastructure.

The rejected alternatives were:

- MSK now: the bill outweighs v1 traffic by an order of magnitude.
- RabbitMQ: a queue, not a replayable log.
- Postgres as a queue: fine for jobs, wrong for a multi-consumer event stream with replay.
