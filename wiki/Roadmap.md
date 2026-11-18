# Roadmap

Each milestone states the outcome it enables, how the maintainers will know it is reached, what ships and what is deferred. Dates are targets, not commitments; [[Release Notes]] state what shipped. This page owns milestone dates and scope; other pages link here.

![Milestones](images/roadmap-timeline.svg)

## Who this serves and why this order

| Question | Answer |
|---|---|
| Who | Java and Spring Boot teams, in banking and elsewhere, that need governed business rules, human approvals and bounded AI agents inside their own service and database, without a separate engine cluster or per-instance BPM fees |
| Why rules first | The Rule Engine is useful alone and is the guardrail mechanism that `agentTask` needs later; it also exercises storage, versioning and the cockpit contract on the smallest surface |
| Why `agentTask` in M2 and not M3 | It is a service step plus schema validation plus a guardrail decision, all of which exist once the Process Engine core and the Rule Engine are in place |
| Why the grid editor is deferred to Cockpit 0.2 | YAML authoring with import and export is enough for a GitOps flow and lets Rule Engine 0.1 ship six weeks earlier |

## M0: Architecture (2 weeks, to 4 Dec 2026)

**Outcome.** Contributors and host-service architects can verify the storage schema, transaction boundaries, expression grammar and module dependency rules against a live PostgreSQL before engine code is written.

| Ships | Deferred |
|---|---|
| This wiki as the public specification; module `SPEC.md` and `DESIGN.md`; ADR-0001 to ADR-0007; repository skeleton with CI, licence check and wiki publishing; `examples/rule-engine-only` starts against PostgreSQL and creates the `rle_*` tables | Any evaluation code |

**Exit.** The example host boots in CI from a clean database (TR-EMB-01); every page in this wiki carries its status note.

## M1: Rule Engine 0.1 and Cockpit 0.1 (6 weeks, to 15 Jan 2027)

**Outcome.** A host service evaluates deterministic decision tables in process, and product or compliance staff change YAML-authored rules through simulation, diff and maker-checker approval without a code deployment.

| Area | Ships | Deferred to |
|---|---|---|
| Definitions | Typed inputs and outputs; `first`, `unique`, `collect`; YAML and JSON import and export; row-level validation | Grid editor: M2 |
| Expression language | Grammar as documented; money and dates; built-in and host-registered pure functions | |
| Evaluation | Java API, batch, explicit clock, version pinning; compiled cache with cross-replica invalidation | |
| Versioning | Draft, pending approval, published, retired; effective dates; maker-checker; version diff | |
| Decision log | Transactional and async modes; masking; query; explain | Explain templates: M3 |
| Simulation | Against sample inputs (paste or CSV) for a draft | Replay against history: M2; reports attached to publication requests: M3 |
| REST and Cockpit 0.1 | `/api/bpm/rules/v1`; cockpit as viewer and approver: rule sets, diff, simulation, approvals, decision log with explain | |
| Reliability | `Idempotency-Key` on every mutating endpoint and on evaluate | |
| Tooling | `rule-engine-test` with JUnit extension and row coverage report | |

**Exit.** TR-EMB, TR-DET, TR-SEC, TR-TEN, TR-IDEM-01 (rules), TR-OBS-01, TR-OBS-02, TR-OBS-04, TR-PERF-01, TR-PERF-03, TR-QA-01 and TR-LIC green in CI ([[Technical Requirements]]); a newcomer completes [[Getting Started]] in under 30 minutes.

## M2: Process Engine 0.1 with `agentTask`, Cockpit 0.2 (8 weeks, to 12 Mar 2027)

**Outcome.** Teams orchestrate human approvals, compensating service sagas and bounded AI agents inside their Spring Boot service and PostgreSQL, with deterministic guardrails and no database connection held during a slow model or HTTP call.

| Area | Ships | Deferred to |
|---|---|---|
| Definitions | JSON and YAML, versioned; `start`, `end`, `userTask`, `serviceTask`, `decision`, `exclusiveGateway`, `parallelGateway`, `timer`, `callProcess` | Message correlation: after 1.0 |
| Execution | Synchronous steps in the host transaction; async wait-state for `agentTask` and remote `serviceTask`; replica-safe timers; history; incidents with retry and resolve | |
| Human tasks | Assign by user, role or group; claim, complete, delegate, reassign; SLA and escalation; four-eyes approval | |
| Reliability | Idempotency keys on start and complete; unique business key per tenant and definition; optimistic locking | |
| Compensation | `compensate` handlers, `saga` blocks, reverse-order execution with retry and incidents | |
| Business calendar | `BusinessCalendar` SPI; built-in weekly hours, holidays and time zone per tenant; cockpit editor | |
| Migration | Plan, dry run and batched execution for same-id step mapping | Renamed-step mapping and cancellation: M3 |
| `agentTask` | Adapter SPI (in-process and HTTP); JSON Schema validation of output; guardrail rule set evaluated by the Rule Engine; `onViolation: route-to-human` or `fail`; timeout, retry, incident; full audit | MCP adapter, reference LLM adapters, planning depth: M3 |
| Cockpit 0.2 | Grid editor with type checks and expression validation; replay simulation; process screens; agent task inspector | |
| REST | `/api/bpm/processes/v1`: definitions, instances, task queues, incidents, history | |

**Exit.** TR-PERF-04, TR-PERF-05, TR-OPS-03, TR-IDEM, TR-COMP, TR-CAL, TR-MIG-01, TR-MIG-02, TR-AGT-01 to TR-AGT-03 and every "must" green; `examples/both` runs a process with a `decision` step and an `agentTask` guarded by a rule set and escalated to a human.

## M3: Agentic depth, compatibility, 1.0 (8 weeks, to 7 May 2027)

**Outcome.** Adopters run multi-tenant workflows and agent pipelines in production with adapter kits, offline evaluation of agent changes, zero-downtime upgrades and a frozen API.

| Area | Ships |
|---|---|
| Agentic depth | MCP tool adapter; reference adapters for common LLM APIs in `examples/`; bounded context passed as agent memory; ad-hoc step selection inside a bounded block under the same guardrails; offline runner that replays recorded agent traces and human overrides against changed prompts or guardrails |
| Simulation | Reports attached to publication requests; what-if comparison of two versions over a period |
| Operations | Monthly partitioning by configuration; zero-downtime upgrade tests; OpenTelemetry spans; explain templates |
| Migration | Explicit mapping for renamed steps; cancellation of removed steps; cockpit migration screen |
| Compatibility | DMN decision-table import; BPMN subset import |
| 1.0 | API freeze; deprecation policy; documentation site generated from this wiki |

**Exit.** Every "must" green; two adopters outside the maintainers in production or pilot, at least one using `agentTask`.

## Assumptions that could change the plan

| # | Assumption | It fails if | Cheapest test | Then |
|---|---|---|---|---|
| 1 | `agentTask` inside an embedded library does not exhaust the host's connection pool | A synchronous transaction wraps a slow model or HTTP call and starves the pool under 20 concurrent agents | M2 load test: 5 s simulated adapter latency, 10-connection pool, async wait-state protocol | Keep async wait-state mandatory; reject any synchronous path for remote adapters |
| 2 | Compiled rule caches stay coherent across replicas without a message broker | A replica keeps evaluating version 1 after another replica published version 2 (violates TR-DET-01) | Two-node Testcontainers test of `LISTEN/NOTIFY` with the version-epoch fallback behind a connection pooler | Default to the version-epoch check with a short TTL where `LISTEN/NOTIFY` is unreliable |
| 3 | Six weeks is enough for Rule Engine 0.1 | YAML authoring or the approval flow still has friction, or editor scope creeps back into Cockpit 0.1 | Run `examples/rule-engine-only` and the Cockpit 0.1 approval flow end to end by week 4 of M1 | Cut non-essential REST filters or async outbox batching rather than slip past 15 Jan 2027 |
| 4 | Dual licensing (PolyForm Noncommercial plus commercial) passes enterprise procurement | Prospects reject a source-available dependency in favour of Apache 2.0 alternatives | Review the commercial terms with two prospective legal contacts during M0 and M1 | Prepare an OEM rider, or evaluate an Apache 2.0 core with a commercial extension pack if two or more deals block |
| 5 | Established BPM vendors do not ship an embedded, cluster-free mode with built-in rule governance | A vendor releases an embeddable library with maker-checker rule governance | Review competitor releases quarterly | Concentrate on deterministic `agentTask` guardrails, decision replay and predictable non-metered pricing |

## After 1.0 (candidates)

Message correlation between processes; more databases through the storage SPI; Jakarta EE integration; hosted cockpit; multi-agent planning with ad-hoc step selection.

Proposals are issues labelled `type:proposal`; accepted ones become ADRs and move into a milestone. See [[Contributing]].
