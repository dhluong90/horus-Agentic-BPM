# Product Roadmap

Planned in weeks. Targets, not commitments; release notes state what shipped. Each milestone exits on [[Technical Requirements]] "must" items. The library is deliberately small, which is what makes this pace plausible.

![Milestones](images/roadmap-timeline.svg)

## M0 — Architecture (2 weeks, to 4 Dec 2026)
This wiki as the public specification · storage model · expression grammar · ADR-0001 to 0005 · repository skeleton with CI and licence checks · `examples/rule-engine-only` starts against PostgreSQL.

## M1 — Rule Engine 0.1, Cockpit 0.1 (6 weeks, to 15 Jan 2027)
Goal: the engine is usable in production from YAML-authored rule sets, with governance and audit in place. The interactive editor is deliberately deferred so the core ships early.
| Area | Ships |
|---|---|
| Definitions | Typed inputs and outputs; `first`, `unique`, `collect`; YAML/JSON import and export; row-level validation |
| Expression language | Grammar as documented; money and dates; whitelisted and host-registered pure functions |
| Evaluation | Java API, batch, explicit clock, version pinning, compiled cache |
| Versioning | Draft → pending → published → retired; effective dates; maker-checker; version diff |
| Decision log | Transactional and async; masking; query; explain |
| Authoring | By YAML/JSON: files loaded at start-up, import endpoint, export; fits a GitOps flow |
| Simulation | Against sample inputs (paste or CSV) for a draft version |
| REST and cockpit 0.1 | `/api/bpm/rules/v1`; cockpit as **viewer and approver**: rule set and version viewer, diff, simulation, request/approve/reject with maker-checker, decision log explorer with explain. No in-browser table editor yet |
| Reliability | `Idempotency-Key` on every mutating endpoint and on evaluate; `rle_idempotency` with retention |
| Tooling | `rule-engine-test` with JUnit extension and coverage report |

Exit: TR-EMB, TR-DET, TR-SEC, TR-TEN, TR-IDEM-01 (rules), TR-OBS-01/02/04, TR-PERF-01/03, TR-QA-01, TR-LIC green; a newcomer completes Getting Started in under 30 minutes.

## M2 — Process Engine 0.1 with `agentTask`, Cockpit 0.2 (8 weeks, to 12 Mar 2027)
Goal: processes with humans, services and agents as participants, governed by rules. `agentTask` is in scope here because it is a service step plus schema validation plus a guardrail decision, all of which exist by then.
| Area | Ships |
|---|---|
| Definitions | JSON/YAML, versioned; `start`, `end`, `userTask`, `serviceTask`, `decision`, `exclusiveGateway`, `parallelGateway`, `timer`, `callProcess` |
| Execution | Host-transaction steps; replica-safe timers; history; incidents with retry and resolve |
| Human tasks | Assign by user, role, group; claim, complete, delegate, reassign; SLA and escalation; four-eyes approval |
| Reliability | Idempotency keys on start and complete; unique business key per tenant and definition; optimistic locking |
| Compensation | `compensate` handlers, `saga` blocks, reverse-order execution with retry and incidents |
| Business calendar | `BusinessCalendar` SPI; built-in weekly hours, holidays, time zone per tenant; cockpit editor |
| Migration | Migration plan, dry run and batched execution for same-id step mapping |
| Agentic core | `agentTask` step: adapter SPI (in-process and HTTP), JSON Schema validation of agent output, guardrail rule set evaluated through the Rule Engine, `onViolation: route-to-human` or `fail`, timeout, retry, incident; full audit of inputs and outputs |
| Cockpit 0.2 | Interactive decision-table grid editor with type checks and expression validation; simulation against decision history; agent task view with inputs, outputs and guardrail results |
| REST and cockpit | `/api/bpm/processes/v1`; definitions, instances, task queues, incidents, history |

Exit: TR-PERF-04/05, TR-OPS-03, TR-IDEM, TR-COMP, TR-CAL, TR-MIG-01/02, TR-AGT-01/02/03 and all "must" green; `examples/both` runs a process with a decision step and an `agentTask` guarded by a rule set and escalated to a human.

## M3 — Agentic depth, compatibility, 1.0 (8 weeks, to 7 May 2027)
| Area | Ships |
|---|---|
| Agentic depth | MCP tool adapter; reference adapters for common LLM APIs in `examples/`; agent memory passed as bounded context; ad-hoc step selection inside a bounded block (multi-step agent planning under the same guardrails) |
| Simulation | Simulation reports attached to publish requests; what-if comparison of two versions over a period |
| Operations | Partitioning, zero-downtime upgrade tests, OpenTelemetry spans, explain templates |
| Migration | Explicit step mapping for renamed steps, cancellation of removed steps, cockpit migration screen |
| Compatibility | DMN decision-table import; BPMN subset import |
| 1.0 | API freeze; deprecation policy; documentation site from the wiki |

Exit: every "must" green; two adopters outside the maintainers in production or pilot, at least one using `agentTask`.

## After 1.0 (candidates)
Message correlation between processes · more databases through the storage SPI · Jakarta EE integration · hosted cockpit · multi-agent planning with ad-hoc step selection.

Proposals are issues labelled `type:proposal`; accepted ones become ADRs and move into a milestone.
