# Risk Register

Date: 2026-07-01

This register tracks the risks to the project, each with its probability, impact,
mitigation, and current status. It is a living document, so a status moves here as a
risk is mitigated, accepted, or closed. Risks are ordered by impact, and the first
three are the project's falsifiable kill criteria.

| ID | Risk | Probability | Impact | Mitigation | Status |
|:---:|---|:---:|:---:|---|:---:|
| RSK-001 | Ten design-partner conversations within four weeks of v1, by 2026-10-08, produce zero installs (kill criterion 1). | Medium | High | Start outreach at the beginning of the build rather than at launch, and run through an early funnel screenshot, a public eval methodology post, and a first external install attempt. | Open |
| RSK-002 | An incumbent releases the full trio, stall detection with a timing policy, confirmed action execution, and holdout-measured lift, under $100/month before v1 (kill criterion 2). | Low | High | Track incumbent pricing. Reactive chat under $100 already exists (Intercom Fin from ~$49.50/month, Product Fruits Elvin from ~$96/month) without triggering the criterion, and the fallback reuses the pipeline, eval harness, and Java spine. | Open |
| RSK-003 | The widget cannot hold 30KB gzipped with the confirmation flow included (kill criterion 3). | Low | High | Keep zero runtime dependencies, gate bundle size in continuous integration from the first widget build, and never cut the confirmation flow to fit. | Open |
| RSK-004 | The LLM judge drifts from human judgment (Cohen's kappa below 0.7), so groundedness numbers cannot be trusted. | Medium | High | Hand-verify 100 samples after each rubric or model change, and publish kappa and its date in the README. | Open |
| RSK-005 | Injected instructions in crawled docs steer the agent into an unwanted action. | Medium | High | Re-validate the action name and scope on the server regardless of model output, require a recorded confirmation before any webhook fires, and gate continuous integration on the 30-case adversarial suite. | Open |
| RSK-006 | An RLS gap leaks one customer's data to another. | Low | High | Cover every customer table with RLS, give the application role no BYPASSRLS, and block merge until an isolation test proves a cross-customer read fails. | Open |
| RSK-007 | The single Redpanda node fails and drops in-flight events. | Medium | Medium | Batch and retry client-side in the widget, dedupe on each event's unique ID to make redelivery safe, and document recovery and the MSK trigger in the runbook. | Accepted (ADR-002) |
| RSK-008 | Token use or provider pricing drifts past $0.05 per MAU. | Medium | Medium | Cap model calls at 1 to 3% of events with the gate, run a small model in the policy node, and graph cost per decision from Langfuse traces. | Open |
