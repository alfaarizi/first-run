# ADR-009: Holdout-based lift measurement

Date: 2026-06-11

## Status

Accepted

## Context

Every vendor in this category asserts lift, and asserted lift is marketing. Measured lift needs a control group. At the monthly active user (MAU) counts a small SaaS sees, that control group is small, so the statistics must be honest about uncertainty.

## Decision

Each tenant gets a default 10% holdout, a deterministic hash bucket on `end_user_id`, assigned at first sight, immutable, and excluded from every intervention surface (INV-6). Lift is the difference in milestone-completion rate between the intervened cohort and the holdout within the attribution window, reported with a 95% bootstrap confidence interval. No surface in the product, the README, or the dashboard shows lift without its interval.

## Consequences

Lift is measured, not asserted, and the method is publishable. At 1,000 MAU the holdout is about 100 users, so intervals will be wide. They get reported anyway, because a bare percentage at that sample size is a lie of omission. Deterministic assignment makes the split reproducible from the data alone. The cost is that 10% of users at a struggling tenant get no help, which the docs disclose. Tenants can lower the holdout only by accepting wider intervals, never to zero while lift reporting is on.

The rejected alternatives were:

- Before-and-after comparison: confounded by releases, seasonality, and cohort mix.
- Per-intervention A/B tests on copy: valuable later, but the first-order question is whether intervening at all causes activation.
