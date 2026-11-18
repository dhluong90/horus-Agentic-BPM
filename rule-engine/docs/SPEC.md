# Rule Engine: Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated test named by its spec ID (`TR-QA-01`). Each statement carries the milestone that ships it, matching [`docs/product/roadmap.md`](../../docs/product/roadmap.md); a statement whose milestone has not been reached is not a release gate yet. Money values use the contract in `DESIGN.md` section 3.4.

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
- **Then:** Result fails with `UNIQUE_HIT_VIOLATION` (`422` over REST, ADR-0010; never `409`, which is reserved for state conflicts), returns **no partial outputs**, and records an audit entry with `outcome = UNIQUE_HIT_VIOLATION`.
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
- **Pending decision P1 (ADR-0019, proposed):** if FEEL three-valued null logic is accepted for M1, this statement keeps its Given, When and Then, and adds two cases: `tier != 'GOLD'` with `tier = null` matches (FEEL: `null != "GOLD"` is `true`), and `not (income < 5000000 VND)` with `income = null` does not match (FEEL: `not(null)` is `null`). See [`standards-compliance.md`](../../docs/architecture/standards-compliance.md) section 4.3.

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
- **Milestone:** M3 (replay against history moved to M3 by PD-042; simulation over sample inputs is M1, `RLE-016`)
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

---

## Decision Log Partitions, Tenancy and Host Metadata

### RLE-017 The decision log is partitioned monthly from the first migration; retention drops whole partitions only
- **Traces to:** `TR-OPS-04`, `TR-OBS-01`
- **Milestone:** M1
- **Given:** A clean database; `TestClock` at `2027-01-26T00:00:00Z`; `decision-log.partitions-ahead = P7D`. Second run: `decision-log.retention = P12M`, clock at `2028-02-03T00:00:00Z`, partitions `2027_01` to `2028_02` present. Third run: a decision row with `created_at = 2027-03-15T00:00:00Z` was inserted while no `2027_03` partition existed.
- **When:** The engine migration runs and the partition job runs in each case, with SQL captured.
- **Then:** After migration `rle_decision` is range-partitioned with `rle_decision_default`, `rle_decision_2027_01` and `rle_decision_2027_02` (7 days ahead reaches February). In the retention run only `rle_decision_2027_01` is dropped (its upper bound `2027-02-01` is before `2027-02-03`); no `DELETE` statement on `rle_decision` appears. In the third run the straggler is moved into the new `2027_03` partition before `ATTACH` and the default partition is empty afterwards. With `decision-log.retention` unset, no partition is ever dropped.
- **Example:** `retention P12M at 2028-02-03 -> drop rle_decision_2027_01 only; 0 DELETE statements`.
- **Test ID:** `RLE_017_decision_log_monthly_partitions_and_retention_by_drop`

### RLE-018 `X-Tenant-Id` is accepted only for a tenant in the token's allowed list
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** `tenancy.switching.enabled = true`; `lan`'s token has `tenant_id: bank-a` and `allowed_tenants: [bank-a, bank-a-fin]`; rule set `card-fee` exists in `bank-a-fin` and `bank-b`. Second case: switching disabled.
- **When:** `lan` calls `GET /api/bpm/rules/v1/rule-sets/card-fee` with `X-Tenant-Id: bank-a-fin`, then with `X-Tenant-Id: bank-b`; in the second case with `X-Tenant-Id: bank-a-fin`.
- **Then:** The first call returns `bank-a-fin`'s rule set; the second returns `403 TENANT_NOT_ALLOWED` with no rule set data and a security log entry naming user, requested tenant and `correlationId`; with switching disabled, a header that differs from the token tenant also returns `403 TENANT_NOT_ALLOWED`.
- **Example:** `X-Tenant-Id: bank-b -> 403 TENANT_NOT_ALLOWED, logged; bank-a-fin -> 200`.
- **Test ID:** `RLE_018_tenant_header_accepted_only_within_allowed_tenants`

### RLE-019 `/api/bpm/meta` and `/api/bpm/me` describe the host and the caller
- **Traces to:** `TR-UI-08`, `TR-SEC-08`
- **Milestone:** M1
- **Given:** A host with the rule engine starter `0.1.3`; caller `lan` holds `rules:read` and `rules:author` in tenant `bank-a`; an unauthenticated request.
- **When:** `GET /api/bpm/meta` and `GET /api/bpm/me` with `lan`'s token, and `GET /api/bpm/meta` without a token.
- **Then:** `meta` returns `schemaVersion: 1`, `modules: [{ module: rules, apiVersion: v1, engineVersion: 0.1.3, features: [...] }]` and no process entry; `me` returns user `lan`, `tenant.current: bank-a`, `tenant.allowed: [bank-a]` and exactly the two permissions; the request without a token returns `401`. Both responses validate against `cockpit/api/schema/bpm-meta.v1.schema.json` and `bpm-me.v1.schema.json`.
- **Example:** `GET /api/bpm/me -> {user: lan, tenant: {current: bank-a}, permissions: [rules:read, rules:author]}`.
- **Test ID:** `RLE_019_meta_and_me_describe_host_and_caller`

---

## Standards Foundations (ADR-0017)

### RLE-020 Import keeps the source document beside the canonical version
- **Traces to:** `TR-STD-01`, `TR-STD-03`
- **Milestone:** M1
- **Given:** The YAML file `card-fee.yaml` (1,834 bytes, with comments and keys in author order) in Git, and its RFC 8785 hash computed by the pipeline.
- **When:** It is imported with `POST /api/bpm/rules/v1/rule-sets`, then read with `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1?include=source` and without the parameter.
- **Then:** `rle_version` stores `source_format = YAML`, `source_document` byte for byte equal to the file, `source_hash` the SHA-256 of those bytes, and `canonical_hash` equal to the pipeline's hash; with `include=source` the response carries the original text including comments and its hash; without it, the canonical document only. Nothing in the source document is evaluated.
- **Example:** `import card-fee.yaml -> source_format YAML, source bytes equal, canonical_hash = pipeline hash`.
- **Test ID:** `RLE_020_import_keeps_source_document_and_canonical_hash`

### RLE-021 Every expression has a FEEL translation that gives the same result
- **Traces to:** `TR-STD-02`, `TR-DET-01`
- **Milestone:** M1 (proposed with ADR-0019; the null cases depend on decision P1)
- **Given:** jqwik generators for well-typed `horus-expr-v1` expressions over `string`, `integer`, `decimal`, `boolean`, `date`, `datetime` and lists, with inputs including `null`, and the translation table of `standards-compliance.md` section 4.4. The reference FEEL implementation runs in test scope only.
- **When:** Each expression is evaluated by the engine and its FEEL translation by the reference implementation with the same inputs and clock; 1,000 tries per property in CI, 100,000 nightly. Fixed cases: `7 / 2`, `round(2.5, 0)`, `matches('ab12', '[0-9]+')`, `daysBetween(date('2027-01-01'), date('2027-03-01'))`.
- **Then:** Results are equal for every try (money expressions excluded, since money is a HORUS extension); the fixed cases give `3.5`, `2`, `true` and `59`.
- **Example:** `matches('ab12', '[0-9]+') -> true` (unanchored search, as FEEL).
- **Test ID:** `RLE_021_v1_expressions_match_their_feel_translation`

### RLE-022 `today()` uses the rule set's time zone
- **Traces to:** `TR-DET-01`, `TR-DET-05`
- **Milestone:** M1
- **Given:** Rule set `same-day-cutoff` v1 with `timeZone: Asia/Ho_Chi_Minh` and rule `R1` (`txDate == today() -> sameDay: true`); a second version v2 without `timeZone` (default `UTC`).
- **When:** Both are evaluated with `txDate = date('2027-01-16')` and clock `2027-01-15T18:30:00Z` (01:30 on 16 January in Ho Chi Minh City).
- **Then:** v1 matches `R1` (`today()` is 2027-01-16 in the rule set's zone); v2 does not match (`today()` is 2027-01-15 in UTC); replaying either decision with its logged version and clock gives the same result.
- **Example:** `clock 2027-01-15T18:30Z, zone Asia/Ho_Chi_Minh -> today() = 2027-01-16`.
- **Test ID:** `RLE_022_today_uses_rule_set_time_zone`
