# ADR-005: Deterministic gate before the model policy

Date: 2026-06-11

## Status

Accepted

## Context

Running a model on every event destroys unit economics and latency. Take a planning envelope, re-verified against live provider pricing. A 5,000-MAU tenant at about 40 events per user per day produces about 6M events per month, and a policy call is about 700 tokens round trip on a small model.

- Model on every event: about 4.2B tokens per month, hundreds to thousands of dollars per tenant. The $0.05 per MAU guardrail is dead on arrival.
- Gate firing on 1 to 3% of events: at most 180K calls, about 126M tokens, single digits to low tens of dollars per tenant, under $0.015 per MAU.

## Decision

A deterministic, in-stream stuck gate filters events under a 50ms budget with no network calls, reading time-on-step thresholds, repeated failures, and abandonment loops. Only gated candidates reach the model policy, which weighs history, dismissals, context, and suppression rules, and holds when unsure.

## Consequences

Cost scales with stuck users, not traffic, and the hot path stays off the network. The gate's recall bounds the system's recall, so the harness evaluates the gate on its own (the gate suite). There are now two components to tune, which the labeled-session dataset supports.

The rejected alternatives were:

- A model on every event: the math above.
- A gate alone with templated nudges: cheap but context-blind, and the measured gap between gate-only and gate-plus-policy is itself a benchmark worth publishing.
