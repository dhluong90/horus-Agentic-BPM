# Product Roadmap

| Field | Value |
|---|---|
| Type | Explanation and plan |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

Design preview: nothing is released. This file owns milestones, dates and the order of work (PD-002). Feature scope and status are owned by the [feature map](feature-map.md); this roadmap names features by id. Reasons for scope choices are in the [decision log](decisions.md). The short public view is the wiki page [Roadmap](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Roadmap). Dates are targets, not commitments; [Release Notes](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Release-Notes) state what shipped.

Pending decisions that would change this roadmap are marked "(PD-nnn, pending)". The cockpit direction (PD-032 to PD-042) is shown separately in [Scenario: Camunda-like cockpit](#scenario-camunda-like-cockpit). Until a pending entry is decided, the roadmap shows the scope before that decision, and cut candidates stay in their milestone.

## At a glance

| Milestone | Dates (target) | Outcome | Features |
|---|---|---|---|
| M0 Architecture | 18 Nov to 4 Dec 2026 (2 weeks) | Contributors verify schema, transaction boundaries and module rules against a live PostgreSQL; the product design is decided | 3 |
| M1 Rule Engine 0.1, Cockpit 0.1 | 7 Dec 2026 to 15 Jan 2027 (6 weeks) | Business staff change governed rules without a deployment | 46 |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 18 Jan to 12 Mar 2027 (8 weeks) | Teams run approvals, sagas and guarded AI agent steps in their own service | 44 |
| M3 Agentic depth, compatibility, 1.0 | 15 Mar to 7 May 2027 (8 weeks) | Adopters run in production with evaluated agent changes and a frozen API | 19 |
| After 1.0 | Candidates | Broader reach: message correlation, multi-agent planning, more databases | 6 |

Counts come from the [feature map summary](feature-map.md#summary). The dates are unchanged; whether they can hold is PD-031 (pending), which recommends keeping them with a pre-approved cut list.

## Strategic context

### Who this serves

Java and Spring Boot teams, in banking and elsewhere, that need governed business rules, human approvals and bounded AI agents inside their own service and database, without a separate engine cluster.

### Why this order

| Choice | Reason |
|---|---|
| Rule Engine first | Useful alone; it is the guardrail mechanism `agentTask` needs and the `decision` step calls. It exercises storage, versioning and the cockpit contract on the smallest surface. Delaying it delays every Process Engine feature that depends on it (PE-F-09, PE-F-18, PE-F-19) |
| Cockpit 0.1 with Rule Engine 0.1 | The M1 outcome names business staff and approvers; without an approval screen the outcome is true only for developers |
| `agentTask` in M2, not M3 | It is a service step plus schema validation plus a guardrail decision; all three exist once the Process Engine core and the Rule Engine are in place. It is also the differentiator adopters evaluate first |
| Grid editor in Cockpit 0.2 | YAML authoring with import and export is enough for a GitOps flow and let Rule Engine 0.1 ship six weeks earlier |
| Agentic depth and compatibility in M3 | MCP and model adapters, the offline runner and import formats build on a stable core; the API freezes only after they have been used |

### How scope is cut

Sequencing uses cost of delay across the features that wait on a dependency, and the Kano split between must-be qualities and differentiators.

| Class | Features | Rule |
|---|---|---|
| Must-be in a regulated service | Tenant isolation, idempotency, maker-checker, append-only audit, masking, the async wait-state protocol, the `agentTask` output gate | Never cut or deferred |
| Differentiators | Engine-enforced governance (RE-F-06, RE-F-07, RE-F-21), decision replay (RE-F-05), guarded agent steps (PE-F-16 to PE-F-22) | Protected; the milestone moves before these are cut |
| Breadth and convenience | Batch evaluate, async log mode, replay simulation, calendar editor screen, `callProcess`, import formats, what-if, documentation site | Cut first, in the order in PD-031 |

## M0: Architecture (18 Nov to 4 Dec 2026)

**Outcome.** Enable contributors and host-service architects to verify the storage schema, transaction boundaries, expression grammar and module dependency rules against a live PostgreSQL before engine code is written, so that M1 builds on decided contracts.

**Features.** X-F-01 (example host boots and creates `rle_*` tables), X-F-12 (SBOM, vulnerability and licence gates in CI), X-F-13 (spec-to-test coverage check).

**Exit criteria.**

| Criterion | Verification |
|---|---|
| `TR-EMB-01`: `examples/rule-engine-only` boots in CI from a clean database and creates the `rle_*` tables, including the partitioned decision log (PD-004) | CI |
| `TR-LIC-01`, `TR-SEC-06`, `TR-QA-01` checks run in CI on every pull request | CI |
| The product design review is complete: PD-012 to PD-031 decided by the product owner | [Decision log](decisions.md) |
| ADR-0008 and ADR-0009 accepted (PD-029, PD-030) | ADR status |
| Design questions that gate M1 code answered: Jackson major for `*-core` (ADR-0011), Maven `groupId`, standalone cockpit host switching | Module DESIGN files |
| Every M1 feature has at least one SPEC statement (19 M1 spec gaps today) | [Feature map](feature-map.md) |

**Deferred.** Any evaluation code.

**Why first.** Contract changes are free before M1 and expensive after (PD-003 to PD-008 were decided for this reason).

## M1: Rule Engine 0.1 and Cockpit 0.1 (7 Dec 2026 to 15 Jan 2027)

**Outcome.** Enable product, risk and compliance staff to change YAML-authored decision tables through simulation, diff and a second approver, and host services to evaluate them in process, so that rule changes no longer wait for a code deployment and every decision can be reconstructed.

**Measures** (input measures until a North Star is decided, PD-019, pending): a newcomer reaches a first logged decision in under 30 minutes; an analyst reaches `PENDING_APPROVAL` without developer help.

**Features.**

| Area | Features |
|---|---|
| Decision tables and expression language | RE-F-01, RE-F-02, RE-F-03, RE-F-04, RE-F-27 |
| Versions and governance | RE-F-05, RE-F-06, RE-F-07, RE-F-08 |
| Evaluation and cache | RE-F-09, RE-F-10, RE-F-20, RE-F-26 (cut candidate), RE-F-32 |
| Tenancy and masking | RE-F-11, RE-F-12, RE-F-13 |
| Decision log | RE-F-14, RE-F-15 (cut candidate), RE-F-16, RE-F-17, RE-F-18 |
| Authoring and simulation | RE-F-21, RE-F-25 |
| Testing and observability | RE-F-28, RE-F-29 |
| Cockpit 0.1: viewer and approver | CK-F-01, CK-F-02, CK-F-03, CK-F-04, CK-F-05, CK-F-06, CK-F-07, CK-F-08, CK-F-09 |
| Cross-cutting | X-F-02, X-F-03, X-F-04, X-F-05, X-F-06, X-F-07, X-F-08, X-F-09, X-F-14, X-F-16, X-F-19 |

**Exit criteria.** Every Must that applies to the Rule Engine and Cockpit 0.1 is green in CI ([requirements](requirements.md), PD-021, pending):

| Group | Musts that gate M1 |
|---|---|
| Embedding | TR-EMB-01, TR-EMB-02, TR-EMB-03, TR-EMB-04, TR-EMB-05, TR-EMB-06 |
| Determinism | TR-DET-01, TR-DET-02, TR-DET-03, TR-DET-04, TR-DET-05 |
| Security | TR-SEC-01, TR-SEC-02, TR-SEC-03, TR-SEC-04, TR-SEC-05, TR-SEC-06, TR-SEC-08, TR-SEC-09, TR-SEC-10 |
| Retries (rules) | TR-IDEM-01, TR-IDEM-05 |
| Cache | TR-ASY-04, TR-PERF-06 |
| Tenancy | TR-TEN-01, TR-TEN-02 |
| Audit and events | TR-OBS-01, TR-OBS-02, TR-OBS-04, TR-OBS-06 |
| Performance | TR-PERF-01, TR-PERF-03 |
| Operability | TR-OPS-01, TR-OPS-05 |
| Cockpit | TR-UI-01; TR-UI-02 from M2 if PD-013 is decided as recommended; TR-UI-03 and TR-UI-04 if PD-026 is decided as recommended |
| Quality and licences | TR-QA-01, TR-QA-02, TR-LIC-01, TR-LIC-02 |

And: `examples/rule-engine-only` runs the full journey with Cockpit 0.1 (import, simulate, request, approve by a second identity, evaluate, explain); a newcomer completes Getting Started in under 30 minutes. Reported, not gating: TR-PERF-02, TR-SEC-07, TR-OBS-05, TR-QA-03.

**Dependencies and sequencing.**

| Needs | Before | Why |
|---|---|---|
| ADR-0009 accepted (PD-030) | RE-F-09, RE-F-10 | One cache design to build |
| Jackson major fixed (ADR-0011) | Any `*-core` code | Spring Boot 4 and 3.5 use different Jackson majors |
| RE-F-02 validation | RE-F-25 import, RE-F-21 simulation, CK-F-02 | Import and simulation run validation first |
| RE-F-14 decision log | RE-F-05 replay, RE-F-17 query, RE-F-18 explain | All read the log |
| RE-F-06 and RE-F-07 version lifecycle | CK-F-04 approvals | The screen reflects engine states |
| Walking skeleton first | Everything else | Import, simulate, approve and evaluate one table end to end by week 3, then widen (kill assumption K12) |

**Deferred.**

| Item | To | Reason |
|---|---|---|
| Grid editor (CK-F-10) | M2 | YAML is enough for the first adopters; saves six weeks |
| Replay simulation (RE-F-22, CK-F-11) | M2 | Needs a populated log; pulled into M1 only if K7 fails (PD-018, pending) |
| Explain templates (RE-F-19) | M3 | Generated explanation first (PD-027, pending) |
| Simulation reports on publication requests (RE-F-23, CK-F-20) | M3 | PD-022, pending |
| Decision log retention (RE-F-31) | M3 | Table is partitioned in M1; purge can wait for pilot volumes (PD-020, pending) |
| DMN import (RE-F-30) | M3 | Compatibility after the core |
| Cut if the 8 Jan 2027 checkpoint fails | M2 | RE-F-15 async log, RE-F-26 batch evaluate, decision log filters beyond rule set and period (PD-031, pending) |

## M2: Process Engine 0.1 with `agentTask`, Cockpit 0.2 (18 Jan to 12 Mar 2027)

**Outcome.** Enable Java teams to orchestrate human approvals, compensating service sagas and bounded AI agent steps inside their own Spring Boot service and PostgreSQL, so that agent output passes deterministic rules before a process moves and no database connection is held during a slow model or HTTP call.

**Measures** (PD-019, pending): external teams running `examples/both`; share of escalated agent tasks completed by a person within SLA in pilot hosts; human override rate read from history.

**Features.**

| Area | Features |
|---|---|
| Definitions and execution | PE-F-01, PE-F-04, PE-F-05, PE-F-06, PE-F-08, PE-F-09, PE-F-12 (cut candidate), PE-F-14, PE-F-15 |
| Retries and concurrency | PE-F-02, PE-F-03, PE-F-16, PE-F-17 |
| Human tasks and SLA | PE-F-07, PE-F-10, PE-F-11, PE-F-13 |
| `agentTask` | PE-F-18, PE-F-19, PE-F-20, PE-F-21, PE-F-22, PE-F-23 (if PD-014), PE-F-24, PE-F-38 |
| Incidents and compensation | PE-F-25, PE-F-26, PE-F-27 |
| Migration | PE-F-29, PE-F-30 (cut candidate) |
| API and observability | PE-F-28, PE-F-32, PE-F-33 |
| Rule Engine | RE-F-22 (cut candidate) |
| Cockpit 0.2: editor and operations | CK-F-10, CK-F-11 (cut candidate), CK-F-12, CK-F-13, CK-F-14, CK-F-15, CK-F-16, CK-F-17, CK-F-18 (cut candidate) |
| Cross-cutting | X-F-15 |

**Exit criteria.** All M1 gates stay green, and every Must that applies to the Process Engine and Cockpit 0.2 is green:

| Group | Musts that gate M2 |
|---|---|
| Retries (processes) | TR-IDEM-01, TR-IDEM-02, TR-IDEM-03, TR-IDEM-04, TR-IDEM-05 |
| Async steps | TR-ASY-01, TR-ASY-02, TR-ASY-03 |
| Compensation | TR-COMP-01, TR-COMP-02, TR-COMP-03 |
| Calendars | TR-CAL-01, TR-CAL-02 |
| Migration | TR-MIG-01, TR-MIG-02 (moves to M3 if the PD-017 trigger fires) |
| Agents | TR-AGT-01, TR-AGT-02, TR-AGT-03 |
| Replicas and operations | TR-PERF-05, TR-OPS-03 |
| Untagged Musts, now also for processes | TR-TEN-01, TR-TEN-02, TR-SEC-03, TR-SEC-04, TR-SEC-05, TR-SEC-08, TR-OBS-01, TR-OBS-02, TR-OBS-04, TR-OBS-06, TR-OPS-05, TR-QA-01 |
| Cockpit | TR-UI-01, TR-UI-02 (PD-013, pending), TR-UI-03 and TR-UI-04 re-run on 0.2 screens (PD-026, pending) |

And: `examples/both` runs a process with a `decision` step and an `agentTask` guarded by a rule set and escalated to a person (X-F-15); the pool test of kill assumption K1 passes. Reported, not gating: TR-PERF-04 (PD-026, pending).

**Dependencies and sequencing.**

| Needs | Before | Why |
|---|---|---|
| Rule Engine 0.1 (RE-F-01, RE-F-20) | PE-F-09, PE-F-18, PE-F-19 | Decision steps and guardrails are rule set evaluations |
| ADR-0008 accepted (PD-029) | PE-F-16, PE-F-17 | One async protocol to build |
| Child compensation question answered (process DESIGN, gate M2 start) | PE-F-12, PE-F-27 | Defines how `callProcess` and `saga` interact |
| PE-F-16 async wait-state | PE-F-08 remote, PE-F-18 to PE-F-24 | Every external call uses it |
| PE-F-15 history | PE-F-22, CK-F-12, CK-F-16 | Agent audit and the timeline read history |
| Walking skeleton first | Everything else | Start, `userTask`, `decision`, `agentTask` with a stub adapter and `route-to-human`, end, by week 3; then compensation, timers, migration |

**Deferred.**

| Item | To | Reason |
|---|---|---|
| MCP and reference model adapters (PE-F-34, PE-F-35), agent memory (PE-F-36), offline runner (PE-F-37) | M3 | Build on recorded M2 history |
| Renamed-step migration and the migration screen (PE-F-31, CK-F-19) | M3 | PD-023, pending |
| Message correlation (PE-F-40) | After 1.0 | Outside the single-service scope until 1.0 |
| Cut if the 19 Feb 2027 checkpoint fails (fewer than 9 of 17 `PRE-*` green) | M3 | In order: PE-F-30 migration execution (PD-017), CK-F-18 calendar editor, PE-F-12 `callProcess`, RE-F-22 and CK-F-11 replay (PD-031, pending) |

The Lunar New Year holidays fall in the first half of February 2027, inside M2; plan capacity for about seven working weeks.

## M3: Agentic depth, compatibility, 1.0 (15 Mar to 7 May 2027)

**Outcome.** Enable adopters to run multi-tenant workflows and agent steps in production, to evaluate a changed prompt or guardrail offline against recorded traces before it goes live, and to upgrade without downtime on a frozen API, so that two teams outside the maintainers depend on the engines.

**Measures.** Two adopters outside the maintainers in production or pilot, at least one using `agentTask`; North Star reporting starts if PD-019 is decided as recommended.

**Features.**

| Area | Features |
|---|---|
| Agentic depth | PE-F-34, PE-F-35, PE-F-36, PE-F-37, PE-F-39 (PD-028 proposes after 1.0), PE-F-43 (if PD-015) |
| Simulation and explanation | RE-F-19, RE-F-23, RE-F-24 (cut candidate), CK-F-20 |
| Operations | RE-F-31, X-F-10, X-F-11 |
| Migration | PE-F-31, CK-F-19 |
| Compatibility | RE-F-30 (cut candidate), PE-F-41 (cut candidate) |
| 1.0 | X-F-17 (cut candidate), X-F-18 |

**Exit criteria.** Every Must green. Reported: TR-OPS-02, TR-OPS-04, TR-OBS-03, TR-MIG-03, TR-AGT-04. API frozen with the deprecation policy published (X-F-18). Two adopters outside the maintainers in production or pilot, at least one using `agentTask` (kill assumption K13).

**Dependencies and sequencing.**

| Needs | Before | Why |
|---|---|---|
| M2 history with agent I/O and overrides (PE-F-22) | PE-F-37 offline runner, PE-F-36 memory | The runner replays what M2 recorded |
| PE-F-24 adapter SPI | PE-F-34, PE-F-35 | Adapters are examples on the SPI |
| PE-F-30 migration execution | PE-F-31, CK-F-19 | Mapping extends execution |
| All features in | X-F-18 API freeze | Freeze last, after use |

**Deferred.** Agent-chosen steps (PE-F-39) and multi-agent planning (PE-F-42) after 1.0 (PD-028, pending). Cut if the 9 Apr 2027 checkpoint fails: PE-F-41 BPMN import (unless K5 fired), RE-F-30 DMN import, RE-F-24 what-if, X-F-17 documentation site (PD-031, pending).

## After 1.0 (candidates)

PE-F-40 message correlation; PE-F-42 multi-agent planning; CK-F-21 pulse strip (PD-016, pending); CK-F-22 hosted cockpit; X-F-20 other databases through a storage SPI; X-F-21 Jakarta EE integration. Proposals are issues labelled `type:proposal`; accepted ones become ADRs and decision log entries and then move into a milestone.

## Kill assumptions

Load-bearing assumptions behind this roadmap, each with the cheapest test and the point at which the plan changes. Ranked by impact if wrong, likelihood of being wrong and cost of testing; the top five are tested first.

### Test first

| Rank | Id | Claim | Fails if | Cheapest test | Kill criterion | Then |
|---|---|---|---|---|---|---|
| 1 | K13 | Two teams outside the maintainers adopt by 7 May 2027 | No named team has a use case, a host service and time for a pilot by M2 exit | List candidate teams now; two conversations per month from M1 | Fewer than two named pilot teams with a scheduled start by 12 Mar 2027 | Restate the M3 exit as "1.0 frozen, adopters in evaluation"; keep the date |
| 2 | K4 | Guardrail rules over structured output catch the agent errors that matter | Costly errors (a misread date with high confidence) pass schema and guardrail | KYC example with 30 labelled outputs, 10 with seeded critical errors, stub adapter, one day | More than 2 of 10 seeded critical errors pass | Publish rung 1 as the only guidance until the offline runner exists |
| 3 | K3 | Analysts author changes in YAML well enough for 0.1 | Every change still needs a developer, so the M1 outcome is true only for developers | Two moderated sessions in week 2 of M1, including a quoting error (PD-005) | Neither analyst reaches `PENDING_APPROVAL` without help | Restate the M1 outcome as developer-mediated; raise the grid editor's priority in M2 |
| 4 | K10 | The closed expression language covers real fee, limit and eligibility tables | Common tables need constructs outside the grammar | Rewrite ten public sample tables in the grammar, one afternoon in M0 | More than two of ten need a host function or cannot be expressed | Extend built-in functions before M1 exit; never add scripting |
| 5 | K11 | The M2 scope fits eight weeks with the Lunar New Year break | Async wait-state, compensation and migration slip together and `agentTask` lands late | Weekly count of `PRE-*` statements green | Fewer than 9 of 17 green at the end of week 5 | Apply the M2 cut list (PD-017, PD-031) |

### All assumptions

| Id | Claim | Fails if | Cheapest test | Kill criterion | Then |
|---|---|---|---|---|---|
| K1 | `agentTask` inside an embedded library does not exhaust the host's connection pool | A transaction wraps a slow model or HTTP call and starves the pool | M2 load test: 20 concurrent 5 s adapters on a 10-connection pool; 10 concurrent 2 s adapters on a 2-connection pool (`TR-ASY-01`) | Any pool timeout | Keep async wait-state mandatory; reject any synchronous path for remote adapters |
| K2 | Compiled rule caches stay coherent across replicas without a broker | A replica serves version 1 after version 2 is effective elsewhere | Two-replica test, direct and through a transaction-mode pooler (`TR-ASY-04`) | Stale beyond 5 s in either run | Default to the epoch check only where `LISTEN/NOTIFY` is unreliable |
| K3 | Analysts author YAML well enough for 0.1 | See "Test first" | | | |
| K4 | Guardrail rules catch the agent errors that matter | See "Test first" | | | |
| K5 | Evaluators accept a YAML or JSON definition subset without a modeller | Architects shortlist only engines with BPMN diagrams | Three architect conversations during M1 | Two of three would not shortlist without a modeller | Move BPMN subset import (PE-F-41) ahead of agentic depth in M3 and off the cut list |
| K6 | Mandatory four-eyes is a reason to adopt, not a blocker | Teams need same-day single-person changes | Three compliance conversations during M1 | Two of three require a single-person path | Open an ADR for a logged emergency path after 1.0 (PD-012) |
| K7 | Diff plus sample simulation is enough to approve in 0.1 | Approvers refuse without the effect on real traffic | One moderated approver session in week 3 of M1 with a 20-row change | The approver cannot say who is affected, or defers | Move replay simulation into M1 and cut decision log filters (PD-018) |
| K8 | Approvers will approve in the cockpit | Compliance requires approval in an existing ticket or workflow tool | Three compliance conversations in M1 | Two of three refuse a second approval tool | Document approval through the REST API and the publish-requested event as the primary path; keep the screen |
| K9 | A stateless cockpit is enough for operators | Operators need push alerts from the tool itself | Two operations lead conversations before M2 | Both need push alerts | Document alerting on engine metrics and events in the host's monitoring; keep the cockpit stateless |
| K10 | The closed expression language covers real tables | See "Test first" | | | |
| K11 | The M2 scope fits | See "Test first" | | | |
| K12 | Six weeks (about five working weeks) are enough for Rule Engine 0.1 | Authoring or approval friction, or editor scope creeps into Cockpit 0.1 | `examples/rule-engine-only` and the Cockpit 0.1 approval flow end to end by 8 Jan 2027 | Not end to end on that date | Apply the M1 cut list (PD-031) rather than slip past 15 Jan 2027 |
| K13 | Two outside teams adopt by 7 May 2027 | See "Test first" | | | |
| K14 | Established BPM vendors do not ship an embedded, cluster-free mode with built-in rule governance | A vendor releases an embeddable library with maker-checker rule governance | Review competitor releases quarterly | Such a release | Concentrate on deterministic `agentTask` guardrails, the offline runner and decision replay |

### What holds up

- The order Rule Engine, then Process Engine with `agentTask`, then agentic depth follows real dependencies: guardrails and `decision` steps are rule set evaluations.
- The async wait-state protocol (ADR-0008) and the cache design (ADR-0009) answer K1 and K2 by design; the tests confirm rather than discover.
- Contract decisions PD-003 to PD-008 were made before any adopter exists, when they cost nothing.

### What this roadmap cannot assess

- Team capacity per milestone: the dates assume full focus on the engines (PD-010) and no other commitments.
- Adopter demand: no adopter conversations are recorded yet; K13 is the first test for that reason.

## Scenario: Camunda-like cockpit

Not adopted. This section shows the roadmap as it would be if the product owner accepts PD-032 to PD-038 as recommended and chooses option 2 of [PD-042](decisions.md#pd-042-milestone-dates-if-the-cockpit-direction-is-accepted). The plan above stays in force until then. The proposed features are listed in the [feature map](feature-map.md#proposed-camunda-like-cockpit-pending-pd-032-to-pd-042).

### What the scenario adds

The product owner asked for Camunda-like tools on top of embedded engines. The scenario delivers diagrams, operations on the diagram, a task inbox across hosts and governed process change. It does not deliver fleet analytics or full BPMN 2.0 ([what is included](decisions.md#pending-cockpit-direction-camunda-like-cockpit)).

| Decision | Recommendation | Where it lands |
|---|---|---|
| PD-032 Diagrams and modeller | Read-only diagrams in M2; modeller canvas in M3 as the first M3 cut, fallback 1.1; its engine APIs before the freeze | M2, M3 |
| PD-033 Maker-checker for definitions | Lifecycle, permissions, events and graph diff in the engine in M2; screens in M3; `dev` may auto-approve | M2, M3 |
| PD-034 Notation | Own JSON canonical; BPMN subset import protected, export added as a cut candidate | M3 |
| PD-035 Multi-host cockpit | Option B with A supported, D documented, C rejected for now; registry and `/meta` and `/me` in M1, merged inbox and incidents in M2 | M1, M2 |
| PD-036 Source of truth | Git and cockpit both; imports arrive as drafts; per-environment switch for cockpit authoring | M2 |
| PD-037 Tenant switching | Header only within the token's allowed tenants; no cross-tenant view | M2 |
| PD-038 Analytics | Live counts per node in M2; hourly rollup and heatmap in M3; no aggregator before a paying customer | M2, M3 |

### Milestones under the scenario

| Milestone | Dates (target) | Change | Outcome | Features |
|---|---|---|---|---|
| M0 Architecture | 18 Nov to 4 Dec 2026 (2 weeks) | Date unchanged. Adds PD-032 to PD-042 to the decisions that must be made, the two-day diagram spike, and ADR-0012 to ADR-0015 drafted | Contributors verify schema, transaction boundaries and module rules; the product design, including the cockpit direction, is decided | 3 |
| M1 Rule Engine 0.1, Cockpit 0.1 | 7 Dec 2026 to 15 Jan 2027 (6 weeks) | Date unchanged. Adds CK-F-23 and X-F-22 | Business staff change governed rules without a deployment, from one cockpit that reaches every host | 48 |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 18 Jan to 26 Mar 2027 (10 weeks, was 12 Mar) | +2 weeks. Adds 14 features; CK-F-18, RE-F-22 and CK-F-11 move to M3 | Teams run approvals, sagas and guarded AI agent steps in their own service, and operators see and work every instance on its diagram from one inbox | 55 |
| M3 Agentic depth, compatibility, 1.0 | 29 Mar to 21 May 2027 (8 weeks, was 15 Mar to 7 May) | +2 weeks. Adds 8 features and the 3 moved from M2; PE-F-41 leaves the cut list | Adopters run in production with evaluated agent changes, governed process changes and a frozen API | 30 |
| After 1.0 | Candidates | Adds the modeller canvas if cut from M3; option C aggregator only with a paying customer | Unchanged | 6 |

Scheduled features rise from 112 to 136 (M0 to M3). If PD-028 is accepted, M3 holds 29.

### M1 under the scenario

| Area | Features added |
|---|---|
| Cockpit 0.1 | CK-F-23 host registry, single sign-in with OIDC PKCE, host switching, screens hidden by `/meta` and `/me` |
| Cross-cutting | X-F-22 `/api/bpm/meta` and `/api/bpm/me` |

Sizing: a few days, not part of the architect's M2 estimate (assumption until the architect sizes it). The M1 date and the PD-031 M1 cut list are unchanged. The M0 gate "standalone cockpit host switching" is answered by PD-035.

### M2 under the scenario

| Area | Features added | Moved out |
|---|---|---|
| Definitions | PE-F-45 lifecycle with maker-checker, PE-F-46 graph diff | |
| Read models | PE-F-47 activity history, PE-F-48 live node counts, X-F-24 bounded queries | |
| Human tasks | PE-F-50 task query, PE-F-51 task forms | |
| Tenancy | X-F-23 tenant switching | |
| Cockpit 0.2 | CK-F-24 definition diagram, CK-F-25 instance diagram with tokens, CK-F-26 live node counts (cut candidate), CK-F-28 merged inbox, CK-F-29 merged incidents, CK-F-30 task forms | CK-F-18 calendar editor to M3 |
| Rule Engine | | RE-F-22 and CK-F-11 replay simulation to M3 |

Effort: about 3 weeks added (architect's estimate), about 1 week removed by the moves (assumption), about 2 weeks net, covered by the two-week date move. The remaining M2 cut list, in order: PE-F-30 migration execution (PD-017), CK-F-26 live node counts on the diagram, PE-F-12 `callProcess`. Checkpoint: end of week 7, 5 Mar 2027, trigger unchanged (fewer than 9 of 17 `PRE-*` green), plus the walking skeleton now includes one instance drawn with its token by week 4.

Added sequencing: ADR-0012 before PE-F-45; ADR-0013 and the M0 spike before CK-F-24; ADR-0015 before PE-F-47 and PE-F-48; PE-F-50 before CK-F-28; the diagram views follow PE-F-15 history and PE-F-28 API, which are already early in M2.

Added exit criteria: a process definition imported from Git arrives as a draft and is approved by a second identity before it runs (`TR-SEC-04` for processes); `examples/both` shows its instance as a diagram with the current token; a CI query-plan check proves the activity and task queries are bounded.

### M3 under the scenario

| Area | Features added |
|---|---|
| Moved from M2 | RE-F-22 and CK-F-11 replay simulation, CK-F-18 calendar editor (first M3 cut) |
| Modeller | PE-F-44 layout storage, PE-F-52 step catalogue, CK-F-32 canvas (cut candidate, fallback 1.1) |
| Governed change | CK-F-31 definition approval screens with graph diff |
| Analytics | PE-F-49 hourly node rollup, CK-F-27 historical heatmap (cut candidate) |
| Compatibility | PE-F-41 BPMN subset import (no longer a cut candidate), PE-F-53 BPMN subset export (cut candidate) |
| Migration | CK-F-33 migration plan as two diagrams |

Checkpoint: end of week 4, 23 Apr 2027. M3 cut list, in order: CK-F-18 calendar editor to after 1.0, CK-F-32 modeller canvas to 1.1, CK-F-27 historical heatmap to after 1.0, PE-F-53 BPMN export, RE-F-30 DMN import, RE-F-24 what-if, X-F-17 documentation site. Never cut in M3: the engine APIs that freeze at 1.0 (PE-F-44, PE-F-45, PE-F-46, PE-F-52), because adding them after the freeze would break the deprecation policy.

The April and May public holidays leave about seven working weeks. M3 is not estimated yet; at 30 features it carries about 4.3 per working week against about 2.7 today, which is why four of the new or moved features are cut candidates.

### Assumptions the scenario adds

| Rank | Id | Claim | Fails if | Cheapest test | Kill criterion | Then |
|---|---|---|---|---|---|---|
| 1 | K16 | In banks, process owners or analysts, not only developers, change process definitions, so a modeller has users | Every process change goes through IT, so the canvas is used only by developers who prefer Git | Ask three process owners or operations leads who changes an approval flow today and how long it takes; during M1 | Two of three say only IT changes processes | Move CK-F-32 to after 1.0; keep diagrams, lifecycle and step catalogue |
| 2 | K18 | The architect's estimate of about 3 weeks for M2 holds | The diagram views or the lifecycle take longer than planned | Two-day spike in M0: render `kyc-onboarding` with automatic layout and a token overlay in React Flow | The spike takes more than four days or needs a library that fails `TR-LIC-01` | Drop CK-F-26 from M2 and keep only the instance diagram; try maxGraph |
| 3 | K15 | A read-only diagram is what evaluators and operators need first, more than the modeller | Evaluators reject the product without editing in the browser | Show spike screenshots in the three K5 architect conversations and two operations lead conversations in M1 | Two of five require browser editing to shortlist | Bring CK-F-32 forward to the start of M3 and protect it; cut replay instead |
| 4 | K17 | Pilot banks run at most about 15 hosts and one IdP whose tokens every host accepts | A pilot cannot issue a token accepted across hosts, or runs more hosts | Ask the K13 pilot candidates for host count and IdP setup; during M1 | Either pilot cannot meet the condition | Run that pilot on option A, document option D through its gateway |

K5 (evaluators accept definitions without a modeller) is no longer the whole bet if PD-032 is accepted; it becomes a test of whether import (PE-F-41) moves ahead of agentic depth.

### Positioning under the scenario

Say: diagrams for every process, operations on the diagram, one inbox across services, and process changes approved by a second person, all inside the team's own service. Do not say: a Camunda replacement, process analytics, or full BPMN. The comparison we can win is governed change on an embedded engine; the one we cannot win is modeller polish.

## Pending decisions that affect this roadmap

PD-012 to PD-031 in the [decision log](decisions.md), summarised with recommendations in the [product design review](README.md). PD-032 to PD-042 (cockpit direction) would change it as shown in [Scenario: Camunda-like cockpit](#scenario-camunda-like-cockpit).
