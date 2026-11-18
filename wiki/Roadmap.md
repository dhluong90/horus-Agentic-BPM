# Product Roadmap

Targets, not commitments. Release notes state what shipped. Each milestone exits on [[Technical Requirements]] "must" items.

![Milestones](images/roadmap-timeline.svg)

## M0 — Architecture (Q4 2026)
This wiki as the public specification · storage model · expression grammar · ADR-0001 to 0005 · repository skeleton with CI and licence checks · `examples/rule-engine-only` starts against PostgreSQL.

## M1 — Rule Engine 0.1, Cockpit 0.1 (Q1 2027)
| Area | Ships |
|---|---|
| Definitions | Typed inputs and outputs; `first`, `unique`, `collect`; YAML/JSON import and export; row-level validation |
| Expression language | Grammar as documented; money and dates; whitelisted and host-registered pure functions |
| Evaluation | Java API, batch, explicit clock, version pinning, compiled cache |
| Versioning | Draft → pending → published → retired; effective dates; maker-checker; version diff |
| Decision log | Transactional and async; masking; query; explain |
| REST and cockpit | `/api/bpm/rules/v1`; rule set list, table editor, diff, approvals, log viewer |
| Tooling | `rule-engine-test` with JUnit extension and coverage report |

Exit: TR-EMB, TR-DET, TR-SEC, TR-TEN, TR-OBS-01/02/04, TR-PERF-01/03, TR-QA-01, TR-LIC green; a newcomer completes Getting Started in under 30 minutes.

## M2 — Process Engine 0.1, Cockpit 0.2 (Q2 2027)
| Area | Ships |
|---|---|
| Definitions | JSON/YAML, versioned; `start`, `end`, `userTask`, `serviceTask`, `decision`, `exclusiveGateway`, `parallelGateway`, `timer`, `callProcess` |
| Execution | Host-transaction steps; replica-safe timers; history; incidents with retry and resolve |
| Human tasks | Assign by user, role, group; claim, complete, delegate, reassign; SLA and escalation; four-eyes approval |
| REST and cockpit | `/api/bpm/processes/v1`; definitions, instances, task queues, incidents, history |

Exit: TR-PERF-04/05, TR-OPS-03 and all "must" green; `examples/both` runs a process with a decision step.

## M3 — Agentic steps, simulation, 1.0 (Q3–Q4 2027)
| Area | Ships |
|---|---|
| Agentic | `agentTask` with adapter SPI (HTTP, MCP tool, in-process), output schema, guardrail rule set, route-to-human, timeouts, retries; reference adapters in `examples/` |
| Simulation | Against samples and against decision history, attached to publish requests |
| Operations | Partitioning, zero-downtime upgrade tests, OpenTelemetry spans, explain templates |
| Compatibility | DMN decision-table import; BPMN subset import |
| 1.0 | API freeze; deprecation policy; documentation site from the wiki |

Exit: every "must" green; two adopters outside the maintainers in production or pilot.

## After 1.0 (candidates)
Message correlation between processes · more databases through the storage SPI · Jakarta EE integration · hosted cockpit · multi-agent planning with ad-hoc step selection.

Proposals are issues labelled `type:proposal`; accepted ones become ADRs and move into a milestone.
