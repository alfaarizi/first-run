# ADR-007: Event allowlist and hashed end-user IDs

Date: 2026-06-11

## Status

Accepted

## Context

A third-party script inside other companies' products is one bad default away from collecting PII. Trust is the adoption blocker for the buyer, and a system that cannot hold sensitive payloads is a stronger claim than a promise not to look.

## Decision

Five rules keep sensitive data out of the system:

- Event properties are default-deny. Only founder-allowlisted keys leave the page, enforced client-side and authoritatively at the gateway.
- No free-text capture exists as a feature.
- End-user IDs are customer-supplied hashes, and we never hold the mapping.
- IPs are truncated at the gateway before storage.
- The widget and SDK are open source (MIT), so the claim is auditable.

## Consequences

Smaller data, a simpler compliance posture, an honest sales line, and a demo that matches production. The cost is that some analytics questions are unanswerable by design (no session replay, no text mining), and debugging sometimes means asking a customer to map a hash. Both are worth the trust.

The rejected alternatives were:

- Capture-everything-with-redaction: redaction fails open, so the burden of proof lands on us.
- Configurable free-text capture: one toggle away from being a keylogger, a liability rather than a feature.
