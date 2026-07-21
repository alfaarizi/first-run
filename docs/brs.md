# Business Requirements Specification

Date: 2026-07-01

## Problem

When a new user struggles to set up your product, they often quit before
activation. Product analytics report the drop-off only after it happens, and
support chatbots respond only when the user inquires. Under $100/month, no tool
combines these four capabilities:

1. Detect the stuck user in real time.
2. Answer from the product's docs.
3. Act only after the user confirms.
4. Prove the help drove activation.

## Solution class

FirstRun is an embeddable browser widget backed by a hosted multi-tenant service.

## Stakeholders

- Small-SaaS founders, the customers who install the widget and read the dashboard.
- End users inside the customer's product, who receive the nudges.

## Success criteria

1. Stuck detection reaches 0.80 or higher precision and 0.70 or higher recall on 300
   labeled sessions.
2. In 90% of answers, every claim cites the product's docs.
3. All 30 planted-instruction attacks fail.
4. A registered action runs only after the user clicks Confirm.
5. No customer can read another customer's data.
6. A public dashboard is live by 2026-09-10.
7. A design partner installs FirstRun by 2026-10-08.

## Constraints

- The widget bundle is at most 30KB gzipped, with zero runtime dependencies.
- Each monthly active user costs $0.05 or less to serve.
- A decision returns within 2s and the first chat token within 1.5s, both at p95.
- End-user names, emails, and free text are never stored, only hashed IDs and
  allowlisted properties.
- v1 stays small, one Redpanda node and one Postgres.

## Assumptions

1. Customers install FirstRun by pasting a JavaScript snippet and registering
   setup-action webhooks.
2. Each customer has a public docs site that answers most setup questions.
3. Customer-hashed IDs are enough to tell end users apart.
4. Customers accept a 10% intervention holdout, a control group that receives no
   nudges, to measure activation lift.

## Dependencies

- An LLM provider (Anthropic for chat, Voyage AI for embeddings).
- Stripe for metered billing.
- AWS (ECS Fargate and RDS Postgres).
- Langfuse for tracing, without which the agent will not start.
- The customer's docs site, webhook endpoint, and installed snippet.
