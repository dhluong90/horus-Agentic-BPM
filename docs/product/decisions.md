# Product Decision Log

| Field | Value |
|---|---|
| Type | Reference (decision record) |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

This log records every product decision about the engines: scope, milestones, priorities, product rules and the acceptance of architecture decisions that change what ships. The product manager writes the question and a recommendation first; the product owner decides (PD-001). A decision is never applied to the roadmap, the feature map, a PRD or a SPEC before its status here is Decided.

Architecture decisions keep their own records in [`../adr/`](../adr/). When an ADR changes scope or a milestone, the scope consequence is recorded here and links the ADR.

## Rules for this log

- Ids are `PD-nnn`, never reused or renumbered.
- A decided entry changes only by a later entry that supersedes it; the old entry gets "Superseded by PD-nnn" and stays.
- Status is one of: Pending product owner, Decided, Superseded.
- Every entry has at least two options with consequences, the product manager's recommendation, who decided and when, and follow-ups with an owner role.
- Owner roles: product manager, architect, maintainers (engineering), documentation, QA.

## Index

| Id | Title | Status |
|---|---|---|
| [PD-001](#pd-001-how-product-decisions-are-made-and-recorded) | How product decisions are made and recorded | Decided |
| [PD-002](#pd-002-where-the-product-roadmap-and-feature-map-live) | Where the product roadmap and feature map live | Decided |
| [PD-003](#pd-003-permission-names-are-moduleaction) | Permission names are `<module>:<action>` | Decided |
| [PD-004](#pd-004-the-decision-log-is-partitioned-monthly-from-the-first-migration) | The decision log is partitioned monthly from the first migration | Decided |
| [PD-005](#pd-005-string-outputs-keep-inner-quotes) | String outputs keep inner quotes | Decided |
| [PD-006](#pd-006-error-type-uris-point-to-the-wiki-error-code-page) | Error `type` URIs point to the wiki error-code page | Decided |
| [PD-007](#pd-007-spring-boot-4x-primary-35x-tested-until-10) | Spring Boot 4.x primary, 3.5.x tested until 1.0 | Decided |
| [PD-008](#pd-008-unique_hit_violation-returns-422) | `UNIQUE_HIT_VIOLATION` returns 422 | Decided |
| [PD-009](#pd-009-licensing-stays-minimal) | Licensing stays minimal | Decided |
| [PD-010](#pd-010-the-engines-and-their-wiki-are-the-first-priority) | The engines and their wiki are the first priority | Decided |
| [PD-011](#pd-011-design-documents-live-in-docs-separate-from-the-user-wiki) | Design documents live in `docs/`, separate from the user wiki | Decided |
| [PD-012](#pd-012-emergency-single-approver-path) | Emergency single-approver path | Pending product owner |
| [PD-013](#pd-013-from-which-milestone-tr-ui-02-gates) | From which milestone `TR-UI-02` gates | Pending product owner |
| [PD-014](#pd-014-model-and-prompt-version-in-agentresult) | Model and prompt version in `AgentResult` | Pending product owner |
| [PD-015](#pd-015-autonomy-rung-0-shadow-mode-and-rung-4) | Autonomy rung 0 (shadow mode) and rung 4 | Pending product owner |
| [PD-016](#pd-016-cockpit-pulse-strip) | Cockpit pulse strip | Pending product owner |
| [PD-017](#pd-017-migration-execution-as-the-first-cut-if-m2-slips) | Migration execution as the first cut if M2 slips | Pending product owner |
| [PD-018](#pd-018-replay-simulation-in-m1) | Replay simulation in M1 | Pending product owner |
| [PD-019](#pd-019-north-star-metric) | North Star metric | Pending product owner |
| [PD-020](#pd-020-conflict-partitioning-milestone-on-the-roadmap) | Conflict: partitioning milestone on the roadmap | Pending product owner |
| [PD-021](#pd-021-conflict-exit-criteria-omit-musts-tagged-for-the-milestone) | Conflict: exit criteria omit Musts tagged for the milestone | Pending product owner |
| [PD-022](#pd-022-conflict-simulation-report-in-cockpit-01-approvals) | Conflict: simulation report in Cockpit 0.1 approvals | Pending product owner |
| [PD-023](#pd-023-conflict-milestone-of-the-instance-migration-screen) | Conflict: milestone of the instance migration screen | Pending product owner |
| [PD-024](#pd-024-conflict-compensation-runs-view-and-sla-breach-list) | Conflict: compensation runs view and SLA breach list | Pending product owner |
| [PD-025](#pd-025-conflict-process-engine-version-label) | Conflict: Process Engine version label | Pending product owner |
| [PD-026](#pd-026-conflict-should-requirements-used-as-release-gates) | Conflict: Should requirements used as release gates | Pending product owner |
| [PD-027](#pd-027-conflict-decision-explanation-in-m1) | Conflict: decision explanation in M1 | Pending product owner |
| [PD-028](#pd-028-conflict-agent-chosen-steps-in-m3-or-after-10) | Conflict: agent-chosen steps in M3 or after 1.0 | Pending product owner |
| [PD-029](#pd-029-accept-adr-0008-async-wait-state-for-external-steps) | Accept ADR-0008 (async wait-state for external steps) | Pending product owner |
| [PD-030](#pd-030-accept-adr-0009-cache-coherence) | Accept ADR-0009 (cache coherence) | Pending product owner |
| [PD-031](#pd-031-milestone-dates-and-pre-approved-cuts) | Milestone dates and pre-approved cuts | Pending product owner |

---

## Decided

### PD-001 How product decisions are made and recorded

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Who proposes and who decides product questions, and where is the decision kept? |
| Decision | Decided by the product owner, 2026-11-18 |

**Options considered**

1. The product manager proposes first on every product question; the product owner decides; every decision is recorded in this log in full (chosen). Consequence: one place to find why scope is what it is; a decision costs one entry of writing.
2. Decisions recorded only in the documents they change. Consequence: less writing, but the reason and the rejected options are lost, and conflicting edits are fixed silently.

**Recommendation.** Option 1.

**Rationale.** The engines are a design preview with three PRDs, three SPECs, a requirements list and a wiki that already disagree in places (PD-020 to PD-028). A single decision record makes every scope change visible and reviewable.

**Consequences.** Scope, milestone and priority changes start as an entry here. Architecture decisions remain ADRs; their acceptance is recorded here when it changes what ships.

**Follow-ups.** Product manager: keep the index current; link entries from the feature map and roadmap.

### PD-002 Where the product roadmap and feature map live

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Which document owns milestones, dates and feature scope and status? |
| Decision | Decided by the product owner, 2026-11-18 |

**Options considered**

1. The product roadmap source is [`roadmap.md`](roadmap.md) and feature scope and status are owned by [`feature-map.md`](feature-map.md), both in `docs/product/`, maintained by the product manager; the wiki Roadmap page is a short public view that links to the source (chosen). Consequence: design-level detail (feature ids, TR gates, kill assumptions) lives beside the PRDs; the wiki stays short for users.
2. Keep the wiki Roadmap page as owner and add a wiki Feature Status page. Consequence: users read design detail they do not need, and feature ids would sit in the user documentation.

**Recommendation.** Option 1.

**Rationale.** The wiki is for users of the engines (PD-011). Feature ids, spec gaps and exit gates are design material.

**Consequences.** [`../roadmap.md`](../roadmap.md) points to `docs/product/roadmap.md`. The ownership table in [`../README.md`](../README.md) still names the wiki Roadmap page and a future wiki Feature Status page as owners and must be updated.

**Follow-ups.** Product manager: maintain both files. Maintainers: update the "Who owns which fact" rows for milestones and feature status in `docs/README.md`.

### PD-003 Permission names are `<module>:<action>`

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | How are permission strings named across modules? |
| Decision | Decided by the product owner, 2026-11-18, on the architect's recommendation. Recorded in [ADR-0010](../adr/0010-engine-contract-conventions.md) |

**Options considered**

1. `<module>:<action>`, with `<module>:<resource>:<action>` for sub-resources (chosen). Consequence: no collisions between modules; existing `rules:*` names unchanged; process names change (`tasks:work` becomes `processes:task:work`).
2. `<module>:<resource>:<action>` everywhere. Consequence: fully regular but every `rules:*` name changes.
3. Keep global words (`tasks:work`). Consequence: collisions with the next module that has tasks or incidents.

**Recommendation.** Option 1 (the architect's recommendation, which the product manager supports).

**Rationale.** Hosts hard-code these strings in token mappers; renaming after M1 breaks every adopter.

**Consequences.** Cockpit personas and the Process Engine permission table use the new names. Feature X-F-06.

**Follow-ups.** Architect: update wiki and DESIGN permission tables. Product manager: update [`prd-cockpit.md`](prd-cockpit.md) personas (done 2026-11-18).

### PD-004 The decision log is partitioned monthly from the first migration

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Is `rle_decision` partitioned from the start, optionally by configuration, or never? |
| Decision | Decided by the product owner, 2026-11-18, on the architect's recommendation. Recorded in [ADR-0010](../adr/0010-engine-contract-conventions.md) |

**Options considered**

1. Range-partitioned by month from the first migration; configuration controls retention only (chosen). Consequence: free on an empty table, one schema to test; a little more M1 migration work.
2. Optional partitioning by configuration (the M3 roadmap item). Consequence: two schemas to test; switching later rewrites a populated table under lock.
3. Never partitioned; delete by date. Consequence: row deletes on an append-only audit table, which the audit rule forbids.

**Recommendation.** Option 1.

**Rationale.** Converting a populated audit table later is a long, locking migration for every adopter.

**Consequences.** The partitioned table moves from M3 to M1 (feature RE-F-16). The previous M3 roadmap item "monthly partitioning by configuration" no longer applies; when retention by configuration ships is PD-020 (RE-F-31). `TR-OPS-04` text no longer matches the decision.

**Follow-ups.** Architect: align `TR-OPS-04` and add an `RLE-*` statement for partition creation and retention. Product manager: roadmap and feature map updated (done 2026-11-18).

### PD-005 String outputs keep inner quotes

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Is a bare word in a `then` cell a string literal or an identifier? |
| Decision | Decided by the product owner, 2026-11-18, on the architect's recommendation. Recorded in [ADR-0010](../adr/0010-engine-contract-conventions.md) |

**Options considered**

1. Inner quotes required (`"'UNDER_AGE'"`); a bare word is an identifier (chosen). Consequence: one grammar for every cell; slightly noisier YAML for authors.
2. Auto-quote cells of string-typed outputs. Consequence: shorter YAML, but a cell that names an input changes meaning silently.

**Recommendation.** Option 1.

**Rationale.** A silent change of meaning in a fee or eligibility table is worse than a validation error that names the fix.

**Consequences.** Raises the cost of YAML authoring for analysts slightly; this feeds the red-team assumption on YAML authoring (roadmap kill assumption K3). Validation messages must name the fix (feature RE-F-02).

**Follow-ups.** Architect: authoring wiki examples and schema lint. Product manager: include a quoting error in the M1 analyst authoring sessions.

### PD-006 Error `type` URIs point to the wiki error-code page

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Where do Problem Details `type` URIs resolve? |
| Decision | Decided by the product owner, 2026-11-18, on the architect's recommendation. Recorded in [ADR-0010](../adr/0010-engine-contract-conventions.md) |

**Options considered**

1. `https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#<code>` (chosen). Consequence: the link resolves today with no hosting; the wiki page becomes a contract page.
2. A project domain. Consequence: requires a domain and hosting that do not exist.

**Recommendation.** Option 1.

**Rationale.** A `type` URI that does not resolve helps nobody; clients branch on `code`, never on `type`.

**Consequences.** The Common-Error-Codes wiki page must exist before M1 (feature X-F-05).

**Follow-ups.** Architect: create the page and keep anchors stable.

### PD-007 Spring Boot 4.x primary, 3.5.x tested until 1.0

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Which Spring Boot lines do the starters support? |
| Decision | Decided by the product owner, 2026-11-18, on the architect's recommendation. Recorded in [ADR-0011](../adr/0011-spring-boot-4-baseline.md) |

**Options considered**

1. 4.x primary; 3.5.x tested in CI until 1.0 (chosen). Consequence: first releases sit on supported lines; hosts mid-migration can adopt during 0.x; a second CI leg and Jackson 2 and 3 bridging.
2. 3.5.x primary. Consequence: open-source support ended before M1.
3. 4.x only. Consequence: excludes hosts that have not migrated during the period when early adopters choose.

**Recommendation.** Option 1.

**Rationale.** Early adopters are the M3 exit criterion; excluding 3.5 hosts shrinks the pool, while building on 3.5 alone ships on an unsupported line.

**Consequences.** M1 carries a dual CI leg and a Jackson bridging decision before code starts (dependency in the roadmap). `TR-EMB-02` text changes. Feature X-F-02.

**Follow-ups.** Architect: fix the Jackson major in both DESIGN files before M1 code; update `TR-EMB-02`. Product manager: vision and PRD platform lines updated (done 2026-11-18).

### PD-008 `UNIQUE_HIT_VIOLATION` returns 422

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Which HTTP status does a `unique` table return when zero or several rows match? |
| Decision | Decided by the product owner, 2026-11-18, on the architect's recommendation. Recorded in [ADR-0010](../adr/0010-engine-contract-conventions.md) |

**Options considered**

1. `422` (chosen). Consequence: `409` stays reserved for state conflicts a client fixes by reloading.
2. `409` (the current `RLE-002` text). Consequence: clients retry or reload, which never fixes an authoring defect.

**Recommendation.** Option 1.

**Rationale.** The error is an authoring defect in the rule set, not a state conflict.

**Consequences.** `RLE-002` still states `409` and must change (feature RE-F-01).

**Follow-ups.** Architect: change `RLE-002` and the error matrix.

### PD-009 Licensing stays minimal

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | What do the public documents say about licensing? |
| Decision | Decided by the product owner, 2026-11-18 |

**Options considered**

1. PolyForm Noncommercial 1.0.0; other use by separate agreement; no further terms published; future licence questions parked (chosen). Consequence: no licence or commercial content in roadmap, PRDs or kill assumptions.
2. Publish commercial terms and test them as a roadmap assumption. Consequence: the roadmap carries commercial assumptions the maintainers do not want to discuss publicly yet.

**Recommendation.** Option 1.

**Rationale.** Product owner's choice; the engineering plan does not depend on licence terms.

**Consequences.** The former roadmap assumption on licensing is removed. No feature in the feature map depends on licensing. `TR-LIC-*` (third-party licences and notices) are unaffected.

**Follow-ups.** None. Licence questions reopen only by a new entry.

### PD-010 The engines and their wiki are the first priority

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | What does the team work on first? |
| Decision | Decided by the product owner, 2026-11-18 |

**Options considered**

1. The engines (Rule Engine, Process Engine, Cockpit) and their wiki first (chosen). Consequence: the roadmap below is the team's primary plan; other work waits or fits around it.
2. Split capacity with other work. Consequence: the M1 to M3 dates would need to move.

**Recommendation.** Option 1.

**Rationale.** Product owner's choice.

**Consequences.** The roadmap dates assume full team focus. Any later change of priority is a reason to revisit PD-031.

**Follow-ups.** None.

### PD-011 Design documents live in `docs/`, separate from the user wiki

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Where do product and technical design documents live? |
| Decision | Decided by the product owner, 2026-11-18 |

**Options considered**

1. In `docs/` of the public repository; the wiki holds only user documentation (chosen). Consequence: users read short pages; contributors and evaluators read `docs/`.
2. Everything in the wiki. Consequence: user pages mix with design material and drift.

**Recommendation.** Option 1.

**Rationale.** Product owner's choice; matches the documentation map in [`../README.md`](../README.md).

**Consequences.** Basis for PD-002. Wiki pages link to `docs/` by full URL.

**Follow-ups.** None.

---

## Pending: open product questions

### PD-012 Emergency single-approver path

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Is a logged single-approver path for urgent rule changes ever allowed? |
| Decision | Pending product owner |

**Options considered**

1. No emergency path before 1.0; four-eyes is mandatory outside `dev`; document as a non-goal. Urgent changes use the normal flow with an on-call second approver and `effectiveFrom` now. Consequence: differentiator 2 stays absolute; some teams may need a second approver on call.
2. A logged emergency path: one approver, a mandatory reason, an event, and an automatic follow-up approval task for a second identity within N hours. Consequence: covers real urgent cases; adds a new state, permission and tests in M1 or M2, and weakens the claim that the engine enforces four-eyes.
3. A host-configurable switch to disable maker-checker per rule set. Consequence: cheapest to build; makes the control optional, which defeats it.

**Recommendation.** Option 1 for 1.0, revisited with evidence. Run the PRD red-team test (three compliance contacts on how an urgent fee change is approved today). If two of three require a single-person path, open an ADR for option 2 targeted after 1.0.

**Rationale.** Engine-enforced four-eyes is one of three differentiators in the [vision](vision.md). Option 2 is a real feature with its own risk and has no evidence behind it yet.

**Consequences.** If option 1: no feature added; non-goal added to [`prd-rule-engine.md`](prd-rule-engine.md). If option 2: new feature in M2 or after 1.0, new `RLE-*` statements, new permission.

**Follow-ups.** Product manager: three compliance conversations during M1 (kill assumption K6).

### PD-013 From which milestone `TR-UI-02` gates

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | `TR-UI-02` (author, validate, simulate, request publication and approve without leaving the cockpit) is a Must with no milestone, while Cockpit 0.1 has no editing. Does it gate M1? |
| Decision | Pending product owner |

**Options considered**

1. `TR-UI-02` becomes "Must (M2)"; M1 gates on the narrower path: import a draft from the cockpit, validate, simulate, request publication and approve (CKP-002 to CKP-004 plus CK-F-02). Consequence: M1 keeps its scope; full in-cockpit authoring is a gate once the grid editor ships.
2. YAML import from the cockpit counts as authoring, so `TR-UI-02` gates from M1 unchanged. Consequence: no requirement change, but "authoring" means uploading a file, which overstates the M1 user outcome.
3. Bring a minimal editor into Cockpit 0.1. Consequence: reverses the M1 scope choice and risks the 15 Jan date.

**Recommendation.** Option 1.

**Rationale.** Option 1 states the M1 outcome honestly and keeps the grid editor where the schedule put it.

**Consequences.** Feature CK-F-02 (rule sets list, version view, import and export) becomes an M1 must and needs a `CKP-*` statement. CK-F-10 (grid editor) carries `TR-UI-02` in M2.

**Follow-ups.** Architect: tag `TR-UI-02` "Must (M2)" and add an M1 requirement or `CKP-*` statement for import from the cockpit.

### PD-014 Model and prompt version in `AgentResult`

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Should `AgentResult` carry optional model and prompt version fields recorded in history? |
| Decision | Pending product owner |

**Options considered**

1. Add optional `modelId` and `promptVersion` strings to `AgentResult` in M2, recorded in history, never interpreted by the engine. Consequence: an additive field now; the M3 offline runner can compare like with like; history rows from M2 are usable for evaluation.
2. Leave it to adopters to put versions inside the output payload. Consequence: no engine change; every adopter does it differently and the offline runner cannot rely on it.
3. Add it in M3 with the offline runner. Consequence: M2 history cannot be compared by model or prompt.

**Recommendation.** Option 1.

**Rationale.** It is a cheap additive field, and evaluation of agent changes is the main reason to keep history. The engine still never calls or stores a model (`TR-AGT-03`).

**Consequences.** Feature PE-F-23 moves from design preview to M2. `TR-AGT-02` gains two recorded fields; `PRE-012` gains an assertion.

**Follow-ups.** Architect: extend `TR-AGT-02` and `PRE-012`; adapter SPI DESIGN.

### PD-015 Autonomy rung 0 (shadow mode) and rung 4

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Does the autonomy ladder document a rung 0 (agent observes, humans decide) and a rung 4 (end to end without sampling)? |
| Decision | Pending product owner |

**Options considered**

1. Document rung 0 as a pattern using M2 capabilities (the agent runs and its output and guardrail result are recorded, but the process follows the human path), published with the M3 offline runner; no rung 4 before 1.0, with rung 3 as the ceiling. Consequence: no engine feature; guidance only.
2. Build a `mode: shadow` flag on `agentTask` in M2. Consequence: a first-class shadow mode; adds engine scope to the most loaded milestone.
3. Document rungs 0 to 4 now. Consequence: rung 4 guidance without the offline runner to back it.

**Recommendation.** Option 1.

**Rationale.** Shadow runs are useful only once there is a way to compare outputs with human decisions, which is the M3 offline runner. Rung 4 contradicts the product position that agent output passes deterministic rules a person can audit.

**Consequences.** Feature PE-F-43 (rung 0 guidance) in M3, documentation only. Non-goal "rung 4 before 1.0" added to [`prd-process-engine.md`](prd-process-engine.md).

**Follow-ups.** Product manager: write the rung 0 pattern with the offline runner in M3.

### PD-016 Cockpit pulse strip

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The cockpit DESIGN describes a KPI pulse strip that is on no milestone. Schedule it or remove it? |
| Decision | Pending product owner |

**Options considered**

1. Remove it from the cockpit DESIGN for 0.x; show counts in the headers of the queues that exist (pending approvals, open incidents, SLA breaches). Consequence: no new scope; matches the non-goal "dashboards that replace the host's monitoring".
2. Add it to Cockpit 0.2. Consequence: new aggregate endpoints in both engines and new screens in the most loaded milestone.
3. Leave it in DESIGN, unscheduled. Consequence: DESIGN describes something the PRD does not ask for.

**Recommendation.** Option 1; revisit after 1.0 with adopter requests.

**Rationale.** Metrics belong to the host's monitoring (`TR-OBS-02`); queue header counts give operators the same signal at no extra cost.

**Consequences.** Feature CK-F-21 stays design preview, not scheduled. Queue header counts are part of CK-F-04, CK-F-15 and CK-F-17.

**Follow-ups.** Architect: remove or mark the pulse strip in `cockpit/docs/DESIGN.md`.

### PD-017 Migration execution as the first cut if M2 slips

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | If M2 runs late, which scope moves first to protect `agentTask`? |
| Decision | Pending product owner |

**Options considered**

1. Pre-approve moving batched migration execution (PE-F-30, `PRE-015`, `TR-MIG-02`) to M3 if fewer than 9 of the 17 `PRE-*` statements are green at the end of week 5 of M2; dry run (PE-F-29) stays in M2. Consequence: adopters can plan migrations in M2 but execute in M3; `agentTask` and compensation ship on time.
2. Cut `agentTask` depth instead (HTTP adapter only, no `route-to-human`). Consequence: the M2 outcome loses its differentiator.
3. No pre-approved cut; decide at the time. Consequence: a late decision under pressure, usually a date slip.

**Recommendation.** Option 1, together with the wider cut list in PD-031.

**Rationale.** Few adopters will have running instances to migrate before 1.0; every adopter of M2 needs `agentTask`, idempotency and compensation.

**Consequences.** If the trigger fires: PE-F-30 moves to M3; `TR-MIG-02` is retagged "Must (M3)"; M2 exit drops `TR-MIG-02`.

**Follow-ups.** Maintainers: report the weekly `PRE-*` green count during M2. Product manager: apply the cut on the trigger and record it as a new entry.

### PD-018 Replay simulation in M1

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Should replay of logged decisions through a draft (RE-F-22, CK-F-11) move from M2 into M1? |
| Decision | Pending product owner |

**Options considered**

1. Keep replay in M2; pull it into M1 only if the cockpit red-team test fails (an approver cannot say who is affected from a diff and five samples, in the week 3 session), paying for it by cutting REST list filters. Consequence: M1 scope protected; a defined trigger.
2. Move replay into M1 now. Consequence: stronger approval evidence at launch; adds engine and cockpit work to a six-week milestone that already absorbed partitioning (PD-004) and a dual Spring Boot baseline (PD-007).
3. Defer replay to M3. Consequence: approvers rely on samples for longer.

**Recommendation.** Option 1.

**Rationale.** Replay needs a populated decision log, which a new adopter does not have in its first weeks; samples cover the first adoption period.

**Consequences.** None unless the trigger fires; then RE-F-22 and CK-F-11 move to M1 and REST list filters on decision log query move to M2.

**Follow-ups.** Product manager: run the approver session in week 3 of M1 (kill assumption K7).

### PD-019 North Star metric

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Which single measure tells the maintainers the engines deliver value? |
| Decision | Pending product owner |

**Options considered**

1. North Star: governed decisions and process steps executed per week in adopters' services, reported voluntarily by adopters (the engines send no telemetry); tracked from M3. Before M3, track input measures: time to first logged decision for a newcomer, number of external teams running an example host, and number of `agentTask` escalations completed by a person in pilot hosts. Consequence: measures use, not downloads; depends on adopter reports.
2. Repository stars and artifact downloads. Consequence: free to collect; measures interest, not use.
3. No North Star before 1.0; only the M3 adoption exit. Consequence: no signal between milestones.

**Recommendation.** Option 1.

**Rationale.** Governed executions are what the product exists to make safe. The input measures are observable before production use.

**Consequences.** The roadmap lists the input measures per milestone. No telemetry is added (`TR-EMB-05`).

**Follow-ups.** Product manager: ask pilot adopters for a monthly count from their own metrics (`TR-OBS-02`); update [`vision.md`](vision.md) when decided.

---

## Pending: conflicts between roadmap, SPEC and wiki

Each entry records a disagreement found while writing the PRDs. Nothing is changed in the conflicting documents until the entry is decided.

### PD-020 Conflict: partitioning milestone on the roadmap

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The previous roadmap ships "monthly partitioning by configuration" in M3 and `TR-OPS-04` is a Should, while PD-004 (decided) partitions `rle_decision` from the first migration and `rule-engine/docs/DESIGN.md` already shows the partitioned DDL. Which milestone carries partitioning and retention? |
| Decision | Pending product owner |

**Options considered**

1. Partitioned table and retention by configuration in M1 (with the first migration); M3 keeps only zero-downtime upgrade tests. Consequence: follows PD-004; adds partition creation and retention to M1.
2. Partitioned table in M1, retention configuration in M3. Consequence: smaller M1; the table grows without a purge until M3, which is acceptable in pilot volumes.

**Recommendation.** Option 2.

**Rationale.** PD-004 requires the partitioned table from the start; it does not require the retention purge on day one. Moving retention to M3 removes M1 scope with no adopter impact during pilot.

**Consequences.** RE-F-16 (partitioned table, scheduled partition creation) in M1; RE-F-31 (retention) in M3. The previous M3 roadmap line is replaced.

**Follow-ups.** Architect: `RLE-*` statements for partition creation (M1) and retention (M3); `TR-OPS-04` wording.

### PD-021 Conflict: exit criteria omit Musts tagged for the milestone

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The previous M1 exit lists requirement groups but omits Musts tagged M1 or untagged: `TR-ASY-04`, `TR-PERF-06`, `TR-IDEM-05`, `TR-OPS-05`, `TR-OBS-06`, `TR-SEC-06`, `TR-QA-02`. The M2 exit says "every must" but names only some. Which list gates? |
| Decision | Pending product owner |

**Options considered**

1. Exit criteria are "every Must that applies to the modules in the milestone, with its milestone tag reached", and the roadmap lists them explicitly per milestone. Consequence: matches [requirements.md](requirements.md); the list is long but unambiguous.
2. Keep the short group list. Consequence: shorter, but a reader can believe M1 ships without the cache bound, idempotency mismatch handling or pooler support.

**Recommendation.** Option 1.

**Rationale.** [requirements.md](requirements.md) already says a Must gates every release from its milestone; the roadmap must not say less.

**Consequences.** The roadmap lists every gating Must per milestone, including `TR-ASY-04`, `TR-PERF-06`, `TR-IDEM-05` and `TR-OPS-05` in M1. Features RE-F-09, RE-F-10, RE-F-20 and X-F-08 are M1 musts.

**Follow-ups.** Architect: `RLE-010` traces `TR-ASY-04` and asserts the 5 s bound behind a pooler; new `RLE-*` statement for `TR-PERF-06`.

### PD-022 Conflict: simulation report in Cockpit 0.1 approvals

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The wiki Cockpit page says the 0.1 approver reads "diff and simulation report"; the roadmap and `CKP-002` put reports attached to publication requests in M3. Which is right? |
| Decision | Pending product owner |

**Options considered**

1. M3 for attached reports; in 0.1 the approver reads the diff and runs a sample simulation in the cockpit. Consequence: wiki Cockpit row changes.
2. Attach the sample simulation result to the request in M1. Consequence: new storage and API on the publication request in M1.

**Recommendation.** Option 1.

**Rationale.** An approver with `rules:simulate` can run the same samples; attaching reports needs storage and a contract that M1 does not have time for.

**Consequences.** CK-F-20 stays M3. Wiki Cockpit Approvals row is corrected.

**Follow-ups.** Documentation: correct the wiki Cockpit Approvals row.

### PD-023 Conflict: milestone of the instance migration screen

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The wiki Cockpit page lists "Instance migration" under Cockpit 0.2; the roadmap ships the cockpit migration screen in M3 with `TR-MIG-03` (Should, M3). Which is right? |
| Decision | Pending product owner |

**Options considered**

1. M3: migration is by REST in M2; the screen arrives with renamed-step mapping in M3. Consequence: wiki Cockpit row moves.
2. M2: a screen for dry run and execution in Cockpit 0.2. Consequence: more M2 cockpit scope, on a feature that is itself a cut candidate (PD-017).

**Recommendation.** Option 1.

**Rationale.** The screen is most useful with explicit mapping, which is M3; M2 is the most loaded milestone.

**Consequences.** CK-F-19 in M3.

**Follow-ups.** Documentation: mark the wiki Cockpit migration row as M3.

### PD-024 Conflict: compensation runs view and SLA breach list

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The wiki Cockpit page lists "Compensation runs" and "SLA breaches" in Cockpit 0.2; neither is on the previous roadmap or in the cockpit SPEC. Are they in scope? |
| Decision | Pending product owner |

**Options considered**

1. In Cockpit 0.2, kept thin: compensation runs shown as entries in the instance history timeline (CK-F-12), and an SLA breach list as a filter of the task queue on overdue tasks (CK-F-17). Consequence: operators see both with little new UI; needs two `CKP-*` statements.
2. Separate screens in 0.2. Consequence: more scope in M2.
3. Remove both from the wiki until after 1.0. Consequence: operators find failed compensations and breaches only through incidents.

**Recommendation.** Option 1.

**Rationale.** Failed compensations already raise incidents (`TR-COMP-02`); what operators lack is the order of what ran, which the timeline shows. Overdue tasks are a filter on data the queue already has.

**Consequences.** CK-F-16 (compensation runs in the timeline) and CK-F-17 (SLA breach filter) in M2, both spec gaps.

**Follow-ups.** Architect: `CKP-*` statements for both.

### PD-025 Conflict: Process Engine version label

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The wiki Concepts page says it describes "the 0.2 target (Process Engine terms)"; the roadmap and Release Notes ship Process Engine 0.1 in M2 with Cockpit 0.2. Which label is right? |
| Decision | Pending product owner |

**Options considered**

1. Process Engine 0.1 in M2; Cockpit 0.2 in M2. Module versions are independent. Consequence: Concepts status note corrected.
2. Align all modules on one version number. Consequence: Process Engine would start at 0.2 with no 0.1, which confuses release notes and tags.

**Recommendation.** Option 1.

**Rationale.** Each module is tagged separately (`process-engine/v0.1.0`); Release Notes already say so.

**Consequences.** None to scope.

**Follow-ups.** Documentation: correct the status note on the wiki Concepts page.

### PD-026 Conflict: Should requirements used as release gates

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The previous M2 exit lists `TR-PERF-04` (a Should) as a gate, and `CKP-009` and `CKP-010` (M1) trace to `TR-UI-03` and `TR-UI-04` (Shoulds) while SPEC statements with a reached milestone gate under `TR-QA-01`. Do these gate? |
| Decision | Pending product owner |

**Options considered**

1. `TR-PERF-04` is measured and reported in M2, not a gate. Accessibility and the two languages stay gating through `CKP-009` and `CKP-010`, and `TR-UI-03` and `TR-UI-04` become "Must (M1)" so the requirement matches the SPEC. Consequence: one consistent rule; accessibility is cheap to hold on a new UI and expensive to retrofit.
2. Everything traced to a Should is report-only, including `CKP-009` and `CKP-010`. Consequence: consistent; accessibility may regress.
3. Keep as is. Consequence: two documents disagree on what blocks a release.

**Recommendation.** Option 1.

**Rationale.** Throughput depends on the adopter's database and is a target; accessibility and translation of a new UI are within the maintainers' control.

**Consequences.** M2 exit reports `TR-PERF-04`. CK-F-08 and CK-F-09 are M1 gates.

**Follow-ups.** Architect: retag `TR-UI-03` and `TR-UI-04`.

### PD-027 Conflict: decision explanation in M1

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The roadmap ships decision log "explain" in M1 and defers "explain templates" to M3; `CKP-005` (M1) renders `/explain` text; no `RLE-*` statement specifies an explanation, and `TR-OBS-05` is a Should. What ships in M1? |
| Decision | Pending product owner |

**Options considered**

1. M1 ships a generated explanation built from the matched row's conditions and the recorded inputs (masked inputs stay masked), from the log alone; M3 adds author-written templates. Consequence: auditors get a readable answer in M1; needs an `RLE-*` statement.
2. M1 shows only matched row ids and inputs; any explanation text in M3. Consequence: smaller M1; `CKP-005` must drop the explanation text.

**Recommendation.** Option 1.

**Rationale.** Reconstructing a decision without SQL is the auditor's job in the PRD; a generated text from the matched conditions is small and deterministic.

**Consequences.** RE-F-18 (generated explanation) M1, spec gap; RE-F-19 (templates) M3.

**Follow-ups.** Architect: `RLE-*` statement for the explain endpoint; consider retagging `TR-OBS-05` "Must (M1)".

### PD-028 Conflict: agent-chosen steps in M3 or after 1.0

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The previous roadmap ships "ad-hoc step selection inside a bounded block" in M3 and lists "multi-agent planning with ad-hoc step selection" after 1.0. Is agent-chosen step selection in 1.0? |
| Decision | Pending product owner |

**Options considered**

1. Move agent-chosen steps (PE-F-39) to after 1.0, with multi-agent planning; M3 agentic depth is the MCP adapter, reference adapters, bounded agent memory and the offline runner. Consequence: M3 has room for the API freeze; the 1.0 agent story is "guarded, evaluated steps", not planning.
2. Keep single-agent selection among declared steps in M3. Consequence: a more ambitious 1.0; a new execution model (dynamic step order) in the milestone that freezes the API.
3. Pull it into M2. Consequence: M2 cannot absorb it.

**Recommendation.** Option 1.

**Rationale.** M3 holds 19 features, 17 of them without SPEC statements, plus an API freeze in eight weeks. A dynamic step order is the riskiest of them and is not needed for the M3 exit (two adopters, one using `agentTask`). The offline runner directly supports the guardrail story.

**Consequences.** PE-F-39 moves to "later"; [`prd-process-engine.md`](prd-process-engine.md) section 8 follows the decision once made.

**Follow-ups.** Product manager: update the PRD after the decision.

---

## Pending: acceptance of architecture decisions

### PD-029 Accept ADR-0008 (async wait-state for external steps)

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Accept [ADR-0008](../adr/0008-async-wait-state-for-external-steps.md), currently "proposed"? |
| Decision | Pending product owner (an architecture decision; the product owner accepts it) |

**Options considered**

1. Accept before M1 ends. Consequence: M2 starts with a settled protocol; `PRE-009` and `PRE-017` are buildable; differentiator 3 and kill assumption K1 rest on an accepted decision.
2. Leave proposed until M2 starts. Consequence: no cost now; risk that M2 opens with a protocol debate.

**Recommendation.** Option 1, accept now.

**Rationale.** The lease rule (timeout plus margin) is already reflected in `PRE-017` and the PRD; nothing in the ADR is in dispute.

**Consequences.** No scope change. Features PE-F-16 and PE-F-17.

**Follow-ups.** Architect: set the status to accepted with the date.

### PD-030 Accept ADR-0009 (cache coherence)

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Accept [ADR-0009](../adr/0009-cache-coherence-listen-notify-and-epoch.md), currently "proposed"? |
| Decision | Pending product owner (an architecture decision; the product owner accepts it) |

**Options considered**

1. Accept before M1 code starts. Consequence: M1 builds the cache to a settled design; `TR-ASY-04` and `TR-PERF-06` have one implementation path.
2. Defer to M1 exit. Consequence: the M1 cache is built against a proposed design.

**Recommendation.** Option 1, accept now (M0).

**Rationale.** The cache is an M1 must (`TR-ASY-04`, `TR-PERF-06`) and kill assumption K2 depends on it.

**Consequences.** No scope change. Features RE-F-09 and RE-F-10.

**Follow-ups.** Architect: set the status to accepted with the date.

---

## Pending: dates

### PD-031 Milestone dates and pre-approved cuts

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Can M0 (4 Dec 2026), M1 (15 Jan 2027), M2 (12 Mar 2027) and M3 (7 May 2027) hold with the scope in the [feature map](feature-map.md)? |
| Decision | Pending product owner |

**Analysis.**

- M0 (2 weeks) holds if the pending decisions in this log are decided by 2 Dec 2026. Three design questions gate M1 code: the Jackson major (ADR-0011), the Maven `groupId`, and standalone cockpit host switching.
- M1 (6 calendar weeks) contains the year-end holidays, which leaves about 5 working weeks. Since the previous roadmap it has absorbed the partitioned decision log (PD-004), a dual Spring Boot CI leg (PD-007) and four Musts made explicit (PD-021): 46 features, 19 of them spec gaps. It holds only with pre-approved cuts.
- M2 (8 calendar weeks) contains the Lunar New Year holidays (the first half of February 2027), which leaves about 7 working weeks for the largest milestone (44 features, 20 of them spec gaps). It does not hold as scoped; it holds with the cuts below.
- M3 (8 weeks) holds 19 features (17 spec gaps) plus the API freeze, and its exit depends on two external adopters, which is outside the maintainers' control.

**Options considered**

1. Keep all four dates and pre-approve a cut list applied at fixed checkpoints (chosen if accepted). Consequence: dates stay; scope shrinks to the outcome's critical path when a checkpoint fails.
2. Move M2 to 26 Mar 2027 and M3 to 21 May 2027 (two weeks each) and keep full scope. Consequence: full scope; later adoption, and the M3 adopter window shrinks before mid-year.
3. Keep dates and full scope. Consequence: likely silent slippage.

**Pre-approved cut list (option 1)**

| Milestone | Checkpoint | Trigger | Cut, in this order |
|---|---|---|---|
| M1 | 8 Jan 2027 (end of week 5; week 4 is the year-end break) | `examples/rule-engine-only` and the Cockpit 0.1 approval flow do not run end to end | 1. Async decision log mode (RE-F-15) to M2. 2. Batch evaluate (RE-F-26) to M2; version pinning stays. 3. Decision log list filters beyond rule set and period to M2 |
| M2 | End of week 5 (19 Feb 2027) | Fewer than 9 of 17 `PRE-*` statements green | 1. Batched migration execution (PE-F-30) to M3 (PD-017). 2. Business calendar editor screen (CK-F-18) to M3; the SPI and built-in calendar stay. 3. `callProcess` (PE-F-12) to M3. 4. Replay simulation (RE-F-22, CK-F-11) to M3 |
| M3 | End of week 4 (9 Apr 2027) | API freeze candidate not ready, or fewer than two named pilot adopters | 1. BPMN subset import (PE-F-41) after 1.0, unless kill assumption K5 fired. 2. DMN import (RE-F-30) after 1.0. 3. What-if comparison (RE-F-24) after 1.0. 4. Documentation site generator (X-F-17) after 1.0 |

Never cut, in any milestone: tenant isolation, idempotency, maker-checker, append-only audit, masking, the async wait-state protocol and the `agentTask` output gate.

**Recommendation.** Option 1, plus PD-028 (agent-chosen steps after 1.0), which is needed for M3 to hold.

**Rationale.** No adopter depends on a date yet, but a date that moves every milestone teaches readers to ignore the roadmap. The cuts are features off the outcome's critical path.

**Consequences.** The roadmap shows the dates unchanged and the cut list as conditional deferrals. A cut, when triggered, is recorded as a new entry.

**Follow-ups.** Maintainers: report checkpoint status on the dates above. Product manager: record any triggered cut.
