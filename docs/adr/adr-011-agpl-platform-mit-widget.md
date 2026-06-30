# ADR-011: AGPL-3.0 for the platform, MIT for the widget

Date: 2026-06-12

## Status

Accepted

## Context

The repo is public and the revenue is the hosted service, so readable code costs nothing. The one threat a license can block is a competitor hosting this code as their own service. The widget runs inside customers' products, so its terms must impose nothing on them.

## Decision

License the repository AGPL-3.0-only. The one exception is `widget/`, which is MIT. The hosted deployment is the commercial offering, with no open-core split, no dual licensing, and no CLA.

## Consequences

Modifying the platform and serving it over a network now requires publishing the source (AGPL section 13), so commercial use flows to the hosted product. The only code in a customer's bundle stays MIT, so embedding clears legal review and the ADR-007 audit claim is enforceable. The costs, fewer corporate contributors and a relicensing step before any future open-core split, are acceptable for a solo project.

The rejected alternatives were:

- MIT everywhere: invites a closed hosted clone with no recourse.
- Open core under `/ee`: licensing machinery with no enterprise buyer to gate features for.
- Source-available licenses such as BSL or FSL: block the same threat but are not OSI-approved open source, which weakens the open-audit claim.
- No license: all rights reserved, which contradicts the MIT promise already in the docs.
