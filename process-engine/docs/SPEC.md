# Process Engine: Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated test named by its spec ID (`TR-QA-01`). Each statement carries the milestone that ships it, matching [`docs/product/roadmap.md`](../../docs/product/roadmap.md); a statement whose milestone has not been reached is not a release gate yet. Money values use the contract in `rule-engine/docs/DESIGN.md` section 3.4.

---

## Instance Start, Idempotency & Concurrency

### PRE-001 Starting a process pins its effective definition version
- **Traces to:** `TR-DET-01`
- **Milestone:** M2
- **Given:** Process `loan-origination` has `v1` active; instance `I1` starts on `v1`, then `v2` is approved and becomes effective.
- **When:** Complete steps on `I1`, and start new instance `I2`.
- **Then:** `I1` executes exclusively against `v1` until completion or explicit migration; `I2` pins `v2`.
- **Example:** `I1 starts on v1 -> v2 approved and effective -> I1 continues on v1; I2 runs on v2`.
- **Test ID:** `PRE_001_running_instance_pins_definition_version`

### PRE-002 Business key enforces uniqueness and idempotent start returns existing instance
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-02`
- **Milestone:** M2
- **Given:** Tenant `bank-a`, process `loan-origination` v1.
- **When:** Caller posts `POST /api/bpm/processes/v1/instances` with `businessKey: "LN-2027-000123"` and `Idempotency-Key: k1`, then repeats with `businessKey: "LN-2027-000123"` (with `k1` or new key `k2`).
- **Then:** First call returns `201 Created` (`instanceId = I1`); replay returns the existing `I1` (`201` on same `k1`, `200` on new `k2`) with **only 1 instance row** in `pre_instance`.
- **Example:** `Same businessKey in tenant-a -> returns existing I1; same businessKey in tenant-b -> creates new I2`.
- **Test ID:** `PRE_002_business_key_and_idempotency_prevent_duplicate_starts`

### PRE-003 Stale task completion fails with optimistic locking conflict
- **Traces to:** `TR-IDEM-03`
- **Milestone:** M2
- **Given:** `userTask` `T1` at `lockVersion = 1` (`ETag: "1"`). Two operators fetch `T1` concurrently.
- **When:** Operator A completes `T1` with `If-Match: "1"` (succeeds, `lockVersion -> 2`), then Operator B attempts to complete `T1` with `If-Match: "1"`.
- **Then:** Operator B receives `409 Conflict` (`VERSION_CONFLICT`) with no duplicate transition or history entry.
- **Example:** `User A complete(T1, lockVersion=1) -> 200; User B complete(T1, lockVersion=1) -> 409 VERSION_CONFLICT`.
- **Test ID:** `PRE_003_stale_task_completion_returns_conflict`

---

## Gateways, Parallel Execution & Timers

### PRE-004 Exclusive gateway routes deterministically without fallthrough ambiguity
- **Traces to:** `TR-DET-01`
- **Milestone:** M2
- **Given:** `exclusiveGateway` with condition branches `amount >= 100000000 VND -> senior-approval`, `default -> auto-approve`.
- **When:** Instance evaluates gateway with `amount = Money.ofMinor("VND", 150000000)`.
- **Then:** Single active token advances to `senior-approval`; `auto-approve` is not activated.
- **Example:** `amount=150000000 VND -> senior-approval`.
- **Test ID:** `PRE_004_exclusive_gateway_routes_single_matching_branch`

### PRE-005 Concurrent parallel gateway join prevents lost wakeup under multi-replica race
- **Traces to:** `TR-PERF-05`
- **Milestone:** M2
- **Given:** `parallelGateway` forks into Branch A (`credit-bureau`) and Branch B (`fraud-check`) which converge at `join-checks`.
- **When:** Pod 1 finishes Branch A and Pod 2 finishes Branch B **at the exact same millisecond**.
- **Then:** Row-level lock on `pre_instance` serializes the two join evaluations so exactly **one** post-join token advances to `underwrite` (never zero lost wakeups, never two duplicate advances).
- **Example:** `Concurrent Thread-1 (A) + Thread-2 (B) -> exactly 1 downstream token created`.
- **Test ID:** `PRE_005_parallel_gateway_join_prevents_concurrent_lost_wakeup`

### PRE-006 Replica-safe timer claims via SKIP LOCKED and recovers crashed leases
- **Traces to:** `TR-PERF-05`
- **Milestone:** M2
- **Given:** `pre_timer` row due at `T0`. Pod 1 claims it (`status = CLAIMED`, `locked_until = T0 + 60s`) and crashes before completing.
- **When:** Pod 2 polls at `T0 + 10s` (still leased), and again at `T0 + 65s` (lease expired).
- **Then:** At `T0 + 10s`, Pod 2 skips the locked timer; at `T0 + 65s`, Pod 2 reclaims the expired lease and fires the timer once.
- **Example:** `Pod 1 crash at T0 -> Pod 2 skips at T0+10s, recovers at T0+65s`.
- **Test ID:** `PRE_006_timer_lease_recovers_after_worker_crash`

---

## Human Tasks, Four-Eyes & Business Calendar

### PRE-007 Four-eyes approval blocks requester or previous actor from completing approval task
- **Traces to:** `TR-SEC-04`
- **Milestone:** M2
- **Given:** Process started or prior `submit-application` step completed by `alice`; next `userTask` has `fourEyes: true` (or lists `alice` in `forbidden_users`).
- **When:** `alice` tries to claim or complete the approval task via Java API or REST.
- **Then:** Engine rejects with `403 SELF_APPROVAL_FORBIDDEN`; only another authorized user (`bob`) can complete it.
- **Example:** `submit=alice, approve=alice -> 403; approve=bob -> 200`.
- **Test ID:** `PRE_007_four_eyes_blocks_same_actor_approval`

### PRE-008 BusinessCalendar SPI computes SLA deadlines across weekends and holidays
- **Traces to:** `TR-CAL-01`, `TR-CAL-02`
- **Milestone:** M2
- **Given:** Tenant calendar `vn-banking-hours` (`Mon-Fri 08:00-17:00 Asia/Ho_Chi_Minh`, holiday `2027-01-01`). Task created `2026-12-31T15:00:00+07:00` with `sla: PT4H`.
- **When:** Engine creates the `userTask` and computes `due_at`.
- **Then:** Consumes 2 hours (`15:00-17:00` on Dec 31), skips Jan 1 holiday + weekend (Jan 2–3), and sets `due_at = 2027-01-04T10:00:00+07:00`. Later calendar edits do not mutate this already-computed `due_at`.
- **Example:** `Dec 31 15:00 + PT4H (biz) -> Jan 4 10:00 +07:00`.
- **Test ID:** `PRE_008_business_calendar_skips_holidays_and_weekends`

---

## `agentTask` Async Wait-State, Guardrails & Audit

### PRE-009 `agentTask` releases DB connections before invoking adapter (Async Wait-State)
- **Traces to:** `TR-AGT-03`, `TR-EMB-04`
- **Milestone:** M2
- **Given:** Host configured with a 2-connection HikariCP pool; `agentTask` calls a mock adapter sleeping `2 seconds`.
- **When:** Start 10 concurrent process instances reaching `agentTask` while another thread executes normal DB queries.
- **Then:** All 10 adapters run concurrently without exhausting the 2-connection pool (`TX 1` commits claim lease `< 10 ms` before adapter sleep; `TX 2` commits output after adapter returns).
- **Example:** `10 concurrent 2s agent calls on 2-conn pool -> 0 pool timeouts, total wall time ~2.1s`.
- **Test ID:** `PRE_009_agent_task_executes_outside_db_transaction`

### PRE-010 `agentTask` validates JSON Schema and evaluates Rule Engine guardrail before advancing
- **Traces to:** `TR-AGT-01`
- **Milestone:** M2
- **Given:** `assess-documents` `agentTask` with `outputSchema` requiring `{ riskScore: number, recommendation: string }`, `guardrails: { decision: agent-output-guardrails, onViolation: route-to-human }`, and guardrail rule set `agent-output-guardrails` (`hitPolicy: first`; `R2`: `riskScore >= 70 -> allow: false, reasons: ['HIGH_RISK_SCORE']`; default row `allow: true`).
- **When:** Adapter returns `{ riskScore: 85, recommendation: "APPROVE" }`.
- **Then:** Output passes schema validation, Rule Engine returns `allow = false`, `reasons = ["HIGH_RISK_SCORE"]` and matched rule `R2`, and engine creates a maker-checker `userTask` carrying inputs, agent output, `reasons`, and matched rule IDs instead of auto-advancing.
- **Example:** `riskScore=85 -> allow=false, R2, HIGH_RISK_SCORE -> userTask created for compliance-reviewer`.
- **Test ID:** `PRE_010_agent_task_guardrail_violation_routes_to_human`

### PRE-011 `agentTask` invalid JSON output or timeout retries with backoff then raises incident
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-OPS-03`
- **Milestone:** M2
- **Given:** `agentTask` with `timeout: PT1S`, `retry: { max: 2, backoff: PT1S }`. Adapter exceeds timeout or returns malformed JSON failing `outputSchema`.
- **When:** Initial attempt and 2 retries fail.
- **Then:** Engine retries twice and on the 3rd failure opens a `pre_incident` with `error_code = AGENT_SCHEMA_INVALID` or `AGENT_TIMEOUT`, `retry_count = 2`, pausing the token for ops retry/resolve.
- **Example:** `3 failed attempts -> 1 OPEN pre_incident with cause & retry_count=2`.
- **Test ID:** `PRE_011_agent_task_exhausted_retries_raises_incident`

### PRE-012 `agentTask` logs masked inputs, output, guardrail decision ID, and human override
- **Traces to:** `TR-AGT-02`, `TR-OBS-01`
- **Milestone:** M2
- **Given:** `agentTask` with `nationalId` marked `mask: true` routes to human `userTask`; the adapter returns labels `{ "model": "scoring-2026-10", "promptVersion": "p7", "vendor": "in-house" }`; reviewer changes `recommendation` from `"APPROVE"` to `"REJECT"`. Second case: an adapter result with 17 labels while `agents.labels.max-count` is 16.
- **When:** Reviewer completes the `userTask`.
- **Then:** `pre_history` records hashed `nationalId` (no plaintext), raw agent output, `guardrailDecisionId`, adapter latency, the three labels unchanged, and `human_override` diff for Eval replay; the engine takes no decision from a label. In the second case the attempt fails with `AGENT_LABELS_INVALID` and is retried like an invalid output (`PRE-011`).
- **Example:** `Override APPROVE -> REJECT logged in pre_history with guardrailDecisionId`.
- **Test ID:** `PRE_012_agent_task_audits_masked_io_and_human_override`

---

## Saga Compensation & Instance Migration

### PRE-013 Failed step triggers reverse LIFO compensation and propagates attempt ID
- **Traces to:** `TR-COMP-01`, `TR-COMP-02`, `TR-COMP-03`, `TR-IDEM-04`
- **Milestone:** M2
- **Given:** Process runs `reserve-funds` (comp: `releaseFunds`) → `open-account` (comp: `closeAccount`) → `notify-partner` (`onFailure: compensate`).
- **When:** `notify-partner` exhausts retries and fails.
- **Then:** Engine executes `closeAccount` first, then `releaseFunds` second (strict reverse order), passing unique `attemptId` to each compensate handler. If `compensate: manual` is reached, engine opens an incident instead of skipping.
- **Example:** `Forward [A, B, C(fail)] -> Compensate [comp(B), comp(A)] in LIFO order`.
- **Test ID:** `PRE_013_failure_executes_reverse_saga_compensation`

### PRE-014 Migration validate dry-run detects unmapped steps without mutating instances
- **Traces to:** `TR-MIG-01`
- **Milestone:** M2
- **Given:** 500 instances running on `v1`, including 50 paused on `legacy-review` which was removed in `v2`.
- **When:** Call `POST /api/bpm/processes/v1/migrations/{id}:validate` without mapping `legacy-review`.
- **Then:** Returns dry-run report listing 50 unmapped instances at `legacy-review` and marks plan invalid; 0 rows in `pre_instance` are modified. Resolving them by an explicit mapping for a renamed step or by cancellation is M3 (`TR-MIG-03`) and is not part of this statement.
- **Example:** `Validate v1->v2 missing legacy-review -> conflict report, 0 instances mutated`.
- **Test ID:** `PRE_014_migration_validate_reports_unmapped_steps_without_mutation`

### PRE-015 Batched migration isolates per-instance failures without stalling healthy instances
- **Traces to:** `TR-MIG-02`
- **Milestone:** M2
- **Given:** Validated migration plan `v1 -> v2` across 250 running instances where instance `#120` hits a transient lock conflict or mapping invariant error.
- **When:** Execute `POST /api/bpm/processes/v1/migrations/{id}:execute`.
- **Then:** 249 healthy instances pin to `v2` with `MIGRATED` `pre_history` rows; instance `#120` gets an incident without rolling back the other 249 instances.
- **Example:** `250 instances (1 corrupt) -> 249 migrated to v2, 1 incident raised`.
- **Test ID:** `PRE_015_migration_execute_isolates_instance_failure`

---

## Deploy-Time Contracts & Leases

### PRE-016 Deploy rejects an `agentTask` whose guardrail rule set breaks the guardrail contract
- **Traces to:** `TR-AGT-01`
- **Milestone:** M2
- **Given:** Definition `kyc-onboarding` v1 with `agentTask` `assess-documents` and `guardrails.decision: agent-output-guardrails`. Case A: the rule set's effective version declares outputs `allow: boolean` and `reasons: list<string>` with `hitPolicy: first`. Case B: it declares `decision: string` and no `allow`. Case C: it declares `allow: boolean` with `hitPolicy: collect`. Case D: no rule set of that name exists in the tenant.
- **When:** Caller posts the definition to `POST /api/bpm/processes/v1/definitions`.
- **Then:** Case A creates a draft (`201`, `status: DRAFT`). Cases B, C and D return `422 GUARDRAIL_CONTRACT_INVALID` naming the step id and the failed check; no `pre_definition` row is written. The same check runs again at `:request-approval` and `:approve`.
- **Example:** `outputs [decision: string] -> 422 GUARDRAIL_CONTRACT_INVALID at step assess-documents`.
- **Test ID:** `PRE_016_deploy_rejects_guardrail_rule_set_breaking_contract`

### PRE-017 Async job lease is the step timeout plus the lease margin, so a live attempt is never taken over
- **Traces to:** `TR-AGT-03`, `TR-PERF-05`
- **Milestone:** M2
- **Given:** `agentTask` with `timeout: PT2S`, `jobs.lease-margin = 30s`; the adapter on Pod 1 returns after 1.9 s. In a second run Pod 1 crashes right after TX1.
- **When:** Pod 2 polls continuously from the claim instant `T0`.
- **Then:** TX1 sets `locked_until = T0 + 32s`. In the first run Pod 2 never claims the job, and Pod 1's TX2 records the result once. In the crash run Pod 2 skips the job until `T0 + 32s`, then reclaims it with `attempt = 2` and a new `attemptId`.
- **Example:** `timeout PT2S -> lease 32s; live attempt completes at 1.9s untouched; after crash reclaimed at T0+32s`.
- **Test ID:** `PRE_017_async_lease_is_timeout_plus_margin`

---

## Definition Lifecycle and Maker-Checker (ADR-0012)

### PRE-018 Importing a definition creates a draft, never an active version
- **Traces to:** `TR-DEF-03`, `TR-DEF-04`
- **Milestone:** M2
- **Given:** Tenant `bank-a` has `kyc-onboarding` v1 `ACTIVE`; profile is not `dev`.
- **When:** A pipeline identity with `processes:deploy` posts a valid document to `POST /api/bpm/processes/v1/definitions`.
- **Then:** Response `201` with `version: 2`, `status: DRAFT`, `source: IMPORT` and `definitionHash`; v1 stays the effective version; `POST /instances` still starts on v1.
- **Example:** `import kyc-onboarding -> 201 {version: 2, status: DRAFT}; start -> pinned to v1`.
- **Test ID:** `PRE_018_import_creates_draft_not_active_version`

### PRE-019 No maker of a definition version can approve it
- **Traces to:** `TR-SEC-04`, `TR-DEF-03`, PD-096
- **Milestone:** M2
- **Given:** `kyc-onboarding` v2 was imported by `carol` and is `PENDING_APPROVAL`, requested by `alice`. `alice`, `bob` and `carol` hold `processes:approve`. Setting `lifecycle.approver-must-differ-from` is unset (default `makers`). Second case: the host sets `requester`. Once draft editing exists, a third case: `dave` edited the draft and holds `processes:approve`.
- **When:** `alice` and `carol` each call `:approve`, through REST and through `DefinitionService.approve`; then `bob` calls `:approve` with `If-Match: "<lockVersion>"`. In the second case, `carol` calls `:approve`. In the third case, `dave` calls `:approve`.
- **Then:** Under `makers`, `alice` (the requester) and `carol` (the importer) each get `403 SELF_APPROVAL_FORBIDDEN` (Java: `SelfApprovalForbiddenException`), with a detail naming why, and nothing is written. `bob` gets `200`, `status: ACTIVE`, `approvedBy: bob`. Under `requester`, `carol` may approve, `alice` still gets `403`, the start-up log has one WARN naming the relaxed setting, and the approval history row records `approverMustDifferFrom: requester`. In the third case `dave` gets `403` under `makers`. `/api/bpm/meta` lists `lifecycle.approver-must-differ-from` with the active value.
- **Example:** `makers: requester alice -> 403, importer carol -> 403, bob -> 200 ACTIVE; requester mode: carol -> 200, alice -> 403`.
- **Test ID:** `PRE_019_no_maker_of_a_definition_version_can_approve`

### PRE-020 An approved version takes effect on its effective date and retires the previous one
- **Traces to:** `TR-DEF-03`, `TR-DET-01`
- **Milestone:** M2
- **Given:** `kyc-onboarding` v1 `ACTIVE`; v2 requested with `effectiveFrom: 2027-04-01T00:00:00+07:00` and approved on 2027-03-28 by `bob`. Clock is a `TestClock`.
- **When:** Instances are started on 2027-03-31 23:59 and on 2027-04-01 00:01 (`+07:00`).
- **Then:** The first instance pins v1, the second pins v2. At the effective time v1 becomes `RETIRED` and `processes.definition.retired.v1` is published once with `supersededBy: 2`; instances already on v1 continue on v1.
- **Example:** `start at 03-31 23:59 -> v1; start at 04-01 00:01 -> v2; v1 RETIRED once`.
- **Test ID:** `PRE_020_effective_date_switches_version_and_retires_previous`

### PRE-021 Reject returns a version to draft; an older draft cannot overtake a newer active version
- **Traces to:** `TR-DEF-03`
- **Milestone:** M2
- **Given:** v3 `PENDING_APPROVAL` requested by `alice`; separately v4 is `ACTIVE` while v3 is still pending.
- **When:** `bob` rejects v3 with reason "missing fraud check"; in the second case `bob` approves v3.
- **Then:** The reject returns `200`, `status: DRAFT`, `decision_comment` stored, `processes.definition.rejected.v1` published; a reject without a reason is `400 INPUT_INVALID`. In the second case the approve returns `409 VERSION_SUPERSEDED` and v3 stays `PENDING_APPROVAL`.
- **Example:** `reject v3 -> DRAFT; approve v3 while v4 ACTIVE -> 409 VERSION_SUPERSEDED`.
- **Test ID:** `PRE_021_reject_returns_draft_and_superseded_approval_refused`

### PRE-022 Outside the dev profile the starter refuses auto-approval and disabled maker-checker
- **Traces to:** `TR-SEC-11`
- **Milestone:** M2
- **Given:** Five start-ups: (a) profile `prod`, `horus.bpm.processes.definitions.auto-approve=true`; (b) profile `prod`, `horus.bpm.processes.maker-checker.enabled=false`; (c) profile `dev`, `definitions.auto-approve=true` and one classpath definition; (d) profiles `dev,prod`, `definitions.auto-approve=true`; (e) profile `dev`, the removed key `horus.bpm.processes.definitions-auto-approve=false`.
- **When:** The host starts.
- **Then:** (a), (b) and (d) fail to start with a message naming the property and the `dev`-only rule. (e) fails to start with a message naming the removed key and its replacement, whatever its value. (c) starts; the classpath definition is `ACTIVE` with `approvedBy: system:dev-auto-approve`, a WARN log line names the tenant and definition key, and a definition imported afterwards through `POST /definitions` is auto-approved the same way.
- **Example:** `prod + auto-approve -> start-up fails; dev,prod + auto-approve -> fails; legacy key set -> fails; dev + auto-approve -> ACTIVE by system:dev-auto-approve`.
- **Test ID:** `PRE_022_dev_only_relaxations_refused_outside_dev`

### PRE-023 Turning off cockpit drafts blocks authoring endpoints, not imports
- **Traces to:** `TR-DEF-04`
- **Milestone:** M2
- **Given:** `horus.bpm.processes.authoring.cockpit-drafts=false`; `carol` holds `processes:author`; the pipeline identity holds `processes:deploy`.
- **When:** `carol` calls `POST /definitions/kyc-onboarding/drafts` and `PUT /definitions/kyc-onboarding/versions/5`; the pipeline posts `POST /definitions`; `carol` saves a layout on v4.
- **Then:** `carol`'s draft calls return `403 DRAFT_AUTHORING_DISABLED` and write nothing; the import returns `201 DRAFT`; the layout save returns `200` (layout is not a draft). `GET /api/bpm/meta` reports `processes.authoring.cockpit-drafts: false`.
- **Example:** `cockpit-drafts=false -> author create 403 DRAFT_AUTHORING_DISABLED; deploy import 201 DRAFT`.
- **Test ID:** `PRE_023_cockpit_drafts_setting_blocks_authoring_only`

### PRE-024 The content hash ignores the diagram part and matches the hash of the Git file
- **Traces to:** `TR-DEF-04`, `TR-DEF-02`
- **Milestone:** M2
- **Given:** File `kyc-onboarding.yaml` in Git, with a `diagram` part; the same file without `diagram` and with keys in another order, as JSON.
- **When:** Both are imported as drafts, and the published hashing reference (RFC 8785 then SHA-256, `diagram` removed) is run on the file in CI.
- **Then:** Both drafts carry the same `definitionHash`, equal to the hash computed in CI; the graph diff between them is empty.
- **Example:** `hash(yaml with diagram) == hash(json reordered, no diagram) == ci_hash(file)`.
- **Test ID:** `PRE_024_content_hash_excludes_diagram_and_matches_git`

### PRE-025 Graph diff lists nodes and edges added, removed and changed
- **Traces to:** `TR-DEF-03`
- **Milestone:** M2
- **Given:** v1 `start -> verify-docs -> approve -> end`; v2 adds `fraud-check` between `verify-docs` and `approve`, changes `approve.assignee.role` from `ops` to `credit-officer`, and moves every box.
- **When:** `GET /definitions/kyc-onboarding/versions/2/diff?base=1`.
- **Then:** `nodesAdded: [fraud-check]`; `nodesChanged: [{id: approve, path: /assignee/role, from: ops, to: credit-officer}]`; `edgesRemoved: [verify-docs->approve]`; `edgesAdded: [verify-docs->fraud-check, fraud-check->approve]`; nothing about positions.
- **Example:** `diff v1..v2 -> +fraud-check, ~approve.assignee.role, -1 edge, +2 edges`.
- **Test ID:** `PRE_025_graph_diff_reports_nodes_and_edges`

### PRE-026 Layout revisions are append-only, need no approval and never change the definition
- **Traces to:** `TR-DEF-02`
- **Milestone:** M3
- **Given:** `kyc-onboarding` v2 `ACTIVE` with layout revision 3; `carol` and `dave` hold `processes:author`.
- **When:** `carol` saves a layout with `If-Match: "3"`; `dave` then saves with `If-Match: "3"`; `carol` saves a layout naming node `ghost-step`.
- **Then:** `carol`'s first save returns `200` with revision 4, `createdBy: carol`; the definition status, hash and `lockVersion` are unchanged and no approval is requested. `dave` gets `409 VERSION_CONFLICT`. The `ghost-step` save gets `422 LAYOUT_INVALID`. Revisions 1 to 4 remain readable.
- **Example:** `save layout If-Match 3 -> revision 4, hash unchanged; concurrent If-Match 3 -> 409`.
- **Test ID:** `PRE_026_layout_revision_append_only_without_approval`

### PRE-037 Definition lifecycle events are published inside the state transaction
- **Traces to:** `TR-OBS-04`, `TR-OBS-06`
- **Milestone:** M2
- **Given:** A host event publisher that records events and a host transaction that can be rolled back.
- **When:** v2 goes through request-approval, reject, request-approval, approve and is superseded; separately a host publisher throws during approve.
- **Then:** Events `publish-requested.v1`, `rejected.v1`, `publish-requested.v1`, `approved.v1`, `retired.v1` are recorded in that order with the full envelope; when the publisher throws, the approval rolls back and the version stays `PENDING_APPROVAL`.
- **Example:** `publisher throws on approve -> status still PENDING_APPROVAL, no approved.v1`.
- **Test ID:** `PRE_037_definition_events_published_in_transaction`

---

## Definition Size, Notation and BPMN Interchange (ADR-0013)

### PRE-035 Definitions above 500 nodes or 1 MB are rejected
- **Traces to:** `TR-DEF-05`
- **Milestone:** M2
- **Given:** Document A with 500 steps (900 KB); document B with 501 steps; document C of 1.2 MB with 300 steps.
- **When:** Each is imported with `POST /definitions`.
- **Then:** A creates a draft; B and C return `422 DEFINITION_TOO_LARGE` naming the limit exceeded; no row is written.
- **Example:** `501 steps -> 422 DEFINITION_TOO_LARGE {limit: max-nodes, value: 501}`.
- **Test ID:** `PRE_035_definition_size_limits_enforced`

### PRE-033 BPMN subset import creates a draft and names every unsupported element
- **Traces to:** `TR-DEF-01`
- **Milestone:** M3
- **Given:** File A uses only the subset of ADR-0013 with BPMNDI shapes; file B adds a `boundaryEvent` `be-timeout` on line 42 and an `inclusiveGateway` `ig-1` on line 57; file C starts with a `DOCTYPE` declaring an external entity.
- **When:** Each is posted to `POST /api/bpm/processes/v1/definitions:import-bpmn`.
- **Then:** A returns `201` with `status: DRAFT`, `source: BPMN` and layout revision 1 from BPMNDI. B returns `422 BPMN_UNSUPPORTED_ELEMENT` listing both elements with id and line. C returns `422 BPMN_UNSAFE_XML` and no entity is resolved. No transaction is opened for B or C.
- **Example:** `boundaryEvent be-timeout line 42 -> 422 BPMN_UNSUPPORTED_ELEMENT`.
- **Test ID:** `PRE_033_bpmn_subset_import_rejects_unsupported_by_name`

### PRE-034 BPMN export round-trips to the same definition hash
- **Traces to:** `TR-DEF-01`
- **Milestone:** M3 (cut candidate)
- **Given:** Every example definition in `examples/`, including `agentTask` and `saga` steps.
- **When:** Each is exported with `GET .../export?format=bpmn` and the result imported again.
- **Then:** The exported XML validates against the BPMN 2.0 XSD; `agentTask` is a `serviceTask` with `horus:type="agentTask"`; the re-imported draft has the same `definitionHash` as the source.
- **Example:** `export kyc-onboarding v2 -> import -> definitionHash equal`.
- **Test ID:** `PRE_034_bpmn_export_round_trip_preserves_hash`

---

## Cockpit Read Models and Query Limits (ADR-0015)

### PRE-027 Activities of an instance are read only from the partitions it lived in
- **Traces to:** `TR-PERF-08`, `TR-OBS-01`
- **Milestone:** M2
- **Given:** `pre_history` has monthly partitions for 2026-01 to 2027-06; instance `I7` started 2027-03-10 and ended 2027-04-02; its `nationalId` input is masked.
- **When:** `GET /api/bpm/processes/v1/instances/I7/activities?limit=200`, with the plan captured.
- **Then:** The plan scans only the 2027-03 and 2027-04 partitions; the response lists per node entered and completed times, duration, attempts, actor and incident, with `nationalId` masked; a second call with the returned `ETag` gets `304`.
- **Example:** `I7 activities -> 2 partitions scanned of 18; 304 on repeat`.
- **Test ID:** `PRE_027_activities_bounded_by_instance_lifetime`

### PRE-028 Live counts per node come from the token index, never from history
- **Traces to:** `TR-PERF-09`
- **Milestone:** M2
- **Given:** `kyc-onboarding` v2 with 40 tokens `WAITING_USER` at `approve`, 7 `WAITING_ASYNC` at `assess-documents`, 2 open incidents at `assess-documents`, and 10,000 history rows.
- **When:** `GET /definitions/kyc-onboarding/versions/2/node-stats?mode=live`, with SQL captured; then again within 5 s.
- **Then:** Response `{approve: {WAITING_USER: 40}, assess-documents: {WAITING_ASYNC: 7, openIncidents: 2}}` with `asOf`; the captured SQL reads only `pre_token` through `idx_pre_token_live` and `pre_incident` through `idx_pre_incident_node`, never `pre_history`; the second call is served from the cache with the same `asOf`.
- **Example:** `approve 40 waiting, assess-documents 7 async + 2 incidents; no pre_history read`.
- **Test ID:** `PRE_028_live_node_counts_from_token_index`

### PRE-029 The hourly rollup lags at most 10 minutes and never touches the step transaction
- **Traces to:** `TR-PERF-10`
- **Milestone:** M3
- **Given:** Two replicas; `TestClock`; 1,200 completions of `approve` between 10:00 and 10:40 with durations from 2 min to 3 h; the rollup interval 5 min and lag 5 min.
- **When:** The clock reaches 10:50 and both replicas run the rollup; a transition is captured at the SQL level during the run.
- **Then:** Exactly one replica processes each slice; the 10:00 bucket for `approve` holds `completed_count = 1,200`; the p95 read from `?mode=window` is within 19 percent of the true p95; the captured transition contains no statement on `pre_node_stats_hourly` or `pre_engine_watermark`. A slice whose transaction is rolled back is counted once on the next run, not twice.
- **Example:** `10:50 -> bucket 10:00 approve completed 1200; no stats write in step TX`.
- **Test ID:** `PRE_029_rollup_bounded_lag_and_no_step_tx_writes`

### PRE-030 Task queries by assignee, role, group, due date and SLA breach are bounded
- **Traces to:** `TR-PERF-07`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** `CallerResolver` returns user `minh`, roles `[credit-officer, ops]`, group `[branch-hcm-01]`; 30,000 open tasks for `credit-officer`, 12 claimed by `minh`, 3 overdue.
- **When:** `GET /tasks?assignment=mine`, `GET /tasks?assignment=candidate&limit=50`, `GET /tasks?assignment=candidate&slaBreached=true`, and `GET /tasks?limit=500`.
- **Then:** `mine` returns the 12 tasks ordered by `dueAt`; `candidate` returns 50 tasks across both roles and the group ordered by `(dueAt, id)` with a `nextCursor`; `slaBreached` returns the 3 overdue tasks; `limit=500` returns `400 QUERY_INVALID` naming the maximum 200. The query-plan check shows only partial-index range scans.
- **Example:** `candidate limit 50 -> 50 items, nextCursor; limit 500 -> 400 QUERY_INVALID (max 200)`.
- **Test ID:** `PRE_030_task_query_filters_bounded_by_index`

### PRE-036 Every list query passes the CI query-plan check
- **Traces to:** `TR-PERF-07`
- **Milestone:** M2
- **Given:** A seeded database with 2 tenants, 1 million instances, 20 million history rows over 12 monthly partitions, and the query registry of `process-engine-postgres`.
- **When:** The query-plan check runs `EXPLAIN (FORMAT JSON)` for every registered query with representative parameters.
- **Then:** No plan contains a sequential scan of a table above 10,000 rows or touches more partitions than its declared bound; a query added without a declared bound fails the build.
- **Example:** `unregistered GROUP BY on pre_history -> build fails`.
- **Test ID:** `PRE_036_list_queries_pass_query_plan_check`

---

## Task Forms and Step Catalogue

### PRE-031 A task form is returned resolved and the completion is validated against it
- **Traces to:** `TR-UI-02`, `TR-SEC-05`
- **Milestone:** M2
- **Given:** `userTask` `approve` with `form: { schema: forms/approve.schema.json, hints: { order: [decision, reason] } }` requiring `decision` in `[APPROVE, REJECT]` and `reason` when `decision = REJECT`; a second definition whose `userTask` has `form: { link: "https://evil.example/t/{taskId}" }` with `tasks.form-link-origins` empty.
- **When:** `GET /tasks/T5/form`; then `:complete` with `{decision: REJECT}`; then with `{decision: REJECT, reason: "income not verified"}`; then the second definition is imported.
- **Then:** The form response carries the schema, the hints and masked prefilled data; the first completion returns `422 FORM_VALIDATION_FAILED` with pointer `/reason`; the second returns `200`; the import returns `422 VALIDATION_FAILED` with problem `FORM_LINK_NOT_ALLOWED`.
- **Example:** `complete {decision: REJECT} -> 422 FORM_VALIDATION_FAILED /reason`.
- **Test ID:** `PRE_031_task_form_resolved_and_completion_validated`

### PRE-032 The step catalogue lists only what the host registered
- **Traces to:** `TR-SEC-01`
- **Milestone:** M3
- **Given:** The host registers service handler `kyc.verifyDocuments` (remote, compensable), agent adapter `doc-assessor`, calendar `vn-banking-hours`; rule sets `card-fee` (outputs `fee`) and `agent-output-guardrails` (outputs `allow`, `reasons`, hit policy `first`).
- **When:** `carol` with `processes:author` calls `GET /api/bpm/processes/v1/step-catalogue`; `erin` with only `processes:read` calls it too.
- **Then:** `carol` gets the handler with its input schema and `compensable: true`, the agent adapter, `card-fee` under decision rule sets, `agent-output-guardrails` under both decision and guardrail rule sets, and the calendar; no configuration value appears. `erin` gets `403 FORBIDDEN`. An import that names an unregistered handler fails with `422 VALIDATION_FAILED`.
- **Example:** `catalogue -> 1 handler, 1 agent, 2 decision rule sets, 1 guardrail rule set, 1 calendar`.
- **Test ID:** `PRE_032_step_catalogue_lists_registered_steps_only`

---

## Tenant Switching (ADR-0014)

### PRE-038 `X-Tenant-Id` is accepted only for a tenant in the token's allowed list
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** `tenancy.switching.enabled=true`; `minh`'s token has `tenant_id: bank-a` and `allowed_tenants: [bank-a, bank-a-fin]`; tenant `bank-b` has instance `I9`.
- **When:** `minh` calls `GET /tasks` with no header, with `X-Tenant-Id: bank-a-fin`, and with `X-Tenant-Id: bank-b`; then `GET /instances/I9` with `X-Tenant-Id: bank-a-fin`.
- **Then:** The first call returns `bank-a` tasks; the second returns `bank-a-fin` tasks; the third returns `403 TENANT_NOT_ALLOWED`, returns no data and writes a security log entry with user, requested tenant and `correlationId`; the fourth returns `404 INSTANCE_NOT_FOUND`.
- **Example:** `X-Tenant-Id: bank-b not in allowed_tenants -> 403 TENANT_NOT_ALLOWED, logged`.
- **Test ID:** `PRE_038_tenant_header_accepted_only_within_allowed_tenants`

---

## Standards Foundations (ADR-0017)

### PRE-039 Import keeps the source document; the hash is fixed in the stored schema version
- **Traces to:** `TR-STD-01`, `TR-STD-03`
- **Milestone:** M2
- **Given:** `kyc-onboarding.yaml` in Git (with comments) and the RFC 8785 hash the pipeline computed from it; the same definition later read back after a newer schema version file has been added to the build.
- **When:** It is imported with `POST /api/bpm/processes/v1/definitions`, then read with `GET .../definitions/kyc-onboarding/versions/1?include=source`.
- **Then:** `pre_definition` has `source_format = YAML`, `source_document` byte for byte equal to the file, `source_hash` of those bytes, and `definition_hash` equal to the pipeline's hash; the response carries the original text; with the newer schema version present, `definition_hash` of version 1 is unchanged and no stored document is re-serialised.
- **Example:** `import kyc-onboarding.yaml -> source_format YAML, definitionHash = pipeline hash, unchanged after upgrade`.
- **Test ID:** `PRE_039_import_keeps_source_document_and_fixed_hash`

### PRE-040 A handler's business error opens an incident until error events exist
- **Traces to:** `TR-OPS-03`, `TR-IDEM-04`
- **Milestone:** M2
- **Given:** Definition `loan-disbursement` with a local `serviceTask` `check-limit` whose handler returns `HandlerResult.BusinessError("LIMIT_EXCEEDED", "Daily limit reached")`.
- **When:** An instance reaches `check-limit`.
- **Then:** The step transaction commits an incident with `error_code = UNHANDLED_BUSINESS_ERROR` and the business code `LIMIT_EXCEEDED` in `cause_summary`; the token stays on `check-limit`; no retry is scheduled because a business error is not retryable; `:retry` after the host fixes the data runs the handler again with a new `attemptId`.
- **Example:** `BusinessError(LIMIT_EXCEEDED) -> incident UNHANDLED_BUSINESS_ERROR, token on check-limit`.
- **Test ID:** `PRE_040_business_error_result_opens_incident`

### PRE-041 Cancelling an instance during an adapter call discards the late result
- **Traces to:** `TR-ASY-01`, `TR-ASY-02`, `TR-STD-12`
- **Milestone:** M2
- **Given:** Instance `I1` on `agentTask` `score-applicant` with its job `CLAIMED` by replica A and a stub adapter that answers after 2 s.
- **When:** `POST /instances/I1:cancel` commits after 0.5 s, and the adapter answers at 2 s.
- **Then:** The cancel transaction sets the instance `CANCELLED`, the token `CANCELLED`, the job `CANCELLED` and writes `TOKEN_CANCELLED`; replica A's TX2 selects no row for `status = 'CLAIMED'`, writes only `LATE_RESULT_DISCARDED` with the attempt id, and does not advance, retry or open an incident; no connection was held during the adapter call.
- **Example:** `cancel at 0.5 s, adapter result at 2 s -> LATE_RESULT_DISCARDED, instance CANCELLED`.
- **Test ID:** `PRE_041_cancel_during_adapter_call_discards_late_result`

### PRE-042 BPMN-style step ids are accepted and kept everywhere
- **Traces to:** `TR-STD-03`, `TR-DEF-01`
- **Milestone:** M2
- **Given:** A definition whose steps have ids `StartEvent_1`, `Activity_0x1y2z3`, `Gateway_kyc.risk` and `end`, and a layout keyed by the same ids; a second definition with step id `1-bad`.
- **When:** Both are imported, the first is approved and an instance runs to `Activity_0x1y2z3`.
- **Then:** The first is stored with those ids unchanged in `definition_json`, the layout, `pre_token.current_node_id`, `pre_history.node_id` and the graph diff; the second is `422 VALIDATION_FAILED` naming `1-bad`, because an id must start with a letter or underscore.
- **Example:** `step id Activity_0x1y2z3 -> stored and shown unchanged; 1-bad -> 422`.
- **Test ID:** `PRE_042_bpmn_style_step_ids_kept`

### PRE-043 A v1 definition reads into the BPMN model and writes back to the same canonical JSON
- **Traces to:** `TR-STD-28`, `TR-STD-03`, `TR-DEF-04`
- **Milestone:** M2
- **Given:** `kyc-onboarding.yaml` (v1) with steps `start`, `collect-documents` (`userTask`), `check-risk` (`exclusiveGateway` with branches `high` and `low` and a default), a `saga` block `open-accounts` holding `reserve-funds` and `open-account`, `notify` (`serviceTask`) and `end`, and the RFC 8785 hash computed by the pipeline from the file.
- **When:** The v1 reader builds the in-memory model, and the canonical writer prints it back.
- **Then:** The model holds one BPMN `process` with a `startEvent`, a `userTask`, an `exclusiveGateway` with one default flow, a `transaction` subprocess `open-accounts` containing two `serviceTask` nodes, a `serviceTask` and an `endEvent`, every node with its step id; sequence flows carry the derived ids `start__collect-documents`, `check-risk__b0`, `check-risk__b1`, `check-risk__default` and so on; the printed canonical JSON is byte for byte equal to the RFC 8785 form of the document, contains no sequence flow ids, and its SHA-256 equals the pipeline hash.
- **Example:** `kyc-onboarding.yaml -> model -> canonical JSON; hash before = hash after`.
- **Test ID:** `PRE_043_v1_definition_round_trips_through_bpmn_model`

### PRE-044 Joins and compensation are keyed by scope token
- **Traces to:** `TR-STD-29`, `TR-ASY-03`, `TR-COMP-01`
- **Milestone:** M2
- **Given:** Definition `loan-booking` v1: `register-loan` (`serviceTask`, compensate `cancelLoan`), then a `saga` block `disburse` holding a `parallelGateway` that forks into `reserve-funds` (compensate `releaseFunds`) and `open-account` (compensate `closeAccount`), joins, then `notify-partner` (`onFailure: compensate`, `retry.max: 0`), all handlers stubbed to succeed and `notify-partner` stubbed to fail.
- **When:** An instance starts and runs until `notify-partner` fails.
- **Then:** At start `pre_token` holds one root `SCOPE` token (`scope_token_id` null, `current_node_id = loan-booking`, `WAITING_SCOPE`) and one `FLOW` token under it; entering `disburse` creates a second `SCOPE` token whose `scope_token_id` is the root; the join counts only the two `FLOW` tokens of the `disburse` scope token and creates exactly one outgoing token; `pre_compensation_stack` holds `cancelLoan` under the root scope token and `releaseFunds` and `closeAccount` under the `disburse` scope token; after the failure the engine runs the two `disburse` handlers newest first, never `cancelLoan`, and the `disburse` scope token ends `COMPENSATED` while the root `cancelLoan` entry stays `PENDING`; a second instance started at the same time shares no token or stack row with the first.
- **Example:** `notify-partner fails in saga disburse -> the two disburse handlers run newest first; cancelLoan is not run`.
- **Test ID:** `PRE_044_joins_and_compensation_keyed_by_scope_token`

### PRE-045 Engine REST bodies ignore the host's JSON settings
- **Traces to:** `TR-EMB-07`, `TR-API-06`
- **Milestone:** M2
- **Given:** A host with the Process Engine starter and REST module, and host properties `spring.jackson.property-naming-strategy=SNAKE_CASE` and `spring.jackson.serialization.write-dates-as-timestamps=true`; the same test runs in the Spring Boot 4.x leg and in the Spring Boot 3.5 leg, where the host's own Jackson 2 stays at the version its dependency management pins.
- **When:** `POST /api/bpm/processes/v1/instances` with `Idempotency-Key: 01JD2X5K9T1Q4N8V2M7B3C6D0E` and body `{ "definition": "kyc-onboarding", "businessKey": "APP-1001", "variables": { "applicantName": "Tran Thi B" } }`, then `GET /api/bpm/processes/v1/instances/{id}`.
- **Then:** The start is accepted (`201`) because the engine reads the camelCase body with its own Jackson 3 mapper; the response keys are camelCase (`businessKey`, `startedAt`), `startedAt` is an ISO 8601 string, and the host's own controllers still answer in snake case; the dependency tree of the 3.5 leg shows Jackson 3 from the engine next to the host's Jackson 2, with no host version override.
- **Example:** `host SNAKE_CASE -> engine response {"businessKey":"APP-1001","startedAt":"2027-02-01T09:00:00Z"}`.
- **Test ID:** `PRE_045_engine_rest_bodies_ignore_host_json_settings`

### PRE-046 A decision step evaluates in the instance's tenant, as the engine on behalf of the user, with no principal needed
- **Traces to:** `TR-TEN-01`, `TR-IDEM-04`, ADR-0027
- **Milestone:** M2
- **Given:** Tenants `bank-a` and `bank-b` each publish a rule set `card-fee`, with different fees. An instance of `card-fee-review` runs in `bank-a`. Its `businessRuleTask` `price` is reached (a) inside `tuan`'s `POST /tasks/{id}:complete` request, and (b) on a thread with no principal (simulated by the test harness, as a timer or poller would run it).
- **When:** The step evaluates `card-fee` through the `DecisionPort`, and the step is then retried with the same attempt.
- **Then:**
  - In both (a) and (b), the decision uses `bank-a`'s `card-fee`, read from `pre_instance.tenant_id`.
  - The `rle_decision` row is in `bank-a`, with caller `system:process-engine` and `onBehalfOf` set to `tuan` in (a) and `null` in (b).
  - `tuan` does not need `rules:evaluate`.
  - The retry replays the logged decision with the idempotency key derived from instance, node and attempt; no second `rle_decision` row is written.
- **Example:** `bank-a instance, no principal -> bank-a card-fee, caller system:process-engine; retry -> same decisionId`.
- **Test ID:** `PRE_046_decision_step_uses_the_instance_tenant_and_the_engine_identity`

### PRE-047 A request whose tenant differs from the instance's tenant cannot run its decision step
- **Traces to:** `TR-TEN-01`, ADR-0027
- **Milestone:** M2
- **Given:** An instance in `bank-a` waiting at a user task followed by a `businessRuleTask`, and a request from a caller whose `EngineAccess` tenant is `bank-b`, which reaches the step through a host path that bypasses the task tenant check (simulated by the test harness).
- **When:** The `DecisionPort` adapter is called for the step.
- **Then:** The adapter refuses with `403 TENANT_NOT_ALLOWED` before any rule set is read, nothing is written to `rle_decision` in either tenant, the step fails and the whole transaction rolls back, and one security log entry is written.
- **Example:** `request tenant bank-b, instance tenant bank-a -> 403 TENANT_NOT_ALLOWED, no decision row`.
- **Test ID:** `PRE_047_decision_port_refuses_a_request_tenant_other_than_the_instance_tenant`

### PRE-048 The kernel runs only the source document that was approved
- **Traces to:** `TR-DEF-04`, `TR-STD-01`, ADR-0026
- **Milestone:** M2
- **Given:** Definition `card-fee-review` v1 approved (or auto-approved on `dev`), with `sourceHash` and `readerVersion` recorded in `processes.definition.approved.v1` and the approval history row, and one running instance.
- **When:** (a) The stored `source_document` and `source_hash` are both replaced in the database by a different, internally consistent process. (b) The engine starts with a different BPMN reader version and the same source.
- **Then:**
  - (a) Starting or advancing the version is refused. Each affected instance gets an incident naming the integrity check, and one audit entry and one alert are written. The changed process never runs.
  - (b) The version compiles and runs, and a WARN log line names the definition and both reader versions.
- **Example:** `source swapped with a matching row hash -> refused, incident, audit, alert; reader upgraded -> runs, WARN`.
- **Test ID:** `PRE_048_kernel_runs_only_the_approved_source`

### PRE-052 The requester withdraws their own pending definition, and it returns to draft without a rejection
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, PD-097
- **Milestone:** M2
- **Given:** `kyc-onboarding` v2 is `PENDING_APPROVAL` with `lockVersion 3`, requested by `alice`, who holds `processes:author`; v1 is `ACTIVE`; `bob` holds `processes:approve`.
- **When:** `alice` sends `POST /api/bpm/processes/v1/definitions/kyc-onboarding/versions/2:withdraw` with `Idempotency-Key`, `If-Match: "3"` and an optional reason. Failure cases: `bob` withdraws v2; `alice` rejects v2 with `:reject`; `alice` withdraws with a stale `If-Match`; `alice` withdraws v1.
- **Then:** `200`. v2 is `DRAFT` with the request cleared, one history row `WITHDRAWN` (actor `alice`, the reason or null) and one `processes.definition.withdrawn.v1`. No rejection is recorded, v1 stays in effect and nothing is published. A replay returns the same answer and writes nothing. `bob` gets `403 PERMISSION_DENIED`; `alice`'s `:reject` gets `403 SELF_APPROVAL_FORBIDDEN` with a detail pointing to `:withdraw`; the stale `If-Match` gets `409 VERSION_CONFLICT`; v1 gets `409 INVALID_STATE`. No failure case changes anything.
- **Example:** `alice withdraws her pending v2 -> DRAFT, WITHDRAWN, v1 still active; alice rejects her own -> 403, use withdraw`.
- **Test ID:** `PRE_052_requester_withdraws_own_pending_definition_to_draft`

### PRE-053 Retiring a definition version stops new starts, never running instances, and the last version needs a confirmation
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, PD-101, PD-102
- **Milestone:** M2
- **Given:**
  - Tenant `bank-a`, at `2027-04-20T09:00:00Z`.
  - `loan-review` v1 is `ACTIVE`, effective since `2027-01-10`, and three instances run on it, one waiting at the user task `credit-check`.
  - v2 is `ACTIVE`, with `effective_from = 2027-05-01T00:00:00Z` and a `DEFINITION_EFFECTIVE` job due then.
  - `bob` holds `processes:approve`; `lan` holds `processes:author` and `processes:start`.
  - `lifecycle.retire-approval` is `single` and `lifecycle.retire-last-version` is `confirm`, the defaults.
- **When:**
  1. `bob` sends `POST /api/bpm/processes/v1/definitions/loan-review/versions/2:retire` with `Idempotency-Key`, `If-Match` and `{ "reason": "pricing step points at the wrong rule set" }`.
  2. He then retires v1 with `{ "reason": "flawed credit routing" }`.
  3. He retires v1 again with `"confirmNoVersionInEffect": true` added.
  4. The task at `credit-check` is completed.
  5. `lan` starts `loan-review`.
  6. The clock passes `2027-05-01T00:00:00Z`.
  - **Failure cases:**
    - `lan` retires v1;
    - `bob` retires v1 with a blank reason;
    - on a host with `lifecycle.retire-last-version: refuse`, the confirmed retire.
- **Then:**
  - **Step 1** returns `200`. v2 is `RETIRED`, and its job is `CANCELLED` in the same transaction. The history row `RETIRED` and `processes.definition.retired.v1` carry the reason, `approvalMode: single` and `noVersionInEffect: false`, because v1 is still in effect.
  - **Step 2** returns `409 LAST_VERSION_IN_EFFECT` with `definitionKey: loan-review`, `version: 1`, `setting: confirm` and `nextEffectiveFrom: null`. Nothing changes.
  - **Step 3** returns `200`. v1 is `RETIRED`, and the history row and the event carry `noVersionInEffect: true`.
  - **Steps 4 and 5.** The three running instances stay on v1, and the task completion advances its instance. The start returns `404 NO_EFFECTIVE_VERSION`.
  - **Step 6.** On `2027-05-01` nothing is retired or started by the cancelled job.
  - **Replay.** A replay of any of the calls returns the same answer and writes nothing.
  - **Failure cases:**
    - `lan` gets `403 PERMISSION_DENIED`;
    - the blank reason gets `400 INPUT_INVALID`;
    - under `refuse`, the confirmed retire gets `409 LAST_VERSION_IN_EFFECT` with `setting: refuse`.
    - None of them changes anything.
- **Example:** `retire v2 (future) -> RETIRED, job cancelled; retire v1 -> 409 LAST_VERSION_IN_EFFECT; with confirmation -> RETIRED, noVersionInEffect true; running instances continue; new start -> 404`.
- **Test ID:** `PRE_053_retire_stops_new_starts_keeps_running_instances_and_confirms_last_version`

### PRE-054 Cancelling an instance needs processes:cancel and a reason
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, PD-103
- **Milestone:** M2
- **Given:**
  - Tenant `bank-a`. Instance `I7` of `loan-review` is `RUNNING`, with `lockVersion 4` and an open task at `credit-check`.
  - `lan` holds `processes:start` and `processes:read`.
  - `minh` holds `processes:cancel` and `processes:read`.
- **When:**
  1. `lan` sends `POST /api/bpm/processes/v1/instances/I7:cancel` with `Idempotency-Key`, `If-Match: "4"` and `{ "reason": "duplicate application" }`.
  2. `minh` sends the same request.
  3. Both call `GET /api/bpm/me`.
  - **Failure cases** for `minh`:
    - a blank reason;
    - a reason of 2,001 characters;
    - a stale `If-Match`;
    - a cancel of a `COMPLETED` instance.
- **Then:**
  - **Step 1.** `lan` gets `403 PERMISSION_DENIED`, and `I7` is unchanged.
  - **Step 2.** `minh` gets `200`:
    - `I7` is `CANCELLED`;
    - its token and its task are `CANCELLED` in the same transaction;
    - one history row `INSTANCE_ENDED` records actor `minh`, the reason and `CANCELLED`;
    - one `processes.instance.cancelled.v1` carries `cancelledBy: minh` and the reason;
    - no compensation handler is called.
  - **Replay.** A replay returns the same answer and writes nothing.
  - **Step 3.** `/api/bpm/me` lists `processes:cancel` for `minh` and not for `lan`.
  - **Failure cases:**
    - the two bad reasons get `400 INPUT_INVALID`;
    - the stale `If-Match` gets `409 VERSION_CONFLICT`;
    - the completed instance gets `409 INVALID_STATE`.
    - None of them changes anything.
- **Example:** `lan (processes:start) cancels I7 -> 403; minh (processes:cancel) cancels with reason -> CANCELLED, task CANCELLED, cancelled event with reason`.
- **Test ID:** `PRE_054_cancel_needs_processes_cancel_and_a_reason`
