# Contributing to FirstRun

Thanks for helping build FirstRun. This guide covers the developer setup,
the rules the codebase holds itself to, and the path from a branch to a
merged pull request. By participating, you agree to the
[code of conduct](CODE_OF_CONDUCT.md).

## Before you start

The [README](README.md) explains what FirstRun does and lists the design
records the code executes. Read the ones your change touches. Two of them
shape every pull request:

- [Architecture decision records](docs/adr/) hold the reasoning behind each
  structural choice. Do not relitigate them in code. To change one, propose
  a new ADR from the [template](docs/adr/adr-000-decision-record-template.md).
- [Definition of done](docs/definition-of-done.md) is the bar every change
  clears before it merges. CI enforces the machine-checkable parts.

## Set up

You need Docker, Java 21, Node 22, and Python 3.12 with
[uv](https://docs.astral.sh/uv/). Bring the stack up with the
[README quickstart](README.md#to-start-using-firstrun), then use the
Makefile, the only command surface. The targets you will use most:

```sh
make test      # every suite: server, agent, widget, web
make lint      # spotless, ruff, mypy, eslint, buf
make eval      # offline eval harness against evals/baselines.json
make generate  # regenerate all stubs from /api
```

`make test-server` needs the Docker daemon, because Testcontainers starts a
throwaway Postgres and Redpanda.

## Make changes

Work trunk-based: a short-lived branch off `main`, a focused pull request,
a squash merge. Keep each commit compiling and its tests passing.

Commit messages follow
[Conventional Commits](https://www.conventionalcommits.org) with the module
or directory as the scope, for example `fix(funnel): ...` or
`refactor(widget): ...`. Keep the subject imperative and under 50
characters.

Match the surrounding code. The style is enforced by formatters, and the
codebase itself is the exemplar:

- Functions stay small and do one thing. Names reveal intent.
- Comments explain why, never what, in short plain sentences without
  semicolons. Delete a comment that restates the code.
- Every class and method carries a one-line doc, including private helpers.
- Tests ship with the change, named as the behavior they prove, one concept
  per test.

## Know the landmines

A few rules are not obvious from the code and reverting them looks like a
cleanup. CI blocks most of these, but knowing why saves you the round trip:

- `/api` is the single source of truth. Never hand-edit generated code
  (`server/target`, `web/src/gql`, `agent/src/firstrun`). Change the
  contract first, run `make generate`, update every consumer in the same
  pull request, and record it in [api/CHANGELOG.md](api/CHANGELOG.md).
- Never edit an applied migration, comments included. Flyway checksums the
  file, so any edit fails validation on every existing database. Fix
  forward with a new `V<n>__short_desc.sql`.
- Never edit a dataset, rubric, or floor in `evals/` to make a run pass.
  Datasets are hash-pinned, and moving a floor requires an ADR.
- Some duplication is deliberate. The per-module Kafka dedupers and GraphQL
  exception classes look foldable, but sharing them would couple modules
  ArchUnit keeps apart. Leave the copies.
- The widget ships with zero runtime dependencies inside a 30KB gzipped
  budget, and `make size` fails the build past it. The confirmation flow is
  never what you cut to fit.
- Cross-module access goes through published interfaces or domain events.
  `ApplicationModules.verify()` and ArchUnit fail the build otherwise, and
  the fix is the design, never the rule.

## Open a pull request

CI runs the lint, test, and eval legs your paths touch, checks the API
contracts for breaking changes, and regenerates the stubs to prove none
were hand-edited. All required checks must pass.

In the pull request, say what changed and why. A structural decision needs
an ADR in the same pull request, and a contract change needs its
`api/CHANGELOG.md` entry.

## Report a bug or propose a feature

Open a [GitHub issue](https://github.com/alfaarizi/first-run/issues). The
templates cover user stories and time-boxed spikes. For anything
security-sensitive, follow the [security policy](SECURITY.md) instead of
opening a public issue.