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
| [PD-032](#pd-032-diagrams-and-a-visual-modeller-in-scope) | Diagrams and a visual modeller in scope | Decided |
| [PD-033](#pd-033-maker-checker-for-process-definitions) | Maker-checker for process definitions | Decided |
| [PD-034](#pd-034-process-notation-own-json-canonical-bpmn-subset-for-import-and-export) | Process notation: own JSON canonical, BPMN subset for import and export | Decided (superseded in part by PD-057) |
| [PD-035](#pd-035-multi-host-cockpit-model) | Multi-host cockpit model | Decided |
| [PD-036](#pd-036-source-of-truth-for-process-definitions) | Source of truth for process definitions | Decided |
| [PD-037](#pd-037-tenant-switching-for-multi-tenant-operators) | Tenant switching for multi-tenant operators | Decided |
| [PD-038](#pd-038-analytics-scope) | Analytics scope | Decided |
| [PD-039](#pd-039-principle-9-wording) | Principle 9 wording | Decided (superseded in part by PD-057) |
| [PD-040](#pd-040-principle-6-extended-to-process-definitions) | Principle 6 extended to process definitions | Decided |
| [PD-041](#pd-041-conflict-vision-non-goal-diagram-modeller) | Conflict: vision non-goal "diagram modeller" | Decided (superseded in part by PD-057) |
| [PD-042](#pd-042-milestone-dates-if-the-cockpit-direction-is-accepted) | Milestone dates if the cockpit direction is accepted | Decided |
| [PD-043](#pd-043-rule-authoring-for-business-users-without-yaml) | Rule authoring for business users without YAML | Pending product owner |
| [PD-044](#pd-044-rpo-and-rto-responsibility-wording) | RPO and RTO responsibility wording | Pending product owner |
| [PD-045](#pd-045-capacity-limits-we-publish) | Capacity limits we publish | Pending product owner |
| [PD-046](#pd-046-federation-limits-we-support) | Federation limits we support | Pending product owner |
| [PD-047](#pd-047-usability-targets) | Usability targets | Pending product owner |
| [PD-048](#pd-048-diagram-accessibility) | Diagram accessibility | Pending product owner |
| [PD-049](#pd-049-wcag-asvs-and-coverage-as-must-at-10) | WCAG, ASVS and coverage as Must at 1.0 | Pending product owner |
| [PD-050](#pd-050-retention-default-for-the-decision-log-and-history) | Retention default for the decision log and history | Pending product owner |
| [PD-051](#pd-051-tenant-exit-export-and-purge) | Tenant exit: export and purge | Pending product owner |
| [PD-052](#pd-052-tamper-evidence-beyond-append-only) | Tamper evidence beyond append-only | Pending product owner |
| [PD-053](#pd-053-licence-rule-for-test-and-build-tools) | Licence rule for test and build tools | Pending product owner |
| [PD-054](#pd-054-deprecation-length) | Deprecation length | Pending product owner |
| [PD-055](#pd-055-postgresql-version-range) | PostgreSQL version range | Pending product owner |
| [PD-056](#pd-056-rule-management-ux-direction) | Rule management UX direction | Pending product owner |
| [PD-057](#pd-057-full-bpmn-20-and-dmn-support-no-cmmn) | Full BPMN 2.0 and DMN support, no CMMN | Decided |
| [PD-058](#pd-058-feel-null-semantics-in-the-expression-language-from-m1) | FEEL null semantics in the expression language from M1 | Pending product owner |
| [PD-059](#pd-059-execution-tree-with-scope-tokens-in-m2) | Execution tree with scope tokens in M2 | Pending product owner |
| [PD-060](#pd-060-canonical-model-bpmn-and-dmn-semantic-models) | Canonical model: BPMN and DMN semantic models | Pending product owner |
| [PD-061](#pd-061-horus-built-feel) | HORUS-built FEEL | Pending product owner |
| [PD-062](#pd-062-release-targets-for-the-standards-track) | Release targets for the standards track | Pending product owner |
| [PD-063](#pd-063-principle-9-wording-and-the-standards-non-goals) | Principle 9 wording and the standards non-goals | Pending product owner |
| [PD-064](#pd-064-bpmn-miwg-test-data-licence) | BPMN MIWG test data licence | Pending product owner |
| [PD-065](#pd-065-choreography-and-conversation-diagrams-not-targeted) | Choreography and conversation diagrams not targeted | Pending product owner |
| [PD-066](#pd-066-feel-error-mode-default) | FEEL error mode default | Pending product owner |

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

**Update, 2026-11-18.** [PD-042](#pd-042-milestone-dates-if-the-cockpit-direction-is-accepted) (Decided) moves M2 to 26 Mar 2027 and M3 to 21 May 2027 and replaces the M2 and M3 rows of the cut list below; [PD-034](#pd-034-process-notation-own-json-canonical-bpmn-subset-for-import-and-export) (Decided) removes PE-F-41 from the M3 row. What stays open in this entry is the M1 row (date 15 Jan 2027 and its cut list) and the never-cut list. The analysis below is kept as written on 2026-11-18.

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

## Decided: cockpit direction (Camunda-like cockpit)

The product owner asked for a cockpit that works like Camunda's tools (model, operate, work tasks, see where processes slow down) while the engines stay embedded libraries. The architect's review "Embedded engines with a Camunda-like cockpit" (18 Nov 2026) answers that this fits the principles: all replicas of one service share one PostgreSQL database, so each host's REST layer already gives the full picture for that service, and a static cockpit that calls several hosts covers the view across services. The review found three gaps in the current plan: no diagrams anywhere, no maker-checker for process definitions, and no engine read APIs built for a cockpit. It asks for seven decisions before M2 code starts (PD-032 to PD-038). PD-039 to PD-041 record the principle and vision wording those decisions would change. PD-042 records the effect on dates.

The product owner accepted the direction on 2026-11-18 ("add it into the product roadmap"), with the product manager's recommended variant of every entry. The [roadmap](roadmap.md) and the [feature map](feature-map.md) apply it: the 24 new features are in the feature map tables with status "spec gap", M2 ends on 26 Mar 2027 and M3 on 21 May 2027, and kill assumptions K15 to K18 are in the roadmap.

What "Camunda-like" includes and excludes in all eleven entries:

| Camunda tool | Included | Not included |
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
| Decision | Decided by the product owner, 2026-11-18: option 2 as adjusted by the product manager. Read-only diagrams in M2; the modeller canvas (CK-F-32) in M3 as the first M3 cut, fallback 1.1; the engine APIs it needs (PE-F-44, PE-F-46, PE-F-52) before the 1.0 freeze |

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

- Personas. The Operator and Auditor (cockpit PRD section 5) get the most from the read-only diagram: "where is this instance and why is it stuck" is their daily question, and the token overlay answers it in one screen. The Author persona for processes does not exist yet; who changes a process in a bank today is unknown (kill assumption K16 in the roadmap). That makes the diagram certain value and the modeller a bet.
- Positioning against Camunda. We will not out-draw a modeller with ten years of polish. We can win on what an embedded engine with governance does that Camunda's modeller does not: the canvas offers only steps the host has registered, validation runs on save, and every change goes through a second approver (PD-033). The modeller should be sold as "governed change to a running service", not as a drawing tool. A read-only diagram removes the most visible gap in an evaluation ("no diagrams") at the lowest cost.
- Scope and dates. M2 is the largest milestone (44 features, about seven working weeks). A modeller in M2 would sink it. In M3 the modeller competes with the API freeze and the adopter exit; keeping the canvas cuttable, but the APIs fixed, protects 1.0 without closing the door.
- The architect's two-day React Flow spike (render `kyc-onboarding` with automatic layout and a token overlay) should run in M0 and feed the K5 architect conversations in M1 with real screenshots.

**Consequences.** New features PE-F-44, PE-F-47, PE-F-48, CK-F-24, CK-F-25, CK-F-26, CK-F-32, CK-F-33 (see the feature map). CK-F-12 keeps its timeline and gains the diagram through CK-F-25. Vision, process PRD and cockpit PRD non-goals change (PD-041). Principle 9 changes (PD-039). ADR-0013 (notation, layout storage, diagram library, bpmn-js licence finding) is needed before M2 code. React Flow and dagre each need a one-paragraph dependency justification and a licence re-check at adoption.

**Follow-ups.** Architect: ADR-0013 and the M0 spike. Product manager: CKP-level journeys for the diagram views in the cockpit PRD after the decision; add K15 to K18 to the roadmap kill assumptions once decided (done 2026-11-18). QA: licence gate test that rejects bpmn-js.

### PD-033 Maker-checker for process definitions

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Do process definitions get the same governed lifecycle as rule sets (draft, request, approve by another identity, effective date, retire)? Today they are deployed directly with `processes:deploy` |
| Raised by | Architect's review, decision 2 |
| Decision | Decided by the product owner, 2026-11-18: option 2 with the product manager's addition. Lifecycle, permissions, events and graph diff in the engine in M2; approval screens in M3; the `dev` profile may auto-approve |

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

**Consequences.** New features PE-F-45 (lifecycle, M2), PE-F-46 (graph diff, M2), CK-F-31 (approval screens, M3). PE-F-01 and PE-F-28 change contract: deploy creates a draft. Principle 6 is extended (PD-040). ADR-0012 and an ADR-0010 amendment are needed before M2 code. `TR-SEC-04` applies to process definitions as well as rule sets.

**Follow-ups.** Architect: ADR-0012, ADR-0010 amendment, process OpenAPI changes, `PRE-*` statements for the lifecycle and the self-approval check. Product manager: update the process PRD and requirements after the decision. Security reviewer: confirm the `dev`-only relaxation rule.

### PD-034 Process notation: own JSON canonical, BPMN subset for import and export

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | What is the source notation for process definitions once diagrams exist, and what is BPMN's role? |
| Raised by | Architect's review, decision 3 |
| Decision | Decided by the product owner, 2026-11-18: option 1. Own JSON canonical; BPMN subset import (PE-F-41) off the cut list; export (PE-F-53) in M3 as a cut candidate. Superseded in part by [PD-057](#pd-057-full-bpmn-20-and-dmn-support-no-cmmn) (2026-11-18): the subset is the M3 scope, not the end state; the canonical notation is pending as PD-060 |

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

**Consequences.** PE-F-41 is removed from the PD-031 M3 cut list (PD-031 is otherwise unchanged). New feature PE-F-53 (export, M3, cut candidate). The vision's "never a full BPMN 2.0 implementation" stays.

**Follow-ups.** Architect: the subset definition and error codes in ADR-0013. Product manager: update PD-031's cut list wording once decided.

### PD-035 Multi-host cockpit model

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | How does one cockpit show work from several host services when there is no central engine server? This also answers the open M1 gate "standalone cockpit host switching" |
| Raised by | Architect's review, decision 4 |
| Decision | Decided by the product owner, 2026-11-18: option B, with A supported, D documented and C rejected for now. Host registry, `/meta` and `/me` in M1; merged inbox and incident list in M2 |

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
- Limits stated plainly. Federation above about 15 hosts or 2,000 active operators is outside what B is designed for. The roadmap adds kill assumption K17 to test pilot topology early.

**Consequences.** New features CK-F-23 (M1), X-F-22 (M1), CK-F-28 and CK-F-29 (M2). ADR-0014 amends ADR-0005 and `TR-UI-01` (host registry, sign-in across hosts with OIDC PKCE as a public client, version skew: the cockpit supports the current and previous engine minor version and turns off screens a host cannot serve). `oidc-client-ts` (Apache 2.0) needs a dependency justification. CK-F-01's open design question is closed by this decision.

**Follow-ups.** Architect: ADR-0014 and the `/meta` and `/me` contracts before M1 code; size the M1 part. Security reviewer: token audience per host, token lifetime, CORS off by default, ASVS L2 review of the federated mode. Documentation: reference gateway configuration for option D.

### PD-036 Source of truth for process definitions

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Banks promote changes from development to UAT to production. If definitions can be changed both in Git and in the production cockpit, which one is the truth? |
| Raised by | Architect's review, decision 5 |
| Decision | Decided by the product owner, 2026-11-18: option 3, with a per-environment host setting that turns off draft creation from the cockpit (default on) |

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

**Consequences.** Part of PE-F-45 (import arrives as draft, content hash exposed, per-environment setting). Approvers in each environment approve the promoted draft; this is stated in the process PRD.

**Follow-ups.** Architect: the setting and the hash in ADR-0012. Product manager: the promotion journey in the process PRD.

### PD-037 Tenant switching for multi-tenant operators

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Today the tenant comes only from the token. How does an operator who serves several tenants work in the cockpit? |
| Raised by | Architect's review, decision 6 |
| Decision | Decided by the product owner, 2026-11-18: option 2, in M2 (X-F-23) |

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

**Consequences.** New feature X-F-23 (M2): host check plus the cockpit tenant switcher. The allowed-tenants claim name is documented for host IdP configuration.

**Follow-ups.** Architect: claim name and resolver rule in ADR-0014. Security reviewer: tests that a header outside the allowed list is rejected and logged.

### PD-038 Analytics scope

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | How much of Camunda Optimize does the product provide? |
| Raised by | Architect's review, decision 7 |
| Decision | Decided by the product owner, 2026-11-18: option 2. Live counts per node in M2; hourly rollup and heatmap in M3; no aggregator before a paying customer |

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

**Consequences.** New features PE-F-48, PE-F-49, CK-F-26, CK-F-27. ADR-0015 (operational read models and query limits) and X-F-24 (bounded queries, CI query-plan check) apply. PD-016 is unchanged; if the product owner wants the pulse strip, PD-016 is the place to change it.

**Follow-ups.** Architect: ADR-0015 with DDL, indexes, rollup job and query math. Documentation: event schemas page as the warehouse integration point.

### PD-039 Principle 9 wording

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Principle 9 reads "Standards as vocabulary, not compliance. BPMN and DMN words, a documented subset of their semantics", which readers take as "no diagrams". Change it if PD-032 and PD-034 are accepted? |
| Raised by | Architect's review, section 1 |
| Decision | Decided by the product owner, 2026-11-18: option 2. Superseded in part by [PD-057](#pd-057-full-bpmn-20-and-dmn-support-no-cmmn) (2026-11-18): the limit on BPMN scope no longer holds; new wording pending as PD-063 |

**Options considered**

1. Keep the wording. Consequence: contradicts PD-032 if it is accepted.
2. Reword to: "Our own graph notation is the source of truth and always has a diagram; a BPMN subset is for import and export. DMN words, a documented subset of their semantics." Consequence: the principle states what a user gets and keeps the limit on BPMN scope.

**Recommendation (product manager).** Option 2 if PD-032 and PD-034 are accepted; option 1 otherwise. The "Therefore" column stays: "The engines stay small. What is supported is exactly what is documented."

**Consequences.** Wiki page Principles row 9, the vision table "What the engine will never be" (row on BPMN, which keeps its meaning), wiki Concepts and FAQ ("Does it read DMN or BPMN files?").

**Follow-ups.** Documentation: update the wiki in the same change that records the decision. Architect: reflect it in ADR-0013.

**Applied, 2026-11-18.** Vision table "What the engine will never be", BPMN row. The wiki Principles row 9, Concepts and FAQ are with documentation.

### PD-040 Principle 6 extended to process definitions

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Principle 6 ("Governance built in. Draft, simulate, request, approve by another identity, effective date, retire. The engine enforces each step.") is applied only to rule sets today. State that it covers process definitions? |
| Raised by | Architect's review, section 1 |
| Decision | Decided by the product owner, 2026-11-18: option 2 |

**Options considered**

1. Keep the text and its rules-only practice. Consequence: matches today's plan; conflicts with PD-033 if accepted.
2. Extend the text: "Governance built in, for rule sets and process definitions. Draft, validate or simulate, request, approve by another identity, effective date, retire. The engine enforces each step." Consequence: the principle matches PD-033; "simulate" for processes means validation and graph diff until a dry run with test variables exists.

**Recommendation (product manager).** Option 2 if PD-033 is accepted. Layout changes (PE-F-44) are outside the principle because they never change behaviour, as the architect proposes; the wording should say so in the wiki explanation, not in the principle line.

**Consequences.** Wiki Principles row 6, vision differentiator 2 ("Rule changes by business users with maker-checker" becomes "Rule and process changes"), cockpit PRD Approver persona permissions.

**Follow-ups.** Documentation: wiki update with the decision. Product manager: vision and PRD updates after the decision.

**Applied, 2026-11-18.** Vision differentiator 2 and the cockpit PRD Approver permissions. The wiki Principles row 6 is with documentation.

### PD-041 Conflict: vision non-goal "diagram modeller"

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The vision says the product is not for "teams that need full BPMN 2.0, DMN FEEL, CMMN or a diagram modeller"; the process PRD lists "a graphical modeller" and the cockpit PRD "a BPMN diagram modeller" as non-goals. PD-032 would make these false |
| Raised by | Product manager, from the architect's review |
| Decision | Decided by the product owner, 2026-11-18: option 2. Superseded in part by [PD-057](#pd-057-full-bpmn-20-and-dmn-support-no-cmmn) (2026-11-18): full BPMN 2.0 and DMN are no longer non-goals; CMMN and the modeller limit stay |

**Options considered**

1. Keep the non-goals; reject PD-032. Consequence: documents stay consistent; the product owner's request is not met.
2. Narrow the non-goals: vision "Not for" becomes "Teams that need full BPMN 2.0, DMN FEEL or CMMN, or a general-purpose diagram tool"; both PRDs replace the modeller non-goal with "a full BPMN 2.0 modeller or any canvas element the engine cannot run". Consequence: the non-goal still rules out the comparison we would lose and allows the scoped modeller.
3. Remove the modeller non-goal entirely. Consequence: invites full-BPMN expectations (the positioning risk the architect names).

**Recommendation (product manager).** Option 2 if PD-032 is accepted. Until then, the vision and both PRDs carry a pointer to this entry and keep their text.

**Consequences.** [vision.md](vision.md) "Who it is for, and who it is not for"; [prd-process-engine.md](prd-process-engine.md) and [prd-cockpit.md](prd-cockpit.md) non-goals; wiki Architecture non-goals list (unchanged: it names full BPMN 2.0 only).

**Follow-ups.** Product manager: apply the wording after the decision.

**Applied, 2026-11-18.** [vision.md](vision.md) "Not for" row, [prd-process-engine.md](prd-process-engine.md) and [prd-cockpit.md](prd-cockpit.md) non-goals.

### PD-042 Milestone dates if the cockpit direction is accepted

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | If PD-032 to PD-038 are accepted as recommended, can M2 (12 Mar 2027) and M3 (7 May 2027) hold? |
| Raised by | Product manager, from the architect's estimate |
| Decision | Decided by the product owner, 2026-11-18: option 2. M2 ends 26 Mar 2027, M3 ends 21 May 2027 |

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

**Consequences.** The roadmap adopts these dates: M2 18 Jan to 26 Mar 2027, M3 29 Mar to 21 May 2027, M2 checkpoint moves from 19 Feb to 5 Mar 2027 (end of week 7), M3 checkpoint from 9 Apr to 23 Apr 2027 (end of week 4). This entry replaces the M2 and M3 rows of PD-031, which stays pending for its M1 row. Wiki Roadmap dates change.

**Follow-ups.** Maintainers: size the moved and new features before 2 Dec 2026 so the plan rests on estimates, not assumptions. Product manager: record the outcome in PD-031 and apply the plan to the roadmap and feature map.

**Applied, 2026-11-18.** Roadmap, wiki Roadmap page and timeline image, feature map (24 features moved into the module tables as spec gaps; CK-F-11, CK-F-18 and RE-F-22 moved to M3), product design review page, and a note in PD-031.

---

## Pending: business-user authoring

### PD-043 Rule authoring for business users without YAML

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Today a business user can only import YAML or JSON in Cockpit 0.1 (M1) and gets the grid editor in Cockpit 0.2 (M2). Which authoring path do pricing managers and credit risk analysts get in M1, and what must "user-friendly" mean in testable terms? |
| Raised by | Product owner review of the Rule Engine product design ("who is the user, how do they interact, do we provide a user-friendly UI so the business user can create rules and rule sets?"); product manager, from the [Rule Engine users and journeys](rule-engine-users-and-journeys.md) catalogue |
| Decision | Pending product owner |

**What the users need.** From the [personas](rule-engine-users-and-journeys.md#users):

| Persona | Reads YAML | Uses Excel | What they change | How often |
|---|---|---|---|---|
| Pricing manager (fees and pricing) | No | Every day | Values and rows in existing fee and rate tables | A few changes a month, around pricing committee dates |
| Credit risk analyst | With help; does not write it | Every day, large workbooks | Thresholds and rows in eligibility and limit tables; a new rule set a few times a year | Monthly policy cycle |
| Compliance officer (approver) | No | Yes | Nothing; reviews and approves | Per request, a few a week |
| Business analyst (change team) | Yes | Yes | Anything, on behalf of the business | Weekly |

Two facts shape the answer:

- Most business changes are edits to an existing rule set: a value, a threshold, a new row. [ASSUMPTION A1] Test: ask two pricing and two credit risk contacts to list their rule changes of the last 12 months and classify them. Kill criterion: more than one third need a new rule set or a new input.
- A new rule set always needs a developer, because the host service must send its inputs and use its outputs. The business user designs the table; a developer wires it ([journey 2](rule-engine-users-and-journeys.md#journey-2-a-credit-risk-analyst-creates-a-new-eligibility-rule-set)). An editor that creates rule sets helps less than one that changes them.

**What "user-friendly" must mean.** Each criterion is testable in a moderated session or a CI test:

| Id | Criterion | Test |
|---|---|---|
| U1 | No YAML or JSON on any business path: changing a value, adding a row, and (from M2) creating a rule set never show a YAML or JSON document | Moderated session; the screens under test have no YAML view in the path |
| U2 | No expression syntax for common conditions. A cell accepts `GOLD` (equals), `GOLD, PLATINUM` (one of), `not GOLD, PLATINUM`, `>= 50000000 VND`, `< 18`, `18 to 65` (between, inclusive), `empty` (no value), and `-` or a blank cell (any). The product turns these into the expression language using the declared input type. The full language stays available and is marked "advanced" | Conversion tests per declared type; session task with no help text open |
| U3 | Templates: a new rule set starts from a template (fee by tier, eligibility with reason codes, limit by segment, blank) with inputs, outputs and a default row filled in | Session: create from a template |
| U4 | Plain-language validation messages that name the row and the column and say what to do, in English and Vietnamese. Example: "Row R4, column Balance: 50,000,000 has no currency. Write 50000000 VND." Never a parser message or a stack trace | Message catalogue test: every validation code has a text in both languages |
| U5 | Preview with sample data before requesting approval: results for the draft next to the effective version, changed rows flagged | Exists in M1 (CK-F-05) |
| U6 | Typed values: money needs a currency, dates are ISO dates, inputs with allowed values offer only those values (drop-down in the grid; checked on import) | Validation tests per type |
| U7 | Request approval and pick the effective date from the same screen | Session |

Target: an analyst who has not seen the product changes one value and adds one row in an existing rule set, previews it and reaches `PENDING_APPROVAL` in under 15 minutes without help. This replaces the YAML task in kill assumption K3.

**Options considered**

| # | Option | M1 | M2 | Extra cost (assumption, to be sized by the maintainers) | Criteria met in M1 | Date risk |
|---|---|---|---|---|---|---|
| 1 | Keep as is | YAML or JSON import (CK-F-02) | Grid editor (CK-F-10) | None | U5, U7 | None |
| 2 | Basic grid editor in M1: create from a template, add rows, typed cells, validation, request approval | Grid editor and templates | Replay, operations | 2 to 3 weeks of cockpit work | U1, U3 to U7; U2 partly | High. M1 has about 5 working weeks for 48 features; 15 Jan 2027 holds only by moving M1 2 to 3 weeks or cutting the decision explorer filters, batch evaluate and async logging and more |
| 3 | Spreadsheet round-trip in M1; grid with templates and condition shorthand in M2 | Export a rule table as CSV, edit it in Excel, import it back as a draft; cells use the U2 shorthand; plain-language messages | Grid editor with templates, a "new rule set" flow and drop-downs | About 1 week in M1, about 1 week in M2 | U1 for changes to existing rule sets, U2, U4, U5, U6 (checked on import), U7 | Moderate. The M1 part fits if batch evaluate (RE-F-26) moves to M2 up front |
| 4 | Guided "create rule set" wizard (name, hit policy, inputs, outputs, first rows) | Wizard | Grid editor | 1.5 to 2 weeks | U1 and U3 for new rule sets only | High, and it serves the rarest job, which still needs a developer to wire |

Why CSV and not `.xlsx` in option 3: Excel opens and saves CSV; reading `.xlsx` in the browser needs a spreadsheet library that must pass the licence gate (`TR-LIC-01`) and adds bundle weight. Native `.xlsx` stays a later candidate (UC-RE-12).

What a CSV round-trip must handle, because Excel changes data silently: comma or semicolon separators depending on locale, UTF-8 with or without a byte order mark, long numbers turned into scientific notation, leading zeros dropped, dates reformatted, and thousands separators. Each of these must be rejected with a U4 message, never accepted as a different value. [ASSUMPTION A3] Test: round-trip a 50-row `card-fee` table through Excel on Windows and macOS in English and Vietnamese locales, one day in week 1 of M1. Kill criterion: any changed value passes validation.

**Competitors and what banks use today.**

| Alternative | How business users author | What it lacks for this job |
|---|---|---|
| Camunda decision tables | DMN tables in Camunda's modeller; cells are FEEL expressions | Built for developers and technical analysts; a change is a deployment; approval is left to the customer's pipeline |
| Drools | Decision tables written in spreadsheets | No enforced second approver and no decision log in the library |
| Commercial rule management suites | Business rule editors with governance | Licence cost and a separate server to run |
| Excel plus email, the common bank practice [ASSUMPTION A2] | The fee schedule or credit policy lives in a workbook, is approved by email or committee minutes, and IT re-keys it | No validation, no preview, no enforced second approver, no link from a decision to the row that made it |

Test for A2: ask the three compliance contacts of K6 and two pricing contacts how a fee change travels today, during M1.

The position this supports: meet business users in the spreadsheet they already use, and add what the spreadsheet lacks (validation, preview, an enforced second approver, an effective date and a decision log). Do not compete on editor polish in M1.

**Recommendation (product manager).** Option 3.

- M1: spreadsheet round-trip for existing rule sets (CK-F-35), with the U2 shorthand and U4 messages. The CSV changes rows only; inputs and outputs are not editable through it, because changing them breaks the host's call. Batch evaluate (RE-F-26) moves to M2 up front to make room; no business persona depends on it in M1.
- M2: the grid editor (CK-F-10) gains templates and a "new rule set" flow (CK-F-36), which replaces option 4's wizard, and drop-downs plus a plain-language reading of each condition (CK-F-37).
- No grid in M1. Option 2 puts the 15 Jan 2027 date at risk for a screen that option 3 covers for the most frequent job.
- Journey gaps found by the use case catalogue, in the same decision: restore an earlier version as a draft (RE-F-33, M1, makes the documented rollback explicit); open one decision from a link in the bank's own screen (CK-F-34, M1); find decisions by correlation id or an exact input value (RE-F-34, M2, cut candidate); version history with approvers and evidence export (CK-F-38, M3, cut candidate).

**Rationale.**

- Personas. Pricing managers and risk analysts work in Excel every day and change existing tables a few times a month. The round-trip gives them a path without YAML in M1; the grid in M2 gives them typed cells and templates. Business analysts keep YAML and Git.
- Positioning. The claim "business users change rules" is the M1 outcome. With option 1 the first business demo shows a YAML file and the claim is true only through a business analyst.
- Scope and dates. M1 changes by +3 features and -1 moved (RE-F-26), from 48 to 50, at about 1 week of work against about 0.5 week removed. M2 gains 4 (RE-F-26, RE-F-34, CK-F-36, CK-F-37), from 55 to 59; RE-F-26 and RE-F-34 join the M2 cut list after PE-F-12. If the maintainers' sizing shows M2 cannot absorb CK-F-37, its drop-downs move to M3 and grid cells accept the U2 shorthand as typed text only, which still meets U2.
- Where the shorthand conversion runs is an architecture question: in the cockpit (no engine contract change in M1) or in the engine import API (one rule for Git and cockpit). The stored version always holds the canonical expression, so the decision log, the diff and replay do not change.

**Kill criteria if accepted.**

| Id | Claim | Cheapest test | Kill criterion | Then |
|---|---|---|---|---|
| K3 (revised) | Analysts change an existing table through the spreadsheet round-trip without help | Two moderated sessions in week 2 of M1 with the CSV path | Neither analyst reaches `PENDING_APPROVAL` within 15 minutes without help | Move CK-F-10 and CK-F-37 to the start of M2 and protect them; restate the M1 outcome as analyst-mediated |
| A1 | Most business changes edit an existing rule set | Classify 12 months of changes from four contacts | More than one third need a new rule set or input | Raise CK-F-36 (templates) above CK-F-37 in M2 |
| A3 | Excel round-trips do not change values silently | One-day round-trip test, two operating systems, two locales | Any changed value passes validation | Require quoted text cells in the exported CSV and document the Excel import steps; reconsider `.xlsx` |

**Consequences if accepted.** Feature map: CK-F-35, RE-F-33 and CK-F-34 in M1; RE-F-26 to M2; RE-F-34, CK-F-36 and CK-F-37 in M2; CK-F-38 in M3. Roadmap counts M1 50, M2 59, M3 31. Kill assumption K3 is rewritten; A1 and A3 join the roadmap. `TR-UI-02` (authoring in the cockpit) can gate M1 for changes to existing rule sets, which narrows PD-013. The wiki authoring page gains a "change a table in Excel" how-to. Option 1 needs no change; option 2 would also move M1, M2 and M3 by 2 to 3 weeks.

**Follow-ups.** Maintainers: size CK-F-35, CK-F-36 and CK-F-37 before 2 Dec 2026. Architect: where the shorthand conversion runs, and the CSV header and quoting rules. QA: the Excel round-trip test (A3). Product manager: run the A1 and A2 conversations; rewrite K3. Documentation: the Excel how-to page after the decision.

---

## Pending: requirements review (non-functional and developer requirements)

The architect reviewed the technical, non-functional and developer requirements on 18 Nov 2026. The gaps that are product choices are PD-044 to PD-055. The architect's proposals for the rest (availability, failover, read limits, alerts, log content, footprint, Java API, documentation and test kit standards) wait for the product owner's acceptance and are not recorded here. [requirements.md](requirements.md) is not changed until these entries are decided.

### PD-044 RPO and RTO responsibility wording

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The engines run inside the host's JVM and database and have no backup or failover of their own. What do we promise about recovery point (RPO) and recovery time (RTO), and in what words? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** A published responsibility matrix. The bank owns backup, point-in-time recovery, replication, RPO and RTO. The engine guarantees three things: a restore needs no engine-specific step; engine start-up adds at most 5 s to the host's RTO; leases left from before the restore are reclaimed automatically. The host owns reconciliation after a restore, because a restore can repeat adapter calls whose second transaction was lost and downstream systems may hold events for state the restore removed. `TR-DR-01` and `TR-DR-02`, Must at 1.0.

**Options considered**

1. Say nothing. Consequence: every bank's architecture review asks, and each adopter gets a different answer.
2. The architect's matrix, Must at 1.0. Consequence: a clear split; the three engine guarantees need a restore test.
3. Publish engine RPO and RTO numbers. Consequence: a library cannot meet them; the numbers would be the host database's, restated as ours.

**Recommendation (product manager).** Option 2, with the wording published with the M2 documentation, when the first pilots start, and the restore test gating 1.0. Proposed wording: "The engines have no recovery point or recovery time of their own; they inherit those of your PostgreSQL database. After a restore the engines need no extra step, add at most 5 seconds to your service's start-up, and reclaim unfinished work automatically. Your service reconciles side effects: adapters must be idempotent by `attemptId`, and consumers must tolerate events for state the restore removed."

**Rationale.** "No separate data store" (principle 2) is a selling point only if the recovery story is as plain as the database's. Pilots in M2 will ask first; the wording costs nothing, the test can wait for 1.0.

**Consequences if accepted.** New `TR-DR-01` (responsibility matrix and post-restore runbook) and `TR-DR-02` (start-up and lease bounds), Must at 1.0. Wiki: a recovery section in the operations documentation. Adapter documentation states the idempotency obligation.

**Follow-ups.** Architect: the bounds and the restore test. Documentation: the wiki section. Product manager: requirements.md after the decision.

### PD-045 Capacity limits we publish

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Which capacity numbers per host do we publish to adopters, and as what: a limit, a tested envelope, or a design target? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** Tested per release: 200 tenants, 100 rule sets per tenant, 1 million active instances, 50 million history rows per month, 1,000 transitions per second, 2,000 decisions per second. Design target 500 tenants. `TR-CAP-01`, Must at 1.0.

**Options considered**

1. Publish nothing until measured. Consequence: bank sizing reviews have no number to start from.
2. Publish only the tested envelope, with the reference hardware stated, as "tested, not a limit", from the first release that measures it; keep design targets internal. Consequence: every published number is reproducible; the first numbers come after the M2 load test.
3. Publish the design targets now. Consequence: unmeasured numbers become promises.

**Recommendation (product manager).** Option 2. The numbers above become the 1.0 tested envelope. Until the M2 load test, the documentation says "design targets, not measured" and points to the published harness so a bank can measure its own hardware. The 500-tenant design target is not published.

**Rationale.** A bank's architecture review needs a starting number, and the harness lets it verify one. A number we have not measured is a liability in a procurement answer. This matches the performance contract the architect proposed for the evaluator: state the conditions, publish the harness, promise nothing beyond them.

**Consequences if accepted.** New `TR-CAP-01` (Must at 1.0) with the envelope above and the reference hardware. The capacity test runs per release on the dedicated runner. The roadmap M2 exit reports the first measured numbers.

**Follow-ups.** Architect and QA: the capacity test design. Product manager: the published wording after the first measurement.

### PD-046 Federation limits we support

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | PD-035 made the standalone cockpit federate hosts from the browser. What limit do we support and state to adopters? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** At most 15 hosts and 2,000 active operators per standalone cockpit; merged inbox p95 300 ms. `TR-UI-05`, Must in M2.

**Options considered**

1. No stated limit. Consequence: a bank with 40 services finds the limit in production.
2. State 15 hosts and 2,000 active operators as the supported envelope, tested in M2 with simulated hosts; above it, the documented answers are a cockpit per host (option A) or the bank's gateway (option D). Consequence: a clear boundary; the aggregator (option C) stays a paid-customer decision.
3. State a higher limit to widen the market. Consequence: browser merging and approximate paging stop working well above about 15 hosts (PD-035 analysis).

**Recommendation (product manager).** Option 2. Gate `TR-UI-05` in M2 with a test of 15 simulated hosts (stub hosts with p99 150 ms) and 2,000 operators' refresh load. Revisit the numbers when kill assumption K17 (pilot topology) is answered in M1.

**Rationale.** The limit is the honest edge of a design with no server (differentiator 1). Saying it plainly is better than an evaluator finding it; and the two answers above the limit keep large banks in scope without building a control plane.

**Consequences if accepted.** New `TR-UI-05` (Must, M2). Cockpit documentation states the envelope and the two options above it. K17 links to this entry.

**Follow-ups.** Architect and QA: the simulated-host load test. Product manager: update after K17.

### PD-047 Usability targets

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The only usability number today is "first decision in under 30 minutes" (X-F-16), and it has no requirement id. Which usability targets gate or report a milestone? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** `TR-UI-10` (Should, M2): an operator finds why an instance is stuck in 3 clicks or fewer; 4 of 5 pilot operators finish a task unaided.

**Options considered**

1. No usability targets. Consequence: the outcome claims ("business staff", "operators") have no measure.
2. The architect's `TR-UI-10` only. Consequence: operators get a target; business users and developers do not.
3. `TR-UI-10` as proposed, plus a business-user target and the newcomer target as requirements.

**Recommendation (product manager).** Option 3, all Should and reported at milestone exit: `TR-UI-10` as proposed (M2; if no pilot operators exist by M2, five moderated sessions with stand-in operators); `TR-UI-11` (M1): two analysts who have not seen the product change an existing table and reach `PENDING_APPROVAL` in under 15 minutes unaided (the PD-043 target, on whichever authoring path PD-043 chooses); `TR-UI-12` (M1): a newcomer reaches a first logged decision in under 30 minutes (X-F-16).

**Rationale.** Each milestone outcome names a person; each target measures that person. They are Should, not Must, because they are measured with people in moderated sessions, not in CI; a failure changes the next milestone's priorities, as kill assumption K3 already does.

**Consequences if accepted.** New `TR-UI-10`, `TR-UI-11`, `TR-UI-12` (Should). X-F-16 gets a requirement id. The roadmap M1 and M2 exits report them.

**Follow-ups.** Product manager: session scripts and recruiting. QA: timing protocol.

### PD-048 Diagram accessibility

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | `TR-UI-03` (WCAG 2.1 AA) covers screens. How do the M2 diagrams and the M3 modeller canvas meet it? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** Every diagram has a keyboard-usable node list; the modeller canvas is exempt in M3 and says so. Extend `TR-UI-03`.

**Options considered**

1. Exempt diagrams. Consequence: operators who use a keyboard or a screen reader cannot work instances.
2. Node list for every diagram; canvas exempt, stated on screen. Consequence: read paths are accessible; editing on the canvas is not.
3. Full keyboard editing on the canvas in M3. Consequence: canvas work grows in the most loaded milestone, where the canvas is already the first cut.

**Recommendation (product manager).** Option 2 with two additions: token state, incidents and counts are conveyed in text as well as colour (WCAG 1.4.1), and the canvas exemption names an equivalent path, editing the definition as JSON or YAML and importing it as a draft, so no user is blocked from making a change.

**Rationale.** The node list gives the same information as the diagram at small cost. An exemption is acceptable only when an equivalent path exists, which the import path already provides (PD-036).

**Consequences if accepted.** `TR-UI-03` extended to diagrams in M2. Cockpit SPEC statements for the node list and the exemption notice.

**Follow-ups.** Architect: CKP statements. QA: axe-core and keyboard tests on the diagram views.

### PD-049 WCAG, ASVS and coverage as Must at 1.0

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | `TR-UI-03` (WCAG 2.1 AA), `TR-SEC-07` (OWASP ASVS level 2 review per minor release) and `TR-QA-03` (coverage of 85 percent for core and 95 percent for the evaluator) are Should today. Do they become Must at 1.0? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** Must from 1.0 for all three. The review also lists `TR-OPS-02` (zero-downtime upgrade between adjacent minors) for promotion.

**Options considered**

1. Keep them Should. Consequence: 1.0 can ship without evidence a bank's security and accessibility reviews ask for.
2. Must at 1.0 for all three, and `TR-OPS-02` as well. Consequence: four more 1.0 gates.
3. Must earlier, from M2. Consequence: gates before the API and screens are stable.

**Recommendation (product manager).** Option 2. `TR-UI-03` may already gate earlier if [PD-026](#pd-026-conflict-should-requirements-used-as-release-gates) is decided as recommended (Must from M1); this entry sets the 1.0 floor either way. `TR-OPS-02` is included because the M3 outcome promises upgrades without downtime. Coverage counts line and branch coverage of the core and evaluator modules only, not examples or the cockpit.

**Rationale.** These are the evidence items a bank's vendor review requests for a component in its payment or lending path. At 1.0 the API is frozen, so the cost of meeting them is known.

**Consequences if accepted.** `TR-UI-03`, `TR-SEC-07`, `TR-QA-03` and `TR-OPS-02` become Must at 1.0. The roadmap M3 exit lists them.

**Follow-ups.** QA: coverage scope in CI. Security reviewer: ASVS checklist ownership.

### PD-050 Retention default for the decision log and history

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The decision log and process history are partitioned monthly (PD-004), and retention drops whole partitions when configured (RE-F-31). With no configuration, what happens? Banks keep audit data for 5 to 10 years, often longer for credit decisions. |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** Choose between keeping everything by default and shipping a default period. Amend `TR-OPS-04`.

**Options considered**

1. Keep forever by default; purge only when configured. Consequence: no audit evidence lost by accident; storage grows (about 43 GB a day of history at a sustained 1,000 transitions per second).
2. A default period, for example 10 years. Consequence: a bank that must keep longer loses data unless it changes a setting it may not know about.
3. No default: outside the `dev` profile the starter refuses to start until retention is set. Consequence: an explicit choice, at the cost of one more required setting.

**Recommendation (product manager).** Option 1, with a start-up warning outside the `dev` profile when retention is not set, the growth per row published (see PD-045), and retention configurable only in whole months, never below 12 months.

**Rationale.** Deleting audit evidence must be a deliberate act by the bank, never a product default. A refusal to start (option 3) would also block the 30-minute Getting Started path in non-dev profiles used for demos. The warning and the published growth figures make the cost visible.

**Consequences if accepted.** `TR-OPS-04` rewritten to match PD-004 and this default. RE-F-31 and the process history retention follow it.

**Follow-ups.** Architect: the warning and the 12-month floor in DESIGN. Documentation: retention guidance in the decision log page.

### PD-051 Tenant exit: export and purge

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | A shared service that hosts several tenants may need to hand one tenant all its data and then remove it. Purging rows conflicts with append-only storage. Do we support tenant exit, and when? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** `TR-TEN-03` in 1.1: export and purge one tenant. Monthly partitions hold all tenants, so purge means row deletes, which conflicts with append-only. Product decides whether; architecture decides how.

**Options considered**

1. Not supported; a database administrator deletes rows by hand. Consequence: an unaudited bypass of append-only.
2. In 1.1: export of all of one tenant's data (rule sets, versions, decision log, definitions, instances, history) in a documented format, and purge as a governed operation approved by a second identity, leaving a purge record outside the purged data. Consequence: the only exception to append-only is itself audited.
3. Both in 1.0. Consequence: more scope in the most loaded milestone for a need no pilot has stated.

**Recommendation (product manager).** Option 2, in 1.1. Until then the documentation states that tenant exit is not supported and that row deletes outside the engine break the audit guarantee.

**Rationale.** Tenant exit matters for shared-service providers and fintech platforms, not for the single-bank pilots expected before 1.0. When it comes, it should follow the product's own rule: a destructive action needs a second approver and leaves a record.

**Consequences if accepted.** New `TR-TEN-03` (1.1). `TR-OBS-01` gains one named exception: governed tenant purge.

**Follow-ups.** Architect: the purge design against monthly partitions. Product manager: revisit if a pilot is a shared-service provider.

### PD-052 Tamper evidence beyond append-only

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Append-only holds against our code, not against a database administrator. Do we add tamper evidence? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** A per-partition hash chain after 1.0 (`TR-OBS-09`, Should, 1.1).

**Options considered**

1. None; rely on the bank's database audit and privileged access controls. Consequence: no product work; auditors rely on database controls.
2. Tamper evidence in 1.1. Consequence: a check an auditor can run without trusting the database administrator.
3. In 1.0. Consequence: scope and a write-path change in M3.

**Recommendation (product manager).** Option 2 in 1.1, with a preference to evaluate sealing each monthly partition when it closes (a hash manifest the bank can copy to its own write-once storage) rather than a per-row chain, because a chain orders every insert of a partition and could add contention at 2,000 decisions per second. From 1.0 the documentation states the boundary plainly: append-only protects against the engine's code; database audit and privileged access controls protect against administrators.

**Rationale.** Auditors ask "could anyone change a past decision?" The honest 1.0 answer is the boundary statement; the 1.1 answer is a check they can run. Sealing closed months detects later changes without touching the hot insert path; the architect decides which mechanism.

**Consequences if accepted.** New `TR-OBS-09` (Should, 1.1). Documentation of the 1.0 boundary.

**Follow-ups.** Architect: chain or seal, with its cost at 2,000 inserts per second.

### PD-053 Licence rule for test and build tools

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | `TR-LIC-01` allows only Apache 2.0, MIT and BSD and fails the build otherwise. Our own test stack fails it: JUnit 5 and jqwik are EPL-2.0, JMH is GPL-2.0 with the Classpath Exception. The rule must also cover the npm build. |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** Runtime artifacts stay strict. `*-test` artifacts may declare the JUnit Jupiter API (EPL-2.0) as `provided`. Named build-only tools (jqwik, JMH) are allowed and never shipped. Amend `TR-LIC-01`.

**Options considered**

1. Keep the rule as written. Consequence: `rule-engine-test` (RE-F-28) cannot be built; property tests and benchmarks cannot run.
2. The architect's amendment, with an explicit allow-list. Consequence: runtime artifacts stay permissive; tests and benchmarks work.
3. Allow weak copyleft (EPL, MPL) everywhere. Consequence: a runtime dependency under EPL could reach adopters' services, which some bank legal reviews reject.

**Recommendation (product manager).** Option 2, with the allow-list written into `TR-LIC-01` by name (JUnit Jupiter API as `provided` in `*-test` artifacts only; jqwik and JMH in test and build scopes only); a CI check that inspects the dependency tree of every shipped artifact separately from build tools; and the same rule for npm: the shipped cockpit bundle is strict, and named development tools are allowed.

**Rationale.** Adopters' legal reviews look at what ships into their service. JUnit is already on every Java team's test classpath, and `provided` means the host supplies it. Naming each exception keeps the list short and reviewable.

**Consequences if accepted.** `TR-LIC-01` amended; `TR-LIC-02` notice unchanged. X-F-12 CI gate updated.

**Follow-ups.** QA: CI licence check by scope. Architect: the allow-list in the build.

### PD-054 Deprecation length

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | X-F-18 and the vision promise that deprecated APIs overlap one minor release. Is that long enough? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** One minor release and at least six months; removal only in a major release. `TR-API-09`.

**Options considered**

1. One minor release (current). Consequence: with a minor every two to three months, an API can disappear before a bank upgrades once.
2. One minor release and at least six months, removal only in a major release. Consequence: adopters on a semi-annual upgrade cycle never meet a removal by surprise; the maintainers carry deprecated code longer.
3. Twelve months. Consequence: longer carrying cost, little gain over option 2 for semi-annual cycles.

**Recommendation (product manager).** Option 2.

**Rationale.** Banks upgrade libraries in their own release windows, often quarterly or semi-annually. The promise has to fit the slowest adopter we target, and removal in a major release only is the convention Java teams expect.

**Consequences if accepted.** New `TR-API-09`. X-F-18 and the vision success measure "deprecations overlap one minor release" change wording.

**Follow-ups.** Product manager: vision and feature map wording after the decision. Architect: deprecation annotations and the API compatibility check.

### PD-055 PostgreSQL version range

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The engines support PostgreSQL 14 and later. PostgreSQL 14 reaches the end of community support in November 2026 under the five-year policy (the date is to be confirmed on postgresql.org). Which range do we support at 1.0? |
| Raised by | Architect's requirements review (18 Nov 2026), section 4 |
| Decision | Pending product owner |

**Architect's proposal.** PostgreSQL 15 or later from 1.0; 14 tested until 1.0. `TR-EMB-02`.

**Options considered**

1. Keep 14 or later after 1.0. Consequence: we support a version that gets no security fixes upstream.
2. Test 14 until 1.0; 15 or later from 1.0. Consequence: pilots on 14 can start; production adopters move to 15 or later.
3. Drop 14 now. Consequence: a pilot bank on 14 cannot start before it upgrades its database.

**Recommendation (product manager).** Option 2, with two additions: ask the K13 pilot candidates for their PostgreSQL version during M1, and state in the documentation that on 14 we fix engine defects, not database ones.

**Rationale.** Banks upgrade databases slowly, and some run 14 under a cloud provider's paid extended support. Dropping it before 1.0 could cost a pilot; keeping it after 1.0 would tie a frozen API to an unsupported database.

**Consequences if accepted.** `TR-EMB-02` names the range and the CI matrix; X-F-02 and the vision "For" row change wording at 1.0.

**Follow-ups.** Architect: confirm the end-of-life date and the CI matrix. Product manager: the pilot question in M1.

## Pending: rule management UX

### PD-056 Rule management UX direction

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Three directions for rule management were published for review: A, a grid that works like a spreadsheet; B, rules read and edited as sentences; C, a change request page that carries the draft, checks, simulation, diff, approval and history, with an assistant that suggests. Which direction does the cockpit build, and in which release does each part land? |
| Raised by | Product manager, from the published rule management directions and the [cockpit UX design](ux-design.md) |
| Decision | Pending product owner. The product owner asked for a redesigned prototype that shows four paradigms side by side (a spatial canvas with a docked decision table, a quantitative backtest view, a four-eyes governance room, an agentic control tower for escalated agent steps) plus a synthesis view of what lands when. That prototype is not yet delivered; this entry should be decided after the product owner has reviewed it |

**Options considered**

1. Option A only: the grid is the editor, round-tripped through CSV in 0.1 and editable in the browser in 0.2. Consequence: fastest for pricing and risk staff who change many rows; approvers and support staff read cells, not sentences; the accessible editable grid is the hardest widget to build.
2. Option B only: each row is a sentence edited through guided pickers. Consequence: lowest learning curve for approvers and occasional authors; does not scale past about 30 rows; Vietnamese needs its own sentence templates.
3. Option C with the assistant early: the change request page in 0.1 and the assistant drafting rows by M3. Consequence: strongest evidence for approvers; the assistant needs an evaluation set, a hosting decision and compliance sign-off that cannot be done before 1.0; compliance may refuse AI on governed rules.
4. Hybrid of A, B and C without the assistant before 1.0, split by release (recommended):

| Release | Rules | Processes |
|---|---|---|
| Cockpit 0.1 (M1) | Every change lives on a change request page (C, without the assistant). Rows arrive through the CSV round-trip (A). Every row in the diff, the simulation and the explanation shows its plain-language reading (B), returned by the engine in English and Vietnamese | None (the Process Engine ships in M2) |
| Cockpit 0.2 (M2) | The editable grid (A) opens inside the change request, with a "Read as sentences" switch for approvers, and templates for new rule sets | Definition and instance diagrams with the token overlay, one inbox and one incident list across hosts, task forms, agent task inspector, tenant switcher |
| M3 | Replay of logged decisions in the Impact region, stored simulation report on the request, version history and evidence export | Process definition approval with a graph diff; modeller canvas limited to registered steps (first M3 cut, fallback 1.1); heatmap; migration plan as two diagrams |

**Recommendation (product manager).** Option 4. Each part does the job of one persona: the grid for the author who changes many rows, the sentence for the approver and support staff who must understand one row, and the change request for the approver who needs all evidence on one page. Components are shared, so a later change of direction removes or swaps a region, not a screen ([ux-design.md](ux-design.md#what-is-proposed-and-how-it-adjusts)). It assumes PD-043 is accepted as recommended.

**Sub-questions inside this decision**

| # | Question | Recommendation |
|---|---|---|
| 1 | Plain-language readings in 0.1 or 0.2? The UX design shows them in 0.1; the feature map places the readings with the grid shorthand (CK-F-37) in 0.2 under PD-043 | Read-only readings in 0.1 in the diff, the simulation and the explanation, returned by the engine per language; the shorthand with drop-downs in the grid stays 0.2. The feature map splits CK-F-37 once decided. Effort about 3 to 4 days of front end if the engine returns a reading per row (assumption) |
| 2 | One change request per rule set version, or one request that holds changes to several rule sets that go live together? | One request per version in 0.x, so the engine needs no request object; revisit in M3 with pilot evidence |
| 3 | Is simulation evidence stored with the request in 0.1? | No. Stored evidence lands with the simulation report in M3 (RE-F-23, CK-F-20, PD-022); in 0.1 the Impact stage is skipped and the approver runs the samples |
| 4 | What happens when the requested effective date passes while the request waits for approval (gap G3)? | The engine refuses the approval and the requester chooses a new date; a version is never made effective in the past. The page warns before Approve. The architect confirms the engine rule |
| 5 | When, if ever, does the assistant come? | After 1.0 only, as suggestion only: it never applies a change, every suggestion passes the same checks and the same second approver, and it needs an evaluation set, a hosting decision and compliance sign-off first |
| 6 | Who checks the Vietnamese wording? | A native writer from a bank reviews every Vietnamese message and sentence template before Cockpit 0.2 |

**Kill criterion.** In the week 2 M1 sessions (K3), an approver who reads the change request page cannot say which customers are affected, or an author cannot reach `PENDING_APPROVAL` within 15 minutes: then fall back to option 1 for 0.1 and keep the request page for 0.2.

**Consequences if accepted.** [ux-design.md](ux-design.md) status becomes decided; CK-F-37 splits into a 0.1 reading and a 0.2 editing feature; the cockpit SPEC adds the change request page statements.

**Follow-ups.** Product manager: deliver the four-paradigm prototype for review, then the feature map split. Architect: engine readings per language (UX-API-01) and the G3 rule. Documentation: Vietnamese reviewer.

## Decided: standards direction

### PD-057 Full BPMN 2.0 and DMN support, no CMMN

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Do the engines support BPMN 2.0 and DMN in full, or only the documented subset of PD-034 and principle 9? Is CMMN in scope? |
| Raised by | Product owner |
| Decision | Decided by the product owner, 2026-11-18: BPMN 2.0 and DMN are fully supported on the roadmap; full support is not required for the MVP (M1, M2); the architecture is designed now so the engines reach it without a rewrite; CMMN is out of scope. Recorded as [ADR-0017](../adr/0017-full-bpmn-and-dmn-support-no-cmmn.md) (accepted) |

**What "full support" means.** BPMN 2.0.2 Process Modeling Conformance and Process Execution Conformance, and DMN 1.5 Conformance Level 3, each with a published deviation register ([standards compliance](../architecture/standards-compliance.md) sections 1 and 12). Proof is the BPMN MIWG round trip, a HORUS executable conformance suite and the DMN TCK (`TR-STD-10`, `TR-STD-11`, `TR-STD-20`, `TR-STD-21`).

**Options considered** (from ADR-0017)

1. Full BPMN 2.0 and DMN, staged after 1.0 in S1 (1.1), S2 (1.2) and S3 (1.3), with foundations in M1 and M2 (chosen). Consequence: standard models in and out; evaluations that require the standards become winnable; about 60 engineer-weeks after 1.0 (assumption) and a small cost in M1 and M2.
2. Keep the subset permanently (PD-034 as decided). Consequence: smallest engines and support surface; every evaluation that requires BPMN or DMN conformance is lost and the lock-in objection stays half answered.
3. Full support within M1 to M3. Consequence: not possible within the PD-042 dates; M2 and M3 are at capacity.
4. Include CMMN. Consequence: a second execution model for little use in the target banks.

**Product manager's view.** Agree, with three conditions that are applied in the roadmap. First, conformance is not a moat: every established BPM and decision vendor already claims it, so it removes a reason to lose rather than adding a reason to win. Positioning keeps leading with governed change inside the host transaction and guarded agent steps; the standards line is "standard models in and out, with a published deviation register". Second, no conformance claim is made before the evidence exists: "BPMN 2.0 and DMN conformance" is said only for the stage whose suites are green (`TR-STD-05`). Third, the demand behind S2 and S3 is tested before they start (kill assumption K20 in the [roadmap](roadmap.md#kill-assumptions)).

**Rationale.**

- Positioning. Bank architecture reviews often carry a checklist line for BPMN and DMN. A documented subset answered the switching path (PD-034) but not the checklist; full support answers both and lets a team take its models away, which is the strongest answer to lock-in.
- Personas. The host developer (process PRD S1) brings BPMN files from existing engines; the rule author (rule PRD S2) may hold DMN models from a modeller. Both can now arrive without rewriting.
- Principles. Full support is implemented inside the principles: library in the host JVM, host-owned data, determinism, no code in definitions, Async Wait-State for every network call, maker-checker. Script tasks run only FEEL or our expression language; service tasks bind only to registered handlers; the host owns every transport.

**Consequences.**

- Supersedes in part [PD-034](#pd-034-process-notation-own-json-canonical-bpmn-subset-for-import-and-export): the BPMN subset is the M3 scope, no longer the end state, and "the vision's 'never a full BPMN 2.0 implementation' stays" no longer holds. Whether the BPMN and DMN semantic models replace our JSON as the canonical model is pending as PD-060.
- Supersedes in part [PD-039](#pd-039-principle-9-wording): the limit on BPMN scope in principle 9 no longer holds. The new wording is pending as PD-063.
- Supersedes in part [PD-041](#pd-041-conflict-vision-non-goal-diagram-modeller): "Teams that need full BPMN 2.0, DMN FEEL or CMMN" is no longer a non-goal; CMMN stays one. The modeller non-goal ("any canvas element the engine cannot run") stays.
- The PD-032 exclusion "full BPMN 2.0 symbols" holds until S1 (1.1), which adds the BPMN and DMN symbol sets to the cockpit.
- New requirements `TR-STD-01` to `TR-STD-27` in [requirements.md](requirements.md). Do-now changes DN-01 to DN-11 are in the M1 and M2 designs; P1 to P9 of the standards compliance page are pending as PD-058 to PD-066.
- Roadmap: a post-1.0 standards track S1 to S3, dates pending PD-062; feature map: 18 standards-track features (design preview), 10 foundation features in M1 to M3 and 3 more pending PD-058 to PD-060.

**Follow-ups.** Product manager: roadmap, feature map, vision and PRDs (applied below). Documentation: wiki Principles row 9, Concepts, FAQ and Roadmap pages, after PD-063. Architect: ADR-0018 to ADR-0020 through PD-058 to PD-061. QA: plans for the MIWG, TCK and executable conformance suites.

**Applied, 2026-11-18.** [roadmap.md](roadmap.md) (standards track, do-now items, gates), [feature-map.md](feature-map.md), [vision.md](vision.md), [prd-rule-engine.md](prd-rule-engine.md), [prd-process-engine.md](prd-process-engine.md), [prd-cockpit.md](prd-cockpit.md), [README.md](README.md).

## Pending: standards foundations and track

The architect's [standards compliance](../architecture/standards-compliance.md) page section 9.2 lists nine larger choices (P1 to P9) that follow from PD-057. Each is one entry below. Gates: PD-058 and PD-060 must be decided before M1 code (7 Dec 2026); PD-059 before M2 code (18 Jan 2027).

### PD-058 FEEL null semantics in the expression language from M1

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Should `horus-expr-v1` follow FEEL null logic from M1, so that every v1 expression has a FEEL equivalent with the same result? It changes two cases of `RLE-006`: `x != 'GOLD'` with `x` null becomes true, and `not(x < 5)` with `x` null becomes null (the condition does not match) |
| Raised by | Architect, standards compliance P1 ([ADR-0019](../adr/0019-feel-strategy-horus-built-deterministic-feel.md), proposed) |
| Decision | Pending product owner. Gate: before M1 code |

**Options considered**

1. Accept: FEEL three-valued logic from M1, with a differential test against a reference FEEL implementation in test scope (`TR-STD-02`, `RLE-021`). Consequence: about 1 day plus 3 days for the test harness in M1 (assumption); `RLE-006` and the wiki expression page change; every logged decision stays replayable under FEEL.
2. Keep today's null rule and align later. Consequence: logged decisions depend on today's semantics after M1 ships; aligning later breaks replay (`TR-DET-05`) or leaves v1 forever different from FEEL.

**Recommendation (product manager).** Option 1. The cost is four days before any adopter exists; afterwards it is a replay break in a product whose promise is replay. The author-facing change is small: `x != 'GOLD'` matching a missing tier is what FEEL users expect, and the validator can warn on a nullable input used with `!=`.

**Consequences if accepted.** RE-F-41 joins M1; RE-F-03 wording changes; `RLE-006` changes; the wiki expression page changes with the same pull request.

**Follow-ups.** Architect: `RLE-006` and the harness. Documentation: wiki expression page.

### PD-059 Execution tree with scope tokens in M2

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Should the M2 Process Engine store a root scope token per instance and one per `saga`, with joins and compensation keyed by scope token, instead of a flat text `scope_id`? |
| Raised by | Architect, standards compliance P2 ([ADR-0020](../adr/0020-execution-tree-scopes-and-event-subscriptions.md), proposed for the M2 part) |
| Decision | Pending product owner. Gate: before M2 code |

**Options considered**

1. Accept in M2. Consequence: about 4 days in M2 (assumption); subprocesses, event subprocesses and multi-instance in S1 add scopes without changing joins or compensation.
2. Flat scopes in M2, scope tokens in S1. Consequence: joins and compensation are rewritten in S1 and every in-flight instance needs a data migration at 1.1.

**Recommendation (product manager).** Option 1. A data migration of in-flight instances in banks' production databases is the kind of upgrade adopters refuse; four days in M2 avoid it. If M2 slips, this is not on the cut list; the existing cuts (PD-017, PD-042) apply first.

**Consequences if accepted.** PE-F-58 joins M2; process engine DESIGN and the token DDL change before M2 code.

**Follow-ups.** Architect: DESIGN and DDL.

### PD-060 Canonical model: BPMN and DMN semantic models

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Should the canonical internal model be the BPMN 2.0 and DMN semantic models, with our JSON as their canonical serialisation and BPMN and DMN XML as lossless serialisations of the same model? This replaces the notation part of PD-034 |
| Raised by | Architect, standards compliance P3 ([ADR-0018](../adr/0018-canonical-model-bpmn-and-dmn-semantic-models.md), proposed) |
| Decision | Pending product owner. Gate: before M1 code (the M1 rule set model), and before M2 code (the process model) |

**Options considered**

1. Our model canonical, with a lossless BPMN round trip. Consequence: lowest cost now; every BPMN feature is designed twice, once in our model and once in the mapping, for the life of the product.
2. BPMN and DMN semantic models canonical; our JSON as serialisation; v1 documents stay valid forever as a compact profile (recommended by the architect). Consequence: about 3 days in M1 and 5 days in M2 (assumption); one mapping per serialisation; hash and diff unchanged in kind.
3. BPMN or DMN XML text canonical. Consequence: unstable hashes over XML and a rewrite of the M2 definition handling.

**Recommendation (product manager).** Option 2. Users see no change: JSON and YAML authoring, our step types (`agentTask`, guardrails, async mode) as `horus` extensions, and hashes that never change (`TR-STD-03`). What changes is that the M3 BPMN reader and every later stage build on a model that can hold full BPMN, so S1 is not a rewrite of the definition layer.

**Consequences if accepted.** PD-034 is superseded in its notation part; X-F-28 joins M1 and M2; `TR-DEF-01` drops its "if ADR-0018 is accepted" wording.

**Follow-ups.** Architect: ADR-0018 to accepted; module DESIGN documents.

### PD-061 HORUS-built FEEL

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Is FEEL built here, deterministic and sandboxed, on the same typed tree and evaluator as today's language, rather than embedding an existing FEEL engine? |
| Raised by | Architect, standards compliance P4 ([ADR-0019](../adr/0019-feel-strategy-horus-built-deterministic-feel.md), proposed) |
| Decision | Pending product owner. Needed before S1 planning |

**Options considered**

1. HORUS-built FEEL (recommended by the architect). Consequence: about 22 engineer-weeks across S1 to S3 (S-FEEL core 6, full FEEL 12, TCK completion 4; assumption); determinism, money and the 100 us compiled path are designed in; no runtime dependency.
2. Embed an existing Apache 2.0 FEEL engine. Consequence: mature and high TCK score; adds a large runtime to every host classpath, its own value model and clock, interpretation per call that puts the 100 us target at risk, no money type and no fail-closed error mode. It would be the product itself, since the evaluator is the Rule Engine.
3. Keep only `horus-expr-v1` with a FEEL subset translated at import. Consequence: DMN Level 3 is unreachable; contradicts PD-057.

**Recommendation (product manager).** Option 1, with the existing engine used only as a test oracle (`TR-STD-27`). Determinism and fail-closed behaviour are part of what we sell to banks; an evaluator we do not control cannot carry that promise. Kill assumption K21 tests the S-FEEL estimate before full FEEL starts.

**Consequences if accepted.** RE-F-45 to RE-F-47 are built on it; ADR-0019 accepted.

**Follow-ups.** Architect: ADR-0019 to accepted; S1 plan for the S-FEEL core.

### PD-062 Release targets for the standards track

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | When do S1 (1.1), S2 (1.2) and S3 (1.3) ship, and with what capacity? |
| Raised by | Architect, standards compliance P5 |
| Decision | Pending product owner |

Effort (assumption): S1 about 24 engineer-weeks (BPMN 12, DMN 8, cockpit 4), S2 about 28 (BPMN 14, DMN 12, cockpit 2), S3 about 8 (BPMN 6, DMN 2); about 60 in total.

**Options considered**

1. Fix dates now. Consequence: a public promise before the team's throughput in M1 to M3 is known.
2. Publish order, content and exit criteria now; fix dates at the M3 exit review (21 May 2027) from measured throughput. Consequence: no dates on the public roadmap for six months.
3. Fix only S1 now. Consequence: one promise to keep; S2 and S3 follow the K20 demand test.

**Recommendation (product manager).** Option 2. Indicative only, at three engineers full time: S1 about 8 weeks, S2 about 9 to 10, S3 about 3, which would put 1.1 around August 2027, 1.2 around November 2027 and 1.3 around December 2027 (assumption, not a target). S2 and S3 start only if K20 holds.

**Consequences if accepted.** The roadmap keeps "dates pending PD-062" until the M3 exit review.

**Follow-ups.** Product manager: dates proposal at the M3 exit review.

### PD-063 Principle 9 wording and the standards non-goals

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | Principle 9 (PD-039) still says a BPMN subset is for import and export; ADR-0017 proposes new wording. Which wording do the public documents use? |
| Raised by | Architect, standards compliance P6 |
| Decision | Pending product owner. The vision and PRD non-goals were changed under PD-057 to state the decided stance; only the principle wording waits |

**Options considered**

1. ADR-0017 wording: "BPMN 2.0 and DMN are the models. Our JSON is their canonical serialisation and always has a diagram. What each release executes is exactly what the standards compliance page lists." Therefore: "Teams bring and take standard models; the engines stay deterministic and code-free; every deviation is published." Consequence: assumes PD-060 option 2.
2. Wording that holds whatever PD-060 decides: "Standard models in and out. BPMN 2.0 and DMN are imported, displayed and exported in full; what each release executes is exactly what the standards compliance page lists, with its deviations. No CMMN." Therefore unchanged in spirit: "Supported is what is published."

**Recommendation (product manager).** Option 1 if PD-060 is accepted, option 2 otherwise. Both keep the useful half of the old principle: what is supported is exactly what is published.

**Consequences if accepted.** Wiki Principles row 9, Concepts and FAQ; the vision table row that cites principle 9.

**Follow-ups.** Documentation: wiki pages in the same change as the decision.

### PD-064 BPMN MIWG test data licence

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | The BPMN MIWG reference models are published under CC BY 3.0, outside the Apache 2.0, MIT and BSD list of `TR-LIC-01`. May they be used as test data under the PD-053 rule for test and build tools? |
| Raised by | Architect, standards compliance P7 |
| Decision | Pending product owner, after PD-053 |

**Options considered**

1. Allow as test data: fetched at build time at a pinned commit, with attribution, never shipped in an artifact. Consequence: `TR-STD-10` can be verified; the licence allow-list of PD-053 names one data set.
2. Refuse. Consequence: no MIWG evidence; the round-trip claim rests on our own models only, which evaluators will not accept as proof.

**Recommendation (product manager).** Option 1, written into the PD-053 allow-list by name. Attribution is the only obligation of CC BY and nothing reaches adopters' services.

**Consequences if accepted.** PD-053 allow-list gains the MIWG data set; X-F-27 can publish MIWG results.

**Follow-ups.** QA: build-time fetch with attribution. Architect: allow-list entry.

### PD-065 Choreography and conversation diagrams not targeted

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | BPMN choreography and conversation diagrams are part of BPMN 2.0.2 but not of the process conformance types we target. Are they out of scope? |
| Raised by | Architect, standards compliance P8 |
| Decision | Pending product owner |

**Options considered**

1. Not targeted: a file that contains them is imported with the choreography kept byte for byte in the stored source document and a warning; listed in the deviation register. Consequence: no work; the claim stays precise.
2. Display them. Consequence: cockpit symbols with no execution meaning and no user we know of.

**Recommendation (product manager).** Option 1. No target bank models choreographies for execution; the source document keeps them, so nothing is lost if a user ever asks.

**Consequences if accepted.** Deviation register row confirmed.

**Follow-ups.** None beyond the register.

### PD-066 FEEL error mode default

| Field | Value |
|---|---|
| Date | 2026-11-18 |
| Question | FEEL defines many errors as `null`. Which `errorMode` is the default: `spec` (FEEL semantics, the error recorded as a warning, outcome `OK_WITH_WARNINGS`) or `strict` (today's `EVALUATION_ERROR`)? |
| Raised by | Architect, standards compliance P9 |
| Decision | Pending product owner. Needed before S2 |

**Options considered**

1. `strict` for v1 rule sets, `spec` for imported DMN models; guardrails fail closed in both modes (recommended by the architect). Consequence: existing behaviour never changes; imported models behave as their authors expect; the TCK runs in `spec`.
2. `strict` everywhere. Consequence: imported DMN models fail where other engines return `null`, which breaks the "bring your models" promise.
3. `spec` everywhere. Consequence: v1 rule sets change behaviour silently, which breaks replay.

**Recommendation (product manager).** Option 1, with the mode shown per decision model in the cockpit and recorded with each decision, so an auditor sees why a `null` was not an error.

**Consequences if accepted.** `TR-STD-23` wording; cockpit decision view shows the mode.

**Follow-ups.** Architect: rule engine DESIGN.
