# Product Roadmap

Planned in weeks. Targets, not commitments; release notes state what shipped. Each milestone is anchored on a **measurable customer/operator outcome**, paired with explicit **Strategy Red-Team kill criteria**, and exits on [[Technical Requirements]] "must" items. The library is deliberately small, which is what makes this pace plausible.

![Milestones](images/roadmap-timeline.svg)

## Strategic Positioning & Moat Summary

- **Who this serves:** Java / Spring Boot engineering and banking product teams needing governed business rules, human/service approvals, and bounded AI agents without deploying a separate Zeebe + Elasticsearch cluster or paying per-instance enterprise BPM fees.
- **Why HORUS wins ("Can't or Won't" vs Camunda 8, Flowable, and raw LLM frameworks):**
  1. **Zero-cluster embedded footprint:** One Spring Boot starter + host PostgreSQL (`rle_`, `pre_`). In-process rule evaluation (`<100 µs` p99) and host business state commit in the same database.
  2. **Business-user rule governance out of the box:** Draft → simulate → diff → maker-checker approval → effective dating → explainable decision log without code redeployments.
  3. **Agentic inside deterministic rails (`agentTask`):** AI agents execute outside DB locks with bounded inputs, JSON Schema output validation, deterministic Rule Engine guardrails, and automatic `route-to-human` maker-checker escalation.

---

## M0 — Architecture (2 weeks, to 4 Dec 2026)

**Outcome:** *Enable core contributors and host-service architects to verify the storage schemas, transaction boundaries, expression grammar, and module dependency rules against a live PostgreSQL instance before engine implementation begins.*

This wiki as the public specification · storage model · expression grammar · ADR-0001 to 0007 · repository skeleton with CI and licence checks · `examples/rule-engine-only` starts against PostgreSQL.

## M1 — Rule Engine 0.1, Cockpit 0.1 (6 weeks, to 15 Jan 2027)

**Outcome:** *Enable banking services and compliance/product teams to evaluate deterministic rules in under 100 µs and govern YAML-authored rule changes via simulation, diff, and maker-checker approval without redeploying application code.*

Goal: the engine is usable in production from YAML-authored rule sets, with governance and audit in place. The interactive editor is deliberately deferred so the core ships early.
| Area | Ships |
|---|---|
| Definitions | Typed inputs and outputs; `first`, `unique`, `collect`; YAML/JSON import and export; row-level validation |
| Expression language | Grammar as documented; money and dates; whitelisted and host-registered pure functions |
| Evaluation | Java API, batch, explicit clock, version pinning, compiled cache with multi-replica invalidation (`LISTEN/NOTIFY` + version epoch) |
| Versioning | Draft → pending → published → retired; effective dates; maker-checker; version diff |
| Decision log | Transactional and async; masking; query; explain |
| Authoring | By YAML/JSON: files loaded at start-up, import endpoint, export; fits a GitOps flow |
| Simulation | Against sample inputs (paste or CSV) for a draft version |
| REST and cockpit 0.1 | `/api/bpm/rules/v1`; cockpit as **viewer and approver**: rule set and version viewer, diff, simulation, request/approve/reject with maker-checker, decision log explorer with explain. No in-browser table editor yet |
| Reliability | `Idempotency-Key` on every mutating endpoint and on evaluate; `rle_idempotency` with retention |
| Tooling | `rule-engine-test` with JUnit extension and coverage report |

Exit: TR-EMB, TR-DET, TR-SEC, TR-TEN, TR-IDEM-01 (rules), TR-OBS-01/02/04, TR-PERF-01/03, TR-QA-01, TR-LIC green; a newcomer completes Getting Started in under 30 minutes.

## M2 — Process Engine 0.1 with `agentTask`, Cockpit 0.2 (8 weeks, to 12 Mar 2027)

**Outcome:** *Enable engineering and product teams to orchestrate human approvals, compensating service sagas, and bounded AI agents (`agentTask`) inside their existing Spring Boot service and PostgreSQL database—with deterministic compliance rails and zero held DB connections during slow LLM/HTTP calls.*

Goal: processes with humans, services and agents as participants, governed by rules. `agentTask` is in scope here because it is a service step plus schema validation plus a guardrail decision, all of which exist by then.
| Area | Ships |
|---|---|
| Definitions | JSON/YAML, versioned; `start`, `end`, `userTask`, `serviceTask`, `decision`, `exclusiveGateway`, `parallelGateway`, `timer`, `callProcess` |
| Execution | Sync host-transaction steps vs Async Wait-State steps (`agentTask` and remote `serviceTask` execute outside DB locks); replica-safe timers and row-locked `parallelGateway` joins; history; incidents with retry and resolve |
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

**Outcome:** *Enable enterprise adopters to operate multi-tenant workflows and AI agent pipelines in production at scale with MCP/LLM adapter kits, offline/CI agent eval replay, zero-downtime upgrades, and long-term API stability.*

| Area | Ships |
|---|---|
| Agentic depth | MCP tool adapter; reference adapters for common LLM APIs in `examples/`; agent memory passed as bounded context; ad-hoc step selection inside a bounded block (multi-step agent planning under the same guardrails); **offline/CI Agent Eval runner replaying historical traces and human overrides against updated prompts/guardrails** |
| Simulation | Simulation reports attached to publish requests; what-if comparison of two versions over a period |
| Operations | Partitioning, zero-downtime upgrade tests, OpenTelemetry spans, explain templates |
| Migration | Explicit step mapping for renamed steps, cancellation of removed steps, cockpit migration screen |
| Compatibility | DMN decision-table import; BPMN subset import |
| 1.0 | API freeze; deprecation policy; documentation site from the wiki |

Exit: every "must" green; two adopters outside the maintainers in production or pilot, at least one using `agentTask`.

## Strategy Red-Team: Top Kill-Assumptions & Guardrails

| # | Load-Bearing Claim | Fails If… | Cheapest Validation Test | Kill / Course-Correct Criterion |
|---|---|---|---|---|
| 1 | **`agentTask` in an embedded Spring library won't exhaust host DB pools** | Synchronous transaction wraps slow LLM/HTTP calls, starving HikariCP under 20+ concurrent agents | Load test in M2 with 5s mock LLM latency and a 10-connection pool using Async Wait-State lease-commit-execute-complete | Reject any sync execution path for remote adapters; Async Wait-State is mandatory |
| 2 | **Compiled rule caches stay coherent across pods without Kafka/Redis** | Pod 2 evaluates stale rule `v1` after Pod 1 publishes `v2`, violating `TR-DET-01` | Two-node Testcontainers integration test verifying `LISTEN/NOTIFY` + version-epoch fallback through connection pooling | Default to version-epoch check with short TTL if `LISTEN/NOTIFY` drops across customer PgBouncer setups |
| 3 | **6-week M1 pace holds without delaying the first consuming service** | YAML authoring / governance flows still hit UX friction or scope creeps back into Cockpit 0.1 | Run `examples/rule-engine-only` + Cockpit 0.1 approval flow end-to-end by Week 4 of M1 | Cut non-essential REST filters or defer async outbox batching rather than slipping Rule Engine 0.1 past 15 Jan 2027 |
| 4 | **Dual-licensing (PolyForm Noncommercial + HORUS Commercial) passes bank procurement** | Target banks reject source-available PolyForm dependencies vs Apache 2.0 Camunda 7 forks | Validate commercial license agreement & indemnification terms with 2 prospective bank/SI legal contacts during M0–M1 | Prepare OEM commercial packaging rider or evaluate Apache 2.0 core + commercial enterprise pack if 2+ deals block |
| 5 | **Camunda 8 adding RDBMS support doesn't neutralize our moat** | Camunda ships an embeddable zero-cluster library mode with native maker-checker rule governance | Track Camunda 8 RDBMS releases quarterly; verify whether Zeebe cluster remains separate | Double down on deterministic `agentTask` guardrails + business-user rule replay + predictable non-metered pricing |

## After 1.0 (candidates)
Message correlation between processes · more databases through the storage SPI · Jakarta EE integration · hosted cockpit · multi-agent planning with ad-hoc step selection.

Proposals are issues labelled `type:proposal`; accepted ones become ADRs and move into a milestone.
