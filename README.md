<div align="center">

# FirstRun

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Widget](https://img.shields.io/badge/widget-MIT-blue.svg)](widget/LICENSE)
[![Status](https://img.shields.io/badge/status-building%20v1-orange.svg)](docs/architecture.md)

</div>

FirstRun is an open source activation agent for small B2B SaaS. A founder drops a
JavaScript snippet into their app, points FirstRun at their public docs, and defines
the milestones that count as activation. It runs the loop below, and every number it
reports is measured, not asserted.

FirstRun is a Java modular monolith for the product core and a Python agent for the
model loop (ADR-001). The project is building toward v1, with the dashboard live on
2026-09-10 (BRS).

## How it works

FirstRun runs one loop, from a raw event to measured lift:

1. The widget captures clicks, pageviews, and custom events, and posts them to the ingest gateway in signed batches.
2. A deterministic gate runs in-stream under 50ms and flags users stalled before a milestone, so model cost tracks stuck users, not raw traffic (ADR-005).
3. The agent scores each candidate and returns intervene or hold, exactly one decision per candidate.
4. On intervene, the widget shows a nudge, and chat answers with citations from the tenant's docs, or stays silent rather than guess.
5. The agent proposes only registered actions, and no webhook fires until the end user clicks Confirm (ADR-008).
6. Every decision lands in an append-only ledger, and FirstRun measures lift against a deterministic 10% holdout, always with a 95% confidence interval (ADR-009).

## Benchmarks

Each row shows the target the metric must clear, or for lift and ingest how it will
be reported. Measured values fill in at v1, labeled SYNTHETIC (Tasklet demo) or REAL
(design partner) and never blended. Targets come from the success criteria and
constraints in [Business requirements](docs/brs.md).

| Metric | Target | Basis |
|--------|--------|-------|
| Stuck detection | P 0.80 or higher, R 0.70 or higher | 300 labeled sessions (SYNTHETIC) |
| Groundedness | 90% or higher | 150-question golden set, judge kappa reported |
| Injection resistance | 30 of 30 blocked | adversarial suite |
| Activation lift | measured, with 95% CI | Tasklet holdout (SYNTHETIC), real-tenant row when live |
| Decision latency | p95 2s or less | replay + online |
| Cost | $0.05 or less / MAU / month | traces |
| Ingest | reported under load | k6 |

## To start using FirstRun

The stack runs locally on Docker through one command surface, the Makefile:

```sh
git clone https://github.com/alfaarizi/first-run.git
cd first-run
cp .env.example .env
make up && make seed
open http://localhost:5173        # founder dashboard
open http://localhost:5174        # Tasklet demo app with the widget installed
```

- `make up` starts Postgres with pgvector, Redis, Redpanda, Langfuse, and the server,
agent, web, and Tasklet.
- `make seed` loads a demo tenant and Tasklet data.

## To start developing FirstRun

FirstRun's design is settled in the records below, which the code executes. Read them
before any structural change:

- [Business requirements](docs/brs.md) records the problem, stakeholders, and v1 success criteria.
- [Architecture](docs/architecture.md) maps the system context, containers, components, and event stream.
- [Architecture decision records](docs/adr/) collect the reasoning behind each major choice.
- [Tradeoff analysis](docs/tradeoff-analysis.md) scores the alternatives those decisions weighed.
- [Risk register](docs/risk-register.md) tracks the risks, their mitigations, and status.
- [Definition of done](docs/definition-of-done.md) sets the bar every change clears before it merges.

## License

The platform is [AGPL-3.0-only](LICENSE). The embeddable widget is the exception,
[MIT](widget/LICENSE) so customers can audit and embed the code that runs in their
product without copyleft obligations (ADR-011).
