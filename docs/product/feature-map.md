# Feature Map

| Field | Value |
|---|---|
| Type | Reference |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

Design preview: nothing is released. This file is the single owner of feature scope and status for the Rule Engine, the Process Engine, the Cockpit and cross-cutting capabilities until releases exist; after that, [Release Notes](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Release-Notes) state what shipped. Other documents link to feature ids here instead of restating scope. Milestone dates are in the [product roadmap](roadmap.md); the reasons for scope choices are in the [decision log](decisions.md) (PD-002).

## How to read this map

| Column | Meaning |
|---|---|
| Id | `RE-F-nn` Rule Engine, `PE-F-nn` Process Engine, `CK-F-nn` Cockpit, `X-F-nn` cross-cutting. Ids are stable and never reused |
| Feature | The outcome for the persona, in one line |
| Persona | Rule Engine: S1 host developer, S2 rule author, S3 approver and auditor. Process Engine: S1 host developer, S2 operations team, S3 agent step designer. Cockpit: Author, Approver, Operator, Auditor. As defined in each PRD section 5 |
| Milestone | M0, M1, M2, M3, or later (after 1.0 or not scheduled). "If PD-nnn" means the milestone depends on a pending decision. "Cut candidate" means pre-approved to move out at the milestone checkpoint (see [Summary](#summary)) |
| PRD | Section of the module PRD: [RE](prd-rule-engine.md), [PE](prd-process-engine.md), [CK](prd-cockpit.md), [V](vision.md); `UJ` names use cases in [Rule Engine users and journeys](rule-engine-users-and-journeys.md) |
| SPEC | `RLE-*`, `PRE-*`, `CKP-*` statements in the module `SPEC.md` |
| TR | Release requirements in [requirements.md](requirements.md) |
| Status | **specified**: covered by at least one SPEC statement, or, for build, CI and documentation properties with no runtime behaviour, by the verification of its TR requirement or by the PRD text. **spec gap**: in scope for a milestone but no SPEC statement yet. **design preview**: described but on no milestone before 1.0 |
| Depends on | Features or decisions that must exist first |
| Decisions | Entries in the [decision log](decisions.md) |

A PRD capability row that bundles several outcomes is split into several features that name the same PRD section and together cover it once. No feature repeats another.

## Rule Engine

| Id | Feature | Persona | Milestone | PRD | SPEC | TR | Status | Depends on | Decisions |
|---|---|---|---|---|---|---|---|---|---|
| RE-F-01 | Choose how matching rows combine: `first`, `unique` (error, never partial outputs, on zero or several matches), `collect` with aggregation | S1, S2 | M1 | RE 7.2 Logic | RLE-001, RLE-002, RLE-003 | TR-DET-01, TR-DET-04 | specified | RE-F-02 | PD-008 |
| RE-F-02 | An unparsable or ill-typed expression cannot be published; the error names the row and the fix | S2 | M1 | RE 7.2 Logic | RLE-004 | TR-DET-02, TR-SEC-01 | specified | | PD-005 |
| RE-F-03 | Money, dates and nulls behave predictably: cross-currency comparison fails, a null comparison is false unless `is null` | S2 | M1 | RE 7.2 Logic | RLE-005, RLE-006 | TR-DET-01, TR-DET-03 | specified | RE-F-02 | |
| RE-F-04 | An adversarial regex cannot hang an evaluation | S1 | M1 | RE 7.2 Logic | RLE-007 | TR-SEC-02 | specified | | |
| RE-F-05 | Versions take effect on their effective date; any past decision replays to the same result; evaluation can pin a version | S1, S3 | M1 | RE 7.2 Logic, 7.3 | RLE-008 | TR-DET-01, TR-DET-05 | specified | RE-F-14 | |
| RE-F-06 | A requester can never approve their own publication, through Java or REST | S3 | M1 | RE 7.2 Logic | RLE-009 | TR-SEC-04 | specified | X-F-06 | PD-012 |
| RE-F-07 | Approver rejects a pending version; an author retires a version; a version retires when a newer one becomes effective | S2, S3 | M1 | RE 7.3 | | TR-OBS-04 | spec gap | RE-F-06 | |
| RE-F-08 | The engine API returns the row-level diff between two versions | S3 | M1 | RE 7.3 | | | spec gap | | |
| RE-F-09 | Every replica serves a newly published or retired version within 5 s, with or without `LISTEN/NOTIFY`, also behind a transaction-mode pooler | S1 | M1 | RE 7.2 API | RLE-010 | TR-ASY-04, TR-DET-01, TR-OBS-04 | specified (RLE-010 must add the `TR-ASY-04` trace and the pooler case) | ADR-0009 | PD-021, PD-030 |
| RE-F-10 | The compiled cache stays within `cache.max-entries`; heap stays flat when more versions rotate | S1 | M1 | RE 7.2 API | | TR-PERF-06 | spec gap | RE-F-09 | PD-021, PD-030 |
| RE-F-11 | A tenant never reads or evaluates another tenant's rule sets or decisions; a tenant in the request body is ignored | S1, S3 | M1 | RE 7.2 Data | RLE-011 | TR-SEC-03, TR-TEN-01, TR-TEN-02 | specified | | |
| RE-F-12 | Inputs marked for masking are stored and shown as keyed HMAC and stay searchable by exact match | S3 | M1 | RE 7.2 Data | RLE-012 | TR-SEC-05, TR-SEC-10 | specified | | |
| RE-F-13 | Start-up fails when a loaded definition masks a field and no key is set; the key never appears in logs or responses | S1 | M1 | RE 7.3 | | TR-SEC-10 | spec gap | RE-F-12 | |
| RE-F-14 | Each decision is logged in the host transaction with exactly one insert and rolls back with it | S1, S3 | M1 | RE 7.2 Data | RLE-013 | TR-EMB-04, TR-PERF-03, TR-OBS-01 | specified | X-F-01 | |
| RE-F-15 | The decision log can be written asynchronously for high-volume hosts | S1 | M1 (cut candidate) | RE 7.3 | | TR-OBS-01 | spec gap | RE-F-14 | PD-031 |
| RE-F-16 | The decision log is range-partitioned by month from the first migration, with partitions created ahead | S1 | M1 | RE 7.3 | | TR-OPS-04 | spec gap | X-F-01 | PD-004, PD-020 |
| RE-F-17 | An auditor finds decisions by rule set, period, outcome, rule id or output value | S3 | M1 | RE 7.3 | | TR-OBS-01 | spec gap | RE-F-14 | PD-031 |
| RE-F-18 | A decision is explained in plain language generated from the log alone | S3 | M1 | RE 7.3 | | TR-OBS-05 | spec gap | RE-F-14 | PD-027 |
| RE-F-19 | Authors write explanation templates per rule set | S2, S3 | M3 | RE 7.3, 8 | | TR-OBS-05 | spec gap | RE-F-18 | PD-027 |
| RE-F-20 | A host evaluates and manages rules through the Java API and `/api/bpm/rules/v1`; every mutation and evaluate accepts an idempotency key, rejects a reused key with a different body and answers an in-flight duplicate with `409` | S1 | M1 | RE 7.2 API | RLE-014 | TR-IDEM-01, TR-IDEM-05, TR-SEC-08 | specified | X-F-05 | PD-021 |
| RE-F-21 | An author simulates a draft over sample inputs next to the effective version; nothing is logged | S2 | M1 | RE 7.2 Logic | RLE-016 | TR-OBS-01, TR-DET-01 | specified | RE-F-02 | |
| RE-F-22 | An author replays logged decisions through a draft and sees which outcomes change; nothing is logged | S2, S3 | M3 | RE 8 | RLE-015 | TR-OBS-01 | specified | RE-F-17 | PD-018, PD-031, PD-042 |
| RE-F-23 | A simulation report is attached to a publication request for the approver | S3 | M3 | RE 7.3 | | | spec gap | RE-F-21, RE-F-22 | PD-022 |
| RE-F-24 | An author compares two versions over a past period (what-if) | S2 | M3 (cut candidate) | RE 7.3 | | | spec gap | RE-F-22 | PD-031 |
| RE-F-25 | An author imports and exports rule sets as YAML or JSON | S2 | M1 | RE 7.3 | | | spec gap | RE-F-02 | |
| RE-F-26 | A host evaluates up to 1 000 items in one batch call | S1 | M1 (cut candidate) | RE 7.3 | | TR-DET-01 | spec gap | RE-F-01 | PD-031 |
| RE-F-27 | A host registers pure functions the expression language can call | S1, S2 | M1 | RE 7.3 | | TR-SEC-01 | spec gap | RE-F-02 | |
| RE-F-28 | A developer tests rule sets in JUnit and gets a row coverage report in CI | S1 | M1 | RE 7.3 | | TR-QA-01 | spec gap | RE-F-01 | |
| RE-F-29 | Hosts get metrics and events for evaluation, publish and retire | S1 | M1 | RE 7.2 Observability, 7.3 | RLE-010 (published event only) | TR-OBS-02, TR-OBS-04, TR-OBS-06 | spec gap | X-F-07 | |
| RE-F-30 | An author imports a DMN decision table | S2 | M3 (cut candidate) | RE 8 | | | spec gap | RE-F-25 | PD-031 |
| RE-F-31 | The host sets decision log retention; whole monthly partitions older than it are dropped | S3 | M3 | RE 8 | | TR-OPS-04 | spec gap | RE-F-16 | PD-004, PD-020 |
| RE-F-32 | Evaluation adds little to the host transaction: p99 under 100 µs for a 200-row `first` table; a 1 000-row table compiles in under 500 ms | S1 | M1 | RE 4 KR3 | | TR-PERF-01, TR-PERF-02 | specified | RE-F-01 | |
| RE-F-33 | An author starts a new draft from any earlier version to roll back a change; history is never rewritten and the draft needs approval like any other | S2 | M1 if PD-043 | RE 7.3; UJ UC-RE-03, UC-RE-07 | | TR-SEC-04 | spec gap | RE-F-07 | PD-043 |
| RE-F-34 | Support and audit staff find decisions by correlation id (the host's application or case reference) or by an exact input value; masked inputs match by keyed hash | S3 | M2 (cut candidate) if PD-043 | RE 7.3; UJ UC-RE-41, UC-RE-46 | | TR-SEC-05 | spec gap | RE-F-17, RE-F-12 | PD-043 |
| RE-F-35 | A requester withdraws their own pending publication request; the version returns to draft and the withdrawal is logged | S2 | later | UJ UC-RE-10 | | | design preview | RE-F-07 | |
| RE-F-36 | A rule set can require approvals from two different roles (for example risk and compliance) before it is published | S3 | later | UJ UC-RE-36 | | TR-SEC-04 | design preview | RE-F-06 | PD-012 |
| RE-F-37 | Author and approve permissions can be limited to some rule sets (pricing staff edit only fee tables) | S1, S3 | later | UJ UC-RE-61 | | TR-SEC-04 | design preview | X-F-06 | PD-003 |

## Process Engine

| Id | Feature | Persona | Milestone | PRD | SPEC | TR | Status | Depends on | Decisions |
|---|---|---|---|---|---|---|---|---|---|
| PE-F-01 | Definitions are versioned (JSON or YAML); a running instance stays on its version; deploy creates a draft that needs approval (PE-F-45) | S1 | M2 | PE 7.2 Data | PRE-001 | TR-DET-01, TR-TEN-02 | specified | | PD-033, PD-034 |
| PE-F-02 | A retried start never creates a second instance; the business key is unique per tenant and definition | S1 | M2 | PE 7.2 Logic | PRE-002 | TR-IDEM-01, TR-IDEM-02, TR-IDEM-05 | specified | | |
| PE-F-03 | A stale task or instance update is rejected with a conflict, never applied twice | S1, S2 | M2 | PE 7.2 Logic | PRE-003 | TR-IDEM-03 | specified | | |
| PE-F-04 | An exclusive gateway routes to exactly one branch | S1 | M2 | PE 7.2 Logic | PRE-004 | TR-DET-01 | specified | | |
| PE-F-05 | A parallel join produces exactly one outgoing token under concurrent arrival on different replicas | S1 | M2 | PE 7.2 Logic | PRE-005 | TR-ASY-03, TR-PERF-05 | specified (PRE-005 should trace `TR-ASY-03`) | | |
| PE-F-06 | Timers fire once across replicas and recover after a crashed claimant | S1, S2 | M2 | PE 7.2 Logic | PRE-006 | TR-PERF-05, TR-ASY-02 | specified | | |
| PE-F-07 | Human tasks are assigned by user, role or group and can be claimed, completed, delegated and reassigned | S2 | M2 | PE 7.4 | | TR-IDEM-03 | spec gap | PE-F-03, X-F-06 | |
| PE-F-08 | Service steps run in the host transaction when local and through the `ServiceAdapter` SPI with the async protocol when remote | S1 | M2 | PE 7.2 API, 7.4 | | TR-ASY-01 | spec gap | PE-F-16 | |
| PE-F-09 | A `decision` step evaluates a rule set in the host transaction and routes on its outputs | S1 | M2 | PE 7.4 | | TR-EMB-04 | spec gap | RE-F-01, RE-F-20 | |
| PE-F-10 | Four-eyes: the requester or an earlier actor cannot complete an approval task | S2 | M2 | PE 7.2 Logic | PRE-007 | TR-SEC-04 | specified | PE-F-07 | |
| PE-F-11 | SLA deadlines respect working hours, holidays and time zone per tenant and do not move when the calendar changes | S2 | M2 | PE 7.2 Logic, 7.2 API | PRE-008 | TR-CAL-01, TR-CAL-02 | specified | | |
| PE-F-12 | A step starts a child process (`callProcess`) and waits for it | S1 | M2 (cut candidate) | PE 7.4 | | | spec gap | Design question on child compensation (gate M2 start) | PD-031 |
| PE-F-13 | An overdue task escalates to the configured user or role | S2 | M2 | PE 7.4 | | TR-CAL-01 | spec gap | PE-F-11 | |
| PE-F-14 | A tenant never reads or changes another tenant's definitions, instances, tasks or history | S1 | M2 | PE 7.4 | | TR-TEN-01, TR-TEN-02, TR-SEC-03 | spec gap | | |
| PE-F-15 | Every transition is recorded in append-only history | S2, S3 | M2 | PE 7.2 Data, 7.4 | | TR-OBS-01 | spec gap | | |
| PE-F-16 | Agent and remote steps never hold a database connection or lock during the call; the lease is the step timeout plus a margin | S1, S3 | M2 | PE 7.2 Logic | PRE-009, PRE-017 | TR-ASY-01, TR-AGT-03 | specified | ADR-0008 | PD-029 |
| PE-F-17 | A result that arrives after its lease expired is discarded and leaves no state change | S1 | M2 | PE 7.4 | | TR-ASY-02 | spec gap | PE-F-16 | PD-029 |
| PE-F-18 | Agent output advances the process only after it passes the JSON Schema and the guardrail rule set; a violation creates an approval task with output and fired rules | S3 | M2 | PE 7.2 Logic, 7.3 | PRE-010 | TR-AGT-01 | specified | RE-F-01, PE-F-16 | |
| PE-F-19 | Deploy rejects an `agentTask` whose guardrail rule set breaks the guardrail contract | S3 | M2 | PE 7.2 Logic | PRE-016 | TR-AGT-01 | specified | RE-F-20 | |
| PE-F-20 | `onViolation: fail` raises an incident; the escalation task refuses the identity that triggered the step | S3 | M2 | PE 7.4 | | TR-AGT-01, TR-SEC-04 | spec gap | PE-F-18, PE-F-10 | |
| PE-F-21 | Agent steps run under timeout and retry; exhausted retries raise an incident with cause and count | S2, S3 | M2 | PE 7.2 Logic | PRE-011 | TR-AGT-01, TR-AGT-03, TR-OPS-03 | specified | PE-F-16 | |
| PE-F-22 | History records masked agent inputs, output, schema result, guardrail decision id, latency and any human override | S3 | M2 | PE 7.2 Data | PRE-012 | TR-AGT-02, TR-OBS-01 | specified | PE-F-15 | |
| PE-F-23 | History records the model and prompt version the adapter reports | S3 | M2 if PD-014 | PE 7.5 | | TR-AGT-02 | design preview | PE-F-22 | PD-014 |
| PE-F-24 | Hosts plug agents in through the `AgentAdapter` SPI, in process or over HTTP | S1, S3 | M2 | PE 7.2 API, 8 | | TR-AGT-03 | spec gap | PE-F-16 | |
| PE-F-25 | Operators retry or resolve an incident by API | S2 | M2 | PE 7.4 | | TR-OPS-03 | spec gap | PE-F-21 | |
| PE-F-26 | A failed saga undoes completed steps in reverse order with attempt ids; a failed or manual compensation raises an incident | S1, S2 | M2 | PE 7.2 Logic | PRE-013 | TR-COMP-01, TR-COMP-02, TR-COMP-03, TR-IDEM-04 | specified | PE-F-08 | |
| PE-F-27 | A `saga` block scopes compensation; `throwCompensation` starts it explicitly | S1 | M2 | PE 7.4 | | TR-COMP-01 | spec gap | PE-F-26 | |
| PE-F-28 | Hosts manage definitions, instances, task queues, incidents and history through `/api/bpm/processes/v1`; deploy creates a draft (PD-033) | S1, S2 | M2 | PE 7.2 API | | TR-SEC-08, TR-IDEM-01 | spec gap | X-F-05 | PD-033 |
| PE-F-29 | A migration plan is validated as a dry run that changes nothing | S2 | M2 | PE 7.2 Logic | PRE-014 | TR-MIG-01 | specified | PE-F-01 | PD-017 |
| PE-F-30 | A migration executes in batches; one instance's failure affects only that instance | S2 | M2 (cut candidate) | PE 7.2 Logic | PRE-015 | TR-MIG-02 | specified | PE-F-29 | PD-017, PD-031 |
| PE-F-31 | A migration maps renamed steps explicitly and cancels removed steps | S2 | M3 | PE 7.4, 8 | | TR-MIG-03 | spec gap | PE-F-30 | |
| PE-F-32 | Hosts get metrics for active instances, incidents and timer lag, and events for start, completion and incident | S1, S2 | M2 | PE 7.2 Observability | | TR-OBS-02, TR-OBS-04, TR-OBS-06 | spec gap | X-F-07 | |
| PE-F-33 | Throughput of 1 000 step transitions per second per replica is measured and reported | S1 | M2 | PE 7.4 | | TR-PERF-04 | spec gap | | PD-026 |
| PE-F-34 | An MCP tool adapter is available as an example outside the engine artifacts | S3 | M3 | PE 8 | | TR-AGT-04 | spec gap | PE-F-24 | |
| PE-F-35 | Reference adapters for common model APIs are available in `examples/` | S3 | M3 | PE 8 | | TR-AGT-04 | spec gap | PE-F-24 | |
| PE-F-36 | An agent receives bounded context from earlier steps as memory | S3 | M3 | PE 7.4, 8 | | | spec gap | PE-F-22 | |
| PE-F-37 | An offline runner replays recorded agent traces and human overrides against a changed prompt or guardrail | S3 | M3 | PE 7.3, 7.4, 8 | | | spec gap | PE-F-22 | PD-014 |
| PE-F-38 | Adopters follow the autonomy ladder (rungs 1 to 3) and eval bar to raise agent autonomy one guardrail version at a time | S3 | M2 | PE 7.3 | PRD 7.3 (guidance, no engine behaviour) | | specified | PE-F-18 | |
| PE-F-39 | An agent selects the next step among declared steps inside a bounded block, under the same guardrails | S3 | M3 (PD-028 proposes later) | PE 7.4, 8 | | | spec gap | PE-F-18 | PD-028 |
| PE-F-40 | Processes correlate messages between instances | S1 | later | PE 8 | | | design preview | | |
| PE-F-41 | An architect imports a BPMN subset into a definition | S1 | M3 | PE 8 | | | spec gap | PE-F-01 | PD-031, PD-034 |
| PE-F-42 | Several agents plan and coordinate steps | S3 | later | PE 8 | | | design preview | PE-F-39 | PD-028 |
| PE-F-43 | Adopters run an agent in shadow (rung 0): output recorded, the process follows the human path | S3 | M3 if PD-015 | PE 7.5 | | | design preview | PE-F-37 | PD-015 |
| PE-F-44 | A definition's diagram layout is stored apart from the definition hash, append-only, with who changed it; moving a box needs no approval | S1, S2 | M3 | PE | | TR-DET-01 | spec gap | PE-F-01 | PD-032, PD-040 |
| PE-F-45 | A process definition goes draft, pending approval, active, retired; a requester can never approve it; it takes effect on its effective date; an import arrives as a draft; the content hash shows whether it matches Git; a host can turn off draft creation from the cockpit per environment; `dev` may auto-approve | S1, S2 | M2 | PE 7.2 Logic | | TR-SEC-04, TR-OBS-04 | spec gap | PE-F-01, PE-F-28, X-F-06 | PD-033, PD-036, PD-040 |
| PE-F-46 | The engine API returns the graph diff between two definition versions: nodes and edges added, removed or changed | S2 | M2 | PE | | | spec gap | PE-F-45 | PD-033 |
| PE-F-47 | An instance's activity history (nodes run, when, how long, attempts, who acted) is read bounded by the instance start | S2 | M2 | PE 7.2 Data | | TR-OBS-01 | spec gap | PE-F-15 | PD-032 |
| PE-F-48 | Live counts of active tokens per node of a definition version, from an index, never from history | S2 | M2 | PE | | | spec gap | PE-F-01 | PD-038 |
| PE-F-49 | Hourly rollup of counts, incidents and p50 and p95 durations per node, filled by the scheduler with a watermark, a few minutes behind | S2 | M3 | PE | | | spec gap | PE-F-15, PE-F-48 | PD-038 |
| PE-F-50 | Tasks are queried by assignee, role, group, due date and SLA breach; the caller resolver supplies roles and groups | S2 | M2 | PE 7.4 | | TR-TEN-01 | spec gap | PE-F-07 | PD-035 |
| PE-F-51 | A `userTask` declares a form (JSON Schema with display hints) or a link to the host's own screen | S1, S2 | M2 | PE 7.4 | | | spec gap | PE-F-07 | PD-032 |
| PE-F-52 | The host publishes a step catalogue: registered service and agent adapters with input schemas, rule sets that meet the guardrail contract, calendars and roles | S1, S3 | M3 | PE | | TR-SEC-01 | spec gap | PE-F-08, PE-F-24, PE-F-19 | PD-032 |
| PE-F-53 | An architect exports a definition as a BPMN subset; elements outside the subset are named | S1 | M3 (cut candidate) | PE 8 | | | spec gap | PE-F-41 | PD-034 |

## Cockpit

| Id | Feature | Persona | Milestone | PRD | SPEC | TR | Status | Depends on | Decisions |
|---|---|---|---|---|---|---|---|---|---|
| CK-F-01 | The cockpit runs embedded in the host or standalone from one base URL, with security headers and a CORS allowlist | All | M1 | CK 7.2 | CKP-001 | TR-UI-01, TR-SEC-09 | specified | | PD-035 |
| CK-F-02 | An author lists rule sets, reads a version and imports or exports it | Author | M1 | CK 7.3, 8 | | TR-UI-02 | spec gap | RE-F-25 | PD-013 |
| CK-F-03 | An approver sees added, changed and removed rows side by side | Approver | M1 | CK 7.2 | CKP-002 | TR-UI-02 | specified | RE-F-08 | |
| CK-F-04 | An approver works the approvals queue; Approve is disabled for the requester; reject needs a comment | Approver | M1 | CK 7.2 | CKP-003 | TR-SEC-04, TR-UI-02 | specified | RE-F-06, RE-F-07 | PD-016 |
| CK-F-05 | An author runs a sample simulation and sees changed outcomes | Author | M1 | CK 7.2 | CKP-004 | TR-UI-02 | specified | RE-F-21 | |
| CK-F-06 | An auditor filters decisions, opens one and sees the matched row, the explanation and masked inputs | Auditor | M1 | CK 7.2 | CKP-005 | TR-SEC-05, TR-OBS-05 | specified (filters not yet in CKP-005) | RE-F-17, RE-F-18 | PD-027 |
| CK-F-07 | Every error shows its code and `correlationId`; every mutation sends an idempotency key and `If-Match` | All | M1 | CK 7.2 API | CKP-008 (0.2 only) | TR-SEC-08, TR-IDEM-01 | spec gap | X-F-05 | |
| CK-F-08 | Every screen is keyboard operable with WCAG 2.1 AA contrast in light and dark themes | All | M1 (re-run M2) | CK 7.2 | CKP-009 | TR-UI-03 | specified | | PD-026 |
| CK-F-09 | Every screen is available in English and Vietnamese | All | M1 (re-run M2) | CK 7.2 | CKP-010 | TR-UI-04 | specified | | PD-026 |
| CK-F-10 | An author edits a table in a grid with inline validation | Author | M2 | CK 7.2 | CKP-006 | TR-DET-02, TR-UI-02 | specified | RE-F-02 | PD-013 |
| CK-F-11 | An author replays logged decisions through a draft in the cockpit | Author, Approver | M3 | CK 7.2 | CKP-011 | TR-UI-02 | specified | RE-F-22 | PD-018, PD-031, PD-042 |
| CK-F-12 | An operator browses definitions and opens an instance with its current step and history timeline; the diagram view is CK-F-25 | Operator, Auditor | M2 | CK 7.3, 8 | | | spec gap | PE-F-15, PE-F-28 | PD-032 |
| CK-F-13 | An operator works task queues: claim, complete, reassign | Operator | M2 | CK 7.3, 8 | | | spec gap | PE-F-07 | |
| CK-F-14 | A reviewer inspects an escalated agent task and approves or overrides it with a reason | Approver | M2 | CK 7.2 | CKP-007 | TR-AGT-01, TR-AGT-02 | specified | PE-F-18, PE-F-22 | |
| CK-F-15 | An operator works the incident queue and retries or resolves an incident | Operator | M2 | CK 7.2 | CKP-008 | TR-OPS-03 | specified | PE-F-25 | PD-016 |
| CK-F-16 | An operator sees which compensation handlers ran, in which order, in the instance timeline | Operator | M2 | CK 7.3 | | TR-COMP-02 | spec gap | PE-F-26, CK-F-12 | PD-024 |
| CK-F-17 | An operator filters the task queue to SLA breaches | Operator | M2 | CK 7.3 | | TR-CAL-01 | spec gap | PE-F-11, CK-F-13 | PD-024, PD-016 |
| CK-F-18 | An operator edits business calendars per tenant | Operator | M3 (cut candidate) | CK 7.3, 8 | | TR-CAL-01 | spec gap | PE-F-11 | PD-031, PD-042 |
| CK-F-19 | An operator plans, validates and runs an instance migration from the cockpit | Operator | M3 | CK 7.3, 8 | | TR-MIG-03 | spec gap | PE-F-31 | PD-023 |
| CK-F-20 | An approver reads the simulation report attached to a publication request | Approver | M3 | CK 7.3, 8 | | | spec gap | RE-F-23 | PD-022 |
| CK-F-21 | Landing views show a strip of key counts and health | Operator | later (not scheduled) | CK 7.3 | | | design preview | | PD-016 |
| CK-F-22 | A hosted cockpit | All | later | CK 8 | | | design preview | | |
| CK-F-23 | The standalone cockpit lists several hosts from its configuration, signs in once with OIDC PKCE, switches host, and hides screens a host or user cannot use | All | M1 | CK 7.2 | | TR-UI-01, TR-SEC-09 | spec gap | CK-F-01, X-F-22 | PD-035 |
| CK-F-24 | An operator or auditor sees a definition version as a diagram, laid out automatically when it has no stored layout | Operator, Auditor | M2 | CK 7.3 | | TR-UI-03 | spec gap | PE-F-01 | PD-032 |
| CK-F-25 | An operator sees an instance as a diagram with its current tokens and, per node, when it ran, how long, attempts and who acted | Operator, Auditor | M2 | CK 7.3 | | TR-UI-03 | spec gap | CK-F-24, PE-F-47, CK-F-12 | PD-032 |
| CK-F-26 | An operator sees live active-token and incident counts on each node of a definition diagram | Operator | M2 (cut candidate) | CK 7.3 | | | spec gap | CK-F-24, PE-F-48 | PD-038, PD-016 |
| CK-F-27 | A process owner sees a historical heatmap of counts and p50 and p95 durations per node for a chosen window | Operator | M3 (cut candidate) | CK 7.3 | | | spec gap | CK-F-24, PE-F-49 | PD-038 |
| CK-F-28 | An operator works one task inbox merged across hosts, ordered by due date | Operator | M2 | CK 7.3 | | | spec gap | CK-F-23, CK-F-13, PE-F-50 | PD-035 |
| CK-F-29 | An operator works one incident list merged across hosts | Operator | M2 | CK 7.3 | | TR-OPS-03 | spec gap | CK-F-23, CK-F-15 | PD-035 |
| CK-F-30 | An operator completes a task through its declared form, or follows the link to the host's screen | Operator, Approver | M2 | CK 7.3 | | TR-UI-03, TR-UI-04 | spec gap | PE-F-51, CK-F-13 | PD-032 |
| CK-F-31 | An approver reviews a process definition change as a graph diff and approves or rejects it; Approve is disabled for the requester | Approver | M3 | CK 7.2 | | TR-SEC-04, TR-UI-02 | spec gap | PE-F-45, PE-F-46, CK-F-24 | PD-033 |
| CK-F-32 | An author changes a process on a canvas using only steps from the host's step catalogue; the engine validates on save and the result is a draft | Author | M3 (cut candidate, fallback 1.1) | CK 7.3 | | TR-SEC-01, TR-UI-02 | spec gap | PE-F-44, PE-F-45, PE-F-52 | PD-032, PD-036 |
| CK-F-33 | An operator sees a migration plan as two diagrams side by side with the mapped steps | Operator | M3 | CK 7.3 | | TR-MIG-03 | spec gap | CK-F-19, CK-F-24 | PD-032, PD-023 |
| CK-F-34 | A host screen links to one decision in the cockpit by its id; the cockpit opens it after sign-in, with the same masking and permissions | Auditor, support staff | M1 if PD-043 | CK 7.3; UJ UC-RE-40 | | TR-SEC-05 | spec gap | CK-F-06 | PD-043 |
| CK-F-35 | An author exports a rule set's rows as CSV, edits them in a spreadsheet and imports the file as a new draft; cells accept the condition shorthand; errors name the row and column in plain language in English and Vietnamese; inputs and outputs cannot change through the file | Author | M1 if PD-043 | CK 7.3; UJ UC-RE-01, UC-RE-02, UC-RE-03, UC-RE-13, UC-RE-14, UC-RE-25 | | TR-DET-02, TR-UI-02, TR-UI-04 | spec gap | RE-F-25, RE-F-02 | PD-043, PD-013 |
| CK-F-36 | An author creates a new rule set in the grid from a template (fee by tier, eligibility with reason codes, limit by segment, blank): name, hit policy, inputs and outputs with types and allowed values, then rows | Author | M2 if PD-043 | CK 7.3; UJ UC-RE-15, UC-RE-16 | | TR-UI-02 | spec gap | CK-F-10 | PD-043 |
| CK-F-37 | Grid cells accept the condition shorthand, offer allowed values as drop-downs and show each condition in plain language ("Balance is at least 50,000,000 VND") to the author and the approver | Author, Approver | M2 if PD-043 | CK 7.3; UJ UC-RE-14, UC-RE-30 | | TR-UI-02, TR-UI-04 | spec gap | CK-F-10, CK-F-35 | PD-043 |
| CK-F-38 | An auditor sees a rule set's version history (author, requester, approver, comments, effective dates) and exports it with selected decisions as CSV or JSON evidence; masked inputs stay masked | Auditor | M3 (cut candidate) if PD-043 | CK 7.3; UJ UC-RE-45, UC-RE-47 | | TR-SEC-05, TR-OBS-01 | spec gap | RE-F-07, RE-F-17 | PD-043 |
| CK-F-39 | An author copies a rule set from one tenant into another as a draft ("fork from tenant default"); until then, export and import | Author | later | UJ UC-RE-21 | | TR-TEN-01 | design preview | RE-F-25, RE-F-11 | |

## Cross-cutting

| Id | Feature | Persona | Milestone | PRD | SPEC | TR | Status | Depends on | Decisions |
|---|---|---|---|---|---|---|---|---|---|
| X-F-01 | A host adds one starter and one configuration block; the engine's schema is created in its own Flyway history and the engine uses the host's `DataSource` and transactions | Host developer | M0 | V; RE 7.2 Data | RLE-013 | TR-EMB-01, TR-EMB-04, TR-EMB-06 | specified | | |
| X-F-02 | Runs on Java 21 and 25, Spring Boot 4.x (and 3.5.x until 1.0), PostgreSQL 14+ | Host developer | M1 | V | | TR-EMB-02 | specified | Jackson major fixed in DESIGN | PD-007 |
| X-F-03 | Core modules have no Spring dependency and perform no network I/O; the engines never call a model | Host developer | M1 | V | | TR-EMB-03, TR-EMB-05, TR-AGT-03 | specified | | |
| X-F-04 | No definition or input can execute code | Host developer, auditor | M1 | V; RE 7.2 Logic | RLE-004 | TR-SEC-01 | specified | | |
| X-F-05 | Every REST error is a Problem Details body with a stable `code`, a `correlationId` and a `type` URI to the wiki error-code page | Host developer | M1 (rules), M2 (processes) | RE 7.2 API | | TR-SEC-08 | spec gap | | PD-006 |
| X-F-06 | Hosts map their roles to the permission set `<module>:<action>` | Host developer, all cockpit personas | M1 (rules), M2 (processes) | CK 5 | | TR-SEC-04 | spec gap | | PD-003 |
| X-F-07 | Every event carries the standard envelope, a versioned type, and is published in the transaction that changes state | Host developer | M1 | RE 7.2 Observability | | TR-OBS-06 | spec gap | | |
| X-F-08 | The engines work behind a transaction-mode connection pooler | Host developer | M1 | RE 7.2 API | | TR-OPS-05 | specified | | PD-021 |
| X-F-09 | Engine migrations are forward-only and every release notes the ones it adds | Host developer | M1 | V | | TR-OPS-01 | specified | | |
| X-F-10 | Old and new module versions tolerate each other's schema for one minor release | Host developer | M3 | RE 8; PE 8 | | TR-OPS-02 | specified | X-F-09 | |
| X-F-11 | Evaluations and steps emit OpenTelemetry spans when a tracer is present | Host developer | M3 | RE 8 | | TR-OBS-03 | spec gap | | |
| X-F-12 | Every release ships an SBOM, blocks on high or critical vulnerabilities, uses only permissive third-party licences and carries the licence notice | Host developer, auditor | M0 | V | | TR-SEC-06, TR-LIC-01, TR-LIC-02 | specified | | |
| X-F-13 | Every SPEC statement has a test named by its id; public API changes update the wiki in the same pull request | Contributor | M0 | V | | TR-QA-01, TR-QA-02, TR-QA-03 | specified | | |
| X-F-14 | `examples/rule-engine-only` boots against PostgreSQL (M0) and runs the full rule journey with Cockpit 0.1 (M1) | Host developer | M1 | RE 4 KR2 | | TR-EMB-01 | specified | X-F-01 | |
| X-F-15 | `examples/both` runs a process with a `decision` step and an `agentTask` guarded by a rule set and escalated to a person | Host developer, agent step designer | M2 | PE 4 KR1 | | TR-AGT-01 | specified | PE-F-18, PE-F-09 | |
| X-F-16 | A newcomer goes from an empty Spring Boot service to a first logged decision in under 30 minutes | Host developer | M1 | RE 4 KR1; V | | | spec gap (needs a timed newcomer protocol) | X-F-14 | PD-019 |
| X-F-17 | A documentation site is generated from the wiki | All | M3 (cut candidate) | V | | | spec gap | | PD-031 |
| X-F-18 | The public API is frozen at 1.0 with a deprecation policy of one minor release overlap | Host developer | M3 | V; RE 8; PE 8 | | | spec gap | | |
| X-F-19 | The REST layer and cockpit are reviewed against OWASP ASVS level 2 per minor release | Auditor | M1 | V | | TR-SEC-07 | specified | | |
| X-F-20 | Other databases through a storage SPI | Host developer | later | V; PE 8 | | | design preview | | |
| X-F-21 | Jakarta EE integration | Host developer | later | V | | | design preview | | |
| X-F-22 | Each host answers `/api/bpm/meta` (engine and API versions, supported features) and `/api/bpm/me` (tenant, user, permissions); the cockpit supports the current and previous engine minor version | Host developer, all cockpit personas | M1 | CK 7.2 | | TR-SEC-08 | spec gap | X-F-06 | PD-035 |
| X-F-23 | An operator switches tenant in the cockpit; the host accepts `X-Tenant-Id` only for a tenant in the token's allowed list; no screen combines tenants | Operator, Host developer | M2 | CK 5, PE 7.4 | | TR-TEN-01, TR-SEC-03 | spec gap | X-F-22 | PD-037 |
| X-F-24 | Every list API needs a tenant plus a bounded index or time range, pages hold at most 200 items, and a CI query-plan check enforces it | Host developer | M2 | V | | TR-PERF-05 | spec gap | | PD-038, PD-032 |

## SPEC statement to feature index

Every SPEC statement maps to one primary feature. A statement that also verifies part of another feature is listed in that feature's SPEC column too.

| SPEC | Feature | SPEC | Feature | SPEC | Feature |
|---|---|---|---|---|---|
| RLE-001 | RE-F-01 | PRE-001 | PE-F-01 | CKP-001 | CK-F-01 |
| RLE-002 | RE-F-01 | PRE-002 | PE-F-02 | CKP-002 | CK-F-03 |
| RLE-003 | RE-F-01 | PRE-003 | PE-F-03 | CKP-003 | CK-F-04 |
| RLE-004 | RE-F-02 | PRE-004 | PE-F-04 | CKP-004 | CK-F-05 |
| RLE-005 | RE-F-03 | PRE-005 | PE-F-05 | CKP-005 | CK-F-06 |
| RLE-006 | RE-F-03 | PRE-006 | PE-F-06 | CKP-006 | CK-F-10 |
| RLE-007 | RE-F-04 | PRE-007 | PE-F-10 | CKP-007 | CK-F-14 |
| RLE-008 | RE-F-05 | PRE-008 | PE-F-11 | CKP-008 | CK-F-15 |
| RLE-009 | RE-F-06 | PRE-009 | PE-F-16 | CKP-009 | CK-F-08 |
| RLE-010 | RE-F-09 | PRE-010 | PE-F-18 | CKP-010 | CK-F-09 |
| RLE-011 | RE-F-11 | PRE-011 | PE-F-21 | CKP-011 | CK-F-11 |
| RLE-012 | RE-F-12 | PRE-012 | PE-F-22 | | |
| RLE-013 | RE-F-14 | PRE-013 | PE-F-26 | | |
| RLE-014 | RE-F-20 | PRE-014 | PE-F-29 | | |
| RLE-015 | RE-F-22 | PRE-015 | PE-F-30 | | |
| RLE-016 | RE-F-21 | PRE-016 | PE-F-19 | | |
| | | PRE-017 | PE-F-16 | | |

## SPEC corrections already decided

These SPEC lines disagree with decided entries and are tracked here until the SPEC changes.

| SPEC | Current text | Decided | Owner |
|---|---|---|---|
| RLE-002 | `UNIQUE_HIT_VIOLATION` returns `409` | `422` (PD-008) | Architect |
| RLE-010 | Traces `TR-DET-01`, `TR-OBS-04`; "immediately (or within epoch fallback bound)" | Trace `TR-ASY-04`; assert the 5 s bound with and without `LISTEN/NOTIFY` (PD-021, pending) | Architect |
| PRE-005 | Traces `TR-PERF-05` | Trace `TR-ASY-03` | Architect |

## Summary

Each cell: features (specified / spec gap / design preview). Generated from the tables above; regenerate when a row changes. Features whose milestone reads "if PD-043" are not counted until [PD-043](decisions.md#pd-043-rule-authoring-for-business-users-without-yaml) is decided; they are listed below the table.

| Module | M0 | M1 | M2 | M3 | Later | Total |
|---|---|---|---|---|---|---|
| Rule Engine | 0 | 26 (13 / 13 / 0) | 0 | 6 (1 / 5 / 0) | 3 (0 / 0 / 3) | 35 (14 / 18 / 3) |
| Process Engine | 0 | 0 | 39 (17 / 21 / 1) | 12 (0 / 11 / 1) | 2 (0 / 0 / 2) | 53 (17 / 32 / 4) |
| Cockpit | 0 | 10 (7 / 3 / 0) | 13 (3 / 10 / 0) | 8 (1 / 7 / 0) | 3 (0 / 0 / 3) | 34 (11 / 20 / 3) |
| Cross-cutting | 3 (3 / 0 / 0) | 12 (7 / 5 / 0) | 3 (1 / 2 / 0) | 4 (1 / 3 / 0) | 2 (0 / 0 / 2) | 24 (12 / 10 / 2) |
| **All** | 3 (3 / 0 / 0) | 48 (27 / 21 / 0) | 55 (21 / 33 / 1) | 30 (3 / 26 / 1) | 10 (0 / 0 / 10) | 146 (54 / 80 / 12) |

Totals: 146 features; 54 specified, 80 spec gaps, 12 design previews. The cockpit direction (PD-032 to PD-042, decided 2026-11-18) added 24 features, all spec gaps: 2 in M1, 14 in M2 and 8 in M3. It moved RE-F-22, CK-F-11 and CK-F-18 from M2 to M3 and took PE-F-41 off the cut list.

Pending PD-043 (not counted): 7 features, all spec gaps. RE-F-33, CK-F-34 and CK-F-35 would join M1; RE-F-34, CK-F-36 and CK-F-37 M2; CK-F-38 M3. PD-043 also moves RE-F-26 from M1 to M2. If accepted as recommended, M1 holds 50, M2 59 and M3 31.

Cut candidates: 12 features.

| Milestone | Checkpoint | Cut, in this order | Decision |
|---|---|---|---|
| M1 | 8 Jan 2027 | RE-F-15, RE-F-26, then decision log filters beyond rule set and period | PD-031 (pending) |
| M2 | 5 Mar 2027 | PE-F-30, CK-F-26, PE-F-12 | PD-042 |
| M3 | 23 Apr 2027 | CK-F-18, CK-F-32, CK-F-27, PE-F-53, RE-F-30, RE-F-24, X-F-17 | PD-042 |

If PD-043 is accepted, RE-F-26 and RE-F-34 join the M2 list after PE-F-12, and CK-F-38 joins the M3 list after X-F-17.

Use case ids (`UC-RE-nn`) are defined in [Rule Engine users and journeys](rule-engine-users-and-journeys.md).
