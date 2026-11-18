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
| Rule Engine | Evaluate governed decision tables in process; change them without a deployment | 0.1 in M1 | Host developer, rule author, approver and auditor ([eight bank roles](rule-engine-users-and-journeys.md)) | [prd-rule-engine.md](prd-rule-engine.md) |
| Process Engine | Run human, service, decision and agent steps with idempotency, compensation and SLAs | 0.1 in M2 | Host developer, operations team, agent step designer | [prd-process-engine.md](prd-process-engine.md) |
| Cockpit | Let people approve, simulate, investigate and operate both engines | 0.1 in M1, 0.2 in M2 | Author, approver, operator, auditor | [prd-cockpit.md](prd-cockpit.md) |

## Roadmap

| Milestone | Target date | Outcome | Features |
|---|---|---|---|
| M0 Architecture | 4 Dec 2026 | Contracts verified against a live PostgreSQL; this review decided | 3 |
| M1 Rule Engine 0.1, Cockpit 0.1 | 15 Jan 2027 | Business staff change governed rules without a deployment, from one cockpit that reaches every host | 48 |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 26 Mar 2027 | Approvals, sagas and guarded AI agent steps inside the team's own service; every instance on its diagram; one inbox across services | 55 |
| M3 Agentic depth, compatibility, 1.0 | 21 May 2027 | Production use with offline evaluation of agent changes, governed process changes and a frozen API | 30 |
| After 1.0 | Candidates | Message correlation, multi-agent planning, more databases | 10 |

Full plan: [roadmap.md](roadmap.md). M2 and M3 moved by two weeks each when the product owner accepted the cockpit direction on 2026-11-18 (PD-042). Date risk: M1 has about five working weeks after the year-end break and holds only with the pre-approved cuts in PD-031 (pending); M2 has about nine working weeks after the Lunar New Year break and keeps three cut candidates; M3 carries 30 features in about seven working weeks, with seven cut candidates, and depends on two outside adopters (kill assumption K13).

## Feature map in numbers

| Module | Features | Specified | Spec gap | Design preview |
|---|---|---|---|---|
| Rule Engine | 35 | 14 | 18 | 3 |
| Process Engine | 53 | 17 | 32 | 4 |
| Cockpit | 34 | 11 | 20 | 3 |
| Cross-cutting | 24 | 12 | 10 | 2 |
| **Total** | **146** | **54** | **80** | **12** |

All 44 SPEC statements map to a feature. 21 M1 features have no SPEC statement yet; closing them is an M0 exit criterion. 12 features are cut candidates (2 in M1 if PD-031 is accepted, 3 in M2 and 7 in M3 under PD-042). Seven more features wait for PD-043 and are not counted. Details: [feature-map.md](feature-map.md).

## Decisions waiting for the product owner

Twenty-two decisions are recorded as decided (PD-001 to PD-011, and PD-032 to PD-042 on the cockpit direction, summarised below). These twenty-one need a decision; twelve more from the requirements review (PD-044 to PD-055) are listed in [Requirements review (pending)](#requirements-review-pending). Full options and consequences: [decisions.md](decisions.md).

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
| PD-031 | Can the M1 date hold? (M2 and M3 rows replaced by PD-042) | Keep 15 Jan 2027 with the pre-approved M1 cut list |
| PD-043 | Which authoring path do business users get in M1, and what does "user-friendly" mean? | Spreadsheet round-trip with plain conditions in M1; grid with templates and drop-downs in M2; no grid in M1. See the next section |

## Cockpit direction (decided 2026-11-18)

The product owner accepted the cockpit direction ("add it into the product roadmap") with the product manager's recommended variant of PD-032 to PD-042. The engines stay embedded libraries; the cockpit stays static and calls each host.

| Id | Decided |
|---|---|
| PD-032 | Read-only diagrams with token overlay in M2; modeller canvas in M3 as the first M3 cut (fallback 1.1); its engine APIs before the freeze |
| PD-033 | Maker-checker for process definitions in the engine in M2, screens in M3; `dev` may auto-approve |
| PD-034 | Own JSON canonical; BPMN subset import protected, export a cut candidate |
| PD-035 | Standalone cockpit federating hosts; registry, `/meta` and `/me` in M1; merged inbox and incidents in M2 |
| PD-036 | Git and cockpit both; imports arrive as drafts; cockpit authoring can be turned off per environment |
| PD-037 | Tenant switching only within the token's allowed tenants, in M2 |
| PD-038 | Live counts per node in M2, hourly rollup and heatmap in M3; no aggregator before a paying customer |
| PD-039 to PD-041 | Principle 9 reworded, principle 6 covers process definitions, vision and PRD modeller non-goals narrowed |
| PD-042 | M2 ends 26 Mar 2027, M3 ends 21 May 2027; M0 and M1 unchanged |

Effect: 24 new features, all spec gaps (2 in M1, 14 in M2, 8 in M3); calendar editor and replay simulation moved from M2 to M3. What "Camunda-like" means: diagrams, operations on the diagram, one inbox across services, governed process change. Not included: fleet analytics or full BPMN 2.0. Details: [decision log](decisions.md#decided-cockpit-direction-camunda-like-cockpit), [roadmap](roadmap.md#what-camunda-like-means-here).

Wiki wording that follows PD-039 and PD-040 (Principles rows 6 and 9, Concepts, FAQ, Cockpit page) is with documentation.

## Who uses the Rule Engine and how

Eight roles in a bank or fintech use the Rule Engine. Details, permissions, six end-to-end journeys and the full catalogue: [rule-engine-users-and-journeys.md](rule-engine-users-and-journeys.md).

| Persona | Real role | Reads YAML | Main surface | Use cases (M1 / M2 / M3 / later / never, with PD-043) |
|---|---|---|---|---|
| Pricing manager | Product manager for fees and pricing | No | Cockpit; spreadsheet round-trip (PD-043) | 7 / 0 / 2 / 2 / 1 |
| Credit risk analyst | Credit policy analyst | With help | Cockpit; spreadsheet; grid from M2 | 6 / 2 / 1 / 0 / 1 |
| Business analyst | Change or IT delivery team | Yes | Git and YAML; cockpit import | 3 / 0 / 2 / 0 / 1 |
| Compliance officer | Approver (checker) | No | Cockpit: Approvals; or the bank's ticket tool through REST | 7 / 0 / 1 / 1 / 1 |
| Support staff | Contact centre, branch or back office | No | The bank's case screen calling the explain API | 3 / 1 / 1 / 0 / 0 |
| Internal auditor | Internal audit | No | Cockpit: Decisions and versions; REST for evidence | 3 / 1 / 1 / 0 / 0 |
| Host-service developer | Java developer | Yes | Java API, REST, JUnit, Git | 9 / 2 / 0 / 0 / 0 |
| Platform administrator | IT operations, identity and access | Configuration | Configuration, IdP, monitoring | 5 / 0 / 3 / 2 / 0 |

Use cases per milestone (69 in total):

| Plan | M1 | M2 | M3 | Later | Never |
|---|---|---|---|---|---|
| Today | 37 | 5 | 10 | 13 | 4 |
| With PD-043 as recommended | 43 | 6 | 11 | 5 | 4 |

The main finding: under today's plan a pricing manager or credit risk analyst cannot change a table in M1 without a business analyst or developer writing YAML, so the M1 outcome "business staff change rules" holds only through intermediaries. Creating a new rule set always needs a developer, because the host must supply its inputs; most business changes are edits to existing tables.

**PD-043 recommendation (pending product owner).** Option 3: in M1, a spreadsheet round-trip for existing rule sets (export rows as CSV, edit in Excel, import as a draft) with plain conditions such as `GOLD, PLATINUM`, `>= 50000000 VND` or `18 to 65`, and plain-language errors naming row and column; in M2, the grid editor gains templates, a "new rule set" flow and drop-downs. No grid in M1: it costs 2 to 3 weeks in a milestone with about five working weeks. "User-friendly" is defined as seven testable criteria: no YAML, no expression syntax for common conditions, templates, plain-language validation, preview with sample data, typed values, and requesting approval from the same screen. M1 date unchanged; batch evaluate moves to M2 to make room. Kill test: two analysts reach `PENDING_APPROVAL` within 15 minutes without help in week 2 of M1. Details: [PD-043](decisions.md#pd-043-rule-authoring-for-business-users-without-yaml).

## Requirements review (pending)

The architect reviewed the technical, non-functional and developer requirements on 18 Nov 2026. Correctness is well covered; operations, usability and developer experience are not. The gaps that are product choices are below. The architect's other proposals (availability, failover, cockpit read limits, alerts, log content, footprint, Java API, documentation and test kit standards) wait for the product owner's acceptance. [requirements.md](requirements.md) does not change until these are decided.

| Id | Question | Recommendation |
|---|---|---|
| PD-044 | RPO and RTO responsibility | The bank owns backup, RPO and RTO; the engines add no recovery step, at most 5 s to start-up, and reclaim unfinished work automatically. Publish the wording with the M2 documentation; the restore test gates 1.0 |
| PD-045 | Capacity limits we publish | Publish only the tested envelope on stated hardware (200 tenants, 100 rule sets each, 1 million active instances, 1,000 transitions/s, 2,000 decisions/s) as "tested, not a limit", from the first measurement; keep the 500-tenant design target internal |
| PD-046 | Federation limits | Support 15 hosts and 2,000 active operators per standalone cockpit, tested in M2 with simulated hosts; above that, a cockpit per host or the bank's gateway |
| PD-047 | Usability targets | Three Should targets reported at milestone exit: operator finds why an instance is stuck in 3 clicks (M2); analyst reaches `PENDING_APPROVAL` in 15 minutes unaided (M1); newcomer's first decision in 30 minutes (M1) |
| PD-048 | Diagram accessibility | A keyboard-usable node list for every diagram, state in text as well as colour; the canvas is exempt in M3 only because JSON or YAML import is an equivalent path |
| PD-049 | WCAG, ASVS, coverage at 1.0 | Must at 1.0 for `TR-UI-03`, `TR-SEC-07`, `TR-QA-03`, and `TR-OPS-02` (zero-downtime upgrade, promised by the M3 outcome) |
| PD-050 | Retention default | Keep forever unless configured; warn at start-up outside `dev`; never below 12 months; publish growth per row |
| PD-051 | Tenant exit | Export and a governed purge (second approver, purge record) in 1.1; not supported before, and documented as such |
| PD-052 | Tamper evidence | In 1.1; prefer sealing each closed monthly partition over a per-row chain; from 1.0 state that append-only protects against the engine's code, not against a database administrator |
| PD-053 | Licence rule for tests | Runtime and the shipped cockpit bundle stay Apache, MIT or BSD; JUnit Jupiter API as `provided` in `*-test` artifacts; jqwik and JMH build-only; every exception named |
| PD-054 | Deprecation length | One minor release and at least six months; removal only in a major release |
| PD-055 | PostgreSQL range | 14 tested until 1.0, 15 or later from 1.0; ask pilots for their version in M1 |

## Product documents

| File | Contents |
|---|---|
| [vision.md](vision.md) | Problem, who it is for and not for, differentiators, what the engines will never be |
| [rule-engine-users-and-journeys.md](rule-engine-users-and-journeys.md) | Rule Engine personas, surfaces, 69 use cases by milestone, six journeys with failure paths |
| [prd-rule-engine.md](prd-rule-engine.md), [prd-process-engine.md](prd-process-engine.md), [prd-cockpit.md](prd-cockpit.md) | What to build per module and why |
| [feature-map.md](feature-map.md) | Every feature with milestone, SPEC and TR ids and status; owner of feature scope and status |
| [roadmap.md](roadmap.md) | Milestones, dates, exit gates, sequencing, kill assumptions; owner of dates |
| [decisions.md](decisions.md) | Every product decision, with options and rationale |
| [requirements.md](requirements.md) | Numbered `TR-*` release requirements |
