# Rule Engine: Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated test named by its spec ID (`TR-QA-01`). Each statement carries the milestone that ships it, matching `wiki/Roadmap.md`; a statement whose milestone has not been reached is not a release gate yet. Money values use the contract in `DESIGN.md` section 3.4.

---

## Evaluation & Hit Policies

### RLE-001 `first` hit policy returns top matching rule outputs
- **Traces to:** `TR-DET-01`
- **Milestone:** M1
- **Given:** Rule set `card-fee` v1 with `hitPolicy: first`, rules `R1` (`tier == 'GOLD' -> fee: 0 VND`), `R2` (`txCount >= 3 -> fee: 10000 VND`), `R3` (`{} -> fee: 50000 VND`).
- **When:** Evaluate `inputs = { tier: "GOLD", txCount: 5 }`.
- **Then:** Result succeeds (`isError = false`), `matchedRuleIds = ["R1"]`, `outputs.fee = Money.ofMinor("VND", 0)`.
- **Example:** `evaluate("card-fee", { tier: "GOLD", txCount: 5 }) -> R1, 0 VND`.
- **Test ID:** `RLE_001_first_hit_policy_returns_top_matching_rule`

### RLE-002 `unique` hit policy fails when zero or multiple rules match
- **Traces to:** `TR-DET-04`
- **Milestone:** M1
- **Given:** Rule set `loan-pricing` v1 with `hitPolicy: unique` where both `R1` (`score >= 700`) and `R2` (`income >= 20000000 VND`) overlap.
- **When:** Evaluate `inputs = { score: 750, income: 25000000 VND }` (2 matches) or `{ score: 500, income: 5000000 VND }` (0 matches).
- **Then:** Result fails with `UNIQUE_HIT_VIOLATION` (`409` over REST), returns **no partial outputs**, and records an audit entry with `outcome = UNIQUE_HIT_VIOLATION`.
- **Example:** `2 matched rules [R1, R2] -> error UNIQUE_HIT_VIOLATION, outputs = empty`.
- **Test ID:** `RLE_002_unique_hit_policy_rejects_zero_or_multiple_matches`

### RLE-003 `collect` hit policy returns ordered lists or aggregated scalars
- **Traces to:** `TR-DET-01`
- **Milestone:** M1
- **Given:** Rule set `risk-flags` v1 with `hitPolicy: collect` and outputs `flags` (`list<string>`) and `penalty` (`collect: sum`).
- **When:** Evaluate inputs matching `R1` (`flags: "HIGH_VELOCITY", penalty: 10`) and `R3` (`flags: "NEW_DEVICE", penalty: 5`).
- **Then:** `matchedRuleIds = ["R1", "R3"]`, `outputs.flags = ["HIGH_VELOCITY", "NEW_DEVICE"]`, `outputs.penalty = 15`.
- **Example:** `R1 (+10) + R3 (+5) -> penalty = 15, flags = [HIGH_VELOCITY, NEW_DEVICE]`.
- **Test ID:** `RLE_003_collect_hit_policy_aggregates_matched_rules`

---

## Expression Language, Types & Safety

### RLE-004 Publish-time parser and type-checker rejects invalid expressions
- **Traces to:** `TR-DET-02`, `TR-SEC-01`
- **Milestone:** M1
- **Given:** Draft rule set referencing unknown input `creditScoreX`, mismatched arithmetic `balance + 100` (`money + integer`), or forbidden method call `T(java.lang.Runtime).getRuntime()`.
- **When:** Call `validate` or `requestPublish`.
- **Then:** Validation fails with row ID (`R1`) and error detail; status remains `DRAFT`; version cannot transition to `PENDING_APPROVAL` or `PUBLISHED`.
- **Example:** `when: { balance: "+ 100 > 0" } -> 422 VALIDATION_FAILED at row R1`.
- **Test ID:** `RLE_004_publish_rejects_unparsable_or_ill_typed_expressions`

### RLE-005 Money currency mismatch is rejected deterministically
- **Traces to:** `TR-DET-03`
- **Milestone:** M1
- **Given:** Rule condition `balance >= 50000000 VND`.
- **When:** Evaluate with `balance = Money.ofMinor("USD", 300000)` (3000.00 USD).
- **Then:** Evaluation fails deterministically with `EVALUATION_ERROR` (`422` over REST), returns no partial outputs, and writes `outcome = EVALUATION_ERROR` to `rle_decision`.
- **Example:** `50000000 VND` vs `3000.00 USD -> EVALUATION_ERROR (currency mismatch)`.
- **Test ID:** `RLE_005_money_currency_mismatch_is_rejected`

### RLE-006 Null comparisons evaluate to false; explicit `is null` required
- **Traces to:** `TR-DET-01`
- **Milestone:** M1
- **Given:** Rule `R1` (`income < 5000000 VND`) and `R2` (`income is null`).
- **When:** Evaluate with `income = null`.
- **Then:** `R1` evaluates to `false` (does not match); `R2` matches (`matchedRuleIds = ["R2"]`).
- **Example:** `income = null -> R1 false, R2 true`.
- **Test ID:** `RLE_006_null_comparisons_are_false_unless_is_null`

### RLE-007 Regex execution is bounded by length and timeout
- **Traces to:** `TR-SEC-02`
- **Milestone:** M1
- **Given:** Rule using `matches('(a+)+$')` evaluated against adversarial input `"aaaaaaaaaaaaaaaaaaaaaaaaaaaa!"` with `regex-timeout-ms = 50`.
- **When:** Evaluate the rule set.
- **Then:** Either publish-time static check rejects nested quantifiers OR runtime aborts within `<= 50 ms` returning `EVALUATION_ERROR` (`REGEX_TIMEOUT`) without hanging the thread.
- **Example:** `28 'a' + '!' -> abort <= 50ms with EVALUATION_ERROR`.
- **Test ID:** `RLE_007_regex_timeout_aborts_adversarial_input`

---

## Versioning, Effective Dating, Determinism & Governance

### RLE-008 Explicit evaluation clock selects effective version and replays deterministically
- **Traces to:** `TR-DET-01`, `TR-DET-05`
- **Milestone:** M1
- **Given:** Tenant `bank-a` has `v1` (`effectiveFrom: 2026-11-01T00:00:00Z`, fee `50000 VND`) and `v2` (`effectiveFrom: 2026-12-01T00:00:00Z`, fee `30000 VND`), and rule uses `today()`.
- **When:** Evaluate with `clock = 2026-11-20T10:00:00Z`, then evaluate again after `2026-12-05` using `replay(decisionId)` or pinned `(v1, 2026-11-20T10:00:00Z)`.
- **Then:** Initial call selects `v1` (`50000 VND`); post-`v2` replay yields identical `v1`, `50000 VND`, and `matchedRuleIds`.
- **Example:** `clock=2026-11-20 -> v1 (50000 VND); clock=2026-12-01 -> v2 (30000 VND)`.
- **Test ID:** `RLE_008_explicit_clock_resolves_effective_version_and_replays`

### RLE-009 Maker-checker blocks self-approval in service and REST layers
- **Traces to:** `TR-SEC-04`
- **Milestone:** M1
- **Given:** User `alice` (`rules:author`, `rules:approve`) creates `card-fee` v2 and calls `:request-publish`.
- **When:** `alice` invokes `VersionService.approve(...)` directly OR calls `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve`.
- **Then:** Both Java API and REST (`403 SELF_APPROVAL_FORBIDDEN`) reject the approval; version remains `PENDING_APPROVAL`. Only distinct identity `bob` (`rules:approve`) succeeds.
- **Example:** `requester=alice, approver=alice -> 403; approver=bob -> 200 PUBLISHED`.
- **Test ID:** `RLE_009_maker_checker_blocks_self_approval`

### RLE-010 Multi-replica cache invalidates across pods on publication
- **Traces to:** `TR-DET-01`, `TR-OBS-04`
- **Milestone:** M1
- **Given:** Two independent Spring Boot replicas (`Pod A`, `Pod B`) sharing one PostgreSQL database, both holding `card-fee` v1 in local compiled cache.
- **When:** `Pod A` approves `card-fee` v2 (`effectiveFrom <= now`).
- **Then:** `Pod A` publishes `rules.version.published.v1` and notifies PostgreSQL (`LISTEN/NOTIFY` + `cache_epoch` increment); `Pod B` invalidates its local cache and evaluates `v2` immediately (or within epoch fallback bound).
- **Example:** `Pod A approves v2 -> Pod B next evaluate() returns version=2`.
- **Test ID:** `RLE_010_multi_replica_cache_invalidates_on_publish`

---

## Multi-Tenancy, Security, Audit & Idempotency

### RLE-011 Tenant isolation ignores request body tenant and prevents cross-tenant reads
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`, `TR-TEN-02`
- **Milestone:** M1
- **Given:** Authenticated security context resolves `tenantId = "tenant-a"`; rule set `secret-tier` exists only in `"tenant-b"`.
- **When:** Caller sends `POST /api/bpm/rules/v1/decisions/secret-tier:evaluate` with JSON body `{ "tenantId": "tenant-b", "inputs": {...} }` or queries `GET /decisions/{tenantBDecisionId}`.
- **Then:** Request-body `tenantId` is ignored; both evaluation and decision-log lookup return `404` under `"tenant-a"`.
- **Example:** `Caller=tenant-a, body.tenantId=tenant-b -> 404 RULE_SET_NOT_FOUND`.
- **Test ID:** `RLE_011_tenant_isolation_ignores_body_and_blocks_cross_tenant_read`

### RLE-012 Masked PII inputs are stored as keyed HMAC-SHA256 and rendered masked
- **Traces to:** `TR-SEC-05`, `TR-OBS-01`
- **Milestone:** M1
- **Given:** Input `nationalId` declared with `mask: true` and host `hmac-key` configured.
- **When:** Evaluate with `nationalId = "079090001234"`.
- **Then:** Plaintext `"079090001234"` never appears in `rle_decision.masked_inputs` or `GET /decisions/{id}` REST response; instead a deterministic keyed HMAC token (e.g. `hmac:9f4a...`) is stored so exact-match audit search still works.
- **Example:** `input="079090001234" -> DB/REST shows "hmac:<hex>", plaintext absent`.
- **Test ID:** `RLE_012_masked_inputs_stored_as_keyed_hmac`

### RLE-013 Decision log participates in host transaction rollback and adds one insert
- **Traces to:** `TR-EMB-04`, `TR-PERF-03`, `TR-OBS-01`
- **Milestone:** M1
- **Given:** `decision-log.mode = transactional`. Host service evaluates `card-fee` inside `@Transactional` and subsequently throws `RuntimeException` rolling back the host transaction.
- **When:** Transaction rolls back, and in a committed case SQL statements are counted.
- **Then:** Rolled-back transaction leaves `0` rows in `rle_decision`; committed evaluation executes **exactly 1 `INSERT`** into `rle_decision` (no `UPDATE` or `DELETE` paths exist in the engine).
- **Example:** `Host TX rollback -> rle_decision count unchanged; commit -> +1 INSERT`.
- **Test ID:** `RLE_013_transactional_decision_log_rolls_back_with_host`

### RLE-014 Idempotency key prevents duplicate mutations and rejects payload mismatch
- **Traces to:** `TR-IDEM-01`
- **Milestone:** M1
- **Given:** Caller calls `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` with `Idempotency-Key: k-101` and body `B1`.
- **When:** Caller retries with `k-101` and identical body `B1`, versus `k-101` with different body `B2`.
- **Then:** First replay returns the original `201` version response without creating `v3`; mismatched body `B2` returns `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` and creates nothing. A second request with `k-101` sent while the first is still running returns `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1`.
- **Example:** `(k-101, B1) -> 201 v2; replay (k-101, B1) -> 201 v2; (k-101, B2) -> 422 IDEMPOTENCY_KEY_REUSE_MISMATCH; (k-101, B1) concurrent with the first -> 409 IDEMPOTENCY_IN_FLIGHT`.
- **Test ID:** `RLE_014_idempotency_key_replays_identical_and_rejects_mismatch`

### RLE-015 Simulation evaluates draft against history without polluting decision log
- **Traces to:** `TR-OBS-01`
- **Milestone:** M2 (replay against history ships with Cockpit 0.2; simulation over sample inputs is M1)
- **Given:** `card-fee` has `v1` published with 100 logged decisions and `v2` in `DRAFT`.
- **When:** Caller with `rules:simulate` runs historical simulation of `v2` over those 100 decisions.
- **Then:** Engine returns diff summary (e.g. `18 outcomes changed`) and writes `0` rows to `rle_decision`. Attaching the simulation report to a publication request is M3 and is not part of this statement.
- **Example:** `Simulate v2 on 100 past decisions -> diff summary returned, 0 rle_decision inserts`.
- **Test ID:** `RLE_015_simulation_leaves_decision_log_untouched`

### RLE-016 Simulation of a draft over sample inputs returns outputs without writing decision rows
- **Traces to:** `TR-OBS-01`, `TR-DET-01`
- **Milestone:** M1
- **Given:** `card-fee` has `v1` published and `v2` in `DRAFT`; caller has `rules:simulate`.
- **When:** Caller posts `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate` with 5 sample input sets.
- **Then:** Response lists, per input set, `matchedRuleIds` and outputs for `v2` and for the effective version `v1`, flags the input sets whose outcome changed, and `rle_decision` gains `0` rows.
- **Example:** `5 samples -> 5 results, 2 marked changed, 0 rle_decision inserts`.
- **Test ID:** `RLE_016_sample_simulation_leaves_decision_log_untouched`
