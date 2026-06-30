# ADR-006: SSE over WebSocket for the widget

Date: 2026-06-11

## Status

Accepted

## Context

The widget needs server push (nudges and streamed answer tokens) inside a 30KB bundle. That channel must survive corporate proxies and flaky networks and never break the host app.

## Decision

Server-Sent Events (SSE) carry all server-to-widget push. Plain batched POST carries client-to-server traffic (events, chat messages, confirmations). Reconnect uses `Last-Event-ID`, so a dropped stream resumes without losing nudges.

## Consequences

The channel is one-directional plain HTTP, so it is proxy-friendly, auto-reconnecting, small in client code, and easy to bridge to the agent's gRPC stream server-side. The cost is no client-to-server multiplexing on one socket, which this product does not need. Revisit only if a feature needs sustained bidirectional traffic, none of which is planned (see ADR-010).

The rejected alternatives were:

- WebSocket: heavier client code, worse proxy traversal, and hand-rolled reconnect and heartbeat, all for bidirectionality we do not use.
- Long polling: what the SSE fallback already is, with worse latency.
