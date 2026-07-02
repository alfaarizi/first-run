# ADR-008: Human confirmation before action execution

Date: 2026-06-11

## Status

Accepted

## Context

The agent can execute writes against the customer's own API through registered webhooks. The model is probabilistic, its inputs include crawled third-party content (an injection surface), and the end user belongs to someone else. A wrong autonomous write inside a customer's product ends the relationship.

## Decision

Execution requires four steps, in order:

1. The proposed action name exists in the app's registry.
2. The server validates the name and scope regardless of model output.
3. The end user clicks Confirm on the registry's confirmation copy.
4. The confirmation event lands in the ledger.

No confirmation row, and no webhook call (INV-2, INV-3). There is no auto-confirm setting.

## Consequences

Even if injection fully steers the model, it cannot cause an unregistered or unconfirmed action, because the registry plus the click form a capability firewall outside the model's reach. The ledger records every proposal, confirmation, and outcome for the founder to audit. The cost is friction on every action, accepted because the product's wedge is trustworthy proactivity, not autonomy.

The rejected alternatives were:

- Founder-configurable auto-execution for "safe" actions: safety declared by the same party that wrote the webhook, deferred until real tenants ask for it with scoped, reversible actions.
- Prompt-level allow-or-deny guardrails alone: defense that lives inside the thing being attacked.
