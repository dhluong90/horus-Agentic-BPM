# Product design review

| Field | Value |
|---|---|
| Type | Review summary |
| Owner area | Product |
| Last reviewed | 2026-11-18 |
| Status | Ready for product owner review, before implementation planning starts |

## What the engine is

HORUS Agentic BPM is two Java libraries and a static cockpit that run inside a team's own Spring Boot service and PostgreSQL database, with no engine cluster, broker or separate login. The Rule Engine evaluates versioned decision tables in the service's transaction; business staff change them through simulation and a second approver, without a deployment. The Process Engine runs human, service, decision and AI agent steps with safe retries, compensation and SLAs; an agent's output must pass a JSON Schema and a deterministic guardrail rule set before the process moves, and on violation a person decides. The cockpit lets authors, approvers, operators and auditors do this work without SQL or scripts, and cannot weaken any engine control. Details: [vision](vision.md).

## The three modules

| Module | Job | First version | Users | PRD |
|---|---|---|---|---|
| Rule Engine | Evaluate governed decision tables in process; change them without a deployment | 0.1 in M1 | Host developer, rule author, approver and auditor | [prd-rule-engine.md](prd-rule-engine.md) |
| Process Engine | Run human, service, decision and agent steps with idempotency, compensation and SLAs | 0.1 in M2 | Host developer, operations team, agent step designer | [prd-process-engine.md](prd-process-engine.md) |
| Cockpit | Let people approve, simulate, investigate and operate both engines | 0.1 in M1, 0.2 in M2 | Author, approver, operator, auditor | [prd-cockpit.md](prd-cockpit.md) |

## Roadmap

| Milestone | Target date | Outcome | Features |
|---|---|---|---|
| M0 Architecture | 4 Dec 2026 | Contracts verified against a live PostgreSQL; this review decided | 3 |
| M1 Rule Engine 0.1, Cockpit 0.1 | 15 Jan 2027 | Business staff change governed rules without a deployment | 46 |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 12 Mar 2027 | Approvals, sagas and guarded AI agent steps inside the team's own service | 44 |
| M3 Agentic depth, compatibility, 1.0 | 7 May 2027 | Production use with offline evaluation of agent changes and a frozen API | 19 |
| After 1.0 | Candidates | Message correlation, multi-agent planning, more databases | 6 |

Full plan: [roadmap.md](roadmap.md). Date risk: M1 has about five working weeks after the year-end break, and M2 about seven after the Lunar New Year break; both hold only with the pre-approved cuts in PD-031. M3 depends on two outside adopters (kill assumption K13).

## Feature map in numbers

| Module | Features | Specified | Spec gap | Design preview |
|---|---|---|---|---|
| Rule Engine | 32 | 14 | 18 | 0 |
| Process Engine | 43 | 17 | 22 | 4 |
| Cockpit | 22 | 11 | 9 | 2 |
| Cross-cutting | 21 | 12 | 7 | 2 |
| **Total** | **118** | **54** | **56** | **8** |

All 44 SPEC statements map to a feature. 19 M1 features have no SPEC statement yet; closing them is an M0 exit criterion. 11 features are pre-approved cut candidates if PD-031 is accepted. Details: [feature-map.md](feature-map.md).

## Decisions waiting for the product owner

Eleven decisions are already recorded as decided (PD-001 to PD-011). These twenty need a decision; eleven more on the cockpit direction (PD-032 to PD-042) are summarised in the next section. Full options and consequences: [decisions.md](decisions.md).

| Id | Question | Recommendation |
|---|---|---|
| PD-012 | Emergency single-approver path for urgent rule changes? | No path before 1.0; four-eyes stays mandatory. Revisit by ADR only if two of three compliance contacts require one |
| PD-013 | Does `TR-UI-02` (authoring in the cockpit) gate M1? | No: tag it Must (M2); M1 gates on import, simulate, request and approve in the cockpit |
| PD-014 | Record model and prompt version from the adapter? | Yes, optional fields in M2, recorded only, never interpreted |
| PD-015 | Autonomy rung 0 (shadow) and rung 4? | Rung 0 as a documented pattern with the M3 offline runner; no rung 4 before 1.0 |
| PD-016 | Cockpit pulse strip? | Remove from DESIGN for 0.x; show counts in queue headers |
| PD-017 | First cut if M2 slips? | Batched migration execution moves to M3 if fewer than 9 of 17 `PRE-*` are green at week 5; dry run stays |
| PD-018 | Replay simulation in M1? | Keep in M2; pull in only if the week 3 approver session fails |
| PD-019 | North Star metric? | Governed decisions and process steps per week in adopters' services, adopter-reported from M3; input measures before that |
| PD-020 | Partitioning on the roadmap (was M3) | Partitioned decision log in M1; retention configuration in M3 |
| PD-021 | Exit criteria omit tagged Musts | List every gating Must per milestone, including `TR-ASY-04`, `TR-PERF-06`, `TR-IDEM-05`, `TR-OPS-05` in M1 |
| PD-022 | Simulation report in Cockpit 0.1 approvals (wiki) | M3; in 0.1 the approver reads the diff and runs samples |
| PD-023 | Migration screen in Cockpit 0.2 (wiki) or M3 | M3, with renamed-step mapping |
| PD-024 | Compensation runs view and SLA breach list (wiki only) | In Cockpit 0.2, kept thin: timeline entries and a queue filter |
| PD-025 | Process Engine "0.2" label (wiki Concepts) | Process Engine 0.1 in M2; module versions are independent |
| PD-026 | Should requirements used as gates | `TR-PERF-04` reported only; accessibility and two languages gate, with `TR-UI-03` and `TR-UI-04` raised to Must (M1) |
| PD-027 | Decision explanation in M1 without a SPEC statement | Generated explanation from the log in M1; author templates in M3 |
| PD-028 | Agent-chosen steps in M3 or after 1.0 | After 1.0, to keep room for the API freeze |
| PD-029 | Accept ADR-0008 (async wait-state) | Accept now |
| PD-030 | Accept ADR-0009 (cache coherence) | Accept now, before M1 code |
| PD-031 | Can the dates hold? | Keep all four dates with the pre-approved cut list at fixed checkpoints |

## Cockpit direction (pending)

The product owner asked for Camunda-like tools while the engines stay embedded libraries. The architect's review answers that this fits the principles: replicas of one service share one database, so each host's REST layer already has the full picture for that service, and a static cockpit that calls several hosts covers the rest. It also found three gaps in today's plan: no diagrams, no maker-checker for process definitions, and no engine read APIs for a cockpit.

| Id | Question | Architect | Product manager |
|---|---|---|---|
| PD-032 | Diagrams and a modeller? | Read-only diagrams with token overlay in M2, modeller in M3 | Agree; the modeller canvas is the first M3 cut (fallback 1.1), its engine APIs land before the freeze |
| PD-033 | Maker-checker for process definitions? | Engine API in M2, screens in M3 | Agree; `dev` may auto-approve so Getting Started stays under 30 minutes |
| PD-034 | Notation? | Own JSON canonical, BPMN subset import and export; PE-F-41 off the cut list | Agree; export added as a cut candidate |
| PD-035 | One cockpit for several hosts? | B: standalone cockpit federating hosts; A supported, D documented, C after 1.0 with a paying customer | Agree; only registry, `/meta` and `/me` in M1, merged inbox and incidents in M2 |
| PD-036 | Where do definitions live? | Git and cockpit both; imports arrive as drafts | Agree; host can turn off cockpit authoring per environment |
| PD-037 | Tenant switching? | Header only within the token's allowed tenants; no cross-tenant view | Agree; in M2, not M1 |
| PD-038 | Analytics? | In-host rollup only | Agree; live counts M2, history M3; pulse strip stays out (PD-016) |
| PD-039 to PD-041 | Wording | Principle 9 reworded, principle 6 covers process definitions, vision modeller non-goal narrowed | Follow PD-032 and PD-033 |
| PD-042 | Dates | About 3 extra weeks in M2; calendar editor and replay out of M2 | M2 to 26 Mar 2027, M3 to 21 May 2027; M0 and M1 unchanged |

Effect if accepted: 24 new features (2 in M1, 14 in M2, 8 in M3), all spec gaps until `SPEC` statements exist; M2 grows from 44 to 55 features, M3 from 19 to 30. What "Camunda-like" means here: diagrams, operations on the diagram, one inbox across services, governed process change. Not included: fleet analytics or full BPMN 2.0. Details: [decision log](decisions.md#pending-cockpit-direction-camunda-like-cockpit), [proposed features](feature-map.md#proposed-camunda-like-cockpit-pending-pd-032-to-pd-042), [roadmap scenario](roadmap.md#scenario-camunda-like-cockpit).

## Product documents

| File | Contents |
|---|---|
| [vision.md](vision.md) | Problem, who it is for and not for, differentiators, what the engines will never be |
| [prd-rule-engine.md](prd-rule-engine.md), [prd-process-engine.md](prd-process-engine.md), [prd-cockpit.md](prd-cockpit.md) | What to build per module and why |
| [feature-map.md](feature-map.md) | Every feature with milestone, SPEC and TR ids and status; owner of feature scope and status |
| [roadmap.md](roadmap.md) | Milestones, dates, exit gates, sequencing, kill assumptions; owner of dates |
| [decisions.md](decisions.md) | Every product decision, with options and rationale |
| [requirements.md](requirements.md) | Numbered `TR-*` release requirements |
