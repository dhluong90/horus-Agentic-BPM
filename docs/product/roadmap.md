# Product Roadmap

| Field | Value |
|---|---|
| Type | Explanation and plan |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

Design preview: nothing is released. This file owns milestones, dates and the order of work (PD-002). Feature scope and status are owned by the [feature map](feature-map.md); this roadmap names features by id. Reasons for scope choices are in the [decision log](decisions.md). The short public view is the wiki page [Roadmap](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Roadmap). Dates are targets, not commitments; [Release Notes](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Release-Notes) state what shipped.

Pending decisions that would change this roadmap are marked "(PD-nnn, pending)". Until a pending entry is decided, the roadmap shows the scope before that decision, and cut candidates stay in their milestone. The cockpit direction (PD-032 to PD-042) was decided by the product owner on 2026-11-18 and is part of this plan. Business-user authoring (PD-043) is pending; its effect is stated in each milestone.

## At a glance

| Milestone | Dates (target) | Outcome | Features |
|---|---|---|---|
| M0 Architecture | 18 Nov to 4 Dec 2026 (2 weeks) | Contributors verify schema, transaction boundaries and module rules against a live PostgreSQL; the product design is decided | 3 |
| M1 Rule Engine 0.1, Cockpit 0.1 | 7 Dec 2026 to 15 Jan 2027 (6 weeks) | Business staff change governed rules without a deployment, from one cockpit that reaches every host | 48 |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 18 Jan to 26 Mar 2027 (10 weeks) | Teams run approvals, sagas and guarded AI agent steps in their own service, and operators see and work every instance on its diagram from one inbox | 55 |
| M3 Agentic depth, compatibility, 1.0 | 29 Mar to 21 May 2027 (8 weeks) | Adopters run in production with evaluated agent changes, governed process changes and a frozen API | 30 |
| After 1.0 | Candidates | Broader reach: message correlation, multi-agent planning, more databases | 10 |

![Milestones M0 to M3 with gates and cut checkpoints](images/rm-milestones.svg)

Gates (green hexagons) must hold before the next milestone's code starts; checkpoints (grey) trigger the pre-approved cut list. Source: [diagrams/rm-milestones.mmd](diagrams/rm-milestones.mmd).

Counts come from the [feature map summary](feature-map.md#summary). PD-042 (decided) moved M2 and M3 by two weeks each to absorb the cockpit direction; M0 and M1 dates are unchanged. Whether M1 holds is PD-031 (pending), which recommends keeping 15 Jan 2027 with a pre-approved cut list. If PD-043 is accepted as recommended, M1 holds 50 features, M2 59 and M3 31, with no date change.

## Strategic context

### Who this serves

Java and Spring Boot teams, in banking and elsewhere, that need governed business rules, human approvals and bounded AI agents inside their own service and database, without a separate engine cluster.

### Why this order

| Choice | Reason |
|---|---|
| Rule Engine first | Useful alone; it is the guardrail mechanism `agentTask` needs and the `decision` step calls. It exercises storage, versioning and the cockpit contract on the smallest surface. Delaying it delays every Process Engine feature that depends on it (PE-F-09, PE-F-18, PE-F-19) |
| Cockpit 0.1 with Rule Engine 0.1 | The M1 outcome names business staff and approvers; without an approval screen the outcome is true only for developers |
| `agentTask` in M2, not M3 | It is a service step plus schema validation plus a guardrail decision; all three exist once the Process Engine core and the Rule Engine are in place. It is also the differentiator adopters evaluate first |
| Grid editor in Cockpit 0.2 | YAML authoring with import and export is enough for a GitOps flow and let Rule Engine 0.1 ship six weeks earlier. PD-043 (pending) recommends a spreadsheet round-trip in M1 so business users need no YAML, and templates in the M2 grid |
| Read-only diagrams in M2, modeller in M3 | Operators and auditors need "where is this instance now" daily; who would use a modeller in a bank is unproven (K16). The modeller's engine APIs land before the 1.0 freeze so the canvas can move to 1.1 without breaking the API (PD-032) |
| Maker-checker for process definitions in the M2 engine | The deploy contract changes shape; changing it after M2 adopters use it would cost more. Approval screens follow in M3 (PD-033) |
| Host registry in M1, merged inbox in M2 | One cockpit reaches every host without a server (PD-035); there is nothing to merge until processes exist |
| Agentic depth and compatibility in M3 | MCP and model adapters, the offline runner and import formats build on a stable core; the API freezes only after they have been used |

![Cross-module feature dependencies by milestone](images/rm-feature-dependencies.svg)

Thick edges cross module boundaries: Rule Engine evaluation feeds the `decision` step and `agentTask` guardrails, and the M1 host registry feeds the M2 merged inbox. Edges come from the dependency tables in each milestone below and the "Depends on" column of the [feature map](feature-map.md). Source: [diagrams/rm-feature-dependencies.mmd](diagrams/rm-feature-dependencies.mmd).

### How scope is cut

Sequencing uses cost of delay across the features that wait on a dependency, and the Kano split between must-be qualities and differentiators.

| Class | Features | Rule |
|---|---|---|
| Must-be in a regulated service | Tenant isolation, idempotency, maker-checker, append-only audit, masking, the async wait-state protocol, the `agentTask` output gate | Never cut or deferred |
| Differentiators | Engine-enforced governance for rules and processes (RE-F-06, RE-F-07, RE-F-21, PE-F-45, PE-F-46), decision replay (RE-F-05), guarded agent steps (PE-F-16 to PE-F-22), read-only diagrams (CK-F-24, CK-F-25) | Protected; the milestone moves before these are cut |
| Breadth and convenience | Batch evaluate, async log mode, calendar editor screen, `callProcess`, migration execution, live counts and heatmap on the diagram, the modeller canvas, BPMN export, DMN import, what-if, documentation site | Cut first, in the order at each milestone checkpoint |

### What "Camunda-like" means here

The product owner asked for Camunda-like tools on top of embedded engines (PD-032 to PD-042). The plan delivers diagrams, operations on the diagram, a task inbox across hosts and governed process change. It does not deliver fleet analytics or full BPMN 2.0.

| Camunda tool | Included | Not included |
|---|---|---|
| Modeler | A diagram for every definition (M2); a browser modeller limited to our step types and to steps the host has registered (M3, cut candidate, fallback 1.1) | Full BPMN 2.0 symbols, a desktop modeller, scripts or expressions typed into the canvas |
| Operate | Definition and instance diagrams with token overlay, node history, live counts per node (M2); migration shown as two diagrams (M3) | Moving a token by hand inside a running instance |
| Tasklist | Task query by user, role and group, task forms, one inbox across hosts (M2) | A form designer |
| Optimize | Counts, incidents and p50 and p95 durations per node from an in-host rollup (live M2, history M3) | Ad-hoc reports, process mining across services, BI; hosts send engine events to their own warehouse |
| Console and Identity | Host registry, `/meta` and `/me` (M1), tenant switcher (M2) | A user store, a login of our own, a control plane |

### Positioning

Say: diagrams for every process, operations on the diagram, one inbox across services, and rule and process changes approved by a second person, all inside the team's own service. Do not say: a Camunda replacement, process analytics, or full BPMN. The comparison we can win is governed change on an embedded engine; the one we cannot win is modeller polish.

## M0: Architecture (18 Nov to 4 Dec 2026)

**Outcome.** Enable contributors and host-service architects to verify the storage schema, transaction boundaries, expression grammar and module dependency rules against a live PostgreSQL before engine code is written, so that M1 builds on decided contracts.

**Features.** X-F-01 (example host boots and creates `rle_*` tables), X-F-12 (SBOM, vulnerability and licence gates in CI), X-F-13 (spec-to-test coverage check). Also, not features: the two-day diagram spike (render `kyc-onboarding` with automatic layout and a token overlay in React Flow, kill assumption K18) and ADR-0012 to ADR-0015 drafted.

**Exit criteria.**

| Criterion | Verification |
|---|---|
| `TR-EMB-01`: `examples/rule-engine-only` boots in CI from a clean database and creates the `rle_*` tables, including the partitioned decision log (PD-004) | CI |
| `TR-LIC-01`, `TR-SEC-06`, `TR-QA-01` checks run in CI on every pull request | CI |
| The product design review is complete: PD-012 to PD-031 and PD-043 decided by the product owner (PD-032 to PD-042 decided on 2026-11-18) | [Decision log](decisions.md) |
| ADR-0008 and ADR-0009 accepted (PD-029, PD-030) | ADR status |
| Design questions that gate M1 code answered: Jackson major for `*-core` (ADR-0011), Maven `groupId`. Standalone cockpit host switching is answered by PD-035; ADR-0014 and the `/meta` and `/me` contracts are written | Module DESIGN files, ADR status |
| ADR-0012 (process definition lifecycle), ADR-0013 (notation, layout, diagram library), ADR-0014 (multi-host cockpit) and ADR-0015 (read models and query limits) drafted; the diagram spike done | ADR status, spike report |
| Every M1 feature has at least one SPEC statement (21 M1 spec gaps today) | [Feature map](feature-map.md) |

**Deferred.** Any evaluation code.

**Why first.** Contract changes are free before M1 and expensive after (PD-003 to PD-008 were decided for this reason).

## M1: Rule Engine 0.1 and Cockpit 0.1 (7 Dec 2026 to 15 Jan 2027)

**Outcome.** Enable product, risk and compliance staff to change decision tables through simulation, diff and a second approver, from one cockpit that reaches every host, and host services to evaluate them in process, so that rule changes no longer wait for a code deployment and every decision can be reconstructed. Today the tables are authored in YAML; PD-043 (pending) recommends a spreadsheet round-trip so business staff need no YAML.

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
| Cockpit 0.1: viewer and approver | CK-F-01, CK-F-02, CK-F-03, CK-F-04, CK-F-05, CK-F-06, CK-F-07, CK-F-08, CK-F-09, CK-F-23 (host registry, single sign-in, host switching) |
| Cross-cutting | X-F-02, X-F-03, X-F-04, X-F-05, X-F-06, X-F-07, X-F-08, X-F-09, X-F-14, X-F-16, X-F-19, X-F-22 (`/api/bpm/meta`, `/api/bpm/me`) |
| If PD-043 is accepted | Add RE-F-33, CK-F-34, CK-F-35; move RE-F-26 to M2 |

CK-F-23 and X-F-22 are sized at a few days (assumption until the architect sizes them) and are not part of the M2 estimate.

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
| ADR-0014 and the `/meta` and `/me` contracts | CK-F-23, X-F-22 | Sign-in across hosts and version skew rules |
| Jackson major fixed (ADR-0011) | Any `*-core` code | Spring Boot 4 and 3.5 use different Jackson majors |
| RE-F-02 validation | RE-F-25 import, RE-F-21 simulation, CK-F-02 | Import and simulation run validation first |
| RE-F-14 decision log | RE-F-05 replay, RE-F-17 query, RE-F-18 explain | All read the log |
| RE-F-06 and RE-F-07 version lifecycle | CK-F-04 approvals | The screen reflects engine states |
| Walking skeleton first | Everything else | Import, simulate, approve and evaluate one table end to end by week 3, then widen (kill assumption K12) |

**Deferred.**

| Item | To | Reason |
|---|---|---|
| Grid editor (CK-F-10) | M2 | YAML is enough for the first adopters; saves six weeks. PD-043 (pending) would add a spreadsheet round-trip to M1 instead |
| Replay simulation (RE-F-22, CK-F-11) | M3 | Needs a populated log; moved from M2 by PD-042; pulled into M1 only if K7 fails (PD-018, pending) |
| Explain templates (RE-F-19) | M3 | Generated explanation first (PD-027, pending) |
| Simulation reports on publication requests (RE-F-23, CK-F-20) | M3 | PD-022, pending |
| Decision log retention (RE-F-31) | M3 | Table is partitioned in M1; purge can wait for pilot volumes (PD-020, pending) |
| DMN import (RE-F-30) | M3 | Compatibility after the core |
| Cut if the 8 Jan 2027 checkpoint fails | M2 | RE-F-15 async log, RE-F-26 batch evaluate, decision log filters beyond rule set and period (PD-031, pending) |

## M2: Process Engine 0.1 with `agentTask`, Cockpit 0.2 (18 Jan to 26 Mar 2027)

**Outcome.** Enable Java teams to orchestrate human approvals, compensating service sagas and bounded AI agent steps inside their own Spring Boot service and PostgreSQL, and operators to see every instance on its diagram and work tasks and incidents from one inbox across services, so that agent output passes deterministic rules before a process moves, no database connection is held during a slow model or HTTP call, and no process definition runs without a second approver.

**Measures** (PD-019, pending): external teams running `examples/both`; share of escalated agent tasks completed by a person within SLA in pilot hosts; human override rate read from history.

**Features.**

| Area | Features |
|---|---|
| Definitions and execution | PE-F-01, PE-F-04, PE-F-05, PE-F-06, PE-F-08, PE-F-09, PE-F-12 (cut candidate), PE-F-14, PE-F-15 |
| Governed definitions | PE-F-45 (lifecycle with maker-checker, import as draft, content hash), PE-F-46 (graph diff) |
| Read models | PE-F-47 (activity history), PE-F-48 (live node counts), X-F-24 (bounded queries) |
| Retries and concurrency | PE-F-02, PE-F-03, PE-F-16, PE-F-17 |
| Human tasks and SLA | PE-F-07, PE-F-10, PE-F-11, PE-F-13, PE-F-50 (task query), PE-F-51 (task forms) |
| Tenancy | X-F-23 (tenant switching within the token's allowed tenants) |
| `agentTask` | PE-F-18, PE-F-19, PE-F-20, PE-F-21, PE-F-22, PE-F-23 (if PD-014), PE-F-24, PE-F-38 |
| Incidents and compensation | PE-F-25, PE-F-26, PE-F-27 |
| Migration | PE-F-29, PE-F-30 (cut candidate) |
| API and observability | PE-F-28, PE-F-32, PE-F-33 |
| Cockpit 0.2: editor and operations | CK-F-10, CK-F-12, CK-F-13, CK-F-14, CK-F-15, CK-F-16, CK-F-17 |
| Cockpit 0.2: diagrams and one inbox | CK-F-24 (definition diagram), CK-F-25 (instance diagram with tokens), CK-F-26 (live node counts, cut candidate), CK-F-28 (merged inbox), CK-F-29 (merged incidents), CK-F-30 (task forms) |
| Cross-cutting | X-F-15 |
| If PD-043 is accepted | Add RE-F-26 (from M1), RE-F-34, CK-F-36, CK-F-37 |

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
| Governed definitions | TR-SEC-04 for process definitions (PD-033) |

And: `examples/both` runs a process with a `decision` step and an `agentTask` guarded by a rule set and escalated to a person (X-F-15), and shows its instance as a diagram with the current token; a process definition imported from Git arrives as a draft and is approved by a second identity before it runs; a CI query-plan check proves the activity and task queries are bounded (X-F-24); the pool test of kill assumption K1 passes. Reported, not gating: TR-PERF-04 (PD-026, pending).

**Dependencies and sequencing.**

| Needs | Before | Why |
|---|---|---|
| Rule Engine 0.1 (RE-F-01, RE-F-20) | PE-F-09, PE-F-18, PE-F-19 | Decision steps and guardrails are rule set evaluations |
| ADR-0008 accepted (PD-029) | PE-F-16, PE-F-17 | One async protocol to build |
| Child compensation question answered (process DESIGN, gate M2 start) | PE-F-12, PE-F-27 | Defines how `callProcess` and `saga` interact |
| PE-F-16 async wait-state | PE-F-08 remote, PE-F-18 to PE-F-24 | Every external call uses it |
| PE-F-15 history | PE-F-22, CK-F-12, CK-F-16, PE-F-47 | Agent audit, the timeline and the activity history read history |
| ADR-0012 and the ADR-0010 amendment | PE-F-45, PE-F-46 | Lifecycle states, `processes:author` and `processes:approve` |
| ADR-0013 and the M0 diagram spike | CK-F-24, CK-F-25 | Notation, layout storage, diagram library |
| ADR-0015 | PE-F-47, PE-F-48, X-F-24 | Read models, indexes and query limits |
| PE-F-50 task query | CK-F-28 | The merged inbox reads it on every host |
| Walking skeleton first | Everything else | Start, `userTask`, `decision`, `agentTask` with a stub adapter and `route-to-human`, end, by week 3; one instance drawn with its token by week 4; then compensation, timers, migration |

Effort: the architect estimates about 3 weeks for the cockpit direction in M2; moving CK-F-18, RE-F-22 and CK-F-11 to M3 removes about 1 week (assumption); the net 2 weeks are covered by the two-week date move (PD-042).

**Deferred.**

| Item | To | Reason |
|---|---|---|
| MCP and reference model adapters (PE-F-34, PE-F-35), agent memory (PE-F-36), offline runner (PE-F-37) | M3 | Build on recorded M2 history |
| Renamed-step migration and the migration screen (PE-F-31, CK-F-19) | M3 | PD-023, pending |
| Business calendar editor screen (CK-F-18), replay simulation (RE-F-22, CK-F-11) | M3 | Moved up front to make room for the diagrams and the lifecycle (PD-042); the calendar SPI and built-in calendar stay in M2 |
| Message correlation (PE-F-40) | After 1.0 | Outside the single-service scope until 1.0 |
| Cut if the 5 Mar 2027 checkpoint fails (end of week 7; fewer than 9 of 17 `PRE-*` green) | M3 | In order: PE-F-30 migration execution (PD-017), CK-F-26 live counts on the diagram, PE-F-12 `callProcess` (PD-042). If PD-043 is accepted, RE-F-26 and RE-F-34 follow |

The Lunar New Year holidays fall in the first half of February 2027, inside M2; plan capacity for about nine working weeks.

## M3: Agentic depth, compatibility, 1.0 (29 Mar to 21 May 2027)

**Outcome.** Enable adopters to run multi-tenant workflows and agent steps in production, to evaluate a changed prompt or guardrail offline against recorded traces before it goes live, to approve process changes as a graph diff, and to upgrade without downtime on a frozen API, so that two teams outside the maintainers depend on the engines.

**Measures.** Two adopters outside the maintainers in production or pilot, at least one using `agentTask`; North Star reporting starts if PD-019 is decided as recommended.

**Features.**

| Area | Features |
|---|---|
| Agentic depth | PE-F-34, PE-F-35, PE-F-36, PE-F-37, PE-F-39 (PD-028 proposes after 1.0), PE-F-43 (if PD-015) |
| Simulation and explanation | RE-F-19, RE-F-22 and CK-F-11 (replay, from M2), RE-F-23, RE-F-24 (cut candidate), CK-F-20 |
| Operations | RE-F-31, X-F-10, X-F-11, CK-F-18 (calendar editor from M2, first M3 cut) |
| Modeller | PE-F-44 (layout storage), PE-F-52 (step catalogue), CK-F-32 (canvas, cut candidate, fallback 1.1) |
| Governed change | CK-F-31 (definition approval screens with graph diff) |
| Analytics | PE-F-49 (hourly node rollup), CK-F-27 (historical heatmap, cut candidate) |
| Migration | PE-F-31, CK-F-19, CK-F-33 (migration plan as two diagrams) |
| Compatibility | RE-F-30 (cut candidate), PE-F-41 (protected by PD-034), PE-F-53 (BPMN export, cut candidate) |
| 1.0 | X-F-17 (cut candidate), X-F-18 |
| If PD-043 is accepted | Add CK-F-38 (cut candidate) |

**Exit criteria.** Every Must green. Reported: TR-OPS-02, TR-OPS-04, TR-OBS-03, TR-MIG-03, TR-AGT-04. API frozen with the deprecation policy published (X-F-18). Two adopters outside the maintainers in production or pilot, at least one using `agentTask` (kill assumption K13).

**Dependencies and sequencing.**

| Needs | Before | Why |
|---|---|---|
| M2 history with agent I/O and overrides (PE-F-22) | PE-F-37 offline runner, PE-F-36 memory | The runner replays what M2 recorded |
| PE-F-24 adapter SPI | PE-F-34, PE-F-35 | Adapters are examples on the SPI |
| PE-F-30 migration execution | PE-F-31, CK-F-19, CK-F-33 | Mapping extends execution |
| PE-F-44, PE-F-45, PE-F-52 | CK-F-32 canvas | The canvas stores layout, saves drafts and offers only catalogue steps |
| PE-F-48 | PE-F-49, CK-F-27 | The rollup extends the live counts |
| All features in | X-F-18 API freeze | Freeze last, after use |

**Deferred.** Agent-chosen steps (PE-F-39) and multi-agent planning (PE-F-42) after 1.0 (PD-028, pending). Cut if the 23 Apr 2027 checkpoint fails (end of week 4; API freeze candidate not ready, or fewer than two named pilot adopters), in order: CK-F-18 calendar editor to after 1.0, CK-F-32 modeller canvas to 1.1, CK-F-27 historical heatmap to after 1.0, PE-F-53 BPMN export, RE-F-30 DMN import, RE-F-24 what-if, X-F-17 documentation site (PD-042). Never cut in M3: the engine APIs that freeze at 1.0 (PE-F-44, PE-F-45, PE-F-46, PE-F-52), because adding them after the freeze would break the deprecation policy.

The April and May public holidays leave about seven working weeks. M3 is not estimated yet; at 30 features it carries about 4.3 per working week against about 2.7 before the cockpit direction, which is why four of the new or moved features are cut candidates.

## After 1.0 (candidates)

PE-F-40 message correlation; PE-F-42 multi-agent planning; CK-F-21 pulse strip (PD-016, pending); CK-F-22 hosted cockpit; X-F-20 other databases through a storage SPI; X-F-21 Jakarta EE integration. From the [Rule Engine use case catalogue](rule-engine-users-and-journeys.md): RE-F-35 withdraw a pending request, RE-F-36 two approvers from different roles, RE-F-37 permissions limited to some rule sets, CK-F-39 copy a rule set across tenants. Also: the modeller canvas (CK-F-32) if cut from M3; a read-side aggregator (PD-035 option C) only with a paying customer. Proposals are issues labelled `type:proposal`; accepted ones become ADRs and decision log entries and then move into a milestone.

## Kill assumptions

Load-bearing assumptions behind this roadmap, each with the cheapest test and the point at which the plan changes. Ranked by impact if wrong, likelihood of being wrong and cost of testing; the top seven are tested first.

### Test first

| Rank | Id | Claim | Fails if | Cheapest test | Kill criterion | Then |
|---|---|---|---|---|---|---|
| 1 | K13 | Two teams outside the maintainers adopt by 21 May 2027 | No named team has a use case, a host service and time for a pilot by M2 exit | List candidate teams now; two conversations per month from M1 | Fewer than two named pilot teams with a scheduled start by 26 Mar 2027 | Restate the M3 exit as "1.0 frozen, adopters in evaluation"; keep the date |
| 2 | K4 | Guardrail rules over structured output catch the agent errors that matter | Costly errors (a misread date with high confidence) pass schema and guardrail | KYC example with 30 labelled outputs, 10 with seeded critical errors, stub adapter, one day | More than 2 of 10 seeded critical errors pass | Publish rung 1 as the only guidance until the offline runner exists |
| 3 | K3 | Analysts author changes in YAML well enough for 0.1 (PD-043, pending, would test the spreadsheet round-trip instead, within 15 minutes) | Every change still needs a developer, so the M1 outcome is true only for developers | Two moderated sessions in week 2 of M1, including a quoting error (PD-005) | Neither analyst reaches `PENDING_APPROVAL` without help | Restate the M1 outcome as developer-mediated; raise the grid editor's priority in M2 |
| 4 | K10 | The closed expression language covers real fee, limit and eligibility tables | Common tables need constructs outside the grammar | Rewrite ten public sample tables in the grammar, one afternoon in M0 | More than two of ten need a host function or cannot be expressed | Extend built-in functions before M1 exit; never add scripting |
| 5 | K16 | In banks, process owners or analysts, not only developers, change process definitions, so a modeller has users | Every process change goes through IT, so the canvas is used only by developers who prefer Git | Ask three process owners or operations leads who changes an approval flow today and how long it takes; during M1 | Two of three say only IT changes processes | Move CK-F-32 to after 1.0; keep diagrams, lifecycle and step catalogue |
| 6 | K18 | The architect's estimate of about 3 weeks for the cockpit direction in M2 holds | The diagram views or the lifecycle take longer than planned | Two-day spike in M0: render `kyc-onboarding` with automatic layout and a token overlay in React Flow | The spike takes more than four days or needs a library that fails `TR-LIC-01` | Drop CK-F-26 from M2 and keep only the instance diagram; try maxGraph |
| 7 | K11 | The M2 scope fits ten weeks with the Lunar New Year break | Async wait-state, compensation, migration and the diagram views slip together and `agentTask` lands late | Weekly count of `PRE-*` statements green | Fewer than 9 of 17 green at the end of week 7 (5 Mar 2027) | Apply the M2 cut list (PD-017, PD-042) |

### All assumptions

| Id | Claim | Fails if | Cheapest test | Kill criterion | Then |
|---|---|---|---|---|---|
| K1 | `agentTask` inside an embedded library does not exhaust the host's connection pool | A transaction wraps a slow model or HTTP call and starves the pool | M2 load test: 20 concurrent 5 s adapters on a 10-connection pool; 10 concurrent 2 s adapters on a 2-connection pool (`TR-ASY-01`) | Any pool timeout | Keep async wait-state mandatory; reject any synchronous path for remote adapters |
| K2 | Compiled rule caches stay coherent across replicas without a broker | A replica serves version 1 after version 2 is effective elsewhere | Two-replica test, direct and through a transaction-mode pooler (`TR-ASY-04`) | Stale beyond 5 s in either run | Default to the epoch check only where `LISTEN/NOTIFY` is unreliable |
| K3 | Analysts author YAML well enough for 0.1 | See "Test first" | | | |
| K4 | Guardrail rules catch the agent errors that matter | See "Test first" | | | |
| K5 | Evaluators accept the diagrams and a YAML or JSON definition subset without a modeller in M2 | Architects shortlist only engines with BPMN modelling | Three architect conversations during M1, with the spike screenshots | Two of three would not shortlist without a modeller | Move BPMN subset import (PE-F-41) ahead of agentic depth in M3; it is already off the cut list (PD-034) |
| K6 | Mandatory four-eyes is a reason to adopt, not a blocker | Teams need same-day single-person changes | Three compliance conversations during M1 | Two of three require a single-person path | Open an ADR for a logged emergency path after 1.0 (PD-012) |
| K7 | Diff plus sample simulation is enough to approve in 0.1 | Approvers refuse without the effect on real traffic | One moderated approver session in week 3 of M1 with a 20-row change | The approver cannot say who is affected, or defers | Move replay simulation into M1 and cut decision log filters (PD-018) |
| K8 | Approvers will approve in the cockpit | Compliance requires approval in an existing ticket or workflow tool | Three compliance conversations in M1 | Two of three refuse a second approval tool | Document approval through the REST API and the publish-requested event as the primary path; keep the screen |
| K9 | A stateless cockpit is enough for operators | Operators need push alerts from the tool itself | Two operations lead conversations before M2 | Both need push alerts | Document alerting on engine metrics and events in the host's monitoring; keep the cockpit stateless |
| K10 | The closed expression language covers real tables | See "Test first" | | | |
| K11 | The M2 scope fits | See "Test first" | | | |
| K12 | Six weeks (about five working weeks) are enough for Rule Engine 0.1 | Authoring or approval friction, or editor scope creeps into Cockpit 0.1 | `examples/rule-engine-only` and the Cockpit 0.1 approval flow end to end by 8 Jan 2027 | Not end to end on that date | Apply the M1 cut list (PD-031) rather than slip past 15 Jan 2027 |
| K13 | Two outside teams adopt by 21 May 2027 | See "Test first" | | | |
| K14 | Established BPM vendors do not ship an embedded, cluster-free mode with built-in rule governance | A vendor releases an embeddable library with maker-checker rule governance | Review competitor releases quarterly | Such a release | Concentrate on deterministic `agentTask` guardrails, the offline runner and decision replay |
| K15 | A read-only diagram is what evaluators and operators need first, more than the modeller | Evaluators reject the product without editing in the browser | Show spike screenshots in the three K5 architect conversations and two operations lead conversations in M1 | Two of five require browser editing to shortlist | Bring CK-F-32 forward to the start of M3 and protect it; cut replay instead |
| K16 | Process owners, not only developers, change process definitions | See "Test first" | | | |
| K17 | Pilot banks run at most about 15 hosts and one IdP whose tokens every host accepts | A pilot cannot issue a token accepted across hosts, or runs more hosts | Ask the K13 pilot candidates for host count and IdP setup; during M1 | Either pilot cannot meet the condition | Run that pilot on option A (cockpit per host); document option D through its gateway |
| K18 | The M2 estimate for the cockpit direction holds | See "Test first" | | | |

### What holds up

- The order Rule Engine, then Process Engine with `agentTask`, then agentic depth follows real dependencies: guardrails and `decision` steps are rule set evaluations.
- The async wait-state protocol (ADR-0008) and the cache design (ADR-0009) answer K1 and K2 by design; the tests confirm rather than discover.
- Contract decisions PD-003 to PD-008 were made before any adopter exists, when they cost nothing.
- The cockpit direction keeps ADR-0005: the standalone cockpit federates hosts from the browser, with no server of its own (PD-035).

### What this roadmap cannot assess

- Team capacity per milestone: the dates assume full focus on the engines (PD-010) and no other commitments.
- Adopter demand: no adopter conversations are recorded yet; K13 is the first test for that reason.

## Pending decisions that affect this roadmap

PD-012 to PD-031 and PD-043 in the [decision log](decisions.md), summarised with recommendations in the [product design review](README.md). PD-043 (business-user authoring) would add 7 features as stated in M1, M2 and M3 above.
