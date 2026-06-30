# Business Requirements Specification

Date: 2026-06-30

| Field | Value |
|---|---|
| Project | FirstRun |
| Problem | When a new user gets stuck while setting up your product, they often quit before activation. Product analytics flag the drop-off too late, and support chatbots reply only when asked. Under $100/month, no tool combines these four capabilities: <br> 1. Spot the stuck user in real time. <br> 2. Answer from the product's docs. <br> 3. Act only after the user confirms. <br> 4. Prove the help drove activation. |
| Stakeholders | - Muhammad Al Farizi (developer) <br> - Small-SaaS founders (customers) <br> - End users receiving nudges |
| Success criteria | 1. Stuck detection hits 0.80+ precision and 0.70+ recall on 300 labeled sessions. <br> 2. In 90% of answers, every claim cites the product's docs. <br> 3. All 30 planted-instruction attacks fail. <br> 4. A registered action runs only after the user clicks Confirm. <br> 5. No customer can read another's data. <br> 6. A public dashboard is live by 2026-08-21. <br> 7. At least one design partner by 2026-09-18. |
| Constraints | - The widget bundle is at most 30KB gzipped, with zero runtime dependencies. <br> - Each monthly active user costs $0.05 or less to serve. <br> - A decision is made in 2s, with first chat token in 1.5s (p95). <br> - End-user names, emails, and free text are never stored (hashed IDs and allowlisted properties only). <br> - v1 stays small (one Redpanda node, one Postgres). |
| Solution class | Embeddable browser widget backed by a hosted multi-tenant service |
| Assumptions | 1. Customers install FirstRun by pasting a JS snippet and registering setup-action webhooks. <br> 2. Each customer has a public docs site that answers most setup questions. <br> 3. Customer-hashed IDs are enough to tell end users apart. <br> 4. Customers accept a 10% intervention holdout to measure activation lift. |
| Dependencies | - LLM provider (OpenAI or Bedrock). <br> - Stripe for metered billing. <br> - AWS (ECS Fargate, RDS Postgres). <br> - Langfuse for tracing (the agent won't start without it). <br> - Customer docs site, webhook endpoint, installed snippet. |
