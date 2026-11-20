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

### RLE-002 `unique` hit policy: one match returns it, zero matches is no match, several matches fail
- **Traces to:** `TR-DET-04`, `TR-STD-22`, PD-088
- **Milestone:** M1
- **Given:** Rule set `loan-pricing` v1 with `hitPolicy: unique`, output `rate` (decimal), rules `R1` (`score >= 700 -> rate: 0.08`) and `R2` (`income >= 20000000 VND -> rate: 0.09`), which overlap.
- **When:** Evaluate (a) `{ score: 750, income: 10000000 VND }` (1 match), (b) `{ score: 500, income: 5000000 VND }` (0 matches) and (c) `{ score: 750, income: 25000000 VND }` (2 matches).
- **Then:**
  - (a) Outcome `Matched`, `matchedRuleIds = ["R1"]`, `outputs.rate = 0.08`.
  - (b) Outcome `NoMatch` (REST `200`, `matched: false`), every declared output `null`, `matchedRuleIds = []`; one decision log row with `outcome = OK` and `matched_rule_ids = []`, exactly as for `first`. Zero matches is never `UNIQUE_HIT_VIOLATION`.
  - (c) Outcome `Failed` with `UNIQUE_HIT_VIOLATION` (`422` over REST, ADR-0010; never `409`, which is reserved for state conflicts), **no partial outputs**, `matchedRuleIds = ["R1", "R2"]` in rule order; one decision log row with `outcome = UNIQUE_HIT_VIOLATION` and `matched_rule_ids = ["R1", "R2"]`.
- **Example:** `1 match [R1] -> rate 0.08; 0 matches -> NoMatch, rate null; 2 matches [R1, R2] -> UNIQUE_HIT_VIOLATION, outputs empty`.
- **Test ID:** `RLE_002_unique_hit_policy_returns_one_match_no_match_on_zero_and_fails_on_several`

### RLE-003 `collect` hit policy returns aligned lists or aggregated scalars, with the DMN 1.5 null rules
- **Traces to:** `TR-DET-01`, `TR-STD-22`, PD-066, PD-088
- **Milestone:** M1
- **Given:** Rule set `risk-flags` v1 with `hitPolicy: collect` and outputs `flags` (`list<string>`, no aggregate), `penalty` (`integer`, `collect: sum`), `worst` (`integer`, `collect: max`) and `hits` (`integer`, `collect: count`); rules `R1` (`flags: "HIGH_VELOCITY", penalty: 10, worst: 10, hits: 1`), `R2` (`flags: null, penalty: 0, worst: 0, hits: null`) and `R3` (`flags: "NEW_DEVICE", penalty: 5, worst: 5, hits: 1`). Rule set `risk-flags-null` is the same except that `R2` has `penalty: null`.
- **When:** Evaluate `risk-flags` with (a) inputs matching `R1`, `R2` and `R3`, (b) inputs matching only `R2`, (c) inputs matching no rule; and (d) `risk-flags-null` with inputs matching `R1` and `R2`.
- **Then:**
  - **Lists (no aggregate).** One entry per matched rule, in rule order, aligned with `matchedRuleIds`; a `null` output is a `null` entry and is never dropped. (a) `matchedRuleIds = ["R1", "R2", "R3"]`, `flags = ["HIGH_VELOCITY", null, "NEW_DEVICE"]`. (b) `flags = [null]`.
  - **`count`.** The number of matched rules, `null` values included, as FEEL `count` (DMN 1.5 10.3.4.4); not a count of distinct values. (a) `hits = 3`. (b) `hits = 1`.
  - **`sum`, `min`, `max`.** Over the values of all matched rules, not distinct values (DMN 1.5 8.2.11). (a) `penalty = 15`, `worst = 10`. (b) `penalty = 0`, `worst = 0`. Money sums are exact in minor units; mixed currencies, and a sum beyond plus or minus 2^53 - 1 minor units, are `EVALUATION_ERROR` (rule engine DESIGN 3.4). `min` and `max` compare numbers, money of one currency, strings by code point, dates and date-times; `boolean` is refused at publish time.
  - **A `null` value under `sum`, `min` or `max` (d).** `null` is outside the domain of FEEL `sum`, `min` and `max`. A v1 rule set evaluates in `errorMode: strict` (PD-066), so the outcome is `Failed` with `EVALUATION_ERROR` naming the output (`penalty`) and the rule (`R2`), with no partial outputs, and the decision log row has `outcome = EVALUATION_ERROR` and `matched_rule_ids = ["R1", "R2"]`. A model in `errorMode: spec` (imported DMN from S1) gives `penalty = null` with a warning in the decision trace.
  - **No match (c).** Outcome `NoMatch`, every output `null`, lists, aggregates and `count` included (DMN 1.5 10.3.2.10: no rule matched, the result is `null`), `matchedRuleIds = []`; logged as `outcome = OK` with `matched_rule_ids = []`.
  - **Publish time.** An aggregate on a non-`collect` table, a list output on a non-`collect` table, `sum` on a non-numeric type and `count` on a non-`integer` output are `422 VALIDATION_FAILED` naming the output.
- **Example:** `R1 (+10), R2 (+0), R3 (+5) -> penalty 15, worst 10, hits 3, flags [HIGH_VELOCITY, null, NEW_DEVICE]; only R2 -> hits 1, flags [null]; null penalty under sum -> EVALUATION_ERROR (strict); no match -> NoMatch, all null`.
- **Test ID:** `RLE_003_collect_hit_policy_keeps_null_entries_and_follows_dmn_aggregation_rules`

---

## Expression Language, Types & Safety

### RLE-004 Publish-time parser and type-checker rejects invalid expressions
- **Traces to:** `TR-DET-02`, `TR-SEC-01`
- **Milestone:** M1
- **Given:** Draft rule set referencing unknown input `creditScoreX`, mismatched arithmetic `balance + 100` (`money + integer`), or forbidden method call `T(java.lang.Runtime).getRuntime()`.
- **When:** Import it, or call `validate` or `requestPublish` on a stored draft.
- **Then:** Validation fails with row ID (`R1`), column, a stable `code` (`UNKNOWN_INPUT`, `TYPE_MISMATCH`, `FORBIDDEN_CONSTRUCT`) and the character position in the cell as written; status remains `DRAFT`; version cannot transition to `PENDING_APPROVAL` or `PUBLISHED`. A forbidden construct is refused before anything inside it is bound or evaluated. Operands are checked under every operator, in conditions and in output cells computed from inputs. A table whose worst-case step count (the sum of its node counts: every condition, plus the outputs of the rules its hit policy can evaluate) is over the host's step budget is refused the same way with code `EVALUATION_BUDGET_EXCEEDED` on the first rule from which it is over, using the limits evaluation will apply, not the defaults. A cell whose tree is deeper than 64 levels, a left-associative chain such as `a + a + ...` included, is `EXPRESSION_TOO_DEEP`. Failures that depend on the input stay at evaluation and fail closed: a currency mismatch against an input, division by zero, money overflow.
- **Example:** `when: { balance: "balance + 100 > 0" } -> 422 VALIDATION_FAILED, R1, column balance, TYPE_MISMATCH at position 8 ("+ is not defined for money and number: write the number with a currency, for example 100 VND")`.
- **Test ID:** `RLE_004_publish_rejects_unparsable_or_ill_typed_expressions` (`RLE_004_*` in `ExpressionValidationTest`, `ExpressionTypeCheckPropertiesTest`)

### RLE-005 Money currency mismatch is rejected deterministically
- **Traces to:** `TR-DET-03`
- **Milestone:** M1
- **Given:** Rule condition `balance >= 50000000 VND`.
- **When:** Evaluate with `balance = Money.ofMinor("USD", 300000)` (3000.00 USD).
- **Then:** Evaluation fails deterministically with `EVALUATION_ERROR` (`422` over REST), returns no partial outputs, and writes `outcome = EVALUATION_ERROR` to `rle_decision`.
- **Example:** `50000000 VND` vs `3000.00 USD -> EVALUATION_ERROR (currency mismatch)`.
- **Test ID:** `RLE_005_money_currency_mismatch_is_rejected`

### RLE-006 Null follows FEEL three-valued logic; a condition matches only when it is `true`
- **Traces to:** `TR-DET-01`, `TR-STD-02`
- **Milestone:** M1
- **Given:** Rule set `null-check` v1 with `hitPolicy: collect`, inputs `income` (money, VND) and `tier` (string), and rules `R1` (`income < 5000000 VND`), `R2` (`income is null`), `R3` (`tier != 'GOLD'`), `R4` (`not(income < 5000000 VND)`), `R5` (`tier not in ['GOLD', 'PLATINUM']`), `R6` (`tier == 'GOLD'`), `R7` (`income < 5000000 VND or tier is null`), `R8` (`income < 5000000 VND and tier is null`).
- **When:** Evaluate with `income = null` and `tier = null`.
- **Then:** `R1` is `null` (no match); `R2` is `true` (match); `R3` is `true` (match, `null != "GOLD"` is `true` in FEEL); `R4` is `null` (no match, `not(null)` is `null`); `R5` is `true` (match, `not(null in [...])` is `not(false)`); `R6` is `false` (no match); `R7` is `true` (`null or true`); `R8` is `null` (`null and true`, no match). `matchedRuleIds = ["R2", "R3", "R5", "R7"]`; the decision is logged with outcome `OK`, and replaying it with its logged version and clock gives the same list.
- **Example:** `income = null, tier = null -> matched R2, R3, R5, R7; R1, R4, R6, R8 not matched`.
- **Test ID:** `RLE_006_null_follows_feel_three_valued_logic`

### RLE-007 Regex execution is bounded by reads, length and time
- **Traces to:** `TR-SEC-02`
- **Milestone:** M1
- **Given:** Rule using `matches('^(a+?)+?$')` evaluated against adversarial input `"aaaaaaaaaaaaaaaaaaaaaaaaaaaa!"` with the defaults `regex-max-reads = 1000000` and `regex-timeout-ms = 50`.
- **When:** Evaluate the rule set.
- **Then:** The match stops when it has read 1,000,000 characters, a deterministic budget that gives the same outcome on every machine and on replay, or at the latest after `50 ms` (the backstop on a slow machine), returning `EVALUATION_ERROR` with `REGEX_TIMEOUT` on the decision row, without hanging the thread. A text longer than `regex-max-input-length` (default 4,096) fails with `REGEX_INPUT_TOO_LONG` without running the match; a pattern whose backtracking runs out of thread stack fails with `REGEX_TOO_DEEP`. Every character any `matches` reads in one evaluation also counts against `regex-max-reads-per-decision` (default 5,000,000, lower only), the same deterministic count, so a rule set of many `matches` rows stops at that total: `EVALUATION_ERROR` with `REGEX_TIMEOUT`, no partial outputs, the reason on the decision row, and the `detail` naming `evaluation.regex-max-reads-per-decision`. A request that evaluates many decisions (`:simulate` today, `:evaluate-batch` when it is built) also counts every character its decisions read against `regex-max-reads-per-request` (default 50,000,000, 1 to 5,000,000,000): the item whose match passes it fails with `EVALUATION_ERROR` / `REGEX_TIMEOUT` and a `detail` naming `evaluation.regex-max-reads-per-request`, items before it keep their results, no item after it is evaluated (each is reported failed with the same code and a `detail` saying it was not evaluated), and the same request stops at the same item on every replay. Back-references, look-around, inline flags, possessive quantifiers, character class nesting and intersection, Unicode block names and Java-only escapes are refused at import. Regex engines differ between JVM versions; the example is one the supported JVMs run in exponential time, and the bounds apply to any pattern.
- **Example:** `28 'a' + '!' against ^(a+?)+?$ -> stopped after 1,000,000 reads, within 50 ms, EVALUATION_ERROR / REGEX_TIMEOUT`; a screening table of 200 rows `matches('[0-9]*K<n>')` on a 500-digit remittance reference, about 125,000 reads per row -> stopped at 5,000,000 reads for the decision, the same detail on every run, EVALUATION_ERROR / REGEX_TIMEOUT. A simulation of the 2-row table over 5 references (long, long, long, short, long) with a request budget of 5 rows of reads -> the first two match `CLEAR`, the third stops with REGEX_TIMEOUT naming `evaluation.regex-max-reads-per-request`, the short fourth and the fifth are not evaluated; the same on every replay (`RLE_007_a_request_stops_at_the_first_item_over_its_budget_and_runs_no_item_after_it`).
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
- **Then:** Both Java API and REST (`403 SELF_APPROVAL_FORBIDDEN`) reject the approval; version remains `PENDING_APPROVAL`. Only distinct identity `bob` (`rules:approve`) succeeds. The Rule Engine always applies `makers`: the same refusal holds when the host sets `lifecycle.approver-must-differ-from` to `requester`, and `/api/bpm/meta` reports `makers`.
- **Example:** `requester=alice, approver=alice -> 403; approver=bob -> 200 PUBLISHED`.
- **Test ID:** `RLE_009_maker_checker_blocks_self_approval`

### RLE-010 Multi-replica cache invalidates across pods on publication
- **Traces to:** `TR-ASY-04`, `TR-DET-01`, `TR-OBS-04`
- **Milestone:** M1
- **Given:** Two independent Spring Boot replicas (`Pod A`, `Pod B`) sharing one PostgreSQL database, both holding `card-fee` v1 in local compiled cache, `cache.epoch-check-interval = PT5S`. The test runs twice: with a direct connection and `cache.listen-notify = true`; and through PgBouncer in transaction mode with `cache.listen-notify = false`.
- **When:** `Pod A` approves `card-fee` v2 (`effectiveFrom <= now`); `Pod B` evaluates `card-fee` every 100 ms.
- **Then:** `Pod A` publishes `rules.version.published.v1`, increments `cache_epoch` and notifies after commit; in the direct run `Pod B` evaluates v2 within 1 s of the commit; in the pooler run, with no notification delivered, `Pod B` evaluates v2 within 5 s of the commit (the epoch sweep bound) and never after; neither run opens a session-mode connection behind the pooler.
- **Example:** `Pod A approves v2 -> Pod B returns version=2 within 1 s (NOTIFY) or within 5 s (PgBouncer, epoch sweep only)`.
- **Delivery:** the two-copy runs are `MultiReplicaCacheIT` (example host); the lost-listener failure path is `RLE_010_a_lost_listener_connection_still_refreshes_within_the_interval_and_logs_the_reconnect_once`. The pooler run allows one 100 ms evaluation step on top of the 5 s interval, because copy B polls every 100 ms.
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
- **Then:** Plaintext `"079090001234"` never appears in `rle_decision.masked_inputs` or `GET /decisions/{id}` REST response; instead a deterministic keyed HMAC token (e.g. `hmac:k1:9f4a...`) is stored so exact-match audit search still works. The stored token carries the id of the key that made it (`hmac:<keyId>:<hex>`, for example `hmac:k1:9f4a...`), so a tenant can rotate keys without stranding rows of the permanent log. An auditor with `rules:masked:search` (a permission `rules:log:read` does not imply) who posts the plain value to `POST /decisions:find-by-masked-input` finds every decision of that value, at most `masking.search.max-per-minute` times a minute per caller and replica (default 30; past it `429 RATE_LIMITED` with `Retry-After`, and the refusal is audited): the engine fingerprints it with every key the caller's tenant holds before the query, and the search writes one audit entry (`rules.decision-log.masked-input-searched.v1`: actor, tenant, rule set, input, period, number of matches, key ids, correlation id; never the value or its fingerprint). An output cell or default that reads a `mask: true` input is refused at import, and one that reads an input masked by the tenant default fails the decision closed (`error_code = MASKED_INPUT_IN_OUTPUT`), so the plain value never reaches `outputs`. Each tenant may hold its own key, so tenants `bank-a` and `bank-b` store different fingerprints of one value and neither tenant's read or search returns the other's decisions. A tenant without a key never logs a masked input in plain text: the decision fails closed with `EVALUATION_ERROR` (row `error_code = MASKING_KEY_MISSING`, no input logged). Rotation (#281): given tenant `bank-a` with current key `k2` and previous key `k1` (`masking.previous-keys`, or `masking.tenants.<tenant>.previous-keys`), and one decision about `079090001234` under each key, an auditor's search for that value finds both decisions, and new decisions are stored as `hmac:k2:<hex>`; a previous key is used for search only, never for writing, and a key no longer listed is no longer searched. Moving off the host key (#281): given `bank-a` wrote a decision about `079090001234` under its subkey of host key `k1`, when it gets its own key `a1`, new rows are `hmac:a1:<hex>` and the search still finds the earlier decision through its subkey of `k1` until `k1` is retired.
- **Example:** `input="079090001234" -> DB/REST shows "hmac:<keyId>:<hex>", plaintext absent; two decisions of 079090001234 -> both found by the plain value; bank-b's search finds none of bank-a's; current k2 + previous k1 -> decisions under k1 and k2 both found, new rows hmac:k2:<hex>`.
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
- **Delivery:** creation ahead and the overflow case are tested by `RLE_017_*` in `DecisionLogPartitionMaintenanceIT` and `DecisionLogSearchIT`; the retention run lands with the retention slice (RE-F-31), and until then nothing is dropped.
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
- **Given:** The YAML file `card-fee.yaml` (1,834 bytes, with comments and keys in author order) in Git, and the SHA-256 of its committed blob computed by the pipeline (`git show HEAD:card-fee.yaml | sha256sum`).
- **When:** It is imported with `POST /api/bpm/rules/v1/rule-sets`, then read with `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1?include=source` and without the parameter.
- **Then:** `rle_version` stores `source_format = YAML`, `source_document` byte for byte equal to the file, `source_hash` the SHA-256 of the raw request bytes, equal to the pipeline's hash, and `canonical_hash` the SHA-256 of the RFC 8785 form of the canonical document written from the model, with cells in their normal form (ADR-0026); with `include=source` the response carries the original text including comments and its hash; without it, the canonical document only. Nothing in the source document is evaluated.
- **Example:** `import card-fee.yaml -> source_format YAML, source bytes equal, source_hash = sha256sum, canonical_hash = hash of the printed model`.
- **Test ID:** `RLE_020_import_keeps_source_document_and_canonical_hash`

### RLE-021 Every expression has a FEEL translation that gives the same result
- **Traces to:** `TR-STD-02`, `TR-DET-01`
- **Milestone:** M1 (ADR-0019; null cases as `RLE-006`, PD-058)
- **Given:** Seeded generators (the project's own, PD-090; a failure prints its seed) for well-typed `horus-expr-v1` expressions over `string`, `integer`, `decimal`, `boolean`, `date`, `datetime` and lists, with inputs including `null`, and the translation table of `standards-compliance.md` section 4.4. The reference FEEL implementation runs in test scope only.
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

### RLE-023 Engine JSON is the engine's own: host Jackson settings change no engine payload
- **Traces to:** `TR-EMB-07`, `TR-API-06`
- **Milestone:** M1
- **Given:** The example host with the Rule Engine starter and REST module, rule set `card-fee` v1 published, and host properties `spring.jackson.property-naming-strategy=SNAKE_CASE` and `spring.jackson.serialization.write-dates-as-timestamps=true`; the test runs in the Spring Boot 4.x leg and in the Spring Boot 3.5 leg, where the host's Jackson 2 stays at the version its dependency management pins.
- **When:** `card-fee.yaml` is imported with `POST /api/bpm/rules/v1/rule-sets`, then `POST /api/bpm/rules/v1/decisions/card-fee:evaluate` is sent with body `{ "inputs": { "tier": "GOLD", "txCount": 5 } }`.
- **Then:** Both requests succeed; the evaluate response keys are camelCase (`matchedRuleIds`, `decisionId`, `evaluatedAt`), `evaluatedAt` is an ISO 8601 string, and `fee` is `{ "currency": "VND", "amount": 0 }`; the host's own controllers still answer in snake case; in the 3.5 leg the resolved dependency tree has `tools.jackson` artifacts from the engine and the host's `com.fasterxml.jackson` artifacts at the host's pinned version, with no override in the host build.
- **Example:** `host SNAKE_CASE -> engine response {"matchedRuleIds":["R1"],"outputs":{"fee":{"currency":"VND","amount":0}}}`.
- **Test ID:** `RLE_023_engine_json_ignores_host_jackson_settings`

### RLE-024 A v1 rule set reads into the DMN model and writes back to the same canonical JSON
- **Traces to:** `TR-STD-28`, `TR-STD-03`
- **Milestone:** M1
- **Given:** `card-fee.yaml` (v1, `hitPolicy: first`, inputs `tier` and `txCount`, output `fee`, rules `R1` to `R3`, every cell already in normal form) and the RFC 8785 hash of that document.
- **When:** The v1 reader builds the in-memory model and the canonical writer prints it back.
- **Then:** The model is one `Definitions` named `card-fee` holding one `Decision` `card-fee` whose `DecisionTable` has hit policy `FIRST`, input clauses `tier` and `txCount`, output clause `fee` and rules with ids `R1`, `R2`, `R3` in document order; the printed canonical JSON is byte for byte equal to the RFC 8785 form of the document (it is already in normal form) and its SHA-256 equals that hash and the stored `canonical_hash`.
- **Example:** `card-fee.yaml -> Definitions(card-fee) / Decision(card-fee) / DecisionTable(FIRST, 2 inputs, 1 output, 3 rules) -> same hash`.
- **Test ID:** `RLE_024_v1_rule_set_round_trips_through_dmn_model`

---

## Version Lifecycle: Reject, Retire and Diff (RE-F-07, RE-F-08)

### RLE-025 An approver rejects a pending version with a reason and it returns to draft
- **Traces to:** `TR-OBS-04`, `TR-IDEM-01`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v2 is `PENDING_APPROVAL` with `lockVersion 3`, requested by `alice`; `bob` holds `rules:approve`.
- **When:** `bob` sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:reject` with `Idempotency-Key: k-201`, `If-Match: "3"` and body `{ "reason": "R2 waiver needs pricing committee minutes" }`. Failure cases: the same call with body `{ "reason": "" }`; with `If-Match: "2"`; and a reject of `card-fee` v1, which is `PUBLISHED`. Self-reject case: `fx-margin` v4 is `PENDING_APPROVAL`, requested by `bob`, and `bob` rejects it with reason `"Wrong margin table attached"`.
- **Then:** `200`; v2 is `DRAFT` with `lockVersion 4`, `requested_by` and `requested_at` cleared, and one `rle_version_history` row `REJECTED` with actor `bob`, the reason, statuses `PENDING_APPROVAL` to `DRAFT` and `lockVersion 4` (`GET .../versions/2/history` returns it after the `CREATED` and `REQUESTED` rows); one `rules.version.rejected.v1` event carries rule set, version, tenant, actor `bob`, reason and timestamp; `alice` can edit v2 again and request publication again. A replay with `k-201` and the same body returns the same `200` and writes no second event. The empty reason returns `400 INPUT_INVALID` naming `reason`; the stale `If-Match` returns `409 VERSION_CONFLICT`; the reject of v1 returns `409 INVALID_STATE`. In each failure case the version, its history and the event count are unchanged. The self-reject returns `403 SELF_APPROVAL_FORBIDDEN` with a detail pointing to `:withdraw`, and `fx-margin` v4 stays `PENDING_APPROVAL` (PD-089; the requester withdraws instead, `RLE-046`).
- **Example:** `bob rejects card-fee v2 with a reason -> DRAFT, lockVersion 4, 1 rejected event; reason "" -> 400 INPUT_INVALID; v1 PUBLISHED -> 409 INVALID_STATE`.
- **Test ID:** `RLE_025_reject_returns_pending_version_to_draft_with_reason`

### RLE-046 The requester withdraws their own pending request, and it returns to draft without a rejection
- **Traces to:** `TR-OBS-04`, `TR-IDEM-01`, `TR-SEC-04`, PD-089
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v2 is `PENDING_APPROVAL` with `lockVersion 3`, requested by `alice`, who holds `rules:author` only; `card-fee` v1 is `PUBLISHED` and in effect; `bob` holds `rules:approve`; `carol` holds `rules:author`.
- **When:** `alice` sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:withdraw` with `Idempotency-Key: k-301`, `If-Match: "3"` and body `{ "reason": "Wrong fee for GOLD" }`; then she edits v2 and requests publication again. Failure cases: `bob` withdraws v2; `carol` withdraws v2; `alice` withdraws with `If-Match: "2"`; `alice` withdraws `card-fee` v1 (`PUBLISHED`); `alice` withdraws with a 2,001-character reason.
- **Then:**
  - `200`; v2 is `DRAFT` with `lockVersion 4`, and `requested_by` and `requested_at` are cleared.
  - One `rle_version_history` row `WITHDRAWN` is written, with actor `alice`, the reason, and statuses `PENDING_APPROVAL` to `DRAFT`.
  - One `rules.version.withdrawn.v1` event carries rule set, version, tenant, `withdrawnBy: alice`, the reason and the timestamp. No `rules.version.rejected.v1` event is written.
  - Evaluation still selects v1, and nothing is published.
  - A replay with `k-301` and the same body returns the same `200` and writes no second event. A body without `reason` is also accepted, with `reason` null in the history row and the event.
  - The new request publication starts a new approval: a new `REQUESTED` history row, and `bob` may approve it.
  - `bob` and `carol` get `403 PERMISSION_DENIED` (an approver uses `:reject`). The stale `If-Match` returns `409 VERSION_CONFLICT`. The withdrawal of v1 returns `409 INVALID_STATE`. The long reason returns `400 INPUT_INVALID` naming `reason`. In each failure case the version, its history and the event count are unchanged.
  - `allowedActions` on v2, read by `alice` while it is pending, contains `withdraw` and not `approve` or `reject`.
- **Example:** `alice withdraws her own pending v2 -> DRAFT, WITHDRAWN row, withdrawn event, v1 still in effect; bob or carol withdraws -> 403; stale If-Match -> 409`.
- **Test ID:** `RLE_046_requester_withdraws_own_pending_request_to_draft`

### RLE-026 Retiring a published version by hand stops every replica serving it
- **Traces to:** `TR-OBS-04`, `TR-ASY-04`, `TR-DET-05`, PD-101
- **Milestone:** M1
- **Given:** Tenant `bank-a`; rule set `tet-fee-waiver` has only v1, `PUBLISHED`, effective since `2027-01-20T00:00:00Z`, `lockVersion 1`; decision `D1` was logged against v1 at `2027-02-01T09:00:00Z` with output `waiveFee: true`; two replicas hold v1 compiled; `bob` holds `rules:approve`; `lifecycle.retire-approval` is `single` and `lifecycle.retire-last-version` is `confirm`, the defaults, so v1 is the last version in effect. The emergency single-approver path (PD-012) is not configured; it is not an M1 feature.
- **When:** At `2027-02-17T08:00:00Z` `bob` sends `POST /api/bpm/rules/v1/rule-sets/tet-fee-waiver/versions/1:retire` with `Idempotency-Key: k-301`, `If-Match: "1"` and body `{ "reason": "Tet promotion ended", "confirmNoVersionInEffect": true }`; 5 s later each replica evaluates `tet-fee-waiver` with the host clock; then `replay(D1)` runs, and a caller with `rules:simulate` evaluates `tet-fee-waiver` with `clock = 2027-02-01T09:00:00Z`. Failure cases: a retire of a `DRAFT` version; a retire with body `{ "reason": "" }`; the same retire of v1 without `confirmNoVersionInEffect`; and, on a host with `lifecycle.retire-last-version: refuse`, the retire with the confirmation.
- **Then:** `200`; v1 is `RETIRED` with `retired_at = 2027-02-17T08:00:00Z` and one history row `RETIRED` (actor `bob`, the reason); one `rules.version.retired.v1` event carries rule set, version, tenant, actor `bob`, the reason, the timestamp and `noVersionInEffect: true`, and the history row stores `no_version_in_effect = true`; the same transaction increments the cache epoch and notifies after commit; within `cache.epoch-check-interval` (5 s) both replicas return `404 NO_EFFECTIVE_VERSION` for the evaluation. `replay(D1)` still returns v1 and `waiveFee: true`, because a retired version stays readable for explain and replay. The evaluation at `clock = 2027-02-01T09:00:00Z` selects v1 and returns `waiveFee: true`, because v1 was effective at that instant (selection reads `retired_at`, `DESIGN.md` section 3.3.1). The retire of a `DRAFT` returns `409 INVALID_STATE`, the empty reason `400 INPUT_INVALID`. The retire without the confirmation returns `409 LAST_VERSION_IN_EFFECT` with `ruleSet: tet-fee-waiver`, `version: 1`, `setting: confirm`, `nextEffectiveFrom: null` and a detail saying that evaluations will fail until a new version is approved. Under `refuse` the confirmed retire returns the same code with `setting: refuse`. None of the refusals changes anything. No second approver is needed in the default `single` mode (ADR-0025, PD-080); the event and the history row record the mode `single`.
- **Example:** `retire tet-fee-waiver v1 without confirmNoVersionInEffect -> 409 LAST_VERSION_IN_EFFECT; with it -> RETIRED, 1 retired event, noVersionInEffect true; evaluate after 5 s on either replica -> 404 NO_EFFECTIVE_VERSION; replay(D1) -> v1, waiveFee true`.
- **Test ID:** `RLE_026_manual_retire_stops_every_replica_serving_the_version`

### RLE-027 A published version retires on its own when a newer version becomes effective
- **Traces to:** `TR-OBS-04`, `TR-DET-01`, `TR-OPS-05`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v1 `PUBLISHED`, effective since `2026-11-01T00:00:00Z`; `card-fee` v2 approved at `2026-11-25T10:00:00Z` with `effectiveFrom: 2026-12-01T00:00:00Z`; two replicas share one database; `TestClock` drives both. Second case: rule set `fx-margin` v3 `PUBLISHED` and v4 approved with `effectiveFrom` equal to the approval instant.
- **When:** The clock moves from `2026-11-30T23:59:59Z` to `2026-12-01T00:00:01Z`; each replica evaluates `card-fee`; then the lifecycle sweep (`DESIGN.md` section 3.3.2) runs on both replicas at the same moment, through PgBouncer in transaction mode in the pooler leg. In the second case v4's approval commits.
- **Then:** At `2026-11-30T23:59:59Z` v1 is `PUBLISHED` and evaluation selects v1. At `2026-12-01T00:00:01Z` evaluation selects v2 on both replicas whether or not v1's status has changed yet, because selection follows the effective date (`RLE-008`). After the maintenance run v1 is `RETIRED` and exactly one `rules.version.retired.v1` event exists for it, with actor `system:supersession`, reason `SUPERSEDED` and `supersededBy: 2`, and exactly one history row `RETIRED`, although both replicas ran; no advisory lock was taken. An evaluation of v1's decision with `clock = 2026-11-20T10:00:00Z` still selects v1. In the second case v3 is `RETIRED` in the transaction that publishes v4, with one retired event.
- **Example:** `clock 2026-12-01T00:00:01Z -> evaluate selects v2; after the sweep on 2 replicas -> v1 RETIRED, 1 retired event (actor system, reason SUPERSEDED)`.
- **Test ID:** `RLE_027_previous_version_retires_once_when_newer_becomes_effective`

### RLE-028 The engine returns a row-level diff between two versions of a rule set
- **Traces to:** `TR-UI-02`, `TR-STD-28`, `TR-STD-03`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v1 `PUBLISHED` with rows `R1` (`tier == 'GOLD' -> fee: 0 VND`), `R2` (`txCount >= 3 -> fee: 30000 VND`), `R3` (`{} -> fee: 50000 VND`); v2 `PENDING_APPROVAL` with `R1` unchanged, `R2` output changed to `fee: 0 VND`, new row `R6` (`tier == 'PLATINUM' -> fee: 0 VND`) inserted before `R3`, and `R3` unchanged; v3 `DRAFT` imported from a YAML file that differs from v2's source only in comments and in `effectiveFrom`; v4 `DRAFT` with the same rows as v2 but `R2` placed before `R1`. Caller `bob` holds `rules:read`.
- **When:** `bob` sends `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff?base=1`, then `.../versions/3/diff?base=2` and `.../versions/4/diff?base=2`. Failure cases: `base=9`, which does not exist; no `base`; a caller of tenant `bank-b`.
- **Then:** The v2 against v1 diff lists `R2` as `CHANGED` with the field `/then/fee` from `{ "currency": "VND", "amount": 30000 }` to `{ "currency": "VND", "amount": 0 }`, `R6` as `ADDED` at position 3, no `REMOVED` row, no `MOVED` row (the relative order of `R1`, `R2` and `R3` is unchanged), `unchangedCount: 2`, no change to hit policy, inputs or outputs, and both canonical hashes. The v3 against v2 diff has no row or header change, reports equal canonical hashes, `sourceHashChanged: true` and the `effectiveFrom` change as metadata only, because the diff compares the canonical models (`RLE-024`), not the source text. The v4 against v2 diff lists only `R1` as `MOVED` from `basePosition` 1 to `position` 2: the moved set is the common rows outside the longest common subsequence, ties broken by keeping the earlier rows of v4, so `R2`, `R6` and `R3` are not moved. Repeating a request returns a byte-identical body. `base=9` returns `404 VERSION_NOT_FOUND`; no `base` returns `400 INPUT_INVALID`; the `bank-b` caller gets `404 RULE_SET_NOT_FOUND`. The diff writes nothing.
- **Example:** `diff v2 against v1 -> R2 CHANGED (/then/fee 30000 VND -> 0 VND), R6 ADDED at 3, unchangedCount 2; v3 against v2 -> no change, canonical hashes equal; v4 against v2 -> R1 MOVED 1 -> 2`.
- **Test ID:** `RLE_028_version_diff_lists_added_changed_removed_and_moved_rows`

---

## Decision Log Search and Explanation (RE-F-17, RE-F-18)

### RLE-029 An auditor lists one rule set's decisions for a period of at most 31 days
- **Traces to:** `TR-OBS-01`, `TR-SEC-05`, `TR-TEN-01`
- **Milestone:** M1 (rule set and period only; outcome, rule id and output value filters are the M1 cut candidate, PD-031)
- **Given:** Tenant `bank-a` has 250 decisions of `card-fee` between `2027-01-01T00:00:00Z` and `2027-01-31T23:00:00Z`, each with masked input `nationalId`, and 40 decisions of `kyc-risk` in the same month; tenant `bank-b` has 10 decisions of its own `card-fee`. Caller `dan` holds `rules:log:read` in `bank-a`.
- **When:** `dan` sends `GET /api/bpm/rules/v1/decisions?ruleSet=card-fee&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z`, then follows the returned cursor. Failure cases: `from=2027-01-01T00:00:00Z&to=2027-02-02T00:00:00Z` (32 days); no `ruleSet`; the same first query by a `bank-b` caller; a caller without `rules:log:read`.
- **Then:** The first page holds 200 `card-fee` decisions of `bank-a`, newest first, and a cursor; the second page holds the other 50 and no cursor; no `kyc-risk` or `bank-b` row appears; every `nationalId` is an `hmac:<keyId>:<hex>` value, never plain text; the query plan reads at most 2 monthly partitions. The 32-day range and the missing rule set return `400 QUERY_INVALID` naming the parameter; the `bank-b` caller gets its own 10 decisions only; the caller without the permission gets `403`. The search writes nothing.
- **Example:** `card-fee, January 2027 -> page 1: 200 rows + cursor; page 2: 50 rows; 32 days -> 400 QUERY_INVALID`.
- **Test ID:** `RLE_029_decision_search_by_rule_set_and_period_is_bounded`

### RLE-030 A decision is explained in plain language from its log entry and its version
- **Traces to:** `TR-OBS-05`, `TR-DET-05`; PD-027 option 1, decided 2026-11-19
- **Milestone:** M1 (generated explanation, PD-027 option 1; author templates are M3, RE-F-19)
- **Given:** Tenant `bank-a`; `card-fee` v1 (`hitPolicy: first`) with `R1` (`tier == 'GOLD' -> fee: 0 VND`), `R2` (`txCount >= 3 -> fee: 10000 VND`), `R3` (`{} -> fee: 50000 VND`); decision `D7` logged with inputs `tier: "SILVER"`, `txCount: 5`, `matchedRuleIds: ["R2"]`, output `fee: 10000 VND`, clock `2026-11-20T10:00:00Z`. `risk-flags` v1 (`hitPolicy: collect`) and decision `D8` with `matchedRuleIds: ["R1", "R3"]` as in `RLE-003`. v2 of `card-fee` was published after both decisions. Caller `dan` holds `rules:log:read`.
- **When:** `dan` sends `GET /api/bpm/rules/v1/decisions/D7/explain`, again, and `GET /api/bpm/rules/v1/decisions/D8/explain`. Failure cases: an unknown id; `D7` requested by a caller of tenant `bank-b`.
- **Then:** The `D7` response names rule set `card-fee`, version 1, hit policy `FIRST`, outcome `OK` and evaluation clock; lists matched row `R2` with one condition entry (input `txCount`, recorded value `5`, test `>= 3`, result `true`) and its output `fee` `{ "currency": "VND", "amount": 10000 }`; carries `reasons` `[ { "code": "RULE_MATCHED", "params": { "ruleId": "R2", "version": 1 } } ]`, which the cockpit renders in English or Vietnamese; and carries the English `text`, with numbers written without grouping and money in major units with its currency code, "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Result: fee 10000 VND." The second request returns a byte-identical body. The explanation is built from the decision row and the stored version 1 only: v2 does not change it, nothing is re-evaluated and `rle_decision` gains no row. The `D8` response lists `R1` and `R3` in order with their conditions and the aggregated outputs `flags: [HIGH_VELOCITY, NEW_DEVICE]` and `penalty: 15`. The unknown id and the `bank-b` caller get `404 DECISION_NOT_FOUND`.
- **Example:** `explain D7 -> "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Result: fee 10000 VND."`
- **Test ID:** `RLE_030_explanation_is_generated_from_log_entry_and_version`

### RLE-031 Explanations keep masked inputs masked and explain no match, null inputs and failures
- **Traces to:** `TR-OBS-05`, `TR-SEC-05`, `TR-STD-02`; PD-027 option 1, decided 2026-11-19
- **Milestone:** M1
- **Given:** Tenant `bank-a`. Rule set `minor-review` v1 (`hitPolicy: first`) with input `nationalId` (`mask: true`) and `age`, rows `R1` (`nationalId is not null and age < 18 -> route: "MANUAL"`) and `R2` (`age >= 18 -> route: "AUTO"`); decision `D11` with `nationalId = "079090001234"` and `age = 16`. Decision `D12` of `card-fee` v1 with no matched row (a version without the catch-all `R3`). Decision `D13` of `null-check` v1 (`RLE-006`) with `income = null`, `tier = null`. Decision `D14` of `loan-pricing` v1 with outcome `UNIQUE_HIT_VIOLATION` and `matchedRuleIds: ["R1", "R2"]` (`RLE-002`).
- **When:** `GET /api/bpm/rules/v1/decisions/{id}/explain` for `D11` to `D14`, with log capture on.
- **Then:** `D11`: the condition on `nationalId` shows the recorded value as `hmac:<keyId>:<hex>` with `masked: true`, reason `VALUE_MASKED` and the word "masked" in `text`, the condition on `age` shows `16` against `< 18`, and `079090001234` appears in no response field and no log line. `D12`: "No rule of card-fee version 1 matched. No output was produced." `D13`: the `R2` condition has `missing: true`, reason `VALUE_MISSING` and reads "income has no value", and no row is reported as matched through a condition whose result was `null`. No row that did not match is explained, and no expression is evaluated. `D14`: outcome `UNIQUE_HIT_VIOLATION`, `matched` lists `R1` and `R2` from the stored `matched_rule_ids`, "Rules R1 and R2 of loan-pricing version 1 both matched; this table allows one. No output was produced."
- **Example:** `explain D11 -> nationalId shown as hmac:k1:9f4a... (masked), age 16 < 18, route MANUAL; plain value in 0 fields and 0 log lines`.
- **Test ID:** `RLE_031_explanation_masks_inputs_and_covers_no_match_null_and_failure`

---

## Import and Export (RE-F-25)

### RLE-032 An exported version imports back as a draft with the same canonical hash
- **Traces to:** `TR-STD-03`, `TR-STD-28`, `TR-UI-02`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v1 `PUBLISHED`, imported from `card-fee.yaml` with comments and `effectiveFrom: 2026-11-01T00:00:00Z`, canonical hash `H1` (computed without `effectiveFrom`); caller `lan` holds `rules:read` and `rules:author` in `bank-a` and in `bank-a-fin`.
- **When:** `lan` exports v1 with `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1/export?format=json` and with `format=yaml`; imports the JSON file with `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` in `bank-a`; and imports the YAML file with `POST /api/bpm/rules/v1/rule-sets` in `bank-a-fin`, where no `card-fee` exists. Failure cases: `format=xlsx`; version 9.
- **Then:** The JSON export is the RFC 8785 canonical document written from the DMN model (`RLE-024`), byte for byte, with a `Content-Disposition` file name `card-fee-v1.json`; the YAML export holds the same model. Neither file carries tenant, status, `lockVersion`, `effectiveFrom`, requester, approver or decision data. The JSON import creates `card-fee` v2 as `DRAFT` in `bank-a` with canonical hash `H1`; the YAML import creates `card-fee` v1 as `DRAFT` in `bank-a-fin` with canonical hash `H1`. Each import stores its own file as the source document (`RLE-020`); the original comments stay available on v1 through `?include=source`. Both drafts have no `effective_from` until `:request-publish` sets one. `format=xlsx` returns `400 INPUT_INVALID`; version 9 returns `404 VERSION_NOT_FOUND`. Export writes nothing.
- **Example:** `export card-fee v1 as JSON -> import into bank-a as v2 DRAFT, canonical hash H1 = H1; YAML into bank-a-fin -> v1 DRAFT, hash H1`.
- **Test ID:** `RLE_032_export_round_trips_to_same_canonical_hash`

### RLE-033 Importing a file as a new version creates the next draft; a mismatched file is refused whole
- **Traces to:** `TR-STD-01`, `TR-IDEM-01`, `TR-UI-02`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` has v1 `PUBLISHED` and v2 `DRAFT`; caller `lan` holds `rules:author`. Files: `card-fee-v3.yaml` (valid, `ruleSet: card-fee`); `kyc-risk.yaml` (`ruleSet: kyc-risk`); `card-fee-bad.yaml` whose row `R4` has `balance >= 50000000` with no currency.
- **When:** `lan` posts `card-fee-v3.yaml` to `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` with `Idempotency-Key: k-401`, then repeats the call; posts `kyc-risk.yaml` to the same `card-fee` path; posts `card-fee-bad.yaml` to it; posts `card-fee-v3.yaml` to `POST /api/bpm/rules/v1/rule-sets`, where `card-fee` already exists.
- **Then:** The first post returns `201` with `card-fee` v3 `DRAFT`; the repeat returns the same `201` and creates no v4. The `kyc-risk` file returns `400 INPUT_INVALID` naming the mismatch between the path and the document's `ruleSet`. The bad file returns `422 VALIDATION_FAILED` naming row `R4` and column `balance`. The post to `/rule-sets` returns `409 RULE_SET_EXISTS`. In every failure case no version and no source document is stored, and v1 and v2 are unchanged.
- **Example:** `card-fee-v3.yaml -> 201 v3 DRAFT; replay k-401 -> same 201; kyc-risk.yaml on card-fee -> 400; R4 without currency -> 422 at R4/balance`.
- **Test ID:** `RLE_033_import_as_new_version_creates_next_draft_or_refuses_whole`

### RLE-047 The author replaces their draft in place, with the import checks and without a new version
- **Traces to:** `TR-STD-01`, `TR-STD-03`, `TR-IDEM-01`, `TR-SEC-04`, `TR-UI-02`, PD-094
- **Milestone:** M1
- **Given:** Tenant `bank-a` with `horus.bpm.rules.authoring.cockpit-drafts: true`; `card-fee` v1 `PUBLISHED`; v2 `DRAFT` created by `lan` from `card-fee.yaml` (`R2`: `txCount >= 3 -> fee: 10000 VND`), `lockVersion 0`, canonical hash `H2`; `lan` holds `rules:author`, `minh` holds `rules:author` and `rules:approve`, `dan` holds `rules:read`. File `card-fee-r2-zero.yaml` is `card-fee.yaml` with `R2` writing `fee: "0 VND"`.
- **When:** `lan` sends `PUT /api/bpm/rules/v1/rule-sets/card-fee/versions/2` with `card-fee-r2-zero.yaml`, `Idempotency-Key: k-501` and `If-Match: "0"`, then repeats it. Failure cases: the same `PUT` with `If-Match: "0"` again and a new key; without `If-Match`; without `Idempotency-Key`; a file whose `R4` has `balance >= 50000000` with no currency; a file whose `ruleSet` is `kyc-risk`; a `PUT` of v1; `minh` replaces v2; `dan` replaces v2; a caller of `bank-b` replaces v2; the host with `cockpit-drafts: false`; two `PUT` requests with `If-Match: "0"` and different keys at the same moment.
- **Then:**
  - `200`; v2 is still `DRAFT`, still number 2 and still created by `lan`, with `lockVersion 1`, `ETag: "1"`, a canonical hash different from `H2` (the hash of the printed model, `RLE-045`), the new file as `sourceDocument` with its own `sourceHash`, and `R2` returning `fee: { "currency": "VND", "amount": 0 }` once approved. No v3 exists. One `rle_version_history` row `DRAFT_UPDATED` is written with actor `lan`, `lockVersion 1`, `previous_canonical_hash` `H2` and the new `canonical_hash`, and one `rules.version.draft-updated.v1` event carries rule set, version, tenant, `updatedBy: lan`, `canonicalHash` and `previousCanonicalHash: H2`.
  - The repeat with `k-501` returns the same `200` body and changes nothing.
  - The stale `If-Match` is `409 VERSION_CONFLICT`; no `If-Match` is `428 PRECONDITION_REQUIRED`; no key is `400 INPUT_INVALID`; the bad file is `422 VALIDATION_FAILED` with a violation at `R4` and `balance`; the `kyc-risk` file is `400 INPUT_INVALID` naming the mismatch; v1 is `409 INVALID_STATE`; `minh` (not the author) gets `403 PERMISSION_DENIED` with a detail pointing to importing his own document as a new version (`POST .../rule-sets/card-fee/versions`), and `dan` gets `403 PERMISSION_DENIED`; the `bank-b` caller gets `404 RULE_SET_NOT_FOUND`; with drafts off `lan` gets `403 DRAFT_AUTHORING_DISABLED`. In every failure case v2's document, hashes, source and `lockVersion` are unchanged, and no history row, event or idempotency record is written.
  - Of the two concurrent requests exactly one returns `200`; the other returns `409 VERSION_CONFLICT`, and v2 holds the winner's document.
  - An approval names the content reviewed (#275): `minh` reads v2 while it is pending at `lockVersion` L; it is sent back, `lan` replaces it and requests publication again. `minh`'s `:approve` with `If-Match: "L"` returns `409 VERSION_CONFLICT`; `:approve` or `:reject` without `If-Match` returns `428 PRECONDITION_REQUIRED`, through REST and the Java API. Neither publishes or records anything; an approval with the current `lockVersion` publishes.
- **Example:** `lan PUTs card-fee-r2-zero.yaml on draft v2 with If-Match "0" -> 200, still v2, lockVersion 1, new canonical hash, 1 DRAFT_UPDATED row, 1 draft-updated event; If-Match "0" again -> 409; minh -> 403; drafts off -> 403 DRAFT_AUTHORING_DISABLED`.
- **Test ID:** `RLE_047_author_replaces_draft_in_place_with_import_checks`

### RLE-048 The diff marks look-alike characters and reports header and column changes
- **Traces to:** `TR-UI-02`, `TR-STD-03`, ADR-0026 decision 6
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `kyc-route` v1 with input `country` (string) and rule `R1` (`country != 'VN' -> route: "MANUAL"`); v2 equal except that `R1` reads `country != 'ВN'` with a Cyrillic `В`; v3 equal to v1 except that `R1` reads `country != 'V\u200BN'` (an escaped zero-width space); v4 equal to v1 except that the hit policy is `unique`, the input `country` has `mask: true` and a new input `segment` (string) is declared. Caller `bob` holds `rules:read`.
- **When:** `bob` sends `GET .../rule-sets/kyc-route/versions/2/diff?base=1`, then `versions/3/diff?base=1` and `versions/4/diff?base=1`.
- **Then:** The v2 diff lists `R1` as `CHANGED` with field `/when/country` from `!= 'VN'` to `!= 'ВN'` and `nonAscii: true`, and different canonical hashes. The v3 diff lists the same field with `to` `!= 'V\u200BN'` and `nonAscii: true`. In both, a field whose values are printable ASCII has `nonAscii: false`. The v4 diff has no row change and `unchangedCount: 1`; `header` holds `{ path: /hitPolicy, from: first, to: unique }`; `inputs.added` is `[segment]`; `inputs.changed` holds `country` with field `/mask` from null to `true`.
- **Example:** `country != 'VN' -> country != 'ВN' (Cyrillic) -> CHANGED /when/country, nonAscii true; hit policy first -> unique -> header /hitPolicy`.
- **Test ID:** `RLE_048_diff_marks_non_ascii_literals_and_reports_header_and_column_changes`

### RLE-049 An undeclared input whose name looks like personal data is a warning the approver sees
- **Traces to:** `TR-SEC-05`, PD-099 (mitigation 1)
- **Milestone:** M1
- **Given:** Tenants `bank-a` and `bank-b`. Rule set `kyc-routing` with inputs `nationalId` (string, no `mask`), `age` (integer, `mask: false`) and `segment` (string, `mask: false`). Rule set `lien-he` with inputs `so_dien_thoai` and `ngaySinh` (no `mask`) and `kenh`. Rule set `company-fee` with inputs `company` and `tier` (no `mask`), `customerEmail` (`mask: true`) and `homeAddress` (`mask: false`). `bank-a` uses the built-in personal-data names; `bank-b` sets `horus.bpm.rules.masking.tenants.bank-b.personal-data-names: [segment]`. `lan` holds `rules:author`, `bob` holds `rules:approve`.
- **When:** `lan` imports `kyc-routing`, `lien-he` and `company-fee` in `bank-a`, requests publication of `kyc-routing` v1, and `bob` reads it; `lan` imports `kyc-routing` in `bank-b`; the import is repeated with the same `Idempotency-Key`.
- **Then:**
  - The `bank-a` import of `kyc-routing` answers `201` with `warnings: [{ code: UNMASKED_PERSONAL_DATA, input: nationalId, matchedName: "national id", message }]`; the message names the input and the matched name and says the values would be stored in plain text. The publication request answers with the same warning, and `bob`'s `GET` of the pending version shows it too.
  - `lien-he` warns about `so_dien_thoai` ("so dien thoai") and `ngaySinh` ("ngay sinh"), not `kenh`. `company-fee` has no warning: a declared input never warns, and a name matches only on whole words, so `company` does not match a short entry inside it.
  - In `bank-b` the import has no warning: its own list has no "national id", and `segment` is declared.
  - The replay returns the original body, warnings included. When the tenant masks undeclared inputs by default, the message says the input is masked only by that default.
  - Names are compared as words, ignoring case, punctuation and accents, without regular expressions; the built-in list covers full name, national id, passport, phone, email, address, date of birth, account and card number, tax id and driver licence in English and Vietnamese. Only names are checked, never values.
- **Example:** `nationalId without mask -> 201 with UNMASKED_PERSONAL_DATA (national id), seen by the approver; company -> no warning; bank-b list [segment] -> no warning`.
- **Test ID:** `RLE_049_an_undeclared_national_id_warns_at_import_naming_the_input_and_the_matched_name`

### RLE-050 Strict mode refuses an input that declares no mask, and names it
- **Traces to:** `TR-SEC-05`, PD-099 (mitigation 2), PD-074
- **Milestone:** M1
- **Given:** `bank-b` sets `horus.bpm.rules.masking.tenants.bank-b.require-explicit-mask: true`; `bank-a` keeps the default `decision-log.require-explicit-mask: false`. Rule set `kyc-routing` as in `RLE-049`, and the same table with `nationalId` declared `mask: true`. In `bank-a`, a draft of `kyc-routing` imported while strict mode was off.
- **When:** `lan` imports `kyc-routing` in `bank-b`, then the declared table; replaces a declared draft in `bank-b` with the undeclared table; `bank-a` turns strict mode on and `lan` requests publication of its older draft; in `bank-a`, a version requested while strict mode was off is approved by `bob` after strict mode is turned on.
- **Then:** The undeclared import is `422 VALIDATION_FAILED` with one violation per undeclared input, its `column` `nationalId`, the detail naming `decision-log.require-explicit-mask`; no rule set, version, event or idempotency record is stored. The declared import answers `201`. The replacement is refused the same way and the draft is unchanged. The publication request of the older draft is refused naming `nationalId`, and the version stays `DRAFT`. `bob`'s approval is refused the same way: the version stays `PENDING_APPROVAL` with its lock version, and no event is written. `bank-a` without strict mode imports the undeclared table with a warning only. `/api/bpm/meta` shows `decision-log.require-explicit-mask` for the caller's tenant.
- **Example:** `strict bank-b imports nationalId without mask -> 422 VALIDATION_FAILED, column nationalId, nothing stored; every input declared -> 201`.
- **Test ID:** `RLE_050_strict_mode_refuses_an_undeclared_input_naming_it_and_stores_nothing`

### RLE-051 The version diff shows each input's masking status and marks changes
- **Traces to:** `TR-SEC-05`, `TR-UI-02`, PD-099 (mitigation 3)
- **Milestone:** M1
- **Given:** `kyc-routing` v1 `PUBLISHED` with `nationalId` `mask: true`; v2 a draft with `nationalId` `mask: false`. In a tenant that masks undeclared inputs by default, v1 with `nationalId` `mask: true` and v2 with no `mask` on `nationalId` and without the input `segment`.
- **When:** `bob` reads `GET .../rule-sets/kyc-routing/versions/2/diff?base=1`.
- **Then:** `masking` lists every input of either version: `{ input: nationalId, from: MASKED, to: NOT_MASKED, changed: true }`, then `age` and `segment` with `changed: false`. In the tenant that masks by default, `nationalId` is `MASKED` to `MASKED_BY_DEFAULT` with `changed: true` and `personalDataName: "national id"`, and `segment` is `NOT_MASKED` to null with `changed: true`. `inputs.changed` still reports the `/mask` member as before.
- **Example:** `nationalId mask true -> false -> masking: MASKED to NOT_MASKED, changed true`.
- **Test ID:** `RLE_051_the_diff_marks_national_id_going_from_masked_to_not_masked`

### RLE-052 A change of the masking default is logged at start-up and the value in force is shown
- **Traces to:** `TR-SEC-05`, `TR-OBS-01`, PD-099 (mitigation 4)
- **Milestone:** M1
- **Given:** The example host on one database: `bank-a` has rule sets and no masking setting of its own, `bank-b` sets `require-explicit-mask: true`; the host masking default is `false`. Then the host restarts with `horus.bpm.rules.masking.tenants.bank-a.mask-default: true` and a masking key.
- **When:** The host starts, `lan` (`bank-a`) and `binh` (`bank-b`) read `GET /api/bpm/meta`; the host restarts; `lan` reads `/api/bpm/meta` again; the host restarts a third time unchanged.
- **Then:** The first start records the host default and every known tenant in `rle_setting_seen`. `/api/bpm/meta` shows `decision-log.mask-default: false` and `decision-log.require-explicit-mask: false` in the `rules` settings for `lan`, and `require-explicit-mask: true` for `binh`. The restart logs exactly one line, `decision-log.mask-default for tenant bank-a changed from false to true since the last start-up`, at INFO (WARN when masking is turned off), and none for `bank-b`; `/api/bpm/meta` then shows `true` for `lan`. A tenant first seen since the last start-up is compared with the host default it followed then. The third start logs nothing. No line carries the key.
- **Example:** `bank-a mask-default false -> true across a restart -> one log line naming bank-a, false and true; /meta shows true`.
- **Test ID:** `RLE_052_a_changed_masking_default_is_one_line_with_the_tenant_and_the_old_and_new_value`

---

### RLE-053 Approving a version with an unmasked personal-data warning needs an acknowledgement on the record
- **Traces to:** `TR-SEC-04`, `TR-SEC-05`, `TR-OBS-04`, PD-099 (mitigation 1), PD-104
- **Milestone:** M1
- **Given:**
  - Tenant `bank-a`, at its default `decision-log.mask-default: false`.
  - `kyc-routing` v1 is `PENDING_APPROVAL` with `lockVersion 2`, requested by `lan`. Its inputs `nationalId` and `contactNote` declare no `mask`, and `age` declares `mask: false`. With the built-in names, the version carries `UNMASKED_PERSONAL_DATA` for `nationalId` only (`RLE-049`).
  - `bob` holds `rules:approve`.
  - `lifecycle.acknowledge-warnings` is `[UNMASKED_PERSONAL_DATA]`, the default.
  - Second case: tenant `bank-c` with `decision-log.mask-default: true` and the same version.
- **When:**
  1. `bob` sends `POST /api/bpm/rules/v1/rule-sets/kyc-routing/versions/1:approve` with `Idempotency-Key`, `If-Match: "2"` and `{ "comment": "ok" }`.
  2. He sends it again with `"acknowledgedWarnings": [{ "code": "UNMASKED_PERSONAL_DATA", "input": "nationalId" }]` and a new key.
     - Before he does, the host adds `contact note` to `bank-a`'s personal-data names, so the version now also warns on `contactNote`. He sends the `nationalId` pair only.
     - He then sends both pairs.
  3. He replays the request with both pairs.
  4. In `bank-c`, `bob` approves without `acknowledgedWarnings`.
  - **Failure cases:** an extra pair `{ UNMASKED_PERSONAL_DATA, age }`, which `age` does not need because it declares `mask: false`; an unknown code `{ FOO, nationalId }`.
- **Then:**
  - **Step 1** returns `409 WARNINGS_NOT_ACKNOWLEDGED`. Its body carries `warnings: [{ code: UNMASKED_PERSONAL_DATA, input: nationalId, matchedName: "national id" }]` and `setting: [UNMASKED_PERSONAL_DATA]`. v1 stays `PENDING_APPROVAL` at `lockVersion 2`, and no history row or event is written.
  - **Step 2.**
    - The `nationalId`-only request returns `409 WARNINGS_NOT_ACKNOWLEDGED`, whose `warnings` list both `nationalId` and `contactNote`, and nothing is written. One acknowledged code never covers an input the approver was not shown.
    - The request with both pairs returns `200`, and v1 is `PUBLISHED`.
    - The history row `APPROVED` stores `acknowledged_warnings = [{ code: UNMASKED_PERSONAL_DATA, input: nationalId }, { code: UNMASKED_PERSONAL_DATA, input: contactNote }]` and `masked_by_default_inputs = []`.
    - `rules.version.published.v1` carries `acknowledgedWarnings` with the same two entries and `maskedByDefaultInputs: []`.
  - **Step 3** returns the same answer and writes nothing.
  - **Failure cases:** the extra pair gets `409 WARNINGS_NOT_ACKNOWLEDGED` with the current set; the unknown code gets `400 INPUT_INVALID`. Neither writes anything.
  - **Marked in the version.** Every warning in the version response carries `requiresAcknowledgement`, computed by the same rule as the approve check: at `bank-a` the warnings on `nationalId` and `contactNote` are `true`; at `bank-c`, whose default masks them, `false`; with an empty `lifecycle.acknowledge-warnings`, `false`. Approving with exactly the marked pairs succeeds, and without them is `409 WARNINGS_NOT_ACKNOWLEDGED` (`RLE_053_each_warning_says_whether_the_approval_must_acknowledge_it_and_the_approve_check_agrees`). A client shows a checkbox for exactly the marked warnings and never derives the set from `/meta`.
  - **Step 4** returns `200`, because both undeclared inputs are masked by `bank-c`'s default. The history row and the event carry `acknowledgedWarnings: []` and `maskedByDefaultInputs: [contactNote, nationalId]`, so an auditor sees why no acknowledgement was asked.
- **Example:** `approve with a nationalId warning -> 409 WARNINGS_NOT_ACKNOWLEDGED, nothing written; only the nationalId pair after contact note was added -> 409 listing both; both pairs -> PUBLISHED, acknowledgement on the history row and the event; masked by default -> no acknowledgement needed`.
- **Test ID:** `RLE_053_approval_with_an_unmasked_personal_data_warning_needs_an_acknowledgement`

---

### RLE-054 A pending version with no recorded author cannot be approved
- **Traces to:** `TR-SEC-04`, PD-105, PD-096
- **Milestone:** M1
- **Given:**
  - Tenant `bank-a`. `card-fee` v1 is `PENDING_APPROVAL`, requested by `lan`, and has no recorded author: it was written before schema V2 added `created_by`, and the upgrade assigned none.
  - `bob` and `carol` hold `rules:approve`; neither requested v1.
- **When:**
  1. `bob` sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/1:approve` with `Idempotency-Key` and `If-Match` set to v1's lock version; then `carol` does the same.
  2. `bob` reads v1.
  3. `bob` rejects v1 with a reason (or `lan` withdraws it). `lan` imports the same document as v2 and requests publication; `bob` approves v2.
  - **Order case:** `lan`, the requester, also holds `rules:approve` and approves v1.
- **Then:**
  - **Step 1** returns `409 AUTHOR_UNKNOWN` to both, with a detail naming `card-fee v1` and saying the change must be imported again. v1 stays `PENDING_APPROVAL` at the same lock version, `created_by` and `approved_by` stay null, and no history row or event is written. No author is ever assigned, by the upgrade or by the refusal.
  - **Step 2:** `allowedActions` for `bob` is `[reject]`; `approve` is never offered for a version with no author.
  - **Step 3:** the rejection (or withdrawal) succeeds and v1 is `DRAFT`. v2 records `lan` as author, and `bob`'s approval publishes it.
  - **Order case:** `403 SELF_APPROVAL_FORBIDDEN`: four eyes is checked first, then the recorded author, then the lock version.
- **Example:** `pending v1 without author -> bob approves -> 409 AUTHOR_UNKNOWN, nothing written; reject v1, lan imports v2 -> bob approves v2 -> PUBLISHED`.
- **Test ID:** `RLE_054_a_pending_version_with_no_recorded_author_cannot_be_approved`

## Platform Requirements Made Testable (RE-F-10, RE-F-13, X-F-05)

### RLE-034 The compiled cache holds at most `cache.max-entries` versions and heap stays flat
- **Traces to:** `TR-PERF-06`
- **Milestone:** M1
- **Given:** The example host with `cache.max-entries = 50`; tenant `bank-a` has 100 published rule sets `fee-001` to `fee-100`, each with one 200-row `first` table; the eviction counter `horus_rle_cache_evictions_total` (Micrometer `horus.rle.cache.evictions`, tag `cause`) reads 0.
- **When:** The host evaluates the 100 rule sets in rotation for 10 rounds (1,000 evaluations) after a warm-up round, measuring retained heap after a full garbage collection at the end of round 2 and of round 10.
- **Then:** The compiled store never holds more than 50 entries; the eviction counter reads at least 950 after the 10 rounds; every evaluation returns the same result as with an unbounded cache; retained heap at the end of round 10 is within 10 percent or 8 MB of the end of round 2, whichever is larger, each the median of three measurements after a full garbage collection; the gauge `horus_rle_cache_size` never exceeds 50.
- **Example:** `max-entries 50, 100 rule sets x 10 rounds -> size <= 50, evictions >= 950, heap round 10 within max(10%, 8 MB) of round 2`.
- **Test ID:** `RLE_034_compiled_cache_is_bounded_and_heap_stays_flat`

### RLE-035 The host refuses to start without a valid masking key, and the key never leaks
- **Traces to:** `TR-SEC-10`
- **Milestone:** M1
- **Given:** Rule set `minor-review` with input `nationalId` (`mask: true`) loaded at start-up through `definitions`. Case A: no `horus.bpm.rules.masking.hmac-key`. Case B: a Base64 key that decodes to 31 bytes. Case C: a Base64 key `k32` that decodes to 32 bytes, with log capture on.
- **When:** The host starts in each case; in case C it evaluates `minor-review` with `nationalId = "079090001234"`, reads the decision through `GET /decisions/{id}` and its explanation, and computes HMAC-SHA256 of `079090001234` under `k32` in the test.
- **Then:** Cases A and B fail start-up with a message that names `horus.bpm.rules.masking.hmac-key` and the rule set `minor-review`, and no decision is accepted. The check covers every version that is not retired, in every tenant, and an input masked by the tenant's `decision-log.mask-default` as well as by `mask: true`; a tenant with its own key setting (`masking.tenants.<tenant>.hmac-key`) is checked against that key only. Case C starts; no log line, response body, problem body, event or metric label contains `k32` or `079090001234`; the stored `masked_inputs.nationalId` equals `hmac:`, the key id (`k1` by default), `:` and the hex of the HMAC-SHA256, under the tenant's HKDF subkey of `k32` (DESIGN section 6), of `079090001234`, which the test computes on its own, so an exact-match lookup by the masked value finds the decision (`RLE-012`). Key ids (#281, `TR-SEC-10`): two keys with the same id for one tenant (or among the host keys), a tenant key id equal to a host key id, or a previous key equal to a configured key fail start-up naming the setting and never the key; a key id already recorded in `rle_masking_key_check` with a different check value fails start-up naming the key id and both check values, never the key; a key id recorded for the host and then configured for a tenant (for example `bank-a` taking `k1` after the host retired it), or the reverse, fails start-up and records nothing; a tenant configured as `*` fails start-up naming the setting; and a host `MaskingPolicy` that hands out a key for a tenant with live rule sets without declaring it in `configuredKeys()` fails start-up naming the policy class, the tenant and the key id.
- **Example:** `no key -> start-up fails naming the key setting and minor-review; 31-byte key -> fails; 32-byte key -> starts, 0 occurrences of key or plain value in logs and responses; k1 back with other bytes -> fails naming host masking key id k1`.
- **Test ID:** `RLE_035_masking_key_required_at_startup_and_never_leaked`

### RLE-036 Every Rule Engine REST error is a Problem Details body with a stable code
- **Traces to:** `TR-SEC-08`
- **Milestone:** M1 (rules; process engine errors are M2)
- **Given:** The example host with the Rule Engine REST module; the error matrix in `DESIGN.md` section 4.2; a test hook that makes the version repository throw `SQLException("relation rle_version does not exist")` on demand.
- **When:** Each code in the matrix that exists at merge time is triggered once (for example `RULE_SET_NOT_FOUND`, `VALIDATION_FAILED`, `SELF_APPROVAL_FORBIDDEN`, `VERSION_CONFLICT`, `QUERY_INVALID`), then the injected failure is triggered on `GET /api/bpm/rules/v1/rule-sets/card-fee`.
- **Then:** Every response has `Content-Type: application/problem+json` with `type` equal to `https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#<code in lower case>`, `status`, `code`, `detail` and `correlationId`, and the status matches the matrix. The injected failure returns `500` with code `INTERNAL_ERROR` and a `correlationId` that also appears in the server log; its body contains no stack trace, Java class name, `SQLException` or table name.
- **Example:** `GET a missing rule set -> 404 {type: ...#rule_set_not_found, code: RULE_SET_NOT_FOUND, correlationId: ...}; injected SQL failure -> 500 INTERNAL_ERROR, no "rle_version" in body`.
- **Test ID:** `RLE_036_every_rest_error_is_problem_details_with_stable_code`

---

## Tenant Resolution and Four-Eyes Retirement (PD-079, PD-080)

### RLE-037 The tenant comes from the authenticated principal, session or token, never from what the browser sends
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`, `TR-EXT-02`
- **Milestone:** M1
- **Given:** Host A embeds the cockpit in its own web application with a server-side login session; the session principal carries the attribute `tenant_id = bank-a`. Host B uses bearer tokens with claim `tenant_id: bank-a`. Both use the default `TenantResolver` with `tenancy.claim = tenant_id` and switching disabled. Rule set `card-fee` exists in `bank-a` and `bank-b`. Host C registers its own `TenantResolver` bean that returns `bank-c` for every caller. Case D: a session principal without the attribute.
- **When:** On hosts A and B the caller sends `GET /api/bpm/rules/v1/rule-sets/card-fee`, then `POST /api/bpm/rules/v1/decisions/card-fee:evaluate` with body `{ "tenantId": "bank-b", "inputs": { "tier": "GOLD", "txCount": 5 } }`, then the read again with header `X-Forwarded-Tenant: bank-b`. On host C the same read. In case D the same read.
- **Then:** Hosts A and B return `bank-a`'s rule set and evaluate `bank-a`'s version; the body `tenantId` and the extra header are ignored and the decision row has `tenant_id = bank-a`. Host C uses its own resolver: the engine's default is not called and the read is served for `bank-c`. Case D returns `401 UNAUTHENTICATED` and reads nothing. The starter registers no resolver that reads a request header.
- **Example:** `session tenant_id=bank-a, body tenantId=bank-b -> served for bank-a; principal without tenant -> 401`.
- **Test ID:** `RLE_037_tenant_from_principal_session_or_token_never_from_browser`

### RLE-038 In `four-eyes` mode a manual retirement needs a second approver, and the version serves until it is confirmed
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-EXT-05`
- **Milestone:** M3
- **Given:** `lifecycle.retire-approval = four-eyes`; tenant `bank-a`; `tet-fee-waiver` v1 `PUBLISHED`, `lockVersion 1`, the only version; `bob` and `carol` hold `rules:approve`; `/api/bpm/meta` lists the setting `lifecycle.retire-approval: four-eyes`.
- **When:** `bob` sends `POST .../rule-sets/tet-fee-waiver/versions/1:retire` with `If-Match: "1"` and `{ "reason": "Tet promotion ended", "confirmNoVersionInEffect": true }` (v1 is the last version in effect, PD-101); the host evaluates `tet-fee-waiver`; `bob` sends `POST .../versions/1:confirm-retire`; `carol` sends `POST .../versions/1:confirm-retire`. Failure cases: a retire request with `{ "reason": "" }`; a second retire request while one is pending; `:confirm-retire` with no pending request. Then `:cancel-retire` with a reason on a new request.
- **Then:** The request returns `200` with v1 still `PUBLISHED` and a pending retirement (`retireRequestedBy: bob`), one history row `RETIRE_REQUESTED` and one `rules.version.retire-requested.v1` event; the evaluation still selects v1. `bob`'s confirmation returns `403 SELF_APPROVAL_FORBIDDEN` and changes nothing. `carol`'s confirmation returns `200`: v1 is `RETIRED` with the same effects as `RLE-026` (retired event, cache epoch, `404 NO_EFFECTIVE_VERSION` on every replica within 5 s), and the event and the history row record `approvalMode: four-eyes`, requester `bob`, confirmer `carol` and the reason. The empty reason returns `400 INPUT_INVALID`; the second request and the confirmation without a request return `409 INVALID_STATE`; none changes anything. A cancellation leaves v1 `PUBLISHED` with no pending request and one history row `RETIRE_CANCELLED`.
- **Example:** `bob requests -> still serving; bob confirms -> 403 SELF_APPROVAL_FORBIDDEN; carol confirms -> RETIRED, approvalMode four-eyes`.
- **Test ID:** `RLE_038_four_eyes_retirement_needs_second_approver`

---

## Authoring API for the Rule Editor (#147)

### RLE-039 A caller lists the rule sets of their own tenant, by name, a page at a time
- **Traces to:** `TR-TEN-01`, `TR-SEC-03`, `TR-UI-02`
- **Milestone:** M1
- **Given:** Tenant `bank-a` has `card-fee` (v1 `PUBLISHED` effective since `2026-12-01T00:00:00Z`, v2 `PENDING_APPROVAL` requested at `2026-12-10T03:00:00Z`), `fx-margin` (v1 `DRAFT`) and `kyc-risk` (v1 `DRAFT`); tenant `bank-b` has its own `aml-score`. Caller `lan` holds `rules:read` in `bank-a`; the host clock reads `2026-12-10T03:00:00Z`.
- **When:** `lan` sends `GET /api/bpm/rules/v1/rule-sets?limit=2`, then the same request with the returned `cursor`. Failure cases: `limit=0`, `limit=201`, a `cursor` that was not issued by the endpoint, a cursor whose decoded key is not a rule set name (for example a NUL byte), a cursor issued to `lan` replayed by a `bank-b` caller, and a caller without `rules:read`.
- **Then:** The first page lists `card-fee` (`latestVersion: 2`, `latestStatus: PENDING_APPROVAL`, `publishedVersion: 1`, `lastChangedAt: 2026-12-10T03:00:00Z`) and `fx-margin` (`latestVersion: 1`, `latestStatus: DRAFT`, `publishedVersion: null`) and a `nextCursor`; the second page lists `kyc-risk` alone and `nextCursor: null`. `aml-score` never appears. `limit=0`, `limit=201`, the forged cursor, the NUL key and the replayed cursor return `400 QUERY_INVALID`, never `500`: a cursor is signed (HMAC-SHA256 over tenant, list and position, with a key stored once per database), so only a cursor issued to the same tenant for the same list is accepted; the caller without the permission gets `403 PERMISSION_DENIED`. `GET /rule-sets/card-fee` returns the same summary as the list item, and a `bank-b` caller reading it gets `404 RULE_SET_NOT_FOUND`. A name that cannot be a rule set name (`card\u0000fee`, upper case) is `404 RULE_SET_NOT_FOUND` on every endpoint that takes one. The list writes nothing.
- **Example:** `limit=2 -> [card-fee v2 PENDING_APPROVAL published 1, fx-margin v1 DRAFT] + cursor; next -> [kyc-risk]; bank-b's aml-score never listed`.
- **Test ID:** `RLE_039_rule_sets_are_listed_per_tenant_by_name_with_a_cursor`

### RLE-040 A caller lists the versions of one rule set, newest first, with who created, requested and approved each
- **Traces to:** `TR-TEN-01`, `TR-SEC-04`, `TR-UI-02`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v1 created by `lan`, requested by `lan` and approved by `minh`; v2 created and requested by `lan`; v3 `DRAFT` created by `lan`. Caller `minh` holds `rules:read` and `rules:approve`.
- **When:** `minh` sends `GET /api/bpm/rules/v1/rule-sets/card-fee/versions?limit=2`, then follows `nextCursor`. Failure cases: the same request from a `bank-b` caller; `GET /rule-sets/no-such-set/versions`; `limit=500`.
- **Then:** The first page lists v3 (`DRAFT`, `createdBy: lan`, `requestedBy: null`) then v2 (`PENDING_APPROVAL`, `requestedBy: lan`, `approvedBy: null`); the second page lists v1 (`PUBLISHED`, `approvedBy: minh`) and `nextCursor: null`. Items carry no definition. The `bank-b` caller and the unknown name get `404 RULE_SET_NOT_FOUND`; `limit=500` returns `400 QUERY_INVALID`. The list writes nothing.
- **Example:** `card-fee versions, limit 2 -> [v3 DRAFT lan, v2 PENDING_APPROVAL requested by lan] + cursor; next -> [v1 PUBLISHED approved by minh]`.
- **Test ID:** `RLE_040_versions_are_listed_newest_first_with_their_actors`

### RLE-041 A refused document lists every cell problem with its rule id and column
- **Traces to:** `TR-DET-02`, `TR-UI-02`, `TR-SEC-08`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; caller `lan` holds `rules:author`. File `card-fee-bad.yaml` with inputs `tier` (string) and `balance` (money), output `fee` (money), and rows `R2` (`balance: ">= 50000000"`, a number compared with money) and `R4` (`then: { fee: "+ 1" }`, which does not parse). Second file: a document with an unknown root property `maks`.
- **When:** `lan` posts each file to `POST /api/bpm/rules/v1/rule-sets`.
- **Then:** The first returns `422 VALIDATION_FAILED` whose `violations` are, in document order (rule by rule; within a rule the conditions, then the outputs, each in the order written, nested `any` and `all` groups after the plain conditions), `{ ruleId: R2, column: balance, message: ... }` and `{ ruleId: R4, column: fee, message: ... }`; each `message` carries no rule id or column prefix. The second returns `422 VALIDATION_FAILED` with one violation whose `ruleId` and `column` are null and whose `message` names `maks`. Neither stores a rule set, a version or a source document. Both carry `violationsTruncated: false`; a document with 150 bad output cells lists the first 100 in document order with `violationsTruncated: true`.
- **Example:** `R2 balance >= 50000000 and R4 fee "+ 1" -> 422, violations [R2/balance, R4/fee]`.
- **Test ID:** `RLE_041_validation_failure_lists_each_cell_problem_with_rule_and_column`

### RLE-042 A money output written without a currency is refused at import, not at evaluation
- **Traces to:** `TR-DET-02`, `TR-DET-03`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee.yaml` whose output `fee` is `money`, with row `R2` (`txCount >= 3 -> fee: "30000"`) and a second file where `R2` writes `fee: 30000` (a YAML number). Third file: output `waived` of type `boolean` with `R1` writing `waived: "'yes'"`. Fourth file: `R2` writes `fee: "30000 VND"`.
- **When:** `lan` imports each file.
- **Then:** The first three return `422 VALIDATION_FAILED` with a violation `{ ruleId: R2, column: fee }` (`{ ruleId: R1, column: waived }` for the third) whose message says the output is `money` and must carry a currency (is `boolean` for the third); nothing is stored, so no decision can ever fail with `EVALUATION_ERROR` for this reason. The fourth imports as `DRAFT`, and once approved, evaluating it with `tier = "SILVER"`, `txCount = 5` returns `fee: { "currency": "VND", "amount": 30000 }` from `R2`.
- **Example:** `fee: "30000" on a money output -> 422 at R2/fee at import; fee: "30000 VND" -> 201 DRAFT`.
- **Test ID:** `RLE_042_money_output_without_currency_is_refused_at_import`

### RLE-043 A version tells the caller which lifecycle actions they may take, with four eyes applied
- **Traces to:** `TR-SEC-04`, `TR-UI-02`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v2 `PENDING_APPROVAL`, created and requested by `lan`; v3 `DRAFT` created by `lan`; v1 `PUBLISHED`. `lan` holds `rules:read`, `rules:author` and `rules:approve`; `minh` holds `rules:read` and `rules:approve`; `dan` holds `rules:read` only.
- **When:** Each of them reads v1, v2 and v3 with `GET .../versions/{v}` and lists the versions.
- **Then:** On v2 `lan` gets `allowedActions: ["withdraw"]` (`approve` and `reject` are absent because `lan` requested it; `withdraw` is the requester's action, `RLE-046`), `minh` gets `["approve", "reject"]`, `dan` gets `[]`. On v3 `lan` gets `["request-publish"]` and `minh` gets `[]`. On v1 `lan` and `minh` get `["retire"]` (they hold `rules:approve`, #28) and `dan` gets `[]`; a retired version gives everyone `[]`. The list items carry the same values. `lan`'s `approve` of v2 is still refused with `403 SELF_APPROVAL_FORBIDDEN`, because `allowedActions` is a hint and the command checks again. An idempotent replay of a command returns the actions computed for the caller at replay time, not the stored ones.
- **Example:** `v2 requested by lan -> lan: [withdraw], minh: [approve, reject], dan: []`.
- **Test ID:** `RLE_043_allowed_actions_follow_permissions_and_four_eyes`

### RLE-044 Command and evaluation bodies are read up to their limit; a larger body is `413 PAYLOAD_TOO_LARGE`
- **Traces to:** `TR-SEC-07`, `TR-SEC-08`
- **Milestone:** M1
- **Given:** Tenant `bank-a`; `card-fee` v1 published and v2 `PENDING_APPROVAL`; caller `minh` holds `rules:approve` and `rules:evaluate`. The limits are 16,384 bytes for `:approve` and `:reject` bodies and 1,048,576 bytes for `:evaluate` (`EngineDefaults`), the import limit of `RuleSetReader`.
- **When:** `minh` sends `:reject` and `:approve` of v2 with a 16,385-byte body; `:reject` with a body that has no `Content-Length` (chunked) and never ends; `:evaluate` of `card-fee` with a 1,048,577-byte body; `POST /rule-sets` with a 1,048,577-byte YAML body, declared and chunked; the same import by a caller with only `rules:read`; and `:reject` with `{ "reason": "R2 waiver needs pricing committee minutes" }`.
- **Then:** The oversized and endless bodies return `413 PAYLOAD_TOO_LARGE` as problem details; a declared `Content-Length` above the limit is refused before the body is read, and an undeclared one is read at most one byte past the limit. The caller with only `rules:read` gets `403 PERMISSION_DENIED`, because every endpoint checks its permission before it reads the body. None of them changes v2, creates a rule set, writes an event, a history row, a decision row or an idempotency record. The normal reject returns `200`.
- **Example:** `:reject with 16,385 bytes -> 413 PAYLOAD_TOO_LARGE, v2 still PENDING_APPROVAL; :evaluate with 1 MiB + 1 byte -> 413, 0 rle_decision rows`.
- **Test ID:** `RLE_044_oversized_command_and_evaluation_bodies_are_refused_with_413`

---

## Canonical Form and Behaviour Hash (ADR-0026)

### RLE-045 The same behaviour has one hash in any style and serialisation, and different behaviour never shares one
- **Traces to:** `TR-STD-03`, `TR-STD-25`, `TR-STD-28`, `TR-DEF-04`, ADR-0026
- **Milestone:** M1
- **Given:** Rule set `style-check` v1 (`hitPolicy: first`) with inputs `x` (integer), `y` (integer), `country` (string), `at` (datetime) and `amount` (money, USD), and output `rate` (decimal), written twice. Document A uses author style: `x >= 3`, `x between 1 and 5`, `x is not null`, `country != 'VN'`, `country in ['VN','SG']`, `at == datetime('2026-12-01T00:00:00+07:00')`, `amount == 12.5 USD`. Document B uses the normal form: `>= 3`, `between 1 and 5`, `is not null`, `!= 'VN'`, `in ['VN', 'SG']`, `== datetime('2026-11-30T17:00:00Z')`, `== 12.50 USD`. Both have `rate: "0.10"`.
- **When:** A and B are imported as YAML in two tenants. A is exported as DMN and imported again, then exported and imported once more. Separately, the DMN cells `money(12.5, "USD")`, `money(10000.00, "VND")` and `money(-5, "USD")` go through import, export and import. Then each negative case below is imported as its own version of `style-check`.
- **Scope in schema v1 (ADR-0026 decision 2):** "one hash per behaviour" covers simple cells (a single comparison, range, list or literal). A compound cell (arithmetic, `and`/`or`, a function call) keeps its written spacing, so two spellings of the same compound cell hash differently in v1; different behaviour still never shares a hash.
- **Then (same behaviour, one hash):**
  - A and B have the same `canonical_hash` and different `source_hash` values.
  - Every DMN round trip keeps that `canonical_hash`.
  - The row-level diff of A against B reports no change.
  - `?include=source` on A returns A's bytes unchanged.
  - Money is printed at the currency scale (`12.50 USD`, `10000 VND`, `-5.00 USD`) and keeps its hash through the round trip.
- **Then (different behaviour, different hash):** each case below gets a `canonical_hash` different from the base version, and the diff reports the change.
  - **(n1)** `rate` written as the JSON number `0.1` against the JSON number `0.1000000000000000055511151231257827`, which is refused at import with `422 VALIDATION_FAILED` naming the line, because it does not survive an exact RFC 8785 round trip. The same value as the string `"0.1000000000000000055511151231257827"` is accepted and gets its own hash.
  - **(n2)** `rate: "0.10"` against `rate: "0.1"`, because the scale is kept.
  - **(n3)** The integer `9007199254740993` as a JSON number is refused with `422`. As a string it is accepted, with a hash different from `"9007199254740992"`.
  - **(n4)** In column `x`, `y >= 3` against `>= 3`, and `x > y` against `> y`: each keeps its operands.
  - **(n5)** `country != 'VN'` against the same text with a Cyrillic `В` in place of `V`. The canonical document shows it unchanged, because no normalisation is applied, and the diff marks the changed literal as containing non-ASCII characters.
  - **(n6)** `country != 'V\u200BN'` (a zero-width space) is printed with the `\u200B` escape and has its own hash. `country != 'V\u202EN'` (a bidirectional override) is refused at import with `422` naming the line.
  - **(n7)** A source that is not valid UTF-8, contains NUL, or starts with a byte order mark is refused at import with `422`, and nothing is stored.
  - **(n8)** For an output with `default: 0`, a rule with an explicit `null` output and the same rule with the cell omitted have different hashes; for an output without a default the two have one hash. Under `collect`, with output `flags` (`list<string>`, `default: "NONE"`): a matched rule with the cell omitted adds `"NONE"` to `flags`, a matched rule with an explicit `null` adds `null`, and the two tables have different hashes.
- **Then (integrity):**
  - A model whose printed form does not parse back to an equal tree is refused at import with `422` and nothing stored. This is tested with a deliberately broken printer in the test harness.
  - A stored version whose `canonical_json` is altered in the database so that it no longer hashes to `canonical_hash` is not served. Evaluation returns `EVALUATION_ERROR` with a detail naming the integrity check, and one audit entry and one alert are written.
- **Example:** `x >= 3 (YAML) == >= 3 (YAML) == >= 3 (DMN) -> one canonical_hash; 0.1 vs 0.1000000000000000055511151231257827 -> 422 as numbers, two hashes as strings; y >= 3 stays y >= 3; Cyrillic V -> different hash, marked in the diff`.
- **Test ID:** `RLE_045_one_hash_per_behaviour_in_any_style_and_never_for_different_behaviour`
