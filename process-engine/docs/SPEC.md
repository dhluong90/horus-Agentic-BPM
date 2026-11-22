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
- **Traces to:** `TR-PERF-05`, `TR-ASY-03`
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
- **Given:** Process started or prior `submit-application` step completed by `alice`; next `userTask` has `fourEyes: true`. The refused identities are the starter of the instance and everyone who completed an earlier task of it; an explicit per-task list of refused users is not part of the model.
- **When:** `alice` tries to claim or complete the approval task via Java API or REST. Further cases: `carol` claims the approval, then completes a task on a parallel branch of the same instance, then completes the approval; a caller of another tenant claims or completes it.
- **Then:** Engine rejects with `403 SELF_APPROVAL_FORBIDDEN`; only another authorized user (`bob`) can complete it. `carol`'s completion is refused the same way, because the check runs again at completion under the instance lock. The other tenant gets `404 TASK_NOT_FOUND`. No refusal writes a history entry, an event, a stored answer or a change to the task.
- **Example:** `submit=alice, approve=alice -> 403; approve=bob -> 200`.
- **Test ID:** `PRE_007_four_eyes_blocks_same_actor_approval`

### PRE-008 BusinessCalendar SPI computes SLA deadlines across weekends and holidays
- **Traces to:** `TR-CAL-01`, `TR-CAL-02`
- **Milestone:** M2
- **Given:** Tenant calendar `vn-banking-hours` (`Mon-Fri 08:00-17:00 Asia/Ho_Chi_Minh`, holiday `2027-01-01`). A `userTask` `credit-review` with `horus:sla="PT4H"` and `horus:calendar="vn-banking-hours"`; the engine clock at `2026-12-31T15:00:00+07:00`.
- **When:** Engine creates the `userTask` and computes `due_at`; later an approved change adds `2027-01-04` as a holiday and a second task is created at the same time.
- **Then:** Consumes 2 hours (`15:00-17:00` on Dec 31), skips Jan 1 holiday + weekend (Jan 2–3), and sets `due_at = 2027-01-04T10:00:00+07:00`, shown as `dueAt` on the task. The value is computed once, with the engine clock, in the calendar's time zone (never the JVM's), and stored; the later change does not mutate it, and only the second task is due `2027-01-05T10:00:00+07:00`. A task without `horus:calendar` counts elapsed time. A calendar the tenant does not have fails the step with `422 STEP_FAILED` (reason `CALENDAR_NOT_FOUND`) and nothing is written. An `horus:sla` that is not a positive ISO 8601 duration up to 366 days, an invalid calendar key, or a calendar without an SLA is `422 VALIDATION_FAILED` (`SLA_INVALID`) at import.
- **Example:** `Dec 31 15:00 + PT4H (biz) -> Jan 4 10:00 +07:00`.
- **Test ID:** `PRE_008_business_calendar_skips_holidays_and_weekends` (unit, `WorkingCalendarTest`, with cases across a clock change, a lunch break, a closing-time end and a seeded property that the due time does not depend on the JVM time zone; and on PostgreSQL, `BusinessCalendarPostgresIT`), `PRE_008_a_later_calendar_change_does_not_move_an_existing_due_time`, `PRE_008_*` in `SubsetBpmnReaderSlaTest`

### PRE-114 A business calendar is created or changed only when a second person approves it
- **Traces to:** `TR-CAL-01`, `TR-SEC-04`
- **Milestone:** M2
- **Given:** Tenant `bank-a`, `vn-banking-hours` at version 1; `kim` and `lan` hold `processes:calendar:manage`; `horus.bpm.processes.calendar.approval` at its default `four-eyes` (PD-118 as amended by PD-131).
- **When:** `kim` proposes the calendar with `2027-01-04` added (against version 1, with a reason) and tries to approve it; `lan` approves it; a second proposal made against version 1 is then approved; a manager of `bank-b` reads the calendar or approves a `bank-a` proposal; the service starts with `calendar.approval=single` under the `prod` profile.
- **Then:** `kim`'s approval is `403 SELF_APPROVAL_FORBIDDEN` and changes nothing; `lan`'s approval moves the calendar to version 2 and emits `processes.calendar.changed.v1` with `proposedBy=kim` and `approvedBy=lan`; the change record keeps the reason, both people and the old and the new value. The stale proposal is `409 VERSION_CONFLICT` and stays `PENDING`. `bank-b` gets `CALENDAR_NOT_FOUND` and nothing is written. Start-up is refused with `single` outside the `dev` profile; under `dev` alone a proposal applies at once and is recorded as `SINGLE`. The table refuses an approved four-eyes change whose approver is its proposer, whoever writes it.
- **Example:** `kim proposes, kim approves -> 403; lan approves -> version 2, event names kim and lan`.
- **Test ID:** `PRE_114_*` in `BusinessCalendarPostgresIT` and `DevOnlyRelaxationTest`

---

## `agentTask` Async Wait-State, Guardrails & Audit

### PRE-009 `agentTask` releases DB connections before invoking adapter (Async Wait-State)
- **Traces to:** `TR-AGT-03`, `TR-EMB-04`, `TR-ASY-01`
- **Milestone:** M2
- **Given:** Host configured with a 2-connection HikariCP pool (borrow timeout 500 ms); an async step calls a stub adapter that holds every call until all 10 are in flight. The async step is a remote `serviceTask` (`horus:mode="remote"`) `bureau-score` of `loan-bureau-check`. Agent case (#52): the agent step `assess-documents` of `loan-agent-assessment`, through a stub agent adapter, with the guardrail rule set published.
- **When:** 10 instances reach the step, one poll starts all 10 calls, and while they are in flight another thread runs 20 queries on the same pool.
- **Then:** All 10 adapters run concurrently with 0 pool connections active and no adapter call inside a transaction; all 20 queries get a connection; after the calls return, each instance records the result once (`TX 1` committed the claim before the call; `TX 2` commits the result after it).
- **Example:** `10 concurrent bureau calls on a 2-connection pool -> 0 active connections while calling, 0 pool timeouts, 10 instances on underwrite`.
- **Test ID:** `PRE_009_remote_step_executes_outside_db_transaction`, `PRE_009_agent_step_executes_outside_db_transaction` (the agent case; each loan's answer is allowed by the guardrail and recorded once)

### PRE-010 `agentTask` validates JSON Schema and evaluates Rule Engine guardrail before advancing
- **Traces to:** `TR-AGT-01`
- **Milestone:** M2
- **Given:** `assess-documents` `agentTask` with `outputSchema` requiring `{ riskScore: number, recommendation: string }`, `guardrails: { decision: agent-output-guardrails, onViolation: route-to-human }`, and guardrail rule set `agent-output-guardrails` (`hitPolicy: first`; `R2`: `riskScore >= 70 -> allow: false, reasons: ['HIGH_RISK_SCORE']`; default row `allow: true`).
- **When:** Adapter returns `{ riskScore: 85, recommendation: "APPROVE" }`.
- **Then:** Output passes schema validation, Rule Engine returns `allow = false`, `reasons = ["HIGH_RISK_SCORE"]` and matched rule `R2`, and engine creates a maker-checker `userTask` carrying inputs, agent output, `reasons`, and matched rule IDs instead of auto-advancing.
- **Example:** `riskScore=85 -> allow=false, R2, HIGH_RISK_SCORE -> userTask created for compliance-reviewer`.
- **Test ID:** `PRE_010_agent_task_guardrail_violation_routes_to_human`, `PRE_010_an_answer_the_guardrail_allows_moves_the_loan_on_without_a_review` (example host over HTTP, `GuardedAgentTaskIT`: the review task's `review` object carries the code, reasons, rule ids, guardrail decision id, inputs and the masked answer; the starter of the loan cannot claim it)

### PRE-011 `agentTask` invalid JSON output or timeout retries with backoff then raises incident
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-OPS-03`
- **Milestone:** M2
- **Given:** `agentTask` with `timeout: PT1S`, `retry: { max: 2, backoff: PT1S }` (BPMN `<horus:retry max="2" backoff="PT1S"/>`). Adapter exceeds timeout, or returns an answer failing `outputSchema` with `onViolation: fail` and `runners.async-agent.schema-retries` set to 2 (default 1: a schema failure is retried apart from `retry.max`).
- **When:** Initial attempt and 2 retries fail.
- **Then:** Engine retries twice, one `backoff` apart, and on the 3rd failure fails the job and, in the same transaction, opens a `pre_incident` with `error_code = AGENT_TIMEOUT` or `AGENT_SCHEMA_INVALID`, `retry_count = 2` (attempts after the first) and a cause built from codes only, records `INCIDENT_RAISED` and emits `processes.incident.raised.v1`, pausing the token on the step (`WAITING_ASYNC`) for ops retry/resolve (#68). Every async job that fails for good opens one, with its own code (`UNHANDLED_BUSINESS_ERROR` for a business error, `MAX_ATTEMPTS_REACHED` for a job claimed too often). Another tenant can neither complete the step nor read the incident, and nothing is written.
- **Example:** `3 failed attempts -> 1 OPEN pre_incident with cause & retry_count=2`.
- **Test ID:** `PRE_011_agent_task_exhausted_retries_raises_incident`, `PRE_011_agent_task_exhausted_schema_retries_raises_incident`, `PRE_011_another_tenant_can_neither_complete_the_step_nor_read_its_incidents`

### PRE-012 `agentTask` logs masked inputs, output, guardrail decision ID, and human override
- **Traces to:** `TR-AGT-02`, `TR-OBS-01`
- **Milestone:** M2
- **Given:** `agentTask` with input `nationalId` marked `mask="true"` (canonical `maskedInputs`) routes to human `userTask`; the adapter returns labels `{ "model": "scoring-2026-10", "promptVersion": "p7", "vendor": "in-house" }`; reviewer changes `recommendation` from `"APPROVE"` to `"REJECT"` with a reason. Second case: an adapter result with 17 labels while `agents.labels.max-count` is 16.
- **When:** Reviewer completes the `userTask`.
- **Then:** `pre_history` `ASYNC_RESULT_REFUSED` (and `ASYNC_RESULT_ACCEPTED` for an allowed answer) records `inputs` with `nationalId` as its keyed fingerprint (`hmac:<keyId>:<hex>` under the tenant's masking key, `***` without one; no plaintext, neither in history nor in the review task), the agent output masked by the schema policy, `guardrailDecisionId`, `latencyMs` (claim to result), and the three labels unchanged; `HUMAN_OVERRIDE` records the reviewer as actor and `reviewedBy`, `refusedReportedBy` and `refusedAttemptId`, the refusal `code`, `guardrailDecisionId`, `reason`, the reviewer's masked answer and `changes` (per field `from` and `to`) for Eval replay; the engine takes no decision from a label. The review stays four-eyes: the starter and the reporter of the refused answer cannot complete it. In the second case the attempt fails with `AGENT_LABELS_INVALID` and is retried like any failed attempt (`PRE-011`). Another tenant can neither read nor complete the review, and nothing is written.
- **Example:** `Override APPROVE -> REJECT logged in pre_history with guardrailDecisionId`.
- **Test ID:** `PRE_012_agent_task_audits_masked_io_and_human_override`, `PRE_012_labels_above_the_bound_fail_the_attempt_and_it_is_retried`, `PRE_012_another_tenant_cannot_read_or_complete_the_review_and_nothing_is_written`

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

A `saga` block is a BPMN `transaction`: its steps run under the block's own scope token (ADR-0020). A completed `serviceTask` or `agentTask` with `horus:compensate` pushes one entry onto `pre_compensation_stack` (V31) under its scope token. A step that fails for good with `horus:onFailure="compensate"`, or a `throwCompensation` (an `intermediateThrowEvent` with a `compensateEventDefinition`, inside a `saga`), walks the stack of that scope token newest first, one undo at a time. Each undo is a `COMPENSATION` job keyed by its own job id. A remote or agent step's undo is a `ServiceAdapter` called with no transaction open. A local step's undo is a `ServiceTaskHandler` that runs in the job's result transaction. The instance is `COMPENSATING` during the walk and ends `FAILED`.

### PRE-073 A failed transfer undoes its completed steps newest first, and each undo gets the forward step's keys
- **Traces to:** `TR-COMP-01`, `TR-COMP-02`, `TR-IDEM-04`, `TR-ASY-01`, #67
- **Milestone:** M2
- **Given:**
  - Definition `fund-transfer` has a `saga` block `transfer` with three steps:
    - `hold-funds`: local, compensated by the local `release-hold`;
    - `debit-source`: remote, adapter `core-banking`, compensated by the remote `credit-back`;
    - `credit-target`: remote, `onFailure: compensate`.
  - An instance moves `25,000,000 VND` from account `0011-223344` to `0099-887766`.
  - `debit-source` was accepted on job `J1`, attempt 1.
- **When:** `credit-target` fails with `BUSINESS_ERROR` `ACCOUNT_CLOSED`.
- **Then:**
  - Because the step declares `onFailure: compensate`, the failure starts compensation instead of the incident of `PRE-040`. The same happens when a step's job reaches `FAILED` for any other reason: not retryable, or the last attempt used.
  - The instance is `COMPENSATING` and `processes.compensation.started.v1` carries the `transfer` scope token and stack depth 2.
  - `credit-back` runs first, as a `COMPENSATION` job `C1` with the `runners.compensation` retry policy. The adapter call carries:
    - `idempotencyKey = C1`, kept on every retry;
    - `forwardJobId = J1`, the key the original debit was posted under, so the core can find and reverse it;
    - `forwardAttemptId`, the accepted forward attempt;
    - the entry's input snapshot: the forward inputs and, under `forwardResult`, the forward result.
  - `release-hold` runs only after `C1` is `DONE`, in its own transaction, and receives `forwardAttemptId`.
  - Both entries end `DONE`, the `transfer` scope token ends `COMPENSATED`, live flow tokens are `CANCELLED`, and the instance ends `FAILED` with `processes.instance.failed.v1`.
  - `credit-target` was never completed, so it is never compensated.
  - A `compensate` name that no host bean registers is refused at `:request-approval` and `:approve` with `422 VALIDATION_FAILED`, as in `PRE-072`: `HANDLER_NOT_REGISTERED` for a local step's undo, `ADAPTER_NOT_REGISTERED` for a remote or agent step's undo. An unknown `onFailure` value or an undo name that is not a name is refused at import (`ON_FAILURE_INVALID`, `COMPENSATE_INVALID`).
- **Example:** `[hold-funds, debit-source, credit-target(fail)] -> credit-back(forwardJobId J1) -> release-hold -> FAILED, scope COMPENSATED`.
- **Test ID:** `PRE_073_*` in `SagaCompensationTest` (`process-engine-postgres`) and `SagaBlockTest` (`process-engine-bpmn`)

### PRE-074 An undo that fails raises an incident, stops the walk and keeps what was undone
- **Traces to:** `TR-COMP-02`, `TR-OPS-03`, `TR-IDEM-04`, PD-126, #67
- **Milestone:** M2
- **Given:** The instance of `PRE-073`, where `credit-back` answers `BUSINESS_ERROR` `ACCOUNT_FROZEN`. Separately, `credit-back` times out on all 3 attempts.
- **When:** Each failure is recorded.
- **Then:**
  - The stack entry of `debit-source` is `FAILED`.
  - An incident `COMPENSATION_FAILED` opens on node `debit-source`. It names the stack entry, `forwardJobId J1` and the cause code (`ACCOUNT_FROZEN` or `ADAPTER_TIMEOUT`), and `processes.incident.raised.v1` is published with the cause as `businessCode`. The adapter's message is never stored.
  - The walk pauses: `release-hold` stays `PENDING` and the instance stays `COMPENSATING`.
  - A retry of the incident (`PRE-077`) runs `credit-back` again under the same job id `C1` with a new attempt id, so the core never credits twice. Its success resolves the incident `RETRY_SUCCEEDED`.
  - A resolution (`PRE-078`) marks the undo done by hand: one operator proposes it with a reason, a second approves it, and the incident is `RESOLVED` with `COMPLETED_MANUALLY`.
  - Once the entry is `DONE`, by a retry or by a resolution, the walk continues with `release-hold`.
- **Example:** `credit-back ACCOUNT_FROZEN -> COMPENSATION_FAILED, release-hold PENDING, instance COMPENSATING`.
- **Test ID:** `PRE_074_*` in `SagaCompensationTest`

### PRE-075 A manual undo opens an incident with the step's context instead of being skipped
- **Traces to:** `TR-COMP-03`, `TR-SEC-05`, PD-126, #67
- **Milestone:** M2
- **Given:** `debit-source` declares `compensate: manual`.
- **When:** The walk reaches its entry.
- **Then:**
  - The entry is `MANUAL` and an incident `COMPENSATION_MANUAL_REQUIRED` opens. The incident names the step, the forward job id and the forward attempt id. History records `COMPENSATION_STEP_MANUAL` with those ids and the input snapshot, masked inputs as keyed hashes only.
  - The walk pauses until the incident is resolved. A retry is `409 INVALID_STATE`, because a manual undo has nothing to run, and the incident offers `resolve` only.
  - Nothing is skipped silently (`TR-COMP-03`).
- **Example:** `compensate: manual on debit-source -> COMPENSATION_MANUAL_REQUIRED, walk paused`.
- **Test ID:** `PRE_075_*` in `SagaCompensationTest`

### PRE-076 A compensation walks only its own scope, and a compensating instance cannot be cancelled
- **Traces to:** `TR-STD-29`, `TR-COMP-01`, `TR-TEN-01`, #67
- **Milestone:** M2
- **Given:** One instance of `fx-booking` whose root scope holds the completed step `open-account`, and two `saga` entries `fx-leg-1` and `fx-leg-2`, each with two compensable steps.
- **When:**
  - (a) `fx-leg-2`'s confirmation fails with `onFailure: compensate`.
  - (b) `throwCompensation` runs inside `fx-leg-1`.
  - (c) `POST /instances/{id}:cancel` is sent while the instance is `COMPENSATING`.
- **Then:**
  - (a) Only the two entries of `fx-leg-2` are undone. `fx-leg-1` and `open-account` are untouched (`PRE-044`, `TR-STD-29`).
  - (b) Only `fx-leg-1`'s entries are walked.
  - (c) The cancel is `409 INVALID_STATE`, and its detail says a compensation is running. An undo is never abandoned half way. An operator resolves the incidents first.
  - A step without a `compensate` handler pushes no entry. A `throwCompensation` outside a `saga` is refused at import (`THROW_COMPENSATION_OUTSIDE_SAGA`), and a sequence flow that crosses a block's boundary when the definition is compiled (`FLOW_CROSSES_SAGA`).
  - An undo is engine work in the tenant of the locked `pre_instance` row, with no caller. Another tenant's instance or incident answers `404`, and nothing is written.
- **Example:** `fx-leg-2 fails -> 2 undos in fx-leg-2 only; cancel while COMPENSATING -> 409 INVALID_STATE`.
- **Test ID:** `PRE_076_*` in `SagaCompensationTest` and `SagaBlockTest`

### PRE-014 Migration validate dry-run detects unmapped steps without mutating instances
- **Traces to:** `TR-MIG-01`
- **Milestone:** M2
- **Given:** 500 instances running on `v1`, including 50 paused on `legacy-review` which was removed in `v2`.
- **When:** Call `POST /api/bpm/processes/v1/migrations/{id}:validate` without mapping `legacy-review`.
- **Then:** Returns dry-run report listing 50 unmapped instances at `legacy-review` and marks plan invalid; 0 rows in `pre_instance` are modified. Resolving them by an explicit mapping for a renamed step or by cancellation is M3 (`TR-MIG-03`) and is not part of this statement.
- **Example:** `Validate v1->v2 missing legacy-review -> conflict report, 0 instances mutated`.
- **Test ID:** `PRE_014_migration_validate_reports_unmapped_steps_without_mutation`, `PRE_014_a_step_kept_under_its_id_as_another_step_type_is_a_conflict`, `PRE_014_a_plan_whose_steps_all_map_is_valid_and_lists_every_instance_that_would_move`, `PRE_014_every_registered_dry_run_query_is_answered_by_an_index_on_a_seeded_database`, `PRE_014_the_dry_run_counts_are_answered_by_an_index_on_a_seeded_database`, `PRE_014_the_dry_run_reads_return_the_seeded_rows_of_one_tenant_and_version`, `PRE_014_the_dry_run_report_matches_the_contract`

### PRE-015 Batched migration isolates per-instance failures without stalling healthy instances
- **Traces to:** `TR-MIG-02`, #90
- **Milestone:** M2
- **Given:** Approved migration plan `v1 -> v2` (`PRE-195`) across 250 running instances, `migration.batch-size` 100. Since the dry run, instance `#120`'s token has reached a step the target does not keep (a mapping invariant error). In a second case another transaction holds the row lock of one instance (a transient lock conflict). In a third, one instance ends after the batch was read.
- **When:** The runner executes the plan, in batches, one instance lock at a time.
- **Then:** 249 healthy instances pin to `v2`: `pre_instance`, every token, open task, due job and recorded result point at the `v2` row, and each has one `MIGRATED` `pre_history` row naming the approver, the proposer, the reason and both versions. Instance `#120` stays on `v1`, with one `OPEN` incident `MIGRATION_FAILED` on its step and an outcome `FAILED` with the code `STEP_NOT_IN_TARGET`, without rolling back the other 249 instances. The lock conflict is `FAILED` with `LOCK_CONFLICT` and an incident, the other instances move; the instance that ended is `SKIPPED` with `NOT_RUNNING` and has no incident. The plan ends `COMPLETED_WITH_FAILURES` (`COMPLETED` when nothing failed) with exact counts of migrated, failed and skipped, and publishes `processes.migration.completed.v1`. A moved instance goes on to a step that only `v2` has. A second run moves and writes nothing.
- **Example:** `250 instances (1 corrupt) -> 249 migrated to v2, 1 incident raised, plan COMPLETED_WITH_FAILURES 249/1/0`.
- **Test ID:** `PRE_015_migration_execute_isolates_instance_failure`, `PRE_015_a_moved_loan_continues_on_the_new_version`, `PRE_015_a_loan_another_transaction_holds_is_a_failure_with_an_incident_and_the_others_move`, `PRE_015_a_loan_that_ended_before_the_runner_reached_it_is_skipped_not_failed`

### PRE-195 An execution is proposed with a reason and runs only after a second holder approves it
- **Traces to:** `TR-MIG-01`, `TR-SEC-04`, `TR-SEC-11`, PD-114, PD-131, #90
- **Milestone:** M2
- **Given:** `loan-review` v1 retired with 5 running loans, v2 active; a plan v1 to v2 created by `thu`; `thu` and `lan` hold `processes:migration:manage`; `migration.dry-run-validity` is one hour.
- **When:** `thu` proposes the execution with the reason `Add the audit sample after officer review, change CHG-2041` and then tries to approve and to reject it; the runner polls; `lan` approves with a stale and then with the right `If-Match`; in other cases the reason is blank, the caller lacks the permission, a loan has since reached a step `v2` lacks, `lan` approves 61 minutes after the proposal, and `lan` rejects instead.
- **Then:**
  - The engine runs the dry run itself at the proposal and at the approval; a plan with a conflict is `409 DRY_RUN_REQUIRED` naming the number of conflicts, as is an approval whose proposal's dry run is older than the validity, and nothing is written. A proposal whose dry run has expired may be proposed again.
  - The proposal is `PROPOSED` with proposer, reason and time, `lockVersion` 1, and `processes.migration.proposed.v1`; it moves nothing and the runner finds nothing to run. `thu`'s own approval or rejection is `403 SELF_APPROVAL_FORBIDDEN` and changes nothing; a stale `If-Match` is `409 VERSION_CONFLICT`; a blank reason is `400 INPUT_INVALID`; a caller without the permission is `403 PERMISSION_DENIED`.
  - `lan`'s approval makes the plan `APPROVED` with both people, in one transaction with its history row and `processes.migration.approved.v1`; only now does the runner move instances. A rejection with a reason makes it `REJECTED`, moves nothing, and the plan may be proposed again.
  - With `migration.approval=single`, accepted on the `dev` profile only, the proposal is approved by the proposer at once and the history row records `single`. A repeat of a command with the same `Idempotency-Key` returns the first answer and writes nothing; the same key with another reason is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`.
  - The table refuses a `RUNNING` or `APPROVED` plan without an approver and a four-eyes plan whose approver is its proposer, whoever writes the row.
- **Example:** `thu proposes, thu approves -> 403; lan approves -> APPROVED, runner moves 5; plan with 1 blocked loan -> 409 DRY_RUN_REQUIRED`.
- **Test ID:** `PRE_195_a_second_holder_approves_and_only_the_approval_lets_the_runner_move_instances`, `PRE_195_a_rejected_proposal_moves_nothing_and_may_be_proposed_again`, `PRE_195_a_proposal_needs_a_reason_a_passing_dry_run_and_the_permission`, `PRE_195_the_dry_run_must_still_be_fresh_and_still_pass_when_the_second_person_approves`, `PRE_195_single_person_execution_is_approved_at_once_and_recorded_as_single`, `PRE_195_a_proposal_is_idempotent_and_a_key_is_not_reused_for_another_reason`, `PRE_195_the_table_refuses_a_running_plan_nobody_approved_and_a_four_eyes_approval_by_the_proposer`, `PRE_195_execution_bodies_and_problems_match_the_contract`

### PRE-196 Another tenant can neither propose, approve nor read an execution, and nothing is written
- **Traces to:** `TR-TEN-01`, `TR-MIG-01`, #90
- **Milestone:** M2
- **Given:** `bank-a`'s plan with a proposal over 3 running loans; `bank-b` has 4 running loans of its own and its own approved plan.
- **When:** A manager of `bank-b` proposes, approves, rejects, reads and lists the outcomes of `bank-a`'s plan; the runner then polls twice.
- **Then:** Each is `404 MIGRATION_NOT_FOUND`; every engine table holds the same rows and no event is published. A runner pass takes the plan of one tenant and moves only that tenant's loans: `bank-a` ends with 3 outcome rows and `bank-b` with 4, and neither tenant's progress counts the other's. The tenant of a plan is read from its row, never from a request.
- **Example:** `bank-b approves bank-a's proposal -> 404, 0 rows; bank-a 3 moved, bank-b 4 moved`.
- **Test ID:** `PRE_196_another_tenant_can_neither_propose_approve_nor_read_a_migration_and_nothing_is_written`

### PRE-197 A crashed runner's plan is taken over after its lease and continues after the last committed instance
- **Traces to:** `TR-MIG-02`, `TR-OPS-01`, #90
- **Milestone:** M2
- **Given:** An approved plan over 60 loans, `migration.batch-size` 25, `migration.lease` two minutes; the runner on `pod-a` stops after its 40th loan as a crash would.
- **When:** `pod-b` polls at once, and again after the lease has ended.
- **Then:** After the crash 40 loans are moved, the plan is `RUNNING` and `pod-a` holds the lease, so `pod-b` takes nothing. After the lease `pod-b` moves the 20 loans after the last committed one. Every loan has exactly one `MIGRATED` history row and one outcome row, the counts are 60/0/0, the plan is `COMPLETED`. The cursor, the counts and the lease are written in the transaction of each loan, so no state exists in which a loan is moved and not counted.
- **Example:** `crash after 40 of 60 -> pod-b at +2 min moves 20, 60 MIGRATED rows, none twice`.
- **Test ID:** `PRE_197_a_crashed_runner_is_taken_over_after_its_lease_and_continues_after_the_last_committed_instance`

### PRE-198 One runner writes a plan at a time, and a runner that lost its lease writes nothing more
- **Traces to:** `TR-MIG-02`, `TR-TEN-01`, #90
- **Milestone:** M2
- **Given:** An approved plan over 30 loans. Case 1: `pod-a` is slow; after its 5th loan its lease ends, `pod-b` takes the plan and finishes it, and `pod-a` then goes on with its batch. Case 2: `pod-b` polls while `pod-a` holds the lease.
- **When:** `pod-a` reaches its 6th loan, which `pod-b` has already moved; `pod-b` polls during `pod-a`'s run.
- **Then:** `pod-a` stops: its transaction is rolled back because it no longer holds the lease, and it counts nothing. `pod-b` did the other 25 loans. 30 `MIGRATED` rows, 30 outcome rows, counts 30/0/0, no loan moved or counted twice. In case 2 `pod-b` finds nothing to run while the lease is held. The claim is `FOR UPDATE SKIP LOCKED` on the plan row.
- **Example:** `pod-a loses its lease after 5 -> pod-b moves 25, pod-a writes nothing more; 30 rows in all`.
- **Test ID:** `PRE_198_one_writer_per_plan_a_runner_that_lost_its_lease_writes_nothing_more`, `PRE_198_two_runners_never_take_the_same_plan`

---

## Deploy-Time Contracts & Leases

### PRE-016 Deploy rejects an `agentTask` whose guardrail rule set breaks the guardrail contract
- **Traces to:** `TR-AGT-01`
- **Milestone:** M2
- **Given:** Definition `kyc-onboarding` v1 with `agentTask` `assess-documents` and `guardrails.decision: agent-output-guardrails`. Case A: the rule set's effective version declares outputs `allow: boolean` and `reasons: list<string>` with `hitPolicy: first`. Case B: it declares `decision: string` and no `allow`. Case C: it has `hitPolicy: collect` (the Rule Engine makes a collected output a list, so `allow` is `list<boolean>`). Case D: no rule set of that name exists in the tenant.
- **When:** Caller posts the definition to `POST /api/bpm/processes/v1/definitions`.
- **Then:** Case A creates a draft (`201`, `status: DRAFT`). Cases B, C and D return `422 GUARDRAIL_CONTRACT_INVALID` naming the step id and the failed check; no `pre_definition` row is written. A rule set that reads an input the step's `outputSchema` does not declare, or declares with an incompatible type, is refused the same way, and so is a rule set published only in another tenant. The same check runs again at `:request-approval` and `:approve`, against the version in effect then; a refusal leaves the version's status and lock version unchanged.
- **Example:** `outputs [decision: string] -> 422 GUARDRAIL_CONTRACT_INVALID at step assess-documents`.
- **Test ID:** `PRE_016_deploy_rejects_guardrail_rule_set_breaking_contract`

### PRE-017 Async job lease is the step timeout plus the lease margin, so a live attempt is never taken over
- **Traces to:** `TR-AGT-03`, `TR-PERF-05`
- **Milestone:** M2
- **Given:** `agentTask` with `timeout: PT2S`, `jobs.lease-margin = 30s`; the adapter on Pod 1 returns after 1.9 s. In a second run Pod 1 crashes right after TX1.
- **When:** Pod 2 polls continuously from the claim instant `T0`.
- **Then:** TX1 sets `locked_until = T0 + 32s`. In the first run Pod 2 never claims the job, and Pod 1's TX2 records the result once. In the crash run Pod 2 skips the job until `T0 + 32s`, then reclaims it with `attempt = 2` and a new `attemptId`.
- **Example:** `timeout PT2S -> lease 32s; live attempt completes at 1.9s untouched; after crash reclaimed at T0+32s`.
- **Test ID:** `PRE_017_async_lease_is_timeout_plus_margin`, `PRE_017_a_crashed_attempt_is_reclaimed_at_the_end_of_its_lease_with_a_new_attempt_id` (both on the remote `serviceTask` `bureau-score`); agent case (#52): `PRE_017_agent_lease_is_timeout_plus_margin`, `PRE_017_a_crashed_agent_attempt_is_reclaimed_at_the_end_of_its_lease_with_a_new_model_call_key` (the reclaimed attempt's model call key is the new attempt id; its idempotency key is still the job id)

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
- **Scope today:** The engine reads BPMN XML only; the `process-definition.v1` JSON and YAML reader is `PRE-043`. Until it ships the case runs on a BPMN file with a `BPMNDI` diagram, the same file with its attributes in another order and every box moved, and the file without any diagram, imported by two tenants. The stored definition holds no diagram, all three carry the hash that an independent run of the published reference (diagram removed, RFC 8785, SHA-256) gives for the stored document, a moved diagram is no new version, and a layout saved afterwards leaves the hash alone. The YAML and JSON file cases follow with `PRE-043`.
- **Test ID:** `PRE_024_content_hash_excludes_diagram_and_matches_git`

### PRE-025 Graph diff lists nodes and edges added, removed and changed
- **Traces to:** `TR-DEF-03`
- **Milestone:** M2
- **Given:** v1 `start -> verify-docs -> approve -> end`; v2 adds `fraud-check` between `verify-docs` and `approve`, changes `approve.assignee.role` from `ops` to `credit-officer`, and moves every box.
- **When:** `GET /definitions/kyc-onboarding/versions/2/diff?base=1`.
- **Then:** `nodesAdded: [fraud-check]`; `nodesChanged: [{id: approve, path: /assignee/role, from: ops, to: credit-officer}]`; `edgesRemoved: [verify-docs->approve]`; `edgesAdded: [verify-docs->fraud-check, fraud-check->approve]`; nothing about positions.
- **Example:** `diff v1..v2 -> +fraud-check, ~approve.assignee.role, -1 edge, +2 edges`.
- **Test ID:** `PRE_025_graph_diff_reports_nodes_and_edges`, `PRE_025_the_diff_of_two_stored_drafts_lists_the_added_step_the_changed_role_and_the_links`, `PRE_025_the_graph_diff_body_and_its_etag_match_the_contract`

### PRE-026 Layout revisions are append-only, need no approval and never change the definition
- **Traces to:** `TR-DEF-02`
- **Milestone:** M3
- **Given:** `kyc-onboarding` v2 `ACTIVE` with layout revision 3; `carol` and `dave` hold `processes:author`.
- **When:** `carol` saves a layout with `If-Match: "3"`; `dave` then saves with `If-Match: "3"`; `carol` saves a layout naming node `ghost-step`.
- **Then:** `carol`'s first save returns `200` with revision 4, `createdBy: carol`; the definition status, hash and `lockVersion` are unchanged and no approval is requested. `dave` gets `409 VERSION_CONFLICT`. The `ghost-step` save gets `422 LAYOUT_INVALID`. Revisions 1 to 4 remain readable.
- **Also:** Two saves over the same revision at once make exactly one new revision and one `409 VERSION_CONFLICT`. The table refuses `UPDATE`, `DELETE` and `TRUNCATE` of a revision (SQLSTATE `55000`). A save publishes `processes.definition.layout-saved.v1` and is allowed for a draft, an active and a retired version, and while `authoring.cockpit-drafts` is off, because a layout is not a draft. A version that has no layout answers `204` with `ETag: "0"`, so the first save sends `If-Match: "0"`.
- **Example:** `save layout If-Match 3 -> revision 4, hash unchanged; concurrent If-Match 3 -> 409`.
- **Test ID:** `PRE_026_layout_revision_append_only_without_approval`, `PRE_026_two_saves_over_the_same_revision_at_once_make_exactly_one_revision`, `PRE_026_the_table_refuses_to_change_or_delete_a_revision`, `PRE_026_a_layout_can_be_saved_for_a_draft`, `PRE_026_layout_bodies_and_problems_match_the_contract`

### PRE-199 A layout is checked against its schema and the steps of its version, needs the right permission, and never crosses tenants
- **Traces to:** `TR-DEF-02`, `TR-TEN-01`, #102
- **Milestone:** M3
- **Given:** `kyc-onboarding` v1 of `bank-a`; `carol` holds `processes:author`; `viewer` holds `processes:read` only; a caller of `bank-b` holds both.
- **When:** `carol` saves a layout with `schemaVersion` 2, a box with a text `x` and an unknown member, a lane with a repeated step and a step the version lacks, a link to a step the version lacks, and an unknown top-level member; she repeats a valid save with the same `Idempotency-Key`, and reuses the key for another layout; `viewer` saves; a caller with neither permission reads; the `bank-b` caller reads, lists and saves `bank-a`'s layout.
- **Then:** The invalid save is `422 LAYOUT_INVALID` listing each problem as a path and a reason in `paths`, at most 20, never a value, and writes nothing. The repeat returns the first revision and writes nothing; the reused key is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. `viewer`'s save is `403 PERMISSION_DENIED`, the read without permission as well. The `bank-b` calls are `404 DEFINITION_NOT_FOUND`, no row or event is written, and `bank-b` has its own layout history for its own version.
- **Example:** `nodes.ghost-step -> 422, paths ["$.nodes.ghost-step names a step that is not in the definition"]; bank-b reads bank-a's layout -> 404`.
- **Test ID:** `PRE_199_a_layout_is_validated_against_its_schema_and_the_steps_of_the_version`, `PRE_199_a_save_is_idempotent_and_a_key_is_not_reused_for_another_layout`, `PRE_199_saving_needs_the_author_permission_and_reading_the_read_permission`, `PRE_199_another_tenant_can_neither_read_nor_save_a_layout_and_nothing_is_written`

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
- **Scope in 0.2.0:** the export covers the executable subset the reader accepts (none start and end events, `serviceTask`, `businessRuleTask`, `userTask`, `exclusiveGateway`, `sequenceFlow` with conditions). `agentTask` and `saga` steps join the round trip when the reader accepts them. The export carries a generated left-to-right BPMNDI layout, not the layout of the imported file, and no foreign extensions; neither is part of the definition hash. A version of another tenant is `404 DEFINITION_NOT_FOUND`, the same answer as a missing one, and nothing is written.

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
- **Given:** `CallerResolver` returns user `minh`, roles `[credit-officer, ops]`, group `[branch-hcm-01]`; 30,000 open tasks for `credit-officer`, 12 claimed by `minh`, 3 overdue; open tasks for `ops` and `branch-hcm-01`, some without a due time; tenant `bank-b` holds tasks for the same names.
- **When:** `GET /tasks?assignment=mine`, `GET /tasks?assignment=candidate&limit=50`, `GET /tasks?assignment=candidate&slaBreached=true`, and `GET /tasks?limit=500`.
- **Then:** `mine` (tasks declared to the caller or claimed by them) returns the 12 tasks ordered by `dueAt`; `candidate` (open tasks of the caller's roles and groups) returns 50 tasks across both roles and the group ordered by `(dueAt, id)` with a `nextCursor`; `slaBreached` returns the 3 overdue tasks; `limit=500` returns `400 QUERY_INVALID` naming the maximum 200. Tasks without a due time follow the others, by `id`. `role` and `group` narrow `candidate` and `any`, and with `mine` alone are `400 QUERY_INVALID`. `assignment=any` needs `processes:task:manage`. Items carry no variables and no review context. Only the caller's tenant is read: `bank-b`'s tasks never appear, a cursor of another tenant or of other filters is `400 QUERY_INVALID`, and a list writes nothing. Each range (the user, the claims, each role, each group, or the tenant) is read through its own partial index for at most one page and merged; the query-plan check shows only partial-index range scans, in index order, under a `Limit`.
- **Example:** `candidate limit 50 -> 50 items, nextCursor; limit 500 -> 400 QUERY_INVALID (max 200)`.
- **Test ID:** `PRE_030_task_query_filters_bounded_by_index` (`TaskQueryPostgresIT`), `PRE_030_*` in `TaskQueryPostgresIT` and, over HTTP, `TaskLifecycleIT`

### PRE-036 Every list query passes the CI query-plan check
- **Traces to:** `TR-PERF-07`
- **Milestone:** M2
- **Given:** The query registry of `process-engine-postgres`, where every multi-row read declares its bound (a page ordered on an index and ended by `LIMIT ?`, or one parent key), and a seeded database with 2 tenants (5,000 instances, 10,000 tokens, 5,000 tasks, 2,000 jobs and 20,000 history rows each) with sequential scans disabled, so the planner scans a table in full only where no index serves the query.
- **When:** The query-plan check runs `EXPLAIN (FORMAT JSON)` for every registered query with representative parameters, and an architecture test looks for multi-row reads outside the registry.
- **Then:** No plan scans an engine table sequentially or walks a whole index without an index condition; every page plan has a `Limit`; every parent-key plan has an index condition on its key; a multi-row read outside the registry, or a query whose SQL breaks its declared bound, fails the build. Partition bounds join the check with the first list query on `pre_history`.
- **Example:** `unregistered GROUP BY on pre_history -> build fails`; `GROUP BY on pre_history registered under a parent key it has no index for -> plan check fails`.
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
- **Then:** The step transaction commits an incident with `error_code = UNHANDLED_BUSINESS_ERROR` and the business code `LIMIT_EXCEEDED` in `cause_summary`, `INCIDENT_RAISED` in history with `businessCode: LIMIT_EXCEEDED`, and `processes.incident.raised.v1`. The token stays on `check-limit` in `WAITING_ASYNC`, the instance stays `RUNNING`, and the call that reached the step (here the start) succeeds. No retry is scheduled because a business error is not retryable. The handler's message ("Daily limit reached") is never stored. Variables the earlier steps of the same transaction set are kept. `:retry` after the host fixes the data runs the handler again with a new `attemptId` (#68, `PRE-078`).
- **Example:** `BusinessError(LIMIT_EXCEEDED) -> incident UNHANDLED_BUSINESS_ERROR, token on check-limit`.
- **Test ID:** `PRE_040_business_error_result_opens_incident`, `PRE_040_a_business_error_keeps_the_token_on_the_step_and_asks_for_an_incident`

### PRE-041 Cancelling an instance during an adapter call discards the late result
- **Traces to:** `TR-ASY-01`, `TR-ASY-02`, `TR-STD-12`
- **Milestone:** M2
- **Given:** Instance `I1` on `agentTask` `score-applicant` with its job `CLAIMED` by replica A and a stub adapter that answers after 2 s.
- **When:** `POST /instances/I1:cancel` commits after 0.5 s, and the adapter answers at 2 s.
- **Then:** The cancel transaction sets the instance `CANCELLED`, the token `CANCELLED`, the job `CANCELLED` and writes `TOKEN_CANCELLED`; replica A's TX2 selects no row for `status = 'CLAIMED'`, writes only `LATE_RESULT_DISCARDED` with the attempt id, and does not advance, retry or open an incident; no connection was held during the adapter call.
- **Example:** `cancel at 0.5 s, adapter result at 2 s -> LATE_RESULT_DISCARDED, instance CANCELLED`.
- **Test ID:** `PRE_041_cancel_during_adapter_call_discards_late_result` (remote step), `PRE_041_cancel_during_agent_call_discards_late_result` (agent case, #52: no guardrail decision is evaluated for the late answer)

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
- **Test ID:** `PRE_044_*`: the join half in `ParallelGatewayTest` and `ParallelJoinPostgresIT` (#251), the compensation half in `SagaCompensationTest` and `SagaBlockTest` (#67)

### PRE-045 Engine REST bodies ignore the host's JSON settings
- **Traces to:** `TR-EMB-07`, `TR-API-06`
- **Milestone:** M2
- **Given:** A host with the Process Engine starter and REST module, and host properties `spring.jackson.property-naming-strategy=SNAKE_CASE` and dates written as timestamps (`spring.jackson.serialization.write-dates-as-timestamps=true` on Spring Boot 3.5, `spring.jackson.datatype.datetime.write-dates-as-timestamps=true` on Spring Boot 4, where Jackson 3 moved the switch); the same test runs in the Spring Boot 4.x leg and in the Spring Boot 3.5 leg, where the host's own Jackson 2 stays at the version its dependency management pins.
- **When:** `POST /api/bpm/processes/v1/instances` with `Idempotency-Key: 01JD2X5K9T1Q4N8V2M7B3C6D0E` and body `{ "definitionKey": "card-fee-review", "businessKey": "APP-1001", "variables": { "applicationId": "APP-1001", "customerId": "C-002", "applicantName": "Tran Thi B" } }`, then `GET /api/bpm/processes/v1/instances/{id}`.
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

### PRE-049 Definition and instance lists are keyset pages with cursors signed for the tenant and the list
- **Traces to:** `TR-TEN-01`, `TR-PERF-07`, ADR-0015
- **Milestone:** M2
- **Given:** Tenant `bank-a` with definitions `card-fee-review` (v1 `ACTIVE`) and `card-limit-review`, and five running instances of `card-fee-review` started one after another; tenant `bank-b` with none. `query.max-page-size = 200`, `query.max-range = P31D`.
- **When:** A `bank-a` caller with `processes:read` reads `GET /definitions?limit=1` and `GET /instances?definitionKey=card-fee-review&limit=2`, following `nextCursor` until `hasNext` is false; then sends `limit=0`, `limit=201`, `status=OPEN`, a 32-day range, `startedTo` before `startedFrom`, a cursor with an edited payload, a cursor with other filters; and a `bank-b` caller replays a `bank-a` cursor.
- **Then:** Pages are `{data, nextCursor, hasNext}`; definitions come ordered by key with `latestVersion`, `latestStatus`, `effectiveVersion` and `lastChangedAt`; instances come newest first (`startedAt`, then `id`, both descending) with no gap and no duplicate across pages and no variables, tokens or tasks; every refused query is `400 QUERY_INVALID`; the `bank-b` caller gets `400 QUERY_INVALID`, lists no `bank-a` row and reads no `bank-a` version (`404 DEFINITION_NOT_FOUND`). `GET /definitions/{key}/versions/{v}` answers the version with its definition hash as `ETag`, which is the `Location` an import returns.
- **Example:** `5 instances, limit=2 -> pages of 2, 2, 1, newest first; bank-a cursor sent by bank-b -> 400 QUERY_INVALID`.
- **Test ID:** `PRE_049_definition_and_instance_lists_are_signed_keyset_pages`

### PRE-050 Cancelling an instance is idempotent, optimistically locked and ends every live token and open task
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-03`, `TR-OBS-04`
- **Milestone:** M2
- **Given:** Instance `I1` of `card-fee-review` in `bank-a`, waiting at the officer review task, `lockVersion = 1`; branch supervisor `hoa` with `processes:cancel`; teller `tuan` with `processes:start` only.
- **When:** (a) `hoa` posts `POST /instances/I1:cancel` with `If-Match: "1"`, `Idempotency-Key: k1` and `{ "reason": "Customer withdrew the card application" }`, then replays it, then sends `k1` with another reason, then cancels again with a new key. (b) Two requests cancel another running instance at the same moment with the same `If-Match` and different keys. (c) Four requests cancel a third instance at the same moment with one key. (d) `If-Match` is stale; `tuan`, who may start but not cancel, cancels; the reason is missing, blank or 2,001 characters; or `If-Match` or `Idempotency-Key` is missing.
- **Then:** (a) `200` with status `CANCELLED`, the task `CANCELLED` and `endedAt` set; one `INSTANCE_ENDED` history entry with actor `hoa` and the reason, and one `processes.instance.cancelled.v1` carrying `cancelledBy: hoa` and the reason, both in the transaction of the change; the replay returns the same body and writes nothing; the other reason is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`; the second cancel is `409 INVALID_STATE`. (b) One `200` and one `409 VERSION_CONFLICT`, one history entry. (c) Four `200` with one body, one history entry. (d) `409 VERSION_CONFLICT`, `403 PERMISSION_DENIED`, `400 INPUT_INVALID`, `428 PRECONDITION_REQUIRED`; nothing is written. `/api/bpm/me` lists `processes:cancel` for `hoa` and not for `tuan`. The permission, the reason and the absence of compensation are stated by `PRE-054` (PD-103): cancel cancels tokens and tasks under the instance lock, makes no adapter call and runs no compensation in 0.2.0.
- **Example:** `cancel(I1, If-Match "1", k1) -> 200 CANCELLED; replay -> same body; second cancel -> 409 INVALID_STATE`.
- **Test ID:** `PRE_050_cancel_is_idempotent_locked_and_ends_tokens_and_tasks`

### PRE-051 A user task is claimed before it is completed, and only its claimant completes or hands it back
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-03`, `TR-SEC-04`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** Card application `IT-T-CLAIM` submitted by `tuan`; its `officer-review` task `T1` is `OPEN` at `lockVersion = 0`, assigned to `role:CARD_OFFICER` and marked four-eyes; officers `an` and `hoa`; `zoe` is an officer of `bank-b`.
- **When:** (a) `an` reads `GET /tasks/T1`, claims it with `If-Match: "0"` and `Idempotency-Key: claim-1`, replays the claim, then sends `claim-1` with `If-Match: "5"`. (b) `hoa` unclaims it; `an` unclaims it with `If-Match: "1"` and replays. (c) `an` and `hoa` claim another open task at the same moment with `If-Match: "0"`. (d) `an` completes an `OPEN` task; `hoa` completes a task `an` has claimed. (e) `zoe` reads, claims, unclaims or completes `T1`. (f) A caller without `processes:task:work` claims; a claim without `If-Match` or `Idempotency-Key`.
- **Then:** (a) The read has `ETag: "0"` and `allowedActions: ["claim"]`; the claim is `200` with `status: CLAIMED`, `claimedBy: an`, `lockVersion: 1`, `ETag: "1"`, `allowedActions: ["unclaim", "complete"]`; the replay returns the same body; the reused key is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. (b) `hoa` gets `403 TASK_NOT_ASSIGNED`; `an` gets `200` with `status: OPEN` and no claimant; the replay returns the same body; one `TASK_CLAIMED` and one `TASK_UNCLAIMED` history entry and one `processes.task.claimed.v1` and one `processes.task.unclaimed.v1` exist. (c) One `200` and one `409 VERSION_CONFLICT`, one `TASK_CLAIMED` entry. (d) `409 INVALID_STATE` ("claim it first") and `403 TASK_NOT_ASSIGNED`; nothing is written. (e) `404 TASK_NOT_FOUND` each time; no row of `bank-b` is written and `T1` stays at `lockVersion 0`. (f) `403 PERMISSION_DENIED`, `428 PRECONDITION_REQUIRED`.
- **Example:** `claim(T1, "0") by an -> 200 CLAIMED, lockVersion 1; complete(T1, "1") by hoa -> 403 TASK_NOT_ASSIGNED; complete(T1, "1") by an -> 200`.
- **Test ID:** `PRE_051_task_is_claimed_before_completion_and_only_its_claimant_completes`

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
    - on a host with `lifecycle.retire-last-version: refuse`, the confirmed retire;
    - a second key `kyc-onboarding` with v1 and v2 `ACTIVE`, v2 effective since `2027-04-20T08:00:00Z` and its `DEFINITION_EFFECTIVE` job not yet run, so v1 is still `ACTIVE`: `bob` retires v1, with or without the confirmation.
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
    - under `refuse`, the confirmed retire gets `409 LAST_VERSION_IN_EFFECT` with `setting: refuse`;
    - retiring the superseded `kyc-onboarding` v1 gets `409 INVALID_STATE` with a detail naming v2 (PD-102, as `RLE-026`); when the job runs, v1 is `RETIRED` with `retired_by = system:effective-date` and `superseded_by = 2`. Retiring v2, the version in effect, behaves as steps 2 and 3.
    - None of them changes anything: no history row, no event, no stored answer.
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

### PRE-055 A decision step after a timer runs in the instance's tenant, as the engine, with no caller
- **Traces to:** `TR-TEN-01`, `TR-SEC-03`, ADR-0027
- **Milestone:** M2
- **Given:** Tenants `bank-a` and `bank-b` each publish a rule set `card-reminder-fee`: a `CLASSIC` card pays 20,000 VND at `bank-a` and 35,000 VND at `bank-b`. Each tenant runs an instance of `card-fee-reminder` (start, a `P3D` timer, `businessRuleTask` `price-reminder`, user task `collect-fee`) for a `CLASSIC` card. The job poller thread has no caller, and its tenant resolver falls back to a third tenant `bank-fallback`.
- **When:** Both timers are due at the same instant and one poll fires them.
- **Then:**
  - Each decision is evaluated in the tenant of its stored instance row through the tenant-scoped Rule Engine entry point: `bank-a`'s instance gets 20,000 VND, `bank-b`'s gets 35,000 VND.
  - Each `rle_decision` row is in the instance's tenant, with caller `system:process-engine` and the instance id as correlation id; no row is written in `bank-fallback`.
  - Each instance records `DECISION_EVALUATED`, keeps the decision id in its result variable, moves on to an open `collect-fee` task, and its timer is `DONE`.
  - The engine-owned context is a parameter of the fire transaction, never a thread-local, and is built only by the job handler; the caller-tenant guard for requests is unchanged.
- **Example:** `two timers due together, poller context bank-fallback -> bank-a 20000 VND, bank-b 35000 VND, caller system:process-engine`.
- **Test ID:** `PRE_055_decision_after_a_timer_runs_in_the_instance_tenant_as_the_engine`

### PRE-056 A decision step after a timer never reads another tenant's rule set, and fails closed
- **Traces to:** `TR-TEN-01`, ADR-0027
- **Milestone:** M2
- **Given:** `card-reminder-fee` exists only in `bank-d`. An instance of `card-fee-reminder` runs in `bank-c`, and the poller thread's context even resolves to `bank-d`. Separately, a host whose Rule Engine can only resolve the tenant from the thread (no tenant-scoped entry point) runs an instance in `bank-e`.
- **When:** Each timer becomes due and the poller fires it.
- **Then:**
  - `bank-c`: the decision fails with `RULE_SET_NOT_FOUND` in `bank-c` and the firing rolls back: no `rle_decision` row in `bank-c` or `bank-d`, no task, no `TIMER_FIRED` or `DECISION_EVALUATED`, no result variable; the token still waits at the timer and the job is `DUE` again with attempt 1 after `jobs.retry-backoff`, and `FAILED` after `jobs.max-attempts`.
  - `bank-e`: the step fails closed with `TENANT_NOT_ALLOWED` without calling the Rule Engine, and the firing rolls back the same way.
  - Separately, `card-reminder-fee` in `bank-f` prices Gold cards only and a Classic card's timer fires: the Rule Engine writes the decision row, the step fails with `DECISION_NO_MATCH`, and that row rolls back with the firing (no `rle_decision` row for the instance, job `DUE` with attempt 1).
- **Example:** `rule set only in bank-d, bank-c timer fires -> RULE_SET_NOT_FOUND, nothing written, job DUE attempt 1`.
- **Test ID:** `PRE_056_decision_after_a_timer_never_reads_another_tenants_rule_set`, `PRE_056_a_rule_engine_without_an_explicit_tenant_cannot_run_a_decision_after_a_timer`, `PRE_056_a_decision_row_written_before_the_step_fails_rolls_back_with_the_firing`

### PRE-057 Each async job kind runs on its own runner, so a backlog of one kind never delays another
- **Traces to:** `TR-ASY-01`, `TR-ASY-02`, `TR-PERF-05`, #363
- **Milestone:** M2
- **Given:** A replica with `runners.async-agent.slots: 2` and nothing else set. The document assessor (an `ASYNC_AGENT` job of `loan-document-assessment`) holds every call; five loans wait for it, and one loan of `loan-bureau-check` waits for the credit bureau (`ASYNC_SERVICE`). Separately, `runners.async-service.max-attempts: 5` and `runners.async-agent.retry-backoff: PT4S`.
- **When:** The replica polls twice while the assessor holds its calls, the assessor is released, and the replica polls again. Separately, an assessor attempt fails with a retryable error.
- **Then:**
  - The first poll starts 3 calls: 2 assessments and the bureau call. The second poll starts none: the assessor's 2 slots are taken, and the bureau's slots are its own. 3 assessment jobs stay `DUE`, and the bureau loan reaches `underwrite`.
  - Each kind is claimed in its own statement, up to that kind's free slots, on the index `idx_pre_timer_poll_kind` (V9). Timers keep `jobs.*` on the timer poller.
  - A remote service job is parked with its kind's `max-attempts` (5); a failed assessor attempt is due again after its kind's back-off (`T0 + 4 s`).
  - With nothing set under `runners.*`, every kind has `adapters.executor-threads` slots, `adapters.default-timeout`, `jobs.max-attempts`, `jobs.retry-backoff` and a 500 ms guard budget, which is the behaviour before this statement; a remote service result is accepted without a result check.
- **Example:** `2 assessor slots, 5 assessments and 1 bureau call due -> 3 started, bureau loan on underwrite, 3 assessments DUE`.
- **Test ID:** `PRE_057_a_backlog_of_one_kind_never_takes_the_slots_or_claims_of_another`, `PRE_057_each_kind_parks_and_retries_with_its_own_settings`, `PRE_057_remote_service_results_are_not_guarded`, `PRE_057_a_host_that_sets_nothing_runs_every_kind_with_the_engine_wide_values`, `PRE_057_runners_default_to_the_engine_wide_keys_and_take_per_kind_values`

### PRE-058 An agent result that breaks its output schema is refused in the result transaction, by any path
- **Traces to:** `TR-AGT-01`, `TR-ASY-02`, #363
- **Milestone:** M2
- **Given:** The `assess-documents` step's guard spec requires `{ riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE }` with no other field. In the first case the assessor answers `riskScore: "high"`. In the second it hands the call off, and its callback, on another replica, reports `{ riskScore: 140, recommendation: APPROVE, comment: "ok" }` through `POST /async-steps/{jobId}:complete`.
- **When:** The executor records the first answer, and the assessor's retry breaks the schema again; the completion API records the second. A third step runs with `runners.async-agent.schema-retries: 0` and `onViolation: fail`.
- **Then:** The first bad answer of each path is `ACCEPTED`, because the step is not decided yet: it records `ASYNC_ATTEMPT_FAILED` with `AGENT_SCHEMA_INVALID`, `retryable: true` and `schemaRetry: 1`, the job is `DUE` again after the back-off with one more allowed attempt (`max_attempts` 3 to 4, so the schema retry does not use up an attempt for technical failures), and the token still waits on the step. The retry that breaks the schema again is `REFUSED`: `ASYNC_RESULT_REFUSED` with code `AGENT_SCHEMA_INVALID`, the violations as reasons, and the step's route (here a four-eyes review task). With no schema retries the first bad answer is `REFUSED` and the job `FAILED`. No path writes `ASYNC_RESULT_ACCEPTED` or a result variable, and none evaluates the guardrail rule set. The violations name paths and rules, never values. The check runs in `ProcessServices.recordAttempt` only: a runner may pre-check, but the result transaction is the authoritative check, and it calls nothing outside the JVM (an ArchUnit rule).
- **Example:** `riskScore "high" -> ACCEPTED (retry 1 of 1), job DUE; "high" again -> REFUSED, AGENT_SCHEMA_INVALID, review task; nothing accepted`.
- **Test ID:** `PRE_058_an_answer_breaking_its_output_schema_is_retried_once_then_refused_to_review`, `PRE_058_with_no_schema_retries_the_first_bad_answer_is_refused`, `PRE_058_an_answer_breaking_its_output_schema_is_refused_through_the_completion_api`, `PRE_058_result_guards_evaluate_in_process_only`

### PRE-059 An agent result its guardrail refuses goes to a reviewer, by any path, and the reviewer completes the step
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-SEC-04`, #363
- **Milestone:** M2
- **Given:** The guardrail rule set `agent-output-guardrails` (`hitPolicy: first`; `R1`: `recommendation == 'DECLINE' -> allow: true`; `R2`: `riskScore >= 70 -> allow: false, reasons: 'HIGH_RISK_SCORE'`; `R3`: default `allow: true`) is published in the tenant. The step's guard spec names it, with `onViolation: route-to-human` and `reviewer: role:COMPLIANCE_REVIEWER`.
- **When:** The assessor answers `{ riskScore: 85, recommendation: APPROVE }`, once through the executor and once through the completion API (repeated). A compliance reviewer then completes the review task with `{ riskScore: 85, recommendation: REFER }`. Separately, the same answer arrives for a step with `onViolation: fail`, and `{ riskScore: 20, recommendation: APPROVE }` arrives for a normal step.
- **Then:**
  - The 85 answer is `REFUSED` by both paths, and the repeat of the callback is `DUPLICATE` and writes nothing. `ASYNC_RESULT_REFUSED` records code `GUARDRAIL_VIOLATION`, reasons `["HIGH_RISK_SCORE"]`, rule ids `["R2"]`, the `rle_decision` id as `guardrailDecisionId`, route `review` and the refused output. No `ASYNC_RESULT_ACCEPTED` and no result variable are written.
  - The token moves to `WAITING_USER` on the same step with one four-eyes task for `role:COMPLIANCE_REVIEWER` (`STEP_ROUTED_TO_REVIEW`); the job is `DONE`. The instance's starter cannot claim the task (`403 SELF_APPROVAL_FORBIDDEN`). The reviewer's completion stores the reviewer's answer as the step result, and the loan moves on to `underwrite`.
  - With `onViolation: fail`, the job is `FAILED`, route `incident` is recorded, no task opens, and the token stays on the step until incidents exist (#68).
  - The refused output in `ASYNC_RESULT_REFUSED` is masked: a value is kept only where the output schema declares the field and does not mark it `mask: true`; undeclared fields are `***`, strings are cut at 200 characters, lists and nested objects at 20 entries, nesting at 4 levels, and `outputFields` gives the field count.
  - The identity that reported the refused result through the completion API cannot claim or complete its review task (`403 SELF_APPROVAL_FORBIDDEN`), even when it holds the reviewer role. A guard spec with `fourEyes: false` is `GUARD_SPEC_INVALID`, and its review task is still four-eyes.
  - A reviewer's answer that breaks the output schema is `422 FORM_VALIDATION_FAILED` with the violations; nothing is written and the task stays claimed.
  - When the review task cannot be created, the caller reads `REFUSED`, `ASYNC_RESULT_REFUSED` is still recorded with route `incident` and the `routeError`, and the job is `FAILED`; the refusal is never rolled back into a retry.
  - The 20 answer is `ACCEPTED` and moves on; `ASYNC_RESULT_ACCEPTED` carries `guardrailDecisionId` and rule ids `["R3"]`.
- **Example:** `riskScore=85 APPROVE -> R2 HIGH_RISK_SCORE -> REFUSED, review task for COMPLIANCE_REVIEWER -> reviewer REFER -> underwrite`.
- **Test ID:** `PRE_059_a_guardrail_violation_routes_to_a_reviewer_whose_answer_completes_the_step`, `PRE_059_a_guardrail_violation_is_refused_the_same_way_through_the_completion_api`, `PRE_059_with_on_violation_fail_the_job_fails_and_nothing_is_accepted`, `PRE_059_an_answer_the_guardrail_allows_advances_with_its_decision_recorded`, `PRE_059_allow_false_refuses_and_routes_to_the_reviewer_with_reasons_and_rules`, `PRE_059_the_guardrail_is_evaluated_in_the_instance_tenant_as_the_engine_on_the_output`, `PRE_059_the_history_keeps_a_refused_output_masked_and_bounded`, `PRE_059_the_caller_who_submitted_a_refused_result_cannot_approve_its_review`, `PRE_059_a_payload_cannot_switch_four_eyes_off`, `PRE_059_a_payload_that_switches_four_eyes_off_is_refused_and_still_reviewed`, `PRE_059_the_reviewers_answer_must_meet_the_output_schema`, `PRE_059_a_refusal_whose_review_task_cannot_be_created_is_recorded_and_fails_the_job`

### PRE-060 A guardrail that cannot decide fails closed
- **Traces to:** `TR-AGT-01`, `TR-SEC-01`, #363
- **Milestone:** M2
- **Given:** Agent answers that the guardrail would allow (`{ riskScore: 20, recommendation: APPROVE }`), in each of these hosts or steps: the guardrail rule set was never published in the tenant; the host has no Rule Engine; the output schema check throws; the check takes 600 ms against `runners.async-agent.guard-budget` of 500 ms (measured on the engine clock); the job has no guard spec. In unit tests, the decision answers `allow: "yes"`, no `allow`, no match, a failure, or throws; and a schema uses an unsupported keyword (`pattern`, `$ref`).
- **When:** Each result is recorded.
- **Then:** Every one is `REFUSED` and nothing of it is accepted. The code is `GUARDRAIL_EVALUATION_ERROR` with the cause as reason (`RULE_ENGINE_MISSING`, `IllegalStateException`, `GUARD_BUDGET_EXCEEDED`, `GUARDRAIL_CONTRACT_INVALID`, `DECISION_NO_MATCH`, the decision's code), and the step goes to its reviewer. The budget is checked after the schema check, so a schema check that uses it up never reaches the decision, and again after the decision. A missing or malformed guard spec is `GUARD_SPEC_INVALID` and fails the job, because no reviewer can be trusted from it. An unsupported schema keyword is a violation, never skipped.
- **Example:** `rule set missing -> REFUSED, GUARDRAIL_EVALUATION_ERROR, review task; no guard spec -> REFUSED, GUARD_SPEC_INVALID, job FAILED`.
- **Test ID:** `PRE_060_a_missing_guardrail_rule_set_fails_closed_to_review`, `PRE_060_a_host_without_a_rule_engine_refuses_every_agent_answer`, `PRE_060_a_check_that_breaks_or_overruns_its_budget_fails_closed`, `PRE_060_an_agent_job_without_a_guard_spec_fails_and_is_never_accepted`, `PRE_060_a_guardrail_that_cannot_decide_fails_closed`, `PRE_060_without_a_rule_engine_the_result_is_refused`, `PRE_060_a_check_slower_than_the_budget_refuses_even_an_allowed_result`, `PRE_060_a_missing_or_malformed_guard_spec_fails_the_job`, `PRE_060_a_schema_check_that_uses_up_the_budget_never_reaches_the_decision`, `PRE_060_an_unsupported_keyword_fails_closed`

### PRE-061 An agent step's guard spec is validated at import, and an invalid one never becomes a draft
- **Traces to:** `TR-AGT-01`, `TR-SEC-04`, `TR-DEF-04`, #52
- **Milestone:** M2
- **Given:** `loan-agent-assessment` with a `serviceTask` `assess-documents`, `horus:type="agentTask"`, `horus:agent="documents.assess"`, `horus:timeout="PT2S"`, inputs `applicationId` and `requestedAmount`, a `<horus:outputSchema>` holding `{ riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE, summary?: string, mask: true }` and `<horus:guardrail decision="agent-output-guardrails" onViolation="route-to-human" reviewer="role:COMPLIANCE_REVIEWER"/>`. The host registers the agent adapter `documents.assess` and has the Rule Engine. Variants: no schema; a schema that is not JSON or not an object; a schema with `pattern`; no guardrail; a guardrail without `decision`, with `onViolation: ignore`, with `route-to-human` and no or a malformed reviewer, with `fail` and a reviewer, or with `fourEyes="false"`; two guardrails; no `horus:agent`; `horus:handler` or `horus:mode` on the step; `horus:timeout="PT0S"`; an agent `documents.summarise` the host does not register; a host without the Rule Engine.
- **When:** Each document is imported.
- **Then:** The valid one is a draft whose canonical form carries `type: agentTask`, `agent`, the schema as canonical JSON, `guardrails` (`decision`, `onViolation`, `reviewer`) and `timeout`, so the definition hash covers the whole guard spec and a change of the reviewer, the rule set or a schema bound is a new version; its BPMN export reads back to the same hash. `onViolation` defaults to `route-to-human`. Each variant is `422 VALIDATION_FAILED` with a problem naming `assess-documents`: `GUARD_SPEC_INVALID` for the schema and guardrail faults (a schema keyword outside the host validator's subset is refused here, not only when an answer arrives), `AGENT_MISSING`, `AGENT_TASK_INVALID`, `TIMEOUT_INVALID`, `AGENT_NOT_REGISTERED`, and `BPMN_NOT_EXECUTABLE` without the Rule Engine. No `pre_definition` row is written. A problem never echoes the schema text. Four-eyes cannot be switched off: a review is always four-eyes. The guardrail rule set's own contract is checked by `PRE-016`.
- **Example:** `fourEyes="false" -> 422 GUARD_SPEC_INVALID at assess-documents, nothing written`.
- **Test ID:** `PRE_061_an_agent_step_reads_with_its_agent_schema_guardrail_inputs_and_timeout`, `PRE_061_the_canonical_form_carries_the_whole_guard_spec_so_the_hash_covers_it`, `PRE_061_on_violation_defaults_to_route_to_human_and_fail_needs_no_reviewer`, `PRE_061_an_invalid_guard_spec_is_refused_naming_the_step`, `PRE_061_an_agent_step_needs_its_agent_and_takes_no_handler_mode_or_bad_timeout`, `PRE_061_a_schema_in_the_subset_passes_the_import_check`, `PRE_061_a_schema_outside_the_subset_is_refused_at_import_naming_the_path`, `PRE_061_a_host_validator_accepts_every_schema_at_import_unless_it_says_otherwise`, `PRE_061_an_invalid_guard_spec_or_an_unknown_agent_is_refused_at_import_and_nothing_is_written`, `PRE_061_a_host_without_a_rule_engine_cannot_import_an_agent_step`

### PRE-062 An agent step parks an agent job with the published guard spec, and its model call is keyed by the attempt id
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-ASY-01`, `TR-IDEM-04`, `TR-TEN-01`, #52
- **Milestone:** M2
- **Given:** `loan-agent-assessment` v1 active, the guardrail rule set published, the stub agent adapter `documents.assess`, `runners.async-agent.schema-retries` unset (1), `jobs.retry-backoff` 10 s. Loan `LOAN-2027-0902` asks for 250,000,000 VND; the agent first answers `riskScore: "high"`, then `{ riskScore: 20, recommendation: APPROVE }`. Separately the agent answers `riskScore: 140` twice; and the agent hands a call off and a callback client of tenant `bank-other` reports an answer for it.
- **When:** A loan reaches the step, and the replica runs its attempts.
- **Then:**
  - The step transaction parks the token in `WAITING_ASYNC` and inserts one `ASYNC_AGENT` job leased for the step timeout plus `jobs.lease-margin` (32 s) with the agent runner's attempts, and a payload of `target`, `timeoutMs`, `businessKey`, the bounded `inputs` and `guard` (`outputSchema`, `decision`, `onViolation`, `reviewer`, `fourEyes: true`), read from the node of the definition version being run, never from a request. `ASYNC_STEP_SCHEDULED` records `jobKind: ASYNC_AGENT`.
  - The agent runner calls the host's `AgentAdapter` with an `AgentInvocation` whose `modelCallKey()` is the attempt id and whose `idempotencyKey()` is the job id. The bad first answer is given back once (`ASYNC_ATTEMPT_FAILED`, `AGENT_SCHEMA_INVALID`, `schemaRetry: 1`, `max_attempts` 3 to 4); the retry after the back-off is a new model call key with the same idempotency key, its answer is allowed (`R3`), and the loan moves on to `underwrite`.
  - Two bad answers: the second is `REFUSED` with `AGENT_SCHEMA_INVALID`, no guardrail decision is evaluated, and the token waits on a four-eyes review task for `role:COMPLIANCE_REVIEWER` that the loan's starter cannot claim (`SELF_APPROVAL_FORBIDDEN`).
  - The other tenant's callback is `404 ASYNC_STEP_NOT_FOUND` and writes nothing; the job stays `CLAIMED`.
  - Only the agent runner calls `AgentAdapter.execute` (an ArchUnit rule); a name with no adapter fails the attempt with `AGENT_NOT_REGISTERED`, not retryable.
- **Example:** `riskScore "high" then 20 -> attempt 1 key A1, attempt 2 key A2, both idempotency key = job id -> underwrite`.
- **Test ID:** `PRE_062_an_agent_step_parks_an_agent_job_with_the_guard_spec_of_the_published_version`, `PRE_062_the_model_call_is_keyed_by_the_attempt_id_and_one_bad_answer_is_asked_again`, `PRE_062_a_second_bad_answer_is_refused_to_the_four_eyes_review_which_the_starter_cannot_take`, `PRE_062_another_tenants_agent_job_is_not_found_and_nothing_is_written`, `PRE_062_the_model_call_is_keyed_by_the_attempt_id_and_side_effects_by_the_job_id`

### PRE-063 An agent answer is recorded masked, and its review task carries why it was refused
- **Traces to:** `TR-AGT-02`, `TR-SEC-05`, `TR-OBS-01`, #52
- **Milestone:** M2
- **Given:** The output schema of `assess-documents` marks `summary` `mask: true`. The agent answers `{ riskScore: 20, recommendation: APPROVE, summary: "Applicant Nguyen Van An, national id 079088001234, ..." }` for one loan and the same with `riskScore: 85` for another.
- **When:** Each answer is recorded.
- **Then:** The allowed answer is the step result as given, and `ASYNC_RESULT_ACCEPTED` records it masked by the policy of `ASYNC_RESULT_REFUSED` (`PRE-059`): `{ riskScore: 20, recommendation: APPROVE, summary: "***" }`, `outputFields: 3`, with `guardrailDecisionId`; no history row of the loan contains the national id. The refused answer opens the review task, whose `review` object (task view, `GET /tasks/{id}`, migration V10 `pre_task.review`) carries `code: GUARDRAIL_VIOLATION`, `reasons: [HIGH_RISK_SCORE]`, `ruleIds: [R2]`, `guardrailDecisionId`, the `inputs` the agent saw and the masked `output` with `outputFields`; every other task has no `review`. The reviewer's answer `{ riskScore: 85, recommendation: REFER }` completes the step and the loan moves on to `underwrite`.
- **Example:** `summary with a national id -> history "***", review task output.summary "***"`.
- **Test ID:** `PRE_063_an_accepted_agent_answer_is_recorded_masked_by_the_output_schema`, `PRE_063_the_review_task_carries_why_the_answer_was_refused_and_the_masked_answer`

### PRE-092 A candidate guardrail is replayed over recorded answers and reports what would change, without calling a model
- **Traces to:** `TR-AGT-03`, `TR-OBS-01`, PD-128, #98
- **Milestone:** M3
- **Given:** Step `assess-documents` of `kyc-onboarding` in one tenant has 1,200 answers recorded in November 2026, checked by `kyc-document-guardrails` v2, whose rule R5 refuses `confidence < 0.90`. 96 of them had a confidence of 0.85 to 0.89 and went to a reviewer; reviewers changed the document type in 7 of those 96 (`HUMAN_OVERRIDE`) and confirmed 2 others. Draft v3 changes R5 to `confidence < 0.85`.
- **When:** A user with `processes:agent:replay` sends `POST /agent-replays` with `{ "definitionKey": "kyc-onboarding", "nodeId": "assess-documents", "from": "2026-11-01", "to": "2026-11-30", "candidate": { "ruleSet": "kyc-document-guardrails", "version": 3 } }` (Java: `AgentReplayService.start`), the job poller replays it, and the user reads `GET /agent-replays/{id}` and `GET /agent-replays/{id}/cases`.
- **Then:** The start answers `202` with a `QUEUED` run. The poller replays it in pages of `replay.page-size` (500): three pages. The summary shows 1,200 cases, 1,104 unchanged, 96 newly allowed, 7 of them `OVERRIDDEN_BY_REVIEWER`, 0 newly refused, 0 newly routed to review. Each line carries the attempt id, the instance id, the recorded and the candidate outcome, the change and the matched rule ids (`R5` recorded, `R9` candidate), never an output value. A change from a masked value is not an override, so the 2 confirmations are not flagged. The stub agent records no call, and `pre_history`, `rle_decision` and every instance row are unchanged.
- **Masked inputs.** A top-level field kept only as `***` is left out of the check. When a rule of the candidate rule set version reads it in a condition or an output (declaring it as an input is not enough), or the schema could test it differently (the check fails closed: the candidate output schema differs from the recorded one anywhere except in the definitions of unmasked properties, whatever the keyword, `type`, combinator, conditional or pattern rule; or the recorded refusal came from the schema, so the failing field is unknown), the check would not have seen it: the line is `NOT_REPLAYED` with code `MASKED_INPUT_REQUIRED`, never `UNCHANGED` or a change, and the summary counts it in `maskedInputRequired`, apart from `notReplayed`. Example: candidate v4 of `kyc-document-guardrails` adds a rule `nationalId` in a list of blocked numbers, and `nationalId` is masked; the 40 answers with `nationalId` masked report `NOT_REPLAYED`, none `UNCHANGED`. A host that needs those cases checked replays them through its own harness (PD-128).
- **Example:** `v3 R5 < 0.85 over 1,200 cases -> 96 newly allowed, 7 of them corrected by a reviewer`.
- **Test ID:** `PRE_092_candidate_guardrail_replay_reports_newly_allowed_cases_and_reviewer_overrides` and `PRE_092_a_candidate_that_reads_a_masked_field_is_not_replayed_and_counted_apart` (`AgentReplayIT`); `PRE_092_*` in `AgentReplayCheckTest` and `KernelContractArchitectureTest`

### PRE-093 The same replay gives the same report, and today() is the recorded time
- **Traces to:** `TR-DET-01`, PD-128, #98
- **Milestone:** M3
- **Given:** Answers recorded on 2 and 3 November 2026; rule R2 of the candidate refuses `expiryDate < today()`, and the answer of 3 November has an expiry date of 10 November 2026.
- **When:** The same replay runs twice on 20 January 2027.
- **Then:** `today()` evaluates to 3 November 2026 for that case, the time its answer was checked, so it is not newly refused. The two runs give identical report lines and summaries; only the run ids and times differ.
- **Example:** `case of 3 Nov, expiry 10 Nov, replayed 20 Jan -> not expired`.
- **Test ID:** `PRE_093_replay_is_deterministic_and_today_is_the_recorded_time`

### PRE-094 A replay is bounded, and a candidate that breaks the contract fails before any case runs
- **Traces to:** `TR-PERF-10`, `TR-AGT-03`, PD-128, #98
- **Milestone:** M3
- **Given:** (a) A period of 45 days. (b) A period that holds more answers than `replay.max-cases` (50,000 by default; the test lowers it to 3 over 4 answers). (c) A candidate whose output is `decision: string` with no `allow`. (d) A recorded answer whose job payload has no guard spec.
- **When:** Each is requested or reached.
- **Then:** (a) `400 QUERY_INVALID`, because the longest period is `query.max-range` (`P31D`). (b) `422 REPLAY_TOO_LARGE`, with `cases` and `limit`. (c) `422 GUARDRAIL_CONTRACT_INVALID` (`PRE-016`), and no run is created. (d) The case is reported `NOT_REPLAYED` with code `GUARD_SPEC_INVALID`, and the run completes. Runs and their reports are deleted `replay.retention` (`P30D`) after they were created; they are not audit records.
- **Example:** `45 days -> 400 QUERY_INVALID; 4 answers over a limit of 3 -> 422 REPLAY_TOO_LARGE`.
- **Test ID:** `PRE_094_replay_is_bounded_and_a_candidate_that_breaks_the_contract_fails_before_any_case_runs`; `PRE_094_*` in `AgentReplayCheckTest`

### PRE-095 Replays read only the caller's tenant and need their own permission
- **Traces to:** `TR-TEN-01`, `TR-SEC-05`, PD-128, #98
- **Milestone:** M3
- **Given:** Run `R1` of tenant A, a user of another tenant, and a tenant A user with `processes:read` only.
- **When:** The other tenant's user reads `R1`, its report and one of its cases, and names tenant A's candidate; the `processes:read` user starts a run and reads `R1`.
- **Then:** The other tenant's user gets `404 REPLAY_NOT_FOUND` and `404 RULE_SET_NOT_FOUND`; the `processes:read` user gets `403 PERMISSION_DENIED`. Cases and the candidate are read in the caller's tenant only. With `processes:agent:replay`, `GET /agent-replays/{id}/cases/{attemptId}/answer` shows the recorded answer as history keeps it: masked output fields as `***` and masked inputs as keyed hashes. A repeat of the start with the same `Idempotency-Key` returns the original run.
- **Example:** `other tenant reads R1 -> 404, nothing returned`.
- **Test ID:** `PRE_095_replay_reads_only_the_callers_tenant_and_needs_its_own_permission`

### PRE-096 An agent step calls one allowed MCP tool, keyed by the job id, and its result is checked where it is recorded
- **Traces to:** `TR-AGT-04`, `TR-IDEM-04`, `TR-ASY-01`, #99
- **Milestone:** M3
- **Given:** The example host `examples/mcp-tool-adapter` registers adapter `mcp-risk` with the server URL, a bearer token and `allowed-tools: [lookup_customer_risk]` from host configuration. Step `score-customer` names the tool with the literal input `tool` and passes `customerId: C-778`, with an output schema `{ riskBand: A|B|C|D, score: integer 0..100 }`. The demo server answers `structuredContent { "riskBand": "B", "score": 62 }`, the first time after the step's one-second timeout.
- **When:** An instance reaches the step, the first attempt times out, and after the backoff the job is claimed again.
- **Then:** Both attempts send `tools/call` for `lookup_customer_risk` with `_meta.idempotencyKey` = the job id and their own attempt id as `_meta.correlationId`. The server runs the tool once and returns the stored result to the second call. The result is accepted in the result transaction after the schema and guardrail checks, and the token moves on. No engine module depends on the example.
- **Example:** `lookup_customer_risk(C-778) twice, same key -> tool ran once, riskBand B, score 62 accepted`.
- **Test ID:** `PRE_096_an_agent_step_calls_one_allowed_mcp_tool_keyed_by_the_job_id_and_its_result_is_checked` (`McpToolAdapterIT`); `PRE_096_*` in `McpToolAdapterTest` and `EngineIndependenceTest` (all in `examples/mcp-tool-adapter`)

### PRE-097 Tool errors, unstructured answers and tools outside the allow-list fail the attempt with a named code
- **Traces to:** `TR-AGT-04`, `TR-AGT-01`, #99
- **Milestone:** M3
- **Given:** (a) a step that names `delete_customer`, which is not allowed; (b) a tool answer with `isError: true`; (c) a JSON-RPC error, an HTTP 5xx or a timeout; (d) a text-only answer that asks for another tool; (e) a structured answer the guardrail refuses.
- **When:** Each attempt is recorded.
- **Then:** (a) `MCP_TOOL_NOT_ALLOWED`, not retryable, and no request is sent; (b) `MCP_TOOL_ERROR`, not retryable; (c) `MCP_PROTOCOL_ERROR` or `ADAPTER_TIMEOUT`, retryable; (d) the adapter returns an empty answer and the result check refuses it with `AGENT_SCHEMA_INVALID`; (e) `GUARDRAIL_VIOLATION`, and the step goes to its reviewer (`PRE-059`). No server message text is kept: history records the code, and the adapter's detail holds at most the JSON-RPC error number or the HTTP status. The adapter returns data and calls no second tool. A call for another tenant's customer is refused by the server's own authorisation and the tool writes nothing.
- **Example:** `isError true -> MCP_TOOL_ERROR, not retryable; text only -> AGENT_SCHEMA_INVALID`.
- **Test ID:** `PRE_097_*` in `McpToolAdapterTest` and `McpToolAdapterIT` (`examples/mcp-tool-adapter`)

### PRE-098 A reference adapter returns a labelled JSON answer, keyed by the attempt id, that the engine checks when it records it
- **Traces to:** `TR-AGT-04`, `TR-AGT-03`, `TR-ASY-01`, PD-129, #100
- **Milestone:** M3
- **Given:** The example host `examples/model-adapters` registers adapter `doc-reviewer` (OpenAI-compatible Chat Completions) and `doc-reviewer-anthropic` (Anthropic Messages). Each one's configuration sets the API base address, the model, the prompt template `prompts/kyc-review@p7` and an API key read from the environment. A local stub server returns a recorded answer whose JSON content is `{ "documentType": "PASSPORT", "nameMatches": true, "expiryDate": "2031-05-01", "tamperingSuspected": false, "confidence": 0.97 }`.
- **When:** Step `assess-documents` runs. The stub fails the first call with HTTP 429 and `Retry-After: 2`, and the step runs again after its backoff.
- **Then:** The request carries the rendered prompt with only the step's bounded inputs, asks for structured output with the step's output schema (`response_format` `json_schema`, or `output_config.format`), and carries the attempt id in the API's `user` or `metadata.user_id` field. The 429 case fails with `MODEL_RATE_LIMITED`, retryable, and the next attempt sends a new attempt id. The answer is returned with labels `model`, `promptVersion: p7`, `usage.inputTokens` and `usage.outputTokens`, and accepted after the schema and guardrail checks. No prompt text, input or key appears in logs; logs show the adapter, the attempt id, the status and the latency. Keys come from host configuration, are masked in `toString()`, and are required for Anthropic Messages; a self-hosted OpenAI-compatible server may need none. No tenant id, instance id or job id is sent to the vendor. No engine module depends on the examples, and they use no vendor SDK.
- **Example:** `429 then 200 -> attempt 1 MODEL_RATE_LIMITED, attempt 2 accepted, promptVersion p7`.
- **Test ID:** `PRE_098_*` in `ModelAdapterTest`, `ModelAdaptersIT` and `EngineIndependenceTest` (`examples/model-adapters`)

### PRE-099 Model errors and non-JSON answers map to named codes, and a bad answer never advances the step
- **Traces to:** `TR-AGT-04`, `TR-AGT-01`, PD-129, #100
- **Milestone:** M3
- **Given:** The stub answers (a) HTTP 400; (b) HTTP 401; (c) HTTP 5xx or 529, or no answer within the request timeout; (d) a refusal or a content-filter stop; (e) prose instead of JSON; (f) JSON with `confidence: 0.72`.
- **When:** Each attempt is recorded.
- **Then:** (a) `MODEL_REQUEST_INVALID`, not retryable; (b) `MODEL_AUTH_FAILED`, not retryable; (c) `MODEL_UNAVAILABLE` or `ADAPTER_TIMEOUT`, retryable; (d) `MODEL_REFUSED`, not retryable, and the job fails for good; (e) the adapter returns an empty answer and the result check refuses it with `AGENT_SCHEMA_INVALID`, retried once, then the step goes to its reviewer; (f) guardrail `LOW_CONFIDENCE` (rule `R5`), routed to the KYC reviewer (`PRE-059`). In none of them is a result variable written before the result check accepts it. No API error text is kept: the adapter's detail holds at most the HTTP status and the `Retry-After` seconds.
- **Example:** `content filter stop -> MODEL_REFUSED, not retryable, step not advanced`.
- **Test ID:** `PRE_099_*` in `ModelAdapterTest` and `ModelAdaptersIT` (`examples/model-adapters`)

### PRE-107 A CMMN file is refused by name at every BPMN import, before any other check
- **Traces to:** `TR-STD-04`, `TR-STD-26`, #112
- **Milestone:** M3
- **Given:** `claim-case.cmmn`, whose root is `definitions` in namespace `http://www.omg.org/spec/CMMN/20151109/MODEL` (CMMN 1.1), with a `casePlanModel` on line 4.
- **When:** It is posted to `POST /api/bpm/processes/v1/definitions` as XML (the import path of the design's `definitions:import-bpmn`).
- **Then:**
  - It answers `422 CMMN_UNSUPPORTED`. The Problem Details body has the detail "CMMN is not supported; model the case as a BPMN process", the `namespace` and the `line` of the root element (1).
  - The reader stops at the root element: no other check runs, no transaction is opened, and nothing is written.
  - A root element in another unknown namespace, such as XPDL or BPEL, is `422 VALIDATION_FAILED` (`NOT_BPMN`) naming the root, as before.
  - The XML hardening of `PRE-033` (no DTD, no external entities) runs before the namespace check.
- **Example:** `claim-case.cmmn -> 422 CMMN_UNSUPPORTED, 0 rows`.
- **Test ID:** `PRE_107_a_cmmn_file_is_refused_by_name_with_its_namespace_and_line`, `PRE_107_the_reader_stops_at_the_root_so_no_other_check_runs`, `PRE_107_a_root_in_another_unknown_namespace_is_a_validation_failure_naming_the_namespace`, `PRE_107_a_dtd_is_refused_before_the_namespace_is_looked_at`, `PRE_107_cmmn_is_422_with_the_detail_the_namespace_and_the_line`, `PRE_107_a_cmmn_file_is_422_cmmn_unsupported_at_the_process_import_and_writes_nothing`

### PRE-108 Every BPMN element, behaviour attribute and expression language outside the stage is listed with its id, line and stage
- **Traces to:** `TR-STD-04`, `TR-STD-26`, #112
- **Milestone:** M3
- **Given:** `loan.bpmn` contains a `boundaryEvent` `be-timeout` on line 42 (stage `S1`), an `inclusiveGateway` `ig-1` on line 57 (`S1`), a `sendTask` `notify` on line 63 (`S2`), an `adHocSubProcess` `adhoc-1` on line 70 (`S3`), a `conditionExpression` with `language="http://www.w3.org/1999/XPath"` on line 88 (`not planned`, a deviation in section 12 of the standards page) and `camunda:class` on a `serviceTask` on line 30.
- **When:** It is imported.
- **Then:**
  - The answer is `422 BPMN_UNSUPPORTED_ELEMENT` with 5 entries in document order in `unsupportedElements`, each `{ element, elementId, line, stage, message }` (PD-141: the field and the item shape are the Rule Engine's, `RLE-061`; `problems` stays for `VALIDATION_FAILED` and `GUARDRAIL_CONTRACT_INVALID`). The XPath entry is the unsupported condition language, with the element `conditionExpression`. `message` is plain English that may change; the element and the stage are the contract.
  - `camunda:class` is a foreign extension and is not refused. It is listed under `warnings` as `FOREIGN_EXTENSION_IGNORED` with its namespace and line, and the stored source keeps it byte for byte. Warnings keep the item fields of a problem (`code`, `message`, `element`, `elementId`, `line`).
  - An attribute that changes how a step runs is refused by name: `isForCompensation="true"` (`S2`), `isInterrupting="false"` (`S1`), `parallelMultiple="true"` (`S3`), `startQuantity` or `completionQuantity` other than 1 and `implementation` other than `##unspecified` (`not planned`).
  - At most 100 entries are returned, with `violationsTruncated: true` beyond that.
  - Ids are printed safely: control characters are removed and ids are cut at 200 characters.
  - The stage is one of `S1`, `S2`, `S3`, `not planned` from the coverage table in section 6.1 of the standards page, or `S0` for an element the table lists for the current stage that this release has not finished (call activity, transaction, an intermediate throw event with no event definition). The stage of an intermediate throw event comes from its event definition: a `signalEventDefinition` or `messageEventDefinition` throw is `S2`, an `escalationEventDefinition` throw `S1`, a `compensateEventDefinition` throw inside a `transaction` block is supported (`PRE-076`). No stage label names a sprint or a date.
- **Example:** `5 unsupported -> 422 with stages S1, S1, S2, S3, not planned; camunda:class -> warning`.
- **Test ID:** `PRE_108_every_element_and_language_outside_the_stage_is_listed_with_id_line_and_stage`, `PRE_108_a_vendor_attribute_is_a_warning_with_its_namespace_and_line_and_is_not_refused`, `PRE_108_a_file_with_only_a_vendor_attribute_is_not_refused`, `PRE_108_a_throw_event_takes_its_stage_from_its_event_definition`, `PRE_108_a_behaviour_attribute_that_changes_how_a_step_runs_is_refused_by_name`, `PRE_108_at_most_100_entries_are_listed_and_the_rest_set_the_truncated_flag`, `PRE_108_ids_lose_control_characters_and_are_cut_at_200_characters`, `PRE_108_no_stage_label_names_a_sprint_a_release_or_a_date`, `PRE_108_event_definitions_take_the_stage_of_their_kind`, `PRE_108_each_entry_carries_element_id_line_and_stage_and_the_list_says_when_it_was_cut`, `PRE_108_unsupported_bpmn_elements_are_listed_with_their_stage_and_nothing_is_stored`

### PRE-200 A successful import lists the vendor attributes it kept but did not read
- **Traces to:** `TR-STD-04`, `TR-STD-26`, PD-141, #380
- **Milestone:** M3
- **Given:** `card-block.bpmn` is a valid process whose start event carries `camunda:initiator="officer"` in the namespace `http://camunda.org/schema/1.0/bpmn`; caller `analyst-lan` holds `processes:deploy`.
- **When:** It is imported with `Idempotency-Key: k-warn-1`, then again with the same key, and then the version is read.
- **Then:**
  - `201` with the stored version and a `warnings` list: one entry `FOREIGN_EXTENSION_IGNORED`, element `camunda:initiator`, `elementId` `s`, the `line` and the namespace in `message`: the same item as the `warnings` of a refusal. The stored source keeps the attribute byte for byte.
  - The repeat returns the same body, warnings included. Reading the version later has no `warnings`: only the answer to an import carries them. A file with no vendor attribute has no `warnings` member.
- **Example:** `camunda:initiator on startEvent s -> 201 with warnings[0] = FOREIGN_EXTENSION_IGNORED camunda:initiator`.
- **Test ID:** `PRE_200_a_successful_import_lists_the_vendor_attribute_it_kept_but_did_not_read`, `PRE_200_reading_the_version_again_has_no_warnings_because_only_the_import_answer_carries_them`, `PRE_108_a_successful_read_returns_the_vendor_attributes_it_ignored_as_warnings`, `PRE_108_a_file_without_vendor_attributes_reads_with_no_warnings`

---

## Local and Remote Service Steps (#63)

A `serviceTask` with no `horus:mode`, or `horus:mode="local"`, calls a registered `ServiceTaskHandler` inside the step transaction; `horus:mode="remote"` parks on the Async Wait-State and calls a registered `ServiceAdapter` with no transaction open (ADR-0008). The remote call keeps the guarantees of #51: the completion token is stored only as its hash, the job id is the idempotency key, and a completion is scoped to its adapter's completer roles.

### PRE-070 One process runs a local step in its transaction and a remote step outside any transaction
- **Traces to:** `TR-ASY-01`, `TR-IDEM-04`, `TR-SEC-03`, #63
- **Milestone:** M2
- **Given:** Definition `loan-disbursement` v1 is active in tenant `bank-a`. Its steps are `reserve-limit` (a `serviceTask` with no `horus:mode`, handler `limits.reserve`), then `post-to-core` (`horus:mode="remote"`, adapter `core-banking`, `horus:timeout="PT10S"`), then `end`. The host pool has 2 connections, and the stub `core-banking` adapter answers after 2 s, first with a retryable failure.
- **When:** An instance starts with business key `LN-2026-0042` and amount `50,000,000 VND` (minor units, currency `VND`), and a `tenantId` of `bank-b` in its variables; the posting is claimed, fails, and is claimed again after the backoff, and answers `COMPLETED` with `{ "postingRef": "FT26324001" }`.
- **Then:** The start transaction calls `limits.reserve` with the instance's tenant, instance id, node id, business key, attempt id and only its declared inputs; the handler's own row (a 50,000,000 VND limit reservation) and the token's move to `post-to-core` commit together. The token is `WAITING_ASYNC` with one `ASYNC_SERVICE` job. The adapter runs with no engine transaction open on its thread and no pool connection held. `AdapterCall.idempotencyKey()` is the job id on every retry; the attempt id and the completion token are new on each claim, and neither the token nor its value appears in `pre_timer` or history. The adapter receives the tenant of the locked `pre_instance` row, never the `tenantId` of the variables (`TR-SEC-03`). TX2 records `ASYNC_RESULT_ACCEPTED`, stores `postingRef` and ends the instance. History lists, in order: `NODE_COMPLETED` of `reserve-limit`, `ASYNC_STEP_SCHEDULED`, `ASYNC_RESULT_ACCEPTED` and `INSTANCE_ENDED`. A step with no `horus:mode` is local. Another tenant neither reads the instance (`404 INSTANCE_NOT_FOUND`) nor completes its step (`404 ASYNC_STEP_NOT_FOUND`), and nothing is written.
- **Example:** `reserve-limit (local, in TX) -> post-to-core (remote, 0 connections held for 2 s) -> COMPLETED, postingRef FT26324001`.
- **Test ID:** `PRE_070_*` in `ServiceStepModesTest` (`process-engine-postgres`) and `LocalServiceInvokerTest` (`process-engine-core`)

### PRE-071 A local handler that fails rolls back with its step and leaves nothing half done
- **Traces to:** `TR-ASY-01`, `TR-SEC-08`, `TR-OBS-01`, #63
- **Milestone:** M2
- **Given:** The definition of `PRE-070`. Here `limits.reserve` writes its reservation row and then throws `IllegalStateException` with a message that names the customer's national id.
- **When:** (a) An instance is started through `POST /instances`. (b) The same handler is reached after a timer of `loan-disbursement-scheduled` fires instead.
- **Then:** (a) The start rolls back: no `pre_instance` row, no reservation row, no history and no job. The caller gets `500 INTERNAL` with a `correlationId` only, never the exception message; the Java API throws an exception naming the handler, the step and the exception class, with the handler's exception as its cause. (b) The firing rolls back. The job is due again after `jobs.retry-backoff` times the attempt (10 s, then 20 s) and becomes `FAILED` after `jobs.max-attempts` (3); the handler ran once per firing and none of its rows committed. A firing that rolled back leaves the instance's committed state unchanged, and the attempt id is derived from it, so every firing presents the same attempt id: it serves for correlation and keys nothing outside the transaction. The incident on `FAILED` follows with #55 and #58. A returned `HandlerResult.Failed` keeps `422 STEP_FAILED`. A local handler takes no idempotency key because its writes commit or roll back with the step. The handler contract states that it must not call the network. A call above `handlers.slow-call-warning` (200 ms) logs `process.step.slow` with the step, the handler and the elapsed time, and never the inputs; the time is read from an injected ticker.
- **Example:** `handler throws after writing -> 0 rows committed, 500 INTERNAL with correlationId`.
- **Test ID:** `PRE_071_*` in `ServiceStepModesTest`, `LocalServiceInvokerTest` and `ProcessRestErrorMatrixTest`

### PRE-072 A service step whose mode, handler or adapter is unknown is refused by name
- **Traces to:** `TR-DEF-04`, `TR-SEC-01`, #63
- **Milestone:** M2
- **Given:** A BPMN file with these faults: step `post-to-core` on line 31 with `horus:mode="batch"`; a local step `reserve-limit` naming handler `limits.reserv`, which is not registered; a remote step `notify-partner` naming adapter `partner-hub`, which is not registered; a local step `check-cutoff` with `horus:timeout="PT5S"`.
- **When:** The file is imported, and a corrected draft that still names the unregistered handler and adapter is sent to `:request-approval`; and a pending version whose adapter the approver's host does not register is sent to `:approve`.
- **Then:** The import returns `422 VALIDATION_FAILED` listing `MODE_INVALID` for `post-to-core` on line 31 and `TIMEOUT_INVALID` for `check-cutoff`; each entry carries the step id, the line and the problem, and no draft is written. Unregistered handler and adapter names are not checked at import, so a Git pipeline can import a draft before the host registers them; an import that activates at once (dev auto-approve) checks them. `:request-approval` refuses the draft with `422 VALIDATION_FAILED`, naming `reserve-limit -> limits.reserv` (`HANDLER_NOT_REGISTERED`) and `notify-partner -> partner-hub` (`ADAPTER_NOT_REGISTERED`); the version stays `DRAFT`. `:approve` refuses the same way and the version stays `PENDING_APPROVAL`. Names are resolved against the host's typed beans only, never by reflection (`TR-SEC-01`). Agent steps keep their import check (`PRE-061`).
- **Example:** `horus:mode="batch" line 31 -> 422 VALIDATION_FAILED; unregistered adapter -> approval refused`.
- **Test ID:** `PRE_072_*` in `ServiceStepModesTest`

## Business Errors and Late Results (#55)

### PRE-111 An agent or remote step's business error fails its job on the first attempt and opens an incident, by any path
- **Traces to:** `TR-OPS-03`, `TR-IDEM-04`, #55
- **Milestone:** M2
- **Given:** `loan-bureau-check` with the remote step `bureau-score` (`creditBureau.score`, `jobs.max-attempts` 3). In the first case the bureau answers `BusinessError("APPLICANT_UNKNOWN", "no file at the bureau for 079088001234")` from the executor. In the second it hands the call off, and its callback reports the same business error through `POST /async-steps/{jobId}:complete`, then repeats it.
- **When:** Each answer is recorded.
- **Then:**
  - The job is `FAILED` after attempt 1 of 3: `ASYNC_ATTEMPT_FAILED` records `errorCode: APPLICANT_UNKNOWN`, `retryable: false`, `final: true`, and no retry is due.
  - One incident is `OPEN` with `error_code = UNHANDLED_BUSINESS_ERROR`, `retry_count = 0` and `APPLICANT_UNKNOWN` in `cause_summary`. `INCIDENT_RAISED` and `processes.incident.raised.v1` carry `businessCode: APPLICANT_UNKNOWN`.
  - The token stays on `bureau-score` in `WAITING_ASYNC`.
  - No history row, incident or event contains the adapter's message or the national id.
  - The repeated callback is `DISCARDED` and writes nothing.
- **Example:** `BusinessError(APPLICANT_UNKNOWN) on attempt 1 of 3 -> job FAILED, incident UNHANDLED_BUSINESS_ERROR, no retry`.
- **Test ID:** `PRE_111_a_business_error_fails_the_job_at_once_and_opens_one_incident`, `PRE_111_a_business_error_reported_through_the_completion_api_is_final_and_its_repeat_writes_nothing`

### PRE-112 A late result after a timeout is discarded, writes nothing and never reopens the step
- **Traces to:** `TR-ASY-02`, `TR-IDEM-04`, `TR-AGT-01`, #55
- **Milestone:** M2
- **Given:** `loan-agent-assessment` with the agent step `assess-documents` (`timeout PT0.2S`, `<horus:retry max="1" backoff="PT1S"/>`), the guardrail rule set published, loan `LOAN-2027-5501` for 250,000,000 VND. The assessor hands every call off (`Accepted`). Its callback keeps each attempt's completion token and answers late.
- **When:**
  - (a) Attempt 1's lease ends, attempt 2 is claimed, and the callback then reports `{ riskScore: 85, recommendation: APPROVE }` for attempt 1.
  - (b) Attempt 2's answer `{ riskScore: 20, recommendation: APPROVE }` is accepted and the loan moves on to `underwrite`. The callback then reports attempt 1's answer again.
  - (c) In a second loan, both attempts' leases end and the job fails with an incident. The callback then reports a valid answer for attempt 2, three times.
- **Then:**
  - Every late report is `DISCARDED`. It writes no history row, no `rle_decision` row (the guardrail is not evaluated) and no task, and the job keeps its status and attempt.
  - (a) Attempt 2 still owns the step: its own answer is then recorded as usual.
  - (b) The step stays completed: `assessment` keeps `riskScore: 20`, the token stays on `underwrite`, and no review task opens.
  - (c) The job stays `FAILED`, the incident stays `OPEN`, the token stays on `assess-documents`, and the three reports have the same outcome.
  - Late results are logged without the answer.
- **Example:** `attempt 1 answers after attempt 2 was claimed -> DISCARDED, 0 rows; after the incident -> DISCARDED x3, incident OPEN`.
- **Test ID:** `PRE_112_a_late_result_of_an_attempt_taken_over_is_discarded_and_the_new_attempt_keeps_the_step`, `PRE_112_a_late_result_after_the_step_completed_never_reopens_it`, `PRE_112_a_late_result_after_the_job_failed_leaves_the_incident_open_every_time`

### PRE-113 Another tenant can neither report a late result nor read the incident of a business error
- **Traces to:** `TR-TEN-01`, `TR-OPS-03`, #55
- **Milestone:** M2
- **Given:** Tenant `bank-a`'s loan with the incident of `PRE-040`, and a second loan whose bureau call was handed off and then cancelled.
- **When:** A client of tenant `bank-b`, with `processes:step:complete` and `processes:read`, reports a result for the cancelled step and reads the first loan's incidents.
- **Then:** The report is `404 ASYNC_STEP_NOT_FOUND` and the read is `404 INSTANCE_NOT_FOUND`. No history row is written, in particular no `LATE_RESULT_DISCARDED`. The incident stays `OPEN`, and the job stays `CANCELLED`.
- **Example:** `bank-b reports for bank-a's cancelled step -> 404, 0 rows`.
- **Test ID:** `PRE_113_another_tenant_cannot_report_a_late_result_or_read_a_business_error_incident`

---

## Incidents: Retry and Resolve (#68, PD-126)

### PRE-077 Retrying an incident runs the step again under the same job id and a new attempt id
- **Traces to:** `TR-OPS-03`, `TR-IDEM-04`, `TR-OBS-01`, #68
- **Milestone:** M2
- **Given:** (a) `loan-agent-assessment`: the agent step `assess-documents` answered `MODEL_UNAVAILABLE` (not retryable) on attempt 1, so its job is `FAILED` and incident `INC-1` is `OPEN` at `lockVersion 0`. (b) `loan-disbursement`: the local step `check-limit` answered the business error `LIMIT_EXCEEDED`, so `INC-2` is `OPEN` and the step has no job. Operator `ops.lan` holds `processes:incident:manage`.
- **When:** `ops.lan` sends `POST /incidents/{id}:retry` with `If-Match: "0"`, an `Idempotency-Key` and `{ "reason": "Model back after maintenance" }`, then repeats the same request.
- **Then:**
  - In one transaction under the `pre_instance` row lock, (a)'s job is `DUE` now with exactly one more attempt, and the incident is `retryCount 1`, `lockVersion 1`, `retrying: true`, still `OPEN`. History records `INCIDENT_RETRIED` with the actor and the reason; `processes.incident.retried.v1` is published. REST answers `202`.
  - The repeat replays the first answer and writes nothing. A second retry while the attempt is due is `409 INVALID_STATE`.
  - Attempt 2 calls the agent with the same job id as its idempotency key and a new attempt id, and its answer passes the result check in the result transaction. When it is accepted, the incident is `RESOLVED` with `RETRY_SUCCEEDED` and `processes.incident.resolved.v1` is published. When it fails again, the same incident stays `OPEN` with the new cause, `retrying: false`, and no second incident opens.
  - (b) runs the local handler again in the retry transaction, under a new attempt id: a completed result moves the token on and resolves `INC-2` with `RETRY_SUCCEEDED` in the same transaction; another business error keeps `INC-2` open with the new cause, never the handler's message.
  - An agent step whose memory failed before any call (`AGENT_MEMORY_*`) is not retried (`409 INVALID_STATE`): the facts it needs are missing, so it is resolved with an output instead.
- **Example:** `retry INC-1 -> job DUE, attempt 2 under the same job id -> accepted -> RESOLVED RETRY_SUCCEEDED`.
- **Test ID:** `PRE_077_a_retried_agent_step_runs_under_the_same_job_id_with_a_new_attempt_id_and_resolves_on_success`, `PRE_077_a_retried_agent_step_that_fails_again_keeps_its_incident_open_with_the_new_cause`, `PRE_077_retrying_a_local_step_runs_its_handler_again_and_a_success_resolves_the_incident`, `PRE_077_a_retried_local_step_that_fails_again_keeps_the_same_incident_open_with_the_new_cause`

### PRE-078 Resolving an incident with an operator's output needs a second person, and only the approval moves the token
- **Traces to:** `TR-OPS-03`, `TR-SEC-11`, `TR-OBS-01`, PD-126, #68
- **Milestone:** M2
- **Given:** `INC-2` is `OPEN` on `check-limit` of `DISB-2027-0080`. `INC-3` is `OPEN` on the agent step `assess-documents`, whose output schema requires `riskScore` (integer) and `recommendation` and masks `summary`. `ops.lan` and `ops.minh` hold `processes:incident:manage`; `horus.bpm.processes.incidents.resolve-approval` is `four-eyes` (the default).
- **When:**
  - `ops.lan` sends `POST /incidents/INC-2:resolve` with `{ "output": { "approvedLimit": { "amount": "30000000", "currency": "VND" } }, "reason": "Limit raised by credit committee, memo CC-118" }`.
  - `ops.lan` then approves or rejects her own proposal; `ops.minh` approves it (or, in a second case, rejects it with a reason).
  - Separately, `ops.lan` proposes `{ "riskScore": "lots", "recommendation": "APPROVE" }` for `INC-3`, and a proposal without a reason, with a reason above `lifecycle.reason-max-length`, or without an output.
- **Then:**
  - The proposal is recorded: `INC-2` is `RESOLUTION_PENDING` with `proposedBy ops.lan`, the reason and the output; the token stays on `check-limit`. History records `INCIDENT_RESOLUTION_PROPOSED`; `processes.incident.resolution-proposed.v1` is published. The guardrail is not run on a person's answer.
  - `ops.lan`'s own approval or rejection is `403 SELF_APPROVAL_FORBIDDEN` and writes nothing.
  - `ops.minh`'s approval completes the step with the proposed output in one transaction: the token moves on to `disburse`, `INC-2` is `RESOLVED` with `COMPLETED_MANUALLY` and `resolvedBy ops.minh`. History records `INCIDENT_RESOLVED` with the proposer, the approver, the reason and the output; `processes.incident.resolved.v1` carries `resolution`, `resolvedBy` and `proposedBy`. A rejection returns the incident to `OPEN` without the proposal, and the token stays.
  - `INC-3`'s output is `422 OUTPUT_INVALID` with `paths` such as `$.riskScore: expected integer`, never the value, and nothing is written. An agent step's output is kept masked by its output schema in the proposal view and in history (`summary: ***`), while the step receives it unmasked.
  - The missing or too long reason and the missing output are `400 INPUT_INVALID`; nothing is written.
  - The table refuses a `four-eyes` resolution whose resolver is its proposer (`ck_pre_incident_four_eyes`). With `resolve-approval: single`, accepted on the `dev` profile only (start-up is refused elsewhere), the proposer's resolution applies at once and is recorded as `single`.
- **Example:** `ops.lan proposes approvedLimit 30,000,000 VND -> RESOLUTION_PENDING; ops.lan approves -> 403; ops.minh approves -> COMPLETED_MANUALLY, token on disburse`.
- **Test ID:** `PRE_078_a_resolution_needs_a_second_person_and_only_the_approval_moves_the_token`, `PRE_078_a_rejected_proposal_leaves_the_incident_open_and_the_token_on_the_step`, `PRE_078_a_resolution_needs_an_output_and_a_reason_within_the_limit`, `PRE_078_single_person_resolution_applies_at_once_and_is_recorded_as_single`, `PRE_078_the_table_refuses_a_four_eyes_resolution_by_its_proposer`, `PRE_078_an_output_that_breaks_the_steps_schema_is_refused_naming_the_path_never_the_value`, `PRE_078_an_approved_agent_output_completes_the_step_and_history_keeps_it_masked`, `PRE_078_single_person_resolution_is_accepted_only_on_the_dev_profile_and_other_values_are_refused`

### PRE-079 A retry or resolution that is stale, repeated or on an ended instance is refused and writes nothing
- **Traces to:** `TR-IDEM-03`, `TR-IDEM-04`, #68
- **Milestone:** M2
- **Given:** `INC-4` on `check-limit` of `DISB-2027-0090`, with `ops.lan`'s proposal pending at `lockVersion 1`.
- **When:** `ops.minh` approves with `If-Match: "0"`; anyone retries or proposes again while the proposal is pending; `ops.lan` reuses her proposal's `Idempotency-Key` with another output; after the approval, `ops.minh` approves and retries again. In a second instance, `ops.lan` cancels `DISB-2027-0091` while its incident is open.
- **Then:**
  - The stale request is `409 VERSION_CONFLICT`.
  - A retry or proposal on an incident that is not `OPEN`, and any command on a `RESOLVED` incident, is `409 INVALID_STATE`.
  - The same key with another body is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`.
  - None of them writes a row: history, incident, task and idempotency counts are unchanged.
  - The cancel resolves the open incident with `INSTANCE_CANCELLED` and `resolvedBy ops.lan` in the cancel transaction, with `processes.incident.resolved.v1`.
- **Example:** `approve with stale If-Match -> 409 VERSION_CONFLICT, 0 rows changed`.
- **Test ID:** `PRE_079_a_stale_repeated_or_late_command_is_refused_and_writes_nothing`, `PRE_079_cancelling_the_instance_resolves_its_incident_in_the_cancel_transaction`

### PRE-080 Incidents are listed with cause, retry count and next action, in the caller's tenant only
- **Traces to:** `TR-TEN-01`, `TR-PERF-04`, `PRE-036`, #68
- **Milestone:** M2
- **Given:** Tenant `bank-a` has 3 open incidents a minute apart; tenant `bank-b` has `INC-9`.
- **When:** A `bank-a` operator calls `GET /incidents?status=OPEN&limit=2` and follows the cursor, reads and commands `INC-9`, and a `bank-a` user with only `processes:read` lists, retries and resolves.
- **Then:**
  - The list returns the 3 `bank-a` incidents, newest first on `(createdAt, id)`, at most `query.max-page-size` (200) per page, served by `idx_pre_incident_status` and registered with the query-plan check. Each item has `errorCode`, `causeSummary`, `retryCount`, `nodeId`, `instanceId` and `allowedActions` (`retry`, `resolve`, `approve-resolution`, `reject-resolution`) computed for the caller.
  - The cursor is signed for the tenant and the status: used for another status or by another tenant it is `400 QUERY_INVALID`; an unknown status is `400 QUERY_INVALID`.
  - `INC-9` is `404 INCIDENT_NOT_FOUND` for the read and for every command, and nothing is written.
  - The `processes:read` user sees no actions, and both commands are `403 PERMISSION_DENIED`.
- **Example:** `bank-a lists 3; INC-9 of bank-b -> 404, nothing written`.
- **Test ID:** `PRE_080_incidents_are_listed_in_the_callers_tenant_only_newest_first_with_a_signed_cursor`, `PRE_080_another_tenants_incident_is_not_found_for_every_command_and_nothing_is_written`, `PRE_080_a_reader_sees_incidents_without_actions_and_cannot_retry_or_resolve`, `PRE_080_the_list_and_one_incident_match_the_contract_with_the_callers_actions`

---

## Reviewed Content (#406, PD-139)

### PRE-177 Approving a definition version names the hash of the content the approver reviewed
- **Traces to:** `TR-SEC-11`, `TR-DEF-04`, PD-139, #406
- **Milestone:** M2
- **Given:** `kyc-onboarding` v1 is `PENDING_APPROVAL`, requested by `alice`; `bob` holds `processes:approve` and reviewed it with `definitionHash H`. `horus.bpm.processes.approval.require-content-hash` is `true` (the default).
- **When:**
  - (a) `bob` approves with `expectedDefinitionHash: H`.
  - (b) The content of v1 was replaced after `bob` reviewed it (it now has hash `H2`), and `bob` approves with `H` at the current lock version; then with `H2`.
  - (c) `bob` approves without a hash, or with a value that is not a hash.
  - (d) A `bank-b` approver approves `bank-a`'s v1 with `H`.
  - (e) `bob` repeats (a) under the same `Idempotency-Key`, then sends another hash under that key.
  - (f) v1 is `ACTIVE`; v2 is pending and differs from v1 in more than 1,000 values. `bob` reads the diff of v2 against the effective v1, then approves v2 naming its `definitionHash`.
- **Then:**
  - (a) v1 is `ACTIVE`; the `APPROVED` history row and `processes.definition.approved.v1` carry `reviewedDefinitionHash: H`.
  - (b) The first is `409 CONTENT_CHANGED` naming `currentDefinitionHash H2` and `currentLockVersion`; nothing is written, published or recorded. The second is accepted.
  - (c) `400 CONTENT_HASH_REQUIRED` before anything is read, and `400 INPUT_INVALID` for a value that is not 64 lower-case hexadecimal characters; nothing is written. With the setting `false`, accepted on the `dev` profile only (start-up is refused elsewhere), an approval without a hash is accepted and records none.
  - (d) `404 DEFINITION_NOT_FOUND`; nothing is written.
  - (e) The repeat replays the first answer; the other hash is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`.
  - The check runs under the key lock after the status, four-eyes and lock-version checks, through the Java API and REST alike.
  - (f) The diff is `truncated` with `approvableHash: null` and no `definitionHash` on either side; the approval is `409 CHANGE_SET_TRUNCATED` and nothing is written, published or recorded. A change list cut off at its limit carries no hash that may be approved.
- **Example:** `bob approves with H after the content became H2 -> 409 CONTENT_CHANGED (current H2), 0 rows`.
- **Test ID:** `PRE_177_an_approval_with_the_reviewed_hash_publishes_the_version_and_records_the_hash`, `PRE_177_an_approval_of_content_changed_after_review_is_refused_naming_the_current_hash_and_writes_nothing`, `PRE_177_an_approval_without_a_hash_is_refused_while_the_setting_is_on`, `PRE_177_with_the_dev_relaxation_an_approval_without_a_hash_is_accepted`, `PRE_177_another_tenants_version_is_refused_and_nothing_is_written`, `PRE_177_a_replay_with_another_hash_under_the_same_key_is_a_key_reuse`, `PRE_177_a_truncated_diff_exposes_no_approvable_hash_and_an_approval_against_it_is_refused`, `PRE_177_the_two_approval_refusals_match_the_contract_and_name_the_current_content`, `PRE_177_an_approval_without_the_reviewed_hash_is_accepted_only_on_the_dev_profile`

### PRE-190 The pending approvals list shows every definition version waiting for a second person, with the hash to review against
- **Traces to:** `TR-SEC-04`, `TR-SEC-11`, `TR-TEN-01`, `TR-PERF-07`, PD-139, #31
- **Milestone:** M2
- **Given:** At `bank-a` the pipeline imports `kyc-onboarding` v1 and v2 and `loan-origination` v1; `alice` requests approval of `kyc-onboarding` v1 (5 April), `loan-origination` v1 (6 April) and `kyc-onboarding` v2 (7 April); v1 of `kyc-onboarding` stays pending when v2 is requested. `bob` holds `processes:read` and `processes:approve`, `dan` holds `processes:read` only, an auditor of `bank-b` holds `processes:read`. The V35 index `idx_pre_definition_pending_page` on `(tenant_id, requested_at, id)` over `PENDING_APPROVAL` versions exists, and `horus.bpm.lifecycle.approver-must-differ-from` is `makers` (the default).
- **When:** Each of them calls `GET /approvals`, and `bob` pages it one version at a time with `limit=1` and the `nextCursor` of each page.
- **Then:**
  - `bob` gets the three versions oldest request first: `kyc-onboarding` v1, `loan-origination` v1, `kyc-onboarding` v2. A pending version that is not the latest of its key is included. Each item has `key`, `name`, `version`, `definitionHash` (the value to send back as `expectedDefinitionHash`, `PRE-177`), `lockVersion` (the `If-Match`), `requestedBy`, `requestedAt`, `allowedActions` and `deniedActions`. It never carries the definition, the source or a variable. The status is `200`, the permission is `processes:read`, and nothing is written.
  - `allowedActions` follows the commands: `bob` gets `approve` and `reject` and an empty `deniedActions`; `alice`, the requester, gets none, with `{approve: SELF_APPROVAL_FORBIDDEN, reject: SELF_APPROVAL_FORBIDDEN}`; the pipeline, the importer, holding `processes:approve`, gets `reject` and `approve: SELF_APPROVAL_FORBIDDEN` (`PRE-019`, PD-096, PD-097); `dan` gets none, with `PERMISSION_REQUIRED` for both. With `maker-checker.enabled` false (dev profile only) a requester may approve, as the command allows.
  - The pages hold one item each; the first two carry a `nextCursor` signed for the tenant and this list, the last has none and `hasNext` false. A version that is approved or rejected leaves the list.
  - The `bank-b` auditor gets none of `bank-a`'s versions, and `bank-a`'s cursor is `400 QUERY_INVALID` for them, as are an unsigned payload, an edited signature, the cursor of the definition list, a cursor that is not a cursor, and `limit=500`. A caller without `processes:read` is `403 PERMISSION_DENIED`.
  - The statement is the registered list query `pending-approvals` (and `pending-approvals-after`) of `PRE-036`: its plan reads `idx_pre_definition_pending_page` in `(requested_at, id)` order with a `Limit` and no sort, and V35 builds that index CONCURRENTLY on a table that holds definitions, and a re-run after a failed build drops the invalid index and builds it again.
- **Example:** `GET /approvals?limit=1 as bob -> kyc-onboarding v1, requestedBy alice, definitionHash H1, allowedActions [approve, reject]`; `as alice -> allowedActions [], deniedActions {approve: SELF_APPROVAL_FORBIDDEN, reject: SELF_APPROVAL_FORBIDDEN}`.
- **Test ID:** `PRE_190_lists_every_pending_version_oldest_request_first_including_one_that_is_not_the_latest`, `PRE_190_the_requester_gets_neither_approve_nor_reject_and_the_reason_as_a_code`, `PRE_190_the_maker_may_reject_but_not_approve_under_the_default_makers_rule`, `PRE_190_a_reader_without_approve_sees_the_queue_with_permission_required`, `PRE_190_pages_by_signed_cursor_and_a_decided_version_leaves_the_queue`, `PRE_190_another_tenant_sees_none_and_its_cursor_is_not_ours`, `PRE_190_a_forged_or_cross_list_cursor_a_bad_limit_and_a_missing_permission_are_refused`, `PRE_190_the_page_matches_the_contract_and_carries_the_hash_and_the_actions_but_no_definition`, `PRE_190_a_bad_limit_a_forged_cursor_and_a_missing_permission_are_problems_of_the_contract`, `PRE_190_the_pending_approvals_pages_read_the_partial_index_in_order_without_a_sort`, `TR_OPS_01_V35_builds_a_valid_partial_index_on_existing_definitions_concurrently`, `TR_OPS_01_a_rerun_of_V35_after_a_failed_build_drops_the_invalid_index_and_builds_it_again`

---

## Agent Memory (#101)

### PRE-100 An agent step receives exactly the declared fields of earlier results, the same on every attempt
- **Traces to:** `TR-AGT-01`, `TR-AGT-02`, `TR-SEC-05`, #101
- **Milestone:** M3
- **Given:**
  - In `kyc-onboarding`, step `extract-id` recorded `{ fullName: "Nguyen Van A", dob: "1990-04-12", idNumber: "079090001234" (mask: true), address: "12 Le Loi, Q1" }`.
  - `screen-sanctions` recorded `{ hits: 0, listVersion: "2026-11-15" }`.
  - `assess-risk` declares `memory: [{ from: extract-id, fields: [fullName, dob], as: applicant }, { from: screen-sanctions, fields: [hits], as: screening }]`.
- **When:** `assess-risk` parks and its first attempt times out, so it is claimed again.
- **Then:**
  - Both attempts send the adapter `memory = { applicant: { fullName, dob }, screening: { hits: 0 } }`, three fields in total. The model call key differs between the attempts and the idempotency key, the job id, does not.
  - `address`, `idNumber` and `listVersion` are never sent.
  - `ASYNC_STEP_SCHEDULED` and the answer's history row record the resolved memory, with masked fields as keyed hashes.
  - In a second case, `screen-sanctions`' answer was refused and routed to a reviewer, who changed `hits` to 1. Memory then carries the reviewer's 1, never the refused agent value.
- **Example:** `2 entries, 3 fields -> adapter gets 3 fields on attempt 1 and 2; address never sent`.
- **Test ID:** `PRE_100_an_agent_step_receives_only_the_declared_fields_the_same_on_every_attempt`, `PRE_100_memory_carries_the_reviewers_answer_never_the_refused_one`, `PRE_100_resolution_keeps_only_declared_fields_and_masks_the_audit_copy`

### PRE-101 Memory is checked at import, and limits fail the step before any call
- **Traces to:** `TR-AGT-01`, `TR-DEF-04`, `TR-OPS-03`, #101
- **Milestone:** M3
- **Given:**
  - (a) An entry names step `extract-idd`, which does not exist.
  - (b) An entry names a step that is not upstream of `assess-risk`.
  - (c) `fields: ["*"]`.
  - (d) 17 entries, against `agents.memory.max-entries` 16.
  - (e) A run where the fields resolve to more bytes than `agents.memory.max-bytes`: 20 KiB against the default 16 KiB, or 83 bytes on a host that lowered the bound to 64.
  - (f) A run where `required: true` names a step on a branch this instance did not take.
- **When:** Each is imported or reached.
- **Then:**
  - (a) to (d): `422 VALIDATION_FAILED` at import, with a problem `AGENT_MEMORY_INVALID` naming the step, the entry and the problem. Wildcards are never allowed. Nothing is written.
  - (e): the step fails with `AGENT_MEMORY_TOO_LARGE`, not retryable, in the step transaction. The job is stored `FAILED` with attempt 0, `ASYNC_ATTEMPT_FAILED` records `final: true`, and the adapter is not called.
  - (f): the step fails the same way with `AGENT_MEMORY_MISSING`. With `required: false` the entry is absent, and `ASYNC_STEP_SCHEDULED` lists it under `memoryMissing`.
  - Both failures open an incident (#58), and the token stays on the step for an operator (#68).
  - Both bounds may be lowered and never raised: a host setting above 16 or 16384 is refused at start-up.
- **Example:** `20 KiB against 16 KiB -> AGENT_MEMORY_TOO_LARGE, 0 adapter calls`.
- **Test ID:** `PRE_101_a_missing_required_entry_fails_and_a_missing_optional_one_is_listed`, `PRE_101_a_required_entry_without_a_recorded_result_fails_the_step_and_an_optional_one_is_listed_missing`, `PRE_101_memory_above_the_byte_limit_fails_the_step_before_any_call`, `PRE_101_memory_limits_are_bounded_by_the_engine`, `PRE_101_memory_limits_may_be_lowered_never_raised`, `PRE_101_memory_naming_an_unknown_or_non_upstream_step_or_a_wildcard_is_refused_at_import`, `PRE_101_more_entries_than_the_host_allows_are_refused_at_import`, `PRE_101_the_import_check_names_the_step_the_entry_and_the_problem`, `PRE_101_the_reader_refuses_wildcards_and_malformed_entries_naming_the_step`, `PRE_101_twenty_kib_against_the_default_limit_is_too_large`

### PRE-102 Masked fields enter memory only when declared, and memory never leaves its instance
- **Traces to:** `TR-SEC-04`, `TR-SEC-05`, `TR-TEN-01`, #101
- **Milestone:** M3
- **Given:**
  - Variant A: an entry lists `idNumber`, which is `mask: true` at its source, without `includeMasked: true`.
  - Variant B: the same entry with `includeMasked: true`.
  - Variant C: an entry tries to read another instance or a variable outside step results (`from="${instance.customer}"`).
- **When:** Each is imported and run.
- **Then:**
  - A: `422 VALIDATION_FAILED`, with a problem `AGENT_MEMORY_MASKED_FIELD` that names the field as masked.
  - B: the adapter receives the plain value and history stores the keyed hash. The canonical form, and so the definition hash, the diff and the approval, show `includeMasked` on the step, so a second person approves sending it. The BPMN export reads back to the same hash.
  - C: refused at import. Memory reads only the recorded results of steps in the same instance, in the instance's tenant. Two loans of the same definition each receive their own facts, and a result recorded under another tenant with the same ids is never read.
- **Example:** `idNumber without includeMasked -> 422; with it -> sent, hashed in history, visible in the approval diff`.
- **Test ID:** `PRE_102_a_masked_field_without_include_masked_is_refused_at_import`, `PRE_102_a_masked_field_without_include_masked_is_refused_by_the_import_check`, `PRE_102_include_masked_sends_the_plain_value_and_the_audit_copy_keeps_only_the_fingerprint`, `PRE_102_include_masked_sends_the_plain_value_hashes_it_in_history_and_shows_in_the_canonical_form`, `PRE_102_memory_is_in_the_canonical_form_and_the_export_reads_back_to_the_same_hash`, `PRE_102_memory_reads_only_the_results_of_its_own_instance_and_tenant`, `PRE_102_memory_that_reads_a_variable_or_another_instance_is_refused_at_import`

---

## Migration Plans and Graph Diff (#74, #76)

### PRE-174 A migration plan records four-eyes for its execution; one person only on the dev profile
- **Traces to:** `TR-MIG-01`, `TR-SEC-04`, `TR-SEC-11`, PD-114, PD-131, #74
- **Milestone:** M2
- **Given:** `loan-review` v1 retired with running loans, v2 active; `thu` holds `processes:migration:manage`.
- **When:** `thu` creates a plan v1 to v2 with `Idempotency-Key: plan-1`, repeats it, reuses the key for v2 to v1, and a host starts with `migration.approval=single` on the `prod` profile and on the `dev` profile alone.
- **Then:**
  - The plan is `PLANNED` with `approval: four-eyes` and `secondPersonRequired: true`; one `pre_migration` row, one `PLANNED` history row naming `thu` and the approval, and `processes.migration.planned.v1` are written in one transaction. No instance, token, task or history row changes.
  - Its dry run reports the same approval. The repeat returns the first plan and writes nothing; the reused key is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`.
  - Start-up on `prod` is refused naming `horus.bpm.processes.migration.approval=single` and the dev profile; on `dev` alone it starts with a WARN and plans record `single`.
  - A retired target is `409 INVALID_STATE`, the same version twice `400 INPUT_INVALID`, and a caller without `processes:migration:manage` `403 PERMISSION_DENIED`; nothing is written.
  - `migration.dry-run-sample-size` (default 100, at most 500) and `migration.dry-run-validity` (default and at most `PT1H`) may be lowered only.
- **Example:** `plan v1->v2 -> approval four-eyes, 1 plan row, 1 history row, 0 instance rows changed; single on prod -> start-up refused`.
- **Test ID:** `PRE_174_a_plan_records_four_eyes_with_its_history_row_and_event_and_moves_nothing`, `PRE_174_a_single_person_plan_is_recorded_only_with_the_dev_setting`, `PRE_174_a_plan_needs_an_active_target_another_version_and_the_permission`, `PRE_174_migration_defaults_are_four_eyes_100_ids_and_one_hour`, `PRE_174_single_person_migration_is_refused_at_start_up_outside_the_dev_profile`, `PRE_174_an_unknown_approval_and_raised_dry_run_bounds_are_refused_naming_the_property`, `PRE_174_a_plan_answers_201_with_the_four_eyes_approval`

### PRE-175 Another tenant can neither plan, dry-run nor read a migration, and nothing is written
- **Traces to:** `TR-TEN-01`, `TR-MIG-01`, #74
- **Milestone:** M2
- **Given:** Tenant `bank-a`'s plan over its running `loan-review` loans; tenant `bank-b` has its own `loan-review` loans.
- **When:** A client of `bank-b` with `processes:migration:manage` and `processes:read` reads and dry-runs `bank-a`'s plan and plans `bank-a`'s versions; each tenant then dry-runs its own plan.
- **Then:** The read and the dry run are `404 MIGRATION_NOT_FOUND`, and the plan is `404 DEFINITION_NOT_FOUND`. Every engine table holds the same rows and no event is published. Each tenant's report counts and lists only its own instances. A body naming a tenant, or a step mapping, is `400 INPUT_INVALID`. A plan row cannot reference another tenant's definition, and the plan history refuses `UPDATE`, `DELETE` and `TRUNCATE`.
- **Example:** `bank-b validates bank-a's plan -> 404, 0 rows; bank-a 4 running, bank-b 7 running, each sees its own`.
- **Test ID:** `PRE_175_another_tenant_can_neither_plan_dry_run_nor_read_a_plan_and_nothing_is_written`, `PRE_175_each_tenant_dry_runs_only_its_own_instances`, `PRE_175_plan_rows_are_tenant_keyed_and_the_history_is_append_only`, `PRE_175_migration_problems_match_the_contract`

### PRE-176 The graph diff is deterministic and bounded, names steps by id and name, and never crosses tenants
- **Traces to:** `TR-DEF-03`, `TR-TEN-01`, #76
- **Milestone:** M2
- **Given:** `kyc-onboarding` v1 and v2 of `PRE-025` in tenant `bank-a`; the same v2 with its flows written first and every box moved again; a loan gateway whose branch condition changes from 500 million to 1 billion VND and whose default moves to the committee; two versions of 600 review steps, each renamed and given to another role.
- **When:** The diff is computed again, with the inputs swapped, for each pair, and by a client of `bank-b`.
- **Then:**
  - The same pair always gives the same diff; element order and layout change nothing. Swapping the versions gives the mirror image.
  - Every step carries its BPMN id, readable name and type, and every link its sequence flow id, both step ids and names, and its kind; no XML or position is returned.
  - The changed condition is `/branches/0/when` on the gateway; the moved default is one link removed and one added, of kind `default`.
  - At most 1,000 changed values are listed, sorted by step id then path, with `truncated: true` beyond.
  - `bank-b` gets `404 DEFINITION_NOT_FOUND` for `bank-a`'s versions, `404 NO_EFFECTIVE_VERSION` without a base when its key has no effective version, and `403 PERMISSION_DENIED` without `processes:read`. A stored source that no longer gives its hash is `DEFINITION_INTEGRITY_FAILED`. Nothing is written.
- **Example:** `diff(v1, v2) == diff(v1, v2 reordered); 1,200 changes -> 1,000 listed, truncated`.
- **Test ID:** `PRE_176_the_same_versions_always_give_the_same_diff_whatever_the_element_order_or_layout`, `PRE_176_swapping_the_versions_gives_the_mirror_image`, `PRE_176_branches_are_links_by_kind_and_a_changed_condition_is_a_value_of_the_gateway`, `PRE_176_at_most_a_thousand_changed_values_are_listed_and_the_rest_set_truncated`, `PRE_176_another_tenants_versions_are_not_found_and_nothing_is_written`, `PRE_176_a_diff_needs_the_read_permission_one_key_and_an_untampered_source`, `PRE_176_graph_diff_problems_match_the_contract`

---

## Delegate and Reassign Tasks (#263, PD-110)

### PRE-183 A supervisor reassigns an open task or delegates a claimed one, with a reason, and the move is recorded with both people
- **Traces to:** `TR-IDEM-03`, `TR-SEC-04`, `TR-AUD-01`, #263
- **Milestone:** M2
- **Given:** Tenant `bank-a`; loan application `LN-2027-0042` started by `alice`, submitted by `bob`; its `check-documents` task `T1` is assigned to `role:LOAN_OFFICER`, `OPEN` at `lockVersion = 0`; officers `carol` and `dan` hold `LOAN_OFFICER` in the host directory; supervisor `hoa` holds `processes:task:manage` and `processes:task:work`; `carol` is on leave.
- **When:** (a) `hoa` reads `GET /tasks/T1`. (b) `hoa` posts `POST /tasks/T1:reassign` with `If-Match: "0"`, `Idempotency-Key: re-1` and `{ "assignee": "carol", "reason": "Loan queue is backed up" }`, then replays it. (c) `carol` falls ill; `hoa` posts `POST /tasks/T1:delegate` with `If-Match: "1"`, `Idempotency-Key: de-1` and `{ "assignee": "dan", "reason": "Carol is on sick leave" }`, then replays it. (d) `hoa` posts `:reassign` to the now claimed task, and `:delegate` to an open one.
- **Then:** (a) `allowedActions` is `["reassign"]`. (b) `200` with `status: CLAIMED`, `claimedBy: carol`, `lockVersion: 1`, `ETag: "1"` and `allowedActions: ["delegate"]`; the replay returns the same body; one `TASK_REASSIGNED` history entry with actor `hoa`, claimant `carol` and the reason, and one `processes.task.reassigned.v1` carrying `reassignedBy: hoa`, `claimedBy: carol` and the reason, in the usual envelope. (c) `200` with `claimedBy: dan`, `lockVersion: 2`; one `TASK_DELEGATED` entry and one `processes.task.delegated.v1` with `delegatedBy: hoa`, `previousClaimant: carol`, `claimedBy: dan` and the reason; the replay changes nothing. The task keeps `assignee: role:LOAN_OFFICER`. (d) `409 INVALID_STATE` each time; nothing is written. A task that is `COMPLETED` or `CANCELLED` is refused the same way.
- **Example:** `reassign(T1, "carol", "Loan queue is backed up", If-Match "0") -> 200 CLAIMED by carol; delegate(T1, "dan", ..., If-Match "1") -> 200 CLAIMED by dan`.
- **Test ID:** `PRE_183_reassign_and_delegate_move_a_task_and_record_both_people_and_the_reason`, `PRE_183_a_completed_or_cancelled_task_is_never_moved`, `PRE_183_a_replay_returns_the_first_answer_and_writes_nothing`

### PRE-184 A target who may not work the task is refused with ASSIGNEE_NOT_ALLOWED and nothing is written
- **Traces to:** `TR-SEC-04`, `TR-IDEM-03`, #263
- **Milestone:** M2
- **Given:** The loan application of `PRE-183` with its four-eyes `approve-credit` task `T2` for `role:LOAN_OFFICER`, claimed by `carol`, `lockVersion = 3`; `alice` started the instance and `bob` completed its `submit-application` task; `lan` holds `CUSTOMER_SERVICE` only; the host directory knows `alice`, `bob`, `carol` and `dan` as `LOAN_OFFICER`. A second open task `T3`, assigned to `user:carol`.
- **When:** `hoa` delegates `T2` to `alice`, then to `bob`, then to `lan`, then to `carol`, then to `system:timer`, then to `dan`; and reassigns `T3` to `lan`.
- **Then:** The first five are `409 ASSIGNEE_NOT_ALLOWED` (started the instance, completed an earlier task, lacks the role, already holds the task, reserved identity); the history, the events, the idempotency records and `T2`'s `lockVersion` are unchanged after each. `dan` succeeds. A task assigned to one user can go to `lan`. With no directory bean, a role or group task cannot be given to a named user: `409 ASSIGNEE_NOT_ALLOWED`.
- **Example:** `delegate(T2, "alice") -> 409 ASSIGNEE_NOT_ALLOWED (started this instance); delegate(T2, "dan") -> 200`.
- **Test ID:** `PRE_184_the_starter_and_an_earlier_completer_cannot_be_given_a_four_eyes_task`, `PRE_184_a_user_without_the_role_or_group_is_refused_and_a_user_task_goes_to_anyone`, `PRE_184_without_a_directory_a_role_task_cannot_be_given_to_a_named_user`

### PRE-187 Moving a task needs the manage permission, a reason, If-Match and an Idempotency-Key, and never crosses tenants
- **Traces to:** `TR-SEC-04`, `TR-TEN-01`, `TR-IDEM-03`, #263
- **Milestone:** M2
- **Given:** `T1` of `PRE-183` at `lockVersion = 0`; `carol` holds `processes:task:work` only; `hoa` holds `processes:task:manage`; supervisor `thu` of tenant `bank-b` holds the same permission; the REST adapter over the engine.
- **When:** (a) `carol` posts `:reassign`. (b) `hoa` posts with `If-Match: "7"`. (c) `hoa` posts without `If-Match`, without `Idempotency-Key`, with no `reason`, a blank one, a 501-character one, and a body without `assignee`. (d) `hoa` sends `re-1` again with another assignee. (e) `thu` posts `:reassign` for `T1`. (f) `GET /tasks/T1` by `carol`.
- **Then:** (a) `403 PERMISSION_DENIED`. (b) `409 VERSION_CONFLICT`. (c) `428 PRECONDITION_REQUIRED` twice, then `400 INPUT_INVALID` for each bad body. (d) `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. (e) `404 TASK_NOT_FOUND`; no row of `bank-b` is written. (f) `allowedActions` has no `reassign`. Nothing is written by any refusal, and `T1` stays at `lockVersion 0`. A 500-character reason is accepted.
- **Example:** `POST /tasks/T1:reassign {"assignee":"carol","reason":""} -> 400 INPUT_INVALID`.
- **Test ID:** `PRE_187_moving_a_task_needs_the_manage_permission_and_the_task_stays_unchanged_when_refused`, `PRE_187_another_tenant_cannot_see_or_move_the_task`, `PRE_187_the_rest_adapter_enforces_headers_reason_and_error_codes`

---

## Metrics, Events and Throughput (#86)

### PRE-084 Metrics count only committed work, carry bounded tags and never a tenant or a business value
- **Traces to:** `TR-OBS-02`
- **Milestone:** M2
- **Given:** A host with a `SimpleMeterRegistry`. Six starts of `loan-disbursement` are attempted and one rolls back because its local handler throws. Of the five that start, three complete, one waits at its user task, and one is running with an open incident whose code is the host's business code `LIMIT_EXCEEDED`. A timer fires 4 s after its due time.
- **When:** The registry is read after `metrics.gauge-refresh` (default `PT30S`).
- **Then:**
  - Counters: `horus.pre.instances.started` = 5, because the rolled-back start is not counted (counters move after commit only); `horus.pre.instances.completed` = 3; `horus.pre.instances.failed` counts an instance that ends `FAILED`; `horus.pre.incidents.raised{errorCode}` = 1 under `UNHANDLED_BUSINESS_ERROR`.
  - Gauges, refreshed by one bounded query set per `metrics.gauge-refresh` on each replica and never per scrape: `horus.pre.instances.active` = 2, `horus.pre.incidents.open` = 1, `horus.pre.jobs.due.oldest.age{kind}` in seconds.
  - Timers and counters: `horus.pre.timer.lag` records 4 s; `horus.pre.step.duration{stepKind,outcome}` with the engine command kinds `START`, `COMPLETE_TASK`, `JOB_DUE`, `ASYNC_RESULT`, `CANCEL` and outcomes `OK` and `FAILED`; `horus.pre.async.attempts{kind,outcome}` with outcomes `ACCEPTED`, `REFUSED`, `FAILED`, `DISCARDED` and `DUPLICATE`.
  - Tags take only engine values from fixed sets; a value outside a set, a host business code included, is `OTHER`. No tag holds a tenant, business key, instance id, user or variable.
  - `definitionKey` is a tag of the instance counters only when `metrics.definition-key-tag: true` (default `false`).
- **Example:** `6 attempted, 1 rolled back -> started = 5, active = 2; tags contain no tenant`.
- **Test ID:** `PRE_084_metrics_count_only_committed_work_and_carry_only_engine_values`

### PRE-085 Every state change hands one enveloped event to the host inside its transaction
- **Traces to:** `TR-OBS-04`, `TR-OBS-06`
- **Milestone:** M2
- **Given:** An instance that starts, completes a step and completes; a second instance cancelled under a request with `X-Correlation-Id: REQ-7781` and `Idempotency-Key: cancel-DISB-0002`; a third at an incident; a fourth whose start rolls back.
- **When:** A recording publisher collects the events of committed transactions.
- **Then:**
  - It receives `processes.instance.started.v1`, `processes.step.completed.v1`, `processes.incident.raised.v1`, `processes.instance.completed.v1` and `processes.instance.cancelled.v1`, with the task events; `processes.incident.resolved.v1` and `processes.instance.failed.v1` have schemas and are emitted when the engine has a resolve operation and a path to `FAILED`.
  - Each event is handed over in the transaction of its change; the rolled-back start hands over events that never commit.
  - Each event has the envelope `eventId` (26 characters), `eventType`, `occurredAt` (engine clock), `tenantId`, `correlationId`, `causationId`, `producer` (`horus-process-engine`), `schemaVersion` (1) and `payload`.
  - `correlationId` is the request's, or for work a job starts the request's when there is one and otherwise the job id; with no scope it is the `eventId`. `causationId` is the command's `Idempotency-Key` or the job id, or null.
  - Each type validates against its JSON Schema under `process-engine/api/events/`, and a compatibility test against the baseline schemas allows additive changes only. An idempotent replay publishes nothing.
- **Example:** `cancel -> processes.instance.cancelled.v1 with causationId = cancel-DISB-0002`.
- **Test ID:** `PRE_085_every_state_change_hands_one_enveloped_event_to_the_host_inside_its_transaction`

### PRE-086 Event payloads carry ids and codes, never variables or masked values
- **Traces to:** `TR-SEC-05`
- **Milestone:** M2
- **Given:** An instance with variable `nationalId` and business key `DISB-ACC-0001`.
- **When:** Every event of `PRE-085` is serialised.
- **Then:** No payload contains a variable value, task result, step output, masked value or handler message. Payloads hold the ids, keys, versions, codes and actors listed in section 6.4 of `process-engine/docs/DESIGN.md`; the business key appears only in the events that list it there.
- **Example:** `instance.started.v1 payload = { instanceId, definitionKey, version, businessKey }, no nationalId`.
- **Test ID:** `PRE_086_event_payloads_carry_ids_and_codes_never_variables_or_masked_values`

### PRE-087 CI reports step throughput against 1,000 transitions per second, without gating
- **Traces to:** `TR-PERF-04`
- **Milestone:** M2
- **Given:** The load test in `process-engine/process-engine-postgres` on Testcontainers PostgreSQL with history on, one replica, a 10-transition definition (exclusive gateway, local service steps, a one-second timer, the shortest the engine accepts, fired as the injected clock moves, a parallel fork and join, end), a warm-up and a measured period.
- **When:** The CI job `throughput-report` runs on `main` with a 1-minute warm-up and a 5-minute measurement.
- **Then:** It publishes `throughput-report.json` and `.md` with the sustained transitions per second, the p50, p95 and p99 time of one engine call, the most pool connections in use, the runner's vCPU count and memory, and the PostgreSQL version. A result below 1,000 per second, or more than 20 % below the previous `main` report, is a warning in the report and the job summary. It never fails the build (PD-026).
- **Example:** `1,180 transitions/s, p95 7 ms, 4 vCPU runner -> report published, no gate`.
- **Test ID:** `PRE_087_throughput_report_is_written_and_never_gates`

---

## Guarded Agent Example (#66)

The example host `examples/guarded-agent-process` runs the guarded agent journey on the engine APIs alone, with seed data and a scripted demo. It has no cockpit dependency; the cockpit's instance view (#57) reads the same APIs.

### PRE-185 An agent answer the guardrail allows moves the loan on, and a refused one goes to a second person
- **Traces to:** `TR-AGT-01`, `TR-SEC-11`, #66
- **Milestone:** M2
- **Given:** The example host on the `dev` profile. Its seeder publishes `loan-document-guardrails` through maker-checker (R2: a risk score of 70 or more is refused, `HIGH_RISK_SCORE`) and deploys `loan-document-review`: the agent step `assess-documents` with `onViolation: route-to-human` and reviewer `role:COMPLIANCE_REVIEWER`, then `underwrite`. Tenant `bank-a` has three seeded applications: `LN-2026-0101` (250,000,000 VND, 3 documents), `LN-2026-0102` (1,500,000,000 VND, 3 documents) and `LN-2026-0103` (600,000,000 VND, 2 documents). The stub assessor approves every complete file, scoring 85 from 1,000,000,000 VND and 20 below, and refers an incomplete one with 55.
- **When:** Tellers send the three applications to review with `POST /loan-applications/{id}:review`, and the agent step runs for each.
- **Then:**
  - `LN-2026-0101`: risk 20, `APPROVE` passes the guardrail; the loan waits on `underwrite`.
  - `LN-2026-0102`: risk 85, `APPROVE` is refused (`R2`, `HIGH_RISK_SCORE`). Nothing of the answer is accepted, and a four-eyes review task for `role:COMPLIANCE_REVIEWER` shows the answer with `summary` masked. The teller who sent it, who also holds the reviewer role, is refused (`403 SELF_APPROVAL_FORBIDDEN`). Reviewer `thu` refers it with a reason; the step completes with `REFER`, history records `HUMAN_OVERRIDE`, and the loan waits on `underwrite`.
  - `LN-2026-0103`: the assessor refers it with risk 55, which the guardrail allows.
  - Sending an application again returns the instance it already has (the application id is the business key, `PRE-002`).
- **Example:** `LN-2026-0102 risk 85 APPROVE -> GUARDRAIL_VIOLATION -> thu refers -> underwrite`.
- **Test ID:** `PRE_185_*` in `GuardedAgentJourneyIT` (`examples/guarded-agent-process`)

### PRE-186 A case is drawn on its diagram from the engine APIs alone
- **Traces to:** `TR-AGT-01`, #66
- **Milestone:** M2
- **Given:** `LN-2026-0102` after its review, waiting on `underwrite`.
- **When:** The reviewer reads `GET /definitions/loan-document-review/versions/1/export?format=bpmn` and `GET /instances/{id}`.
- **Then:** The export is BPMN 2.0 XML with a shape for every step (`start`, `assess-documents`, `underwrite`, `end`); the instance names its definition and the step its live token waits on; and history holds the case's path in order: `INSTANCE_STARTED`, `ASYNC_STEP_SCHEDULED`, `ASYNC_RESULT_REFUSED`, `STEP_ROUTED_TO_REVIEW`. No cockpit code is needed to read them.
- **Example:** `export -> 4 shapes; instance -> underwrite WAITING_USER`.
- **Test ID:** `PRE_186_*` in `GuardedAgentJourneyIT`

## Task Escalation and the Calendar API (#73, #409)

### PRE-178 An overdue task moves to the escalation role at its deadline, keeping four eyes
- **Traces to:** `TR-CAL-01`, `TR-SEC-04`
- **Milestone:** M2
- **Given:**
  - Task `review-application` is for `role:credit-officer`, four-eyes, `horus:sla="PT8H"` on the tenant calendar `vn-banking-hours`: Monday to Friday 08:00 to 17:00, `Asia/Ho_Chi_Minh`.
  - It is created Thu 3 Dec 2026 15:00 ICT, so it is due Fri 4 Dec 2026 14:00 ICT: 2 hours on Thursday and 6 on Friday.
  - `horus:escalation="role:branch-supervisor"`: one level, to `role:branch-supervisor`, waiting `PT0S`.
  - `minh` started the instance, so `minh` is a four-eyes participant.
- **When:** The injected engine clock reaches Fri 4 Dec 14:00 ICT and the engine sweeps (`ProcessServices.escalateOverdue`, run by the job poller of every replica); the clock is one second earlier on the first sweep.
- **Then:**
  - At 13:59:59 nothing changes. At 14:00:00, under the `pre_instance` lock, the task's assignment becomes `role:branch-supervisor`, the task stays `OPEN` and counts level 1.
  - History records `TASK_ESCALATED` by `system:timer` with the level, the assignment before and after and the due time. `processes.task.escalated.v1` is published in the same transaction with `taskId`, `instanceId`, `nodeId`, `level`, `from` and `to`.
  - `minh`, even holding `branch-supervisor`, still cannot claim the task (`403 SELF_APPROVAL_FORBIDDEN`): escalation never removes a participant, so four eyes holds after any number of levels (`TR-SEC-04`). Another supervisor claims it.
  - A task completed at 13:59:59 is never escalated. A second sweep, or a second replica sweeping at the same moment, applies nothing more: one history entry and one event per level, across replicas.
  - The sweep reads at most `jobs.batch-size` due tasks across tenants through the registered bounded query `escalations-due` (`EngineSweep`, partial index `idx_pre_task_escalation_due`, V32), then locks each task's own instance and rereads the task before it changes anything; each change is made in the tenant of the locked row, and one task's failure holds back no other.
- **Example:** `created Thu 15:00, PT8H business -> due Fri 14:00 -> role credit-officer -> branch-supervisor`.
- **Test ID:** `PRE_178_an_overdue_task_moves_to_the_escalation_role_at_its_deadline_keeping_four_eyes` (`TaskEscalationPostgresIT`), `PRE_178_a_task_completed_before_its_deadline_is_never_escalated`, `PRE_178_the_sweep_is_tenant_scoped_idempotent_and_applies_a_level_once_across_replicas`, `PRE_178_the_first_level_with_no_wait_fires_at_the_due_time` (`EscalationPlanTest`), `PRE_178_V32_keeps_existing_tasks_and_builds_its_indexes_concurrently` (`TaskEscalationMigrationTest`)

### PRE-179 A second level fires on business time, and a claimed task follows its declared rule
- **Traces to:** `TR-CAL-01`, `TR-CAL-02`
- **Milestone:** M2
- **Given:**
  - The task of `PRE-178` declares `horus:escalation="role:branch-supervisor;role:regional-manager|PT4H"`: up to 3 levels separated by `;`, each a target and an optional wait after `|`.
  - Officer `tt.binh` claimed the task at 10:00 on Friday.
  - Variant A declares nothing, or `horus:onClaimed="release"`, the default. Variant B declares `horus:onClaimed="keep"`.
- **When:** Level 1 fires at Fri 14:00. Level 2 is 4 business hours later: 3 hours on Friday and 1 on Monday, so Mon 7 Dec 2026 09:00 ICT.
- **Then:**
  - Variant A at level 1: the claim is released, the task is `OPEN` for `branch-supervisor`, and the event names `tt.binh` as `releasedClaimant`.
  - Variant B at level 1: `tt.binh` keeps the claim, the assignment changes, and `tt.binh` still completes the task; nobody else can claim it.
  - Every level time is computed once, when the task is created, from the calendar of its SLA and stored with the task. Level 2 is Mon 09:00, not Sat 02:00, and a calendar change approved after the task was created, such as 7 Dec as a holiday, does not move it (`TR-CAL-02`).
  - At import, a fourth level, a target equal to the assignment it replaces, a later level that waits nothing, a wait that is not an ISO 8601 duration of zero or more up to 366 days, an escalation without `horus:sla`, an unknown claim rule or `horus:onClaimed` without an escalation is `422 VALIDATION_FAILED` with the problem code `ESCALATION_INVALID`. A task without `horus:escalation` keeps its canonical form and hash.
- **Example:** `level 2 PT4H after Fri 14:00 -> Mon 09:00 ICT -> regional-manager`.
- **Test ID:** `PRE_179_a_second_level_fires_on_business_time_and_a_claimed_task_is_released` (`TaskEscalationPostgresIT`), `PRE_179_a_claimant_who_keeps_the_claim_still_completes_the_task`, `PRE_179_a_later_level_counts_its_wait_in_business_time_across_the_weekend` (`EscalationPlanTest`), `PRE_179_*` in `SubsetBpmnReaderEscalationTest`

### PRE-180 An escalation that cannot apply is recorded, never silent, and never crosses tenants
- **Traces to:** `TR-SEC-04`, `TR-TEN-01`
- **Milestone:** M2
- **Given:**
  - Variant A: a level targets `user:minh`, who started the instance of a four-eyes task.
  - Variant B: the instance is cancelled before the deadline, while a task of another tenant with the same schedule is overdue.
  - Variant C: two replicas sweep the same overdue task at the same moment.
- **When:** The deadline passes and the sweep runs.
- **Then:**
  - A: the assignment is unchanged, the level is counted so the sweep does not try it again, history records `ESCALATION_REFUSED` with `ESCALATION_TARGET_FORBIDDEN`, and an incident with that code opens.
  - B: the cancelled instance's task is not escalated; only the other tenant's task moves, and its event carries that tenant. A task is only ever changed under the lock of its own instance row, in the tenant stored on it.
  - C: one replica applies the level and the other, after the first commits, finds it applied and writes nothing.
  - Not in this release: a target role the host `RoleCatalogue` does not list is not checked at approval (the SPI follows with its slice).
- **Example:** `target user minh is the maker -> no change, incident ESCALATION_TARGET_FORBIDDEN`.
- **Test ID:** `PRE_180_a_level_that_targets_the_maker_is_recorded_with_an_incident_and_changes_nothing`, `PRE_180_a_cancelled_instance_is_not_escalated_and_another_tenants_task_is_never_touched` (`TaskEscalationPostgresIT`)

### PRE-181 The calendars in force and the calendar changes are read over REST, in the caller's tenant
- **Traces to:** `TR-CAL-01`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** Tenant `bank-a` with calendars `branch-hours`, `card-centre` and `vn-banking-hours`, a pending change of `vn-banking-hours` and two approved ones; tenant `bank-b` with `bank-b-hours`; the caller holds `processes:read`.
- **When:** The caller reads `GET /calendars` with `limit=1` and then with the returned cursor, `GET /calendars/vn-banking-hours`, `GET /calendar-changes?status=PENDING` and `status=APPROVED` page by page, and `GET /calendar-changes/{id}`; a manager of `bank-b` uses a cursor of `bank-a`.
- **Then:** Calendars come in key order as summaries without hours or holidays, each page read through the registered bounded queries `calendars` and `calendars-after` (the `(tenant_id, calendar_key)` unique index); one calendar has its hours and holidays and an `ETag` of its version; changes come newest first through `idx_pre_calendar_change_status` (V32) with the old and the new value; `bank-b` never sees `bank-a` rows; a cursor of another tenant, an unknown status or a limit above `query.max-page-size` is `400 QUERY_INVALID`; another tenant's calendar or change is `404 CALENDAR_NOT_FOUND`. Every body validates against `process-engine/api/openapi.yaml`.
- **Example:** `GET /calendars?limit=1 -> branch-hours + nextCursor; the cursor with bank-b -> 400 QUERY_INVALID`.
- **Test ID:** `PRE_181_calendars_and_changes_are_listed_in_pages_for_the_callers_tenant_only` (`CalendarChangeIdempotencyPostgresIT`), `PRE_181_the_calendar_list_one_calendar_and_the_changes_match_the_contract` (`CalendarRestContractTest`)

### PRE-182 A calendar change is proposed, approved and rejected once per idempotency key, under four eyes
- **Traces to:** `TR-IDEM-01`, `TR-CAL-01`, `TR-SEC-04`
- **Milestone:** M2
- **Given:** Tenant `bank-a`, `vn-banking-hours` at version 3, `kim` and `lan` holding `processes:calendar:manage`, `horus.bpm.processes.calendar.approval` at its default `four-eyes` (PD-118 as amended by PD-131); `kim` proposes with `If-Match: "3"` and `Idempotency-Key: k-1`.
- **When:** `POST /calendars/vn-banking-hours/changes` is sent twice with the same key and body, once with the same key and another reason, once without `If-Match` and once without the key; `lan` approves with `a-1` and repeats it; two replicas receive the same proposal together; `bank-b` proposes with the same key `k-1`.
- **Then:** The first call is `201` with the change `PENDING` and a `Location`; the repeat returns the same status and body and writes no second change, no second decision and no second `processes.calendar.changed.v1` (the answer is stored whole in `pre_idempotency` in the transaction of the work, so a repeat after the approval still answers `PENDING`); the same key with another request is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`; a missing `If-Match` or key is `428 PRECONDITION_REQUIRED`; a stale `If-Match` is `409 VERSION_CONFLICT`; the concurrent proposals create one change; a key belongs to its tenant; a caller without `processes:calendar:manage` is refused before any key is claimed. `kim` approving is `403 SELF_APPROVAL_FORBIDDEN`. An approval or rejection needs no `If-Match`: the calendar version a change was proposed against is checked under the calendar's row lock, and a change that is no longer pending is `409 INVALID_STATE`. A body with a tenant or any other field is `400 INPUT_INVALID`.
- **Example:** `propose(k-1) twice -> one change; approve(a-1) twice -> version 4 once, one event`.
- **Test ID:** `PRE_182_a_proposal_repeated_with_the_same_key_returns_the_first_answer_and_writes_nothing_more`, `PRE_182_an_approval_repeated_with_the_same_key_applies_once_and_emits_one_event`, `PRE_182_two_concurrent_proposals_with_one_key_create_one_change_and_a_key_is_per_tenant`, `PRE_182_a_proposal_without_the_permission_is_refused_before_any_key_is_claimed` (`CalendarChangeIdempotencyPostgresIT`), `PRE_182_*` in `CalendarRestContractTest`

