# ADR-0005: Cockpit as static assets served by the host or standalone

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: cockpit

## Context
Operators need a UI for authoring, approvals, decision logs, instances and incidents. The UI must not become a server with its own state or authentication.

## Decision
The cockpit is a React application built to static assets, published both as a jar served by `cockpit-spring-boot-starter` under the host's authentication and as a plain archive for standalone hosting against one or more services' REST endpoints. It keeps no state of its own.

## Options considered
1. Static assets (chosen).
2. Server-rendered UI inside the starter: couples UI technology to the Java release cycle.
3. Separate cockpit service with its own database: contradicts the state-in-host principle.

## Consequences
The REST layer is the cockpit's only contract, so it must be complete and versioned. Standalone hosting needs CORS configuration on the host, documented in the cockpit guide.
