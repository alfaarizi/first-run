# Security Policy

## Supported versions

FirstRun is building toward v1 and has no released versions yet. Security
fixes land on `main`. Once v1 ships, this table records which versions
receive them.

| Version | Supported |
|---------|-----------|
| `main`  | Yes       |

## Reporting a vulnerability

Do not open a public issue for a vulnerability. Report it privately through
GitHub's [security advisories](https://github.com/alfaarizi/first-run/security/advisories/new),
or by email to [zizithe2nd@gmail.com](mailto:zizithe2nd@gmail.com).

Include the affected component, the steps to reproduce, and the impact you
see. You will get an acknowledgment within three working days and an
assessment with a fix timeline within seven. Please give a reasonable window
to ship a fix before any public disclosure.

## Scope

FirstRun runs as a third-party script inside other companies' products and
executes actions against their APIs, so these areas matter most:

- **Tenant isolation.** Any read or write that crosses a tenant boundary,
  including a way to bypass Postgres row-level security.
- **Ingest authentication.** Forging the request signature, replaying a
  captured request, or getting an event accepted for an app you do not own.
- **Action execution.** Firing a webhook without a recorded confirmation, or
  running an action outside the app's registry or scope.
- **Prompt injection with real effect.** Content in crawled docs that steers
  the agent into an unregistered action or leaks another tenant's data. A
  jailbreak that only produces text is a model limitation, not a
  vulnerability here.
- **Data capture.** Any path that stores end-user names, emails, or free
  text, which the design forbids.
- **Widget escape.** Breaking out of the shadow root to read or alter the
  host page, or delivering script through server-rendered nudge or answer
  text.

Local development ships with placeholder secrets in `.env.example` and
compose. Those are intentional, not a finding.
