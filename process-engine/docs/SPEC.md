# Process Engine — Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated test named by its spec ID (`TR-QA-01`).

---

## Instance Start, Idempotency & Concurrency

### PRE-001 Starting a process pins its effective definition version
- **Traces to:** `TR-DET-01`
- **Given:** Process `loan-origination` has `v1` active; instance `I1` starts on `v1`, then `v2` is deployed.
- **When:** Complete steps on `I1`, and start new instance `I2`.
- **Then:** `I1` executes exclusively against `v1` until completion or explicit migration; `I2` pins `v2`.
- **Example:** `I1 starts on v1 -> deploy v2 -> I1 continues on v1; I2 runs on v2`.
- **Test ID:** `PRE_001_running_instance_pins_definition_version`

### PRE-002 Business key enforces uniqueness and idempotent start returns existing instance
- **Traces to:** `TR-IDEM-01`, `TR-IDEM-02`
- **Given:** Tenant `bank-a`, process `loan-origination` v1.
- **When:** Caller posts `POST /api/bpm/processes/v1/instances` with `businessKey: "LN-2027-000123"` and `Idempotency-Key: k1`, then repeats with `businessKey: "LN-2027-000123"` (with `k1` or new key `k2`).
- **Then:** First call returns `201 Created` (`instanceId = I1`); replay returns the existing `I1` (`201` on same `k1`, `200` on new `k2`) with **only 1 instance row** in `pre_instance`.
- **Example:** `Same businessKey in tenant-a -> returns existing I1; same businessKey in tenant-b -> creates new I2`.
- **Test ID:** `PRE_002_business_key_and_idempotency_prevent_duplicate_starts`

### PRE-003 Stale task completion fails with optimistic locking conflict
- **Traces to:** `TR-IDEM-03`
- **Given:** `userTask` `T1` at `lock_version = 1`. Two operators fetch `T1` concurrently.
- **When:** Operator A completes `T1` with `version = 1` (succeeds, `lock_version -> 2`), then Operator B attempts to complete `T1` with `version = 1`.
- **Then:** Operator B receives `409 Conflict` (`VERSION_CONFLICT`) with no duplicate transition or history entry.
- **Example:** `User A complete(T1, v=1) -> 200; User B complete(T1, v=1) -> 409`.
- **Test ID:** `PRE_003_stale_task_completion_returns_conflict`

---

## Gateways, Parallel Execution & Timers

### PRE-004 Exclusive gateway routes deterministically without fallthrough ambiguity
- **Traces to:** `TR-DET-01`
- **Given:** `exclusiveGateway` with condition branches `amount >= 100000000 VND -> senior-approval`, `default -> auto-approve`.
- **When:** Instance evaluates gateway with `amount = Money("VND", 150_000_000)`.
- **Then:** Single active token advances to `senior-approval`; `auto-approve` is not activated.
- **Example:** `amount=150000000 VND -> senior-approval`.
- **Test ID:** `PRE_004_exclusive_gateway_routes_single_matching_branch`

### PRE-005 Concurrent parallel gateway join prevents lost wakeup under multi-replica race
- **Traces to:** `TR-PERF-05`
- **Given:** `parallelGateway` forks into Branch A (`credit-bureau`) and Branch B (`fraud-check`) which converge at `join-checks`.
- **When:** Pod 1 finishes Branch A and Pod 2 finishes Branch B **at the exact same millisecond**.
- **Then:** Row-level lock on `pre_instance` serializes the two join evaluations so exactly **one** post-join token advances to `underwrite` (never zero lost wakeups, never two duplicate advances).
- **Example:** `Concurrent Thread-1 (A) + Thread-2 (B) -> exactly 1 downstream token created`.
- **Test ID:** `PRE_005_parallel_gateway_join_prevents_concurrent_lost_wakeup`

### PRE-006 Replica-safe timer claims via SKIP LOCKED and recovers crashed leases
- **Traces to:** `TR-PERF-05`
- **Given:** `pre_timer` row due at `T0`. Pod 1 claims it (`status = CLAIMED`, `locked_until = T0 + 60s`) and crashes before completing.
- **When:** Pod 2 polls at `T0 + 10s` (still leased), and again at `T0 + 65s` (lease expired).
- **Then:** At `T0 + 10s`, Pod 2 skips the locked timer; at `T0 + 65s`, Pod 2 reclaims the expired lease and fires the timer once.
- **Example:** `Pod 1 crash at T0 -> Pod 2 skips at T0+10s, recovers at T0+65s`.
- **Test ID:** `PRE_006_timer_lease_recovers_after_worker_crash`

---

## Human Tasks, Four-Eyes & Business Calendar

### PRE-007 Four-eyes approval blocks requester or previous actor from completing approval task
- **Traces to:** `TR-SEC-04`
- **Given:** Process started or prior `submit-application` step completed by `alice`; next `userTask` has `fourEyes: true` (or lists `alice` in `forbidden_users`).
- **When:** `alice` tries to claim or complete the approval task via Java API or REST.
- **Then:** Engine rejects with `403 SELF_APPROVAL_FORBIDDEN`; only another authorized user (`bob`) can complete it.
- **Example:** `submit=alice, approve=alice -> 403; approve=bob -> 200`.
- **Test ID:** `PRE_007_four_eyes_blocks_same_actor_approval`

### PRE-008 BusinessCalendar SPI computes SLA deadlines across weekends and holidays
- **Traces to:** `TR-CAL-01`, `TR-CAL-02`
- **Given:** Tenant calendar `vn-banking-hours` (`Mon-Fri 08:00-17:00 Asia/Ho_Chi_Minh`, holiday `2027-01-01`). Task created `2026-12-31T15:00:00+07:00` with `sla: PT4H`.
- **When:** Engine creates the `userTask` and computes `due_at`.
- **Then:** Consumes 2 hours (`15:00-17:00` on Dec 31), skips Jan 1 holiday + weekend (Jan 2–3), and sets `due_at = 2027-01-04T10:00:00+07:00`. Later calendar edits do not mutate this already-computed `due_at`.
- **Example:** `Dec 31 15:00 + PT4H (biz) -> Jan 4 10:00 +07:00`.
- **Test ID:** `PRE_008_business_calendar_skips_holidays_and_weekends`

---

## `agentTask` Async Wait-State, Guardrails & Audit

### PRE-009 `agentTask` releases DB connections before invoking adapter (Async Wait-State)
- **Traces to:** `TR-AGT-03`, `TR-EMB-04`
- **Given:** Host configured with a 2-connection HikariCP pool; `agentTask` calls a mock adapter sleeping `2 seconds`.
- **When:** Start 10 concurrent process instances reaching `agentTask` while another thread executes normal DB queries.
- **Then:** All 10 adapters run concurrently without exhausting the 2-connection pool (`TX 1` commits claim lease `< 10 ms` before adapter sleep; `TX 2` commits output after adapter returns).
- **Example:** `10 concurrent 2s agent calls on 2-conn pool -> 0 pool timeouts, total wall time ~2.1s`.
- **Test ID:** `PRE_009_agent_task_executes_outside_db_transaction`

### PRE-010 `agentTask` validates JSON Schema and evaluates Rule Engine guardrail before advancing
- **Traces to:** `TR-AGT-01`
- **Given:** `assess-documents` `agentTask` with `outputSchema` requiring `{ riskScore: number, recommendation: string }` and guardrail rule set `agent-output-guardrails` (`riskScore >= 70 -> decision: "route-to-human"`).
- **When:** Adapter returns `{ riskScore: 85, recommendation: "APPROVE" }`.
- **Then:** Output passes schema validation, Rule Engine returns `route-to-human` with matched rule ID, and engine creates a maker-checker `userTask` carrying inputs, agent output, and matched rule IDs instead of auto-advancing.
- **Example:** `riskScore=85 -> guardrail matched R2 -> userTask created for compliance-reviewer`.
- **Test ID:** `PRE_010_agent_task_guardrail_violation_routes_to_human`

### PRE-011 `agentTask` invalid JSON output or timeout retries with backoff then raises incident
- **Traces to:** `TR-AGT-01`, `TR-AGT-03`, `TR-OPS-03`
- **Given:** `agentTask` with `timeout: PT1S`, `retry: { max: 2, backoff: PT1S }`. Adapter exceeds timeout or returns malformed JSON failing `outputSchema`.
- **When:** Initial attempt and 2 retries fail.
- **Then:** Engine retries twice and on the 3rd failure opens a `pre_incident` with `error_code = AGENT_SCHEMA_INVALID` or `AGENT_TIMEOUT`, `retry_count = 2`, pausing the token for ops retry/resolve.
- **Example:** `3 failed attempts -> 1 OPEN pre_incident with cause & retry_count=2`.
- **Test ID:** `PRE_011_agent_task_exhausted_retries_raises_incident`

### PRE-012 `agentTask` logs masked inputs, output, guardrail decision ID, and human override
- **Traces to:** `TR-AGT-02`, `TR-OBS-01`
- **Given:** `agentTask` with `nationalId` marked `mask: true` routes to human `userTask`; reviewer changes `recommendation` from `"APPROVE"` to `"REJECT"`.
- **When:** Reviewer completes the `userTask`.
- **Then:** `pre_history` records hashed `nationalId` (no plaintext), raw agent output, `guardrailDecisionId`, adapter latency, and `human_override` diff for Eval replay.
- **Example:** `Override APPROVE -> REJECT logged in pre_history with guardrailDecisionId`.
- **Test ID:** `PRE_012_agent_task_audits_masked_io_and_human_override`

---

## Saga Compensation & Instance Migration

### PRE-013 Failed step triggers reverse LIFO compensation and propagates attempt ID
- **Traces to:** `TR-COMP-01`, `TR-COMP-02`, `TR-COMP-03`, `TR-IDEM-04`
- **Given:** Process runs `reserve-funds` (comp: `releaseFunds`) → `open-account` (comp: `closeAccount`) → `notify-partner` (`onFailure: compensate`).
- **When:** `notify-partner` exhausts retries and fails.
- **Then:** Engine executes `closeAccount` first, then `releaseFunds` second (strict reverse order), passing unique `attemptId` to each compensate handler. If `compensate: manual` is reached, engine opens an incident instead of skipping.
- **Example:** `Forward [A, B, C(fail)] -> Compensate [comp(B), comp(A)] in LIFO order`.
- **Test ID:** `PRE_013_failure_executes_reverse_saga_compensation`

### PRE-014 Migration validate dry-run detects unmapped steps without mutating instances
- **Traces to:** `TR-MIG-01`
- **Given:** 500 instances running on `v1`, including 50 paused on `legacy-review` which was removed in `v2`.
- **When:** Call `POST /api/bpm/processes/v1/migrations/{id}:validate` without mapping `legacy-review`.
- **Then:** Returns dry-run report listing 50 unmapped instances at `legacy-review` and marks plan invalid; 0 rows in `pre_instance` are modified.
- **Example:** `Validate v1->v2 missing legacy-review -> conflict report, 0 instances mutated`.
- **Test ID:** `PRE_014_migration_validate_reports_unmapped_steps_without_mutation`

### PRE-015 Batched migration isolates per-instance failures without stalling healthy instances
- **Traces to:** `TR-MIG-02`
- **Given:** Validated migration plan `v1 -> v2` across 250 running instances where instance `#120` hits a transient lock conflict or mapping invariant error.
- **When:** Execute `POST /api/bpm/processes/v1/migrations/{id}:execute`.
- **Then:** 249 healthy instances pin to `v2` with `MIGRATED` `pre_history` rows; instance `#120` gets an incident without rolling back the other 249 instances.
- **Example:** `250 instances (1 corrupt) -> 249 migrated to v2, 1 incident raised`.
- **Test ID:** `PRE_015_migration_execute_isolates_instance_failure`
