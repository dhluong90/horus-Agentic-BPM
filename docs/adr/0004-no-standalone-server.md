# ADR-0004: No standalone server mode

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: project

## Context
A server mode would make the engines usable from any language but would recreate the deployment burden the project exists to remove, and would split effort between two runtime models.

## Decision
The engines are libraries only. Remote access is available through the optional REST layer that the host exposes. A standalone cockpit may be hosted separately because it is static assets, not an engine.

## Options considered
1. Library only (chosen).
2. Library plus optional server: two runtime models to test and document; the server would need its own identity, tenancy and scaling story.
3. Server only: the model of existing platforms; rejected by the principles.

## Consequences
Non-JVM hosts use the REST layer of a JVM host, or wait for a future decision. Documentation and tests cover one runtime model.
