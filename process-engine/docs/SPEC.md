# Process Engine: Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated test named by its spec ID (`TR-QA-01`). Each statement carries the milestone that ships it, matching [`docs/product/roadmap.md`](../../docs/product/roadmap.md); a statement whose milestone has not been reached is not a release gate yet. Money values use the contract in `rule-engine/docs/DESIGN.md` section 3.4.

---

## Instance Start, Idempotency & Concurrency

### PRE-001 Starting a process pins its effective definition version
- **Traces to:** `TR-DET-01`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Process `loan-origination` has `v1` active; instance `I1` starts on `v1`, then `v2` is approved and becomes effective.
- **When:** Complete steps on `I1`, and start new instance `I2`.
- **Then:** `I1` executes exclusively against `v1` until completion or explicit migration; `I2` pins `v2`.
- **Example:** `I1 starts on v1 -> v2 approved and effective -> I1 continues on v1; I2 runs on v2`.
- **Test ID:** `PRE_001_running_instance_pins_definition_version`

### PRE-002 Business key enforces uniqueness and idempotent start returns existing instance
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-02`
- **Milestone:** M2
- **Kind:** control
- **Given:** Tenant `bank-a`, process `loan-origination` v1.
- **When:** Caller posts `POST /api/bpm/processes/v1/instances` with `businessKey: "LN-2027-000123"` and `Idempotency-Key: k1`, then repeats with `businessKey: "LN-2027-000123"` (with `k1` or new key `k2`).
- **Then:** First call returns `201 Created` (`instanceId = I1`); replay returns the existing `I1` (`201` on same `k1`, `200` on new `k2`) with **only 1 instance row** in `pre_instance`.
- **Example:** `Same businessKey in tenant-a -> returns existing I1; same businessKey in tenant-b -> creates new I2`.
- **Test ID:** `PRE_002_business_key_and_idempotency_prevent_duplicate_starts`

### PRE-003 Stale task completion fails with optimistic locking conflict
- **Traces to:** `TR-IDEM-03`
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** control
- **Given:** `exclusiveGateway` with condition branches `amount >= 100000000 VND -> senior-approval`, `default -> auto-approve`.
- **When:** Instance evaluates gateway with `amount = Money.ofMinor("VND", 150000000)`.
- **Then:** Single active token advances to `senior-approval`; `auto-approve` is not activated.
- **Example:** `amount=150000000 VND -> senior-approval`.
- **Test ID:** `PRE_004_exclusive_gateway_routes_single_matching_branch`

### PRE-005 Concurrent parallel gateway join prevents lost wakeup under multi-replica race
- **Traces to:** `TR-PERF-05`, `TR-ASY-03`
- **Milestone:** M2
- **Kind:** control
- **Given:** `parallelGateway` forks into Branch A (`credit-bureau`) and Branch B (`fraud-check`) which converge at `join-checks`.
- **When:** Pod 1 finishes Branch A and Pod 2 finishes Branch B **at the exact same millisecond**.
- **Then:** Row-level lock on `pre_instance` serializes the two join evaluations so exactly **one** post-join token advances to `underwrite` (never zero lost wakeups, never two duplicate advances).
- **Example:** `Concurrent Thread-1 (A) + Thread-2 (B) -> exactly 1 downstream token created`.
- **Test ID:** `PRE_005_parallel_gateway_join_prevents_concurrent_lost_wakeup`

### PRE-006 Replica-safe timer claims via SKIP LOCKED and recovers crashed leases
- **Traces to:** `TR-PERF-05`
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** control
- **Given:** Process started or prior `submit-application` step completed by `alice`; next `userTask` has `fourEyes: true`. The refused identities are the starter of the instance and everyone who completed an earlier task of it; an explicit per-task list of refused users is not part of the model.
- **When:** `alice` tries to claim or complete the approval task via Java API or REST. Further cases: `carol` claims the approval, then completes a task on a parallel branch of the same instance, then completes the approval; a caller of another tenant claims or completes it.
- **Then:** Engine rejects with `403 SELF_APPROVAL_FORBIDDEN`; only another authorized user (`bob`) can complete it. `carol`'s completion is refused the same way, because the check runs again at completion under the instance lock. The other tenant gets `404 TASK_NOT_FOUND`. No refusal writes a history entry, an event, a stored answer or a change to the task.
- **Example:** `submit=alice, approve=alice -> 403; approve=bob -> 200`.
- **Test ID:** `PRE_007_four_eyes_blocks_same_actor_approval`

### PRE-008 BusinessCalendar SPI computes SLA deadlines across weekends and holidays
- **Traces to:** `TR-CAL-01`, `TR-CAL-02`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Tenant calendar `vn-banking-hours` (`Mon-Fri 08:00-17:00 Asia/Ho_Chi_Minh`, holiday `2027-01-01`). A `userTask` `credit-review` with `horus:sla="PT4H"` and `horus:calendar="vn-banking-hours"`; the engine clock at `2026-12-31T15:00:00+07:00`.
- **When:** Engine creates the `userTask` and computes `due_at`; later an approved change adds `2027-01-04` as a holiday and a second task is created at the same time.
- **Then:** Consumes 2 hours (`15:00-17:00` on Dec 31), skips Jan 1 holiday + weekend (Jan 2–3), and sets `due_at = 2027-01-04T10:00:00+07:00`, shown as `dueAt` on the task. The value is computed once, with the engine clock, in the calendar's time zone (never the JVM's), and stored; the later change does not mutate it, and only the second task is due `2027-01-05T10:00:00+07:00`. A task without `horus:calendar` counts elapsed time. A calendar the tenant does not have fails the step with `422 STEP_FAILED` (reason `CALENDAR_NOT_FOUND`) and nothing is written. An `horus:sla` that is not a positive ISO 8601 duration up to 366 days, an invalid calendar key, or a calendar without an SLA is `422 VALIDATION_FAILED` (`SLA_INVALID`) at import.
- **Example:** `Dec 31 15:00 + PT4H (biz) -> Jan 4 10:00 +07:00`.
- **Test ID:** `PRE_008_business_calendar_skips_holidays_and_weekends` (unit, `WorkingCalendarTest`, with cases across a clock change, a lunch break, a closing-time end and a seeded property that the due time does not depend on the JVM time zone; and on PostgreSQL, `BusinessCalendarPostgresIT`), `PRE_008_a_later_calendar_change_does_not_move_an_existing_due_time`, `PRE_008_*` in `SubsetBpmnReaderSlaTest`

### PRE-114 A business calendar is created or changed only when a second person approves it
- **Traces to:** `TR-CAL-01`, `TR-SEC-04`
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** control
- **Given:** Host configured with a 2-connection HikariCP pool (borrow timeout 500 ms); an async step calls a stub adapter that holds every call until all 10 are in flight. The async step is a remote `serviceTask` (`horus:mode="remote"`) `bureau-score` of `loan-bureau-check`. Agent case (#52): the agent step `assess-documents` of `loan-agent-assessment`, through a stub agent adapter, with the guardrail rule set published.
- **When:** 10 instances reach the step, one poll starts all 10 calls, and while they are in flight another thread runs 20 queries on the same pool.
- **Then:** All 10 adapters run concurrently with 0 pool connections active and no adapter call inside a transaction; all 20 queries get a connection; after the calls return, each instance records the result once (`TX 1` committed the claim before the call; `TX 2` commits the result after it).
- **Example:** `10 concurrent bureau calls on a 2-connection pool -> 0 active connections while calling, 0 pool timeouts, 10 instances on underwrite`.
- **Test ID:** `PRE_009_remote_step_executes_outside_db_transaction`, `PRE_009_agent_step_executes_outside_db_transaction` (the agent case; each loan's answer is allowed by the guardrail and recorded once)

### PRE-010 `agentTask` validates JSON Schema and evaluates Rule Engine guardrail before advancing
- **Traces to:** `TR-AGT-01`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `assess-documents` `agentTask` with `outputSchema` requiring `{ riskScore: number, recommendation: string }`, `guardrails: { decision: agent-output-guardrails, onViolation: route-to-human }`, and guardrail rule set `agent-output-guardrails` (`hitPolicy: first`; `R2`: `riskScore >= 70 -> allow: false, reasons: ['HIGH_RISK_SCORE']`; default row `allow: true`).
- **When:** Adapter returns `{ riskScore: 85, recommendation: "APPROVE" }`.
- **Then:** Output passes schema validation, Rule Engine returns `allow = false`, `reasons = ["HIGH_RISK_SCORE"]` and matched rule `R2`, and engine creates a maker-checker `userTask` carrying inputs, agent output, `reasons`, and matched rule IDs instead of auto-advancing.
- **Example:** `riskScore=85 -> allow=false, R2, HIGH_RISK_SCORE -> userTask created for compliance-reviewer`.
- **Test ID:** `PRE_010_agent_task_guardrail_violation_routes_to_human`, `PRE_010_an_answer_the_guardrail_allows_moves_the_loan_on_without_a_review` (example host over HTTP, `GuardedAgentTaskIT`: the review task's `review` object carries the code, reasons, rule ids, guardrail decision id, inputs and the masked answer; the starter of the loan cannot claim it)

### PRE-011 `agentTask` invalid JSON output or timeout retries with backoff then raises incident
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-OPS-03`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `agentTask` with `timeout: PT1S`, `retry: { max: 2, backoff: PT1S }` (BPMN `<horus:retry max="2" backoff="PT1S"/>`). Adapter exceeds timeout, or returns an answer failing `outputSchema` with `onViolation: fail` and `runners.async-agent.schema-retries` set to 2 (default 1: a schema failure is retried apart from `retry.max`).
- **When:** Initial attempt and 2 retries fail.
- **Then:** Engine retries twice, one `backoff` apart, and on the 3rd failure fails the job and, in the same transaction, opens a `pre_incident` with `error_code = AGENT_TIMEOUT` or `AGENT_SCHEMA_INVALID`, `retry_count = 2` (attempts after the first) and a cause built from codes only, records `INCIDENT_RAISED` and emits `processes.incident.raised.v1`, pausing the token on the step (`WAITING_ASYNC`) for ops retry/resolve (#68). Every async job that fails for good opens one, with its own code (`UNHANDLED_BUSINESS_ERROR` for a business error, `MAX_ATTEMPTS_REACHED` for a job claimed too often). Another tenant can neither complete the step nor read the incident, and nothing is written.
- **Example:** `3 failed attempts -> 1 OPEN pre_incident with cause & retry_count=2`.
- **Test ID:** `PRE_011_agent_task_exhausted_retries_raises_incident`, `PRE_011_agent_task_exhausted_schema_retries_raises_incident`, `PRE_011_another_tenant_can_neither_complete_the_step_nor_read_its_incidents`

### PRE-012 `agentTask` logs masked inputs, output, guardrail decision ID, and human override
- **Traces to:** `TR-AGT-02`, `TR-OBS-01`
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** semantics
- **Given:** Process runs `reserve-funds` (comp: `releaseFunds`) → `open-account` (comp: `closeAccount`) → `notify-partner` (`onFailure: compensate`).
- **When:** `notify-partner` exhausts retries and fails.
- **Then:** Engine executes `closeAccount` first, then `releaseFunds` second (strict reverse order), passing unique `attemptId` to each compensate handler. If `compensate: manual` is reached, engine opens an incident instead of skipping.
- **Example:** `Forward [A, B, C(fail)] -> Compensate [comp(B), comp(A)] in LIFO order`.
- **Test ID:** `PRE_013_failure_executes_reverse_saga_compensation`

A `saga` block is a BPMN `transaction`: its steps run under the block's own scope token (ADR-0020). A completed `serviceTask` or `agentTask` with `horus:compensate` pushes one entry onto `pre_compensation_stack` (V31) under its scope token. A step that fails for good with `horus:onFailure="compensate"`, or a `throwCompensation` (an `intermediateThrowEvent` with a `compensateEventDefinition`, inside a `saga`), walks the stack of that scope token newest first, one undo at a time. Each undo is a `COMPENSATION` job keyed by its own job id. A remote or agent step's undo is a `ServiceAdapter` called with no transaction open. A local step's undo is a `ServiceTaskHandler` that runs in the job's result transaction. The instance is `COMPENSATING` during the walk and ends `FAILED`.

### PRE-073 A failed transfer undoes its completed steps newest first, and each undo gets the forward step's keys
- **Traces to:** `TR-COMP-01`, `TR-COMP-02`, `TR-IDEM-04`, `TR-ASY-01`, #67
- **Milestone:** M2
- **Kind:** semantics
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
- **Kind:** semantics
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
- **Kind:** semantics
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
- **Kind:** semantics
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
- **Kind:** semantics
- **Given:** 500 instances running on `v1`, including 50 paused on `legacy-review` which was removed in `v2`.
- **When:** Call `POST /api/bpm/processes/v1/migrations/{id}:validate` without mapping `legacy-review`.
- **Then:** Returns dry-run report listing 50 unmapped instances at `legacy-review` and marks plan invalid; 0 rows in `pre_instance` are modified. Resolving them by an explicit mapping for a renamed step or by cancellation is M3 (`TR-MIG-03`) and is not part of this statement.
- **Example:** `Validate v1->v2 missing legacy-review -> conflict report, 0 instances mutated`.
- **Test ID:** `PRE_014_migration_validate_reports_unmapped_steps_without_mutation`, `PRE_014_a_step_kept_under_its_id_as_another_step_type_is_a_conflict`, `PRE_014_a_plan_whose_steps_all_map_is_valid_and_lists_every_instance_that_would_move`, `PRE_014_every_registered_dry_run_query_is_answered_by_an_index_on_a_seeded_database`, `PRE_014_the_dry_run_counts_are_answered_by_an_index_on_a_seeded_database`, `PRE_014_the_dry_run_reads_return_the_seeded_rows_of_one_tenant_and_version`, `PRE_014_the_dry_run_report_matches_the_contract`

### PRE-015 Batched migration isolates per-instance failures without stalling healthy instances
- **Traces to:** `TR-MIG-02`, #90
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Approved migration plan `v1 -> v2` (`PRE-195`) across 250 running instances, `migration.batch-size` 100. Since the dry run, instance `#120`'s token has reached a step the target does not keep (a mapping invariant error). In a second case another transaction holds the row lock of one instance (a transient lock conflict). In a third, one instance ends after the batch was read.
- **When:** The runner executes the plan, in batches, one instance lock at a time.
- **Then:** 249 healthy instances pin to `v2`: `pre_instance`, every token, open task, due job and recorded result point at the `v2` row, and each has one `MIGRATED` `pre_history` row naming the approver, the proposer, the reason and both versions. Instance `#120` stays on `v1`, with one `OPEN` incident `MIGRATION_FAILED` on its step and an outcome `FAILED` with the code `STEP_NOT_IN_TARGET`, without rolling back the other 249 instances. The lock conflict is `FAILED` with `LOCK_CONFLICT` and an incident, the other instances move; the instance that ended is `SKIPPED` with `NOT_RUNNING` and has no incident. The plan ends `COMPLETED_WITH_FAILURES` (`COMPLETED` when nothing failed) with exact counts of migrated, failed and skipped, and publishes `processes.migration.completed.v1`. A moved instance goes on to a step that only `v2` has. A second run moves and writes nothing.
- **Example:** `250 instances (1 corrupt) -> 249 migrated to v2, 1 incident raised, plan COMPLETED_WITH_FAILURES 249/1/0`.
- **Test ID:** `PRE_015_migration_execute_isolates_instance_failure`, `PRE_015_a_moved_loan_continues_on_the_new_version`, `PRE_015_a_loan_another_transaction_holds_is_a_failure_with_an_incident_and_the_others_move`, `PRE_015_a_loan_that_ended_before_the_runner_reached_it_is_skipped_not_failed`

### PRE-195 An execution is proposed with a reason and runs only after a second holder approves it
- **Traces to:** `TR-MIG-01`, `TR-SEC-04`, `TR-SEC-11`, PD-114, PD-131, #90
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** control
- **Given:** `bank-a`'s plan with a proposal over 3 running loans; `bank-b` has 4 running loans of its own and its own approved plan.
- **When:** A manager of `bank-b` proposes, approves, rejects, reads and lists the outcomes of `bank-a`'s plan; the runner then polls twice.
- **Then:** Each is `404 MIGRATION_NOT_FOUND`; every engine table holds the same rows and no event is published. A runner pass takes the plan of one tenant and moves only that tenant's loans: `bank-a` ends with 3 outcome rows and `bank-b` with 4, and neither tenant's progress counts the other's. The tenant of a plan is read from its row, never from a request.
- **Example:** `bank-b approves bank-a's proposal -> 404, 0 rows; bank-a 3 moved, bank-b 4 moved`.
- **Test ID:** `PRE_196_another_tenant_can_neither_propose_approve_nor_read_a_migration_and_nothing_is_written`

### PRE-197 A crashed runner's plan is taken over after its lease and continues after the last committed instance
- **Traces to:** `TR-MIG-02`, `TR-OPS-01`, #90
- **Milestone:** M2
- **Kind:** control
- **Given:** An approved plan over 60 loans, `migration.batch-size` 25, `migration.lease` two minutes; the runner on `pod-a` stops after its 40th loan as a crash would.
- **When:** `pod-b` polls at once, and again after the lease has ended.
- **Then:** After the crash 40 loans are moved, the plan is `RUNNING` and `pod-a` holds the lease, so `pod-b` takes nothing. After the lease `pod-b` moves the 20 loans after the last committed one. Every loan has exactly one `MIGRATED` history row and one outcome row, the counts are 60/0/0, the plan is `COMPLETED`. The cursor, the counts and the lease are written in the transaction of each loan, so no state exists in which a loan is moved and not counted.
- **Example:** `crash after 40 of 60 -> pod-b at +2 min moves 20, 60 MIGRATED rows, none twice`.
- **Test ID:** `PRE_197_a_crashed_runner_is_taken_over_after_its_lease_and_continues_after_the_last_committed_instance`

### PRE-198 One runner writes a plan at a time, and a runner that lost its lease writes nothing more
- **Traces to:** `TR-MIG-02`, `TR-TEN-01`, #90
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** semantics
- **Given:** Definition `kyc-onboarding` v1 with `agentTask` `assess-documents` and `guardrails.decision: agent-output-guardrails`. Case A: the rule set's effective version declares outputs `allow: boolean` and `reasons: list<string>` with `hitPolicy: first`. Case B: it declares `decision: string` and no `allow`. Case C: it has `hitPolicy: collect` (the Rule Engine makes a collected output a list, so `allow` is `list<boolean>`). Case D: no rule set of that name exists in the tenant.
- **When:** Caller posts the definition to `POST /api/bpm/processes/v1/definitions`.
- **Then:** Case A creates a draft (`201`, `status: DRAFT`). Cases B, C and D return `422 GUARDRAIL_CONTRACT_INVALID` naming the step id and the failed check; no `pre_definition` row is written. A rule set that reads an input the step's `outputSchema` does not declare, or declares with an incompatible type, is refused the same way, and so is a rule set published only in another tenant. The same check runs again at `:request-approval` and `:approve`, against the version in effect then; a refusal leaves the version's status and lock version unchanged.
- **Example:** `outputs [decision: string] -> 422 GUARDRAIL_CONTRACT_INVALID at step assess-documents`.
- **Test ID:** `PRE_016_deploy_rejects_guardrail_rule_set_breaking_contract`

### PRE-017 Async job lease is the step timeout plus the lease margin, so a live attempt is never taken over
- **Traces to:** `TR-AGT-03`, `TR-PERF-05`
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** semantics
- **Given:** Tenant `bank-a` has `kyc-onboarding` v1 `ACTIVE`; profile is not `dev`.
- **When:** A pipeline identity with `processes:deploy` posts a valid document to `POST /api/bpm/processes/v1/definitions`.
- **Then:** Response `201` with `version: 2`, `status: DRAFT`, `source: IMPORT` and `definitionHash`; v1 stays the effective version; `POST /instances` still starts on v1.
- **Example:** `import kyc-onboarding -> 201 {version: 2, status: DRAFT}; start -> pinned to v1`.
- **Test ID:** `PRE_018_import_creates_draft_not_active_version`

### PRE-019 No maker of a definition version can approve it
- **Traces to:** `TR-SEC-04`, `TR-DEF-03`, PD-096
- **Milestone:** M2
- **Kind:** control
- **Given:** `kyc-onboarding` v2 was imported by `carol` and is `PENDING_APPROVAL`, requested by `alice`. `alice`, `bob` and `carol` hold `processes:approve`. Setting `lifecycle.approver-must-differ-from` is unset (default `makers`). Second case: the host sets `requester`. Once draft editing exists, a third case: `dave` edited the draft and holds `processes:approve`.
- **When:** `alice` and `carol` each call `:approve`, through REST and through `DefinitionService.approve`; then `bob` calls `:approve` with `If-Match: "<lockVersion>"`. In the second case, `carol` calls `:approve`. In the third case, `dave` calls `:approve`.
- **Then:** Under `makers`, `alice` (the requester) and `carol` (the importer) each get `403 SELF_APPROVAL_FORBIDDEN` (Java: `SelfApprovalForbiddenException`), with a detail naming why, and nothing is written. `bob` gets `200`, `status: ACTIVE`, `approvedBy: bob`. Under `requester`, `carol` may approve, `alice` still gets `403`, the start-up log has one WARN naming the relaxed setting, and the approval history row records `approverMustDifferFrom: requester`. In the third case `dave` gets `403` under `makers`. `/api/bpm/meta` lists `lifecycle.approver-must-differ-from` with the active value.
- **Example:** `makers: requester alice -> 403, importer carol -> 403, bob -> 200 ACTIVE; requester mode: carol -> 200, alice -> 403`.
- **Test ID:** `PRE_019_no_maker_of_a_definition_version_can_approve`

### PRE-020 An approved version takes effect on its effective date and retires the previous one
- **Traces to:** `TR-DEF-03`, `TR-DET-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** `kyc-onboarding` v1 `ACTIVE`; v2 requested with `effectiveFrom: 2027-04-01T00:00:00+07:00` and approved on 2027-03-28 by `bob`. Clock is a `TestClock`.
- **When:** Instances are started on 2027-03-31 23:59 and on 2027-04-01 00:01 (`+07:00`).
- **Then:** The first instance pins v1, the second pins v2. At the effective time v1 becomes `RETIRED` and `processes.definition.retired.v1` is published once with `supersededBy: 2`; instances already on v1 continue on v1.
- **Example:** `start at 03-31 23:59 -> v1; start at 04-01 00:01 -> v2; v1 RETIRED once`.
- **Test ID:** `PRE_020_effective_date_switches_version_and_retires_previous`

### PRE-021 Reject returns a version to draft; an older draft cannot overtake a newer active version
- **Traces to:** `TR-DEF-03`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** v3 `PENDING_APPROVAL` requested by `alice`; separately v4 is `ACTIVE` while v3 is still pending.
- **When:** `bob` rejects v3 with reason "missing fraud check"; in the second case `bob` approves v3.
- **Then:** The reject returns `200`, `status: DRAFT`, `decision_comment` stored, `processes.definition.rejected.v1` published; a reject without a reason is `400 INPUT_INVALID`. In the second case the approve returns `409 VERSION_SUPERSEDED` and v3 stays `PENDING_APPROVAL`.
- **Example:** `reject v3 -> DRAFT; approve v3 while v4 ACTIVE -> 409 VERSION_SUPERSEDED`.
- **Test ID:** `PRE_021_reject_returns_draft_and_superseded_approval_refused`

### PRE-022 Outside the dev profile the starter refuses auto-approval and disabled maker-checker
- **Traces to:** `TR-SEC-11`
- **Milestone:** M2
- **Kind:** control
- **Given:** Five start-ups: (a) profile `prod`, `horus.bpm.processes.definitions.auto-approve=true`; (b) profile `prod`, `horus.bpm.processes.maker-checker.enabled=false`; (c) profile `dev`, `definitions.auto-approve=true` and one classpath definition; (d) profiles `dev,prod`, `definitions.auto-approve=true`; (e) profile `dev`, the removed key `horus.bpm.processes.definitions-auto-approve=false`.
- **When:** The host starts.
- **Then:** (a), (b) and (d) fail to start with a message naming the property and the `dev`-only rule. (e) fails to start with a message naming the removed key and its replacement, whatever its value. (c) starts; the classpath definition is `ACTIVE` with `approvedBy: system:dev-auto-approve`, a WARN log line names the tenant and definition key, and a definition imported afterwards through `POST /definitions` is auto-approved the same way.
- **Example:** `prod + auto-approve -> start-up fails; dev,prod + auto-approve -> fails; legacy key set -> fails; dev + auto-approve -> ACTIVE by system:dev-auto-approve`.
- **Test ID:** `PRE_022_dev_only_relaxations_refused_outside_dev`

### PRE-023 Turning off cockpit drafts blocks authoring endpoints, not imports
- **Traces to:** `TR-DEF-04`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `horus.bpm.processes.authoring.cockpit-drafts=false`; `carol` holds `processes:author`; the pipeline identity holds `processes:deploy`.
- **When:** `carol` calls `POST /definitions/kyc-onboarding/drafts` and `PUT /definitions/kyc-onboarding/versions/5`; the pipeline posts `POST /definitions`; `carol` saves a layout on v4.
- **Then:** `carol`'s draft calls return `403 DRAFT_AUTHORING_DISABLED` and write nothing; the import returns `201 DRAFT`; the layout save returns `200` (layout is not a draft). `GET /api/bpm/meta` reports `processes.authoring.cockpit-drafts: false`.
- **Example:** `cockpit-drafts=false -> author create 403 DRAFT_AUTHORING_DISABLED; deploy import 201 DRAFT`.
- **Test ID:** `PRE_023_cockpit_drafts_setting_blocks_authoring_only`

### PRE-024 The content hash ignores the diagram part and matches the hash of the Git file
- **Traces to:** `TR-DEF-04`, `TR-DEF-02`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** File `kyc-onboarding.yaml` in Git, with a `diagram` part; the same file without `diagram` and with keys in another order, as JSON.
- **When:** Both are imported as drafts, and the published hashing reference (RFC 8785 then SHA-256, `diagram` removed) is run on the file in CI.
- **Then:** Both drafts carry the same `definitionHash`, equal to the hash computed in CI; the graph diff between them is empty.
- **Example:** `hash(yaml with diagram) == hash(json reordered, no diagram) == ci_hash(file)`.
- **Scope today:** The engine reads BPMN XML only; no JSON or YAML source format is read. The case runs on a BPMN file with a `BPMNDI` diagram, the same file with its attributes in another order and every box moved, and the file without any diagram, imported by two tenants. The stored definition holds no diagram, all three carry the hash that an independent run of the published reference (diagram removed, RFC 8785, SHA-256) gives for the stored document, a moved diagram is no new version, and a layout saved afterwards leaves the hash alone.
- **Test ID:** `PRE_024_content_hash_excludes_diagram_and_matches_git`

### PRE-025 Graph diff lists nodes and edges added, removed and changed
- **Traces to:** `TR-DEF-03`
- **Milestone:** M2
- **Kind:** other
- **Given:** v1 `start -> verify-docs -> approve -> end`; v2 adds `fraud-check` between `verify-docs` and `approve`, changes `approve.assignee.role` from `ops` to `credit-officer`, and moves every box.
- **When:** `GET /definitions/kyc-onboarding/versions/2/diff?base=1`.
- **Then:** `nodesAdded: [fraud-check]`; `nodesChanged: [{id: approve, path: /assignee/role, from: ops, to: credit-officer}]`; `edgesRemoved: [verify-docs->approve]`; `edgesAdded: [verify-docs->fraud-check, fraud-check->approve]`; nothing about positions.
- **Example:** `diff v1..v2 -> +fraud-check, ~approve.assignee.role, -1 edge, +2 edges`.
- **Test ID:** `PRE_025_graph_diff_reports_nodes_and_edges`, `PRE_025_the_diff_of_two_stored_drafts_lists_the_added_step_the_changed_role_and_the_links`, `PRE_025_the_graph_diff_body_and_its_etag_match_the_contract`

### PRE-026 Layout revisions are append-only, need no approval and never change the definition
- **Traces to:** `TR-DEF-02`
- **Milestone:** M3
- **Kind:** control
- **Given:** `kyc-onboarding` v2 `ACTIVE` with layout revision 3; `carol` and `dave` hold `processes:author`.
- **When:** `carol` saves a layout with `If-Match: "3"`; `dave` then saves with `If-Match: "3"`; `carol` saves a layout naming node `ghost-step`.
- **Then:** `carol`'s first save returns `200` with revision 4, `createdBy: carol`; the definition status, hash and `lockVersion` are unchanged and no approval is requested. `dave` gets `409 VERSION_CONFLICT`. The `ghost-step` save gets `422 LAYOUT_INVALID`. Revisions 1 to 4 remain readable.
- **Also:** Two saves over the same revision at once make exactly one new revision and one `409 VERSION_CONFLICT`. The table refuses `UPDATE`, `DELETE` and `TRUNCATE` of a revision (SQLSTATE `55000`). A save publishes `processes.definition.layout-saved.v1` and is allowed for a draft, an active and a retired version, and while `authoring.cockpit-drafts` is off, because a layout is not a draft. A version that has no layout answers `204` with `ETag: "0"`, so the first save sends `If-Match: "0"`.
- **Example:** `save layout If-Match 3 -> revision 4, hash unchanged; concurrent If-Match 3 -> 409`.
- **Test ID:** `PRE_026_layout_revision_append_only_without_approval`, `PRE_026_two_saves_over_the_same_revision_at_once_make_exactly_one_revision`, `PRE_026_the_table_refuses_to_change_or_delete_a_revision`, `PRE_026_a_layout_can_be_saved_for_a_draft`, `PRE_026_layout_bodies_and_problems_match_the_contract`

### PRE-199 A layout is checked against its schema and the steps of its version, needs the right permission, and never crosses tenants
- **Traces to:** `TR-DEF-02`, `TR-TEN-01`, #102
- **Milestone:** M3
- **Kind:** control
- **Given:** `kyc-onboarding` v1 of `bank-a`; `carol` holds `processes:author`; `viewer` holds `processes:read` only; a caller of `bank-b` holds both.
- **When:** `carol` saves a layout with `schemaVersion` 2, a box with a text `x` and an unknown member, a lane with a repeated step and a step the version lacks, a link to a step the version lacks, and an unknown top-level member; she repeats a valid save with the same `Idempotency-Key`, and reuses the key for another layout; `viewer` saves; a caller with neither permission reads; the `bank-b` caller reads, lists and saves `bank-a`'s layout.
- **Then:** The invalid save is `422 LAYOUT_INVALID` listing each problem as a path and a reason in `paths`, at most 20, never a value, and writes nothing. The repeat returns the first revision and writes nothing; the reused key is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. `viewer`'s save is `403 PERMISSION_DENIED`, the read without permission as well. The `bank-b` calls are `404 DEFINITION_NOT_FOUND`, no row or event is written, and `bank-b` has its own layout history for its own version.
- **Example:** `nodes.ghost-step -> 422, paths ["$.nodes.ghost-step names a step that is not in the definition"]; bank-b reads bank-a's layout -> 404`.
- **Test ID:** `PRE_199_a_layout_is_validated_against_its_schema_and_the_steps_of_the_version`, `PRE_199_a_save_is_idempotent_and_a_key_is_not_reused_for_another_layout`, `PRE_199_saving_needs_the_author_permission_and_reading_the_read_permission`, `PRE_199_another_tenant_can_neither_read_nor_save_a_layout_and_nothing_is_written`

### PRE-037 Definition lifecycle events are published inside the state transaction
- **Traces to:** `TR-OBS-04`, `TR-OBS-06`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A host event publisher that records events and a host transaction that can be rolled back.
- **When:** v2 goes through request-approval, reject, request-approval, approve and is superseded; separately a host publisher throws during approve.
- **Then:** Events `publish-requested.v1`, `rejected.v1`, `publish-requested.v1`, `approved.v1`, `retired.v1` are recorded in that order with the full envelope; when the publisher throws, the approval rolls back and the version stays `PENDING_APPROVAL`.
- **Example:** `publisher throws on approve -> status still PENDING_APPROVAL, no approved.v1`.
- **Test ID:** `PRE_037_definition_events_published_in_transaction`

---

## Definition Size, Notation and BPMN Interchange (ADR-0013)

### PRE-035 Definitions above 500 steps or 1 MiB are rejected
- **Traces to:** `TR-DEF-05`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** BPMN document A with 500 steps; document B with 501 steps; document C of 1.2 MB with 300 steps. The defaults are 500 steps (`definitions.max-nodes`) and 1 MiB (`definitions.max-bytes`); a deployment may lower either, never raise it.
- **When:** Each is imported with `POST /definitions`.
- **Then:** A creates a draft; B returns `422 VALIDATION_FAILED` with the problem `TOO_MANY_NODES` naming the count and the limit; C returns `413 PAYLOAD_TOO_LARGE`; no row is written for B or C.
- **Example:** `501 steps -> 422 VALIDATION_FAILED (TOO_MANY_NODES, at most 500); 1.2 MB -> 413 PAYLOAD_TOO_LARGE`.
- **Test ID:** `PRE_035_definition_size_limits_enforced`

### PRE-033 BPMN subset import creates a draft and names every unsupported element
- **Traces to:** `TR-DEF-01`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** File A uses only the subset of ADR-0013 with BPMNDI shapes; file B adds a `boundaryEvent` `be-timeout` on line 42 and a `dataObject` `do-1` on line 57; file C starts with a `DOCTYPE` declaring an external entity.
- **When:** Each is posted to `POST /api/bpm/processes/v1/definitions:import-bpmn`.
- **Then:** A returns `201` with `status: DRAFT`, `source: BPMN` and layout revision 1 from BPMNDI. B returns `422 BPMN_UNSUPPORTED_ELEMENT` listing both elements with id and line. C returns `422 BPMN_UNSAFE_XML` and no entity is resolved. No transaction is opened for B or C.
- **Example:** `boundaryEvent be-timeout line 42 -> 422 BPMN_UNSUPPORTED_ELEMENT`.
- **Test ID:** `PRE_033_bpmn_subset_import_rejects_unsupported_by_name`

### PRE-034 BPMN export round-trips to the same definition hash
- **Traces to:** `TR-DEF-01`
- **Milestone:** M3 (cut candidate)
- **Kind:** other
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
- **Kind:** semantics
- **Given:** `pre_history` has monthly partitions for 2026-01 to 2027-06; instance `I7` started 2027-03-10 and ended 2027-04-02; its `nationalId` input is masked.
- **When:** `GET /api/bpm/processes/v1/instances/I7/activities?limit=200`, with the plan captured.
- **Then:** The plan scans only the 2027-03 and 2027-04 partitions; the response lists per node entered and completed times, duration, attempts, actor and incident, with `nationalId` masked; a second call with the returned `ETag` gets `304`.
- **Example:** `I7 activities -> 2 partitions scanned of 18; 304 on repeat`.
- **Test ID:** `PRE_027_activities_bounded_by_instance_lifetime`
- **Also:** Every `TOKEN_CANCELLED` entry carries `details.cause`. A step whose last closing entry is `TOKEN_CANCELLED` reads `CANCELLED` for `BOUNDARY`, `EVENT_GATEWAY`, `INTERRUPTING_EVENT_SUBPROCESS`, `MULTIPLE_EVENT` and `MULTI_INSTANCE_COMPLETED` (an event or a finished loop cut it off), and `TERMINATED` for `TERMINATE_END`, `ADHOC_CANCEL`, `INSTANCE_CANCELLED`, `COMPENSATION` and `TRANSACTION_CANCEL`. An entry with no cause, or one this version does not know, reads `CANCELLED` with `cause: null`. A step still open when its instance ended cancelled or failed reads `TERMINATED` with `cause: null`. The activity carries the cause. Tests: `PRE_027_cancelled_or_terminated_follows_the_cause_recorded_on_the_closing_row`, and one assertion per cause in the kernel tests of the boundary, terminate end, event gateway, ad-hoc, multiple event and event sub-process paths.

### PRE-028 Live counts per node come from the token index, never from history
- **Traces to:** `TR-PERF-09`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `kyc-onboarding` v2 with 40 tokens `WAITING_USER` at `approve`, 7 `WAITING_ASYNC` at `assess-documents`, 2 open incidents at `assess-documents`, and 10,000 history rows.
- **When:** `GET /definitions/kyc-onboarding/versions/2/node-stats?mode=live`, with SQL captured; then again within 5 s.
- **Then:** Response `{approve: {WAITING_USER: 40}, assess-documents: {WAITING_ASYNC: 7, openIncidents: 2}}` with `asOf`; the captured SQL reads only `pre_token` through `idx_pre_token_live` and `pre_incident` through `idx_pre_incident_node`, never `pre_history`; the second call is served from the cache with the same `asOf`.
- **Example:** `approve 40 waiting, assess-documents 7 async + 2 incidents; no pre_history read`.
- **Test ID:** `PRE_028_live_node_counts_from_token_index`

### PRE-029 The hourly rollup lags at most 10 minutes and never touches the step transaction
- **Traces to:** `TR-PERF-10`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Two replicas; `TestClock`; 1,200 completions of `approve` between 10:00 and 10:40 with durations from 2 min to 3 h; the rollup interval 5 min and lag 5 min.
- **When:** The clock reaches 10:50 and both replicas run the rollup; a transition is captured at the SQL level during the run.
- **Then:** Exactly one replica processes each slice; the 10:00 bucket for `approve` holds `completed_count = 1,200`; the p95 read from `?mode=window` is within 19 percent of the true p95; the captured transition contains no statement on `pre_node_stats_hourly` or `pre_engine_watermark`. A slice whose transaction is rolled back is counted once on the next run, not twice.
- **Example:** `10:50 -> bucket 10:00 approve completed 1200; no stats write in step TX`.
- **Test ID:** `PRE_029_rollup_bounded_lag_and_no_step_tx_writes`

### PRE-030 Task queries by assignee, role, group, due date and SLA breach are bounded
- **Traces to:** `TR-PERF-07`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** `CallerResolver` returns user `minh`, roles `[credit-officer, ops]`, group `[branch-hcm-01]`; 30,000 open tasks for `credit-officer`, 12 claimed by `minh`, 3 overdue; open tasks for `ops` and `branch-hcm-01`, some without a due time; tenant `bank-b` holds tasks for the same names.
- **When:** `GET /tasks?assignment=mine`, `GET /tasks?assignment=candidate&limit=50`, `GET /tasks?assignment=candidate&slaBreached=true`, and `GET /tasks?limit=500`.
- **Then:** `mine` (tasks declared to the caller or claimed by them) returns the 12 tasks ordered by `dueAt`; `candidate` (open tasks of the caller's roles and groups) returns 50 tasks across both roles and the group ordered by `(dueAt, id)` with a `nextCursor`; `slaBreached` returns the 3 overdue tasks; `limit=500` returns `400 QUERY_INVALID` naming the maximum 200. Tasks without a due time follow the others, by `id`. `role` and `group` narrow `candidate` and `any`, and with `mine` alone are `400 QUERY_INVALID`. `assignment=any` needs `processes:task:manage`. Items carry no variables and no review context. Only the caller's tenant is read: `bank-b`'s tasks never appear, a cursor of another tenant or of other filters is `400 QUERY_INVALID`, and a list writes nothing. Each range (the user, the claims, each role, each group, or the tenant) is read through its own partial index for at most one page and merged; the query-plan check shows only partial-index range scans, in index order, under a `Limit`.
- **Example:** `candidate limit 50 -> 50 items, nextCursor; limit 500 -> 400 QUERY_INVALID (max 200)`.
- **Test ID:** `PRE_030_task_query_filters_bounded_by_index` (`TaskQueryPostgresIT`), `PRE_030_*` in `TaskQueryPostgresIT` and, over HTTP, `TaskLifecycleIT`

### PRE-036 Every list query passes the CI query-plan check
- **Traces to:** `TR-PERF-07`
- **Milestone:** M2
- **Kind:** other
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
- **Kind:** semantics
- **Given:** `userTask` `approve` with `form: { schema: forms/approve.schema.json, hints: { order: [decision, reason] } }` requiring `decision` in `[APPROVE, REJECT]` and `reason` when `decision = REJECT`; a second definition whose `userTask` has `form: { link: "https://evil.example/t/{taskId}" }` with `tasks.form-link-origins` empty.
- **When:** `GET /tasks/T5/form`; then `:complete` with `{decision: REJECT}`; then with `{decision: REJECT, reason: "income not verified"}`; then the second definition is imported.
- **Then:** The form response carries the schema, the hints and masked prefilled data; the first completion returns `422 FORM_VALIDATION_FAILED` with pointer `/reason`; the second returns `200`; the import returns `422 VALIDATION_FAILED` with problem `FORM_LINK_NOT_ALLOWED`.
- **Example:** `complete {decision: REJECT} -> 422 FORM_VALIDATION_FAILED /reason`.
- **Test ID:** `PRE_031_task_form_resolved_and_completion_validated` (`TaskFormTest`), `PRE_031_a_foreign_link_is_refused_at_import_while_no_origin_is_listed` (`TaskFormTest`), `PRE_031_a_rejection_needs_a_reason_and_an_approval_does_not`, `PRE_031_a_missing_decision_and_a_value_outside_the_enum_are_named_by_field_never_by_value`, `PRE_031_violations_become_json_pointers`, `PRE_031_a_date_and_the_money_object_are_checked` (`TaskFormsTest`)

### PRE-032 The step catalogue lists only what the host registered
- **Traces to:** `TR-SEC-01`
- **Milestone:** M3
- **Kind:** other
- **Given:** The host registers service handler `kyc.verifyDocuments` (remote, compensable), agent adapter `doc-assessor`, calendar `vn-banking-hours`; rule sets `card-fee` (outputs `fee`) and `agent-output-guardrails` (outputs `allow`, `reasons`, hit policy `first`).
- **When:** `carol` with `processes:author` calls `GET /api/bpm/processes/v1/step-catalogue`; `erin` with only `processes:read` calls it too.
- **Then:** `carol` gets the handler with its input schema and `compensable: true`, the agent adapter, `card-fee` under decision rule sets, `agent-output-guardrails` under both decision and guardrail rule sets, and the calendar; no configuration value appears. `erin` gets `403 FORBIDDEN`. An import that names an unregistered handler fails with `422 VALIDATION_FAILED`.
- **Then (shape, #103):** The answer is `{handlers, agents, ruleSets: {decision, guardrail}, calendars, roles}`. A handler is `{name, mode: local | remote, inputSchema, compensable}`; the host states the schema and `compensable` through the default methods `inputSchema()` and `compensable()` of `ServiceTaskHandler` and `ServiceAdapter`, and a host that states nothing shows an empty schema and `false`. A rule set is `{name, version, hitPolicy, inputs, outputs}` for the version in effect on the engine clock; it is also a guardrail rule set when its hit policy is `first` or `unique` and it declares `allow: boolean` (and `reasons` as `string` or `list<string>` when it declares them), the same output checks as `PRE-016`. Roles come from the default method `RoleCatalogue.roles(tenant)`, which lists none unless the host overrides it. The tenant is the resolver's: a `bank-b` author sees `bank-b`'s calendars, rule sets and roles and none of `bank-a`'s, and the read writes nothing. Each list is cut at 500 entries. The `ETag` is a hash of the body and `If-None-Match` with it answers `304`.
- **Example:** `catalogue -> 1 handler, 1 agent, 2 decision rule sets, 1 guardrail rule set, 1 calendar`.
- **Test ID:** `PRE_032_step_catalogue_lists_registered_steps_only` (`StepCatalogueIT`), `PRE_032_the_catalogue_lists_the_rule_sets_published_in_the_callers_tenant_only` (`AgentTaskIT`, over the real Rule Engine), `PRE_032_catalogue_body_matches_the_contract_and_carries_an_etag`, `PRE_032_a_caller_without_processes_author_is_403_PERMISSION_DENIED` (`StepCatalogueRestContractTest`)

### PRE-219 A declared form is checked at import and approval, and is part of the definition hash
- **Traces to:** `TR-UI-02`, `TR-SEC-01`, `TR-STD-03`, #79
- **Milestone:** M2
- **Kind:** control
- **Given:** `userTask` declarations `horus:form="credit-review"` (a key), `horus:formSchema="forms/approve.schema.json"` with `horus:formHints` (a path), and `horus:formLink="/loans/review/{taskId}"` (a link); the host's `FormRegistry` knows `forms/approve.schema.json` only; `tasks.form-link-origins` is empty.
- **When:** Definitions are imported with an unregistered key; with a schema that uses `pattern`; with hints that order a field the schema does not declare; with two of the three declarations on one step; with a path `../../etc/passwd`; with the link `https://evil.example/t/{taskId}`; and then the correct one, whose link text is later changed in a second import.
- **Then:** Each refusal is `422 VALIDATION_FAILED` with exactly one problem: `FORM_NOT_REGISTERED`, `FORM_SCHEMA_INVALID`, `FORM_HINTS_INVALID`, `FORM_INVALID`, `FORM_INVALID` and `FORM_LINK_NOT_ALLOWED`; nothing is written. The link is accepted once `tasks.form-link-origins` lists `https://evil.example`. A relative link is always accepted; `//host`, `javascript:`, a URL with user information, a placeholder other than `{taskId}` and `{instanceId}`, and a link above 2,000 characters never are. The form is printed in the canonical form only when declared, so a definition without one keeps its hash, and changing the link text gives a new version with a new `definition_hash`. Request-approval and approval check the registry again, so a form removed since the import blocks them with the same problem.
- **Example:** `formLink https://evil.example/t/{taskId} -> 422 FORM_LINK_NOT_ALLOWED; with the origin listed -> 201`.
- **Test ID:** `PRE_219_an_unregistered_form_a_bad_schema_and_bad_hints_are_each_refused_with_their_code`, `PRE_219_a_task_declares_at_most_one_form_and_a_reference_stays_inside_the_form_folder`, `PRE_219_the_same_link_is_accepted_once_its_origin_is_listed`, `PRE_219_the_form_is_part_of_the_definition_hash_so_changing_it_needs_a_new_approval`, `PRE_219_request_approval_checks_the_registry_again_so_a_form_removed_since_import_blocks_it` (`TaskFormTest`), `PRE_219_a_link_is_relative_or_on_a_listed_origin_and_nothing_else`, `PRE_219_a_schema_outside_the_task_form_subset_is_named_by_path_and_rule` (`TaskFormsTest`), `PRE_219_a_schema_path_is_read_as_the_schema_without_hints`, `PRE_219_a_form_key_is_read_from_its_envelope_with_the_hints`, `PRE_219_a_name_outside_the_folder_or_not_plain_is_not_registered`, `PRE_219_the_settings_accept_relative_origins_and_a_relative_folder_only` (`ClasspathFormRegistryTest`)

### PRE-393 Form hints carry option labels with consequences, unit suffixes and money formats
- **Traces to:** `TR-UI-02`, `TR-STD-03`, #639
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A loan approval form with `decision` (`APPROVE`, `REJECT`), `termMonths`, `amount` and `currency`, and hints `options.decision.APPROVE = {label: {en: "Approve", vi: "Phê duyệt"}, consequence: {en: "The loan is disbursed.", vi: "Khoản vay được giải ngân."}}`, `units.termMonths = {en: "months", vi: "tháng"}`, `formats.amount = {currencyFrom: "currency", grouping: true}`.
- **When:** The definition is imported; then variants are imported with an option for `MAYBE` (not in the enum), an option on a non-enum field, a unit for an undeclared field, a lower-case `currency`, a well-formed but unassigned code such as `ZZZ`, both `currency` and `currencyFrom`, a `currencyFrom` that names its own field or a masked field, and a member `palette`.
- **Then:** The first is accepted and the form is returned with the hints as written; each variant is `422 VALIDATION_FAILED` with one `FORM_HINTS_INVALID` problem (the unknown member named). Completion validation is unchanged: the value sent is `APPROVE`, never its label. The hints are part of the form in the definition hash, as `labels` are.
- **Example:** `options.decision.APPROVE.label.vi -> "Phê duyệt"; amount 250000000 + currency VND -> shown "250.000.000 ₫" in vi`.
- **Test ID:** `PRE_393_option_labels_with_consequences_a_unit_suffix_and_a_money_format_are_accepted`, `PRE_393_an_option_for_a_value_the_enum_lacks_or_a_field_that_is_no_enum_is_refused`, `PRE_393_a_bad_unit_or_money_format_is_refused_and_an_unknown_member_is_still_named`, `PRE_393_a_currency_must_be_a_real_iso_4217_code`, `PRE_393_currency_from_must_name_another_field_that_is_not_masked` (`TaskFormsTest`)

### PRE-394 A list read that runs longer than the statement timeout is stopped with `QUERY_TIMEOUT`, and returns no partial page
- **Traces to:** `TR-PERF-07`, ADR-0015, #254
- **Milestone:** M3
- **Kind:** control
- **Given:** Tenant `bank-a` with `query.statement-timeout` of 500 ms (2 s by default); another connection holds an exclusive lock on `pre_definition`, then on `pre_instance`, so a list read waits; a host `@Transactional` method has inserted one row of its own and then calls the list.
- **When:** `GET /definitions` and `GET /instances` are read while the lock is held; then the lock is released and `GET /definitions` is read again.
- **Then:** Each read is stopped by the database after the limit and answered `503 QUERY_TIMEOUT` with a correlation id and no items; the host transaction is untouched and commits its row, because the read runs in a read-only transaction of its own that never joins it; once the lock is gone the list answers, and `SHOW statement_timeout` on a pooled connection is back to the server value, because the limit is transaction-local. A page that does answer is complete: it carries `nextCursor` when more items follow and none on the last page.
- **Example:** `GET /instances with pre_instance locked -> 503 QUERY_TIMEOUT after 0.5 s`.
- **Test ID:** `PRE_394_a_definition_list_slower_than_the_limit_is_stopped_with_a_named_code`, `PRE_394_an_instance_list_slower_than_the_limit_is_stopped_with_a_named_code`, `PRE_394_a_host_write_transaction_around_the_timed_out_read_is_untouched`, `PRE_394_the_list_answers_once_the_lock_is_gone_and_the_limit_does_not_stay_on_the_connection` (`ReadTimeoutPostgresIT`), `TR_SEC_08_every_code_of_the_error_matrix_is_a_problem_with_code_correlation_id_and_type`

### PRE-220 A task form's data keeps masked fields masked, and a link task is returned as its link
- **Traces to:** `TR-SEC-05`, `TR-UI-02`, #79
- **Milestone:** M2
- **Kind:** control
- **Given:** Task `T5` of instance `LN-2027-0042` whose form declares `idNumber` with `"mask": true` and `decision`; the instance variables `idNumber = 079012345678` and `decision = APPROVE`; task `T6` declared with the link `/loans/review/{taskId}`; task `T7` whose step declares no form.
- **When:** `GET /tasks/T5/form`, `GET /tasks/T6/form`, `GET /tasks/T7/form`, and `GET /tasks/T5/form` by a caller of another tenant.
- **Then:** `T5` answers `form.data` with `idNumber = "***"` and `decision = APPROVE`, never the national id number; the `ETag` is the task's `lockVersion`. `T6` answers `link: /loans/review/T6` and no `form`; a task declared with a link is completed by the host's screen through `:complete`, which checks no form. `T7` answers `404 FORM_NOT_DECLARED`, and so does a task whose registered form the host no longer provides. The other tenant's caller gets `404 TASK_NOT_FOUND`. A `422 FORM_VALIDATION_FAILED` carries `violations` and `errors` with one JSON Pointer per failing field (`/reason`), and never a submitted value.
- **Example:** `GET /tasks/T5/form -> data { idNumber: "***", decision: APPROVE }`.
- **Test ID:** `PRE_220_a_masked_field_is_a_placeholder_in_the_prefilled_data_and_never_its_value`, `PRE_220_a_link_has_its_placeholders_replaced` (`TaskFormsTest`), `PRE_031_a_link_task_returns_the_link_and_a_task_without_a_form_is_not_declared`, `PRE_031_another_tenant_cannot_read_the_form_of_a_task` (`TaskFormTest`), `PRE_031_the_task_form_body_matches_the_contract_and_carries_the_task_etag`, `PRE_031_a_task_without_a_form_is_404_and_a_result_that_breaks_the_form_is_422_with_pointers` (`ProcessRestContractTest`)

---

## Tenant Switching (ADR-0014)

### PRE-038 `X-Tenant-Id` is accepted only for a tenant in the token's allowed list
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** `tenancy.switching.enabled=true`; `minh`'s token has `tenant_id: bank-a` and `allowed_tenants: [bank-a, bank-a-fin]`; tenant `bank-b` has instance `I9`.
- **When:** `minh` calls `GET /tasks` with no header, with `X-Tenant-Id: bank-a-fin`, and with `X-Tenant-Id: bank-b`; then `GET /instances/I9` with `X-Tenant-Id: bank-a-fin`.
- **Then:** The first call returns `bank-a` tasks; the second returns `bank-a-fin` tasks; the third returns `403 TENANT_NOT_ALLOWED`, returns no data and writes a security log entry with user, requested tenant and `correlationId`; the fourth returns `404 INSTANCE_NOT_FOUND`.
- **Then (audit, #451):** every accepted switch hands one record to the host's `TenantSwitchAudit` (actor, original tenant, new tenant, time, correlation id) before the call reaches engine data; the default writes one `tenant.switch.accepted` line on the `io.github.dhluong90.bpm.audit` logger, and the security log line stays. A refused switch (unlisted, malformed or repeated header) writes no audit record; a sink that throws fails the call. After `lan` (`tenant_id: bank-a`, `allowed_tenants: [bank-a, bank-b]`) switches to `bank-b`, `GET /instances/P1` of `bank-a`'s case `P1` is `404 INSTANCE_NOT_FOUND` and nothing is written for either tenant.
- **Example:** `X-Tenant-Id: bank-b not in allowed_tenants -> 403 TENANT_NOT_ALLOWED, logged`.
- **Then (engine, #85):** the engine reads the tenant only from its resolver, so once the host's filter has switched `lan` to `bank-b`, `bank-a`'s case `P1` is `404 INSTANCE_NOT_FOUND`, its task `404 TASK_NOT_FOUND` and its cancellation `404 INSTANCE_NOT_FOUND`, with no row written for either tenant; back in `bank-a` the case is unchanged.
- **Test ID:** `PRE_038_tenant_header_accepted_only_within_allowed_tenants`, `PRE_038_after_a_switch_to_bank_b_the_bank_a_instance_is_404_and_nothing_is_written` (host over HTTP), `PRE_038_engine_answers_404_for_the_original_tenants_instance_after_a_switch` (`TenantSwitchPostgresIT`)

---

## Standards Foundations (ADR-0017)

### PRE-039 Import keeps the source document; the hash is fixed in the stored schema version
- **Traces to:** `TR-STD-01`, `TR-STD-03`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A BPMN file `kyc-onboarding.bpmn` with an XML comment; the same definition later read back after a newer schema version has been added to the build.
- **When:** It is imported with `POST /api/bpm/processes/v1/definitions`, then read with `GET .../definitions/kyc-onboarding/versions/1?include=source`.
- **Then:** `pre_definition` has `source_format = BPMN_XML`, `source_document` byte for byte equal to the file, `source_hash` of those bytes, and a `definition_hash` that a second read returns unchanged; `?include=source` answers the original text as `sourceDocument`, byte for byte; with the newer schema version present, `definition_hash` of version 1 is unchanged and no stored document is re-serialised.
- **Example:** `import kyc-onboarding.bpmn -> source_format BPMN_XML, sourceHash = SHA-256 of the file, definitionHash unchanged on every read`.
- **Test ID:** `PRE_039_import_keeps_source_document_and_fixed_hash`

### PRE-040 A handler's business error opens an incident until error events exist
- **Traces to:** `TR-OPS-03`, `TR-IDEM-04`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Definition `loan-disbursement` with a local `serviceTask` `check-limit` whose handler returns `HandlerResult.BusinessError("LIMIT_EXCEEDED", "Daily limit reached")`.
- **When:** An instance reaches `check-limit`.
- **Then:** The step transaction commits an incident with `error_code = UNHANDLED_BUSINESS_ERROR` and the business code `LIMIT_EXCEEDED` in `cause_summary`, `INCIDENT_RAISED` in history with `businessCode: LIMIT_EXCEEDED`, and `processes.incident.raised.v1`. The token stays on `check-limit` in `WAITING_ASYNC`, the instance stays `RUNNING`, and the call that reached the step (here the start) succeeds. No retry is scheduled because a business error is not retryable. The handler's message ("Daily limit reached") is never stored. Variables the earlier steps of the same transaction set are kept. `:retry` after the host fixes the data runs the handler again with a new `attemptId` (#68, `PRE-078`).
- **Example:** `BusinessError(LIMIT_EXCEEDED) -> incident UNHANDLED_BUSINESS_ERROR, token on check-limit`.
- **Test ID:** `PRE_040_business_error_result_opens_incident`, `PRE_040_a_business_error_keeps_the_token_on_the_step_and_asks_for_an_incident`

### PRE-041 Cancelling an instance during an adapter call discards the late result
- **Traces to:** `TR-ASY-01`, `TR-ASY-02`, `TR-STD-12`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Instance `I1` on `agentTask` `score-applicant` with its job `CLAIMED` by replica A and a stub adapter that answers after 2 s.
- **When:** `POST /instances/I1:cancel` commits after 0.5 s, and the adapter answers at 2 s.
- **Then:** The cancel transaction sets the instance `CANCELLED`, the token `CANCELLED`, the job `CANCELLED` and writes `TOKEN_CANCELLED`; replica A's TX2 selects no row for `status = 'CLAIMED'`, writes only `LATE_RESULT_DISCARDED` with the attempt id, and does not advance, retry or open an incident; no connection was held during the adapter call.
- **Example:** `cancel at 0.5 s, adapter result at 2 s -> LATE_RESULT_DISCARDED, instance CANCELLED`.
- **Test ID:** `PRE_041_cancel_during_adapter_call_discards_late_result` (remote step), `PRE_041_cancel_during_agent_call_discards_late_result` (agent case, #52: no guardrail decision is evaluated for the late answer)

### PRE-042 BPMN-style step ids are accepted and kept everywhere
- **Traces to:** `TR-STD-03`, `TR-DEF-01`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A BPMN definition whose elements have ids `StartEvent_1`, `Activity_0x1y2z3` and `end`; a second BPMN definition with step id `1-bad`.
- **When:** Both are imported, the first is approved and an instance runs to `Activity_0x1y2z3`.
- **Then:** The first is stored with those ids unchanged and its BPMN export carries them; the second is `422 VALIDATION_FAILED` naming `1-bad`, because an id must start with a letter or underscore.
- **Example:** `step id Activity_0x1y2z3 -> stored and shown unchanged; 1-bad -> 422`.
- **Test ID:** `PRE_042_bpmn_style_step_ids_kept`

### PRE-043 A BPMN definition reads into the model and writes back to the same canonical JSON
- **Traces to:** `TR-STD-28`, `TR-STD-03`, `TR-DEF-04`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `kyc-onboarding.bpmn` with a `startEvent`, a `userTask` `collect-documents`, an `exclusiveGateway` `check-risk` with two conditional flows and a default flow, a `transaction` subprocess `open-accounts` holding two `serviceTask` nodes, a `serviceTask` `notify` and an `endEvent`.
- **When:** The BPMN reader builds the in-memory model, and the canonical writer prints it back.
- **Then:** The model holds one `process` with those nodes, each with its element id and the gateway with one default flow; the printed canonical JSON is the RFC 8785 form of the model, contains no diagram data, and its SHA-256 equals the `definition_hash` the import stored. The importer reads BPMN XML only; a reader for a JSON or YAML source format is not part of the engine.
- **Example:** `kyc-onboarding.bpmn -> model -> canonical JSON; hash before = hash after`.
- **Test ID:** `PRE_043_bpmn_definition_round_trips_through_the_model`

### PRE-044 Joins and compensation are keyed by scope token
- **Traces to:** `TR-STD-29`, `TR-ASY-03`, `TR-COMP-01`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Definition `loan-booking` v1: `register-loan` (`serviceTask`, compensate `cancelLoan`), then a `saga` block `disburse` holding a `parallelGateway` that forks into `reserve-funds` (compensate `releaseFunds`) and `open-account` (compensate `closeAccount`), joins, then `notify-partner` (`onFailure: compensate`, `retry.max: 0`), all handlers stubbed to succeed and `notify-partner` stubbed to fail.
- **When:** An instance starts and runs until `notify-partner` fails.
- **Then:** At start `pre_token` holds one root `SCOPE` token (`scope_token_id` null, `current_node_id = loan-booking`, `WAITING_SCOPE`) and one `FLOW` token under it; entering `disburse` creates a second `SCOPE` token whose `scope_token_id` is the root; the join counts only the two `FLOW` tokens of the `disburse` scope token and creates exactly one outgoing token; `pre_compensation_stack` holds `cancelLoan` under the root scope token and `releaseFunds` and `closeAccount` under the `disburse` scope token; after the failure the engine runs the two `disburse` handlers newest first, never `cancelLoan`, and the `disburse` scope token ends `COMPENSATED` while the root `cancelLoan` entry stays `PENDING`; a second instance started at the same time shares no token or stack row with the first.
- **Example:** `notify-partner fails in saga disburse -> the two disburse handlers run newest first; cancelLoan is not run`.
- **Test ID:** `PRE_044_*`: the join half in `ParallelGatewayTest` and `ParallelJoinPostgresIT` (#251), the compensation half in `SagaCompensationTest` and `SagaBlockTest` (#67)

### PRE-045 Engine REST bodies ignore the host's JSON settings
- **Traces to:** `TR-EMB-07`, `TR-API-06`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A host with the Process Engine starter and REST module, and host properties `spring.jackson.property-naming-strategy=SNAKE_CASE` and dates written as timestamps (`spring.jackson.serialization.write-dates-as-timestamps=true` on Spring Boot 3.5, `spring.jackson.datatype.datetime.write-dates-as-timestamps=true` on Spring Boot 4, where Jackson 3 moved the switch); the same test runs in the Spring Boot 4.x leg and in the Spring Boot 3.5 leg, where the host's own Jackson 2 stays at the version its dependency management pins.
- **When:** `POST /api/bpm/processes/v1/instances` with `Idempotency-Key: 01JD2X5K9T1Q4N8V2M7B3C6D0E` and body `{ "definitionKey": "card-fee-review", "businessKey": "APP-1001", "variables": { "applicationId": "APP-1001", "customerId": "C-002", "applicantName": "Tran Thi B" } }`, then `GET /api/bpm/processes/v1/instances/{id}`.
- **Then:** The start is accepted (`201`) because the engine reads the camelCase body with its own Jackson 3 mapper; the response keys are camelCase (`businessKey`, `startedAt`), `startedAt` is an ISO 8601 string, and the host's own controllers still answer in snake case; the dependency tree of the 3.5 leg shows Jackson 3 from the engine next to the host's Jackson 2, with no host version override.
- **Example:** `host SNAKE_CASE -> engine response {"businessKey":"APP-1001","startedAt":"2027-02-01T09:00:00Z"}`.
- **Test ID:** `PRE_045_engine_rest_bodies_ignore_host_json_settings`

### PRE-046 A decision step evaluates in the instance's tenant, as the engine on behalf of the user, with no principal needed
- **Traces to:** `TR-TEN-01`, `TR-IDEM-04`, ADR-0027
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** control
- **Given:** An instance in `bank-a` waiting at a user task followed by a `businessRuleTask`, and a request from a caller whose `EngineAccess` tenant is `bank-b`, which reaches the step through a host path that bypasses the task tenant check (simulated by the test harness).
- **When:** The `DecisionPort` adapter is called for the step.
- **Then:** The adapter refuses with `403 TENANT_NOT_ALLOWED` before any rule set is read, nothing is written to `rle_decision` in either tenant, the step fails and the whole transaction rolls back, and one security log entry is written.
- **Example:** `request tenant bank-b, instance tenant bank-a -> 403 TENANT_NOT_ALLOWED, no decision row`.
- **Test ID:** `PRE_047_decision_port_refuses_a_request_tenant_other_than_the_instance_tenant`

### PRE-048 The kernel runs only the source document that was approved
- **Traces to:** `TR-DEF-04`, `TR-STD-01`, ADR-0026
- **Milestone:** M2
- **Kind:** control
- **Given:** Definition `card-fee-review` v1 approved (or auto-approved on `dev`), with `sourceHash` and `readerVersion` recorded in `processes.definition.approved.v1` and the approval history row, and one running instance.
- **When:** (a) The stored `source_document` and `source_hash` are both replaced in the database by a different, internally consistent process. (b) The engine starts with a different BPMN reader version and the same source.
- **Then:**
  - (a) Starting or advancing the version is refused with `500 DEFINITION_INTEGRITY_FAILED` when the stored source does not give its `source_hash`, or its canonical form does not give the stored `definition_hash`; the same check guards export, diff and `?include=source`. One security log entry names the tenant, key and version (never the content). The changed process never runs. Incidents on running instances and an alert are post-1.0.
  - (b) The version compiles and runs, and a WARN log line names the definition and both reader versions (post-1.0: the reader version is not recorded yet).
- **Example:** `source swapped with a matching row hash -> refused, incident, audit, alert; reader upgraded -> runs, WARN`.
- **Test ID:** `PRE_048_kernel_runs_only_the_approved_source`

### PRE-049 Definition and instance lists are keyset pages with cursors signed for the tenant and the list
- **Traces to:** `TR-TEN-01`, `TR-PERF-07`, ADR-0015
- **Milestone:** M2
- **Kind:** control
- **Given:** Tenant `bank-a` with definitions `card-fee-review` (v1 `ACTIVE`) and `card-limit-review`, and five running instances of `card-fee-review` started one after another; tenant `bank-b` with none. `query.max-page-size = 200`, `query.max-range = P31D`.
- **When:** A `bank-a` caller with `processes:read` reads `GET /definitions?limit=1` and `GET /instances?definitionKey=card-fee-review&limit=2`, following `nextCursor` until `hasNext` is false; then sends `limit=0`, `limit=201`, `status=OPEN`, a 32-day range, `startedTo` before `startedFrom`, a cursor with an edited payload, a cursor with other filters; and a `bank-b` caller replays a `bank-a` cursor.
- **Then:** Pages are `{data, nextCursor, hasNext}`; definitions come ordered by key with `latestVersion`, `latestStatus`, `effectiveVersion` and `lastChangedAt`; instances come newest first (`startedAt`, then `id`, both descending) with no gap and no duplicate across pages and no variables, tokens or tasks; every refused query is `400 QUERY_INVALID`; the `bank-b` caller gets `400 QUERY_INVALID`, lists no `bank-a` row and reads no `bank-a` version (`404 DEFINITION_NOT_FOUND`). `GET /definitions/{key}/versions/{v}` answers the version with its definition hash as `ETag`, which is the `Location` an import returns.
- **Example:** `5 instances, limit=2 -> pages of 2, 2, 1, newest first; bank-a cursor sent by bank-b -> 400 QUERY_INVALID`.
- **Test ID:** `PRE_049_definition_and_instance_lists_are_signed_keyset_pages`

### PRE-050 Cancelling an instance is idempotent, optimistically locked and ends every live token and open task
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-03`, `TR-OBS-04`
- **Milestone:** M2
- **Kind:** control
- **Given:** Instance `I1` of `card-fee-review` in `bank-a`, waiting at the officer review task, `lockVersion = 1`; branch supervisor `hoa` with `processes:cancel`; teller `tuan` with `processes:start` only.
- **When:** (a) `hoa` posts `POST /instances/I1:cancel` with `If-Match: "1"`, `Idempotency-Key: k1` and `{ "reason": "Customer withdrew the card application" }`, then replays it, then sends `k1` with another reason, then cancels again with a new key. (b) Two requests cancel another running instance at the same moment with the same `If-Match` and different keys. (c) Four requests cancel a third instance at the same moment with one key. (d) `If-Match` is stale; `tuan`, who may start but not cancel, cancels; the reason is missing, blank or 2,001 characters; or `If-Match` or `Idempotency-Key` is missing.
- **Then:** (a) `200` with status `CANCELLED`, the task `CANCELLED` and `endedAt` set; one `INSTANCE_ENDED` history entry with actor `hoa` and the reason, and one `processes.instance.cancelled.v1` carrying `cancelledBy: hoa` and the reason, both in the transaction of the change; the replay returns the same body and writes nothing; the other reason is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`; the second cancel is `409 INVALID_STATE`. (b) One `200` and one `409 VERSION_CONFLICT`, one history entry. (c) Four `200` with one body, one history entry. (d) `409 VERSION_CONFLICT`, `403 PERMISSION_DENIED`, `400 INPUT_INVALID`, `428 PRECONDITION_REQUIRED`; nothing is written. `/api/bpm/me` lists `processes:cancel` for `hoa` and not for `tuan`. The permission, the reason and the absence of compensation are stated by `PRE-054` (PD-103): cancel cancels tokens and tasks under the instance lock, makes no adapter call and runs no compensation in 0.2.0.
- **Example:** `cancel(I1, If-Match "1", k1) -> 200 CANCELLED; replay -> same body; second cancel -> 409 INVALID_STATE`.
- **Test ID:** `PRE_050_cancel_is_idempotent_locked_and_ends_tokens_and_tasks`

### PRE-051 A user task is claimed before it is completed, and only its claimant completes or hands it back
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-03`, `TR-SEC-04`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** Card application `IT-T-CLAIM` submitted by `tuan`; its `officer-review` task `T1` is `OPEN` at `lockVersion = 0`, assigned to `role:CARD_OFFICER` and marked four-eyes; officers `an` and `hoa`; `zoe` is an officer of `bank-b`.
- **When:** (a) `an` reads `GET /tasks/T1`, claims it with `If-Match: "0"` and `Idempotency-Key: claim-1`, replays the claim, then sends `claim-1` with `If-Match: "5"`. (b) `hoa` unclaims it; `an` unclaims it with `If-Match: "1"` and replays. (c) `an` and `hoa` claim another open task at the same moment with `If-Match: "0"`. (d) `an` completes an `OPEN` task; `hoa` completes a task `an` has claimed. (e) `zoe` reads, claims, unclaims or completes `T1`. (f) A caller without `processes:task:work` claims; a claim without `If-Match` or `Idempotency-Key`.
- **Then:** (a) The read has `ETag: "0"` and `allowedActions: ["claim"]`; the claim is `200` with `status: CLAIMED`, `claimedBy: an`, `lockVersion: 1`, `ETag: "1"`, `allowedActions: ["unclaim", "complete"]`; the replay returns the same body; the reused key is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. (b) `hoa` gets `403 TASK_NOT_ASSIGNED`; `an` gets `200` with `status: OPEN` and no claimant; the replay returns the same body; one `TASK_CLAIMED` and one `TASK_UNCLAIMED` history entry and one `processes.task.claimed.v1` and one `processes.task.unclaimed.v1` exist. (c) One `200` and one `409 VERSION_CONFLICT`, one `TASK_CLAIMED` entry. (d) `409 INVALID_STATE` ("claim it first") and `403 TASK_NOT_ASSIGNED`; nothing is written. (e) `404 TASK_NOT_FOUND` each time; no row of `bank-b` is written and `T1` stays at `lockVersion 0`. (f) `403 PERMISSION_DENIED`, `428 PRECONDITION_REQUIRED`.
- **Example:** `claim(T1, "0") by an -> 200 CLAIMED, lockVersion 1; complete(T1, "1") by hoa -> 403 TASK_NOT_ASSIGNED; complete(T1, "1") by an -> 200`.
- **Test ID:** `PRE_051_task_is_claimed_before_completion_and_only_its_claimant_completes`

### PRE-052 The requester withdraws their own pending definition, and it returns to draft without a rejection
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, PD-097
- **Milestone:** M2
- **Kind:** control
- **Given:** `kyc-onboarding` v2 is `PENDING_APPROVAL` with `lockVersion 3`, requested by `alice`, who holds `processes:author`; v1 is `ACTIVE`; `bob` holds `processes:approve`.
- **When:** `alice` sends `POST /api/bpm/processes/v1/definitions/kyc-onboarding/versions/2:withdraw` with `Idempotency-Key`, `If-Match: "3"` and an optional reason. Failure cases: `bob` withdraws v2; `alice` rejects v2 with `:reject`; `alice` withdraws with a stale `If-Match`; `alice` withdraws v1.
- **Then:** `200`. v2 is `DRAFT` with the request cleared, one history row `WITHDRAWN` (actor `alice`, the reason or null) and one `processes.definition.withdrawn.v1`. No rejection is recorded, v1 stays in effect and nothing is published. A replay returns the same answer and writes nothing. `bob` gets `403 PERMISSION_DENIED`; `alice`'s `:reject` gets `403 SELF_APPROVAL_FORBIDDEN` with a detail pointing to `:withdraw`; the stale `If-Match` gets `409 VERSION_CONFLICT`; v1 gets `409 INVALID_STATE`. No failure case changes anything.
- **Example:** `alice withdraws her pending v2 -> DRAFT, WITHDRAWN, v1 still active; alice rejects her own -> 403, use withdraw`.
- **Test ID:** `PRE_052_requester_withdraws_own_pending_definition_to_draft`

### PRE-053 Retiring a definition version stops new starts, never running instances, and the last version needs a confirmation
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, PD-101, PD-102
- **Milestone:** M2
- **Kind:** semantics
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
- **Kind:** control
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
- **Kind:** control
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
- **Kind:** control
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
- **Kind:** semantics
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
- **Kind:** semantics
- **Given:** The `assess-documents` step's guard spec requires `{ riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE }` with no other field. In the first case the assessor answers `riskScore: "high"`. In the second it hands the call off, and its callback, on another replica, reports `{ riskScore: 140, recommendation: APPROVE, comment: "ok" }` through `POST /async-steps/{jobId}:complete`.
- **When:** The executor records the first answer, and the assessor's retry breaks the schema again; the completion API records the second. A third step runs with `runners.async-agent.schema-retries: 0` and `onViolation: fail`.
- **Then:** The first bad answer of each path is `ACCEPTED`, because the step is not decided yet: it records `ASYNC_ATTEMPT_FAILED` with `AGENT_SCHEMA_INVALID`, `retryable: true` and `schemaRetry: 1`, the job is `DUE` again after the back-off with one more allowed attempt (`max_attempts` 3 to 4, so the schema retry does not use up an attempt for technical failures), and the token still waits on the step. The retry that breaks the schema again is `REFUSED`: `ASYNC_RESULT_REFUSED` with code `AGENT_SCHEMA_INVALID`, the violations as reasons, and the step's route (here a four-eyes review task). With no schema retries the first bad answer is `REFUSED` and the job `FAILED`. No path writes `ASYNC_RESULT_ACCEPTED` or a result variable, and none evaluates the guardrail rule set. The violations name paths and rules, never values. The check runs in `ProcessServices.recordAttempt` only: a runner may pre-check, but the result transaction is the authoritative check, and it calls nothing outside the JVM (an ArchUnit rule).
- **Example:** `riskScore "high" -> ACCEPTED (retry 1 of 1), job DUE; "high" again -> REFUSED, AGENT_SCHEMA_INVALID, review task; nothing accepted`.
- **Test ID:** `PRE_058_an_answer_breaking_its_output_schema_is_retried_once_then_refused_to_review`, `PRE_058_with_no_schema_retries_the_first_bad_answer_is_refused`, `PRE_058_an_answer_breaking_its_output_schema_is_refused_through_the_completion_api`, `PRE_058_result_guards_evaluate_in_process_only`

### PRE-059 An agent result its guardrail refuses goes to a reviewer, by any path, and the reviewer completes the step
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-SEC-04`, #363
- **Milestone:** M2
- **Kind:** semantics
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
- **Kind:** control
- **Given:** Agent answers that the guardrail would allow (`{ riskScore: 20, recommendation: APPROVE }`), in each of these hosts or steps: the guardrail rule set was never published in the tenant; the host has no Rule Engine; the output schema check throws; the check takes 600 ms against `runners.async-agent.guard-budget` of 500 ms (measured on the engine clock); the job has no guard spec. In unit tests, the decision answers `allow: "yes"`, no `allow`, no match, a failure, or throws; and a schema uses an unsupported keyword (`pattern`, `$ref`).
- **When:** Each result is recorded.
- **Then:** Every one is `REFUSED` and nothing of it is accepted. The code is `GUARDRAIL_EVALUATION_ERROR` with the cause as reason (`RULE_ENGINE_MISSING`, `IllegalStateException`, `GUARD_BUDGET_EXCEEDED`, `GUARDRAIL_CONTRACT_INVALID`, `DECISION_NO_MATCH`, the decision's code), and the step goes to its reviewer. The budget is checked after the schema check, so a schema check that uses it up never reaches the decision, and again after the decision. A missing or malformed guard spec is `GUARD_SPEC_INVALID` and fails the job, because no reviewer can be trusted from it. An unsupported schema keyword is a violation, never skipped.
- **Example:** `rule set missing -> REFUSED, GUARDRAIL_EVALUATION_ERROR, review task; no guard spec -> REFUSED, GUARD_SPEC_INVALID, job FAILED`.
- **Test ID:** `PRE_060_a_missing_guardrail_rule_set_fails_closed_to_review`, `PRE_060_a_host_without_a_rule_engine_refuses_every_agent_answer`, `PRE_060_a_check_that_breaks_or_overruns_its_budget_fails_closed`, `PRE_060_an_agent_job_without_a_guard_spec_fails_and_is_never_accepted`, `PRE_060_a_guardrail_that_cannot_decide_fails_closed`, `PRE_060_without_a_rule_engine_the_result_is_refused`, `PRE_060_a_check_slower_than_the_budget_refuses_even_an_allowed_result`, `PRE_060_a_missing_or_malformed_guard_spec_fails_the_job`, `PRE_060_a_schema_check_that_uses_up_the_budget_never_reaches_the_decision`, `PRE_060_an_unsupported_keyword_fails_closed`

### PRE-061 An agent step's guard spec is validated at import, and an invalid one never becomes a draft
- **Traces to:** `TR-AGT-01`, `TR-SEC-04`, `TR-DEF-04`, #52
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `loan-agent-assessment` with a `serviceTask` `assess-documents`, `horus:type="agentTask"`, `horus:agent="documents.assess"`, `horus:timeout="PT2S"`, inputs `applicationId` and `requestedAmount`, a `<horus:outputSchema>` holding `{ riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE, summary?: string, mask: true }` and `<horus:guardrail decision="agent-output-guardrails" onViolation="route-to-human" reviewer="role:COMPLIANCE_REVIEWER"/>`. The host registers the agent adapter `documents.assess` and has the Rule Engine. Variants: no schema; a schema that is not JSON or not an object; a schema with `pattern`; no guardrail; a guardrail without `decision`, with `onViolation: ignore`, with `route-to-human` and no or a malformed reviewer, with `fail` and a reviewer, or with `fourEyes="false"`; two guardrails; no `horus:agent`; `horus:handler` or `horus:mode` on the step; `horus:timeout="PT0S"`; an agent `documents.summarise` the host does not register; a host without the Rule Engine.
- **When:** Each document is imported.
- **Then:** The valid one is a draft whose canonical form carries `type: agentTask`, `agent`, the schema as canonical JSON, `guardrails` (`decision`, `onViolation`, `reviewer`) and `timeout`, so the definition hash covers the whole guard spec and a change of the reviewer, the rule set or a schema bound is a new version; its BPMN export reads back to the same hash. `onViolation` defaults to `route-to-human`. Each variant is `422 VALIDATION_FAILED` with a problem naming `assess-documents`: `GUARD_SPEC_INVALID` for the schema and guardrail faults (a schema keyword outside the host validator's subset is refused here, not only when an answer arrives), `AGENT_MISSING`, `AGENT_TASK_INVALID`, `TIMEOUT_INVALID`, `AGENT_NOT_REGISTERED`, and `BPMN_NOT_EXECUTABLE` without the Rule Engine. No `pre_definition` row is written. A problem never echoes the schema text. Four-eyes cannot be switched off: a review is always four-eyes. The guardrail rule set's own contract is checked by `PRE-016`.
- **Example:** `fourEyes="false" -> 422 GUARD_SPEC_INVALID at assess-documents, nothing written`.
- **Test ID:** `PRE_061_an_agent_step_reads_with_its_agent_schema_guardrail_inputs_and_timeout`, `PRE_061_the_canonical_form_carries_the_whole_guard_spec_so_the_hash_covers_it`, `PRE_061_on_violation_defaults_to_route_to_human_and_fail_needs_no_reviewer`, `PRE_061_an_invalid_guard_spec_is_refused_naming_the_step`, `PRE_061_an_agent_step_needs_its_agent_and_takes_no_handler_mode_or_bad_timeout`, `PRE_061_a_schema_in_the_subset_passes_the_import_check`, `PRE_061_a_schema_outside_the_subset_is_refused_at_import_naming_the_path`, `PRE_061_a_host_validator_accepts_every_schema_at_import_unless_it_says_otherwise`, `PRE_061_an_invalid_guard_spec_or_an_unknown_agent_is_refused_at_import_and_nothing_is_written`, `PRE_061_a_host_without_a_rule_engine_cannot_import_an_agent_step`

### PRE-062 An agent step parks an agent job with the published guard spec, and its model call is keyed by the attempt id
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-ASY-01`, `TR-IDEM-04`, `TR-TEN-01`, #52
- **Milestone:** M2
- **Kind:** semantics
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
- **Kind:** control
- **Given:** The output schema of `assess-documents` marks `summary` `mask: true`. The agent answers `{ riskScore: 20, recommendation: APPROVE, summary: "Applicant Nguyen Van An, national id 079088001234, ..." }` for one loan and the same with `riskScore: 85` for another.
- **When:** Each answer is recorded.
- **Then:** The allowed answer is the step result as given, and `ASYNC_RESULT_ACCEPTED` records it masked by the policy of `ASYNC_RESULT_REFUSED` (`PRE-059`): `{ riskScore: 20, recommendation: APPROVE, summary: "***" }`, `outputFields: 3`, with `guardrailDecisionId`; no history row of the loan contains the national id. The refused answer opens the review task, whose `review` object (task view, `GET /tasks/{id}`, migration V10 `pre_task.review`) carries `code: GUARDRAIL_VIOLATION`, `reasons: [HIGH_RISK_SCORE]`, `ruleIds: [R2]`, `guardrailDecisionId`, the `inputs` the agent saw and the masked `output` with `outputFields`; every other task has no `review`. The reviewer's answer `{ riskScore: 85, recommendation: REFER }` completes the step and the loan moves on to `underwrite`.
- **Example:** `summary with a national id -> history "***", review task output.summary "***"`.
- **Test ID:** `PRE_063_an_accepted_agent_answer_is_recorded_masked_by_the_output_schema`, `PRE_063_the_review_task_carries_why_the_answer_was_refused_and_the_masked_answer`

### PRE-092 A candidate guardrail is replayed over recorded answers and reports what would change, without calling a model
- **Traces to:** `TR-AGT-03`, `TR-OBS-01`, PD-128, #98
- **Milestone:** M3
- **Kind:** control
- **Given:** Step `assess-documents` of `kyc-onboarding` in one tenant has 1,200 answers recorded in November 2026, checked by `kyc-document-guardrails` v2, whose rule R5 refuses `confidence < 0.90`. 96 of them had a confidence of 0.85 to 0.89 and went to a reviewer; reviewers changed the document type in 7 of those 96 (`HUMAN_OVERRIDE`) and confirmed 2 others. Draft v3 changes R5 to `confidence < 0.85`.
- **When:** A user with `processes:agent:replay` sends `POST /agent-replays` with `{ "definitionKey": "kyc-onboarding", "nodeId": "assess-documents", "from": "2026-11-01", "to": "2026-11-30", "candidate": { "ruleSet": "kyc-document-guardrails", "version": 3 } }` (Java: `AgentReplayService.start`), the job poller replays it, and the user reads `GET /agent-replays/{id}` and `GET /agent-replays/{id}/cases`.
- **Then:** The start answers `202` with a `QUEUED` run. The poller replays it in pages of `replay.page-size` (500): three pages. The summary shows 1,200 cases, 1,104 unchanged, 96 newly allowed, 7 of them `OVERRIDDEN_BY_REVIEWER`, 0 newly refused, 0 newly routed to review. Each line carries the attempt id, the instance id, the recorded and the candidate outcome, the change and the matched rule ids (`R5` recorded, `R9` candidate), never an output value. A change from a masked value is not an override, so the 2 confirmations are not flagged. The stub agent records no call, and `pre_history`, `rle_decision` and every instance row are unchanged.
- **Masked inputs.** A top-level field kept only as `***` is left out of the check. When a rule of the candidate rule set version reads it in a condition or an output (declaring it as an input is not enough), or the schema could test it differently (the check fails closed: the candidate output schema differs from the recorded one anywhere except in the definitions of unmasked properties, whatever the keyword, `type`, combinator, conditional or pattern rule; or the recorded refusal came from the schema, so the failing field is unknown), the check would not have seen it: the line is `NOT_REPLAYED` with code `MASKED_INPUT_REQUIRED`, never `UNCHANGED` or a change, and the summary counts it in `maskedInputRequired`, apart from `notReplayed`. Example: candidate v4 of `kyc-document-guardrails` adds a rule `nationalId` in a list of blocked numbers, and `nationalId` is masked; the 40 answers with `nationalId` masked report `NOT_REPLAYED`, none `UNCHANGED`. A host that needs those cases checked replays them through its own harness (PD-128).
- **Example:** `v3 R5 < 0.85 over 1,200 cases -> 96 newly allowed, 7 of them corrected by a reviewer`.
- **Test ID:** `PRE_092_candidate_guardrail_replay_reports_newly_allowed_cases_and_reviewer_overrides` and `PRE_092_a_candidate_that_reads_a_masked_field_is_not_replayed_and_counted_apart` (`AgentReplayIT`); `PRE_092_*` in `AgentReplayCheckTest` and `KernelContractArchitectureTest`

### PRE-093 The same replay gives the same report, and today() is the recorded time
- **Traces to:** `TR-DET-01`, PD-128, #98
- **Milestone:** M3
- **Kind:** control
- **Given:** Answers recorded on 2 and 3 November 2026; rule R2 of the candidate refuses `expiryDate < today()`, and the answer of 3 November has an expiry date of 10 November 2026.
- **When:** The same replay runs twice on 20 January 2027.
- **Then:** `today()` evaluates to 3 November 2026 for that case, the time its answer was checked, so it is not newly refused. The two runs give identical report lines and summaries; only the run ids and times differ.
- **Example:** `case of 3 Nov, expiry 10 Nov, replayed 20 Jan -> not expired`.
- **Test ID:** `PRE_093_replay_is_deterministic_and_today_is_the_recorded_time`

### PRE-094 A replay is bounded, and a candidate that breaks the contract fails before any case runs
- **Traces to:** `TR-PERF-10`, `TR-AGT-03`, PD-128, #98
- **Milestone:** M3
- **Kind:** control
- **Given:** (a) A period of 45 days. (b) A period that holds more answers than `replay.max-cases` (50,000 by default; the test lowers it to 3 over 4 answers). (c) A candidate whose output is `decision: string` with no `allow`. (d) A recorded answer whose job payload has no guard spec.
- **When:** Each is requested or reached.
- **Then:** (a) `400 QUERY_INVALID`, because the longest period is `query.max-range` (`P31D`). (b) `422 REPLAY_TOO_LARGE`, with `cases` and `limit`. (c) `422 GUARDRAIL_CONTRACT_INVALID` (`PRE-016`), and no run is created. (d) The case is reported `NOT_REPLAYED` with code `GUARD_SPEC_INVALID`, and the run completes. Runs and their reports are deleted `replay.retention` (`P30D`) after they were created; they are not audit records.
- **Example:** `45 days -> 400 QUERY_INVALID; 4 answers over a limit of 3 -> 422 REPLAY_TOO_LARGE`.
- **Test ID:** `PRE_094_replay_is_bounded_and_a_candidate_that_breaks_the_contract_fails_before_any_case_runs`; `PRE_094_*` in `AgentReplayCheckTest`

### PRE-095 Replays read only the caller's tenant and need their own permission
- **Traces to:** `TR-TEN-01`, `TR-SEC-05`, PD-128, #98
- **Milestone:** M3
- **Kind:** control
- **Given:** Run `R1` of tenant A, a user of another tenant, and a tenant A user with `processes:read` only.
- **When:** The other tenant's user reads `R1`, its report and one of its cases, and names tenant A's candidate; the `processes:read` user starts a run and reads `R1`.
- **Then:** The other tenant's user gets `404 REPLAY_NOT_FOUND` and `404 RULE_SET_NOT_FOUND`; the `processes:read` user gets `403 PERMISSION_DENIED`. Cases and the candidate are read in the caller's tenant only. With `processes:agent:replay`, `GET /agent-replays/{id}/cases/{attemptId}/answer` shows the recorded answer as history keeps it: masked output fields as `***` and masked inputs as keyed hashes. A repeat of the start with the same `Idempotency-Key` returns the original run.
- **Example:** `other tenant reads R1 -> 404, nothing returned`.
- **Test ID:** `PRE_095_replay_reads_only_the_callers_tenant_and_needs_its_own_permission`

### PRE-096 An agent step calls one allowed MCP tool, keyed by the job id, and its result is checked where it is recorded
- **Traces to:** `TR-AGT-04`, `TR-IDEM-04`, `TR-ASY-01`, #99
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The example host `examples/mcp-tool-adapter` registers adapter `mcp-risk` with the server URL, a bearer token and `allowed-tools: [lookup_customer_risk]` from host configuration. Step `score-customer` names the tool with the literal input `tool` and passes `customerId: C-778`, with an output schema `{ riskBand: A|B|C|D, score: integer 0..100 }`. The demo server answers `structuredContent { "riskBand": "B", "score": 62 }`, the first time after the step's one-second timeout.
- **When:** An instance reaches the step, the first attempt times out, and after the backoff the job is claimed again.
- **Then:** Both attempts send `tools/call` for `lookup_customer_risk` with `_meta.idempotencyKey` = the job id and their own attempt id as `_meta.correlationId`. The server runs the tool once and returns the stored result to the second call. The result is accepted in the result transaction after the schema and guardrail checks, and the token moves on. No engine module depends on the example.
- **Example:** `lookup_customer_risk(C-778) twice, same key -> tool ran once, riskBand B, score 62 accepted`.
- **Test ID:** `PRE_096_an_agent_step_calls_one_allowed_mcp_tool_keyed_by_the_job_id_and_its_result_is_checked` (`McpToolAdapterIT`); `PRE_096_*` in `McpToolAdapterTest` and `EngineIndependenceTest` (all in `examples/mcp-tool-adapter`)

### PRE-097 Tool errors, unstructured answers and tools outside the allow-list fail the attempt with a named code
- **Traces to:** `TR-AGT-04`, `TR-AGT-01`, #99
- **Milestone:** M3
- **Kind:** other
- **Given:** (a) a step that names `delete_customer`, which is not allowed; (b) a tool answer with `isError: true`; (c) a JSON-RPC error, an HTTP 5xx or a timeout; (d) a text-only answer that asks for another tool; (e) a structured answer the guardrail refuses.
- **When:** Each attempt is recorded.
- **Then:** (a) `MCP_TOOL_NOT_ALLOWED`, not retryable, and no request is sent; (b) `MCP_TOOL_ERROR`, not retryable; (c) `MCP_PROTOCOL_ERROR` or `ADAPTER_TIMEOUT`, retryable; (d) the adapter returns an empty answer and the result check refuses it with `AGENT_SCHEMA_INVALID`; (e) `GUARDRAIL_VIOLATION`, and the step goes to its reviewer (`PRE-059`). No server message text is kept: history records the code, and the adapter's detail holds at most the JSON-RPC error number or the HTTP status. The adapter returns data and calls no second tool. A call for another tenant's customer is refused by the server's own authorisation and the tool writes nothing.
- **Example:** `isError true -> MCP_TOOL_ERROR, not retryable; text only -> AGENT_SCHEMA_INVALID`.
- **Test ID:** `PRE_097_*` in `McpToolAdapterTest` and `McpToolAdapterIT` (`examples/mcp-tool-adapter`)

### PRE-098 A reference adapter returns a labelled JSON answer, keyed by the attempt id, that the engine checks when it records it
- **Traces to:** `TR-AGT-04`, `TR-AGT-03`, `TR-ASY-01`, PD-129, #100
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The example host `examples/model-adapters` registers adapter `doc-reviewer` (OpenAI-compatible Chat Completions) and `doc-reviewer-anthropic` (Anthropic Messages). Each one's configuration sets the API base address, the model, the prompt template `prompts/kyc-review@p7` and an API key read from the environment. A local stub server returns a recorded answer whose JSON content is `{ "documentType": "PASSPORT", "nameMatches": true, "expiryDate": "2031-05-01", "tamperingSuspected": false, "confidence": 0.97 }`.
- **When:** Step `assess-documents` runs. The stub fails the first call with HTTP 429 and `Retry-After: 2`, and the step runs again after its backoff.
- **Then:** The request carries the rendered prompt with only the step's bounded inputs, asks for structured output with the step's output schema (`response_format` `json_schema`, or `output_config.format`), and carries the attempt id in the API's `user` or `metadata.user_id` field. The 429 case fails with `MODEL_RATE_LIMITED`, retryable, and the next attempt sends a new attempt id. The answer is returned with labels `model`, `promptVersion: p7`, `usage.inputTokens` and `usage.outputTokens`, and accepted after the schema and guardrail checks. No prompt text, input or key appears in logs; logs show the adapter, the attempt id, the status and the latency. Keys come from host configuration, are masked in `toString()`, and are required for Anthropic Messages; a self-hosted OpenAI-compatible server may need none. No tenant id, instance id or job id is sent to the vendor. No engine module depends on the examples, and they use no vendor SDK.
- **Example:** `429 then 200 -> attempt 1 MODEL_RATE_LIMITED, attempt 2 accepted, promptVersion p7`.
- **Test ID:** `PRE_098_*` in `ModelAdapterTest`, `ModelAdaptersIT` and `EngineIndependenceTest` (`examples/model-adapters`)

### PRE-099 Model errors and non-JSON answers map to named codes, and a bad answer never advances the step
- **Traces to:** `TR-AGT-04`, `TR-AGT-01`, PD-129, #100
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The stub answers (a) HTTP 400; (b) HTTP 401; (c) HTTP 5xx or 529, or no answer within the request timeout; (d) a refusal or a content-filter stop; (e) prose instead of JSON; (f) JSON with `confidence: 0.72`.
- **When:** Each attempt is recorded.
- **Then:** (a) `MODEL_REQUEST_INVALID`, not retryable; (b) `MODEL_AUTH_FAILED`, not retryable; (c) `MODEL_UNAVAILABLE` or `ADAPTER_TIMEOUT`, retryable; (d) `MODEL_REFUSED`, not retryable, and the job fails for good; (e) the adapter returns an empty answer and the result check refuses it with `AGENT_SCHEMA_INVALID`, retried once, then the step goes to its reviewer; (f) guardrail `LOW_CONFIDENCE` (rule `R5`), routed to the KYC reviewer (`PRE-059`). In none of them is a result variable written before the result check accepts it. No API error text is kept: the adapter's detail holds at most the HTTP status and the `Retry-After` seconds.
- **Example:** `content filter stop -> MODEL_REFUSED, not retryable, step not advanced`.
- **Test ID:** `PRE_099_*` in `ModelAdapterTest` and `ModelAdaptersIT` (`examples/model-adapters`)

### PRE-107 A CMMN file is refused by name at every BPMN import, before any other check
- **Traces to:** `TR-STD-04`, `TR-STD-26`, #112
- **Milestone:** M3
- **Kind:** semantics
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
- **Kind:** other
- **Given:** `loan.bpmn` contains a `boundaryEvent` `be-timeout` on line 42 (stage `S1`), an `inclusiveGateway` `ig-1` on line 57 (`S1`), an `eventBasedGateway` `notify` on line 63 (`S2`), an `adHocSubProcess` `adhoc-1` on line 70 (`S3`), a `conditionExpression` with `language="http://www.w3.org/1999/XPath"` on line 88 (`not planned`, a deviation in section 12 of the standards page) and `camunda:class` on a `serviceTask` on line 30.
- **Given:** `loan.bpmn` contains a `boundaryEvent` `be-timeout` on line 42 (stage `S1`), a `globalTask` `do-1` on line 57 (`not planned`), a `sendTask` `notify` on line 63 (`S2`), an `adHocSubProcess` `adhoc-1` on line 70 (`S3`), a `conditionExpression` with `language="http://www.w3.org/1999/XPath"` on line 88 (`not planned`, a deviation in section 12 of the standards page) and `camunda:class` on a `serviceTask` on line 30.
- **Given:** `loan.bpmn` contains a `boundaryEvent` `be-timeout` with a compensation definition on line 42 (stage `S2`), an `inclusiveGateway` `ig-1` on line 57 (`S1`), a `choreographyTask` `notify` on line 63 (`not planned`), an `adHocSubProcess` `adhoc-1` on line 70 (`S3`), a `conditionExpression` with `language="http://www.w3.org/1999/XPath"` on line 88 (`not planned`, a deviation in section 12 of the standards page) and `camunda:class` on a `serviceTask` on line 30.
- **Given:** `loan.bpmn` contains a `boundaryEvent` `be-timeout` on line 42 (stage `S1`), an `inclusiveGateway` `ig-1` on line 57 (`S1`), an `eventBasedGateway` `notify` on line 63 (`S2`), a `conditionExpression` with `language="http://www.w3.org/1999/XPath"` on line 88 (`not planned`, a deviation in section 12 of the standards page) and `camunda:class` on a `serviceTask` on line 30.
- **When:** It is imported.
- **Then:**
  - The answer is `422 BPMN_UNSUPPORTED_ELEMENT` with 4 entries in document order in `unsupportedElements`, each `{ element, elementId, line, stage, message }` (PD-141: the field and the item shape are the Rule Engine's, `RLE-061`; `problems` stays for `VALIDATION_FAILED` and `GUARDRAIL_CONTRACT_INVALID`). The XPath entry is the unsupported condition language, with the element `conditionExpression`. `message` is plain English that may change; the element and the stage are the contract.
  - `camunda:class` is a foreign extension and is not refused. It is listed under `warnings` as `FOREIGN_EXTENSION_IGNORED` with its namespace and line, and the stored source keeps it byte for byte. Warnings keep the item fields of a problem (`code`, `message`, `element`, `elementId`, `line`).
  - An attribute that changes how a step runs is refused by name: `isForCompensation="true"` on a sub-process, transaction or ad-hoc sub-process, `isInterrupting="false"` on an element that is neither a start nor a boundary event, `startQuantity` or `completionQuantity` other than 1 and `implementation` other than `##unspecified` or `urn:horus-bpm:handler:<name>` on a service task (`not planned`). `PRE-382` to `PRE-385` lift the other refusals of this kind.
  - At most 100 entries are returned, with `violationsTruncated: true` beyond that.
  - Ids are printed safely: control characters are removed and ids are cut at 200 characters.
  - The stage is one of `S1`, `S2`, `S3`, `not planned` from the coverage table in section 6.1 of the standards page, or `S0` for an element the table lists for the current stage that this release has not finished (call activity, transaction, an intermediate throw event with no event definition). The stage of an intermediate throw event comes from its event definition: a `signalEventDefinition` throw is `S2` (a message throw is supported, `PRE-235`), an `escalationEventDefinition` throw `S1`, a `compensateEventDefinition` throw inside a `transaction` block is supported (`PRE-076`). No stage label names a sprint or a date.
- **Example:** `5 unsupported -> 422 with stages S1, S1, S2, S3, not planned; camunda:class -> warning`.
- **Test ID:** `PRE_108_every_element_and_language_outside_the_stage_is_listed_with_id_line_and_stage`, `PRE_108_a_vendor_attribute_is_a_warning_with_its_namespace_and_line_and_is_not_refused`, `PRE_108_a_file_with_only_a_vendor_attribute_is_not_refused`, `PRE_108_a_throw_event_takes_its_stage_from_its_event_definition`, `PRE_108_a_behaviour_attribute_that_changes_how_a_step_runs_is_refused_by_name`, `PRE_108_at_most_100_entries_are_listed_and_the_rest_set_the_truncated_flag`, `PRE_108_ids_lose_control_characters_and_are_cut_at_200_characters`, `PRE_108_no_stage_label_names_a_sprint_a_release_or_a_date`, `PRE_108_event_definitions_take_the_stage_of_their_kind`, `PRE_108_each_entry_carries_element_id_line_and_stage_and_the_list_says_when_it_was_cut`, `PRE_108_unsupported_bpmn_elements_are_listed_with_their_stage_and_nothing_is_stored`

### PRE-204 A successful import lists the vendor attributes it kept but did not read
- **Traces to:** `TR-STD-04`, `TR-STD-26`, PD-141, #380
- **Milestone:** M3
- **Kind:** other
- **Given:** `card-block.bpmn` is a valid process whose start event carries `camunda:initiator="officer"` in the namespace `http://camunda.org/schema/1.0/bpmn`; caller `analyst-lan` holds `processes:deploy`.
- **When:** It is imported with `Idempotency-Key: k-warn-1`, then again with the same key, and then the version is read.
- **Then:**
  - `201` with the stored version and a `warnings` list: one entry `FOREIGN_EXTENSION_IGNORED`, element `camunda:initiator`, `elementId` `s`, the `line` and the namespace in `message`: the same item as the `warnings` of a refusal. The stored source keeps the attribute byte for byte.
  - The repeat returns the same body, warnings included. Reading the version later has no `warnings`: only the answer to an import carries them. A file with no vendor attribute has no `warnings` member.
- **Example:** `camunda:initiator on startEvent s -> 201 with warnings[0] = FOREIGN_EXTENSION_IGNORED camunda:initiator`.
- **Test ID:** `PRE_204_a_successful_import_lists_the_vendor_attribute_it_kept_but_did_not_read`, `PRE_204_reading_the_version_again_has_no_warnings_because_only_the_import_answer_carries_them`, `PRE_108_a_successful_read_returns_the_vendor_attributes_it_ignored_as_warnings`, `PRE_108_a_file_without_vendor_attributes_reads_with_no_warnings`

---

## Local and Remote Service Steps (#63)

A `serviceTask` with no `horus:mode`, or `horus:mode="local"`, calls a registered `ServiceTaskHandler` inside the step transaction; `horus:mode="remote"` parks on the Async Wait-State and calls a registered `ServiceAdapter` with no transaction open (ADR-0008). The remote call keeps the guarantees of #51: the completion token is stored only as its hash, the job id is the idempotency key, and a completion is scoped to its adapter's completer roles.

### PRE-070 One process runs a local step in its transaction and a remote step outside any transaction
- **Traces to:** `TR-ASY-01`, `TR-IDEM-04`, `TR-SEC-03`, #63
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Definition `loan-disbursement` v1 is active in tenant `bank-a`. Its steps are `reserve-limit` (a `serviceTask` with no `horus:mode`, handler `limits.reserve`), then `post-to-core` (`horus:mode="remote"`, adapter `core-banking`, `horus:timeout="PT10S"`), then `end`. The host pool has 2 connections, and the stub `core-banking` adapter answers after 2 s, first with a retryable failure.
- **When:** An instance starts with business key `LN-2026-0042` and amount `50,000,000 VND` (minor units, currency `VND`), and a `tenantId` of `bank-b` in its variables; the posting is claimed, fails, and is claimed again after the backoff, and answers `COMPLETED` with `{ "postingRef": "FT26324001" }`.
- **Then:** The start transaction calls `limits.reserve` with the instance's tenant, instance id, node id, business key, attempt id and only its declared inputs; the handler's own row (a 50,000,000 VND limit reservation) and the token's move to `post-to-core` commit together. The token is `WAITING_ASYNC` with one `ASYNC_SERVICE` job. The adapter runs with no engine transaction open on its thread and no pool connection held. `AdapterCall.idempotencyKey()` is the job id on every retry; the attempt id and the completion token are new on each claim, and neither the token nor its value appears in `pre_timer` or history. The adapter receives the tenant of the locked `pre_instance` row, never the `tenantId` of the variables (`TR-SEC-03`). TX2 records `ASYNC_RESULT_ACCEPTED`, stores `postingRef` and ends the instance. History lists, in order: `NODE_COMPLETED` of `reserve-limit`, `ASYNC_STEP_SCHEDULED`, `ASYNC_RESULT_ACCEPTED` and `INSTANCE_ENDED`. A step with no `horus:mode` is local. Another tenant neither reads the instance (`404 INSTANCE_NOT_FOUND`) nor completes its step (`404 ASYNC_STEP_NOT_FOUND`), and nothing is written.
- **Example:** `reserve-limit (local, in TX) -> post-to-core (remote, 0 connections held for 2 s) -> COMPLETED, postingRef FT26324001`.
- **Test ID:** `PRE_070_*` in `ServiceStepModesTest` (`process-engine-postgres`) and `LocalServiceInvokerTest` (`process-engine-core`)

### PRE-071 A local handler that fails rolls back with its step and leaves nothing half done
- **Traces to:** `TR-ASY-01`, `TR-SEC-08`, `TR-OBS-01`, #63
- **Milestone:** M2
- **Kind:** control
- **Given:** The definition of `PRE-070`. Here `limits.reserve` writes its reservation row and then throws `IllegalStateException` with a message that names the customer's national id.
- **When:** (a) An instance is started through `POST /instances`. (b) The same handler is reached after a timer of `loan-disbursement-scheduled` fires instead.
- **Then:** (a) The start rolls back: no `pre_instance` row, no reservation row, no history and no job. The caller gets `500 INTERNAL` with a `correlationId` only, never the exception message; the Java API throws an exception naming the handler, the step and the exception class, with the handler's exception as its cause. (b) The firing rolls back. The job is due again after `jobs.retry-backoff` times the attempt (10 s, then 20 s) and becomes `FAILED` after `jobs.max-attempts` (3); the handler ran once per firing and none of its rows committed. A firing that rolled back leaves the instance's committed state unchanged, and the attempt id is derived from it, so every firing presents the same attempt id: it serves for correlation and keys nothing outside the transaction. The incident on `FAILED` follows with #55 and #58. A returned `HandlerResult.Failed` keeps `422 STEP_FAILED`. A local handler takes no idempotency key because its writes commit or roll back with the step. The handler contract states that it must not call the network. A call above `handlers.slow-call-warning` (200 ms) logs `process.step.slow` with the step, the handler and the elapsed time, and never the inputs; the time is read from an injected ticker.
- **Example:** `handler throws after writing -> 0 rows committed, 500 INTERNAL with correlationId`.
- **Test ID:** `PRE_071_*` in `ServiceStepModesTest`, `LocalServiceInvokerTest` and `ProcessRestErrorMatrixTest`

### PRE-072 A service step whose mode, handler or adapter is unknown is refused by name
- **Traces to:** `TR-DEF-04`, `TR-SEC-01`, #63
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A BPMN file with these faults: step `post-to-core` on line 31 with `horus:mode="batch"`; a local step `reserve-limit` naming handler `limits.reserv`, which is not registered; a remote step `notify-partner` naming adapter `partner-hub`, which is not registered; a local step `check-cutoff` with `horus:timeout="PT5S"`.
- **When:** The file is imported, and a corrected draft that still names the unregistered handler and adapter is sent to `:request-approval`; and a pending version whose adapter the approver's host does not register is sent to `:approve`.
- **Then:** The import returns `422 VALIDATION_FAILED` listing `MODE_INVALID` for `post-to-core` on line 31 and `TIMEOUT_INVALID` for `check-cutoff`; each entry carries the step id, the line and the problem, and no draft is written. Unregistered handler and adapter names are not checked at import, so a Git pipeline can import a draft before the host registers them; an import that activates at once (dev auto-approve) checks them. `:request-approval` refuses the draft with `422 VALIDATION_FAILED`, naming `reserve-limit -> limits.reserv` (`HANDLER_NOT_REGISTERED`) and `notify-partner -> partner-hub` (`ADAPTER_NOT_REGISTERED`); the version stays `DRAFT`. `:approve` refuses the same way and the version stays `PENDING_APPROVAL`. Names are resolved against the host's typed beans only, never by reflection (`TR-SEC-01`). Agent steps keep their import check (`PRE-061`).
- **Example:** `horus:mode="batch" line 31 -> 422 VALIDATION_FAILED; unregistered adapter -> approval refused`.
- **Test ID:** `PRE_072_*` in `ServiceStepModesTest`

## Business Errors and Late Results (#55)

### PRE-111 An agent or remote step's business error fails its job on the first attempt and opens an incident, by any path
- **Traces to:** `TR-OPS-03`, `TR-IDEM-04`, #55
- **Milestone:** M2
- **Kind:** semantics
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
- **Kind:** semantics
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
- **Kind:** control
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
- **Kind:** semantics
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
- **Kind:** control
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
- **Kind:** semantics
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
- **Kind:** control
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
- **Kind:** control
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
  - (f) The diff is `truncated` with `approvableHash: null` and no `definitionHash` on either side; the approval is `409 CHANGE_SET_TRUNCATED` and nothing is written, published or recorded. A change list cut off at its limit carries no hash that may be approved. This holds for the unpaged read and for an approval that names no change-set hash; the paged read and the whole-set hash of `PRE-201` and `PRE-202` are the way a change set over the limit is approved outside `dev`.
- **Example:** `bob approves with H after the content became H2 -> 409 CONTENT_CHANGED (current H2), 0 rows`.
- **Test ID:** `PRE_177_an_approval_with_the_reviewed_hash_publishes_the_version_and_records_the_hash`, `PRE_177_an_approval_of_content_changed_after_review_is_refused_naming_the_current_hash_and_writes_nothing`, `PRE_177_an_approval_without_a_hash_is_refused_while_the_setting_is_on`, `PRE_177_with_the_dev_relaxation_an_approval_without_a_hash_is_accepted`, `PRE_177_another_tenants_version_is_refused_and_nothing_is_written`, `PRE_177_a_replay_with_another_hash_under_the_same_key_is_a_key_reuse`, `PRE_177_a_truncated_diff_exposes_no_approvable_hash_and_an_approval_against_it_is_refused`, `PRE_177_the_two_approval_refusals_match_the_contract_and_name_the_current_content`, `PRE_177_an_approval_without_the_reviewed_hash_is_accepted_only_on_the_dev_profile`

### PRE-190 The pending approvals list shows every definition version waiting for a second person, with the hash to review against
- **Traces to:** `TR-SEC-04`, `TR-SEC-11`, `TR-TEN-01`, `TR-PERF-07`, PD-139, #31
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** semantics
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
- **Kind:** semantics
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
- **Kind:** control
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
- **Kind:** control
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
- **Kind:** control
- **Given:** Tenant `bank-a`'s plan over its running `loan-review` loans; tenant `bank-b` has its own `loan-review` loans.
- **When:** A client of `bank-b` with `processes:migration:manage` and `processes:read` reads and dry-runs `bank-a`'s plan and plans `bank-a`'s versions; each tenant then dry-runs its own plan.
- **Then:** The read and the dry run are `404 MIGRATION_NOT_FOUND`, and the plan is `404 DEFINITION_NOT_FOUND`. Every engine table holds the same rows and no event is published. Each tenant's report counts and lists only its own instances. A body naming a tenant, or a step mapping, is `400 INPUT_INVALID`. A plan row cannot reference another tenant's definition, and the plan history refuses `UPDATE`, `DELETE` and `TRUNCATE`.
- **Example:** `bank-b validates bank-a's plan -> 404, 0 rows; bank-a 4 running, bank-b 7 running, each sees its own`.
- **Test ID:** `PRE_175_another_tenant_can_neither_plan_dry_run_nor_read_a_plan_and_nothing_is_written`, `PRE_175_each_tenant_dry_runs_only_its_own_instances`, `PRE_175_plan_rows_are_tenant_keyed_and_the_history_is_append_only`, `PRE_175_migration_problems_match_the_contract`

### PRE-176 The graph diff is deterministic and bounded, names steps by id and name, and never crosses tenants
- **Traces to:** `TR-DEF-03`, `TR-TEN-01`, #76
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** semantics
- **Given:** Tenant `bank-a`; loan application `LN-2027-0042` started by `alice`, submitted by `bob`; its `check-documents` task `T1` is assigned to `role:LOAN_OFFICER`, `OPEN` at `lockVersion = 0`; officers `carol` and `dan` hold `LOAN_OFFICER` in the host directory; supervisor `hoa` holds `processes:task:manage` and `processes:task:work`; `carol` is on leave.
- **When:** (a) `hoa` reads `GET /tasks/T1`. (b) `hoa` posts `POST /tasks/T1:reassign` with `If-Match: "0"`, `Idempotency-Key: re-1` and `{ "assignee": "carol", "reason": "Loan queue is backed up" }`, then replays it. (c) `carol` falls ill; `hoa` posts `POST /tasks/T1:delegate` with `If-Match: "1"`, `Idempotency-Key: de-1` and `{ "assignee": "dan", "reason": "Carol is on sick leave" }`, then replays it. (d) `hoa` posts `:reassign` to the now claimed task, and `:delegate` to an open one.
- **Then:** (a) `allowedActions` is `["reassign"]`. (b) `200` with `status: CLAIMED`, `claimedBy: carol`, `lockVersion: 1`, `ETag: "1"` and `allowedActions: ["delegate"]`; the replay returns the same body; one `TASK_REASSIGNED` history entry with actor `hoa`, claimant `carol` and the reason, and one `processes.task.reassigned.v1` carrying `reassignedBy: hoa`, `claimedBy: carol` and the reason, in the usual envelope. (c) `200` with `claimedBy: dan`, `lockVersion: 2`; one `TASK_DELEGATED` entry and one `processes.task.delegated.v1` with `delegatedBy: hoa`, `previousClaimant: carol`, `claimedBy: dan` and the reason; the replay changes nothing. The task keeps `assignee: role:LOAN_OFFICER`. (d) `409 INVALID_STATE` each time; nothing is written. A task that is `COMPLETED` or `CANCELLED` is refused the same way.
- **Example:** `reassign(T1, "carol", "Loan queue is backed up", If-Match "0") -> 200 CLAIMED by carol; delegate(T1, "dan", ..., If-Match "1") -> 200 CLAIMED by dan`.
- **Test ID:** `PRE_183_reassign_and_delegate_move_a_task_and_record_both_people_and_the_reason`, `PRE_183_a_completed_or_cancelled_task_is_never_moved`, `PRE_183_a_replay_returns_the_first_answer_and_writes_nothing`

### PRE-184 A target who may not work the task is refused with ASSIGNEE_NOT_ALLOWED and nothing is written
- **Traces to:** `TR-SEC-04`, `TR-IDEM-03`, #263
- **Milestone:** M2
- **Kind:** control
- **Given:** The loan application of `PRE-183` with its four-eyes `approve-credit` task `T2` for `role:LOAN_OFFICER`, claimed by `carol`, `lockVersion = 3`; `alice` started the instance and `bob` completed its `submit-application` task; `lan` holds `CUSTOMER_SERVICE` only; the host directory knows `alice`, `bob`, `carol` and `dan` as `LOAN_OFFICER`. A second open task `T3`, assigned to `user:carol`.
- **When:** `hoa` delegates `T2` to `alice`, then to `bob`, then to `lan`, then to `carol`, then to `system:timer`, then to `dan`; and reassigns `T3` to `lan`.
- **Then:** The first five are `409 ASSIGNEE_NOT_ALLOWED` (started the instance, completed an earlier task, lacks the role, already holds the task, reserved identity); the history, the events, the idempotency records and `T2`'s `lockVersion` are unchanged after each. `dan` succeeds. A task assigned to one user can go to `lan`. With no directory bean, a role or group task cannot be given to a named user: `409 ASSIGNEE_NOT_ALLOWED`.
- **Example:** `delegate(T2, "alice") -> 409 ASSIGNEE_NOT_ALLOWED (started this instance); delegate(T2, "dan") -> 200`.
- **Test ID:** `PRE_184_the_starter_and_an_earlier_completer_cannot_be_given_a_four_eyes_task`, `PRE_184_a_user_without_the_role_or_group_is_refused_and_a_user_task_goes_to_anyone`, `PRE_184_without_a_directory_a_role_task_cannot_be_given_to_a_named_user`

### PRE-205 Agent step fields that declare no `mask` are listed as reminders and never block
- **Traces to:** `TR-SEC-05`, PD-146, #438
- **Milestone:** M2
- **Kind:** control
- **Given:** `loan-agent-assessment`, whose agent step `assess-documents` reads `applicationId` (`mask="false"`) and `requestedAmount` (no `mask`), and whose output schema marks `summary` `"mask": true` and leaves `riskScore` and `recommendation` undeclared; `horus.bpm.processes.classification.warn-unclassified` at its default `true`.
- **When:** The pipeline imports it as a draft, reads the version, requests approval; the engine starts outside `dev` with the version active; then the same with the setting `false`.
- **Then:** `GET .../versions/1` and `:request-approval` answer with `warnings` listing `{code: UNCLASSIFIED_FIELD, step: assess-documents, field, direction, message}` for `requestedAmount` (`INPUT`), `recommendation` and `riskScore` (`OUTPUT`, in the stored schema's order); the request succeeds (`PENDING_APPROVAL`). Start-up logs one WARN counting the fields per active version (`<tenant>/loan-agent-assessment v1: 3`) that names the setting and no field or value. With the setting `false` nothing is listed or logged. Only the declaration counts; names and values are never read. The V45 partial index `idx_pre_definition_active` serves the start-up read.
- **Example:** `request-approval -> 200 PENDING_APPROVAL, warnings [UNCLASSIFIED_FIELD requestedAmount INPUT, ...]`.
- **Test ID:** `PRE_205_unclassified_agent_fields_are_listed_and_the_approval_request_still_succeeds`, `PRE_205_a_silenced_deployment_lists_nothing_and_logs_no_summary`, `PRE_205_the_start_up_summary_counts_fields_per_active_version_without_names_or_values`, `PRE_205_the_reader_records_whether_an_input_declares_mask_and_the_hash_ignores_it`, `TR_OPS_01_V45_builds_a_valid_partial_index_on_existing_definitions_concurrently`

### PRE-206 A refused hand-over says why, and screens show people and roles by name
- **Traces to:** `TR-UI-08`, PD-110, #442
- **Milestone:** M2
- **Kind:** other
- **Given:** `T2` and the targets of `PRE-184`; the open `check-documents` task of a second case, assigned to `role:LOAN_OFFICER`; the host's `DisplayNames` (default: `horus.bpm.display-names.roles|groups|users.<id>.<language>`) knows `alice` as "Nguyen Thi Alice"; `alice` requested approval of three definition versions.
- **When:** `hoa` delegates `T2` to `carol`, `bob`, `alice` and `system:timer`, and reassigns `check-documents` to `lan`; `bob` lists `GET /approvals`; a screen asks `GET /api/bpm/display-names?roles=CARD_OFFICER&users=lan` with `Accept-Language: vi`.
- **Then:** Each `409 ASSIGNEE_NOT_ALLOWED` carries `reason`: `ALREADY_ASSIGNED` (carol), `FOUR_EYES_PARTICIPANT` (bob, alice), `RESERVED_IDENTITY` (system:timer), `MISSING_ROLE` with `required: role:LOAN_OFFICER` (lan); `MISSING_GROUP` names `group:<id>`. Each approval item carries `requestedByName` "Nguyen Thi Alice", the id when the host knows no name; `requestedBy` keeps the id. `/display-names` returns known names in the caller's language with the default language as fallback, leaves unknown identifiers out, refuses more than 100 identifiers of a kind or a malformed one with `400 INPUT_INVALID`, and an anonymous call with `401`.
- **Example:** `reassign(check-documents, lan) -> 409 {code: ASSIGNEE_NOT_ALLOWED, reason: MISSING_ROLE, required: role:LOAN_OFFICER}`.
- **Test ID:** `PRE_206_each_assignee_refusal_names_its_reason_and_the_role_the_target_lacks`, `PRE_206_a_target_without_the_role_of_an_open_task_is_refused_naming_that_role`, `PRE_206_assignee_not_allowed_carries_the_reason_code_and_the_role_the_target_lacks`, `PRE_206_each_item_carries_the_requester_display_name_and_falls_back_to_the_id`, `PRE_206_names_come_back_in_the_callers_language_with_the_default_language_as_fallback`, `PRE_206_too_many_or_malformed_identifiers_are_400_and_an_anonymous_caller_is_401`, `PRE_206_a_default_language_outside_two_or_three_letters_stops_start_up`

### PRE-187 Moving a task needs the manage permission, a reason, If-Match and an Idempotency-Key, and never crosses tenants
- **Traces to:** `TR-SEC-04`, `TR-TEN-01`, `TR-IDEM-03`, #263
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** control
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
- **Kind:** semantics
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
- **Kind:** control
- **Given:** An instance with variable `nationalId` and business key `DISB-ACC-0001`.
- **When:** Every event of `PRE-085` is serialised.
- **Then:** No payload contains a variable value, task result, step output, masked value or handler message. Payloads hold the ids, keys, versions, codes and actors listed in section 6.4 of `process-engine/docs/DESIGN.md`; the business key appears only in the events that list it there.
- **Example:** `instance.started.v1 payload = { instanceId, definitionKey, version, businessKey }, no nationalId`.
- **Test ID:** `PRE_086_event_payloads_carry_ids_and_codes_never_variables_or_masked_values`

### PRE-087 CI reports step throughput against 1,000 transitions per second, without gating
- **Traces to:** `TR-PERF-04`
- **Milestone:** M2
- **Kind:** other
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
- **Kind:** control
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
- **Kind:** semantics
- **Given:** `LN-2026-0102` after its review, waiting on `underwrite`.
- **When:** The reviewer reads `GET /definitions/loan-document-review/versions/1/export?format=bpmn` and `GET /instances/{id}`.
- **Then:** The export is BPMN 2.0 XML with a shape for every step (`start`, `assess-documents`, `underwrite`, `end`); the instance names its definition and the step its live token waits on; and history holds the case's path in order: `INSTANCE_STARTED`, `ASYNC_STEP_SCHEDULED`, `ASYNC_RESULT_REFUSED`, `STEP_ROUTED_TO_REVIEW`. No cockpit code is needed to read them.
- **Example:** `export -> 4 shapes; instance -> underwrite WAITING_USER`.
- **Test ID:** `PRE_186_*` in `GuardedAgentJourneyIT`

## Task Escalation and the Calendar API (#73, #409)

### PRE-178 An overdue task moves to the escalation role at its deadline, keeping four eyes
- **Traces to:** `TR-CAL-01`, `TR-SEC-04`
- **Milestone:** M2
- **Kind:** control
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
- **Kind:** semantics
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
- **Kind:** control
- **Given:**
  - Variant A: a level targets `user:minh`, who started the instance of a four-eyes task.
  - Variant B: the instance is cancelled before the deadline, while a task of another tenant with the same schedule is overdue.
  - Variant C: two replicas sweep the same overdue task at the same moment.
- **When:** The deadline passes and the sweep runs.
- **Then:**
  - A: the assignment is unchanged, the level is counted so the sweep does not try it again, history records `ESCALATION_REFUSED` with `ESCALATION_TARGET_FORBIDDEN`, and an incident with that code opens.
  - B: the cancelled instance's task is not escalated; only the other tenant's task moves, and its event carries that tenant. A task is only ever changed under the lock of its own instance row, in the tenant stored on it.
  - C: one replica applies the level and the other, after the first commits, finds it applied and writes nothing.
  - D: see Variant D below.
- **Variant D (#449): a target role the tenant does not have is refused at approval.**
  - **Given:** a host that registers a `RoleCatalogue` (SPI `io.github.dhluong90.bpm.process.spi.RoleCatalogue`, `exists(tenantId, role)`) listing `CREDIT_OFFICER` and `BRANCH_SUPERVISOR` for `bank-a`; a definition whose user task `review-application` escalates to `role:RISK_HEAD`; the same definition escalating to `role:BRANCH_SUPERVISOR`; a host with no catalogue; `bank-b`, whose list does not hold `RISK_HEAD`.
  - **When:** the version is sent for approval, and again when a second person approves it (the list may have changed between); the same with `BRANCH_SUPERVISOR`; with no catalogue; `bank-b` sends the definition naming a role only `bank-a` holds.
  - **Then:** the unknown role is `422 VALIDATION_FAILED` with one problem `ESCALATION_ROLE_UNKNOWN` naming the step `review-application` and the role `role:RISK_HEAD` (once per role and step), at request-approval and at approval alike, and nothing is written; the known role is accepted at both; with no catalogue the default `RoleCatalogue.ACCEPT_ALL` checks nothing and the starter logs a warning at start-up outside the `dev` profile; the catalogue is asked with the caller's tenant, so one tenant's roles never satisfy another's; a `user:` or `group:` target is not asked (the host directory answers for people at run time, `PRE-183`).
  - **Example:** `review-application -> role:RISK_HEAD, catalogue lists CREDIT_OFFICER and BRANCH_SUPERVISOR -> 422 ESCALATION_ROLE_UNKNOWN (review-application, role:RISK_HEAD), 0 rows`.
- **Example:** `target user minh is the maker -> no change, incident ESCALATION_TARGET_FORBIDDEN`.
- **Test ID:** `PRE_180_a_level_that_targets_the_maker_is_recorded_with_an_incident_and_changes_nothing`, `PRE_180_a_cancelled_instance_is_not_escalated_and_another_tenants_task_is_never_touched` (`TaskEscalationPostgresIT`), `PRE_180_an_escalation_to_a_role_the_host_does_not_list_is_refused_at_request_and_at_approval`, `PRE_180_an_escalation_to_a_listed_role_is_sent_for_approval_and_approved`, `PRE_180_a_host_without_a_role_catalogue_checks_no_role`, `PRE_180_one_tenants_roles_never_satisfy_another_tenant` (`EscalationRoleCatalogueTest`), `PRE_180_a_host_without_a_role_catalogue_is_warned_outside_dev_and_one_with_a_catalogue_is_not` (`StatsRetentionAndRoleCatalogueSettingsTest`)

### PRE-181 The calendars in force and the calendar changes are read over REST, in the caller's tenant
- **Traces to:** `TR-CAL-01`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** Tenant `bank-a` with calendars `branch-hours`, `card-centre` and `vn-banking-hours`, a pending change of `vn-banking-hours` and two approved ones; tenant `bank-b` with `bank-b-hours`; the caller holds `processes:read`.
- **When:** The caller reads `GET /calendars` with `limit=1` and then with the returned cursor, `GET /calendars/vn-banking-hours`, `GET /calendar-changes?status=PENDING` and `status=APPROVED` page by page, and `GET /calendar-changes/{id}`; a manager of `bank-b` uses a cursor of `bank-a`.
- **Then:** Calendars come in key order as summaries without hours or holidays, each page read through the registered bounded queries `calendars` and `calendars-after` (the `(tenant_id, calendar_key)` unique index); one calendar has its hours and holidays and an `ETag` of its version; changes come newest first through `idx_pre_calendar_change_status` (V32) with the old and the new value; `bank-b` never sees `bank-a` rows; a cursor of another tenant, an unknown status or a limit above `query.max-page-size` is `400 QUERY_INVALID`; another tenant's calendar or change is `404 CALENDAR_NOT_FOUND`. Every body validates against `process-engine/api/openapi.yaml`.
- **Example:** `GET /calendars?limit=1 -> branch-hours + nextCursor; the cursor with bank-b -> 400 QUERY_INVALID`.
- **Test ID:** `PRE_181_calendars_and_changes_are_listed_in_pages_for_the_callers_tenant_only` (`CalendarChangeIdempotencyPostgresIT`), `PRE_181_the_calendar_list_one_calendar_and_the_changes_match_the_contract` (`CalendarRestContractTest`)

### PRE-182 A calendar change is proposed, approved and rejected once per idempotency key, under four eyes
- **Traces to:** `TR-IDEM-01`, `TR-CAL-01`, `TR-SEC-04`
- **Milestone:** M2
- **Kind:** control
- **Given:** Tenant `bank-a`, `vn-banking-hours` at version 3, `kim` and `lan` holding `processes:calendar:manage`, `horus.bpm.processes.calendar.approval` at its default `four-eyes` (PD-118 as amended by PD-131); `kim` proposes with `If-Match: "3"` and `Idempotency-Key: k-1`.
- **When:** `POST /calendars/vn-banking-hours/changes` is sent twice with the same key and body, once with the same key and another reason, once without `If-Match` and once without the key; `lan` approves with `a-1` and repeats it; two replicas receive the same proposal together; `bank-b` proposes with the same key `k-1`.
- **Then:** The first call is `201` with the change `PENDING` and a `Location`; the repeat returns the same status and body and writes no second change, no second decision and no second `processes.calendar.changed.v1` (the answer is stored whole in `pre_idempotency` in the transaction of the work, so a repeat after the approval still answers `PENDING`); the same key with another request is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`; a missing `If-Match` or key is `428 PRECONDITION_REQUIRED`; a stale `If-Match` is `409 VERSION_CONFLICT`; the concurrent proposals create one change; a key belongs to its tenant; a caller without `processes:calendar:manage` is refused before any key is claimed. `kim` approving is `403 SELF_APPROVAL_FORBIDDEN`. An approval or rejection needs no `If-Match`: the calendar version a change was proposed against is checked under the calendar's row lock, and a change that is no longer pending is `409 INVALID_STATE`. A body with a tenant or any other field is `400 INPUT_INVALID`.
- **Example:** `propose(k-1) twice -> one change; approve(a-1) twice -> version 4 once, one event`.
- **Test ID:** `PRE_182_a_proposal_repeated_with_the_same_key_returns_the_first_answer_and_writes_nothing_more`, `PRE_182_an_approval_repeated_with_the_same_key_applies_once_and_emits_one_event`, `PRE_182_two_concurrent_proposals_with_one_key_create_one_change_and_a_key_is_per_tenant`, `PRE_182_a_proposal_without_the_permission_is_refused_before_any_key_is_claimed` (`CalendarChangeIdempotencyPostgresIT`), `PRE_182_*` in `CalendarRestContractTest`

---

## Paged Definition Diff, Step Statistics Retention, Compensation Timeline (Batch S9, slice A1)

### PRE-200 Hourly step statistics are kept for a configured time, and no slice is rolled up within the write-lag window
- **Traces to:** `TR-PERF-10`, `TR-OPS-01`, #410
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `pre_node_stats_hourly` holds hours of `bank-a` and `bank-b` from 15 July 2026, one from 22 August 2026 and today's (20 November 2026, 10:50); `horus.bpm.processes.stats.rollup-retention` is `P90D`; `stats.rollup-min-lag` and `stats.rollup-lag` are `PT10M`; completions at 10:30 and 10:45; more expired hours than one run deletes.
- **When:** the rollup runs; it runs again at 10:56; defaults and bad values are bound; a profile other than `dev` sets `rollup-min-lag` below `PT5M`.
- **Then:**
  - The run deletes hours whose start is older than the retention, counted in whole days back from now in UTC (here before 22 August 00:00), in every tenant, in transactions of 5,000 rows, at most 20 per run, skipping rows another replica holds; the next run continues. Newer hours stay. Nothing is deleted by a run another replica holds the watermark of.
  - No slice reaches past `now - max(rollup-lag)`: at 10:50 only the 10:30 completion is counted and the watermark stops at 10:40; at 10:56 the 10:45 one is counted too.
  - Defaults: `rollup-retention` `P13M`, `rollup-min-lag` `PT5M`. A retention under `P31D` (the longest statistics window) is refused at bind time; so is a `rollup-lag` shorter than `rollup-min-lag`, naming both properties. A `rollup-min-lag` below `PT5M` is refused at start-up unless the `dev` profile is active on its own.
  - Migration V36 adds `idx_pre_node_stats_hourly_bucket` so the purge reads by time and never the whole table.
- **Example:** `retention P90D at 20 Nov: 15 Jul hours deleted, 22 Aug kept; lag PT10M at 10:50: watermark 10:40`.
- **Test ID:** `PRE_200_hourly_rows_older_than_the_retention_are_deleted_by_the_rollup_run`, `PRE_200_a_purge_takes_bounded_batches_and_the_next_run_continues`, `PRE_200_no_slice_is_rolled_up_within_the_write_lag_window` (`NodeStatsRollupPostgresIT`), `PRE_200_retention_defaults_to_13_months_and_the_write_lag_window_to_five_minutes`, `PRE_200_a_retention_below_the_longest_window_and_a_lag_below_the_write_lag_window_are_refused`, `PRE_200_a_write_lag_window_below_five_minutes_is_accepted_on_the_dev_profile_only` (`StatsRetentionAndRoleCatalogueSettingsTest`)

### PRE-201 The whole change set between two versions is read page by page, and the hashes come with the last page
- **Traces to:** `TR-SEC-11`, `TR-DEF-03`, PD-139, #426
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `kyc-onboarding` v1 `ACTIVE` with 400 review steps; v2 `PENDING_APPROVAL` with every step renamed, reassigned and given another result variable: 1,200 changed values.
- **When:** `bob` reads `GET /definitions/kyc-onboarding/versions/2/diff?limit=500`, then each `page.nextCursor`; and with a cursor this change set did not issue, `limit=501`, `limit=0` and `limit=many`.
- **Then:**
  - Three pages hold 500, 500 and 200 changes of one sequence (steps added, steps removed, changed values, links added, links removed, each sorted as before), each change on exactly one page, no gap, `truncated: false`. The answer names `page.offset`, `page.total` and `page.nextCursor`.
  - `approvableHash`, `changeSetHash` and the `definitionHash` of both sides are present on the last page only (`null` or omitted before it). `changeSetHash` is the SHA-256 of both definition hashes and every change of the whole set, the same whatever the page size.
  - Each cursor carries a digest of every change before it, so a page cannot be reached without reading the pages before it; a cursor this change set did not issue, another offset with a real digest, a limit outside 1 to 500 or not a number is `400 QUERY_INVALID`. Nothing is written.
  - The read without `limit` and `cursor` is unchanged: at most 1,000 changed values, `truncated: true`, `approvableHash: null` and no `definitionHash` (`PRE-177` f).
- **Example:** `1,200 changes, limit 500 -> pages of 500, 500, 200; changeSetHash on page 3 only`.
- **Test ID:** `PRE_201_the_whole_change_set_is_read_page_by_page_and_the_hashes_come_with_the_last_page`, `PRE_201_a_cursor_this_change_set_did_not_issue_and_a_page_size_out_of_range_are_refused` (`ReviewedContentApprovalTest`), `PRE_201_a_paged_diff_gives_the_hashes_on_its_last_page_only_and_matches_the_contract` (`ProcessRestContractTest`)

### PRE-202 A change set over the limit is approved with the hash of the whole set read, and a changed set is refused
- **Traces to:** `TR-SEC-11`, `TR-SEC-04`, PD-139, #426
- **Milestone:** M2
- **Kind:** control
- **Given:** `PRE-201`'s v2 with 1,200 changes against the effective v1; `bob` read every page and holds `changeSetHash S` and `definitionHash H`; `horus.bpm.processes.approval.require-content-hash` is `true`; a second pending version with one renamed step.
- **When:**
  - (a) `bob` approves v2 with `expectedDefinitionHash: H` and `expectedChangeSetHash: S`.
  - (b) `bob` approves with `H` and another hash, then with `S` of an other form.
  - (c) `bob` approves with `H` and no `expectedChangeSetHash`.
  - (d) `bob` approves the small version with `H` alone, and with a wrong `expectedChangeSetHash`.
- **Then:**
  - (a) v2 is `ACTIVE`; the `APPROVED` history row and `processes.definition.approved.v1` carry `reviewedChangeSetHash: S` next to `reviewedDefinitionHash`. The change set is recomputed under the key lock after the status, four-eyes, lock-version and definition-hash checks, from the stored versions.
  - (b) A hash that is not the change-set hash now (the version or the effective version changed after the review) is `409 CONTENT_CHANGED` with `currentChangeSetHash` and `currentDefinitionHash`; one that is not 64 lower-case hexadecimal characters is `400 INPUT_INVALID`; nothing is written, published or recorded.
  - (c) `409 CHANGE_SET_TRUNCATED`, as in `PRE-177` (f); nothing is written.
  - (d) A change set within 1,000 values is approved on `H` alone; a change-set hash, when sent, must match (`409 CONTENT_CHANGED` otherwise). A hash sent for a version with no effective version to compare with is `409 CONTENT_CHANGED`.
  - The hash joins the idempotency request hash when named, so a repeat under the same key replays and a changed hash is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. The check is the same through the Java API and REST.
- **Example:** `1,200 changes, H and S read page by page -> ACTIVE outside dev; H without S -> 409 CHANGE_SET_TRUNCATED`.
- **Test ID:** `PRE_202_a_change_set_over_the_limit_is_approved_with_the_hash_of_the_whole_set_and_records_it`, `PRE_202_a_change_set_hash_that_is_not_the_whole_set_now_is_refused_and_writes_nothing`, `PRE_202_an_unpaged_read_and_an_approval_without_the_set_hash_keep_refusing_a_truncated_set`, `PRE_202_a_change_set_within_the_limit_needs_no_set_hash_and_checks_one_when_sent` (`ReviewedContentApprovalTest`)

### PRE-203 The instance timeline names its undo steps, what each undoes and where the undo of each saga scope stands
- **Traces to:** `TR-COMP-02`, `TR-TEN-01`, #80
- **Milestone:** M2
- **Kind:** other
- **Given:** the fund transfer of `PRE-073`: `hold-funds`, `debit-source` and `credit-target` inside the saga scope `transfer`, `credit-target` failing with `ACCOUNT_CLOSED`; once with every undo succeeding, once with `credit-back` failing on `ACCOUNT_FROZEN` for good, once read by `bank-b`.
- **When:** an operator with `processes:read` reads `GET /instances/{id}/activities`.
- **Then:**
  - Every entry has `kind`: `STEP`, or `COMPENSATION` for `COMPENSATION_STARTED`, `COMPENSATION_STEP_SCHEDULED`, `COMPENSATION_STEP_COMPLETED`, `COMPENSATION_STEP_FAILED`, `COMPENSATION_STEP_MANUAL` and `COMPENSATED`. An entry of an undo has `scopeTokenId` and `scope` (`transfer`); an undo step has `compensates` (the step it undoes, newest forward step first: `debit-source`, then `hold-funds`), the forward `attemptId`, `handler`, and `attempt` and `errorCode` when they apply; the start has `cause`.
  - `compensations` has one item per scope whose undo started, never cut by `limit`, in start order: `state` `UNDONE` when the scope ended (steps undone counted, `endedAt` set); `NOT_FULLY_UNDONE` when the last event of some step is a failure or a manual step, naming `blockedStep`, `incidentId` and `incidentCode` of its open incident, with no `COMPENSATED` entry; `IN_PROGRESS` otherwise. The cockpit shows the state as given and links `incidentId`.
  - `bank-b` gets `404 INSTANCE_NOT_FOUND`. The read writes nothing and carries no variable or input value.
- **Example:** `credit-target failed, undo debit-source then hold-funds -> scope transfer UNDONE, 2 steps; credit-back failed -> NOT_FULLY_UNDONE, blocked debit-source, incident COMPENSATION_FAILED`.
- **Test ID:** `PRE_203_the_timeline_lists_the_undo_steps_in_reverse_order_with_what_they_undo_and_the_scope_is_undone`, `PRE_203_a_failed_undo_leaves_the_scope_not_fully_undone_and_names_its_step_and_incident`, `PRE_203_the_timeline_of_another_tenants_instance_is_not_found` (`SagaCompensationTest`), `PRE_027_activities_body_matches_the_contract_and_a_matching_etag_gets_304` (`ProcessRestContractTest`, the contract of the new fields)

## Step Mapping, Child Processes and Imported Layout (#106, #92, #445)

### PRE-207 A renamed step is mapped and its tasks keep their claim, deadline and four-eyes rule
- **Traces to:** `TR-MIG-03`, `TR-MIG-01`, PD-023, #106
- **Milestone:** M3
- **Kind:** control
- **Given:** 40 `loan-origination` v1 loans wait on user task `credit-review` (four-eyes, `horus:sla` `PT8H`), 15 of the tasks claimed; v2 renames the step `credit-assessment` with the same task kind; the plan is `v1 -> v2` with `mappings: [{ from: credit-review, to: credit-assessment }]`.
- **When:** The plan is validated, then proposed, approved and executed; separately, a mapping names a step of another type (a user task to a timer) or a step that v2 does not have.
- **Then:**
  - The dry run reports `impact.mapped` 40, `impact.unmapped` 0 and no conflict, lists `credit-review` as `MAPPED` to `credit-assessment`, and changes nothing.
  - Execution moves each token, open task and pending job to `credit-assessment`. Task ids, claims (15), `due_at` and the four-eyes flag are kept; a claimed task completes on the new step. Each instance gets one `MIGRATED` history row whose `steps` name `credit-review -> credit-assessment`.
  - A mapping to a step of another type, or to a step the target lacks, is `422 MIGRATION_PLAN_INVALID` naming the pair, and no plan row is written.
- **Example:** `40 loans on credit-review -> credit-assessment, 15 claims kept; user task -> timer -> 422 naming credit-review -> cooling-off`.
- **Test ID:** `PRE_207_a_renamed_step_is_mapped_and_its_tasks_keep_their_claim_deadline_and_four_eyes`, `PRE_207_a_mapping_to_another_kind_of_step_or_to_a_missing_step_is_refused_naming_the_pair` (`StepMappingMigrationPostgresIT`), `PRE_207_*` in `MigrationRestContractTest`

### PRE-208 A removed step's work is cancelled with a reason, the token continues where the plan says, and a call in flight is never cut off
- **Traces to:** `TR-MIG-03`, `TR-MIG-02`, #106
- **Milestone:** M3
- **Kind:** semantics
- **Given:** 12 loans wait on user task `legacy-manual-check` and 3 on remote step `legacy-score` (2 jobs `DUE`, 1 `CLAIMED` with a call in flight); v2 removes both steps; the plan has `cancellations: [{ step: legacy-manual-check, moveTo: credit-assessment, reason: "Check merged into credit assessment" }, { step: legacy-score, moveTo: bureau-score, reason: "Replaced by bureau score" }]`.
- **When:** The plan is validated and executed; then, once the call has ended, a later plan for the same versions and instructions is executed.
- **Then:**
  - The dry run reports `impact.cancelled` 14, `impact.inFlight` 1, one conflict `STEP_IN_FLIGHT` at `legacy-score` listing the instance, and the plan stays valid (an in-flight call is not a reason to refuse it).
  - Execution, per instance in one transaction: cancels the open task (`TASK_CANCELLED`, the plan's reason) or the `DUE` job (`JOB_CANCELLED`), moves the token to `moveTo`, whose step runs as on arrival (a task opens, a job parks), and writes `MIGRATED`. A cancelled task's context is not copied to history.
  - The in-flight instance stays on v1 and its outcome is `SKIPPED` with code `STEP_IN_FLIGHT`. A later plan for the same versions and instructions moves it once its job is no longer `CLAIMED`. Compensation stack entries of cancelled steps are kept unchanged.
- **Example:** `12 tasks cancelled, 2 jobs cancelled, 1 STEP_IN_FLIGHT left on v1, moved by the later plan`.
- **Test ID:** `PRE_208_a_removed_steps_work_is_cancelled_with_a_reason_and_the_token_continues_where_the_plan_says` (`StepMappingMigrationPostgresIT`)

### PRE-209 A plan that leaves a removed step unhandled is listed, and one that names a step twice or cancels without a reason is refused
- **Traces to:** `TR-MIG-03`, `PRE-014`, #106
- **Milestone:** M3
- **Kind:** other
- **Given:** v2 removes `legacy-manual-check`, where 5 loans wait; one plan neither maps nor cancels it; a second maps and cancels it; a third cancels it without a `reason`, a fourth without `moveTo`, a fifth with a `moveTo` that v2 lacks.
- **When:** Each plan is created or validated.
- **Then:**
  - The first plan is created and its dry run is invalid, listing `STEP_NOT_IN_TARGET` at `legacy-manual-check` with 5 instances.
  - The others are `422 MIGRATION_PLAN_INVALID` with `problems` naming the step. A reason is required and at most `lifecycle.reason-max-length` characters. A cancelled step must be absent from the target. At most 200 mappings and 200 cancellations.
  - No instance and no plan row is changed by the refused ones.
- **Example:** `legacy-manual-check mapped and cancelled -> 422 "legacy-manual-check is handled twice"`.
- **Test ID:** `PRE_209_a_plan_that_names_a_step_twice_or_cancels_without_a_reason_is_refused_and_an_unhandled_step_is_listed` (`StepMappingMigrationPostgresIT`)

### PRE-210 Migration commands with step instructions are permissioned, idempotent, locked and tenant-bound
- **Traces to:** `TR-IDEM-01`, `TR-TEN-01`, `TR-SEC-04`, PD-102, PD-114, PD-131, #106
- **Milestone:** M3
- **Kind:** control
- **Given:** Plan `M1` of `bank-a` with a mapping, proposed by `thu`.
- **When:** The plan is created again with the same `Idempotency-Key`; a holder of `processes:read` only plans; a `bank-b` holder of `processes:migration:manage` reads and approves `M1`; `lan` approves with a stale lock version.
- **Then:**
  - The repeat returns the first plan with its mappings and writes no second plan; the plan's history row names the steps and who planned.
  - The caller without the permission is `403 PERMISSION_DENIED`; `bank-b` gets `404 MIGRATION_NOT_FOUND` and nothing is written; the stale approval is `409 VERSION_CONFLICT`.
  - Four-eyes for the execution is unchanged (PD-131). Cancellations need the same single permission as the rest of the plan: `migration.cancel-approval` is `single`, and `four-eyes` is refused at start-up until it is built.
- **Example:** `bank-b approves M1 -> 404 MIGRATION_NOT_FOUND, 0 instances touched`.
- **Test ID:** `PRE_210_migration_commands_with_instructions_are_permissioned_idempotent_locked_and_tenant_bound` (`StepMappingMigrationPostgresIT`), `PRE_210_*` in `MigrationApprovalSettingsTest`

### PRE-211 A parent waits for its child and continues with the child's declared outputs only
- **Traces to:** `TR-STD-28`, `PRE-043`, PD-127, PD-144, #92
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `account-opening` has a `callActivity` `kyc` with `calledElement: kyc-check`, inputs `customerId` and `segment`, and output `kycLevel`; `kyc-check` v1 is effective; `P1` has business key `AO-2026-1201`, customer `C-778` and segment `RETAIL`.
- **When:** `P1` reaches `kyc`; the child sets `kycLevel: 2` and `reviewNote: "ok"` and ends; and, in a second case, `horus:calledVersion="1"` pins a version that a later one has retired.
- **Then:**
  - In `P1`'s step transaction its token is `WAITING_CHILD` and child `C1` starts on `kyc-check` v1 with `parentInstanceId`, `parentTokenId`, business key `AO-2026-1201/kyc` and only `customerId` and `segment` passed in. History of `P1` records `CHILD_STARTED`.
  - `C1`'s end transaction writes a `CALL_RESULT` job for `P1` and does not touch its row. The job fires in a transaction of its own: `P1.kycLevel` is 2, `reviewNote` is not copied, the token moves on, and history records `CHILD_COMPLETED`.
  - A pinned version that is not `ACTIVE` raises the incident `NO_EFFECTIVE_VERSION` on the step and starts no child.
  - The import reads `callActivity` (`calledElement`, `horus:calledVersion`, `<horus:input>`, `<horus:output>`) as a `callProcess` step, exports it, and reads the export back to the same definition hash; `horus:in="all"` is `CALL_INPUT_ALL_REFUSED` and a missing or malformed `calledElement` is `CALLED_ELEMENT_INVALID`.
- **Example:** `P1 at kyc -> C1 on kyc-check v1 -> kycLevel 2 back to P1, reviewNote not copied`.
- **Test ID:** `PRE_211_a_parent_waits_for_its_child_and_continues_with_the_childs_declared_outputs_only`, `PRE_211_a_pinned_version_is_used_even_when_a_later_one_is_effective_and_a_retired_one_raises_an_incident` (`CallActivityPostgresIT`), `PRE_211_a_call_activity_is_read_with_its_called_key_version_inputs_and_outputs`, `PRE_211_the_export_of_a_call_activity_reads_back_to_the_same_definition_hash`, `PRE_211_a_call_activity_without_a_called_key_or_passing_all_variables_is_refused_by_name` (`CallActivityBpmnTest`)

### PRE-212 A child start is never duplicated, and depth is bounded
- **Traces to:** `TR-IDEM-01`, `TR-RES-01`, PD-144, #92
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A child `C1` started for `P1`'s `kyc` token; separately, `ping`, whose first step calls `ping`; separately, `account-opening` deployed before `kyc-check` exists.
- **When:** A second child is written for the same token and attempt; `ping` is started with `calls.max-depth` at its default 5; `P1` reaches `kyc`.
- **Then:**
  - The unique index on `(tenant, parent_token_id, call_attempt)` refuses the second child: the token has exactly one.
  - The chain stops at depth 5: the root and five calls exist, and the step that would start depth 6 raises the incident `CALL_DEPTH_EXCEEDED` and starts no child. `calls.max-depth` is 1 to 5 and may be lowered only.
  - A `calledElement` with no effective version raises `NO_EFFECTIVE_VERSION` when the step runs; at approval it is never a refusal, because the child may be approved later.
- **Example:** `second child for the same token -> unique violation; ping -> 6 instances, depth 5 incident`.
- **Test ID:** `PRE_212_a_second_child_for_the_same_token_and_attempt_is_refused_by_the_database`, `PRE_212_the_chain_of_calls_stops_at_the_depth_limit_with_an_incident_and_no_child`, `PRE_212_a_called_element_with_no_effective_version_raises_an_incident_at_run_time` (`CallActivityPostgresIT`), `PRE_212_*` in `CallsSettingsTest`

### PRE-213 A child's failure or cancellation reaches the parent, and a parent's cancel ends its children
- **Traces to:** `TR-CAN-01`, `TR-INC-01`, PD-103, #92
- **Milestone:** M2
- **Kind:** semantics
- **Given:** (a) `C1` ended `FAILED`; (b) `C1` is cancelled by an operator with the reason `duplicate case`; (c) `P1` is cancelled by `ops.lan` while `C1` waits on a task.
- **When:** Each case runs and its call result fires; in (b) an operator retries the incident.
- **Then:**
  - (a) The parent step has an `OPEN` incident `CHILD_FAILED` on `kyc`. (b) Same, `CHILD_CANCELLED`, with the reason in the cause summary. The retry of (b) starts a new child for attempt 1 (`AO-2026-1205/kyc#1`) and the old child's result can no longer move the parent. A step with `horus:onFailure="compensate"` starts the parent's compensation instead of the incident.
  - (c) The cancel transaction locks `P1`, then `C1`, cancels both, with `C1`'s open task `CANCELLED`, and publishes `processes.instance.cancelled.v1` for each; `cancelledBy` is `ops.lan` and `C1`'s reason is `parent cancelled`.
- **Example:** `P1 cancelled -> C1 cancelled in the same transaction, reason "parent cancelled"`.
- **Test ID:** `PRE_213_a_cancelled_child_fails_the_parent_step_and_a_retry_starts_a_new_child_keyed_by_the_retry_count`, `PRE_213_a_child_that_ended_failed_fails_the_parent_step_with_child_failed`, `PRE_213_a_cancel_of_the_parent_cancels_its_child_in_the_same_transaction_with_the_parent_cancelled_reason` (`CallActivityPostgresIT`)

### PRE-214 A completed child is undone only by its call step's handler, inside the parent's tenant
- **Traces to:** `TR-COMP-02`, `TR-TEN-01`, PD-127, #92
- **Milestone:** M2
- **Kind:** control
- **Given:** (a) `kyc` sits in the saga `onboard` with `horus:compensate="reverse-kyc"`, has completed with child `C1`, and a later step fails with `onFailure: compensate`; (b) a definition in `bank-a` names `calledElement: kyc-check` while `kyc-check` exists only in `bank-b`; (c) a saga whose call step declares no undo.
- **When:** The walk reaches `kyc`; in (b) the step runs; in (c) the definition is imported.
- **Then:**
  - (a) `reverse-kyc` runs once with `forwardAttemptId` and the child's id (`childInstanceId`) in its inputs, and `C1`'s own stack is not walked: it is still `COMPLETED`, with no compensation row.
  - (b) The step raises `NO_EFFECTIVE_VERSION`; a child is always looked up and started in the parent's tenant, no definition can name a tenant, and no instance exists in `bank-b`.
  - (c) The import is `422 VALIDATION_FAILED` with `COMPENSATION_REQUIRED` naming the step; `horus:compensate="manual"` is accepted and raises `COMPENSATION_MANUAL_REQUIRED` when reached.
- **Example:** `compensate past kyc -> reverse-kyc once; C1 stack untouched; kyc-check only in bank-b -> NO_EFFECTIVE_VERSION`.
- **Test ID:** `PRE_214_a_completed_child_is_undone_only_by_the_call_steps_own_handler`, `PRE_214_a_child_is_looked_up_only_in_the_parents_tenant` (`CallActivityPostgresIT`), `PRE_214_a_call_inside_a_saga_must_declare_its_undo_or_declare_it_manual` (`CallActivityBpmnTest`)

### PRE-215 A BPMN import stores the file's diagram as layout revision 1, apart from the definition
- **Traces to:** `TR-DEF-02`, `PRE-024`, `PRE-026`, #445, #102
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A BPMN file with a `BPMNDI` diagram of three steps, one flow with two waypoints and a shape of an element the process lacks; the same process imported again with other content and another diagram; a version imported before layouts were stored.
- **When:** Each import completes and the layouts are read; the first version's layout is then changed in the cockpit.
- **Then:**
  - Layout revision 1 of each imported version holds the steps and flows that the diagram draws, with `source` `BPMN`, the importer as author and the hash of the definition it was drawn for. The unknown element, a coordinate outside the layout schema and a waypoint list longer than 50 points are dropped, never repaired. A file with no diagram stores none.
  - The definition hash does not depend on the diagram: the same file with and without it has one hash, and the version carries no diagram.
  - Each import has its own revision 1. A saved change is revision 2 of that version, needs no approval and writes no `REQUESTED` or `APPROVED` row. A version stored before layouts reads without a layout (`204`).
- **Example:** `import with diagram -> layout revision 1, source BPMN; re-import with other content -> its own revision 1`.
- **Test ID:** `PRE_215_a_bpmn_import_stores_its_diagram_as_layout_revision_1_apart_from_the_definition`, `PRE_215_each_import_has_its_own_revision_1_and_a_later_layout_change_needs_no_approval`, `PRE_215_a_definition_stored_before_layouts_existed_still_reads_without_a_layout` (`ImportedLayoutPostgresIT`), `PRE_215_a_diagram_is_read_as_a_layout_of_the_steps_and_flows_it_draws_and_nothing_else` (`CallActivityBpmnTest`)

### PRE-216 A task list over more role and group queues than the cap is refused
- **Traces to:** `TR-PERF-07`, `TR-SEC-01`, `PRE-030`, #408
- **Milestone:** M3
- **Kind:** other
- **Given:** `query.max-roles` of 100 (the default; the setting may be raised to 1,000 or lowered); `minh` holds the group `branch-hcm-01` and, in the first case, 99 roles; in the second, 100 roles.
- **When:** `minh` lists `assignment=candidate` with no `role` or `group`; then with `role=ops`; then `assignment=mine`.
- **Then:** The first list is served, because 99 roles and one group are 100 queues. With 100 roles it is `400 ROLE_FAN_OUT_EXCEEDED` with `cap: 100` and `provided: 101`, and no task is read. `role=ops` narrows the list to one queue and is served; `mine` reads no role queue and is served. The cap counts the caller's roles and groups together, as the host's resolver gives them, and a caller with `assignment=any` reads the tenant, not role queues.
- **Example:** `101 role and group queues, max-roles 100 -> 400 ROLE_FAN_OUT_EXCEEDED (cap 100, provided 101)`.
- **Test ID:** `PRE_216_a_task_list_over_more_role_queues_than_the_cap_is_400_ROLE_FAN_OUT_EXCEEDED` (`TaskQueryPostgresIT`), `PRE_216_role_fan_out_problem_is_400_with_the_cap_and_the_number_of_queues` (`StepCatalogueRestContractTest`)

### PRE-217 The review task carries the titles the definition declares for its fields
- **Traces to:** `TR-UI-04`, `PRE-010`, `PRE-012`, #386
- **Milestone:** M3
- **Kind:** other
- **Given:** An agent step whose `<horus:fieldTitles>` is `{"inputs": {"requestedAmount": {"en": "Requested amount", "vi": "Số tiền đề nghị"}}, "outputs": {"riskScore": {"en": "Risk score", "vi": "Điểm rủi ro"}}}`; a second version of it whose `riskScore` title is the number `5`; a third step with no titles.
- **When:** The agent's answer is refused and the reviewer reads the review task; the second version is imported.
- **Then:** The task's `review` object carries `fieldTitles` with the inputs and outputs as declared, every language the step names, and the review's other fields are unchanged. The step with no titles has no `fieldTitles`, and the screen shows the field's name in words. The second version is refused at import with `FIELD_TITLES_INVALID` on the step, a message that names the rule and no title. Titles are labels: no check, guardrail or decision reads them, and they are part of the definition, so its hash covers them. The set is at most 100 fields a side, 10 languages a field and 120 characters a title; a language is a tag such as `en` or `vi`, and the list is open.
- **Example:** `riskScore -> {en: Risk score, vi: Điểm rủi ro}`.
- **Test ID:** `PRE_217_the_review_task_carries_the_english_and_vietnamese_titles_the_step_declares`, `PRE_217_a_step_without_titles_has_no_fieldTitles_and_a_malformed_set_is_refused_at_import` (`AgentTaskIT`)

### PRE-218 The task and incident lists answer a matching If-None-Match with 304
- **Traces to:** `TR-PERF-07`, `TR-TEN-01`, `PRE-030`, `PRE-080`
- **Milestone:** M3
- **Kind:** other
- **Given:** `minh` of `bank-a` lists `GET /tasks` and `lan` lists `GET /incidents`; each answer carries an `ETag`, a hash of the body.
- **When:** The same list is read with `If-None-Match` set to that tag; then a task is claimed, or the next page is asked for, or an incident changes, or the caller loses `processes:incident:manage`, or a caller of `bank-b` sends `bank-a`'s tag; and finally a caller without the permission sends the tag.
- **Then:** The same list is `304` with the `ETag` and no body. Every change in an item, in the page or cursor, or in the caller's own view (`allowedActions`) gives another tag, so the old tag gets `200` and the new body. The tag is computed from the body the caller's own tenant and permissions produced, after the tenant and permission checks: a `bank-b` caller's list differs, so `bank-a`'s tag never matches it, a refused caller gets `403` and never `304`, and the tag says nothing about another tenant's tasks or incidents. `*` and weak (`W/`) tags match as for the instance activities read.
- **Example:** `GET /tasks -> ETag "9f2c..."; again with If-None-Match "9f2c..." -> 304; after a claim -> 200 with a new tag`.
- **Test ID:** `PRE_218_the_task_list_has_an_etag_that_follows_its_page_and_content_and_never_crosses_callers` (`ProcessRestErrorMatrixTest`), `PRE_218_the_incident_list_has_an_etag_that_follows_its_content_and_the_callers_actions` (`IncidentRestContractTest`)

### PRE-250 A script task stores the value of its expression in its result variable
- **Traces to:** `TR-STD-04`, `TR-SEC-01`, #485
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `card-fee-review` with a `scriptTask` `double-fee` whose script is `amount * 2` in `urn:horus-bpm:expr:v1` and `horus:resultVariable="result"`; an instance started with `amount = 1500000`.
- **When:** The token reaches `double-fee`.
- **Then:** In the same transaction `result` holds `3000000`, `amount` is unchanged, `NODE_COMPLETED` is written for `double-fee` with `by: engine` and `resultVariable: result`, and the token takes the outgoing flow. The script reads the variables visible to the token and sets that one variable. Money, dates and instants are stored as text.
- **Example:** `amount 1500000, script "amount * 2" -> result 3000000`.
- **Test ID:** `PRE_250_a_script_task_stores_its_value_in_the_result_variable_and_the_timeline_names_it`, `PRE_250_task_manual_task_and_script_task_import_and_export_to_the_same_definition` (`TaskElementsImportTest`)

### PRE-251 A plain task and a manual task pass the token on and the history records them
- **Traces to:** `TR-STD-04`, #485
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `card-fee-review` with a `task` `note` and a `manualTask` `post-letter` between start and end.
- **When:** An instance starts.
- **Then:** The token moves through both at once, the instance completes, and each writes `NODE_COMPLETED` with `by: engine`. The engine creates no task for a manual task: the work it names is done outside the engine.
- **Example:** `start -> note -> post-letter -> end, all in the start transaction`.
- **Test ID:** `PRE_251_a_manual_task_and_a_plain_task_pass_through_and_the_timeline_records_them`

### PRE-252 A script that is not valid, or in another language, is refused at import with its line
- **Traces to:** `TR-STD-04`, `PRE-033`, #485
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A `scriptTask` whose script `amount *` is on line 5; a `scriptTask` with no script or no `horus:resultVariable`; a `scriptTask` with `scriptFormat` `javascript`.
- **When:** Each file is imported.
- **Then:** The first is `422 VALIDATION_FAILED` with problem `SCRIPT_INVALID`, the element id and line 5. The second lists `SCRIPT_MISSING` and `RESULT_VARIABLE_REQUIRED`. `javascript` is `422 BPMN_SCRIPT_FORMAT_UNSUPPORTED` with stage `not planned` and its name in the message (FEEL is accepted since #498, PRE-256). Nothing is stored.
- **Example:** `scriptFormat javascript -> BPMN_SCRIPT_FORMAT_UNSUPPORTED, not planned`.
- **Test ID:** `PRE_252_a_script_that_is_not_valid_is_refused_with_the_line_of_the_script`, `PRE_252_a_script_task_needs_a_script_and_a_result_variable`, `PRE_252_a_script_in_another_language_is_refused_with_its_stage`

### PRE-253 A script cannot leave its sandbox: expressions only, at most 1,000 characters and 500 steps
- **Traces to:** `TR-SEC-01`, `TR-STD-04`, #485
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `scriptTask` bodies `java.lang.Runtime.getRuntime().exec('rm -rf /')`, `System.exit(0)`, `amount.getClass().getName()`, `load('file:///etc/passwd')`, `while (true) {}` and `amount = 0; amount`; a body of about 540 expression nodes; a body of 1,200 characters; and the body `amount >= 1000 and amount < 10000` with `amount = 5000`.
- **When:** Each definition is compiled, and the instances start.
- **Then:** Each of the first six is refused at activation, so no instance exists: the language has no host objects, no calls into Java, no assignment and no loops. The 540-node body fails the step with `SCRIPT_ERROR` (budget of 500 steps spent); the 1,200-character body is refused at activation. The last gives `true` and sets only its result variable, so `amount` is unchanged.
- **Example:** `System.exit(0) -> refused at activation`.
- **Test ID:** `PRE_253_a_script_cannot_call_host_code_or_name_a_host_class`, `PRE_253_a_script_reads_variables_and_sets_only_its_result_variable`, `PRE_253_a_script_over_its_step_budget_fails_the_step`, `PRE_253_a_script_longer_than_the_size_limit_is_refused_at_activation`

### PRE-254 A FEEL condition takes the flow it matches
- **Traces to:** `TR-STD-04`, `TR-STD-02`, #498
- **Milestone:** M3
- **Kind:** semantics
- **Given:** An exclusive gateway whose flow `tier-check__gold` has the FEEL condition `amount > 1000 and tier = "gold"` and a default flow `tier-check__standard`.
- **When:** An instance starts with `amount` 5,000 and `tier` gold; with `amount` 1,000 and gold; with `amount` 5,000 and silver.
- **Then:** The first takes `tier-check__gold`; the other two take the default. A FEEL list test (`country in ["VN", "SG"]`) and a date literal (`date("2027-01-01")`) read as in FEEL.
- **Example:** `amount = 1000` is not `> 1000`, so the default flow is taken.
- **Test ID:** `PRE_254_a_feel_condition_takes_the_flow_it_matches_and_the_default_otherwise`, `PRE_254_feel_chooses_the_branch_of_a_gold_loan_over_1000`, `PRE_254_list_membership_and_dates_read_as_in_feel`

### PRE-255 A FEEL condition follows the null and money rules of the Rule Engine
- **Traces to:** `RLE-006`, `PRE-004`, #498
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The condition `amount > 1000 and tier = "gold"` with `amount` null; `tier != "gold"` with `tier` null; `amount >= money(100000000, "VND")` with a USD amount.
- **When:** The gateway runs.
- **Then:** An ordering with null is null, so the first does not hold and the default flow is taken; `tier != "gold"` with a null tier is true (two-valued equality); `false and null` is false and `true and null` is null, which does not hold. Money compared with another currency fails the step with `CONDITION_ERROR`, never a silent false.
- **Example:** `null > 1000` is null, so the gold flow is not taken.
- **Test ID:** `PRE_255_a_null_in_a_feel_comparison_does_not_hold_and_the_default_flow_is_taken`, `PRE_255_null_follows_feel_three_valued_logic_and_only_true_holds`, `PRE_255_a_feel_condition_over_money_compares_only_within_one_currency`, `PRE_255_money_compares_within_one_currency_and_a_mismatch_is_an_error_not_false`

### PRE-256 Import accepts the FEEL URI, names any other language, and hashes one meaning once
- **Traces to:** `PRE-033`, `PRE-252`, `ADR-0026`, #498
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A `conditionExpression` and a `scriptTask` in FEEL under the DMN FEEL URI of any version; a condition in `http://www.w3.org/1999/XPath`; the FEEL condition `tier == "gold"` (v1 equality); and the same script written in FEEL and in `urn:horus-bpm:expr:v1`.
- **When:** Each file is imported and compiled.
- **Then:** The FEEL files import and the text is kept as written on export. XPath is `422 BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED` naming `http://www.w3.org/1999/XPath`. The v1 equality in FEEL is `CONDITION_INVALID` with the position and the hint that FEEL writes `=`. The FEEL and v1 scripts have one `definitionHash`, because a FEEL text is hashed in the normal form of `urn:horus-bpm:expr:v1`; a v1 text hashes as authored, so no earlier hash changes. The v1 language still refuses FEEL spelling.
- **Example:** `amount > 1000 and tier = "gold"` and `amount > 1000 and tier == 'gold'` hash the same.
- **Test ID:** `PRE_256_a_feel_condition_imports_and_a_language_other_than_the_two_is_refused_by_name`, `PRE_256_a_feel_script_and_its_v1_spelling_are_one_definition`, `PRE_256_an_invalid_feel_condition_is_refused_at_activation_with_the_position`, `PRE_256_a_language_other_than_v1_and_feel_fails_with_its_name`, `PRE_256_a_condition_in_v1_spelling_is_a_problem_in_feel_with_a_hint_and_a_position`, `PRE_256_the_same_meaning_in_either_language_has_one_normal_form`, `PRE_256_every_dmn_feel_uri_names_feel_and_nothing_else_does`, `PRE_256_the_v1_language_still_reads_its_own_spelling_and_refuses_feels`, `PRE_256_the_worked_feel_example_reads_and_compiles`

### PRE-257 A script budget is set through the expression API and holds in either language
- **Traces to:** `TR-SEC-01`, `PRE-253`, #517
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `ExpressionLanguages.withBudget(language, stepBudget, timeoutMillis)` in `bpm-expression`, a script of 41 terms (`1 + 1 + ...`), and the Process Engine core.
- **When:** The script runs with a budget of 20 steps and with 500; the kernel contract rule is checked.
- **Then:** With 500 it gives 41; with 20 it fails with `EVALUATION_BUDGET_EXCEEDED`, in v1 and in FEEL; a bound out of range or an unknown language is refused. No class of `core` depends on `io.github.dhluong90.bpm.expr.internal`: the script step uses the factory, and the earlier exemption for `Evaluator`, `EvaluationLimits` and `RegexLimits` is gone.
- **Example:** 41 terms need about 83 nodes, over a budget of 20.
- **Test ID:** `PRE_257_a_budgeted_language_stops_a_script_past_its_step_budget_in_both_languages`, `PRE_257_a_bound_out_of_range_or_an_unknown_language_is_refused`, `PRE_257_core_reaches_the_expression_library_only_through_its_api_spi_and_languages`
### PRE-260 An embedded sub-process runs from its own start to its own end inside its own scope, and the parent continues only after that end
- **Traces to:** `TR-STD-29`, `PRE-044`, #486
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `customer-onboarding` with a `subProcess` `onboard` between `start` and the service task `open-account`. Inside `onboard`: a script task `score` (`amount * 2` into `risk`), a `parallelGateway` that forks a service task `bureau` and a service task `fraud`, and a join. An instance starts with `amount = 1500000`.
- **When:** The instance starts; `bureau` completes first; then `fraud` completes.
- **Then:** Entering `onboard` opens a `SCOPE` token under the root scope token, and the first flow token runs under it. `score` reads the parent's `amount` and writes `risk` to the instance variables, which stay readable after the sub-process ends. The tokens on `bureau` and `fraud` carry the scope token of `onboard`. When `bureau` completes first, its token waits at the join and `open-account` has no token; the join counts only the tokens of the scope of `onboard` (`PRE-044`). When `fraud` completes, the scope token is `COMPLETED`, no token under it is live, and one flow token continues at `open-account` in the root scope. History records `SCOPE_ENTERED` for `onboard`. The instance completes when `open-account` completes.
- **Example:** `amount 1500000 -> risk 3000000, open-account starts after the inner end`.
- **Test ID:** `PRE_260_the_parent_continues_only_after_the_inner_end_and_the_scope_closes`

### PRE-261 A cancel while the instance is inside a sub-process cancels every token, task and job in every scope
- **Traces to:** `TR-STD-29`, `PRE-041`, #486
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `customer-onboarding` with `bureau` and `fraud` waiting for their results inside `onboard`.
- **When:** The instance is cancelled with the reason `customer withdrew`.
- **Then:** The instance is `CANCELLED`; the scope token of `onboard` and every flow token under it are `CANCELLED`; each of the two calls in flight is cancelled by its job id; no token of any scope is live. A later result for either call is discarded (`PRE-041`).
- **Example:** `cancel with bureau and fraud in flight -> two jobs cancelled, scope CANCELLED`.
- **Test ID:** `PRE_261_a_cancel_inside_the_sub_process_cancels_every_inner_token_and_job`

### PRE-262 A failure with `onFailure: compensate` inside a sub-process undoes only the steps of that sub-process
- **Traces to:** `TR-COMP-01`, `PRE-044`, #486
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `loan-booking-embedded`: `register-loan` (undone by `cancel-loan`) in the root scope, then a `subProcess` `disburse` with `reserve-funds` (undone by `release-funds`), `open-account` (undone by `close-account`) and `notify-partner` with `onFailure: compensate`, which the partner refuses. A second process `fund` with `hold` (undone by `release-hold`) and `debit` (`onFailure: compensate`) inside a sub-process, after `open` (undone by `close-account`) in the root scope.
- **When:** `notify-partner` fails for good; and, in the second process, `debit` answers the business error `INSUFFICIENT_FUNDS`.
- **Then:** Each undo is pushed under the scope token of the scope its step ran in. The compensation started is that of the scope token of the sub-process, never of the root: the undos of `open-account` and `reserve-funds` run newest first, `cancel-loan` never runs and its entry stays `PENDING`, and the sub-process scope token ends `COMPENSATED`. The instance ends `FAILED`. A completed sub-process's entries are walked by a later undo of the enclosing scope, with the sub-processes nested in it (`PRE-289`).
- **Example:** `disburse fails -> close-account, release-funds run; cancel-loan does not`.
- **Test ID:** `PRE_262_an_undo_started_inside_the_sub_process_walks_that_scope_and_never_the_enclosing_one` (`SubProcessScopeTest`), `PRE_262_an_embedded_sub_process_is_a_scope_whose_failure_undoes_only_its_own_steps` (`SagaCompensationTest`)

### PRE-263 A sub-process imports and exports with its own steps; a scope that is not well formed, or an element not yet supported, is refused
- **Traces to:** `TR-STD-04`, `PRE-033`, #486
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `customer-onboarding` as above; the same file with `triggeredByEvent="true"` on `onboard`; the same file with a flow from `fraud` to the root `end`; the same file without the end event of `onboard`.
- **When:** Each file is imported and compiled.
- **Then:** The first reads with the steps and flows of `onboard` nested under it, and its canonical form holds a step of type `subProcess` with its own `steps`; the BPMN export reads back to the same canonical form. The second is refused as `422 BPMN_UNSUPPORTED_ELEMENT` for `subProcess with triggeredByEvent`, stage `S1`. The third is refused at activation with `FLOW_CROSSES_SAGA` naming the flow, because a flow never crosses the boundary of a scope. The fourth is refused with `NO_END_EVENT` naming `onboard`.
- **Example:** `flow fraud -> end across the boundary -> FLOW_CROSSES_SAGA`.
- **Test ID:** `PRE_263_a_sub_process_reads_with_its_steps_and_exports_back_to_the_same_definition`, `PRE_263_an_event_sub_process_with_a_flow_in_or_out_is_refused_and_a_flow_across_the_boundary_is_refused`, `PRE_263_a_sub_process_needs_one_start_event_and_an_end_event_of_its_own` (`SubProcessScopeTest`)

### PRE-280 An interrupting timer boundary cancels the task it sits on and runs its own flow
- **Traces to:** `TR-STD-04`, `PRE-006`, #489
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `claim-flow` with a user task `review` for role `CLAIM_OFFICER` and a boundary timer `overdue` of `PT4H` that interrupts, started at 03:00 UTC; the boundary's flow leads to the service task `remind` (handler `send-reminder`).
- **When:** The instance starts; then the four hours pass and the timer job falls due.
- **Then:** At start the task is open and the boundary holds one `TIMER` job due at 07:00 UTC, the job kind and lease of any timer; the boundary's token waits in `WAITING_TIMER` under the same scope as the task. When the job fires, the token on `review` and its work item are `CANCELLED`, `remind` runs, and history records `BOUNDARY_FIRED` with the boundary id, the step it sits on and `interrupting: true`. A completion of the cancelled task is refused. The instance completes when the reminder's flow ends. A boundary on a call activity cancels the running child with the reason `parent step cancelled`, and the child's later end finds no waiting token and is dropped.
- **Example:** `overdue` fires at 07:00 -> task `CANCELLED`, `remind` runs, history `BOUNDARY_FIRED`.
- **Test ID:** `PRE_280_an_interrupting_timer_boundary_cancels_the_task_and_runs_its_own_flow` (`BoundaryAndThrowEventsTest`), `PRE_280_a_timer_boundary_on_a_call_activity_cancels_the_child_and_runs_its_flow` (`BoundaryEventsPostgresIT`)

### PRE-281 A non-interrupting timer boundary runs its flow once and leaves the task open; a timer never outlives its task
- **Traces to:** `TR-STD-04`, #489
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The same `claim-flow` with `cancelActivity="false"` on `overdue`.
- **When:** The timer fires; then the officer completes the task. In a second run the officer completes the task before the timer fires.
- **Then:** On firing the task stays `WAITING_USER`, no work item is cancelled, `remind` runs once and no second timer is scheduled; the instance goes on waiting for the officer and completes when both the task and the boundary's flow have ended. When the task completes before the timer, the timer job is cancelled in the same transaction, the boundary has no live token, and a late firing of that job is refused as stale.
- **Example:** `overdue` fires with the task open -> reminder sent once, task still open.
- **Test ID:** `PRE_281_a_non_interrupting_timer_boundary_runs_its_flow_once_and_leaves_the_task_open`, `PRE_281_the_timer_is_cancelled_when_the_task_completes_before_it_fires` (`BoundaryAndThrowEventsTest`)

### PRE-282 An error boundary takes a business error by its code before it becomes an incident; a code no boundary matches still opens one
- **Traces to:** `TR-STD-04`, `PRE-040`, #489
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A debit `post-debit` with an error boundary `no-funds` for the error code `INSUFFICIENT_FUNDS` and a boundary `any-error` that names no error and so catches every code; the same debit without `any-error`.
- **When:** The handler answers the business error `INSUFFICIENT_FUNDS`, then `ACCOUNT_FROZEN`.
- **Then:** With both boundaries, `INSUFFICIENT_FUNDS` takes `no-funds` (the boundary that names the code wins over the catch-all), the step's token is `CANCELLED` and no incident opens; `ACCOUNT_FROZEN` takes `any-error`. Without the catch-all, `ACCOUNT_FROZEN` leaves the token on `debit` and opens the incident `UNHANDLED_BUSINESS_ERROR` with the code, as before this release. A failed call activity matches with the code `CHILD_FAILED` or `CHILD_CANCELLED`. History records `ERROR_THROWN` with the code and, when caught, `caughtBy`.
- **Example:** `INSUFFICIENT_FUNDS` -> boundary `no-funds`, 0 incidents; `ACCOUNT_FROZEN` with no catch-all -> 1 incident.
- **Test ID:** `PRE_282_an_error_boundary_takes_the_business_error_with_its_code_and_no_incident_opens`, `PRE_282_an_error_code_no_boundary_matches_opens_an_incident_as_before` (`BoundaryAndThrowEventsTest`), `PRE_282_a_business_error_with_a_matching_boundary_opens_no_incident`, `PRE_282_a_business_error_with_another_code_still_opens_an_incident` (`BoundaryEventsPostgresIT`)

### PRE-283 An error end event is caught by the nearest matching boundary around it; one nothing catches opens an incident on the end event
- **Traces to:** `TR-STD-04`, #493
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A disbursement sub-process `disburse` whose check ends in an error end event for `LIMIT_EXCEEDED`; one file with a boundary on `disburse` for that code, and one whose boundary is for `INSUFFICIENT_FUNDS`.
- **When:** The check completes and the token reaches the error end event.
- **Then:** With the matching boundary, the scope token of `disburse` and everything under it is `CANCELLED`, the boundary's flow runs in the enclosing scope and no incident opens; history records `ERROR_THROWN` with `caughtBy`. With the other boundary, the token stays on the end event, an incident `UNHANDLED_BUSINESS_ERROR` names the code, and the sub-process is not cancelled. An error that is not caught inside a sub-process is looked for on the sub-process, then on the scope around it, up to the process. An error throw is an end event only: an intermediate throw with an error definition is not BPMN and is refused at import.
- **Example:** `LIMIT_EXCEEDED` thrown in `disburse` -> boundary `limit` runs `notify-limit`.
- **Test ID:** `PRE_283_an_error_end_inside_a_sub_process_is_caught_by_the_boundary_on_the_sub_process`, `PRE_283_an_error_end_that_no_boundary_catches_opens_an_incident_on_the_end_event` (`BoundaryAndThrowEventsTest`)

### PRE-284 An escalation is caught by an interrupting or non-interrupting boundary; one nothing catches is recorded and the flow goes on
- **Traces to:** `TR-STD-04`, #489, #493
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A KYC sub-process `kyc` that throws the escalation `AML_HIT` and then verifies the customer; three files: a boundary for `AML_HIT` that does not interrupt, one that interrupts, and none.
- **When:** The instance starts and the throw event is reached.
- **Then:** Non-interrupting: the AML review runs beside the sub-process, whose own flow goes on to the identity check. Interrupting: the sub-process scope is `CANCELLED`, the identity check never runs, and only the AML review runs. No boundary: the throw is recorded in history as `ESCALATION_THROWN` without `caughtBy`, the flow goes on to the identity check, and no incident opens. An escalation end event ends its token whether it was caught or not.
- **Example:** `AML_HIT` with no boundary -> `ESCALATION_THROWN`, identity check runs.
- **Test ID:** `PRE_284_a_non_interrupting_escalation_boundary_runs_beside_the_sub_process_which_goes_on`, `PRE_284_an_interrupting_escalation_boundary_cancels_the_sub_process`, `PRE_284_an_escalation_nothing_catches_is_recorded_and_the_flow_goes_on` (`BoundaryAndThrowEventsTest`)

### PRE-285 A terminate end event cancels every other token of its scope: the instance at the top level, the sub-process inside one
- **Traces to:** `TR-STD-04`, #493
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A screening with a service task `screen` and a user task `manual-check` in parallel, where `screen` ends in a terminate end event; and an onboarding sub-process with a bureau call that ends in a terminate and a review task in parallel, followed by `open-account` in the parent.
- **When:** `screen` completes and the terminate is reached; in the second file the bureau call completes.
- **Then:** At the top level the open work item of `manual-check` is cancelled, no token is live, the instance is `COMPLETED` and history records `TERMINATED` with the scope and the number of tokens cancelled. Inside the sub-process the review is cancelled and the sub-process completes, and the parent goes on with `open-account`: a terminate ends its own scope only.
- **Example:** `screen` ends in a terminate -> `manual-check` cancelled, instance `COMPLETED`.
- **Test ID:** `PRE_285_a_terminate_end_cancels_the_other_branches_and_the_instance_completes`, `PRE_285_a_terminate_end_inside_a_sub_process_ends_only_that_sub_process` (`BoundaryAndThrowEventsTest`)

### PRE-286 A link throw jumps to the link catch of its name in the same scope; a throw with no catch is refused at import with its element id
- **Traces to:** `TR-STD-04`, #493
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A card registration where the link throw `to-fees` is named `fees`, jumping over the optional step to the link catch `from-fees`; and the same file whose catch is named `charges`.
- **When:** The registration completes and the token reaches the throw; the second file is imported.
- **Then:** The token moves to `from-fees` with no sequence flow, the fee is charged next, the optional step never runs, and history records `LINK_JUMPED` with the name and the catch. The second file is refused as `422 VALIDATION_FAILED` with the problem `LINK_CATCH_MISSING` and the element id `to-fees`. A catch named twice in one scope is `LINK_CATCH_DUPLICATE`, and a link whose throw and catch are in different scopes is not a pair.
- **Example:** `fees` thrown -> `charge-fee` runs; catch `charges` -> `LINK_CATCH_MISSING` on `to-fees`.
- **Test ID:** `PRE_286_a_link_throw_jumps_to_the_link_catch_of_the_same_name`, `PRE_286_a_link_throw_without_a_catch_of_its_name_is_refused_at_import_with_its_element_id` (`BoundaryAndThrowEventsTest`)

### PRE-287 Boundary, error, escalation, terminate and link events export and read back to the same definition
- **Traces to:** `TR-STD-04`, #489, #493
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The files of `PRE-280` to `PRE-286`.
- **When:** Each is read, exported as BPMN and read again.
- **Then:** The canonical form, which the version hash is made of, is equal for the original and the round trip; the export carries the root `error` and `escalation` elements the definitions refer to.
- **Example:** `over-limit` -> `<bpmn:error errorCode="REFUND_LIMIT_EXCEEDED"/>` and an `errorRef`.
- **Test ID:** `PRE_287_the_new_events_export_and_read_back_to_the_same_definition` (`BoundaryAndThrowEventsTest`)
### PRE-290 An interrupting message boundary cancels the task it sits on when the message of its key arrives; the subscription never outlives the task
- **Traces to:** `TR-STD-04`, `PRE-230`, #490
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `payment-review` with a user task `review` for role `PAYMENT_OFFICER` and an interrupting boundary `cancelled-by-customer` on the message `payment-cancelled` with `horus:correlationKey="paymentId"`; two instances, with `paymentId` `PAY-9001` and `PAY-9002`.
- **When:** The message `payment-cancelled` with key `PAY-9001` is correlated; separately, an instance's task completes before any message.
- **Then:** At start each instance holds one `MESSAGE` subscription of its own key and the boundary's token waits in `WAITING_EVENT`. The correlation finds the boundary's subscription, cancels the task and its work item, deletes the subscription, runs the boundary's flow, and records `BOUNDARY_FIRED` with `trigger` `MESSAGE` and the message name. `PAY-9002` is untouched. A second delivery to the same subscription is refused. When the task completes first, the boundary token is cancelled and its subscription deleted in the same transaction.
- **Example:** `payment-cancelled` for `PAY-9001` -> `review` `CANCELLED`, `withdrawn` reached, one subscription left.
- **Test ID:** `PRE_290_an_interrupting_message_boundary_cancels_the_task_when_the_message_of_its_key_arrives`, `PRE_290_the_subscription_of_a_boundary_is_removed_when_the_task_completes_first` (`EventBoundariesAndGatewayTest`), `PRE_290_a_message_correlated_into_a_boundary_cancels_the_task_and_removes_the_subscription` (`EventBoundariesAndGatewayPostgresIT`)

### PRE-291 A non-interrupting message or signal boundary leaves the task open and stays armed; an interrupting signal boundary cancels the task
- **Traces to:** `TR-STD-04`, `PRE-271`, #490
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The review of `PRE-290` with `cancelActivity="false"` on the message boundary; and the same review with an interrupting boundary on the signal `account-frozen`.
- **When:** The message arrives twice for the same key; then the task completes. In the second file the signal is delivered.
- **Then:** Each message runs the boundary's flow once and records `BOUNDARY_FIRED` with `interrupting: false`; the task stays `WAITING_USER`, the subscription is not deleted between them, and it is deleted when the task completes. The signal boundary cancels the task, deletes its subscription and records `trigger` `SIGNAL` with the signal name.
- **Example:** two `payment-cancelled` messages -> two runs of the flow, task still open.
- **Test ID:** `PRE_291_a_non_interrupting_message_or_signal_boundary_leaves_the_task_open_and_stays_armed` (`EventBoundariesAndGatewayTest`)

### PRE-292 A conditional boundary fires once when a variable change makes its condition true, under the instance lock
- **Traces to:** `TR-STD-04`, `PRE-274`, #490
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A review that a boundary on `risk.score >= 80` interrupts, in parallel with a scoring task whose result variable is `risk`; the same file with `cancelActivity="false"`.
- **When:** The scoring task completes with a score of 60, in another instance with a score of 90, and in the non-interrupting file a later task completes.
- **Then:** At 60 the condition is false, the review stays open and nothing fails. At 90 the boundary fires in the same transaction as the completion: the review is cancelled with its work item and `BOUNDARY_FIRED` records `trigger` `CONDITION`. The non-interrupting boundary fires once, leaves the review open, and a later change of variables does not fire it again. A condition already true when the step is entered fires on the next change, not on arrival.
- **Example:** `risk.score` 90 -> review cancelled, `escalated` reached.
- **Test ID:** `PRE_292_a_conditional_boundary_fires_once_when_a_variable_change_makes_its_condition_true`, `PRE_292_a_non_interrupting_conditional_boundary_fires_once_and_leaves_the_task_open` (`EventBoundariesAndGatewayTest`)

### PRE-293 An event-based gateway runs the first event that arrives and cancels the others with their subscriptions and timers
- **Traces to:** `TR-STD-04`, `PRE-006`, `PRE-230`, #495
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `payment-wait`: after the gateway `gw`, the message `payment-received` (key `orderId`), a timer of two hours and the signal `account-frozen`, each to its own end event; a second file with a conditional catch and a receive task.
- **When:** The message arrives first; separately the timer fires first; the instance is cancelled while it waits; and in the second file the condition is true on arrival.
- **Then:** At the gateway one token waits on each catch, with one subscription for the message, one for the signal and one timer job. When the message arrives, the timer job and the signal subscription are removed and the settled branch runs; a timer job that is still delivered is refused. When the timer fires, the subscriptions go. History records `EVENT_GATEWAY_RESOLVED` with the winner and the cancelled catches. A cancelled instance loses every subscription and job. A condition true on arrival wins before the other catches wait and creates no subscription.
- **Example:** `payment-received` for `ORD-20481` at 02:30 -> timer cancelled, `settled` reached.
- **Test ID:** `PRE_293_an_event_based_gateway_runs_the_first_event_and_cancels_the_others_with_their_subscriptions_and_timers`, `PRE_293_cancelling_an_instance_that_waits_at_the_gateway_removes_every_subscription_and_timer`, `PRE_293_a_condition_that_is_true_on_arrival_wins_at_once_and_the_other_catches_never_wait` (`EventBoundariesAndGatewayTest`), `PRE_293_cancelling_an_instance_that_waits_at_the_gateway_removes_every_subscription_and_timer` (`EventBoundariesAndGatewayPostgresIT`)

### PRE-294 Two events that arrive at the same moment on two replicas run exactly one branch
- **Traces to:** `TR-STD-04`, `TR-ASY-02`, #495
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `payment-wait` with messages `payment-received` and `payment-reversed` and a two-hour timer; two replicas on one database; six rounds of one instance each.
- **When:** In each round the two messages are correlated at the same moment, one on each replica (a barrier releases both); in a second test the message and the timer poller of the other replica race after the two hours have passed.
- **Then:** Exactly one delivery succeeds and the other is refused as not correlated; exactly one end token is `COMPLETED`, the one of the winner; one `EVENT_GATEWAY_RESOLVED` entry is written; no subscription and no due job is left.
- **Example:** `payment-received` and `payment-reversed` for `ORD-70` together -> one of `settled` or `refunded`, never both.
- **Test ID:** `PRE_294_two_messages_that_arrive_together_on_two_replicas_run_exactly_one_branch`, `PRE_294_a_message_and_the_timer_that_fall_due_together_run_exactly_one_branch` (`EventBoundariesAndGatewayPostgresIT`)

### PRE-295 The new boundaries and the gateway export and read back to the same definition; a malformed one is refused with its reason
- **Traces to:** `TR-STD-04`, `TR-STD-05`, #490, #495
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The files of `PRE-290` to `PRE-293`; a message boundary without `horus:correlationKey`; a gateway that leads to a task, one whose catch event has a second incoming flow, and one with `eventGatewayType="Parallel"` and no `instantiate`.
- **When:** Each is read, exported and read again, or imported.
- **Then:** The canonical form, which the version hash is made of, is equal for the original and the round trip. A message boundary without a key is refused (`CORRELATION_KEY_REQUIRED`); a gateway to a task or to a shared catch event is refused at compile; a parallel gateway that does not instantiate is refused at compile (`EVENT_GATEWAY_PARALLEL_INSTANTIATE`). The cockpit draws the new boundaries and the gateway as known symbols and the timeline names the entries, read only.
- **Example:** `eventGatewayType="Parallel"` with no `instantiate="true"` -> `422 VALIDATION_FAILED`, `EVENT_GATEWAY_PARALLEL_INSTANTIATE`.
- **Test ID:** `PRE_295_the_new_boundaries_and_the_gateway_export_and_read_back_to_the_same_definition`, `PRE_295_a_message_boundary_needs_its_key_and_a_gateway_must_lead_to_catch_events_that_only_it_enters`, `PRE_295_a_parallel_event_based_gateway_that_does_not_instantiate_is_refused_by_the_compiler` (`EventBoundariesAndGatewayTest`), `PRE_295_the_new_boundaries_and_the_gateway_are_known_symbols_that_say_whether_they_interrupt` (`eventBoundaries.test.ts`)

### PRE-270 A broadcast wakes every waiter of its own tenant, by job, and never a waiter of another tenant
- **Traces to:** `TR-TEN-01`, `TR-STD-11`, `TR-STD-14`, #492
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Three `rate-watcher` instances of `bank-a` and one of `bank-b` wait at the signal catch `wait-rate-card` for `rate-card-updated`; a `rate-publisher` instance of `bank-a` reaches a signal throw of the same name; another `rate-watcher` of `bank-a` is cancelled while it waits.
- **When:** The publisher throws, then the due jobs fire; a broadcast follows the cancel.
- **Then:**
  - The throw is a write in the publisher's transaction: the publisher continues and ends, and three `SIGNAL_DELIVERY` jobs of `bank-a` are due while the three waiters still wait. The waiters are not locked by the thrower. The publisher's history has `SIGNAL_THROWN` with `waiters` 3.
  - When the jobs fire, each waiter continues to its repricing task in a transaction of its own and its subscription is gone. The `bank-b` waiter has no job, no task and still waits.
  - A waiter cancelled before the broadcast has no subscription and is not counted.
- **Example:** `bank-a throws rate-card-updated -> 3 jobs, 3 repricing tasks; bank-b waiter unchanged`.
- **Test ID:** `PRE_270_a_throw_wakes_every_waiter_of_its_tenant_by_job_and_never_a_waiter_of_another_tenant`, `PRE_270_a_cancelled_waiter_leaves_no_subscription_and_is_not_woken` (`SignalEventsPostgresIT`)

### PRE-271 A signal catch waits on one subscription and is woken once, by its own subscription only
- **Traces to:** `TR-STD-11`, `TR-STD-13`, #492
- **Milestone:** M3
- **Kind:** semantics
- **Given:** An instance at `wait-rate-card`, with the subscription `S1` the kernel asked the shell to store.
- **When:** A delivery names another subscription; then one names `S1`; then `S1` is delivered again.
- **Then:** The first is `STALE_ATTEMPT` and changes nothing. The second moves the token to the next step and asks the shell to delete `S1`. The third is `INVALID_STATE`: a second delivery never moves the token again.
- **Example:** `deliver(S1) twice -> second INVALID_STATE`.
- **Test ID:** `PRE_271_a_signal_catch_waits_on_one_subscription_and_a_delivery_moves_it_on`, `PRE_271_a_delivery_for_another_subscription_or_a_second_delivery_is_refused` (`SignalAndConditionalEventsTest`)

### PRE-272 A signal throw or signal end event broadcasts once and does not wait
- **Traces to:** `TR-STD-11`, #492
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `rate-publisher` with an intermediate signal throw `announce` before a task, and `rate-closer` whose end event is a signal end.
- **When:** Each instance starts.
- **Then:** Each asks the shell for exactly one broadcast of its signal name. The publisher's token passes to the task at once; the closer's instance completes. Neither carries data from its variables into the signal.
- **Example:** `announce -> BroadcastSignal(rate-card-updated), token at archive`.
- **Test ID:** `PRE_272_a_signal_throw_broadcasts_once_and_the_thrower_goes_on`, `PRE_272_a_signal_end_broadcasts_and_ends_the_instance` (`SignalAndConditionalEventsTest`)

### PRE-273 A broadcast needs its permission and an idempotency key; a retry wakes and starts nobody twice
- **Traces to:** `TR-IDEM-01`, `TR-TEN-01`, `TR-SEC-04`, #492
- **Milestone:** M3
- **Kind:** control
- **Given:** `bank-b` has a waiting `rate-watcher`, `bank-a` has one too and an active `repricing-on-signal` whose start event is a signal start for `rate-card-updated`.
- **When:** `bank-b`'s operator broadcasts with the key `K1` and again with `K1`; a caller without `processes:signal:broadcast` broadcasts; a caller with it sends no key; `bank-a` broadcasts with `K1`, twice, and `bank-b` once more with `K2`.
- **Then:**
  - The `bank-b` retry returns the first answer and writes no second signal and no second job; only the `bank-b` waiter is woken, the `bank-a` waiter still waits.
  - The caller without the permission is `403 PERMISSION_DENIED`; the caller without a key is `428 PRECONDITION_REQUIRED`; neither writes a signal.
  - `bank-a`'s broadcast starts one `repricing-on-signal` instance on the version effective now, its retry starts none, and `bank-b`'s broadcast starts none. The tenant is never read from the request.
- **Example:** `bank-a K1 twice -> 1 instance, same signalId`.
- **Test ID:** `PRE_273_a_broadcast_from_the_api_reaches_its_own_tenant_only_and_a_retry_wakes_nobody_twice`, `PRE_273_a_broadcast_needs_its_permission_and_an_idempotency_key`, `PRE_273_a_signal_start_event_starts_one_instance_for_a_broadcast_of_its_tenant_and_a_retry_starts_none` (`SignalEventsPostgresIT`)

### PRE-274 A conditional catch passes at once when true, otherwise waits for a change of the variables, and never fails the step that changed them
- **Traces to:** `TR-STD-11`, #492
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A conditional catch `limit > 0` with `limit` 5000 on arrival; a conditional catch `decision.approved == true` after the task `set-limit`; a conditional start `amount >= 1000000`.
- **When:** The first instance arrives; the task is completed with `approved` false, then true; the conditional start receives `amount` 500000.
- **Then:** The first passes at once and records `CONDITION_MET` with `onArrival` true. The second stays waiting after `false`, and moves on in the completion transaction that makes the condition true; a condition that cannot be evaluated yet is not true and never fails the completion. The start is refused with `START_CONDITION_FALSE` and stores nothing.
- **Example:** `approved false -> waits; approved true -> issue-card`.
- **Test ID:** `PRE_274_a_conditional_catch_whose_condition_is_true_on_arrival_passes_at_once`, `PRE_274_a_conditional_catch_waits_until_a_completed_task_makes_its_condition_true`, `PRE_274_a_conditional_catch_stays_while_the_condition_is_false_and_never_fails_the_step_that_changes_variables`, `PRE_274_a_conditional_start_whose_condition_is_false_is_refused` (`SignalAndConditionalEventsTest`)

### PRE-275 Signal and conditional events import with their signal names, are refused when broken, and export back to the same definition
- **Traces to:** `TR-STD-11`, `TR-STD-05`, #492
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A file with a `signal` element named `rate-card-updated` declared after the process, a signal catch, a signal end and a conditional catch; a file whose `signalRef` names no signal and whose condition does not parse; a signal named `rate card!`.
- **When:** Each is imported, and the first is exported and imported again.
- **Then:** The first reads with the signal name on both signal events. The second is `422 VALIDATION_FAILED` with `SIGNAL_REF_INVALID` and `CONDITION_INVALID`; the third with `SIGNAL_NAME_INVALID`; nothing is stored. The export reads back to the same canonical definition.
- **Example:** `signalRef Signal_missing -> SIGNAL_REF_INVALID`.
- **Test ID:** `PRE_275_a_signal_event_carries_the_name_of_the_signal_it_refers_to_wherever_the_signal_is_declared`, `PRE_275_a_signal_reference_to_no_signal_and_a_broken_condition_are_refused_by_name`, `PRE_275_a_signal_name_outside_the_allowed_characters_is_refused`, `PRE_275_the_export_of_signal_and_conditional_events_reads_back_to_the_same_definition`, `PRE_275_the_worked_example_reads_and_compiles` (`SignalAndConditionalEventsImportTest`), `PRE_275_signal_and_conditional_events_are_known_symbols_not_generic_nodes`, `PRE_275_the_timeline_names_the_signal_and_condition_entries` (cockpit `signalEvents.test.ts`)
### PRE-230 A message continues exactly the instance whose key it carries
- **Traces to:** `TR-STD-04`, #491
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `order-payment` with a receive task `wait-for-payment` for message `payment-received`, correlated by the variable `orderId`, and `<horus:output name="paymentRef" from="reference"/>`; instances for orders `ORD-20481` and `ORD-20482` are waiting there.
- **When:** The host calls `POST /messages:correlate` with `messageName` `payment-received`, `correlationKey` `ORD-20481` and payload `reference: TT-77120, ignored: x`.
- **Then:** The answer is `DELIVERED` with the instance of `ORD-20481` and node `wait-for-payment`. That instance completes with `paymentRef = TT-77120` and no variable `ignored`; its subscription row is gone. The instance of `ORD-20482` still waits with its subscription.
- **Example:** ``payment-received` for `ORD-20481` -> only that order continues`
- **Test ID:** `PRE_230_a_message_continues_exactly_the_instance_with_its_key` (`MessageCorrelationPostgresIT`)

### PRE-231 A message that matches two waiting instances is a conflict and nothing runs
- **Traces to:** `TR-STD-13`, #491
- **Milestone:** M3
- **Kind:** control
- **Given:** Two instances of `order-payment` wait at `wait-for-payment` with the same `orderId` `ORD-30001`.
- **When:** `payment-received` for `ORD-30001` arrives.
- **Then:** The answer is `409 MESSAGE_CORRELATION_AMBIGUOUS`, which does not echo the key. Both instances stay `RUNNING`, no `MESSAGE_RECEIVED` is written, and no idempotency record is kept.
- **Example:** `two orders with `ORD-30001` -> 409, both still waiting`
- **Test ID:** `PRE_231_two_waiting_instances_with_the_same_key_are_a_conflict_and_nothing_runs` (`MessageCorrelationPostgresIT`)

### PRE-232 A message with no waiting instance is refused with a problem code and nothing is stored
- **Traces to:** `TR-STD-13`, `TR-TEN-01`, #491
- **Milestone:** M3
- **Kind:** control
- **Given:** One order `ORD-40001` waits at `wait-for-payment`, and no process starts on `payment-received`.
- **When:** `payment-received` for `ORD-99999` arrives.
- **Then:** The answer is `422 MESSAGE_NOT_CORRELATED`. No history row, instance or idempotency record is written, so a retry after the receiver starts is a fresh call. Messages are not buffered.
- **Example:** `key `ORD-99999` -> 422 MESSAGE_NOT_CORRELATED`
- **Test ID:** `PRE_232_a_message_with_no_waiting_instance_is_refused_and_nothing_is_stored` (`MessageCorrelationPostgresIT`)

### PRE-233 A message never reaches an instance of another tenant
- **Traces to:** `TR-TEN-01`, #491
- **Milestone:** M3
- **Kind:** control
- **Given:** `bank-a` has an order `ORD-50001` waiting for `payment-received`.
- **When:** A caller of `bank-b` sends `payment-received` for `ORD-50001`.
- **Then:** The answer is `422 MESSAGE_NOT_CORRELATED`. The tenant is the resolver's, never read from the body; the `bank-a` instance is unchanged and no `MESSAGE_RECEIVED` is written.
- **Example:** ``bank-b` sends `ORD-50001` -> not correlated`
- **Test ID:** `PRE_233_a_message_never_reaches_an_instance_of_another_tenant` (`MessageCorrelationPostgresIT`)

### PRE-234 Delivering the same message twice with one Idempotency-Key runs it once
- **Traces to:** `TR-STD-13`, #491
- **Milestone:** M3
- **Kind:** control
- **Given:** Order `ORD-60001` waits at `wait-for-payment`; `payment-received` was delivered with `Idempotency-Key` `pay-5`.
- **When:** The same request is sent again with `pay-5`; then the same key with another payload; then the same message with the new key `pay-6`.
- **Then:** The first repeat returns the first answer, and `MESSAGE_RECEIVED` was written once. The other payload is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. The new key finds nothing waiting and is `422 MESSAGE_NOT_CORRELATED`.
- **Example:** ``pay-5` twice -> one delivery`
- **Test ID:** `PRE_234_delivering_the_same_message_twice_with_one_key_runs_it_once` (`MessageCorrelationPostgresIT`)

### PRE-235 A send step hands its message to the host once, with only the named values and a masked key
- **Traces to:** `TR-STD-12`, `TR-SEC-05`, #491
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `order-payment` has a send task `request-payment` for `payment-requested`, key variable `orderId`, and `<horus:input name="amount" value="${instance.amount}"/>`; the host registers a `MessageAdapter`.
- **When:** An instance starts with `orderId` `ORD-70001` and `amount` `1250000`; the same state is run again after a rollback.
- **Then:** The adapter receives one message of tenant `bank-a`, name `payment-requested`, key `ORD-70001`, payload `amount: 1250000` only, and a 26-character message id. A repeat from the same state carries the same id, so the host's outbox keeps one row. History keeps the key only as the `InputMasker` makes it, never `ORD-70001`.
- **Example:** `send once, same id on retry`
- **Test ID:** `PRE_235_a_send_step_hands_its_message_to_the_host_once_with_only_the_named_values_and_a_masked_key` (`MessageCorrelationPostgresIT`), `PRE_235_a_retried_send_step_hands_over_the_same_message_id` (`MessageElementsImportTest`)

### PRE-236 A message starts the process with a message start event
- **Traces to:** `TR-STD-04`, #491
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `order-intake` whose start event is a message start event for `order-submitted`.
- **When:** `order-submitted` arrives for key `ORD-80001` with payload `customer: C-778`, twice with different Idempotency-Keys; then a caller of `bank-b` sends it.
- **Then:** The first answer is `STARTED`: one instance with business key `ORD-80001` and the payload as variables. The second returns the same instance and creates no other. `bank-b` has no such process and is `422 MESSAGE_NOT_CORRELATED`.
- **Example:** ``order-submitted` + `ORD-80001` -> one instance`
- **Test ID:** `PRE_236_a_message_starts_the_process_with_a_message_start_event_and_the_same_key_finds_the_same_instance` (`MessageCorrelationPostgresIT`)

### PRE-237 Message elements import and export to the same definition; a missing message or key is refused with its code
- **Traces to:** `TR-STD-04`, `TR-STD-10`, #491
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The worked example `order-payment.bpmn` (send task, receive task, message end event); a receive task naming a message the file does not declare; a receive task with no `horus:correlationKey`.
- **When:** Each file is imported; the example is exported and imported again.
- **Then:** The example reads, compiles, and exports to a file that gives the same canonical definition. The undeclared message is `422 VALIDATION_FAILED` with `MESSAGE_REF_UNRESOLVED`; the missing key is `CORRELATION_KEY_REQUIRED`. `sendTask`, `receiveTask` and `message` are no longer in the refusal list.
- **Example:** `receive task without a key -> CORRELATION_KEY_REQUIRED`
- **Test ID:** `PRE_237_the_worked_example_reads_exports_to_the_same_definition_and_compiles`, `PRE_237_a_message_that_is_not_declared_or_a_waiting_step_without_a_key_is_refused_with_its_code` (`MessageElementsImportTest`)
### PRE-337 A parallel multi-instance step opens every repetition at once and the flow continues once, with the output in item order
- **Traces to:** `TR-STD-29`, `PRE-044`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `loan-cosigning`: a user task `step` over the list `cosigners` (Lan, Minh, Huong, Nam), element variable `cosigner`, output collection `verdicts` taken from `verdict.decision`, then the service task `book`. A second process screens the owners Tran Van An, Le Thi Binh and Pham Quoc Cuong with a sequential service task that passes the owner as input. A third runs a sub-process per branch HCM-01 and HN-02.
- **When:** The parallel loan starts and the four tasks complete out of order (Huong, Lan, Nam, then Minh). The sequential screening starts and each call is answered in turn. The branches start and each branch's two steps are answered.
- **Then:** Four tasks open together, one per co-signer in list order, under one `MI_BODY` token with four `SCOPE` tokens whose `loopCounter` is 1 to 4 and whose local `cosigner` is that co-signer. `book` has no token until the fourth completes, then exactly one. `verdicts` is in co-signer order (`APPROVE`, `APPROVE`, `APPROVE`, `REJECT`), not in completion order. The sequential step opens one call at a time; each call's input `party` is that repetition's owner; the next starts only after the previous ends; `flags` is in owner order. The sub-process runs its own steps once per branch under its own scope and the loan is booked once after the last.
- **Example:** `4 co-signers, completed 3, 1, 4, 2 -> verdicts in order 1, 2, 3, 4; book once`.
- **Test ID:** `PRE_290_a_parallel_step_opens_every_instance_at_once_and_continues_once_after_the_last`, `PRE_290_a_sequential_step_runs_one_instance_at_a_time_with_its_own_element_as_input`, `PRE_290_a_multi_instance_sub_process_runs_its_own_steps_once_per_item_and_continues_after_the_last` (`MultiInstanceTest`); `PRE_290_one_task_per_co_signer_is_written_under_a_body_and_the_decisions_keep_co_signer_order` (`MultiInstanceStoreTest`)

### PRE-338 A completion condition that holds ends the loop and cancels the repetitions still running
- **Traces to:** `TR-STD-29`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-cosigning` with four co-signers and the completion condition `nrOfCompletedInstances >= 2`; a process that checks three accounts with a called process under the same kind of condition (`>= 1`).
- **When:** The second task completes; the second called process ends.
- **Then:** The two tasks still open are cancelled (`CancelUserTask` for each) and their repetitions are `CANCELLED`; `book` has a token; `verdicts` holds `null` where nobody answered; history records `MI_INSTANCE_CANCELLED` for each repetition stopped and `MI_COMPLETED`. For the call activity, the other two children are cancelled by `CancelChildInstance` for their tokens, and the status list has `CLEAR` only at the position of the account that answered.
- **Example:** `4 co-signers, 2 approvals -> the other 2 tasks cancelled, book starts`.
- **Test ID:** `PRE_291_a_completion_condition_that_holds_cancels_the_instances_still_running`, `PRE_291_a_completion_condition_on_a_call_activity_cancels_the_children_still_running` (`MultiInstanceTest`)

### PRE-339 The counters count repetitions, a condition can read them, and an empty collection continues at once
- **Traces to:** `TR-STD-29`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-cosigning` with four co-signers and the condition `nrOfCompletedInstances * 2 >= nrOfInstances`; the same process with no co-signers.
- **When:** The loan starts; one task completes; a second completes; and, in the other case, it starts with an empty list.
- **Then:** After the start, the body has `nrOfInstances` 4, `nrOfActiveInstances` 4 and `nrOfCompletedInstances` 0, and each repetition has its own `loopCounter`. After one completion: 3 active and 1 completed, and the loan has not moved on. After the second, `2 * 2 >= 4` holds and the loop ends; the history entry of that repetition carries `loopCounter` 2, `nrOfInstances` 4 and `nrOfCompletedInstances` 2. The task context holds `loopCounter` and never the element. With an empty list no task opens, `verdicts` is `[]` and `book` starts.
- **Example:** `1 of 4 done -> 3 active, 1 completed`.
- **Test ID:** `PRE_292_the_counters_count_instances_active_and_completed_and_a_condition_can_read_them`, `PRE_292_an_empty_collection_continues_at_once_with_an_empty_output` (`MultiInstanceTest`)

### PRE-340 A collection over the cap, or one that is not a list, opens an incident and creates nothing
- **Traces to:** `TR-STD-29`, `PRE-040`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `multi-instance.max` is 3 (and, on PostgreSQL, 2), and `loan-cosigning` has four co-signers (three, on PostgreSQL); a loan whose `cosigners` is the text `Lan, Minh`.
- **When:** Each loan starts.
- **Then:** The first raises `MULTI_INSTANCE_LIMIT` on `step` and the second `MULTI_INSTANCE_COLLECTION_INVALID`. No task, job, body or repetition token is created, the instance stays `RUNNING`, and the token waits on the step so a retry enters it again. On PostgreSQL the incident row is `OPEN` with no `pre_task` row.
- **Example:** `4 co-signers, cap 3 -> MULTI_INSTANCE_LIMIT, no task`.
- **Test ID:** `PRE_293_a_collection_over_the_cap_opens_an_incident_and_creates_no_task`, `PRE_293_a_collection_that_is_not_a_list_opens_an_incident_and_a_retry_after_the_fix_starts_the_step` (`MultiInstanceTest`); `PRE_293_a_collection_over_the_cap_opens_an_incident_and_writes_no_task` (`MultiInstanceStoreTest`)

### PRE-341 A failure inside one repetition holds the loop, not the others
- **Traces to:** `TR-STD-29`, `PRE-040`, `PRE-077`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Three owners (An, Binh, Cuong) screened by a parallel service task; and two owners screened by a sequential one.
- **When:** The screening of Binh answers the business error `SCREENING_UNAVAILABLE` while the other two complete; in the sequential case the first answers the error.
- **Then:** The incident is on Binh's own token and the other two repetitions complete (2 completed, 1 active); the loop has not ended and `book` has no token. When Binh's step is retried and answers, the loan is booked and `flags` is in owner order. In the sequential case an incident opens and the second owner is never screened.
- **Example:** `Binh fails, An and Cuong done -> loop held; retry, Binh done -> book`.
- **Test ID:** `PRE_294_a_failure_in_one_parallel_instance_holds_the_loop_and_the_others_still_finish`, `PRE_294_a_failure_in_a_sequential_instance_keeps_the_next_one_from_starting` (`MultiInstanceTest`)

### PRE-342 A standard loop repeats while its condition holds, up to its maximum, and may be tested before its first run
- **Traces to:** `TR-STD-29`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A card payment step `charge` with a standard loop, `loopMaximum` 3 and the condition `payment.status != 'PAID'`; a second loop tested before each run, with the condition `amountDue > 0`.
- **When:** The first payment is DECLINED, then PAID; another is DECLINED three times; the second loop starts with `amountDue` 0, and then with 1,200,000.
- **Then:** After DECLINED a second try starts, and after PAID the loop ends with two repetitions. A payment DECLINED three times stops at the maximum and the process goes on, with the repetitions numbered 1, 2, 3. The tested-before loop with nothing due makes no call and goes on; with 1,200,000 due it makes the first call.
- **Example:** `DECLINED, PAID -> 2 tries; DECLINED x3 -> stops at 3`.
- **Test ID:** `PRE_295_a_standard_loop_repeats_while_its_condition_holds_and_stops_at_its_maximum`, `PRE_295_a_loop_tested_before_its_first_run_may_not_run_at_all` (`MultiInstanceTest`)

### PRE-343 A loop imports and exports with its settings; one that cannot end, has no source or declares an undo is refused
- **Traces to:** `TR-STD-04`, `PRE-033`, #496
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The loop files of `PRE-290` to `PRE-295`; the payment loop without `loopMaximum`; the co-signing step without `horus:collection`; the payment loop with `horus:compensate`; the payment loop with a condition in FEEL.
- **When:** Each is imported and compiled, and each valid one is exported and read back.
- **Then:** The canonical form states the loop (`kind` `loop`, `parallel` or `sequential`, with the collection, element, cardinality, output and conditions as declared) and the BPMN export reads back to the same canonical form, so the hash is unchanged. The files without a maximum, without a source and with an undo are refused with `LOOP_UNBOUNDED`, `MULTI_INSTANCE_SOURCE` and `LOOP_COMPENSATION_UNSUPPORTED`. The FEEL condition is refused as `BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED`.
- **Example:** `standardLoopCharacteristics without loopMaximum -> LOOP_UNBOUNDED`.
- **Test ID:** `PRE_296_a_loop_reads_with_its_settings_and_exports_back_to_the_same_definition`, `PRE_296_a_loop_that_cannot_end_or_has_no_source_is_refused_at_import`, `PRE_296_a_loop_condition_in_another_language_is_refused_with_its_stage` (`MultiInstanceTest`)


### PRE-288 A cancel end event compensates its transaction newest first, and the cancel boundary event leaves the flow
- **Traces to:** `TR-COMP-01`, `TR-COMP-02`, `TR-IDEM-04`, `PRE-073`, #488
- **Milestone:** M3
- **Kind:** control
- **Given:** Process `fund-transfer-cancel`: the transaction `transfer` holds `hold-funds` (local, with a compensation boundary event whose compensation activity runs `release-hold`) and `debit-source` (remote, with a compensation boundary event whose compensation activity runs the adapter `credit-back`), then an exclusive gateway that takes a cancel end event when the target account `0099-887766` is closed. A cancel boundary event on `transfer` leads to `notify-customer`. An instance moves `25,000,000 VND` from `0011-223344` and the target is closed.
- **When:** `debit-source` is accepted and the flow reaches the cancel end event.
- **Then:**
  - The other live tokens of the transaction are cancelled, the instance is `COMPENSATING` and cannot be cancelled, and `processes.compensation.started.v1` carries the transaction's scope token, `depth` 2 and `cause` `CANCEL`. The customer has not been told.
  - `credit-back` runs first as a `COMPENSATION` job keyed by its own id, with the forward job and attempt of the debit; `release-hold` runs second with the attempt of the hold; nothing else runs.
  - With every entry `DONE` the transaction's scope token is `COMPENSATED`, the instance is `RUNNING` again, history records `COMPENSATED` and `BOUNDARY_FIRED` with trigger `CANCEL`, `notify-customer` runs once, and the instance ends `COMPLETED`: a cancelled transaction is a business outcome and no `processes.instance.failed.v1` is published.
  - A cancel end event outside a transaction, or in a transaction with no cancel boundary event, a compensation boundary event with no compensation activity, and a compensation activity with a flow, are refused when the definition is compiled (`CANCEL_END_OUTSIDE_TRANSACTION`, `CANCEL_BOUNDARY_REQUIRED`, `COMPENSATION_ACTIVITY_MISSING`, `COMPENSATION_ACTIVITY_HAS_FLOW`).
- **Example:** `debit 25,000,000 VND accepted, target closed -> credit-back, release-hold, then notify-customer; instance COMPLETED`.
- **Test ID:** `PRE_288_a_cancel_end_compensates_the_transaction_newest_first_and_the_cancel_boundary_leaves_the_flow` (`SagaCompensationTest`), `PRE_288_the_cancel_end_waits_for_the_undo_and_the_cancel_boundary_then_takes_the_flow`, `PRE_288_a_compensation_boundary_without_its_activity_and_a_cancel_end_without_its_boundary_are_refused` (`TransactionCompensationTest`)

### PRE-289 A compensation of the enclosing scope walks into the sub-processes completed in it
- **Traces to:** `TR-COMP-01`, `PRE-044`, `PRE-262`, #488
- **Milestone:** M3
- **Kind:** control
- **Given:** Process `loan-booking-nested`: `register-loan` (undone by `cancel-loan`) in the root scope, then the sub-process `disburse` with `reserve-funds` (undone by `release-funds`), the sub-process `fees` with `charge-fee` (undone by `refund-fee`) and `open-account` (undone by `close-account`), all completed; then `notify-partner` in the root scope with `onFailure: compensate`, which the partner refuses.
- **When:** `notify-partner` fails for good.
- **Then:** The compensation is that of the root scope token and takes the entries of the root scope and of every scope token under it, newest pushed first across scopes: `close-account`, `refund-fee`, `release-funds`, `cancel-loan`, each once. Every entry is `DONE`, the root scope token is `COMPENSATED`, the instance ends `FAILED`, and the timeline lists the four undos under the one compensation of the root scope. An undo started inside a sub-process still walks only that sub-process and the scopes under it (`PRE-262`).
- **Example:** `refused at notify-partner -> close-account, refund-fee, release-funds, cancel-loan`.
- **Test ID:** `PRE_289_a_compensation_of_the_enclosing_scope_walks_into_the_sub_processes_completed_in_it` (`SagaCompensationTest`)

### PRE-344 A failed undo is retried under its own job, and every undo runs once before the flow resumes
- **Traces to:** `TR-COMP-02`, `TR-IDEM-04`, `PRE-074`, #488
- **Milestone:** M3
- **Kind:** control
- **Given:** The cancelled transfer of `PRE-288`, where core banking refuses the first `credit-back` with `ACCOUNT_FROZEN`.
- **When:** The first undo fails for good, and then operator `ops.lan` retries the incident after the account is unfrozen.
- **Then:** After the failure the entry of `debit-source` is `FAILED`, an incident `COMPENSATION_FAILED` holds the walk, `release-hold` has not run, the instance is `COMPENSATING` and the cancel boundary has not fired. The retry runs `credit-back` again under the same job id with a new attempt id, so core banking sees one idempotency key; `release-hold` then runs exactly once, `notify-customer` runs once, both entries are `DONE`, the instance is `COMPLETED`, and nothing is left to run.
- **Example:** `credit-back refused, retried -> one key, two attempts; release-hold once`.
- **Test ID:** `PRE_290_a_failed_undo_is_retried_under_its_own_job_and_every_undo_runs_once_before_the_flow_resumes` (`SagaCompensationTest`)

### PRE-345 A compensation throw that names an activity undoes only that activity, and a second throw undoes nothing
- **Traces to:** `TR-COMP-01`, `TR-IDEM-04`, #488
- **Milestone:** M3
- **Kind:** control
- **Given:** Process `loan-undo-one`: `register-loan`, `reserve-funds` and `open-account` complete in the root scope, each with an undo; then two compensation throw events with `activityRef="reserve-funds"` in a row, then the end.
- **When:** The first throw runs, its undo completes, and the second throw runs.
- **Then:** Only `release-funds` runs, once; the entries of `open-account` and `register-loan` stay `PENDING`; the second throw finds nothing `PENDING` for `reserve-funds` and the flow goes on at once; the instance ends `COMPLETED`, and history records `COMPENSATION_STARTED` on each throw. A compensation throw event or compensation end event with no `activityRef` undoes the whole scope the same way; one with no outgoing flow keeps the behaviour of `PRE-076`.
- **Example:** `throw reserve-funds twice -> release-funds once`.
- **Test ID:** `PRE_291_a_throw_that_names_an_activity_undoes_only_that_activity_and_a_second_throw_undoes_nothing` (`SagaCompensationTest`)

### PRE-320 An interrupting event sub-process cancels the other work of its scope and ends the scope when it ends
- **Traces to:** `TR-STD-04`, `TR-STD-13`, #487
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `loan-application` with the task `assess` for an underwriter, and the event sub-process `withdrawal` whose start event is a message start for `applicant-withdrew`, keyed by the variable `applicationId`, interrupting; its one step is `close-file`.
- **When:** The instance `LA-2026-0042` is waiting at `assess`, and `applicant-withdrew` arrives for that key.
- **Then:**
  - The task `assess` is `CANCELLED` with its work item, `close-file` runs, and history records `EVENT_SUBPROCESS_STARTED` with trigger `MESSAGE` and `interrupting` true. A completion of the cancelled task is refused.
  - The instance is `RUNNING` until `close-file` ends and then `COMPLETED`: the scope ends with the event sub-process. A message after that finds no subscription.
- **Example:** `applicant-withdrew for LA-2026-0042 -> assess cancelled, close-file, COMPLETED`.
- **Test ID:** `PRE_320_an_interrupting_message_event_sub_process_cancels_the_other_work_and_the_scope_ends_with_it` (`EventSubProcessTest`), `PRE_320_an_interrupting_message_event_sub_process_cancels_the_wait_and_leaves_no_subscription` (`EventSubProcessPostgresIT`)

### PRE-321 A non-interrupting event sub-process runs beside the main flow, and again for the next trigger where the trigger can come again
- **Traces to:** `TR-STD-04`, #487
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-application` with a reminder event sub-process on a timer of 24 hours, `isInterrupting="false"`, and a repricing event sub-process on the signal `rate-card-updated`, `isInterrupting="false"`.
- **When:** The timer fires while `assess` is open; the underwriter completes `assess` before the reminder ends; the signal is broadcast twice.
- **Then:**
  - The task stays open and the reminder runs once. No second timer is scheduled: a timer fires once. The instance is `RUNNING` until both the main flow and the reminder have ended.
  - Each broadcast starts the repricing once. Its old subscription is deleted and a new one is written in the same transaction, so a delivery for the first subscription is refused after it was used.
  - A message start behaves as the signal does: each message of its key starts the handler.
- **Example:** `timer after 24 h -> reminder runs, assess still open; two rate changes -> two repricings`.
- **Test ID:** `PRE_321_a_non_interrupting_timer_event_sub_process_runs_once_beside_the_main_flow`, `PRE_321_a_non_interrupting_signal_event_sub_process_starts_again_for_every_broadcast` (`EventSubProcessTest`), `PRE_321_a_non_interrupting_timer_event_sub_process_runs_beside_the_wait_and_the_scope_ends_over_nothing` (`EventSubProcessPostgresIT`)

### PRE-322 Each start of an event sub-process triggers when its event happens: timer, message, signal and condition
- **Traces to:** `TR-STD-04`, `TR-STD-11`, #487
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-application` with a conditional event sub-process `assessment.score >= 80` that notifies compliance.
- **When:** The instance starts with no score; the underwriter completes `assess` with a score of 85. Another instance starts with a score of 90 and an interrupting handler. A third has four reviews that write the score 85, 90, 60 and 95, with a non-interrupting handler.
- **Then:**
  - The condition is asked in the transaction that changes the variables, never by polling; at 85 the handler starts, in the same transaction as the completion, before the scope ends. A condition that cannot be read yet, because the variable is not set, is false and fails nothing.
  - A condition that is true when the scope opens starts the handler at once, and an interrupting one cancels the task.
  - A non-interrupting condition starts the handler at 85 and at 95 only: at 90 it still held, and at 60 it was reset.
  - The timer start (`PRE-321`), the message start (`PRE-320`) and the signal start (`PRE-321`) trigger as shown there; a timer is the same job kind and lease as an intermediate timer.
- **Example:** `score 85 -> notify-compliance; 90 -> nothing; 60, 95 -> notify-compliance again`.
- **Test ID:** `PRE_322_a_conditional_event_sub_process_starts_when_the_variables_make_its_condition_true`, `PRE_322_a_conditional_event_sub_process_that_is_true_when_its_scope_opens_starts_at_once`, `PRE_322_a_non_interrupting_conditional_starts_again_only_after_its_condition_has_been_false` (`EventSubProcessTest`)

### PRE-323 An error or an escalation raised inside a scope is caught by the event sub-process of that scope, the nearest catcher first
- **Traces to:** `TR-STD-04`, #487
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A disbursement sub-process whose `check-limit` ends with the error `LIMIT_EXCEEDED` while `manual-review` is open, and an error event sub-process inside it; a debit whose business error `INSUFFICIENT_FUNDS` has a boundary event on the step and a catch-all error event sub-process in the process; a KYC sub-process that raises the escalation `AML_HIT` and goes on, with an escalation event sub-process in the process around it, interrupting or not.
- **When:** Each is raised.
- **Then:**
  - The error ends the other work of the scope: the review is cancelled with its work item, the handler runs and no incident opens. When the handler ends the sub-process is complete and the parent goes on.
  - Walking outwards, an event sub-process of a scope is tried before the boundary events on that scope's own activity, a specific code before a catch-all, and the event sub-process a handler stands in is skipped, so a handler's own error is not caught by itself. A boundary on the step wins over the event sub-process of the scope.
  - A non-interrupting escalation handler runs beside the KYC, which goes on to `verify`; an interrupting one cancels the thrower and the sub-process. An error start event is always interrupting (`EVENT_SUBPROCESS_ERROR_NOT_INTERRUPTING`).
- **Example:** `LIMIT_EXCEEDED in disburse -> review cancelled, notify-limit; INSUFFICIENT_FUNDS -> the boundary, not the process handler`.
- **Test ID:** `PRE_323_an_error_raised_inside_a_sub_process_is_caught_by_its_error_event_sub_process`, `PRE_323_a_business_error_of_a_step_is_caught_by_an_error_event_sub_process_of_the_process`, `PRE_323_a_boundary_on_the_step_wins_over_an_event_sub_process_of_the_scope_and_a_specific_code_over_a_catch_all`, `PRE_323_an_escalation_from_a_sub_process_starts_the_event_sub_process_of_the_process_around_it`, `PRE_323_an_interrupting_escalation_handler_cancels_the_sub_process_that_raised_it` (`EventSubProcessTest`), `PRE_323_a_business_error_of_a_step_is_taken_by_the_error_event_sub_process_and_opens_no_incident` (`EventSubProcessPostgresIT`)

### PRE-324 An event sub-process waits only while its scope is open, and its timer or subscription goes when the scope ends
- **Traces to:** `TR-STD-04`, `TR-STD-13`, #487
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-application` with a timer, a signal and a message event sub-process, all waiting; a screening sub-process with its own message event sub-process.
- **When:** The main flow ends; the screening ends.
- **Then:**
  - The waits do not count as work: the instance completes, the timer job is cancelled and the signal subscription deleted, and no live token is left. A running event sub-process does count, so a non-interrupting handler keeps its scope open.
  - A sub-process arms its event sub-processes when it opens and drops them when it ends. The start event of an event sub-process holds one token that is never shown as work on the timeline.
- **Example:** `assess completes -> COMPLETED, timer cancelled, subscription deleted`.
- **Test ID:** `PRE_324_a_scope_that_ends_removes_the_timer_and_the_subscriptions_of_its_waiting_event_sub_processes`, `PRE_324_an_event_sub_process_of_a_sub_process_is_armed_when_it_opens_and_dropped_when_it_ends` (`EventSubProcessTest`), `PRE_324_a_non_interrupting_signal_event_sub_process_starts_for_each_broadcast_and_its_subscription_goes_with_the_scope` (`EventSubProcessPostgresIT`)

### PRE-325 Event sub-processes import, export to the same definition and are refused with a code when they are misplaced or unfinished
- **Traces to:** `TR-STD-04`, `TR-STD-10`, #487
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The worked example `loan-application-event-subprocesses.bpmn` (message, timer and signal event sub-processes); a process with a timer start event; an event sub-process with a flow in, one whose start event has no trigger, a message one without `horus:correlationKey`, an error one with `isInterrupting="false"`, and a boundary event on one.
- **When:** Each is imported, and the valid ones are exported and imported again.
- **Then:** The valid files read, compile and export to the same canonical definition: `triggeredByEvent` on the sub-process, the trigger kind with its duration, code or signal, and `interrupting` false on the start step when declared; definitions without them keep their hash. The others are `422 VALIDATION_FAILED` with `START_TRIGGER_MISPLACED`, `EVENT_SUBPROCESS_HAS_FLOW`, `EVENT_SUBPROCESS_TRIGGER_REQUIRED`, `CORRELATION_KEY_REQUIRED`, `EVENT_SUBPROCESS_ERROR_NOT_INTERRUPTING` and `BOUNDARY_ATTACHED_INVALID`; nothing is stored. A message start event inside an event sub-process never starts a process: only the start event of the process itself does.
- **Example:** `timer start event on the process -> START_TRIGGER_MISPLACED`.
- **Test ID:** `PRE_325_event_sub_processes_export_and_read_back_to_the_same_definition`, `PRE_325_a_misplaced_or_unfinished_event_sub_process_is_refused_with_its_code` (`EventSubProcessTest`)

### PRE-326 A completed sub-process, transaction or call activity is undone by the handler its compensation event sub-process or compensation boundary event names
- **Traces to:** `TR-COMP-01`, `TR-IDEM-04`, `PRE-289`, #487
- **Milestone:** M3
- **Kind:** control
- **Given:** Process `loan-booking` with the sub-process `disburse`, whose step `reserve-funds` is undone by `release-funds`, and a compensation event sub-process inside it holding one service task `reverse-disbursement` with `horus:input account=${instance.account}`; the same sub-process with a compensation boundary event and compensation activity instead; and a call activity `check-credit` with a compensation boundary event whose activity is `cancel-credit-hold`.
- **When:** The step completes and `disburse` ends; the child of `check-credit` completes.
- **Then:**
  - On the end of the sub-process one entry is pushed on the stack of the scope it stands in, after the entries of its steps, so a later compensation of that scope runs `reverse-disbursement` first, with `account` read when the sub-process ended and nothing else of the variables. Each entry still runs once (`PRE-290`).
  - The handler adds to the undo of the steps inside: they are undone after it. A sub-process that is cancelled or interrupted before it ends pushes nothing.
  - A call activity pushes the entry of its boundary's compensation activity when the child completes, with the child's id, as a `horus:compensate` handler does. A compensation event sub-process needs a sub-process or transaction, a line of service tasks with `horus:handler` (`PRE-340`), and no compensation boundary event beside it (`COMPENSATION_HANDLER_SCOPE`, `COMPENSATION_EVENT_SUBPROCESS_SHAPE`, `COMPENSATION_DUPLICATE`).
- **Example:** `disburse ends -> push release-funds, then reverse-disbursement; a later undo runs reverse-disbursement, release-funds`.
- **Test ID:** `PRE_326_a_compensation_event_sub_process_pushes_the_undo_of_its_sub_process_when_it_completes`, `PRE_326_a_compensation_boundary_event_on_a_sub_process_or_a_call_activity_names_its_undo`, `PRE_326_a_compensation_event_sub_process_has_one_undo_task_in_a_sub_process_that_has_no_other_undo` (`EventSubProcessTest`)
### PRE-300 An inclusive split takes every outgoing flow whose condition is true
- **Traces to:** `TR-STD-04`, #494
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Process `payment-screening` with an `inclusiveGateway` `screen` whose flows go to `aml-review` (condition `amount >= 100000000 VND`), `sanctions-review` (condition `crossBorder == true`) and `standard-check` (the default flow); an instance started with `amount = 150000000 VND` and `crossBorder = true`.
- **When:** The token reaches `screen`.
- **Then:** One token waits at `aml-review` and one at `sanctions-review`; `standard-check` is never entered. `NODE_COMPLETED` on `screen` lists the flows taken, `screen__aml` and `screen__sanctions`, with reason `conditions`. A flow with no condition is always taken. A condition that is not a boolean fails the step with `CONDITION_ERROR`.
- **Example:** `150,000,000 VND, cross-border -> two tokens`.
- **Test ID:** `PRE_300_a_split_takes_every_flow_whose_condition_is_true`, `PRE_300_an_inclusive_gateway_with_its_default_imports_and_exports_to_the_same_definition` (`InclusiveGatewayBpmnTest`)

### PRE-301 An inclusive split takes the default flow only when no condition is true
- **Traces to:** `TR-STD-04`, #494
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The same process; an instance started with `amount = 25000000 VND` and `crossBorder = false`. A second process whose gateway has no default flow; a third whose default flow carries a condition.
- **When:** The token reaches `screen`.
- **Then:** Only `standard-check` is entered, and `NODE_COMPLETED` records reason `default`. With no true condition and no default flow the step fails with `NO_PATH`. A default flow that carries a condition, or is not an outgoing flow of the gateway, is refused at activation (`DEFAULT_FLOW_HAS_CONDITION`, `DEFAULT_FLOW_INVALID`).
- **Example:** `25,000,000 VND, domestic -> standard-check only`.
- **Test ID:** `PRE_301_when_no_condition_is_true_only_the_default_flow_is_taken`, `PRE_301_no_true_condition_and_no_default_flow_is_no_path`, `PRE_301_a_default_flow_that_carries_a_condition_or_is_not_an_outgoing_flow_is_refused_at_activation`

### PRE-302 An inclusive join waits for the tokens that can still arrive and fires once
- **Traces to:** `TR-STD-04`, `TR-ASY-03`, #494
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `payment-screening` with `aml-review` and `sanctions-review` open, both leading to the inclusive gateway `settle` (several incoming flows), which leads to `release`.
- **When:** One review is completed, then the other, in either order. In a second instance only `aml-review` was started, and it is completed.
- **Then:** The first arrival becomes `JOINED` and `TOKEN_JOINED` lists in `waitingFor` the steps a token can still arrive from. The second arrival completes both arrivals and creates one token to `release`, with one `JOIN_FIRED` row. In the second instance the join fires at the first arrival, because no other token can reach it: the number of arrivals is whatever the split started, not the number of incoming flows. Tokens of other scope tokens are never counted.
- **Example:** `AML done, sanctions open -> waiting for sanctions-review; both done -> one release task`.
- **Test ID:** `PRE_302_the_join_waits_for_exactly_the_tokens_the_split_started_in_either_order`, `PRE_302_a_join_with_one_started_branch_fires_when_that_branch_arrives`

### PRE-303 An inclusive join does not wait for a path that can never reach it
- **Traces to:** `TR-STD-04`, #494
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The split also starts `compliance-log`, which ends in its own end event; and a process where `sanctions-review` leads to an exclusive gateway, a hit to the join and a clear to an end event.
- **When:** `aml-review` and `sanctions-review` are completed while the log is open; or `aml-review` is completed first and the sanctions review then ends with no hit.
- **Then:** The join fires while the log is still open, because `compliance-log` has no path to the join. In the second process the join waits for the sanctions review, and fires when that token ends elsewhere: the check runs after every command, so a token ending on another branch releases a waiting join, and the instance does not complete while a token waits at the join.
- **Example:** `sanctions cleared (no hit) -> the waiting join releases the payment`.
- **Test ID:** `PRE_303_a_join_does_not_wait_for_a_path_that_can_never_arrive`, `PRE_303_a_token_that_leaves_for_another_end_releases_a_join_that_is_already_waiting`

### PRE-304 An inclusive join counts tokens in loops and scopes, and two branches ending together fire it once
- **Traces to:** `TR-STD-04`, `TR-ASY-03`, #494
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A loan process whose income check loops back until the income is confirmed while the credit check waits at the join; an onboarding process whose split starts a `saga` block (its own scope token) and a service step, both leading to the join; two joins that wait for a token that sits at the other; and on PostgreSQL two replicas completing `aml-review` and `sanctions-review` of 300 payments at the same moment.
- **When:** The income check is sent round the loop once; the service step ends before the block; the deadlocked ring starts; the two reviews end together.
- **Then:** A token that can still reach the join through a loop holds it until it arrives. A scope token on a block that reaches the join counts as one token that can still arrive, whatever is inside it. A ring of joins fails the command with `JOIN_DEADLOCK` and writes nothing. Under the instance lock the two simultaneous completions apply one after the other, and every payment has exactly one `release` task, no `JOINED` token left and one `JOIN_FIRED` row.
- **Example:** `income not confirmed -> join waits; confirmed -> join fires once`.
- **Test ID:** `PRE_304_a_token_in_a_loop_that_can_still_arrive_holds_the_join_until_it_does`, `PRE_304_a_block_that_can_still_reach_the_join_is_one_token_that_holds_it` (`InclusiveGatewayBpmnTest`), `PRE_304_two_joins_that_wait_for_each_other_fail_the_command_and_leave_the_state_unchanged`, `PRE_304_inclusive_join_prevents_concurrent_lost_wakeup` (`InclusiveJoinPostgresIT`)

### PRE-296 Each executable pool of a collaboration is a definition of its own; a pool with no process is kept for display
- **Traces to:** `TR-STD-04`, `TR-STD-10`, `TR-TEN-01`, #497
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The worked example `order-payment-pools.bpmn`: a collaboration of the pools `Order desk` (process `order-desk`), `Payment desk` (process `payment-desk`) and `Auditor` (no process), imported by `bank-a` with `Idempotency-Key: imp-1`.
- **When:** The file is imported, then imported again with the same key.
- **Then:** `order-desk` and `payment-desk` are two definitions of `bank-a`, both version 1; the answer is the first and names the other in `alsoImported`. The replay returns the same answer and stores nothing more. `Auditor` is not a definition. `bank-b` has neither. A file of several processes with no collaboration is `422 VALIDATION_FAILED` with `PROCESS_NOT_IN_COLLABORATION`.
- **Example:** `3 pools, 2 with a process -> order-desk v1 and payment-desk v1`
- **Test ID:** `PRE_296_each_executable_pool_is_a_definition_and_the_black_box_is_not` (`CollaborationImportTest`), `PRE_296_two_pools_become_two_definitions_and_a_replay_stores_nothing_more` (`CollaborationPostgresIT`)

### PRE-297 A lane names the work group of a user task that has no assignee, and never overrides an assignee
- **Traces to:** `TR-STD-04`, #497
- **Milestone:** M3
- **Kind:** semantics
- **Given:** In `payment-desk`, the lane `Back office` holds the user task `confirm-payment`, which has no `horus:assignee`; a second file where the task has `horus:assignee="role:cashier"`; a third where the task is in no lane.
- **When:** Each file is imported, and a message starts `payment-desk` for key `ORD-90001`.
- **Then:** The task is assigned to `group:back-office` (the lane's name in lower case, other characters as hyphens) and the task list shows that group. In the second file the assignee stays `role:cashier`. In the third the import is refused with `ASSIGNEE_INVALID`. The lanes are kept as written.
- **Example:** `lane "Back office" -> group:back-office`
- **Test ID:** `PRE_297_a_lane_names_the_group_of_a_user_task_with_no_assignee_and_never_overrides_one`, `PRE_297_a_user_task_with_no_assignee_and_no_lane_is_still_refused` (`CollaborationImportTest`), `PRE_297_the_lane_names_the_work_group_of_a_task_started_by_the_message_start_of_its_pool` (`CollaborationPostgresIT`)

### PRE-298 Message flows between pools are kept, document the correlation of their target, and survive the export
- **Traces to:** `TR-STD-04`, `TR-STD-10`, #497
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The worked example: `mf-received` from `report-payment` in `payment-desk` to the receive task `wait-for-payment` of `order-desk` (correlation key `orderId`), and `mf-audit` from `audited` to the black box `Auditor`.
- **When:** `order-desk` is read, and each definition is exported and read again.
- **Then:** The three message flows are kept with their message names and the pool at each end; `mf-received` records the correlation variable `orderId` of the step it targets, and `mf-audit` ends at the pool. The export of one pool is a file that stands alone: this pool with its lanes, every other pool as a participant with no process, and each message flow retargeted to that pool. Reading it gives the same canonical definition, the same lanes and the same three message flows. A message flow runs nothing: messages are delivered by `POST /messages:correlate`.
- **Example:** `mf-received -> orderId; export of payment-desk keeps pool-order as a black box`
- **Test ID:** `PRE_298_message_flows_are_kept_with_their_pools_and_the_correlation_of_the_catching_step`, `PRE_298_export_keeps_the_pools_lanes_and_message_flows_and_reads_back_to_the_same_definition` (`CollaborationImportTest`), `PRE_298_the_export_of_a_pool_keeps_the_message_flows_and_the_lanes` (`CollaborationPostgresIT`)

### PRE-299 A choreography is kept as received with a warning and is never run
- **Traces to:** `TR-STD-04`, #497
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The worked example with a `choreography` element added to `definitions`.
- **When:** The file is imported.
- **Then:** The import succeeds, the stored source keeps the element, and the answer lists `CHOREOGRAPHY_KEPT_AS_RECEIVED` in `warnings` with the line. No definition comes from it.
- **Example:** `choreography c -> warning, 2 definitions as before`
- **Test ID:** `PRE_299_a_choreography_is_kept_as_received_with_a_warning_and_a_second_process_needs_a_collaboration` (`CollaborationImportTest`)
### PRE-346 Pending migration plans and calendar changes appear in the approvals queue, bound to the reviewed hash
- **Traces to:** `TR-SEC-04`, `TR-UI-02`, PD-139, #473
- **Milestone:** M3
- **Kind:** control
- **Given:** At bank-a `thu` proposes the execution of the plan loan-origination v1 to v2 at 02:00 and the holiday 1 January 2028 for `vn-banking-hours` at 03:00; `lan` holds `processes:migration:manage` and `processes:calendar:manage`; `dan` holds `processes:read` only; `eve` is at bank-b.
- **When:** each lists `GET /approvals`; `lan` approves the plan and the change with a wrong hash, then with the `contentHash` of the item; `thu` approves with the right hash.
- **Then:**
  - `lan` sees `MIGRATION_PLAN` then `CALENDAR_CHANGE`, oldest first, with `contentHash` (the plan hash; the change hash), `reason` and `approve`, `reject` allowed. A cursor continues across the two kinds. `thu` has no `approve` on either (`SELF_APPROVAL_FORBIDDEN`); she may withdraw her calendar change with `reject`. `dan`, who holds neither manage permission, sees nothing: not the migration plan and, since AUD-07, not the calendar change either. `eve` sees nothing.
  - A hash other than the item's is `409 CONTENT_CHANGED` and nothing is written; the item stays in the list. With the item's hash both are approved and leave the list. `thu` is `403 SELF_APPROVAL_FORBIDDEN` with the right hash.
- **Example:** `GET /approvals -> [{kind: MIGRATION_PLAN, contentHash: <planHash>}, {kind: CALENDAR_CHANGE, contentHash: <changeHash>}]; approve with another hash -> 409 CONTENT_CHANGED`
- **Test IDs:** `PRE_254_a_pending_migration_plan_and_calendar_change_are_listed_oldest_first_with_their_hash`, `PRE_346_the_proposer_may_not_approve_and_a_reader_sees_no_calendar_change`, `PRE_254_the_cursor_continues_across_kinds`, `PRE_254_an_approval_names_the_reviewed_hash_and_another_hash_writes_nothing`, `PRE_254_the_proposer_cannot_approve_with_the_right_hash`

### PRE-330 A complex gateway joins on its activation condition, fires once, absorbs the late token, and splits on its outgoing conditions
- **Traces to:** `TR-STD-04`, #500
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A payment of VND 2,500,000,000 that three approvers (`branch-a`, `branch-b`, `branch-c`) may sign after a parallel fork, joined by a complex gateway with the activation condition `_activationCount >= 2` and one outgoing flow to the task `release`; and a transfer routed by a complex gateway with the condition `amount > 1000000000` to `review` and a default flow to `auto`.
- **When:** `branch-a`, then `branch-b`, then `branch-c` complete; a transfer of VND 2,500,000,000 and one of VND 5,000,000 start; a join whose condition is `_activationCount > 3` receives its third token.
- **Then:**
  - Each arriving token is held `JOINED` and counted; `_activationCount` is the number held. After `branch-a` nothing leaves; after `branch-b` the condition holds and one token leaves, so `release` opens once; the token of `branch-c` is absorbed and opens nothing. When every incoming flow has delivered its token the gateway resets for the next round.
  - A gateway without an activation condition fires when every incoming flow has delivered, like a parallel join. A gateway with one incoming flow only splits.
  - The split takes every outgoing flow whose condition holds, else the default flow; the large transfer opens `review`, the small one `auto`. No true condition and no default is `NO_PATH`.
  - A join whose condition is still false when every incoming flow has delivered fails the step with `ACTIVATION_CONDITION_FALSE` instead of waiting for ever.
- **Example:** `two of three signatures -> release once; the third is absorbed`.
- **Test ID:** `PRE_330_a_complex_join_fires_once_when_its_condition_is_met_and_absorbs_the_late_token`, `PRE_330_a_complex_gateway_splits_on_its_outgoing_conditions_and_falls_back_to_the_default_flow`, `PRE_330_a_complex_join_whose_condition_never_holds_fails_the_step_instead_of_stalling` (`ComplexAdHocMultipleTest`)

### PRE-331 An ad-hoc sub-process runs the activities its host starts, in any order its `ordering` allows, until its completion condition holds
- **Traces to:** `TR-STD-04`, #500
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `customer-onboarding` with the ad-hoc sub-process `onboarding` holding the activities `collect-id`, `screen-sanctions` and `call-referee`, no sequence flow between them, the completion condition `idCheck.passed == true`, and the task `open-account` after it.
- **When:** The instance enters `onboarding`; the host starts `collect-id` and `screen-sanctions` side by side (Parallel) and `collect-id` completes with `passed` true; or completes with `passed` false and is started again; or, with ordering Sequential, a second activity is started while one runs; or the condition is left out and each activity completes once.
- **Then:**
  - Entering the sub-process opens a scope and runs nothing. An activity runs only when the host starts it, with `StartAdHocActivity`; an activity that does not stand in the sub-process is `ACTIVITY_NOT_FOUND`.
  - With Parallel ordering any number of activities may run; with Sequential one at a time, and a start while another runs is `ACTIVITY_NOT_ENABLED`.
  - When an activity ends the condition is asked. True cancels the activities still running with their work items (`cancelRemainingInstances`, true by default; false lets them finish first) and the token leaves by the outgoing flow. False leaves the sub-process open and an activity may run again.
  - Without a completion condition the sub-process ends when nothing runs and every activity has completed once.
  - An ad-hoc sub-process holds activities and no sequence flow, event, gateway or boundary event (`AD_HOC_HAS_FLOW`, `AD_HOC_CONTENT_INVALID`, `AD_HOC_EMPTY`).
- **Example:** `identity check passed -> screening cancelled, account opening starts`.
- **Test ID:** `PRE_331_an_ad_hoc_sub_process_starts_nothing_until_the_host_starts_an_activity`, `PRE_331_with_parallel_ordering_the_condition_cancels_the_remaining_activities_and_the_flow_goes_on`, `PRE_331_a_false_condition_leaves_the_sub_process_open_and_an_activity_may_run_again`, `PRE_331_with_sequential_ordering_a_second_activity_is_not_enabled_while_one_runs`, `PRE_331_without_a_condition_the_sub_process_ends_when_every_activity_has_completed_once` (`ComplexAdHocMultipleTest`)

### PRE-332 A multiple event fires on any one of its definitions, a parallel multiple event on all of them
- **Traces to:** `TR-STD-04`, #500
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A payment that waits at an intermediate catch event for the signal `rate-card-updated` or the message `funds-received`; the same event with `parallelMultiple="true"`; a service task `charge` with a boundary event holding a timer of one hour and an error; an event sub-process whose start event holds a timer of 24 hours and the message `funds-received`.
- **When:** The funds arrive; only the signal arrives and then the funds; the timer of the boundary falls due; the timer of the event sub-process falls due.
- **Then:**
  - The event waits with one token per definition, each parked by the behaviour of that definition: a subscription for the signal and the message, a job for a timer, the condition asked again when variables change. A multiple event moves on at the first occurrence and drops the other waits (the signal subscription is deleted); a parallel multiple event holds each occurrence and moves on when the last one has occurred.
  - A boundary event with a timer and an error fires on either: the timer interrupts the activity, and an error is found when it is thrown. An event sub-process with several triggers starts on the first one and the other waits go.
  - A multiple event holds timer, message, signal or conditional definitions (a process start event: message and signal only, and with `parallelMultiple` the instance starts when all have arrived, see PRE-339; a boundary event: timer, error and escalation, and parallel only with timers) and one message at most (`MULTIPLE_EVENT_DEFINITION_INVALID`, `MULTIPLE_EVENT_MESSAGE_ONCE`).
- **Example:** `funds received -> the payment moves on, the signal wait is deleted`.
- **Test ID:** `PRE_332_a_multiple_catch_event_fires_on_the_first_definition_and_drops_the_other_wait`, `PRE_332_a_parallel_multiple_catch_event_waits_until_every_definition_has_occurred`, `PRE_332_a_multiple_boundary_event_fires_on_its_timer_and_interrupts_the_activity`, `PRE_332_a_multiple_start_of_an_event_sub_process_fires_on_one_trigger_and_drops_the_other` (`ComplexAdHocMultipleTest`)

### PRE-333 A timer cycle on the start event of an event sub-process repeats a non-interrupting one for its count
- **Traces to:** `TR-STD-04`, #487, #500
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-application` with a reminder event sub-process, `isInterrupting="false"`, started by `timeCycle` `R2/PT24H`.
- **When:** The first timer fires, then the second.
- **Then:** Each firing starts the reminder; the first schedules the next timer one interval later, the second schedules none. `R/PT1H` has no end. An interrupting one fires once. A `timeCycle` anywhere else is refused (`TIMER_CYCLE_MISPLACED`; on a catch or boundary event `S1`).
- **Example:** `R2/PT24H -> reminder at +24 h and +48 h, then no timer`.
- **Test ID:** `PRE_333_a_timer_cycle_start_repeats_a_non_interrupting_event_sub_process_for_its_count` (`ComplexAdHocMultipleTest`)

### PRE-334 A misplaced ad-hoc sub-process or multiple event is refused with its code
- **Traces to:** `TR-STD-04`, `TR-STD-10`, #500
- **Milestone:** M3
- **Kind:** semantics
- **Given:** An ad-hoc sub-process with a sequence flow between two activities; a process start event with `parallelMultiple="true"`; a multiple catch event with two message definitions.
- **When:** Each is imported.
- **Then:** `AD_HOC_HAS_FLOW` naming the flow, `MULTIPLE_EVENT_DEFINITION_INVALID` naming the start event, `MULTIPLE_EVENT_MESSAGE_ONCE` naming the catch event; nothing is stored.
- **Example:** `parallel multiple process start -> MULTIPLE_EVENT_DEFINITION_INVALID`.
- **Test ID:** `PRE_334_a_misplaced_ad_hoc_sub_process_or_multiple_event_is_refused_with_its_code` (`ComplexAdHocMultipleTest`)

### PRE-335 A complex gateway, an ad-hoc sub-process and a multiple event export and read back to the same definition
- **Traces to:** `TR-STD-04`, `TR-STD-10`, #500
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A process with a complex gateway and its activation condition, one with a Sequential ad-hoc sub-process with `cancelRemainingInstances="false"`, and one with a parallel multiple catch event.
- **When:** Each is read, exported as BPMN and read again.
- **Then:** The canonical definition, and so the hash, is the same: the activation condition, the ordering, the completion condition and the cancel flag, and every definition of a multiple event with `parallelMultiple`, are part of it.
- **Example:** `ordering Sequential survives the export`.
- **Test ID:** `PRE_335_the_export_of_each_element_reads_back_to_the_same_definition` (`ComplexAdHocMultipleTest`)

### PRE-336 Starting an ad-hoc activity needs `processes:task:manage`, is idempotent, and cancels what the completion condition leaves
- **Traces to:** `TR-SEC-02`, `TR-IDEM-01`, #500
- **Milestone:** M3
- **Kind:** control
- **Given:** `customer-onboarding` entered by an officer who holds `processes:start` and `processes:task:work`, and a branch manager who holds `processes:task:manage`.
- **When:** The officer starts `collect-id`; the manager starts `collect-id` twice with the same `Idempotency-Key` and then `screen-sanctions`; the officer completes the identity check as passed.
- **Then:** The officer is refused with `403 PERMISSION_DENIED` and nothing changes. The manager's replay returns the first answer and opens one task. The completion cancels the screening task in the same transaction and opens `open-account`. The REST action is `POST /instances/{id}:start-ad-hoc-activity` with `If-Match` and `Idempotency-Key`; a missing one is `428`.
- **Example:** `officer-lan -> 403; manager-hoa -> collect-id opens once`.
- **Test ID:** `PRE_336_the_operator_starts_activities_with_task_manage_and_the_condition_cancels_the_rest` (`AdHocPostgresIT`)

### PRE-347 A data output association writes the step result into a data object; a data input association hands it to a step
- **Traces to:** #499
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-data` with a service task `score` that has a data input association from the data object reference `profile-ref` and a data output association from its output `score` to the reference `score-ref`; the instance starts with `profile` set.
- **When:** `score` completes with `{score: 712, band: "B"}`.
- **Then:** The variable `creditScore` holds `712`, the timeline records `DATA_OBJECT_WRITTEN` with the data object's name, and the step received `profile` as its input `profile`. An output the result does not contain is `DATA_OUTPUT_MISSING`; an input data object with no value is `DATA_OBJECT_UNSET`. A data store reference that an association names is read and written through the host (`PRE-341`); one that no association names is kept, exported and hashed and runs nothing.
- **Example:** `score -> 712 into creditScore`.
- **Test ID:** `PRE_347_a_data_output_association_leaves_the_result_in_the_data_object_and_a_data_input_reads_it` (`ConformanceSuiteTest`)

### PRE-348 Every conformance file keeps its elements, flows and hash through export and import
- **Traces to:** #499
- **Milestone:** M3
- **Kind:** semantics
- **Given:** the files under `bpmn/conformance`: data objects, an exclusive gateway with a default flow, a parallel fork and join, a sub-process followed by a timer catch, an instantiating event-based gateway, a compensation event sub-process of two tasks and a data store.
- **When:** Each is imported, run with every call, task and timer answered, then exported and imported again.
- **Then:** The token takes the expected path and the instance completes; the second import has the same number of flow elements and the same canonical form, so the same definition hash.
- **Example:** `amount 2,500,000 -> large; amount 40,000 -> small (default)`.
- **Test ID:** `PRE_348_every_conformance_file_keeps_its_element_flow_count_and_hash_through_export_and_import` (`ConformanceSuiteTest`)

### PRE-349 A compensation throw with `waitForCompletion="false"` lets the flow go on while the undo runs, and each undo runs once
- **Traces to:** `TR-STD-04`, #499, PD-149
- **Milestone:** M3
- **Kind:** control
- **Given:** a loan booking that registers the loan and reserves the funds (each with an undo), then throws compensation for `reserve-funds` twice with `waitForCompletion="false"`, then notifies the customer.
- **When:** The instance starts; then the undo job runs.
- **Then:**
  - The customer notice runs at once: the instance is `RUNNING`, never `COMPENSATING`, and nothing else is cancelled. The throw records a walk token (a live `FLOW` token marked `$backgroundUndo`) that holds the instance open, so it cannot complete or be cancelled while an undo is in flight (`InvalidStateException`); an intermediate throw with no outgoing flow is refused (`COMPENSATION_BACKGROUND_NEEDS_FLOW`).
  - The walk schedules one `COMPENSATION` job at a time, newest first, under the instance lock; an undo already running, done or failed for good is not scheduled again, so the second throw, a retry and a replayed command undo nothing twice. A failed or manual undo opens its incident and pauses that walk only; the flow beside it is not touched.
  - When the last entry is `DONE` the shell sends `EndBackgroundCompensation`, the walk token completes and the scope can end; the command is safe to repeat. An undo in flight when its instance ended (a terminate end) is still recorded.
- **Example:** `reserve-funds -> release-funds called once, loan registration untouched, instance COMPLETED after it`.
- **Test ID:** `PRE_349_control_a_background_undo_runs_once_while_the_flow_goes_on_and_the_instance_ends_after_it` (`SagaCompensationTest`), `PRE_349_control_a_compensation_throw_that_does_not_wait_lets_the_flow_go_on_and_holds_the_end_until_the_undo_is_done` (`ConformanceSuiteTest`)

### PRE-350 A parallel multiple process start waits for all its definitions
- **Traces to:** `TR-STD-04`, #499, PD-149
- **Milestone:** M3
- **Kind:** semantics
- **Given:** a process `account-opening` whose start event has `parallelMultiple="true"` with the message `account-opening-request` and the signal `kyc-cleared`.
- **When:** The message with key `APP-2026-0042` arrives, then the signal; and in another run the signal first, then the message.
- **Then:** The first arrival is held (`pre_start_arrival`) and a message answers `WAITING`; the last one starts exactly one instance with the message key as business key and the message payload as variables, and consumes the held rows. A repeat of an arrival with the same key replaces the held one and starts nothing; a replayed request returns its first answer. A signal is a latch with no key and completes the oldest held message. Definitions of a parallel start are messages and signals only, one message at most; the versions of the key are locked while an arrival is counted, so two arrivals that complete the set together start one instance.
- **Example:** `APP-2026-0042 request + kyc-cleared -> one instance, business key APP-2026-0042`.
- **Test ID:** `PRE_350_the_instance_starts_only_when_the_message_and_the_signal_have_both_arrived`, `PRE_350_a_signal_that_comes_first_is_held_and_the_message_starts_the_instance` (`ParallelStartPostgresIT`), `PRE_350_a_parallel_multiple_process_start_imports_compiles_and_runs_once_it_has_been_started` (`ConformanceSuiteTest`)

### PRE-351 A multiple throw or end event throws every definition
- **Traces to:** `TR-STD-04`, #499, PD-149
- **Milestone:** M3
- **Kind:** semantics
- **Given:** an intermediate throw event holding the message `loan-approved` and the signal `credit-line-opened`, followed by an end event holding the message and the signal `booking-closed`.
- **When:** The instance runs.
- **Then:** Each definition is thrown, messages, signals and background compensations in document order and escalations last (an escalation an interrupting handler catches takes the token over), then the flow goes on or the token ends. Only message, signal, escalation and compensation with `waitForCompletion="false"` are allowed together; an error, terminate, cancel, link or waiting compensation is `MULTIPLE_THROW_DEFINITION_INVALID`. A multiple throw is part of the definition hash.
- **Example:** `announce -> loan-approved sent and credit-line-opened broadcast, then close -> loan-approved and booking-closed`.
- **Test ID:** `PRE_351_a_multiple_throw_and_a_multiple_end_event_throw_every_definition_and_then_go_on` (`ConformanceSuiteTest`), `PRE_334_a_misplaced_ad_hoc_sub_process_or_multiple_event_is_refused_with_its_code` (`ComplexAdHocMultipleTest`)

### PRE-352 An instantiating event-based gateway starts the process on the first of its message or signal catches
- **Traces to:** #499
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-intake`: an `eventBasedGateway` with `instantiate="true"` and no flow in, leading to the message catch `online` (`loan-application`) and the signal catch `branch` (`branch-batch-opened`), each followed by a review. It is deployed in `bank-a` and `bank-b`.
- **When:** (a) The message `loan-application` is correlated in `bank-a` with `{applicant: "Tran Thi Mai"}`. (b) `bank-a` broadcasts `branch-batch-opened`. (c) A message no catch waits for arrives, or the instance is started through the API with no trigger.
- **Then:** (a) One instance is created and continues after `online` with the payload as its variables; nothing waits on `branch`, so there is nothing to cancel, and history records `EVENT_GATEWAY_STARTED`. (b) One instance of `bank-a` starts after `branch`; `bank-b` starts none. (c) The message is `MESSAGE_NOT_CORRELATED`; the start is refused with `START_TRIGGER_REQUIRED` and nothing is stored. The import refuses, with their codes, a gateway with an incoming flow, one that is not in the process itself, and one that leads to a timer or conditional catch (`EVENT_GATEWAY_INSTANTIATE_INCOMING`, `EVENT_GATEWAY_INSTANTIATE_SCOPE`, `EVENT_GATEWAY_INSTANTIATE_TARGET`). A catch after the gateway needs no `horus:correlationKey`: no instance exists to hold the key. `eventGatewayType="Parallel"` is `PRE-355`.
- **Example:** `loan-application APP-9001 -> instance at review-online; branch-batch-opened -> instance at review-branch`.
- **Test ID:** `PRE_352_the_first_message_creates_the_instance_after_its_catch_and_the_others_are_not_waited_for`, `PRE_352_a_signal_creates_an_instance_of_its_own_tenant_after_the_signal_catch`, `PRE_352_a_message_no_catch_waits_for_starts_nothing_and_a_plain_start_is_refused` (`DataStoreAndGatewayStartPostgresIT`), `PRE_352_an_instantiating_event_gateway_starts_after_the_catch_that_fired_and_waits_for_no_other` (`ConformanceSuiteTest`)

### PRE-355 A parallel instantiating event-based gateway starts the process on its first event and awaits every other
- **Traces to:** #499, `PD-149`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `customer-onboarding`: an `eventBasedGateway` with `instantiate="true"` and `eventGatewayType="Parallel"`, no flow in, leading to the message catches `kyc` (`kyc-documents`) and `consent` (`consent-signed`) and the signal catch `sanction` (`sanction-screening-done`), each followed by a review. It is deployed in `bank-a` and `bank-b`.
- **When:** (a) `kyc-documents` is correlated in `bank-a` with key `CIF-1001`, then `consent-signed` with the same key and `sanction-screening-done` is broadcast. (b) `kyc-documents` and `consent-signed` for `CIF-2002` reach two replicas at the same moment. (c) Both banks receive `kyc-documents` for `CIF-3003`, then `bank-b` receives `consent-signed`. (d) A parallel gateway with no `instantiate="true"` is imported.
- **Then:** (a) The first message creates one instance with `CIF-1001` as business key and it continues after `kyc`; the other two catches wait, one subscription each, and none is cancelled. A message catch with no `horus:correlationKey` waits under the business key of the instance. The second message answers `DELIVERED` for the same instance and the signal wakes the third wait, and starts no second instance while an instance of the process waits for it; the instance holds three reviews. (b) The arrivals of one key are serialised by the lock of the definition, so one instance is created: one arrival answers `STARTED`, the other `DELIVERED` for it. (c) Each bank has its own instance and `consent-signed` reaches that of `bank-b` only. (d) The import is refused with `EVENT_GATEWAY_PARALLEL_INSTANTIATE`. When a signal starts the instance and the process has a message catch, the instance has no business key to correlate under and the start is refused with `CORRELATION_KEY_REQUIRED`.
- **Example:** `kyc-documents CIF-1001 -> instance at check-kyc; consent-signed CIF-1001 -> same instance at file-consent`.
- **Test ID:** `PRE_355_the_first_event_creates_the_instance_and_every_other_catch_is_awaited`, `PRE_355_two_events_of_one_customer_on_two_replicas_create_one_instance`, `PRE_355_the_same_customer_key_in_two_tenants_starts_an_instance_in_each` (`DataStoreAndGatewayStartPostgresIT`), `PRE_355_a_parallel_instantiating_event_gateway_starts_on_one_catch_and_awaits_every_other` (`ConformanceSuiteTest`)

### PRE-353 A compensation event sub-process may hold several service tasks, which run in flow order
- **Traces to:** #499
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-booking-undo-tasks`: the sub-process `disburse` holds the step `reserve-funds` (undo `release-funds`) and a compensation event sub-process of two service tasks in a line, `reverse-disbursement` then `notify-treasury`, each with its own `horus:handler` and inputs. `notify-partner` after it fails with `onFailure: compensate`.
- **When:** The undo of the process runs.
- **Then:** When `disburse` ends the kernel pushes the step's entry, then one entry per task from the last to the first, so the walk (newest first) runs `reverse-disbursement`, `notify-treasury` and then `release-funds`, each once (`PRE-290`), each with the inputs read when the sub-process ended. A body that is not a line of service tasks with handlers from the start event to the one end event (a gateway, a branch, a user task) is `COMPENSATION_EVENT_SUBPROCESS_SHAPE`. The rest of `PRE-326` is unchanged.
- **Example:** `reverse-disbursement, notify-treasury, release-funds`.
- **Test ID:** `PRE_353_a_compensation_event_sub_process_of_two_tasks_runs_them_in_flow_order_before_the_step_inside` (`SagaCompensationTest`), `PRE_353_a_compensation_event_sub_process_with_several_tasks_undoes_them_in_flow_order`, `PRE_353_an_undo_that_branches_or_holds_anything_but_service_tasks_is_refused` (`EventSubProcessTest`), `PRE_353_a_compensation_event_sub_process_of_two_tasks_pushes_them_so_the_walk_runs_them_in_flow_order` (`ConformanceSuiteTest`)

### PRE-354 A data store association reads and writes through the host's adapter, for the tenant of the instance only
- **Traces to:** #499
- **Milestone:** M3
- **Kind:** control
- **Given:** `credit-scoring` with a service task `score`: a data input association from the data store reference `limits-ref` (store `credit-limits`, input `limit`) and a data output association of its output `score` to `ledger-ref` (store `score-ledger`). The host registers a `DataStoreAdapter` for each store, keyed by tenant. `bank-a` has a limit of `50,000,000 VND` and `bank-b` one of `5,000,000 VND`.
- **When:** (a) Each bank starts an instance, one of them with a variable `tenantId` that names the other bank. (b) The definition is imported in a host with no adapter for `score-ledger`. (c) The adapter for `score-ledger` has no write.
- **Then:** (a) Each step receives its own bank's limit as `limit` and its score is written under its own bank; every call to an adapter carries the tenant of the instance and the variable changes nothing. History records `DATA_STORE_READ` and `DATA_STORE_WRITTEN` with the store and the item. A read or write runs in the step transaction; `DataStoreAccess.idempotencyKey()` is the same when the step runs again. (b) The import is `422 VALIDATION_FAILED` with `DATA_STORE_ADAPTER_NOT_REGISTERED`, naming the step and the store, and nothing is stored. (c) The step fails with `DATA_STORE_WRITE_UNSUPPORTED`, or `DATA_STORE_READ_UNSUPPORTED` for a read; any other failure of an adapter is `DATA_STORE_FAILED` with the exception class only, never its message, which may hold a row. A store that returns no value is `DATA_STORE_VALUE_MISSING`. A data store reference that no association names needs no adapter and runs nothing.
- **Example:** `bank-a limit 50,000,000 -> score 712 under bank-a; bank-b is untouched`.
- **Test ID:** `PRE_354_a_step_reads_and_writes_its_stores_and_the_adapter_sees_only_the_tenant_of_the_instance`, `PRE_354_a_definition_that_uses_a_store_with_no_adapter_is_refused_at_deploy_time`, `PRE_354_a_store_that_cannot_be_written_stops_the_step_and_the_instance_does_not_complete` (`DataStoreAndGatewayStartPostgresIT`), `PRE_354_a_data_store_association_reads_and_writes_through_the_host_and_only_for_the_tenant_of_the_instance` (`ConformanceSuiteTest`)

### PRE-356 A start with an existing business key shows the earlier instance only to a reader
- **Traces to:** #572, AUD-01
- **Milestone:** M3
- **Kind:** control
- **Given:** `card-issue` instance `APP-1001` was started by `tuan` with the variable `ssn` set. `hoa` holds `processes:start` but not `processes:read`; `an` holds both.
- **When:** `hoa` and then `an` start `card-issue` with the business key `APP-1001`.
- **Then:** Both answer `200` and create nothing. For `hoa` the body is `{id, status, created: false}`: no variables, tokens or tasks, and an idempotent replay returns the same short body. For `an` it is the full instance. A start that creates the instance answers `201` as before.
- **Example:** `hoa: {"id":"01J...","status":"RUNNING","created":false}`.
- **Test ID:** `PRE_356_control_a_start_caller_without_read_gets_only_id_and_status_for_an_existing_business_key` (`AuditFindingsPostgresIT`), `PRE_356_control_a_start_only_caller_learns_nothing_of_an_existing_application_but_its_id_and_status` (`AuditFindingsRestIT`)

### PRE-357 A field the task form masks is masked in every instance view
- **Traces to:** #572, AUD-04, `TR-SEC-05`
- **Milestone:** M3
- **Kind:** control
- **Given:** the form `forms/approve.schema.json` of the step `approve` marks `idNumber` with `"mask": true`; the instance of `loan-review-form` holds `idNumber = 079012345678`.
- **When:** A reader gets the instance, and a start with an `Idempotency-Key` is replayed.
- **Then:** `variables.idNumber` is `***` in the instance, in the answer of the start and its stored replay, and in the answer of a completion; other variables are unchanged. The clear value stays in the engine's own state, so the process still runs on it.
- **Example:** `idNumber: 079012345678 -> ***`.
- **Test ID:** `PRE_357_control_a_variable_the_form_masks_is_masked_in_the_instance_and_in_the_idempotent_replay` (`AuditFindingsPostgresIT`)

### PRE-358 A task and its form are returned only to who the task is for
- **Traces to:** #572, AUD-05
- **Milestone:** M3
- **Kind:** control
- **Given:** a task of `loan-review-form` assigned to `role:credit-officer`. `an` holds that role; `kien` holds `processes:task:work` with the role `kyc-analyst`; `ngoc` holds `processes:task:manage`.
- **When:** Each reads the task and its form.
- **Then:** `an` (assignee) and `ngoc` (`processes:task:manage`) get them. `kien` gets `404 TASK_NOT_FOUND`, the answer for a task that does not exist, for both reads. The person who claimed the task still reads it after it is handed over.
- **Example:** `kien GET /tasks/{id} -> 404 TASK_NOT_FOUND`.
- **Test ID:** `PRE_358_control_a_task_and_its_form_are_returned_to_an_assignee_a_claimer_or_a_task_manager_and_to_nobody_else` (`AuditFindingsPostgresIT`), `PRE_358_control_a_task_is_read_by_its_assignee_and_a_task_manager_and_by_nobody_else` (`AuditFindingsRestIT`)

### PRE-359 The instance history table refuses UPDATE, DELETE and TRUNCATE
- **Traces to:** #572, AUD-03
- **Milestone:** M3
- **Kind:** control
- **Given:** `pre_history` holds the record that `an` completed the credit review.
- **When:** The application's own database role runs `UPDATE`, `DELETE` or `TRUNCATE` on `pre_history`.
- **Then:** Each statement fails with SQLSTATE `55000` ("pre_history is append-only") and the row is unchanged. The trigger is part of the V1 baseline.
- **Example:** `UPDATE pre_history SET actor_id = 'x' -> 55000`.
- **Test ID:** `PRE_359_control_pre_history_refuses_update_delete_and_truncate` (`AuditFindingsPostgresIT`)

### PRE-360 Held start arrivals expire and one definition holds a bounded number
- **Traces to:** #572, AUD-10, PD-149
- **Milestone:** M3
- **Kind:** control
- **Given:** `account-opening` waits for the message `account-opening-request` and the signal `kyc-cleared`; `parallel-start.arrival-ttl` is 24 hours and `parallel-start.max-arrivals-per-definition` is 3.
- **When:** (a) Four applications `APP-1` to `APP-4` send the message. (b) `APP-1` sends it again. (c) The clock moves 25 hours and the cleanup runs.
- **Then:** (a) `APP-4` is refused with `429 START_ARRIVALS_FULL` and a `Retry-After` header of 86,400 seconds (the TTL), and nothing is held for it. (b) The repeat replaces the held row, so the count stays 3. (c) The three rows older than the TTL are deleted, across tenants, and a new arrival is held again.
- **Example:** `APP-4 -> 429 Retry-After: 86400; after 25 hours pre_start_arrival is empty`.
- **Test ID:** `PRE_360_control_a_definition_holds_at_most_the_configured_arrivals_and_a_repeat_does_not_count` (`AuditFindingsPostgresIT`), `PRE_360_control_a_full_start_backlog_answers_429_with_retry_after_and_a_problem_code` (`ProcessRestErrorMatrixTest`), `PRE_360_control_arrivals_older_than_the_ttl_are_deleted_by_the_cleanup` (`AuditFindingsPostgresIT`)

### PRE-361 A field an agent step masks is masked in every instance view, at any depth
- **Traces to:** #365, AUD-04, `TR-SEC-05`
- **Milestone:** M3
- **Kind:** control
- **Given:** `loan-agent-assessment` has an agent step `assess-documents` with the result variable `assessment`; its `outputSchema` marks `summary` with `"mask": true`. A schema may also mark `applicant.nationalId` and the `nationalId` of each item of `guarantors`. The instance holds `assessment = {riskScore: 42, summary: "salary slip shows 38,000,000 VND", applicant: {name: "Tran Thi Mai", nationalId: "079012345678"}, guarantors: [{name: "Le Van An", nationalId: "079099887766"}]}`. A step with no result variable merges its output fields into the instance variables, and the same schema applies to them.
- **When:** A reader gets the instance, and a start with an `Idempotency-Key` is replayed.
- **Then:** `assessment.summary`, `assessment.applicant.nationalId` and `assessment.guarantors[0].nationalId` are `***` in the instance, in the answer of the start and in its stored replay; `riskScore`, `applicant.name` and `guarantors[0].name` are unchanged. A masked field that is null stays null. The clear values stay in the engine's own state. A form's prefilled data masks nested fields the same way.
- **Example:** `assessment.applicant.nationalId: 079012345678 -> ***`.
- **Test ID:** `PRE_361_control_a_field_an_agent_step_masks_is_masked_in_the_instance_and_the_replay_at_any_depth` (`AuditFindingsPostgresIT`), `PRE_361_control_an_accepted_agent_answer_is_masked_in_the_instance_view_by_its_output_schema` (`AgentTaskIT`), `PRE_361_control_a_masked_field_is_masked_inside_an_object_and_in_every_item_of_a_list` (`TaskFormsMaskTest`)

### PRE-370 A migration plan or a calendar change is approved only on the hash the approver reviewed
- **Traces to:** `TR-SEC-04`, PD-139, #365 (AUD-02)
- **Milestone:** M3
- **Kind:** control
- **Given:** At bank-a `thu` proposes the execution of the plan loan-review v1 to v2 and the holiday 8 February 2027 for `vn-banking-hours`; `lan` holds `processes:migration:manage` and `processes:calendar:manage`; `horus.bpm.processes.approval.require-content-hash` is at its default `true`.
- **When:** `lan` sends `:approve` for each with no `expectedPlanHash` or `expectedChangeHash`; then with the hash `000...0` (64 zeros); then with the `contentHash` of the item in `GET /approvals`.
- **Then:** No hash is `400 CONTENT_HASH_REQUIRED` and another hash is `409 CONTENT_CHANGED`; both write nothing, the plan stays `PROPOSED`, the change stays `PENDING` and no instance moves. With the item's hash both are approved. Only the `dev` profile may set `require-content-hash=false`, and then a missing hash is accepted as before.
- **Example:** `POST /migrations/{id}:approve {} -> 400 CONTENT_HASH_REQUIRED; {expectedPlanHash: <planHash>} -> 200 APPROVED`
- **Test IDs:** `PRE_370_a_migration_approval_must_name_the_reviewed_plan_hash`, `PRE_370_a_calendar_change_approval_must_name_the_reviewed_change_hash`

### PRE-371 A list cursor names the key that signed it, so keys rotate and old cursors fail as 400
- **Traces to:** `TR-SEC-04`, #365 (AUD-13)
- **Milestone:** M3
- **Kind:** control
- **Given:** The host sets `horus.bpm.processes.cursor.keys` with `k2026` and `k2027` and `cursor.active-key-id=k2027`; a cursor for bank-a's instance list was signed with `k2026`.
- **When:** The cursor is replayed to a replica that has both keys; to one that dropped `k2026`; to one whose `k2027` has other bytes; a cursor of the format before key ids is replayed; the host sets a key of 8 bytes or an active id that names no key.
- **Then:** New cursors carry `k2027` and the `k2026` cursor still works while that key is listed. After `k2026` is removed, a cursor of a changed key and a cursor with no key id are `400 QUERY_INVALID`, never `500`. The short key and the unknown active id stop start-up naming the property. With no key configured the engine keeps one in `pre_engine_key` and the key id is derived from its bytes, so replacing that key retires its cursors the same way.
- **Example:** `cursor ...k2026... with keys {k2027} -> 400 QUERY_INVALID`
- **Test IDs:** `PRE_371_a_cursor_names_its_key_and_a_rotation_keeps_the_cursors_of_the_key_still_listed`, `PRE_371_a_cursor_without_a_key_id_or_of_a_changed_key_is_query_invalid`, `PRE_371_a_key_that_is_too_short_or_a_missing_active_key_is_refused`, `PRE_371_configured_keys_are_decoded_and_a_wrong_setting_names_the_property`, `PRE_371_a_start_without_cursor_keys_logs_a_warning_that_names_the_properties`

### PRE-372 A caller without the calendar manage permission sees no pending calendar change
- **Traces to:** `TR-SEC-04`, PD-139, #365 (AUD-07)
- **Milestone:** M3
- **Kind:** control
- **Given:** `thu` has a pending calendar change and a proposed migration at bank-a; `ivy` holds `processes:migration:manage` only, `kim` holds `processes:calendar:manage` only, `dan` holds `processes:read` only.
- **When:** each lists `GET /approvals`.
- **Then:** `ivy` sees the `MIGRATION_PLAN` only, `kim` the `CALENDAR_CHANGE` only and `dan` neither, as for migration plans.
- **Example:** `dan GET /approvals -> []`
- **Test IDs:** `PRE_372_a_caller_without_calendar_manage_sees_no_pending_calendar_change`

### PRE-373 The default flow of an activity is taken only when no conditional outgoing flow of it holds; flows without a condition are always taken
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A credit check `grade` (a service task) with `default="to-review"` and three outgoing flows: `to-approve` with the condition `score >= 700`, `to-review` (the default) to `manual-review`, and `to-audit` with no condition to `audit`.
- **When:** `grade` completes with `score = 720`, and in a second run with `score = 640`.
- **Then:** At 720 the flows `to-approve` and `to-audit` are taken and `manual-review` does not run. At 640 no condition holds, so `to-review` and `to-audit` are taken. A flow without a condition never counts as a condition that held. The `default` attribute of every task, sub-process and call activity is read on import and written on export; a default that is not an outgoing flow of the activity is refused as `DEFAULT_FLOW_INVALID`, and one that has a condition as `DEFAULT_FLOW_HAS_CONDITION`; both name the flow and the node in the message and report the node id. The default is part of the canonical form, so it is part of the definition hash and the graph diff (edge kind `default`): two versions that differ only in it are two versions.
- **Example:** `score = 720` -> `auto-approve` and `audit`; `score = 640` -> `manual-review` and `audit`.
- **Test ID:** `PRE_373_a_matching_conditional_flow_suppresses_the_default_flow_of_an_activity`, `PRE_373_the_default_flow_of_an_activity_is_taken_when_no_conditional_flow_matches` (`ActivityDefaultFlowAndTerminateTest`), `PRE_373_two_models_that_differ_only_in_the_default_flow_of_an_activity_have_different_hashes`, `PRE_373_the_canonical_json_and_the_bpmn_export_keep_the_default_flow_of_an_activity`, `PRE_373_the_graph_diff_shows_a_changed_default_flow_of_an_activity` (`ActivityDefaultFlowDefinitionTest`)

### PRE-374 A terminate end reached from an error boundary ends the instance with every other branch, whether it waits, sits at a join or has a step still to run
- **Traces to:** `TR-STD-04`, #493
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A loan preparation that forks into `register-collateral`, with an error boundary `collateral-flagged` on `COLLATERAL_FLAGGED` leading to a terminate end, and a valuation branch; the valuation is an open user task, a call in flight, a local step not yet run, or already waiting at the parallel join.
- **When:** The registry answers `COLLATERAL_FLAGGED` for application LA-2026-000454.
- **Then:** The valuation token is cancelled with its work item or job, the token at the join is cancelled, no token is live, the instance is `COMPLETED` and no offer is sent. A local step that has not yet run when the terminate happens is skipped in the same call, so the call does not fail with `INVALID_STATE` and the instance is not left `RUNNING`. Only the steps queued behind the terminate are skipped; a sibling local step queued before it has already run. A skipped step writes a `STEP_SKIPPED` history entry (reason `branch ended`) and no event.
- **Example:** Collateral flagged -> `end-fraud`, the valuation never runs, instance `COMPLETED`.
- **Test ID:** `PRE_374_a_terminate_end_reached_from_an_error_boundary_ends_the_instance_while_the_join_waits`, `PRE_374_a_job_in_flight_in_another_branch_is_cancelled_by_the_terminate`, `PRE_374_a_terminate_end_reached_from_an_error_boundary_ends_the_instance_when_the_other_branch_already_waits_at_the_join` (`ActivityDefaultFlowAndTerminateTest`), `PRE_374_a_terminate_reached_from_an_error_boundary_ends_the_instance_and_the_sibling_local_step_never_runs` (`BoundaryEventsPostgresIT`), `PRE_374_a_sibling_local_step_queued_before_the_terminate_runs_once_and_the_instance_still_completes`, `PRE_374_a_retried_local_step_runs_under_its_new_attempt_and_the_instance_goes_on` (`BoundaryEventsPostgresIT`)

### PRE-375 A sequence flow condition reads a field of the result a rule task stored
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A rule task `determine-authority` with `resultVariable="authority"` whose flows are `to-manager` (condition `authority.approvalTier == 'MANAGER'`) and `to-committee` (the default flow).
- **When:** The rule answers `approvalTier = MANAGER`, and in a second run `COMMITTEE`.
- **Then:** `MANAGER` opens the manager approval task only; `COMMITTEE` opens the committee task only.
- **Example:** `authority.approvalTier == 'MANAGER'` -> `approve-manager` open, `approve-committee` absent.
- **Test ID:** `PRE_375_a_sequence_flow_condition_reads_a_field_of_the_rule_task_result`, `PRE_375_the_default_flow_of_a_rule_task_leads_to_the_committee_when_the_tier_is_not_manager` (`ActivityDefaultFlowAndTerminateTest`)

### PRE-376 Several tasks may lead to one end event: each boundary path reaches it without closing the task it sits on
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Two approval user tasks, `approve-manager` (non-interrupting timer of 4 hours) and `approve-committee` (non-interrupting timer of 1 day), whose boundary flows go to the one end event `escalated`.
- **When:** The timer of the open task fires, then the approver completes the task.
- **Then:** `escalated` is reached, the task stays open and the instance `RUNNING`; completing the task completes the instance.
- **Example:** `manager-sla` fires -> `escalated` reached, `approve-manager` still `WAITING_USER`.
- **Test ID:** `PRE_376_two_tasks_whose_escalation_boundaries_share_one_end_event_each_reach_it_and_stay_open` (`ActivityDefaultFlowAndTerminateTest`)

### PRE-390 The history of a completed task records what was submitted, masked as the form says
- **Traces to:** `TR-SEC-05`, `TR-AUD-01`
- **Milestone:** M3
- **Kind:** control
- **Given:** The credit review task `approve` has a form whose field `idNumber` is `mask: true`; the officer `an` has claimed it.
- **When:** `an` completes it with `decision = REJECT`, `reason = income not verified` and `idNumber = 079012345678`, and the case's variables change afterwards.
- **Then:** The `TASK_COMPLETED` row, written in the completing transaction, holds `submission` with the validated values and `idNumber` as the marker `{"masked": true}`, so an audit tells a masked value from the text `***`; the plain id number is in no history row. A submitted field the form does not declare (`note`) is not stored: its path only is in `unrecordedFields`. `GET /instances/{id}/activities` returns `submission` on that entry and it does not change when the variables change. A task without a form, a link task and a task completed before this was recorded have no `submission`. The kernel only copies the submission, so replaying a command gives the same state and the same hash as before; no migration is needed because `details` is JSONB.
- **Example:** `submission = {decision: REJECT, reason: income not verified, idNumber: {masked: true}}`, `unrecordedFields = [note]`.
- **Test ID:** `PRE_390_the_submission_is_recorded_masked_and_later_variable_changes_do_not_alter_it`, `PRE_390_a_field_the_form_does_not_declare_is_named_but_never_stored`, `PRE_390_a_task_without_a_form_records_no_submission` (`TaskFormTest`), `PRE_027_activities_body_matches_the_contract_and_a_matching_etag_gets_304` (`ProcessRestContractTest`)

### PRE-377 Each undo step that finishes raises an event naming the handler, the step it undid and the outcome
- **Traces to:** `TR-COMP-01`, `PRE-073`, `PRE-085`
- **Milestone:** M3
- **Kind:** control
- **Given:** The fund transfer of `PRE-073`: `hold-funds` (undone by `release-hold`), `debit-source` (undone by `credit-back`) and `credit-target`, which fails with `ACCOUNT_CLOSED`. `25,000,000 VND` is moved from `0011-223344`.
- **When:** Both undo steps complete.
- **Then:**
  - `processes.compensation.step-completed.v1` is published twice, newest first: first `nodeId` `debit-source` with `handler` `credit-back`, then `nodeId` `hold-funds` with `handler` `release-hold`; each has `outcome` `COMPLETED`, the instance id, the saga `scopeId` and the undo `attemptId`.
  - Each event is published in the transaction that writes the matching `COMPENSATION_STEP_COMPLETED` history entry, and carries no variable or output. An undo an approver marks done by hand has `outcome` `COMPLETED_MANUALLY` and no `attemptId`.
  - A failed undo step publishes no such event.
- **Example:** `credit-back undoes debit-source -> step-completed(nodeId=debit-source, handler=credit-back, COMPLETED)`.
- **Test ID:** `PRE_377_each_finished_undo_step_raises_an_event_newest_first` (`SagaCompensationTest`)

### PRE-378 A duplicate of a request that is still running waits at most the lock timeout, is then refused as IDEMPOTENCY_IN_FLIGHT, and never runs the work twice
- **Traces to:** `TR-IDEM-01`, ADR-0006, #172
- **Milestone:** M3
- **Kind:** control
- **Given:** At bank-a the payment screening `PAY-2027-0412` was started with `Idempotency-Key: PAY-2027-0412` and its transaction is still open; `horus.bpm.processes.idempotency.lock-timeout` is `300ms` (the default is `2s`).
- **When:** The client sends the same request again, then once more after the first has committed.
- **Then:** The second request gives up within the lock timeout with `409 IDEMPOTENCY_IN_FLIGHT` and `Retry-After: 1`; it starts nothing and the screening has run once. The timeout covers the claim statement only, so the work after the claim keeps the database's own lock timeout. The third request returns the first answer, the same instance id.
- **Example:** `POST /instances` with `Idempotency-Key: PAY-2027-0412` while the first runs -> `409 IDEMPOTENCY_IN_FLIGHT`; after it commits -> `200` with the first instance
- **Test ID:** `PRE_378_a_duplicate_of_a_request_still_running_is_refused_within_the_lock_timeout_and_a_later_retry_replays` (`IdempotencyWaitPostgresIT`)

### PRE-379 A duplicate that waits for the first request within the lock timeout returns the same result
- **Traces to:** `TR-IDEM-01`, ADR-0006, #172
- **Milestone:** M3
- **Kind:** control
- **Given:** The screening of PRE-378 is running with `Idempotency-Key: PAY-2027-0413` and the lock timeout is `2s`; the same request arrives while it runs.
- **When:** The first request commits 400 ms later.
- **Then:** The duplicate was waiting, not refused, and now returns the instance the first created; one instance exists and the screening ran once.
- **Example:** first commits after 0.4 s -> both answers carry instance `01J...` of `PAY-2027-0413`
- **Test ID:** `PRE_379_a_duplicate_that_waits_for_the_first_within_the_timeout_returns_the_same_result_and_runs_nothing` (`IdempotencyWaitPostgresIT`)

### PRE-380 A version is compiled only when its stored hashes equal the hashes recorded at its approval
- **Traces to:** `TR-SEC-04`, ADR-0026 decision 4, #172
- **Milestone:** M3
- **Kind:** control
- **Given:** `payment-screening` v1 is approved. Someone who can update `pre_definition` replaces the source and rewrites `source_hash` and `definition_hash` of the row so that they agree with the new source.
- **When:** Anyone starts, exports or diffs that version.
- **Then:** The engine refuses it with `DEFINITION_INTEGRITY_FAILED`, "the stored hashes differ from the hashes approved", because `pre_definition_approval` holds the hashes written when the version was approved; nothing starts. A version with an approver and no approval record is refused the same way. The record is written in the transaction of the approval (and of a dev auto-approval) and the table refuses `UPDATE`, `DELETE` and `TRUNCATE`; versions approved before V2 are recorded with the hashes their row held when V2 ran. A writer who can also disable the table's triggers is outside this control.
- **Example:** source `screen-payment` swapped to `skip-screening` with matching hashes -> `DEFINITION_INTEGRITY_FAILED`
- **Test ID:** `PRE_380_a_version_whose_source_and_both_hashes_were_rewritten_is_refused_because_the_approval_record_disagrees` (`IdempotencyWaitPostgresIT`)

### PRE-381 The approval record is written with the approval, cannot be changed, and the lock timeout may be lowered only
- **Traces to:** `TR-SEC-04`, `TR-OPS-01`, #172
- **Milestone:** M3
- **Kind:** control
- **Given:** An approved version at bank-a; `horus.bpm.processes.idempotency.lock-timeout` set by the host.
- **When:** A row of `pre_definition_approval` is updated, deleted or truncated; the timeout is set to `5s` or `0s`.
- **Then:** The row exists with the hashes of the version, and each change fails with "append-only". Start-up fails naming `idempotency.lock-timeout` for a value outside 1 millisecond to 2 seconds.
- **Example:** `UPDATE pre_definition_approval SET source_hash = ...` -> `55000 append-only`; `lock-timeout=5s` -> start-up refused
- **Test IDs:** `PRE_381_the_approval_record_is_written_with_the_approval_and_cannot_be_changed` (`IdempotencyWaitPostgresIT`), `PRE_381_the_idempotency_lock_timeout_defaults_to_2_seconds_and_may_be_lowered_only` (`IdempotencyLockTimeoutSettingsTest`)

### PRE-382 A start event with isInterrupting="false" is read in every scope, and a boundary event may say it as cancelActivity="false"
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A loan review whose process and whose embedded sub-process `assess` each start on `<startEvent isInterrupting="false"/>`; a user task `approve` with a timer boundary event that says `isInterrupting="false"` instead of `cancelActivity="false"`.
- **When:** The files are read, compiled, run and exported.
- **Then:** The attribute is ignored outside an event sub-process, as BPMN says, because a process or sub-process has no other work to interrupt; the instance runs and completes as it does with a plain start. The canonical form keeps `interrupting: false`, so the hash covers it, and the export writes the attribute back. The import result carries the warning `INTERRUPTING_HAS_NO_EFFECT` naming the start event, so the user knows the attribute does nothing there; inside an event sub-process there is no warning. On a boundary event it means `cancelActivity="false"`; `isInterrupting="false"` with `cancelActivity="true"` is `422 VALIDATION_FAILED`, `BOUNDARY_INTERRUPTING_CONFLICT`. On any other element the attribute stays refused (`BPMN_UNSUPPORTED_ELEMENT`, `not planned`) because BPMN defines it on none. An event sub-process start keeps its behaviour (`PRE-320` to `PRE-326`).
- **Example:** `<startEvent id="start" isInterrupting="false"/>` in process `loan-review-nonint` -> instance `COMPLETED`, import warning `INTERRUPTING_HAS_NO_EFFECT` naming `start`; `<task isInterrupting="false"/>` -> refused, element `isInterrupting`.
- **Test ID:** `PRE_382_a_start_event_that_is_not_interrupting_runs_as_any_start_outside_an_event_sub_process` (`ConformanceSuiteTest`), `PRE_382_isInterrupting_false_on_a_start_event_outside_an_event_sub_process_is_read_and_kept`, `PRE_382_a_boundary_event_may_say_isInterrupting_false_for_cancelActivity_false_but_not_both_ways`, `PRE_382_isInterrupting_false_outside_an_event_sub_process_gives_an_import_warning_naming_the_element`, `PRE_382_isInterrupting_false_inside_an_event_sub_process_gives_no_warning`, `PRE_382_isInterrupting_false_on_an_element_that_is_neither_a_start_nor_a_boundary_event_stays_refused` (`RefusalsLiftedTest`)

### PRE-383 A compensation activity may be a user, manual, script, send, receive, business rule or abstract task or a call activity: an operator does the undo through an incident
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A loan payout with the service task `disburse` (remote handler `core-banking`), a compensation boundary event on it and an association to the user task `reverse-by-operator` marked `isForCompensation="true"`, with no assignee, result variable or form. A second file gives the same user task `horus:assignee="role:OPS"`.
- **When:** The disbursement completes with posting `FT26332001`; later the scope is undone.
- **Then:** The stack holds one entry for `disburse` whose handler is `manual`: the walk raises the `COMPENSATION_MANUAL_REQUIRED` incident that names the step, as `horus:compensate="manual"` does, and an operator resolves it after the reversal, with the existing four-eyes resolution. A manual undo is an incident, never a task: no work item is created, nobody in a role is asked, no result variable is written, and nothing is run (no script, message or child process). Content that only a run would use is therefore refused at import with `422 VALIDATION_FAILED`, `COMPENSATION_ACTIVITY_CONTENT_UNUSED`, which names the element: `horus:assignee`, `horus:resultVariable` and a form on a user task, `calledElement` on a call activity, `messageRef` on a send or receive task and a script on a script task. Creating a real task for the undo is a later feature. A service task as compensation activity still runs its handler. The compensation activity keeps its rules for every type: no sequence flow (`COMPENSATION_ACTIVITY_HAS_FLOW`) and one boundary event that points at it (`COMPENSATION_ACTIVITY_UNATTACHED`, `COMPENSATION_ACTIVITY_MISSING`). The export writes `isForCompensation="true"` on the element and the canonical form keeps `forCompensation`. A sub-process, a transaction, an ad-hoc sub-process or an element that is not an activity stays refused.
- **Example:** `userTask reverse-by-operator isForCompensation` on `disburse` -> `PushCompensation(disburse, handler "manual")`; `subProcess isForCompensation` -> refused.
- **Test ID:** `PRE_383_a_user_task_compensation_activity_is_a_manual_undo_on_the_stack` (`ConformanceSuiteTest`), `PRE_383_every_task_type_and_the_call_activity_can_be_a_compensation_activity_and_exports_back`, `PRE_383_a_manual_undo_refuses_the_content_it_would_never_run_with_a_named_code`, `PRE_383_a_sub_process_or_a_gateway_is_not_a_compensation_activity`, `PRE_383_a_compensation_activity_keeps_its_rules_whatever_its_type` (`RefusalsLiftedTest`)

### PRE-384 A receive task with instantiate="true" starts the process on the first matching message
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `loan-intake`: a receive task `receive-application` with `instantiate="true"` for the message `loan-application`, no start event and no flow in, then a review user task.
- **When:** (a) `loan-application` is correlated for `APP-9001` with the applicant `Tran Thi Mai`, then again for `APP-9001`, then for `APP-9002`. (b) The process is started without a message. (c) A message that no process starts on arrives.
- **Then:** (a) The first message creates one instance, with the key `APP-9001` as its business key and the payload as its variables; the token starts after the receive task, which never waits (no subscription), and parks at the review. The repeat returns the same instance and starts no second; another key starts another instance. This is the rule of a message start event. (b) `START_TRIGGER_REQUIRED`. (c) The message is not correlated. The compiler refuses a receive task that starts the process when the process has another start event (`START_EVENT_COUNT`), the task has a flow in (`RECEIVE_INSTANTIATE_INCOMING`), it stands in a sub-process or transaction (`RECEIVE_INSTANTIATE_SCOPE`), it names no message (`RECEIVE_INSTANTIATE_MESSAGE`), it has a boundary event (`RECEIVE_INSTANTIATE_BOUNDARY`) or a `horus:correlationKey` (`CORRELATION_KEY_UNEXPECTED`: the instance does not exist yet). `instantiate` on a send task is `INSTANTIATE_ON_SEND_TASK`. The canonical form and the export keep `instantiate`.
- **Example:** `loan-application`, key `APP-9001` -> `STARTED`, instance waits at `review`; the same key again -> the same instance id.
- **Test ID:** `PRE_384_a_receive_task_that_instantiates_starts_after_itself_and_waits_for_nothing` (`ConformanceSuiteTest`), `PRE_384_a_receive_task_that_instantiates_starts_the_process_on_the_first_message_and_a_repeat_starts_no_second` (`DataStoreAndGatewayStartPostgresIT`), `PRE_384_a_receive_task_with_instantiate_is_the_start_needs_no_key_and_exports_back`, `PRE_384_a_receive_task_that_starts_the_process_is_refused_with_a_start_a_flow_in_a_key_or_a_scope`, `PRE_384_instantiate_on_a_send_task_is_refused` (`RefusalsLiftedTest`)

### PRE-385 A service task may name its handler with implementation="urn:horus-bpm:handler:<name>"
- **Traces to:** `TR-STD-16`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A service task `kyc-check` with `implementation="urn:horus-bpm:handler:kyc.check"` and no `horus:handler`.
- **When:** The file is read and exported, and the same task is written with `horus:handler="kyc.check"`.
- **Then:** The reader binds the step to the handler `kyc.check` by name, as `horus:handler` does: the canonical form and the hash are equal for the two files, and the export writes `horus:handler` and no `implementation`. Naming the same handler in both is allowed; two different names are `IMPLEMENTATION_HANDLER_CONFLICT`; a name that is not a letter followed by letters, digits, `_`, `.` or `-` (at most 100) is `IMPLEMENTATION_HANDLER_INVALID`. The engine never loads a class or calls a service from this value. `##WebService`, any other URL or URN, and the urn form on a task that is not a service task stay refused (`BPMN_UNSUPPORTED_ELEMENT`, `not planned`).
- **Example:** `urn:horus-bpm:handler:kyc.check` -> handler `kyc.check`; `##WebService` -> refused, element `implementation`.
- **Test ID:** `PRE_385_implementation_urn_names_the_handler_and_gives_the_same_definition_as_horus_handler`, `PRE_385_two_different_names_or_an_invalid_name_are_refused`, `PRE_385_every_other_implementation_value_and_the_urn_on_other_tasks_stay_refused` (`RefusalsLiftedTest`)

### PRE-386 A file may declare an encoding other than UTF-8: ISO-8859-n, windows-125n or US-ASCII
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A process file whose declaration says `encoding="ISO-8859-1"` and whose task is named `Kiém tra ho so`, written in those bytes; the same with `windows-1252` and a euro sign; UTF-8 bytes under `US-ASCII`; a `UTF-16` declaration.
- **When:** Each is imported.
- **Then:** The bytes are decoded once, strictly, in the declared encoding and the parser reads that text, so the stored source and the parsed document never differ: the task name is `Kiém tra ho so`, and the export is UTF-8 with its own declaration. Bytes that are not valid in the declared encoding, an encoding the JVM does not know, UTF-16 and UTF-32 (which hide the markup from the declaration check), and a byte order mark with a non-UTF-8 declaration are refused with `BPMN_UNSAFE_XML`. A file with no declaration is UTF-8 as before.
- **Example:** `encoding="ISO-8859-1"`, byte `0xE9` -> `é`; `encoding="UTF-16"` -> `422 BPMN_UNSAFE_XML`.
- **Test ID:** `PRE_386_a_file_in_iso_8859_1_is_read_as_the_text_it_declares_and_exports_as_utf_8`, `PRE_386_windows_1252_and_us_ascii_declarations_are_read_and_a_mismatch_is_refused` (`ModellerFileReadingTest`), `the_declared_encoding_decides_how_the_bytes_are_read`, `an_encoding_that_hides_the_markup_or_does_not_match_the_bytes_is_refused` (`XmlHardeningTest`)

### PRE-387 A process id that is not a key, a message with no name and a lane set with no id are read; the export restores the process id
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A process `_3d1ef204-2d4c-4643-8fc5-c319cc032ec0` and a process `Loan_Review`; two processes `Loan_Review` and `loan-review` in one collaboration; messages `Message_1404332496319` and `_9f` with no name; a message `m1` named `Message_9` beside an unnamed `Message_9`; a `laneSet` with no id.
- **When:** They are imported, and the first is exported.
- **Then:** The definition key is the id in lower case with every run of other characters as one hyphen, `p-` in front when it would not start with a letter, at most 100 characters: `p-3d1ef204-2d4c-4643-8fc5-c319cc032ec0`, `loan-review`. The same id always gives the same key; a key that is already kebab-case is never changed. The reader keeps the original id with the process, the export writes it back (the process, its participant `processRef`, the diagram plane), and it is not in the canonical form, so the hash is the same. Two ids that map to one key are `422 VALIDATION_FAILED`, `PROCESS_ID_COLLISION`. A message with no `name` takes its id as the name, with other characters as `_` and `m` in front when it would not start with a letter; if another message has that name the file is refused with `MESSAGE_NAME_COLLISION`. The export writes the name it took; message ids are not restored, because the engine writes a message by its name. A lane set or lane with no id gets `laneSet_line<n>` or `lane_line<n>`.
- **Example:** `_3d1ef204-...` -> key `p-3d1ef204-...`, export `<process id="_3d1ef204-...">`; `Loan_Review` + `loan-review` -> `PROCESS_ID_COLLISION`.
- **Test ID:** `PRE_387_a_process_id_that_is_not_a_key_is_mapped_deterministically_and_the_export_restores_it`, `PRE_387_two_processes_that_map_to_one_key_are_refused_and_a_key_is_never_changed`, `PRE_387_a_message_with_no_name_takes_its_mapped_id_and_a_lane_set_without_an_id_is_read` (`ModellerFileReadingTest`)

### PRE-388 A process that does not say isExecutable is executable
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A process with no `isExecutable` attribute, and one with `isExecutable="false"`.
- **When:** Each is imported.
- **Then:** BPMN leaves the attribute open when it is absent, and a modeller that never writes it is not saying "do not run": the first imports, and `warnings` lists `EXECUTABLE_ASSUMED` so the approver sees that the attribute was assumed. `isExecutable="false"` is the author's word and stays `NOT_EXECUTABLE`; in a collaboration it is a pool kept for display. `horus:resultVariable` on a user task and `horus:correlationKey` on a message wait stay required: a result with no variable would merge into every process variable, and a message with no key could reach any instance.
- **Example:** `<process id="kyc-review">` -> imports; `isExecutable="false"` -> `NOT_EXECUTABLE`.
- **Test ID:** `PRE_388_a_process_that_does_not_say_isExecutable_is_executable_and_false_stays_refused` (`ModellerFileReadingTest`)

### PRE-389 An import and an item definition are ignored with a warning
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A file with `<import importType="http://www.w3.org/2001/XMLSchema" location="types.xsd"/>` and `<itemDefinition id="i1" structureRef="string"/>`.
- **When:** It is imported.
- **Then:** The file imports and `warnings` lists `BPMN_IMPORT_IGNORED` and `ITEM_DEFINITION_IGNORED`: the engine's variables are typed by their values and it fetches no other file. The stored source keeps both. `startQuantity` and `completionQuantity` of 1 (the default) were already accepted; any other value stays refused (a token carries no quantity).
- **Example:** `import` -> warning `BPMN_IMPORT_IGNORED`, `201`.
- **Test ID:** `PRE_389_an_import_and_an_item_definition_are_ignored_with_a_warning_and_not_refused` (`ModellerFileReadingTest`)

### PRE-391 A step with an open binding is stored as a draft that cannot be published
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** control
- **Given:** A file whose `serviceTask` has no `horus:handler` or an `implementation` that names no handler, whose `userTask` has no `horus:assignee` or `horus:resultVariable`, or whose message wait has no `horus:correlationKey`.
- **When:** It is imported, then publish is requested or approved.
- **Then:** The import stores a draft, answers `201` and lists each open binding in `warnings` (`HANDLER_MISSING`, `IMPLEMENTATION_UNBOUND`, `ASSIGNEE_INVALID`, `RESULT_VARIABLE_REQUIRED`, `CORRELATION_KEY_REQUIRED`) with the step id. The draft can be read and exported. It stays a draft under `definitions.auto-approve`. `requestApproval` and `approve` refuse it as `422 VALIDATION_FAILED` with one problem for each binding still open, found by reading the stored source again, before any registration is checked and before anything is written. A wrong value (`horus:assignee="nobody"`) is still refused at import. The strict `SubsetBpmnReader.read` refuses all of these as before. Request, second-person approval, content hash and replay are unchanged.
- **Example:** A user task with no assignee imports as `DRAFT` with `ASSIGNEE_INVALID` on `approve`; `requestApproval` answers `422` with `ASSIGNEE_INVALID` on `approve`.
- **Test ID:** `PRE_391_a_step_with_open_bindings_is_stored_as_a_draft_and_publish_names_each_one`, `PRE_391_open_bindings_keep_a_draft_a_draft_under_auto_approve_and_it_cannot_start`, `PRE_391_a_complete_definition_still_needs_a_request_and_a_second_person_to_approve` (`OpenBindingsTest`); `PRE_391_a_missing_assignee_result_variable_and_handler_are_listed_not_refused`, `PRE_391_a_message_wait_without_a_correlation_key_is_listed_and_a_wrong_value_is_still_refused` (`OpenBindingsReaderTest`); on every path that could run a draft: `PRE_391_a_call_activity_never_starts_a_draft_with_open_bindings_by_key_or_by_pinned_version`, `PRE_391_a_message_never_starts_a_draft_with_open_bindings`, `PRE_391_a_migration_cannot_target_a_draft_with_open_bindings` (`OpenBindingsTest`), `PRE_391_the_rest_publish_request_of_a_draft_with_open_bindings_is_422_and_names_each_binding` (`OpenBindingsRestIT`)

### PRE-392 A process that is not executable is a view-only draft
- **Traces to:** `TR-STD-04`
- **Milestone:** M3
- **Kind:** control
- **Given:** A file whose only process says `isExecutable="false"`, or a collaboration in which no pool is executable.
- **When:** It is imported, then publish is requested.
- **Then:** The import stores a draft (one for each process of such a collaboration) with the warning `NOT_EXECUTABLE_VIEW_ONLY`, in the `dev` profile too. `requestApproval` and `approve` refuse it with `NOT_EXECUTABLE`. In a collaboration with an executable pool the non-executable pools stay display-only as before. The strict read still answers `NOT_EXECUTABLE`.
- **Example:** `<process id="wfp-6" isExecutable="false">` -> `DRAFT`, `NOT_EXECUTABLE_VIEW_ONLY`; request for publish -> `422 NOT_EXECUTABLE`.
- **Test ID:** `PRE_392_a_process_marked_not_executable_is_a_view_only_draft_and_publish_refuses_it` (`OpenBindingsTest`); `PRE_392_a_process_that_says_it_is_not_executable_is_read_for_viewing_only` (`OpenBindingsReaderTest`)

### PRE-518 Each step the kernel applies is a span with ids, versions, codes and outcomes only
- **Traces to:** `TR-OBS-03`, `TR-SEC-05`
- **Milestone:** M3
- **Kind:** control
- **Given:** A host with a Micrometer `Tracer`, and a card application started over HTTP with business key `TRACE-APP-7731` and customer `C-002`. A second host has no tracer.
- **When:** The finished spans are read.
- **Then:**
  - Each step is one span `horus.process.step` in the trace of the request or job that ran it, with `horus.definition_key`, `horus.definition_version`, `horus.step_kind` (`START`, `COMPLETE_TASK`, `JOB_DUE`, `ASYNC_RESULT`, `CANCEL` or `LOCAL_CALL`) and `horus.outcome` (`OK`, `FAILED` or `SKIPPED`). A failed step adds `horus.error_code` and `horus.error_type` (the exception class); the span's error carries the code, never the exception message.
  - A decision evaluated by the step is a child `horus.rule.evaluate` span (`RLE-228`).
  - No span carries a business key, instance id, variable, input, output or masked value. `horus.tenant_id` is present only when `horus.bpm.tracing.tenant-attribute` is `true` (default).
  - A host without a tracer starts and runs unchanged: the engine uses a no-op port. A tracer that throws never fails or rolls back a step.
- **Example:** `POST /api/cards/applications -> horus.process.step START OK, child horus.rule.evaluate; no attribute contains TRACE-APP-7731`.
- **Test ID:** `PRE_518_a_step_is_one_span_with_the_definition_the_kind_and_the_outcome`, `PRE_518_the_tenant_attribute_can_be_turned_off_and_a_failure_records_its_code_and_type_never_its_message` (`ProcessEngineTracingTest`), `PRE_518_a_step_that_ends_on_an_Error_leaves_no_current_span_for_the_next_tenant` (`ProcessEngineTracingTest`); `PRE_518_RLE_228_a_started_application_produces_step_and_evaluation_spans_with_ids_and_codes_only` (`TracingSpansIT`)

### PRE-519 An Error thrown in a step ends its span, so no trace is stitched across tenants
- **Traces to:** `TR-OBS-03`, `TR-SEC-05`, `TR-TEN-01`
- **Milestone:** M3
- **Kind:** control
- **Given:** A tracing binding whose current span stays on the thread until the step's span ends, and a service task whose handler throws an `Error`.
- **When:** The step fails with the `Error`, then the next unit of work, of another tenant, starts on the same thread.
- **Then:** Every span that started has ended, the failed one with outcome `FAILED` and the `Error` class as its failure; no span is current on the thread; the next span has no parent.
- **Example:** `AssertionError in bank-a step -> span ended FAILED; bank-b START has parent null`.
- **Test ID:** `PRE_519_an_error_thrown_in_a_step_ends_its_span_so_the_next_unit_of_work_of_another_tenant_has_no_parent` (`ProcessServicesQaTest`)

### PRE-520 Trace export is off until a bank turns it on
- **Traces to:** `TR-OBS-03`, `TR-SEC-05`
- **Milestone:** M3
- **Kind:** control
- **Given:** The example host started with no tracing property.
- **When:** It takes traffic that makes engine spans.
- **Then:** The context holds no span exporter bean and no OTLP metrics registry, and nothing connects to the default OTLP ports 4317 and 4318. Spring Boot's OpenTelemetry starter turns on an OTLP metrics registry and an OTLP log exporter by default, each sending to `http://localhost:4318` once a minute; the example host switches both off (`HORUS_METRICS_OTLP_EXPORT_ENABLED`, `HORUS_LOGGING_OTLP_EXPORT_ENABLED`, default `false`), and the test sets the metrics step to one second so a host that sends them fails every run and not one run in sixty seconds.
- **Example:** `20 POST /api/cards/applications -> 0 connections on 4317 and 4318, with management.otlp.metrics.export.step=PT1S`.
- **Test ID:** `PRE_520_with_no_tracing_property_nothing_is_exported_and_no_exporter_connects_anywhere` (`TracingExportOffIT`)

<<<<<<< HEAD
### PRE-521 The step that applies an async result is linked to the step that scheduled the call
- **Traces to:** `TR-OBS-03`, `TR-SEC-05`
- **Milestone:** M3
- **Kind:** control
- **Given:** A host with a Micrometer `Tracer`, and a loan whose first step schedules a remote bureau call. The answer comes later, from the job runner or from `POST .../steps/{id}:complete`, in a transaction and a trace of its own.
- **When:** The answer is applied.
- **Then:**
  - The scheduling step's span gives a reference made of its trace id and span id only (`ProcessTracing.Span.link()`); the engine keeps it in the job payload as `traceLink`.
  - The span of the `ASYNC_RESULT` step, and of the `RouteToReview` step of a refused answer, is started with that reference and carries one span link to the scheduling span. No other step has a link, and a link adds no attribute.
  - A reference that is not two hex ids, a tracer that cannot link and a tracer written before links existed (the four-argument `startStep` only) give an ordinary step span; the link is never a reason to fail or roll back a step. A job written before links existed has no `traceLink` and its answer is applied as before.
  - The reference holds no business key, instance id, variable, input or output.
- **Example:** `START span 0af7...-b7ad6b7169203331 schedules the bureau call -> ASYNC_RESULT span, new trace, link to 0af7...-b7ad6b7169203331`.
- **Test ID:** `PRE_521_the_span_that_applies_an_async_result_is_linked_to_the_span_that_scheduled_the_call`, `PRE_521_the_link_is_ids_only_and_never_a_value_of_the_request`, `PRE_521_a_tracer_that_does_not_know_links_still_applies_the_result` (`AsyncSpanLinkTest`); `PRE_521_the_result_of_an_async_call_is_a_span_linked_to_the_span_that_scheduled_it`, `PRE_521_a_step_names_itself_by_trace_id_and_span_id_only_so_a_later_span_can_link_to_it`, `PRE_521_a_link_that_is_not_two_hex_ids_gives_a_plain_span_and_never_fails_the_step`, `PRE_521_a_linked_step_that_ends_on_an_Error_leaves_no_current_span` (`ProcessEngineTracingTest`)
||||||| 6d2b6920
=======
### PRE-525 Instance variables named in the configuration are encrypted at rest with AES-256-GCM, in the instance and in every store that copies them
- **Traces to:** `TR-SEC-05`, `TR-TEN-01`, PD-153, #594
- **Milestone:** M3
- **Kind:** control
- **Given:** Tenant `bank-a`, process `loan-origination`, `variable-encryption.variables = [amount]`, active key `k1`; a start with `amount = 150,000,000 VND` and `channel = BRANCH`.
- **When:** The instance is started, claimed and completed, then read; a copy of the stored `amount` is put in a row of tenant `bank-b`, in another instance, or under another variable name; one byte of its ciphertext is changed.
- **Then:** `pre_instance.variables` holds `channel` as it is and `amount` as `{"$enc":"aes-256-gcm","kid":"k1","iv":...,"ct":...}`, a fresh random 96-bit IV for every write. The instance reads back `amount` as `150,000,000 VND`; the gateway takes the same branch. The AEAD associated data is tenant, instance id, variable name and key id, so each copy, and the changed byte, fails with `500 VARIABLE_INTEGRITY_FAILED`. Encryption is opt-in: with no variable named nothing is encrypted; `variables` applies to every definition, `definitions.<key>` to one, and `*` there means every variable of that definition. A plain variable shaped like an envelope (a top-level `$enc` member) is refused with `400 INPUT_INVALID`. The value `null` is not encrypted. The list `GET /instances` never shows variables, so it does not decrypt them.
  - **Stores covered.** Whenever any variable is protected, every column that can hold a copy of a value is sealed, because the engine cannot tell which member of a payload came from a protected variable: `pre_idempotency.response_body` (one document per answer), `pre_token.locals`, `pre_step_result.result_json`, `pre_compensation_stack.input_snapshot`, `pre_start_arrival.payload`, `pre_incident.proposal_output`, `pre_task.review` and `pre_timer.payload` (the input of a remote or local step, its guard and retry counters) are each one envelope bound to the tenant and the row; in `pre_history.details` the members `submission`, `output` and `changes` are each an envelope, and the members a query reads (`taskId`, `jobId`, `attemptId`, `cause`, counters) stay as they are. An empty `{}` is not sealed, a row written before encryption was turned on is read as it is, and the engine reads every one of these through the same key rules as the instance (`VARIABLE_KEY_UNAVAILABLE`, `VARIABLE_INTEGRITY_FAILED`). A fund transfer of 25,000,000 VND from account `0011-223344` that holds the funds, debits the source, fails to credit the target and is undone leaves neither value in any column of any `pre_*` table, while the same flow with nothing protected leaves both in the stack and the jobs.
  - **Open items, stated so the claim is not wider than the code.** Not sealed, because a query or a key reads them in the clear: `pre_instance.business_key`, `pre_message_subscription.correlation_key`, the names in `pre_event_subscription`, `pre_idempotency.request_hash` (a SHA-256 of the request, which does not hide a value of low entropy), free text of an error (`pre_incident.cause_summary` and `cause` in history, written by the handler or the adapter, which must not echo a value), and the other members of `pre_history.details`. Events published through `ProcessEventPublisher` leave the engine: the host that listens decides what it stores. The re-encryption sweep (PRE-526) rewrites `pre_instance.variables`; the other stores keep the key they were written under, so an earlier key stays in the keyring while any of them holds a row written under it.
- **Example:** `amount = 150,000,000 VND -> pre_instance.variables {"amount":{"$enc":"aes-256-gcm","kid":"k1",...},"channel":"BRANCH"}`.
- **Test ID:** `PRE_525_a_protected_variable_is_stored_as_an_envelope_and_comes_back_whole`, `PRE_525_a_definition_entry_with_a_star_protects_every_variable_of_that_definition_only`, `PRE_525_two_writes_of_one_value_differ_but_both_open_to_it`, `PRE_525_a_value_copied_to_another_tenant_instance_or_variable_does_not_authenticate`, `PRE_525_a_flipped_byte_of_the_ciphertext_is_refused`, `PRE_525_a_plain_variable_shaped_like_an_envelope_is_refused`, `PRE_525_a_key_must_be_exactly_32_bytes_with_a_plain_id` (`VariableVaultTest`), `PRE_525_the_amount_is_ciphertext_in_the_table_and_whole_again_when_read`, `PRE_525_no_table_of_the_engine_holds_the_amount_after_a_start_a_claim_and_a_completion`, `PRE_525_a_row_moved_to_another_tenant_is_refused` (`VariableEncryptionPostgresIT`), `PRE_525_chosen_members_of_a_document_are_sealed_in_place_and_open_to_the_same_document`, `PRE_525_a_vault_with_no_rules_seals_no_member_and_no_document_and_an_empty_document_is_not_sealed` (`VariableVaultTest`), `PRE_525_no_column_of_any_table_holds_the_amount_after_a_remote_step_an_undo_and_the_history`, `PRE_525_without_a_protected_variable_the_same_flow_stores_the_amount_in_the_clear`, `PRE_525_a_history_member_a_start_arrival_a_step_result_and_a_job_payload_are_sealed_and_read_back`, `PRE_525_a_job_payload_is_sealed_whole_and_a_schema_retry_counts_inside_the_seal` (`VariableEncryptionCoverageIT`), `PRE_525_nothing_is_encrypted_until_a_variable_is_named`, `PRE_525_variables_definitions_and_keys_bind_from_configuration_and_the_active_key_writes` (`VariableEncryptionPropertiesTest`)

### PRE-526 A key rotates without locking anyone out, and a sweep rewrites the stored values under the current key
- **Traces to:** `TR-SEC-05`, `TR-IDEM-01`, #594
- **Milestone:** M3
- **Kind:** control
- **Given:** An instance of `loan-origination` waiting at `senior-approval` with `amount` encrypted under `k1`; the keyring now has `k2` current and `k1` earlier; an idempotent start of the same instance was answered before the rotation.
- **When:** The task is read, claimed and completed; the same start is repeated with its `Idempotency-Key`; `JdbcVariableReencryption.reencrypt("bank-a", null, 500)` runs, then `k1` is removed.
- **Then:** Every read and write works while both keys are listed; a write stores the value under `k2`. The repeat returns the first answer with the same variables. The sweep rewrites each row that holds a `k1` envelope under `k2`, one tenant and at most 500 rows per call. Every call runs in a transaction of its own (or joins the caller's), so the row locks last until the page is written; a row is locked `FOR UPDATE SKIP LOCKED` (a running instance is skipped and re-encrypts itself on its next write), the write is compare-and-set on the value that was read, and a transaction-scoped advisory lock per tenant keeps two sweepers apart: the second gets `busy` and touches nothing. A crash loses only the page in flight, and the call is resumed from the `lastId` it returned. It leaves `lock_version`, the state and every `ETag` as they were, counts the rows it could not open as `refused` and leaves them untouched, and logs the tenant and the counts, never a value; a second run rewrites nothing. After `k1` is removed the instance still reads and takes the same path; a stored answer or row that still names `k1` is `500 VARIABLE_KEY_UNAVAILABLE`. A sweep of one tenant touches no row of another. Replay is deterministic: the state the engine rebuilds from the decrypted values is equal before and after a rotation.
- **Example:** `rotate k1 -> k2, sweep: scanned 1, rewritten 1, refused 0; second sweep: scanned 1, rewritten 0`.
- **Test ID:** `PRE_526_a_value_written_under_the_old_key_opens_while_the_new_key_writes`, `PRE_526_reencrypt_moves_every_envelope_to_the_current_key_and_leaves_a_current_row_alone`, `PRE_526_a_rotation_keeps_the_values_the_engine_reads_identical` (`VariableVaultTest`), `PRE_526_a_rotation_locks_nobody_out_and_the_instance_takes_the_same_path_after_it`, `PRE_526_the_sweep_rewrites_a_waiting_instance_under_the_current_key_without_changing_its_version`, `PRE_526_a_sweep_of_one_tenant_touches_no_row_of_another`, `PRE_526_an_idempotent_replay_after_a_rotation_returns_the_first_answer_whole`, `PRE_526_the_sweep_runs_in_a_transaction_of_its_own_and_holds_its_row_locks_until_it_has_written`, `PRE_526_a_second_sweeper_of_the_same_tenant_is_told_it_is_busy_and_touches_nothing`, `PRE_526_a_sweep_resumes_from_its_cursor_and_a_row_a_writer_holds_is_left_for_the_next_run` (`VariableEncryptionPostgresIT`)

### PRE-527 A missing or wrong key is refused with a named code, and the engine never falls back to the clear
- **Traces to:** `TR-SEC-05`, `TR-SEC-08`, #594
- **Milestone:** M3
- **Kind:** control
- **Given:** `amount` is protected; an instance stores it under `k1`; a host whose keyring holds only `k2`, a host with no key, and a host that names `amount` but sets no key.
- **When:** The instance is read; a start carries `amount`; the host starts; a stored envelope is changed in the database.
- **Then:** The read is `500 VARIABLE_KEY_UNAVAILABLE` naming key `k1` and the variable, never a key or a value, and the row is untouched; the start is `500 VARIABLE_KEY_UNAVAILABLE` and stores nothing (no instance row); the host with variables named and no current key does not start (`IllegalStateException` naming `horus.bpm.processes.variable-encryption`); a key that is not Base64 of exactly 32 bytes, or an `active-key-id` that is not among the keys, stops start-up naming the property; the changed envelope is `500 VARIABLE_INTEGRITY_FAILED`. A stored envelope is opened (or refused by name) even when the rules no longer name its variable, and never returned as ciphertext; a write that would store it in the clear is refused (PRE-529). A list of instances still answers, because it carries no variables. Both codes are in `openapi.yaml` and in the error matrix.
- **Example:** `read instance 01JD2X... with only k2 -> 500 VARIABLE_KEY_UNAVAILABLE "Variable 'amount' is encrypted under key 'k1', which the keyring does not hold"`.
- **Test ID:** `PRE_527_a_key_id_the_keyring_does_not_hold_is_refused_with_its_own_code_and_no_value`, `PRE_527_a_protected_variable_with_no_current_key_is_refused_and_never_stored_in_the_clear`, `PRE_527_a_vault_without_rules_still_refuses_a_stored_envelope_instead_of_passing_it_on`, `PRE_527_a_row_that_holds_no_envelope_and_no_protected_variable_passes_untouched` (`VariableVaultTest`), `PRE_527_a_lost_key_refuses_the_read_with_a_named_code_and_the_row_is_untouched`, `PRE_527_a_start_with_a_protected_variable_and_no_current_key_stores_nothing`, `PRE_527_a_changed_ciphertext_is_refused_with_a_named_code` (`VariableEncryptionPostgresIT`), `PRE_527_a_key_that_is_not_32_bytes_or_an_active_id_that_is_missing_names_the_property`, `PRE_527_naming_a_variable_without_a_key_stops_start_up_and_never_falls_back_to_the_clear`, `PRE_527_keys_without_rules_start_a_vault_that_only_opens_what_it_finds` (`VariableEncryptionPropertiesTest`), `TR_SEC_08_every_code_of_the_error_matrix_is_a_problem_with_code_correlation_id_and_type` (`ProcessRestErrorMatrixTest`)

### PRE-528 No key and no encrypted value appears in a log line, a span, a refusal or the history of a submission
- **Traces to:** `TR-SEC-05`, `TR-OBS-03`, #594
- **Milestone:** M3
- **Kind:** control
- **Given:** `amount` protected under `k1`; the task `approve` of `loan-review-form` stores its form answer in the result variable `review`, which is protected.
- **When:** An instance is started with tracing and a log handler attached, read with a wrong keyring, swept; the review is completed with `reason = "income not verified"`.
- **Then:** No log record and no span call holds `150000000` or the bytes of a key (the sweep logs the tenant and the counts, a refusal logs the code); a refusal message names a key id and a variable name only; `Key.toString()` prints `bytes=redacted`; the `TASK_COMPLETED` history entry keeps the submission as `{"masked": true}` because the variable it lands in is encrypted, as it does for a field the form masks (without encryption it keeps the form's declared fields), and the stored member is itself an envelope (PRE-525). A decision that receives a protected variable as an input is masked by the Rule Engine's own `mask` setting on that input, not by this control.
- **Example:** `complete review {reason: "income not verified"} -> history submission {"reason": {"masked": true}, ...}`.
- **Test ID:** `PRE_528_no_key_and_no_value_appear_in_a_key_text_or_a_refusal` (`VariableVaultTest`), `PRE_528_no_log_line_and_no_span_call_holds_the_amount_or_a_key` (`VariableEncryptionPostgresIT`), `PRE_528_a_submission_that_lands_in_an_encrypted_result_variable_is_masked_in_history`, `PRE_528_without_encryption_history_keeps_the_submission_as_the_form_declares_it` (`VariableEncryptionHistoryTest`)

### PRE-529 Removing a variable from the encryption rules never decrypts it silently
- **Traces to:** `TR-SEC-05`, `TR-SEC-08`, #594
- **Milestone:** M3
- **Kind:** control
- **Given:** Instance `LN-2027-0031` waits at `senior-approval` with `amount` stored as an envelope under `k1`; the configuration is changed to name no variable, the keyring still holds `k1`.
- **When:** The senior approver completes the task, which writes the instance.
- **Then:** The write is refused with `409 VARIABLE_ENCRYPTION_RULE_REMOVED` naming the instance and the variable, never its value; the row, its `lock_version` and its envelope are unchanged, and nothing is written to the clear. The check is part of the instance's `UPDATE`, so it costs no extra read. Naming the variable again lets the same completion go through, stored as an envelope. There is no operation that turns a stored envelope back into plain text: a variable that must stop being protected is released by the bank outside the engine, under its own change control. The code is in `openapi.yaml` and in the error matrix.
- **Example:** `complete senior-approval with amount not named -> 409 VARIABLE_ENCRYPTION_RULE_REMOVED "Variable [amount] of instance 01JD2X... is stored encrypted, but the encryption rules no longer name it"`.
- **Test ID:** `PRE_529_removing_a_rule_never_stores_the_encrypted_variable_in_the_clear` (`VariableEncryptionPostgresIT`), `TR_SEC_08_every_code_of_the_error_matrix_is_a_problem_with_code_correlation_id_and_type` (`ProcessRestErrorMatrixTest`)
>>>>>>> origin/main
