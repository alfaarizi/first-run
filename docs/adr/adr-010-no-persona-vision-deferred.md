# ADR-010: No persona agent, vision deferred

Date: 2026-06-11

## Status

Accepted

## Context

The capability checklist behind this build has ten boxes, and this product ticks eight. Forced capabilities read as resume-stuffing to the reviewers this repo is for, and they tax the capabilities that matter here, among them timing policy and evaluation.

## Decision

No product persona, because nothing in the domain needs a character. Scripted session generators live only in the eval harness, as test tooling. Vision is deferred, not rejected. Document Object Model (DOM) events identify the screen for ordinary web apps, and vision earns a place only for canvas-heavy products like design tools and editors, the explicit trigger to revisit.

## Consequences

Engineering time concentrates on the two differentiators, the detection policy and the eval harness. The cost is that if a canvas-heavy design partner appears early, vision work starts from zero, though that partner would also bring the test cases the feature needs.

The rejected alternatives were:

- Ticking all ten capabilities: capability theater.
- Deferring more by dropping the Model Context Protocol (MCP) or memory: both have real jobs here, since MCP makes the agent's tools externally drivable for demos and memory is what makes a returning stuck user feel met where they left off.
