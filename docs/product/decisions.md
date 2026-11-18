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
| [PD-032](#pd-032-diagrams-and-a-visual-modeller-in-scope) | Diagrams and a visual modeller in scope | Pending product owner |
| [PD-033](#pd-033-maker-checker-for-process-definitions) | Maker-checker for process definitions | Pending product owner |
| [PD-034](#pd-034-process-notation-own-json-canonical-bpmn-subset-for-import-and-export) | Process notation: own JSON canonical, BPMN subset for import and export | Pending product owner |
| [PD-035](#pd-035-multi-host-cockpit-model) | Multi-host cockpit model | Pending product owner |
| [PD-036](#pd-036-source-of-truth-for-process-definitions) | Source of truth for process definitions | Pending product owner |
| [PD-037](#pd-037-tenant-switching-for-multi-tenant-operators) | Tenant switching for multi-tenant operators | Pending product owner |
| [PD-038](#pd-038-analytics-scope) | Analytics scope | Pending product owner |
| [PD-039](#pd-039-principle-9-wording) | Principle 9 wording | Pending product owner |
| [PD-040](#pd-040-principle-6-extended-to-process-definitions) | Principle 6 extended to process definitions | Pending product owner |
| [PD-041](#pd-041-conflict-vision-non-goal-diagram-modeller) | Conflict: vision non-goal "diagram modeller" | Pending product owner |
| [PD-042](#pd-042-milestone-dates-if-the-cockpit-direction-is-accepted) | Milestone dates if the cockpit direction is accepted | Pending product owner |

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

---

## Pending: cockpit direction (Camunda-like cockpit)

The product owner asked for a cockpit that works like Camunda's tools (model, operate, work tasks, see where processes slow down) while the engines stay embedded libraries. The architect's review "Embedded engines with a Camunda-like cockpit" (18 Nov 2026) answers that this fits the principles: all replicas of one service share one PostgreSQL database, so each host's REST layer already gives the full picture for that service, and a static cockpit that calls several hosts covers the view across services. The review found three gaps in the current plan: no diagrams anywhere, no maker-checker for process definitions, and no engine read APIs built for a cockpit. It asks for seven decisions before M2 code starts (PD-032 to PD-038). PD-039 to PD-041 record the principle and vision wording those decisions would change. PD-042 records the effect on dates.

Until these entries are decided, the roadmap and the feature map keep the scope before them. The proposed features are listed in the [feature map](feature-map.md#proposed-camunda-like-cockpit-pending-pd-032-to-pd-042) with status "proposed", and the adjusted milestones are in the [roadmap scenario](roadmap.md#scenario-camunda-like-cockpit).

What "Camunda-like" includes and excludes in all ten entries:

| Camunda tool | Included if accepted | Not included |
|---|---|---|
| Modeler | A diagram for every definition (M2); a browser modeller limited to our step types and to steps the host has registered (M3) | Full BPMN 2.0 symbols, a desktop modeller, scripts or expressions typed into the canvas |
| Operate | Definition and instance diagrams with token overlay, node history, live counts per node (M2); migration shown as two diagrams (M3) | Moving a token by hand inside a running instance (deferred; needs its own maker-checker if ever built) |
| Tasklist | Task query by user, role and group, task forms, one inbox across hosts (M2) | A form designer |
| Optimize | Counts, incidents and p50 and p95 durations per node from an in-host rollup (live M2, history M3) | Ad-hoc reports, process mining across services, BI; hosts send engine events to their own warehouse |
| Console and Identity | Host registry, `/meta` and `/me`, tenant switcher (M1 and M2) | A user store, a login of our own, a control plane |

### PD-032 Diagrams and a visual modeller in scope

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Is a visual process modeller in scope before 1.0, and in which form? Today the vision and both PRDs list "a graphical modeller" as a non-goal, definitions carry no layout, and the Cockpit 0.2 instance view is a timeline |
| Raised by | Product owner request; architect's review, decision 1 |
| Decision | Pending product owner |

**Architect's recommendation.** Yes. Read-only diagrams with a token overlay in M2; a modeller in M3; full BPMN 2.0 stays out.

**Architect's reasons.**

- Operators compare any process tool with Camunda Operate, where the instance is a diagram. A timeline answers "what happened", not "where is it now and where can it go".
- Principle 4 (no code in definitions) makes a browser modeller safe: a business user can only arrange steps the host has registered.
- The diagram needs no engine server. Layout is stored apart from the definition hash, so moving a box needs no approval; if no layout exists, the cockpit lays the graph out automatically, so definitions kept in Git still show as diagrams.
- bpmn-js and diagram-js fail `TR-LIC-01` (the bpmn.io licence requires a visible watermark). React Flow (MIT) with dagre (MIT) passes, with maxGraph (Apache 2.0) as fallback. elkjs (EPL-2.0) and JointJS (MPL-2.0) also fail `TR-LIC-01`.

**Options considered**

1. No diagrams before 1.0 (current plan). Consequence: no extra work; the instance view stays a timeline; the product owner's request is not met; kill assumption K5 (evaluators accept definitions without a modeller) carries the whole bet.
2. Read-only diagrams in M2 (definition view and instance view with token overlay and node history), modeller in M3. Consequence: about 3 extra weeks in M2 (architect's estimate) and an unsized amount in M3; the vision, both PRDs and principle 9 change (PD-039, PD-041).
3. Read-only diagrams in M2, modeller after 1.0. Consequence: the M2 cost of option 2 with no M3 cost; process changes stay a developer task in Git until 1.1.
4. Diagrams and modeller both in M2. Consequence: M2 cannot absorb it; it already needs cuts to hold (PD-031).

**Recommendation (product manager).** Option 2, adjusted: the modeller canvas (CK-F-32) is the first M3 cut candidate, with 1.1 as the fallback, while every engine API it needs (layout storage PE-F-44, step catalogue PE-F-52, graph diff PE-F-46) lands before the 1.0 API freeze. Read-only diagrams in M2 are not a cut candidate.

**Rationale.**

- Personas. The Operator and Auditor (cockpit PRD section 5) get the most from the read-only diagram: "where is this instance and why is it stuck" is their daily question, and the token overlay answers it in one screen. The Author persona for processes does not exist yet; who changes a process in a bank today is unknown (new kill assumption K16 in the roadmap scenario). That makes the diagram certain value and the modeller a bet.
- Positioning against Camunda. We will not out-draw a modeller with ten years of polish. We can win on what an embedded engine with governance does that Camunda's modeller does not: the canvas offers only steps the host has registered, validation runs on save, and every change goes through a second approver (PD-033). The modeller should be sold as "governed change to a running service", not as a drawing tool. A read-only diagram removes the most visible gap in an evaluation ("no diagrams") at the lowest cost.
- Scope and dates. M2 is the largest milestone (44 features, about seven working weeks). A modeller in M2 would sink it. In M3 the modeller competes with the API freeze and the adopter exit; keeping the canvas cuttable, but the APIs fixed, protects 1.0 without closing the door.
- The architect's two-day React Flow spike (render `kyc-onboarding` with automatic layout and a token overlay) should run in M0 and feed the K5 architect conversations in M1 with real screenshots.

**Consequences if accepted.** New features PE-F-44, PE-F-47, PE-F-48, CK-F-24, CK-F-25, CK-F-26, CK-F-32, CK-F-33 (see the feature map). CK-F-12 keeps its timeline and gains the diagram through CK-F-25. Vision, process PRD and cockpit PRD non-goals change (PD-041). Principle 9 changes (PD-039). ADR-0013 (notation, layout storage, diagram library, bpmn-js licence finding) is needed before M2 code. React Flow and dagre each need a one-paragraph dependency justification and a licence re-check at adoption.

**Follow-ups.** Architect: ADR-0013 and the M0 spike. Product manager: CKP-level journeys for the diagram views in the cockpit PRD after the decision; add K15 to K18 from the roadmap scenario to the kill assumptions once decided. QA: licence gate test that rejects bpmn-js.

### PD-033 Maker-checker for process definitions

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Do process definitions get the same governed lifecycle as rule sets (draft, request, approve by another identity, effective date, retire)? Today they are deployed directly with `processes:deploy` |
| Raised by | Architect's review, decision 2 |
| Decision | Pending product owner |

**Architect's recommendation.** Yes. Engine API in M2, before the contract freezes; cockpit screens in M3.

**Architect's reasons.**

- Direct deploy breaks principle 6 for processes. The architect treats this as a blocking gap, independent of the cockpit.
- Changing processes from the cockpit is not safe without it.
- Lifecycle: `DRAFT -> PENDING_APPROVAL -> ACTIVE -> RETIRED`, with `:validate`, `:request-approval`, `:approve` and `:reject`, a check that the approver is a different person, an effective date, and a graph diff (nodes and edges added, removed or changed) for the approver. New permissions `processes:author` and `processes:approve` amend ADR-0010. New events `processes.definition.publish-requested.v1`, `.approved.v1`, `.rejected.v1`, `.retired.v1`.

**Options considered**

1. Keep direct deploy; governance of process changes stays with the host's release process. Consequence: no extra work; differentiator 2 stays rules-only; a business user can never change a process from the cockpit; the deploy contract freezes at 1.0 without a lifecycle.
2. Lifecycle, permissions, events and graph diff in the engine API in M2; cockpit approval screens in M3. Consequence: part of the 3 weeks in PD-032's estimate; M2 approvals happen through REST or the host's tooling until M3.
3. Lifecycle and screens both in M2. Consequence: more M2 UI work; M2 cannot absorb it.
4. Lifecycle in M3 with the screens. Consequence: the deploy API changes shape after M2 adopters start using it, in the milestone that freezes the API.

**Recommendation (product manager).** Option 2, agree, with one addition: the `dev` profile may auto-approve process definitions, exactly as the rule engine starter allows maker-checker to be relaxed only in `dev`. Outside `dev` the starter refuses to start with process approval turned off.

**Rationale.**

- Personas. The Approver's value proposition ("approve only what they understand", "self-approval impossible in UI and API") is written for rules; leaving processes out means the same compliance officer gets a second approver for a fee table but not for the approval flow that decides who signs a loan. That inconsistency is the first thing a bank's risk function would find.
- Positioning. Engine-enforced maker-checker is differentiator 2. Camunda leaves approval of model changes to the customer's deployment pipeline. Extending it to processes widens the gap at moderate cost.
- Scope and dates. The lifecycle changes the deploy contract and the definition table (`status` gains `DRAFT` and `PENDING_APPROVAL`). Doing it in M2 costs less than changing a contract adopters already call. The graph diff is a pure function over two definitions and is what makes an API approval meaningful, so it stays in M2 with the lifecycle. The screens can wait for M3 because pilot approvers in M2 are few and technical.
- The `dev` exception protects the 30-minute Getting Started measure (X-F-16): a newcomer should not need two identities to run a first process.

**Consequences if accepted.** New features PE-F-45 (lifecycle, M2), PE-F-46 (graph diff, M2), CK-F-31 (approval screens, M3). PE-F-01 and PE-F-28 change contract: deploy creates a draft. Principle 6 is extended (PD-040). ADR-0012 and an ADR-0010 amendment are needed before M2 code. `TR-SEC-04` applies to process definitions as well as rule sets.

**Follow-ups.** Architect: ADR-0012, ADR-0010 amendment, process OpenAPI changes, `PRE-*` statements for the lifecycle and the self-approval check. Product manager: update the process PRD and requirements after the decision. Security reviewer: confirm the `dev`-only relaxation rule.

### PD-034 Process notation: own JSON canonical, BPMN subset for import and export

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | What is the source notation for process definitions once diagrams exist, and what is BPMN's role? |
| Raised by | Architect's review, decision 3 |
| Decision | Pending product owner |

**Architect's recommendation.** Our own definition JSON stays canonical, with an optional `diagram` part (node positions, edge waypoints, lanes). A BPMN subset is for import and export only, converted in Java with the JDK's StAX parser (no new dependency) and tested in CI against the schema; anything outside the subset is rejected and named in the error. Take PE-F-41 (BPMN subset import) off the cut list.

**Architect's reasons.** The JSON already has our step types (`agentTask`, `decision`, `saga` and the async wait-state), which BPMN has no native symbols for. Making BPMN canonical would push symbols at users that the engine does not support. Interchange in Java keeps the conversion testable.

**Options considered**

1. Own JSON canonical; BPMN subset import and export; PE-F-41 protected. Consequence: one notation to validate; Camunda users can bring and take models.
2. BPMN 2.0 XML canonical, our step types as extension elements. Consequence: familiar files; full-BPMN expectations; every unsupported element becomes a support question; contradicts principle 9.
3. Own JSON only, no BPMN interchange. Consequence: least work; no migration path from Camunda or Flowable models; the "lock-in" objection has no answer.

**Recommendation (product manager).** Option 1, agree. Import (PE-F-41) leaves the cut list; export (PE-F-53) is new in M3 and is a cut candidate after the modeller canvas, because export answers an objection while import opens a switching path.

**Rationale.**

- Positioning. The best-fit adopter is a Java team that already embeds an engine or hand-codes status columns. Teams on embedded Camunda 7 or Flowable hold BPMN files; import is the cheapest way to let them try us with their own process. That is a switching path, not a compatibility checkbox, so it should not be the first thing cut.
- Personas. The host developer (process PRD S1) evaluates with an existing model; the agent step designer (S3) needs `agentTask` with its schema and guardrail, which only our JSON carries.
- Scope. Keeping the subset documented and rejecting the rest by name keeps support bounded, which is the point of principle 9.

**Consequences if accepted.** PE-F-41 is removed from the PD-031 M3 cut list (PD-031 is otherwise unchanged). New feature PE-F-53 (export, M3, cut candidate). The vision's "never a full BPMN 2.0 implementation" stays.

**Follow-ups.** Architect: the subset definition and error codes in ADR-0013. Product manager: update PD-031's cut list wording once decided.

### PD-035 Multi-host cockpit model

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | How does one cockpit show work from several host services when there is no central engine server? This also answers the open M1 gate "standalone cockpit host switching" |
| Raised by | Architect's review, decision 4 |
| Decision | Pending product owner |

**Architect's recommendation.** Option B (standalone cockpit federating hosts) as the product, with A (cockpit embedded in each host) still supported. D documented as a reference configuration for the bank's existing API gateway, not built. C after 1.0, and only if a customer pays for fleet analytics. Decide now, because host switching gates M1.

**Architect's reasons.** B keeps ADR-0005 (no server, no state): the browser calls each host and merges tasks and incidents. Load for 10 hosts and 500 operators refreshing every 30 seconds is about 17 requests per second per host, mostly answered by `ETag` and `304`; a merged inbox loads at the slowest host's p99, under about 300 ms with a 150 ms host p99. Above about 15 hosts or 2,000 active operators, an aggregator starts to pay for itself.

**Options considered**

| Option | How it works | Consequence |
|---|---|---|
| A. Cockpit inside each host | The starter serves the UI at `/bpm/cockpit` | Works today; one cockpit per service; operators keep one bookmark per service; no shared inbox |
| B. Standalone cockpit federating hosts | Static bundle with a host list; the browser calls each host and merges tasks and incidents | No server, no state; needs CORS on each host and one IdP whose tokens every host accepts; merging works up to about 10 to 15 hosts; paging across hosts is approximate |
| C. Read-side aggregator fed by engine events | Separate service with its own database | Copies business data and PII into one more store; eventual consistency; its own identity, tenancy and operations; a product's worth of work |
| D. Control plane (registry, token exchange, proxy) | A small gateway in front of the hosts | Solves CORS and discovery, but is a server with identity; breaks ADR-0005 if we build it; most banks already run a gateway that can do this |

**Recommendation (product manager).** Agree: B, with A supported, D documented, C recorded as "rejected for now" and reopened only with a paying customer. Adjusted sequencing: M1 ships only the host registry with one host at a time, plus `/meta` and `/me` (CK-F-23, X-F-22); the merged inbox and incident list (CK-F-28, CK-F-29) ship in M2 with the process screens.

**Rationale.**

- Personas. The Operator persona works queues across several services in any bank that runs more than one process-bearing service. One bookmark per service (option A only) is the main reason operators would keep a vendor tool. B gives them one inbox without adding a server.
- Positioning. "No engine cluster, no separate identity" is differentiator 1. Option C or D built by us would make it false. B keeps the claim and still answers "one view".
- Scope and dates. M1's outcome is about rules, not processes; a merged inbox has nothing to merge until M2. Putting only the registry and the two small endpoints in M1 settles the gate question with a few days of work (an assumption to be sized by the architect) instead of a week.
- Limits stated plainly. Federation above about 15 hosts or 2,000 active operators is outside what B is designed for. The scenario adds kill assumption K17 to test pilot topology early.

**Consequences if accepted.** New features CK-F-23 (M1), X-F-22 (M1), CK-F-28 and CK-F-29 (M2). ADR-0014 amends ADR-0005 and `TR-UI-01` (host registry, sign-in across hosts with OIDC PKCE as a public client, version skew: the cockpit supports the current and previous engine minor version and turns off screens a host cannot serve). `oidc-client-ts` (Apache 2.0) needs a dependency justification. CK-F-01's open design question is closed by this decision.

**Follow-ups.** Architect: ADR-0014 and the `/meta` and `/me` contracts before M1 code; size the M1 part. Security reviewer: token audience per host, token lifetime, CORS off by default, ASVS L2 review of the federated mode. Documentation: reference gateway configuration for option D.

### PD-036 Source of truth for process definitions

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Banks promote changes from development to UAT to production. If definitions can be changed both in Git and in the production cockpit, which one is the truth? |
| Raised by | Architect's review, decision 5 |
| Decision | Pending product owner |

**Architect's recommendation.** Both paths are allowed. A Git import arrives as a `DRAFT`; only an approved draft becomes active. The content hash shows whether production matches Git.

**Options considered**

1. Git only; the cockpit is read-only for definitions. Consequence: one truth; the modeller (PD-032) has no purpose in production.
2. Cockpit only; Git receives exports. Consequence: business users own change; breaks the GitOps flow most host teams already run for rules (RE-F-25).
3. Both, with every import arriving as a draft and the content hash shown. Consequence: one approval gate for both paths; drift is visible, not prevented.

**Recommendation (product manager).** Option 3, agree, with a host setting that turns off draft creation from the cockpit per environment (default on). A bank that promotes only through Git sets it off in production and still gets approval and the hash comparison there.

**Rationale.**

- Personas. Host developers (S1) keep their pipeline; process owners get the modeller where the bank allows it. The approval gate is the same for both, so the Approver persona sees one queue.
- Positioning. This mirrors what the rule engine already does for rule sets, so the governance story stays one story.
- Scope. The setting is one flag checked by the engine, not a new feature. A bank's change-management policy differs per environment; a fixed product rule would block some adopters.
- Drift. Showing the hash is enough for 1.0. Blocking divergence would need a link to the bank's Git server, which a library should not hold.

**Consequences if accepted.** Part of PE-F-45 (import arrives as draft, content hash exposed, per-environment setting). Approvers in each environment approve the promoted draft; this is stated in the process PRD.

**Follow-ups.** Architect: the setting and the hash in ADR-0012. Product manager: the promotion journey in the process PRD.

### PD-037 Tenant switching for multi-tenant operators

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Today the tenant comes only from the token. How does an operator who serves several tenants work in the cockpit? |
| Raised by | Architect's review, decision 6 |
| Decision | Pending product owner |

**Architect's recommendation.** The cockpit may send `X-Tenant-Id`; the host accepts it only if that tenant is in the token's list of allowed tenants. No combined view across tenants; each screen shows one tenant.

**Options considered**

1. Token only (current). Consequence: an operator signs in again per tenant; no change to the tenancy model.
2. Header accepted only within the token's allowed tenants; one tenant per screen. Consequence: one sign-in; the host checks every request; tenant isolation (`TR-TEN-01`) unchanged.
3. Combined views across tenants. Consequence: queries without a single tenant; weakens the tenant-leading index rule and the isolation tests.

**Recommendation (product manager).** Option 2, agree, scheduled for M2 (X-F-23), not M1.

**Rationale.**

- Personas. Shared-service operators in a banking group serve several legal entities; signing in again per tenant is friction that pushes them to SQL. Rule authors in M1 usually work for one tenant.
- Positioning. Tenant isolation is a must-be quality in a regulated service. A cross-tenant view is the kind of feature that fails a bank's security review; we should say no to it plainly.
- Scope and dates. The change is additive to the tenant resolver, so it can wait for M2 without breaking an M1 contract, and M1 has no slack.

**Consequences if accepted.** New feature X-F-23 (M2): host check plus the cockpit tenant switcher. The allowed-tenants claim name is documented for host IdP configuration.

**Follow-ups.** Architect: claim name and resolver rule in ADR-0014. Security reviewer: tests that a header outside the allowed list is rejected and logged.

### PD-038 Analytics scope

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | How much of Camunda Optimize does the product provide? |
| Raised by | Architect's review, decision 7 |
| Decision | Pending product owner |

**Architect's recommendation.** In-host rollup only: live counts per node, incidents per node, p50 and p95 duration per node from an hourly rollup table filled by the existing scheduler thread with a watermark, SLA breaches. Never `GROUP BY` over history online, and never update counters inside the step transaction. An aggregator after 1.0, and only with a paying customer. Hosts send engine events to their own data warehouse for anything else; the event schemas are the integration point.

**Options considered**

1. No analytics; metrics stay in Micrometer. Consequence: no work; no heatmap on the diagram.
2. In-host rollup (live counts in M2, hourly rollup in M3). Consequence: two engine read models (PE-F-48, PE-F-49) and one table; no copy of data outside the host.
3. A fleet analytics service (option C of PD-035). Consequence: a separate product.

**Recommendation (product manager).** Option 2, agree. Live counts per node in M2 (PE-F-48, CK-F-26, the cockpit part a cut candidate); historical rollup and heatmap in M3 (PE-F-49, CK-F-27, cut candidate). PD-016 keeps its recommendation: no landing-page pulse strip in 0.x; the counts appear on the diagram and in queue headers, where the operator acts.

**Rationale.**

- Personas. The Operator needs "where are instances piling up now"; that is the live count. Process owners want "which step is slow over a month"; that is the rollup, valuable but not needed for the M2 outcome.
- Positioning. Saying no to Optimize keeps the claim "no data copied out of your service" true and avoids a comparison we would lose. The event schemas give BI teams a documented path.
- Scope. The live count needs `definition_id` on `pre_token` and one index; adding the column before the M2 contract settles costs less than adding it later.

**Consequences if accepted.** New features PE-F-48, PE-F-49, CK-F-26, CK-F-27. ADR-0015 (operational read models and query limits) and X-F-24 (bounded queries, CI query-plan check) apply. PD-016 is unchanged; if the product owner wants the pulse strip, PD-016 is the place to change it.

**Follow-ups.** Architect: ADR-0015 with DDL, indexes, rollup job and query math. Documentation: event schemas page as the warehouse integration point.

### PD-039 Principle 9 wording

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Principle 9 reads "Standards as vocabulary, not compliance. BPMN and DMN words, a documented subset of their semantics", which readers take as "no diagrams". Change it if PD-032 and PD-034 are accepted? |
| Raised by | Architect's review, section 1 |
| Decision | Pending product owner (depends on PD-032 and PD-034) |

**Options considered**

1. Keep the wording. Consequence: contradicts PD-032 if it is accepted.
2. Reword to: "Our own graph notation is the source of truth and always has a diagram; a BPMN subset is for import and export. DMN words, a documented subset of their semantics." Consequence: the principle states what a user gets and keeps the limit on BPMN scope.

**Recommendation (product manager).** Option 2 if PD-032 and PD-034 are accepted; option 1 otherwise. The "Therefore" column stays: "The engines stay small. What is supported is exactly what is documented."

**Consequences.** Wiki page Principles row 9, the vision table "What the engine will never be" (row on BPMN, which keeps its meaning), wiki Concepts and FAQ ("Does it read DMN or BPMN files?").

**Follow-ups.** Documentation: update the wiki in the same change that records the decision. Architect: reflect it in ADR-0013.

### PD-040 Principle 6 extended to process definitions

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Principle 6 ("Governance built in. Draft, simulate, request, approve by another identity, effective date, retire. The engine enforces each step.") is applied only to rule sets today. State that it covers process definitions? |
| Raised by | Architect's review, section 1 |
| Decision | Pending product owner (follows PD-033) |

**Options considered**

1. Keep the text and its rules-only practice. Consequence: matches today's plan; conflicts with PD-033 if accepted.
2. Extend the text: "Governance built in, for rule sets and process definitions. Draft, validate or simulate, request, approve by another identity, effective date, retire. The engine enforces each step." Consequence: the principle matches PD-033; "simulate" for processes means validation and graph diff until a dry run with test variables exists.

**Recommendation (product manager).** Option 2 if PD-033 is accepted. Layout changes (PE-F-44) are outside the principle because they never change behaviour, as the architect proposes; the wording should say so in the wiki explanation, not in the principle line.

**Consequences.** Wiki Principles row 6, vision differentiator 2 ("Rule changes by business users with maker-checker" becomes "Rule and process changes"), cockpit PRD Approver persona permissions.

**Follow-ups.** Documentation: wiki update with the decision. Product manager: vision and PRD updates after the decision.

### PD-041 Conflict: vision non-goal "diagram modeller"

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The vision says the product is not for "teams that need full BPMN 2.0, DMN FEEL, CMMN or a diagram modeller"; the process PRD lists "a graphical modeller" and the cockpit PRD "a BPMN diagram modeller" as non-goals. PD-032 would make these false |
| Raised by | Product manager, from the architect's review |
| Decision | Pending product owner (follows PD-032) |

**Options considered**

1. Keep the non-goals; reject PD-032. Consequence: documents stay consistent; the product owner's request is not met.
2. Narrow the non-goals: vision "Not for" becomes "Teams that need full BPMN 2.0, DMN FEEL or CMMN, or a general-purpose diagram tool"; both PRDs replace the modeller non-goal with "a full BPMN 2.0 modeller or any canvas element the engine cannot run". Consequence: the non-goal still rules out the comparison we would lose and allows the scoped modeller.
3. Remove the modeller non-goal entirely. Consequence: invites full-BPMN expectations (the positioning risk the architect names).

**Recommendation (product manager).** Option 2 if PD-032 is accepted. Until then, the vision and both PRDs carry a pointer to this entry and keep their text.

**Consequences.** [vision.md](vision.md) "Who it is for, and who it is not for"; [prd-process-engine.md](prd-process-engine.md) and [prd-cockpit.md](prd-cockpit.md) non-goals; wiki Architecture non-goals list (unchanged: it names full BPMN 2.0 only).

**Follow-ups.** Product manager: apply the wording after the decision.

### PD-042 Milestone dates if the cockpit direction is accepted

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | If PD-032 to PD-038 are accepted as recommended, can M2 (12 Mar 2027) and M3 (7 May 2027) hold? |
| Raised by | Product manager, from the architect's estimate |
| Decision | Pending product owner (depends on PD-031 and PD-032 to PD-038) |

**Analysis.**

- The architect estimates about 3 extra weeks of engine and UI work in M2 for the diagram views, the definition lifecycle, the cockpit read APIs, task forms and the merged inbox. The architect proposes moving the business calendar editor (CK-F-18) and replay simulation (RE-F-22, CK-F-11) out of M2 and keeping the modeller in M3.
- Those three features are already the second and fourth M2 cuts in PD-031. Moving them up front buys back about 1 week (assumption, to be sized by the maintainers). The net M2 gap is about 2 weeks, and the remaining M2 cut list shrinks to PE-F-30 and PE-F-12, plus the new CK-F-26 (live counts on the diagram).
- M1 gains CK-F-23 and X-F-22 (host registry, `/meta`, `/me`): a few days, not in the architect's 3 weeks (assumption). M1 keeps its date and its PD-031 cut list.
- M3 gains 8 new features plus the three moved from M2, with no estimate yet. With the modeller canvas cuttable to 1.1, the engine APIs that freeze at 1.0 are the fixed part.
- Feature counts: M1 48 (from 46), M2 55 (from 44), M3 30 (from 19). Per working week, M2 at 10 calendar weeks (about 9 working weeks) is about 6.1 features, close to today's 6.3 (44 in about 7). M3 at about 4.3 is above today's 2.7, which is why the canvas and the historical heatmap are cut candidates.

**Options considered**

1. Keep 12 Mar and 7 May; apply every remaining M2 cut (PE-F-30, PE-F-12) up front as well. Consequence: dates hold on paper; M2 has no cut left for any slip; migration execution and `callProcess` land in M3 with the modeller and the API freeze.
2. Move M2 by two weeks to 26 Mar 2027 and M3 by two weeks to 21 May 2027; calendar editor and replay move to M3; the modeller canvas is the first M3 cut, with 1.1 as fallback. Consequence: the PD-031 M2 cut list (PE-F-30, PE-F-12) still exists as a buffer; 1.0 lands two weeks later, still before mid-year.
3. Move M2 by two weeks and M3 by four weeks to 4 Jun 2027 with the full modeller in 1.0. Consequence: a complete Camunda-like story at 1.0; adoption window for kill assumption K13 shrinks further.
4. Reject the cockpit direction; keep PD-031. Consequence: no date change; the request is not met.

**Recommendation (product manager).** Option 2.

**Rationale.** PD-031 already says M2 does not hold as scoped without cuts. Adding about 2 net weeks with no date change would spend the whole cut list before the first line of code, which is the silent slippage PD-031 option 3 warns about. Two weeks on M2 and M3 keeps a buffer, keeps the order of work, and keeps 1.0 before mid-year. Option 3 spends adopter time on a canvas whose demand is unproven (K16); the APIs keep that option open for 1.1.

**Consequences if accepted.** The roadmap adopts the [scenario](roadmap.md#scenario-camunda-like-cockpit): M2 18 Jan to 26 Mar 2027, M3 29 Mar to 21 May 2027, M2 checkpoint moves from 19 Feb to 5 Mar 2027 (end of week 7), M3 checkpoint from 9 Apr to 23 Apr 2027 (end of week 4). PD-031 is superseded in its M2 and M3 rows; its M1 row is unchanged. Wiki Roadmap dates change.

**Follow-ups.** Maintainers: size the moved and new features before 2 Dec 2026 so the scenario rests on estimates, not assumptions. Product manager: record the outcome in PD-031 and apply the scenario to the roadmap and feature map.
