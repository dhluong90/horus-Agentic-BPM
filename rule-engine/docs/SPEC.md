# Rule Engine: Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated test named by its spec ID (`TR-QA-01`). Each statement carries the milestone that ships it, matching [`docs/product/roadmap.md`](../../docs/product/roadmap.md); a statement whose milestone has not been reached is not a release gate yet. Money values use the contract in `DESIGN.md` section 3.4.

---

## Evaluation & Hit Policies

### RLE-001 `first` hit policy returns top matching rule outputs
- **Traces to:** `TR-DET-01`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Rule set `card-fee` v1 with `hitPolicy: first`, rules `R1` (`tier == 'GOLD' -> fee: 0 VND`), `R2` (`txCount >= 3 -> fee: 10000 VND`), `R3` (`{} -> fee: 50000 VND`).
- **When:** Evaluate `inputs = { tier: "GOLD", txCount: 5 }`.
- **Then:** Result succeeds (`isError = false`), `matchedRuleIds = ["R1"]`, `outputs.fee = Money.ofMinor("VND", 0)`.
- **Example:** `evaluate("card-fee", { tier: "GOLD", txCount: 5 }) -> R1, 0 VND`.
- **Test ID:** `RLE_001_first_hit_policy_returns_top_matching_rule`

### RLE-002 `unique` hit policy: one match returns it, zero matches is no match, several matches fail
- **Traces to:** `TR-DET-04`, `TR-STD-22`, PD-088
- **Milestone:** M1
- **Kind:** semantics
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
- **Kind:** other
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
- **Kind:** semantics
- **Given:** Draft rule set referencing unknown input `creditScoreX`, mismatched arithmetic `balance + 100` (`money + integer`), or forbidden method call `T(java.lang.Runtime).getRuntime()`.
- **When:** Import it, or call `validate` or `requestPublish` on a stored draft.
- **Then:** Validation fails with row ID (`R1`), column, a stable `code` (`UNKNOWN_INPUT`, `TYPE_MISMATCH`, `FORBIDDEN_CONSTRUCT`) and the character position in the cell as written; status remains `DRAFT`; version cannot transition to `PENDING_APPROVAL` or `PUBLISHED`. A forbidden construct is refused before anything inside it is bound or evaluated. Operands are checked under every operator, in conditions and in output cells computed from inputs. A table whose worst-case step count (the sum of its node counts: every condition, plus the outputs of the rules its hit policy can evaluate) is over the host's step budget is refused the same way with code `EVALUATION_BUDGET_EXCEEDED` on the first rule from which it is over, using the limits evaluation will apply, not the defaults. A cell whose tree is deeper than 64 levels, a left-associative chain such as `a + a + ...` included, is `EXPRESSION_TOO_DEEP`. Failures that depend on the input stay at evaluation and fail closed: a currency mismatch against an input, division by zero, money overflow.
- **Example:** `when: { balance: "balance + 100 > 0" } -> 422 VALIDATION_FAILED, R1, column balance, TYPE_MISMATCH at position 8 ("+ is not defined for money and number: write the number with a currency, for example 100 VND")`.
- **Test ID:** `RLE_004_publish_rejects_unparsable_or_ill_typed_expressions` (`RLE_004_*` in `ExpressionValidationTest`, `ExpressionTypeCheckPropertiesTest`); the cost of the check (#359): `RLE_004_a_5000_rule_import_with_64_level_cells_works_out_each_nodes_type_once` (the type of each node is worked out once per cell, bottom-up, so checking an import takes time in proportion to its size; what is accepted or refused, the codes and the positions do not change)

### RLE-082 A host sets the evaluation step budget and time backstop, and a decision past them fails with a named reason
- **Traces to:** `TR-SEC-01`, `TR-STD-23`, `TR-DET-01`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `loyalty-score` v1 of tenant `bank-a`, approved under the default settings: one rule whose `points` cell is `1 + 1 + ... + 1` (twelve terms, about 23 expression nodes). Two hosts: one with the defaults, and one with `horus.bpm.rules.evaluation.step-budget: 10`.
- **When:** Each host evaluates `{ tier: GOLD }`. The host with the budget of 10 also imports the same document as a new rule set.
- **Then:**
  - The default host (`step-budget` 100,000 and `timeout-ms` 10,000) returns `points: 12`.
  - The host with 10 steps returns `EVALUATION_ERROR` with reason `EVALUATION_BUDGET_EXCEEDED` and `limit: STEPS` (`422` over REST). The decision is logged with `outcome = EVALUATION_ERROR` and `error_code = EVALUATION_BUDGET_EXCEEDED`.
  - The same limits apply to the worst-case check at import and request-publish (`RLE-004`): the document is refused there with the code `EVALUATION_BUDGET_EXCEEDED`.
  - The settings are `horus.bpm.rules.evaluation.step-budget` (1 to 1,000,000, default 100,000) and `horus.bpm.rules.evaluation.timeout-ms` (1 to 30,000, default 10,000), listed in the configuration metadata with a description, and neither can be switched off. The step count is deterministic; the time bound is only the backstop of a slow machine.
- **Example:** `step-budget 10, loyalty-score {tier: GOLD} -> EVALUATION_ERROR, EVALUATION_BUDGET_EXCEEDED, logged; default -> points 12`.
- **Test ID:** `RLE_082_a_host_with_step_budget_10_fails_a_heavy_decision_and_the_default_passes_it`, `RLE_082_a_host_that_lowers_the_budget_also_refuses_such_a_table_at_import`, `RLE_082_the_step_budget_and_the_time_backstop_default_and_may_be_set`

### RLE-083 An evaluation limit outside its range stops start-up and names the property
- **Traces to:** `TR-SEC-01`, `TR-EXT-03`, `TR-STD-23`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** A host that sets `horus.bpm.rules.evaluation.step-budget` or `horus.bpm.rules.evaluation.timeout-ms`.
- **When:** The value is `0` or `1000001` for the step budget, or `0` or `30001` for the time backstop; then the ceilings themselves (`1000000` and `30000`).
- **Then:** Each value outside the range stops start-up with a message that names the property and its range, for example `horus.bpm.rules.evaluation.step-budget must be 1 to 1000000, was 0`; the ceilings are accepted. Nothing is clamped.
- **Example:** `step-budget=0 -> start-up fails: horus.bpm.rules.evaluation.step-budget must be 1 to 1000000, was 0`.
- **Test ID:** `RLE_083_an_out_of_range_budget_stops_start_up_naming_the_property_and_its_range`, `RLE_083_the_limits_name_their_property_when_they_are_out_of_range`

### RLE-005 Money currency mismatch is rejected deterministically
- **Traces to:** `TR-DET-03`
- **Milestone:** M1
- **Kind:** control
- **Given:** Rule condition `balance >= 50000000 VND`.
- **When:** Evaluate with `balance = Money.ofMinor("USD", 300000)` (3000.00 USD).
- **Then:** Evaluation fails deterministically with `EVALUATION_ERROR` (`422` over REST), returns no partial outputs, and writes `outcome = EVALUATION_ERROR` to `rle_decision`.
- **Example:** `50000000 VND` vs `3000.00 USD -> EVALUATION_ERROR (currency mismatch)`.
- **Test ID:** `RLE_005_money_currency_mismatch_is_rejected`

### RLE-006 Null follows FEEL three-valued logic; a condition matches only when it is `true`
- **Traces to:** `TR-DET-01`, `TR-STD-02`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Rule set `null-check` v1 with `hitPolicy: collect`, inputs `income` (money, VND) and `tier` (string), and rules `R1` (`income < 5000000 VND`), `R2` (`income is null`), `R3` (`tier != 'GOLD'`), `R4` (`not(income < 5000000 VND)`), `R5` (`tier not in ['GOLD', 'PLATINUM']`), `R6` (`tier == 'GOLD'`), `R7` (`income < 5000000 VND or tier is null`), `R8` (`income < 5000000 VND and tier is null`).
- **When:** Evaluate with `income = null` and `tier = null`.
- **Then:** `R1` is `null` (no match); `R2` is `true` (match); `R3` is `true` (match, `null != "GOLD"` is `true` in FEEL); `R4` is `null` (no match, `not(null)` is `null`); `R5` is `true` (match, `not(null in [...])` is `not(false)`); `R6` is `false` (no match); `R7` is `true` (`null or true`); `R8` is `null` (`null and true`, no match). `matchedRuleIds = ["R2", "R3", "R5", "R7"]`; the decision is logged with outcome `OK`, and replaying it with its logged version and clock gives the same list.
- **Example:** `income = null, tier = null -> matched R2, R3, R5, R7; R1, R4, R6, R8 not matched`.
- **Test ID:** `RLE_006_null_follows_feel_three_valued_logic`

### RLE-007 Regex execution is bounded by reads, length and time
- **Traces to:** `TR-SEC-02`
- **Milestone:** M1
- **Kind:** semantics
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
- **Kind:** control
- **Given:** Tenant `bank-a` has `v1` (`effectiveFrom: 2026-11-01T00:00:00Z`, fee `50000 VND`) and `v2` (`effectiveFrom: 2026-12-01T00:00:00Z`, fee `30000 VND`), and rule uses `today()`.
- **When:** Evaluate with `clock = 2026-11-20T10:00:00Z`, then evaluate again after `2026-12-05` using `replay(decisionId)` or pinned `(v1, 2026-11-20T10:00:00Z)`.
- **Then:** Initial call selects `v1` (`50000 VND`); post-`v2` replay yields identical `v1`, `50000 VND`, and `matchedRuleIds`.
- **Example:** `clock=2026-11-20 -> v1 (50000 VND); clock=2026-12-01 -> v2 (30000 VND)`.
- **Test ID:** `RLE_008_explicit_clock_resolves_effective_version_and_replays`

### RLE-009 Maker-checker blocks self-approval in service and REST layers
- **Traces to:** `TR-SEC-04`
- **Milestone:** M1
- **Kind:** control
- **Given:** User `alice` (`rules:author`, `rules:approve`) creates `card-fee` v2 and calls `:request-publish`.
- **When:** `alice` invokes `VersionService.approve(...)` directly OR calls `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve`.
- **Then:** Both Java API and REST (`403 SELF_APPROVAL_FORBIDDEN`) reject the approval; version remains `PENDING_APPROVAL`. Only distinct identity `bob` (`rules:approve`) succeeds. The Rule Engine always applies `makers`: the same refusal holds when the host sets `lifecycle.approver-must-differ-from` to `requester`, and `/api/bpm/meta` reports `makers`.
- **Example:** `requester=alice, approver=alice -> 403; approver=bob -> 200 PUBLISHED`.
- **Test ID:** `RLE_009_maker_checker_blocks_self_approval`

### RLE-010 Multi-replica cache invalidates across pods on publication
- **Traces to:** `TR-ASY-04`, `TR-DET-01`, `TR-OBS-04`
- **Milestone:** M1
- **Kind:** semantics
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
- **Kind:** control
- **Given:** Authenticated security context resolves `tenantId = "tenant-a"`; rule set `secret-tier` exists only in `"tenant-b"`.
- **When:** Caller sends `POST /api/bpm/rules/v1/decisions/secret-tier:evaluate` with JSON body `{ "tenantId": "tenant-b", "inputs": {...} }` or queries `GET /decisions/{tenantBDecisionId}`.
- **Then:** Request-body `tenantId` is ignored; both evaluation and decision-log lookup return `404` under `"tenant-a"`.
- **Example:** `Caller=tenant-a, body.tenantId=tenant-b -> 404 RULE_SET_NOT_FOUND`.
- **Test ID:** `RLE_011_tenant_isolation_ignores_body_and_blocks_cross_tenant_read`

### RLE-012 Masked PII inputs are stored as keyed HMAC-SHA256 and rendered masked
- **Traces to:** `TR-SEC-05`, `TR-OBS-01`
- **Milestone:** M1
- **Kind:** control
- **Given:** Input `nationalId` declared with `mask: true` and host `hmac-key` configured.
- **When:** Evaluate with `nationalId = "079090001234"`.
- **Then:** Plaintext `"079090001234"` never appears in `rle_decision.masked_inputs` or `GET /decisions/{id}` REST response; instead a deterministic keyed HMAC token (e.g. `hmac:k1:9f4a...`) is stored so exact-match audit search still works. The stored token carries the id of the key that made it (`hmac:<keyId>:<hex>`, for example `hmac:k1:9f4a...`), so a tenant can rotate keys without stranding rows of the permanent log. An auditor with `rules:masked:search` (a permission `rules:log:read` does not imply) who posts the plain value to `POST /decisions:find-by-masked-input` finds every decision of that value, at most `masking.search.max-per-minute` times a minute per caller and replica (default 30; past it `429 RATE_LIMITED` with `Retry-After`, and the refusal is audited): the engine fingerprints it with every key the caller's tenant holds before the query, and the search writes one audit entry (`rules.decision-log.masked-input-searched.v1`: actor, tenant, rule set, input, period, number of matches, key ids, correlation id; never the value or its fingerprint). An output cell or default that reads a `mask: true` input is refused at import, and one that reads an input masked by the tenant default fails the decision closed (`error_code = MASKED_INPUT_IN_OUTPUT`), so the plain value never reaches `outputs`. Each tenant may hold its own key, so tenants `bank-a` and `bank-b` store different fingerprints of one value and neither tenant's read or search returns the other's decisions. A tenant without a key never logs a masked input in plain text: the decision fails closed with `EVALUATION_ERROR` (row `error_code = MASKING_KEY_MISSING`, no input logged). Rotation (#281): given tenant `bank-a` with current key `k2` and previous key `k1` (`masking.previous-keys`, or `masking.tenants.<tenant>.previous-keys`), and one decision about `079090001234` under each key, an auditor's search for that value finds both decisions, and new decisions are stored as `hmac:k2:<hex>`; a previous key is used for search only, never for writing, and a key no longer listed is no longer searched. Moving off the host key (#281): given `bank-a` wrote a decision about `079090001234` under its subkey of host key `k1`, when it gets its own key `a1`, new rows are `hmac:a1:<hex>` and the search still finds the earlier decision through its subkey of `k1` until `k1` is retired.
- **Example:** `input="079090001234" -> DB/REST shows "hmac:<keyId>:<hex>", plaintext absent; two decisions of 079090001234 -> both found by the plain value; bank-b's search finds none of bank-a's; current k2 + previous k1 -> decisions under k1 and k2 both found, new rows hmac:k2:<hex>`.
- **Test ID:** `RLE_012_masked_inputs_stored_as_keyed_hmac`

### RLE-013 Decision log participates in host transaction rollback and adds one insert
- **Traces to:** `TR-EMB-04`, `TR-PERF-03`, `TR-OBS-01`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `decision-log.mode = transactional`. Host service evaluates `card-fee` inside `@Transactional` and subsequently throws `RuntimeException` rolling back the host transaction.
- **When:** Transaction rolls back, and in a committed case SQL statements are counted.
- **Then:** Rolled-back transaction leaves `0` rows in `rle_decision`; committed evaluation executes **exactly 1 `INSERT`** into `rle_decision` (no `UPDATE` or `DELETE` paths exist in the engine).
- **Example:** `Host TX rollback -> rle_decision count unchanged; commit -> +1 INSERT`.
- **Test ID:** `RLE_013_transactional_decision_log_rolls_back_with_host`

### RLE-014 Idempotency key prevents duplicate mutations and rejects payload mismatch
- **Traces to:** `TR-IDEM-01`
- **Milestone:** M1
- **Kind:** control
- **Given:** Caller calls `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` with `Idempotency-Key: k-101` and body `B1`.
- **When:** Caller retries with `k-101` and identical body `B1`, versus `k-101` with different body `B2`.
- **Then:** First replay returns the original `201` version response without creating `v3`; mismatched body `B2` returns `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` and creates nothing. A second request with `k-101` sent while the first is still running returns `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1`.
- **Example:** `(k-101, B1) -> 201 v2; replay (k-101, B1) -> 201 v2; (k-101, B2) -> 422 IDEMPOTENCY_KEY_REUSE_MISMATCH; (k-101, B1) concurrent with the first -> 409 IDEMPOTENCY_IN_FLIGHT`.
- **Test ID:** `RLE_014_idempotency_key_replays_identical_and_rejects_mismatch`

### RLE-015 Simulation evaluates draft against history without polluting decision log
- **Traces to:** `TR-OBS-01`
- **Milestone:** M3 (replay against history moved to M3 by PD-042; simulation over sample inputs is M1, `RLE-016`)
- **Kind:** semantics
- **Given:** `card-fee` has `v1` published with 100 logged decisions and `v2` in `DRAFT`.
- **When:** Caller with `rules:simulate` runs historical simulation of `v2` over those 100 decisions.
- **Then:** Engine returns diff summary (e.g. `18 outcomes changed`) and writes `0` rows to `rle_decision`. Attaching the simulation report to a publication request is M3 and is not part of this statement.
- **Example:** `Simulate v2 on 100 past decisions -> diff summary returned, 0 rle_decision inserts`.
- **Delivery:** `POST /rule-sets/{name}/versions/{v}:simulate-history` and `RuleEngine.simulateHistory` (#95). The caller needs `rules:simulate` and `rules:log:read`, because the answer is built from logged inputs. The period is at most `query.max-range` and the run at most `simulation.max-inputs` decisions. A decision whose masked input had a value is counted as not replayable, naming the inputs only (DESIGN 4.2.2).
- **Test ID:** `RLE_015_simulation_leaves_decision_log_untouched`

### RLE-016 Simulation of a draft over sample inputs returns outputs without writing decision rows
- **Traces to:** `TR-OBS-01`, `TR-DET-01`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `card-fee` has `v1` published and `v2` in `DRAFT`; caller has `rules:simulate`.
- **When:** Caller posts `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate` with 5 sample input sets.
- **Then:** Response lists, per input set, `matchedRuleIds` and outputs for `v2` and for the effective version `v1`, flags the input sets whose outcome changed, and `rle_decision` gains `0` rows.
- **Example:** `5 samples -> 5 results, 2 marked changed, 0 rle_decision inserts`.
- **Test ID:** `RLE_016_sample_simulation_leaves_decision_log_untouched`

### RLE-075 Two versions are compared over a month of logged decisions with totals side by side
- **Traces to:** `TR-OBS-01`, `TR-DET-01`, `TR-DET-05`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Tenant `bank-a`; `card-fee` v1 published (`R1` GOLD -> 0 VND, `R2` `txCount >= 3` -> 10000 VND, `R3` default -> 50000 VND); v2 `DRAFT`, equal except `R3` -> 30000 VND. January 2027 holds 10,000 logged `card-fee` decisions: 2,000 matched `R1`, 3,000 `R2` and 5,000 `R3`. Caller `thu` holds `rules:simulate` and `rules:log:read`.
- **When:** `thu` posts `POST /api/bpm/rules/v1/rule-sets/card-fee:compare` with `{ "base": 1, "candidate": 2, "from": "2027-01-01T00:00:00Z", "to": "2027-02-01T00:00:00Z" }`, then repeats it. In Java: `RuleEngine.compare("card-fee", ComparisonRequest.of(1, 2, from, to))`.
- **Then:** `200` with:
  - `decisionsCompared: 10000`, `changedCount: 5000` (changed as in DESIGN 4.2.1: the outcome kind, an output value or the failure code differs).
  - `outcomes` per side: `MATCHED 10000` for both.
  - `totals` per numeric and money output, per currency, in minor units, as exact decimal text: `fee` VND `base` `"280000000"`, `candidate` `"180000000"`, `difference` `"-100000000"`.
  - `transitions` (at most 20, ordered by count, then by output and value): `{ output: fee, from: 50000 VND, to: 30000 VND, count: 5000 }`.
  - `from`, `to` and both canonical hashes (`baseCanonicalHash`, `candidateCanonicalHash`).
  - `rle_decision`, the idempotency table, the outbox and the history gain no row, and no event is written. The repeat returns a byte-identical body, which holds no input, decision id or fingerprint.
- **Example:** `v1 vs v2 over January -> 10000 compared, 5000 changed, fee 280000000 -> 180000000 VND (-100000000)`.
- **Test ID:** `RLE_075_two_versions_are_compared_over_a_period_with_totals_side_by_side`

### RLE-076 Decisions that cannot be re-evaluated are counted, never guessed
- **Traces to:** `TR-SEC-05`, `TR-DET-05`
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `kyc-route` v1 does not read `nationalId`; v2 adds a condition on `nationalId` (`mask: true`); v3 has the content of v1. January holds 1,200 decisions. In 900 of them `nationalId` had a value, of which the log holds only the fingerprint. In 300 it was `null`.
- **When:** `thu` compares v1 with v2 over January, then v1 with v3. (A version compared with itself is refused, `RLE-077`; v3 stands for it.)
- **Then:**
  - v1 against v2: the 900 decisions with a masked value are not evaluated and are reported as `skipped: { MASKED_INPUT: 900 }`. The 300 with `null` are compared, because a logged `null` is the real input. `decisionsCompared` is 300, and totals cover those 300 only.
  - v1 against v3 compares all 1,200 with `changedCount: 0`, because a masked input that neither version reads does not prevent re-evaluation. The set of inputs a version reads is every input named by a rule condition or an output cell.
  - Where a version reads an input that a logged decision does not hold, because the input was added later, the decision counts under `MISSING_INPUT`.
  - No plain value and no fingerprint appears in the response.
- **Example:** `v1 vs v2 -> skipped MASKED_INPUT 900, 300 compared; v1 vs v3 -> 1200 compared, 0 changed`.
- **Test ID:** `RLE_076_decisions_that_cannot_be_reevaluated_are_counted_not_guessed`

### RLE-077 A comparison is bounded, permission-checked and tenant-scoped
- **Traces to:** `TR-SEC-03`, `TR-SEC-02`, `TR-TEN-01`
- **Milestone:** M3
- **Kind:** control
- **Given:** As in `RLE-075`, plus `simulation.max-history-decisions` (default 50,000, from 1 to 200,000; PD-124) set to 5,000 in a test, and a caller `dan` with `rules:log:read` only.
- **When:** The January comparison runs with the lowered cap; a 32-day period; `base` equal to `candidate`; `base` 9, which does not exist; a `bank-b` caller; `dan`; a run in which `evaluation.regex-max-reads-per-request` runs out part-way; a version whose stored document no longer matches its hash.
- **Then:**
  - The over-cap period is `422 HISTORY_TOO_LARGE`, naming the 10,000 decisions found and the setting (`found`, `atLeast`, `maxHistoryDecisions`). A sample is never taken, so totals are never partial. The count stops at the ceiling of 200,000 and then says `atLeast: true`.
  - The 32-day period and equal versions are `400 QUERY_INVALID` (`query.max-range`, `candidate`). Version 9 is `404 VERSION_NOT_FOUND`. The `bank-b` caller gets `404 RULE_SET_NOT_FOUND`. `dan` gets `403 PERMISSION_DENIED`, because the comparison needs both `rules:simulate` and `rules:log:read`.
  - The regex run is refused whole with `422 EVALUATION_ERROR`, `REGEX_TIMEOUT` in the detail, naming `evaluation.regex-max-reads-per-request`, and returns no partial totals.
  - Each version is integrity-checked and compiled before any decision is read (`422 VALIDATION_FAILED` otherwise). Decisions are read page by page, so memory holds a page and not the period. Nothing is written in any case.
  - A value of the setting outside 1 to 200,000 stops start-up naming `horus.bpm.rules.simulation.max-history-decisions`.
- **Example:** `cap 5000, 10000 found -> 422 HISTORY_TOO_LARGE found 10000; 32 days -> 400 QUERY_INVALID query.max-range`.
- **Test ID:** `RLE_077_comparison_is_bounded_permission_checked_and_tenant_scoped`

---

## Decision Log Partitions, Tenancy and Host Metadata

### RLE-017 The decision log is partitioned monthly from the first migration; retention drops whole partitions only
- **Traces to:** `TR-OPS-04`, `TR-OBS-01`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** A clean database; `TestClock` at `2027-01-26T00:00:00Z`; `decision-log.partitions-ahead = P7D`. Second run: `decision-log.retention = P12M`, clock at `2028-02-03T00:00:00Z`, partitions `2027_01` to `2028_02` present. Third run: a decision row with `created_at = 2027-03-15T00:00:00Z` was inserted while no `2027_03` partition existed.
- **When:** The engine migration runs and the partition job runs in each case, with SQL captured.
- **Then:** After migration `rle_decision` is range-partitioned with `rle_decision_default`, `rle_decision_2027_01` and `rle_decision_2027_02` (7 days ahead reaches February). In the retention run only `rle_decision_2027_01` is dropped (its upper bound `2027-02-01` is before `2027-02-03`); no `DELETE` statement on `rle_decision` appears. In the third run the straggler is moved into the new `2027_03` partition before `ATTACH` and the default partition is empty afterwards. With `decision-log.retention` unset, no partition is ever dropped.
- **Example:** `retention P12M at 2028-02-03 -> drop rle_decision_2027_01 only; 0 DELETE statements`.
- **Delivery:** creation ahead and the overflow case are tested by `RLE_017_*` in `DecisionLogPartitionMaintenanceIT` and `DecisionLogSearchIT`. The retention run ships with #104 (RE-F-31, PD-050): each drop is recorded in the append-only `rle_decision_log_drop` (V19), and retention is off unless the host sets `decision-log.retention` to whole months of at least `decision-log.retention-floor` (`P12M`); unset, nothing is dropped, start-up is unaffected on every profile and a WARN is logged at every start-up (PD-130). `keep-forever` is the explicit keep-everything value. PD-050 is the 1.0 rule: from 1.0 an unset value refuses start-up outside `dev` (`RLE-100`, #373).
- **Test ID:** `RLE_017_decision_log_monthly_partitions_and_retention_by_drop`

### RLE-018 `X-Tenant-Id` is accepted only for a tenant in the token's allowed list
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** `tenancy.switching.enabled = true`; `lan`'s token has `tenant_id: bank-a` and `allowed_tenants: [bank-a, bank-a-fin]`; rule set `card-fee` exists in `bank-a-fin` and `bank-b`. Second case: switching disabled.
- **When:** `lan` calls `GET /api/bpm/rules/v1/rule-sets/card-fee` with `X-Tenant-Id: bank-a-fin`, then with `X-Tenant-Id: bank-b`; in the second case with `X-Tenant-Id: bank-a-fin`.
- **Then:** The first call returns `bank-a-fin`'s rule set; the second returns `403 TENANT_NOT_ALLOWED` with no rule set data and a security log entry naming user, requested tenant and `correlationId`; with switching disabled, a header that differs from the token tenant also returns `403 TENANT_NOT_ALLOWED`.
- **Then (audit, #451):** every accepted switch hands one record to the host's `TenantSwitchAudit` (actor, original tenant, new tenant, time, correlation id) before the call reaches engine data; the default writes it as one `tenant.switch.accepted` line on the `io.github.dhluong90.bpm.audit` logger, and the security log line stays. A refused switch writes no audit record. After `lan` (`tenant_id: bank-a`, `allowed_tenants: [bank-a, bank-b]`) switches to `bank-b`, `GET /rule-sets/card-fee` of `bank-a`'s rule set is `404` and nothing is written for either tenant.
- **Example:** `X-Tenant-Id: bank-b -> 403 TENANT_NOT_ALLOWED, logged; bank-a-fin -> 200`.
- **Test ID:** `RLE_018_tenant_header_accepted_only_within_allowed_tenants`, `RLE_018_after_a_switch_to_bank_b_the_bank_a_rule_set_is_404_and_nothing_is_written`

### RLE-019 `/api/bpm/meta` and `/api/bpm/me` describe the host and the caller
- **Traces to:** `TR-UI-08`, `TR-SEC-08`
- **Milestone:** M1
- **Kind:** other
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
- **Kind:** semantics
- **Given:** The YAML file `card-fee.yaml` (1,834 bytes, with comments and keys in author order) in Git, and the SHA-256 of its committed blob computed by the pipeline (`git show HEAD:card-fee.yaml | sha256sum`).
- **When:** It is imported with `POST /api/bpm/rules/v1/rule-sets`, then read with `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1?include=source` and without the parameter.
- **Then:** `rle_version` stores `source_format = YAML`, `source_document` byte for byte equal to the file, `source_hash` the SHA-256 of the raw request bytes, equal to the pipeline's hash, and `canonical_hash` the SHA-256 of the RFC 8785 form of the canonical document written from the model, with cells in their normal form (ADR-0026); with `include=source` the response carries the original text including comments and its hash; without it, the canonical document only. Nothing in the source document is evaluated.
- **Example:** `import card-fee.yaml -> source_format YAML, source bytes equal, source_hash = sha256sum, canonical_hash = hash of the printed model`.
- **Test ID:** `RLE_020_import_keeps_source_document_and_canonical_hash`

### RLE-021 Every expression has a FEEL translation that gives the same result
- **Traces to:** `TR-STD-02`, `TR-DET-01`
- **Milestone:** M1 (ADR-0019; null cases as `RLE-006`, PD-058)
- **Kind:** other
- **Given:** Seeded generators (the project's own, PD-090; a failure prints its seed) for well-typed `horus-expr-v1` expressions over `string`, `integer`, `decimal`, `boolean`, `date`, `datetime` and lists, with inputs including `null`, and the translation table of `standards-compliance.md` section 4.4. The reference FEEL implementation runs in test scope only.
- **When:** Each expression is evaluated by the engine and its FEEL translation by the reference implementation with the same inputs and clock; 1,000 tries per property in CI, 100,000 nightly. Fixed cases: `7 / 2`, `round(2.5, 0)`, `matches('ab12', '[0-9]+')`, `daysBetween(date('2027-01-01'), date('2027-03-01'))`.
- **Then:** Results are equal for every try (money expressions excluded, since money is a HORUS extension); the fixed cases give `3.5`, `2`, `true` and `59`.
- **Example:** `matches('ab12', '[0-9]+') -> true` (unanchored search, as FEEL).
- **Test ID:** `RLE_021_v1_expressions_match_their_feel_translation`

### RLE-022 `today()` uses the rule set's time zone
- **Traces to:** `TR-DET-01`, `TR-DET-05`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Rule set `same-day-cutoff` v1 with `timeZone: Asia/Ho_Chi_Minh` and rule `R1` (`txDate == today() -> sameDay: true`); a second version v2 without `timeZone` (default `UTC`).
- **When:** Both are evaluated with `txDate = date('2027-01-16')` and clock `2027-01-15T18:30:00Z` (01:30 on 16 January in Ho Chi Minh City).
- **Then:** v1 matches `R1` (`today()` is 2027-01-16 in the rule set's zone); v2 does not match (`today()` is 2027-01-15 in UTC); replaying either decision with its logged version and clock gives the same result.
- **Example:** `clock 2027-01-15T18:30Z, zone Asia/Ho_Chi_Minh -> today() = 2027-01-16`.
- **Test ID:** `RLE_022_today_uses_rule_set_time_zone`

### RLE-023 Engine JSON is the engine's own: host Jackson settings change no engine payload
- **Traces to:** `TR-EMB-07`, `TR-API-06`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The example host with the Rule Engine starter and REST module, rule set `card-fee` v1 published, and host properties `spring.jackson.property-naming-strategy=SNAKE_CASE` and `spring.jackson.serialization.write-dates-as-timestamps=true`; the test runs in the Spring Boot 4.x leg and in the Spring Boot 3.5 leg, where the host's Jackson 2 stays at the version its dependency management pins.
- **When:** `card-fee.yaml` is imported with `POST /api/bpm/rules/v1/rule-sets`, then `POST /api/bpm/rules/v1/decisions/card-fee:evaluate` is sent with body `{ "inputs": { "tier": "GOLD", "txCount": 5 } }`.
- **Then:** Both requests succeed; the evaluate response keys are camelCase (`matchedRuleIds`, `decisionId`, `evaluatedAt`), `evaluatedAt` is an ISO 8601 string, and `fee` is `{ "currency": "VND", "amount": 0 }`; the host's own controllers still answer in snake case; in the 3.5 leg the resolved dependency tree has `tools.jackson` artifacts from the engine and the host's `com.fasterxml.jackson` artifacts at the host's pinned version, with no override in the host build.
- **Example:** `host SNAKE_CASE -> engine response {"matchedRuleIds":["R1"],"outputs":{"fee":{"currency":"VND","amount":0}}}`.
- **Test ID:** `RLE_023_engine_json_ignores_host_jackson_settings`

### RLE-024 A v1 rule set reads into the DMN model and writes back to the same canonical JSON
- **Traces to:** `TR-STD-28`, `TR-STD-03`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `card-fee.yaml` (v1, `hitPolicy: first`, inputs `tier` and `txCount`, output `fee`, rules `R1` to `R3`, every cell already in normal form) and the RFC 8785 hash of that document.
- **When:** The v1 reader builds the in-memory model and the canonical writer prints it back.
- **Then:** The model is one `Definitions` named `card-fee` holding one `Decision` `card-fee` whose `DecisionTable` has hit policy `FIRST`, input clauses `tier` and `txCount`, output clause `fee` and rules with ids `R1`, `R2`, `R3` in document order; the printed canonical JSON is byte for byte equal to the RFC 8785 form of the document (it is already in normal form) and its SHA-256 equals that hash and the stored `canonical_hash`.
- **Example:** `card-fee.yaml -> Definitions(card-fee) / Decision(card-fee) / DecisionTable(FIRST, 2 inputs, 1 output, 3 rules) -> same hash`.
- **Test ID:** `RLE_024_v1_rule_set_round_trips_through_dmn_model`

### RLE-095 A schema version 1 document that uses a name reserved for full DMN is refused by name with its stage
- **Traces to:** `TR-STD-04`, `TR-STD-03`, `TR-STD-26`, #38
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `card-fee.yaml` (schema version 1, `hitPolicy: first`) and five copies, each changed in one place: `hitPolicy: priority` (also `any`, `ruleOrder` and `outputOrder`); `expressionLanguage: feel` (also `FEEL` and the OMG FEEL namespace URIs of DMN 1.2 to 1.5, such as `https://www.omg.org/spec/DMN/20230324/FEEL/`); `schemaVersion: 2`; `errorMode: spec`. Control cases: `hitPolicy: cheapest`, `expressionLanguage: javascript`, and `schemaVersion` values of `0`, `-1`, `"2"`, `1.5` and `99999999999`.
- **When:** Each document is read, and the `priority` copy of a rule set named `dmn-ready-priority` is posted to `POST /api/bpm/rules/v1/rule-sets` as `application/yaml`.
- **Then:**
  - Each reserved name is refused with `422 VALIDATION_FAILED`, and the detail names the member, the value and the stage that adds it. A hit policy gives "hitPolicy priority is added in stage S1; this release reads first, unique and collect". FEEL gives "expressionLanguage FEEL is added in stage S1 with a later schema version". `schemaVersion: 2` gives "schemaVersion 2 is written for a later engine release; this release reads schema version 1". `errorMode: spec` gives "errorMode spec is added in stage S1" (PD-125). A later schema version is never read as version 1.
  - The control cases keep their plain refusal ("hitPolicy must be first, unique or collect", "expressionLanguage must be urn:horus-bpm:expr:v1", "schemaVersion must be 1"). These details name no stage and do not echo an unknown hit policy.
  - Over HTTP the refusal is `422 VALIDATION_FAILED`, and `GET /rule-sets/dmn-ready-priority` is `404` afterwards, so nothing is stored.
- **Example:** `card-fee.yaml with hitPolicy: priority -> 422 VALIDATION_FAILED "hitPolicy priority is added in stage S1", 0 rows`.
- **Test ID:** `RLE_095_a_reserved_hit_policy_is_refused_by_name_with_stage_s1`, `RLE_095_an_unknown_hit_policy_keeps_the_plain_refusal`, `RLE_095_feel_as_expression_language_is_refused_by_name_with_stage_s1`, `RLE_095_another_expression_language_keeps_the_plain_refusal`, `RLE_095_a_later_schema_version_is_refused_by_name_and_never_read_as_version_1`, `RLE_095_a_schema_version_that_is_not_a_later_version_keeps_the_plain_refusal`, `RLE_095_error_mode_spec_is_refused_by_name_with_stage_s1`, `RLE_095_a_reserved_name_is_refused_over_http_with_its_stage_and_nothing_is_stored`

### RLE-096 `errorMode: strict` is a member of schema version 1 that changes no hash
- **Traces to:** `TR-STD-23`, `TR-STD-03`, PD-066, PD-125, #38
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `card-fee.yaml` without `errorMode`, and the same document with `errorMode: strict`. Control cases: `errorMode: lenient`, `errorMode: STRICT`, `errorMode: ""` and `errorMode: true`.
- **When:** Both documents are read. The `strict` one is also exported to DMN 1.5 and the export is read again.
- **Then:**
  - Both documents give the same model, the same canonical JSON and the same `canonical_hash`. The canonical JSON has no `errorMode` member, because `strict` is the mode of every schema version 1 rule set, written or not. Only another mode, from stage S1, will be printed and hashed.
  - The DMN round trip gives the same hash: an imported DMN table evaluates in `strict` mode until stage S1 (PD-125, `RLE-045`).
  - `lenient`, `STRICT` and `""` are `422 VALIDATION_FAILED` with "errorMode must be strict or spec". `true` is refused because `errorMode` must be a string.
- **Example:** `card-fee.yaml + errorMode: strict -> same canonical JSON and hash as card-fee.yaml`.
- **Test ID:** `RLE_096_error_mode_strict_is_accepted_and_keeps_the_canonical_json_and_hash`, `RLE_096_error_mode_strict_survives_a_dmn_round_trip_with_the_same_hash`, `RLE_096_another_error_mode_is_refused_naming_both_modes`, `RLE_096_error_mode_must_be_a_string`

### RLE-097 An evaluation can name its decision, and only the rule set's own decision is accepted
- **Traces to:** `TR-STD-28`, `TR-IDEM-01`, `TR-TEN-01`, DN-08, #38
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Tenant `bank-a` has `card-fee` v1 `PUBLISHED`. A teller holds `rules:evaluate`. Tenant `bank-b` has no `card-fee`.
- **When:**
  - The teller evaluates `{ tier: GOLD, txCount: 5 }` three times: without a decision id, with `DecisionRequest.withDecision("card-fee")`, and with `withDecision("risk-adj")`.
  - The same inputs are sent with `Idempotency-Key: k-097`, first without a decision id and then with `card-fee`.
  - Over HTTP, `POST /api/bpm/rules/v1/decisions/dmn-ready-fee:evaluate` is sent with the body member `"decision": "dmn-ready-fee"`, then `"risk-adj"`, then `7`.
  - A `bank-b` teller evaluates `card-fee` with `withDecision("card-fee")`.
- **Then:**
  - Leaving the decision out and naming `card-fee` give the same result: `R1` matches and `fee` is `{ "currency": "VND", "amount": 0 }`. Each call is logged once.
  - The decision id is not part of the request hash, so the second `k-097` call replays the first decision and writes no second row.
  - `risk-adj` is `400 INPUT_INVALID`, with a detail that starts "decision must be the rule set's name or left out", names stage S1 and does not echo the id. A non-string `decision` is `400 INPUT_INVALID` as well. No decision row is written.
  - The `bank-b` call is `404 RULE_SET_NOT_FOUND`, and nothing is written.
  - The four-argument and six-argument `DecisionRequest` constructors still compile and leave `decision` null.
- **Example:** `evaluate card-fee with decision risk-adj -> 400 INPUT_INVALID, 0 decision rows`.
- **Test ID:** `RLE_097_naming_the_rule_sets_own_decision_gives_the_same_result_as_leaving_it_out`, `RLE_097_the_decision_id_is_outside_the_idempotency_hash`, `RLE_097_another_decision_id_is_input_invalid_naming_decision_and_logs_nothing`, `RLE_097_another_tenants_rule_set_is_not_found_whatever_the_decision_id_and_nothing_is_written`, `RLE_097_the_older_constructors_leave_the_decision_out`, `RLE_097_the_evaluate_body_names_its_decision_and_only_the_rule_sets_own_is_accepted_over_http`

---

## Version Lifecycle: Reject, Retire and Diff (RE-F-07, RE-F-08)

### RLE-025 An approver rejects a pending version with a reason and it returns to draft
- **Traces to:** `TR-OBS-04`, `TR-IDEM-01`
- **Milestone:** M1
- **Kind:** control
- **Given:** Tenant `bank-a`; `card-fee` v2 is `PENDING_APPROVAL` with `lockVersion 3`, requested by `alice`; `bob` holds `rules:approve`.
- **When:** `bob` sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:reject` with `Idempotency-Key: k-201`, `If-Match: "3"` and body `{ "reason": "R2 waiver needs pricing committee minutes" }`. Failure cases: the same call with body `{ "reason": "" }`; with `If-Match: "2"`; and a reject of `card-fee` v1, which is `PUBLISHED`. Self-reject case: `fx-margin` v4 is `PENDING_APPROVAL`, requested by `bob`, and `bob` rejects it with reason `"Wrong margin table attached"`.
- **Then:** `200`; v2 is `DRAFT` with `lockVersion 4`, `requested_by` and `requested_at` cleared, and one `rle_version_history` row `REJECTED` with actor `bob`, the reason, statuses `PENDING_APPROVAL` to `DRAFT` and `lockVersion 4` (`GET .../versions/2/history` returns it after the `CREATED` and `REQUESTED` rows); one `rules.version.rejected.v1` event carries rule set, version, tenant, actor `bob`, reason and timestamp; `alice` can edit v2 again and request publication again. A replay with `k-201` and the same body returns the same `200` and writes no second event. The empty reason returns `400 INPUT_INVALID` naming `reason`; the stale `If-Match` returns `409 VERSION_CONFLICT`; the reject of v1 returns `409 INVALID_STATE`. In each failure case the version, its history and the event count are unchanged. The self-reject returns `403 SELF_APPROVAL_FORBIDDEN` with a detail pointing to `:withdraw`, and `fx-margin` v4 stays `PENDING_APPROVAL` (PD-089; the requester withdraws instead, `RLE-046`).
- **Example:** `bob rejects card-fee v2 with a reason -> DRAFT, lockVersion 4, 1 rejected event; reason "" -> 400 INPUT_INVALID; v1 PUBLISHED -> 409 INVALID_STATE`.
- **Test ID:** `RLE_025_reject_returns_pending_version_to_draft_with_reason`

### RLE-046 The requester withdraws their own pending request, and it returns to draft without a rejection
- **Traces to:** `TR-OBS-04`, `TR-IDEM-01`, `TR-SEC-04`, PD-089
- **Milestone:** M1
- **Kind:** control
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
- **Kind:** semantics
- **Given:** Tenant `bank-a`; rule set `tet-fee-waiver` has only v1, `PUBLISHED`, effective since `2027-01-20T00:00:00Z`, `lockVersion 1`; decision `D1` was logged against v1 at `2027-02-01T09:00:00Z` with output `waiveFee: true`; two replicas hold v1 compiled; `bob` holds `rules:approve`; `lifecycle.retire-approval` is `single` and `lifecycle.retire-last-version` is `confirm`, the defaults, so v1 is the last version in effect. The emergency single-approver path (PD-012) is not configured; it is not an M1 feature.
- **When:** At `2027-02-17T08:00:00Z` `bob` sends `POST /api/bpm/rules/v1/rule-sets/tet-fee-waiver/versions/1:retire` with `Idempotency-Key: k-301`, `If-Match: "1"` and body `{ "reason": "Tet promotion ended", "confirmNoVersionInEffect": true }`; 5 s later each replica evaluates `tet-fee-waiver` with the host clock; then `replay(D1)` runs, and a caller with `rules:simulate` evaluates `tet-fee-waiver` with `clock = 2027-02-01T09:00:00Z`. Failure cases: a retire of a `DRAFT` version; a retire with body `{ "reason": "" }`; the same retire of v1 without `confirmNoVersionInEffect`; and, on a host with `lifecycle.retire-last-version: refuse`, the retire with the confirmation.
- **Then:** `200`; v1 is `RETIRED` with `retired_at = 2027-02-17T08:00:00Z` and one history row `RETIRED` (actor `bob`, the reason); one `rules.version.retired.v1` event carries rule set, version, tenant, actor `bob`, the reason, the timestamp and `noVersionInEffect: true`, and the history row stores `no_version_in_effect = true`; the same transaction increments the cache epoch and notifies after commit; within `cache.epoch-check-interval` (5 s) both replicas return `404 NO_EFFECTIVE_VERSION` for the evaluation. `replay(D1)` still returns v1 and `waiveFee: true`, because a retired version stays readable for explain and replay. The evaluation at `clock = 2027-02-01T09:00:00Z` selects v1 and returns `waiveFee: true`, because v1 was effective at that instant (selection reads `retired_at`, `DESIGN.md` section 3.3.1). The retire of a `DRAFT` returns `409 INVALID_STATE`, the empty reason `400 INPUT_INVALID`. The retire without the confirmation returns `409 LAST_VERSION_IN_EFFECT` with `ruleSet: tet-fee-waiver`, `version: 1`, `setting: confirm`, `nextEffectiveFrom: null` and a detail saying that evaluations will fail until a new version is approved. Under `refuse` the confirmed retire returns the same code with `setting: refuse`. None of the refusals changes anything. No second approver is needed in the default `single` mode (ADR-0025, PD-080); the event and the history row record the mode `single`.
- **Example:** `retire tet-fee-waiver v1 without confirmNoVersionInEffect -> 409 LAST_VERSION_IN_EFFECT; with it -> RETIRED, 1 retired event, noVersionInEffect true; evaluate after 5 s on either replica -> 404 NO_EFFECTIVE_VERSION; replay(D1) -> v1, waiveFee true`.
- **Test ID:** `RLE_026_manual_retire_stops_every_replica_serving_the_version`

### RLE-027 A published version retires on its own when a newer version becomes effective
- **Traces to:** `TR-OBS-04`, `TR-DET-01`, `TR-OPS-05`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Tenant `bank-a`; `card-fee` v1 `PUBLISHED`, effective since `2026-11-01T00:00:00Z`; `card-fee` v2 approved at `2026-11-25T10:00:00Z` with `effectiveFrom: 2026-12-01T00:00:00Z`; two replicas share one database; `TestClock` drives both. Second case: rule set `fx-margin` v3 `PUBLISHED` and v4 approved with `effectiveFrom` equal to the approval instant.
- **When:** The clock moves from `2026-11-30T23:59:59Z` to `2026-12-01T00:00:01Z`; each replica evaluates `card-fee`; then the lifecycle sweep (`DESIGN.md` section 3.3.2) runs on both replicas at the same moment, through PgBouncer in transaction mode in the pooler leg. In the second case v4's approval commits.
- **Then:** At `2026-11-30T23:59:59Z` v1 is `PUBLISHED` and evaluation selects v1. At `2026-12-01T00:00:01Z` evaluation selects v2 on both replicas whether or not v1's status has changed yet, because selection follows the effective date (`RLE-008`). After the maintenance run v1 is `RETIRED` and exactly one `rules.version.retired.v1` event exists for it, with actor `system:supersession`, reason `SUPERSEDED` and `supersededBy: 2`, and exactly one history row `RETIRED`, although both replicas ran; no advisory lock was taken. An evaluation of v1's decision with `clock = 2026-11-20T10:00:00Z` still selects v1. In the second case v3 is `RETIRED` in the transaction that publishes v4, with one retired event.
- **Example:** `clock 2026-12-01T00:00:01Z -> evaluate selects v2; after the sweep on 2 replicas -> v1 RETIRED, 1 retired event (actor system, reason SUPERSEDED)`.
- **Test ID:** `RLE_027_previous_version_retires_once_when_newer_becomes_effective`

### RLE-028 The engine returns a row-level diff between two versions of a rule set
- **Traces to:** `TR-UI-02`, `TR-STD-28`, `TR-STD-03`
- **Milestone:** M1
- **Kind:** other
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
- **Kind:** control
- **Given:** Tenant `bank-a` has 250 decisions of `card-fee` between `2027-01-01T00:00:00Z` and `2027-01-31T23:00:00Z`, each with masked input `nationalId`, and 40 decisions of `kyc-risk` in the same month; tenant `bank-b` has 10 decisions of its own `card-fee`. Caller `dan` holds `rules:log:read` in `bank-a`.
- **When:** `dan` sends `GET /api/bpm/rules/v1/decisions?ruleSet=card-fee&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z`, then follows the returned cursor. Failure cases: `from=2027-01-01T00:00:00Z&to=2027-02-02T00:00:00Z` (32 days); no `ruleSet`; the same first query by a `bank-b` caller; a caller without `rules:log:read`.
- **Then:** The first page holds 200 `card-fee` decisions of `bank-a`, newest first, and a cursor; the second page holds the other 50 and no cursor; no `kyc-risk` or `bank-b` row appears; every `nationalId` is an `hmac:<keyId>:<hex>` value, never plain text; the query plan reads at most 2 monthly partitions. The 32-day range and the missing rule set return `400 QUERY_INVALID` naming the parameter; the `bank-b` caller gets its own 10 decisions only; the caller without the permission gets `403`. The search writes nothing.
- **Example:** `card-fee, January 2027 -> page 1: 200 rows + cursor; page 2: 50 rows; 32 days -> 400 QUERY_INVALID`.
- **Test ID:** `RLE_029_decision_search_by_rule_set_and_period_is_bounded`

### RLE-030 A decision is explained in plain language from its log entry and its version
- **Traces to:** `TR-OBS-05`, `TR-DET-05`; PD-027 option 1, decided 2026-11-19
- **Milestone:** M1 (generated explanation, PD-027 option 1; author templates are M3, RE-F-19)
- **Kind:** other
- **Given:** Tenant `bank-a`; `card-fee` v1 (`hitPolicy: first`) with `R1` (`tier == 'GOLD' -> fee: 0 VND`), `R2` (`txCount >= 3 -> fee: 10000 VND`), `R3` (`{} -> fee: 50000 VND`); decision `D7` logged with inputs `tier: "SILVER"`, `txCount: 5`, `matchedRuleIds: ["R2"]`, output `fee: 10000 VND`, clock `2026-11-20T10:00:00Z`. `risk-flags` v1 (`hitPolicy: collect`) and decision `D8` with `matchedRuleIds: ["R1", "R3"]` as in `RLE-003`. v2 of `card-fee` was published after both decisions. Caller `dan` holds `rules:log:read`.
- **When:** `dan` sends `GET /api/bpm/rules/v1/decisions/D7/explain`, again, and `GET /api/bpm/rules/v1/decisions/D8/explain`. Failure cases: an unknown id; `D7` requested by a caller of tenant `bank-b`.
- **Then:** The `D7` response names rule set `card-fee`, version 1, hit policy `FIRST`, outcome `OK` and evaluation clock; lists matched row `R2` with one condition entry (input `txCount`, recorded value `5`, test `>= 3`, result `true`) and its output `fee` `{ "currency": "VND", "amount": 10000 }`; carries `reasons` `[ { "code": "RULE_MATCHED", "params": { "ruleId": "R2", "version": 1 } } ]`, which the cockpit renders in English or Vietnamese; and carries the English `text`, with names in words (`tx count`, or the input's declared `label`) and numbers grouped for the language, money keeping its currency code, "Rule R2 of card-fee version 1 matched because tx count is 5, which is at least 3. Result: fee 10,000 VND."; in `vi` the amount reads `10.000 VND`, and a boolean reads Yes or No (`Có`, `Không`) (#392) The second request returns a byte-identical body. The explanation is built from the decision row and the stored version 1 only: v2 does not change it, nothing is re-evaluated and `rle_decision` gains no row. The `D8` response lists `R1` and `R3` in order with their conditions and the aggregated outputs `flags: [HIGH_VELOCITY, NEW_DEVICE]` and `penalty: 15`. The unknown id and the `bank-b` caller get `404 DECISION_NOT_FOUND`.
- **Example:** `explain D7 -> "Rule R2 of card-fee version 1 matched because tx count is 5, which is at least 3. Result: fee 10,000 VND."`
- **Test ID:** `RLE_030_explanation_is_generated_from_log_entry_and_version`

### RLE-031 Explanations keep masked inputs masked and explain no match, null inputs and failures
- **Traces to:** `TR-OBS-05`, `TR-SEC-05`, `TR-STD-02`; PD-027 option 1, decided 2026-11-19
- **Milestone:** M1
- **Kind:** control
- **Given:** Tenant `bank-a`. Rule set `minor-review` v1 (`hitPolicy: first`) with input `nationalId` (`mask: true`) and `age`, rows `R1` (`nationalId is not null and age < 18 -> route: "MANUAL"`) and `R2` (`age >= 18 -> route: "AUTO"`); decision `D11` with `nationalId = "079090001234"` and `age = 16`. Decision `D12` of `card-fee` v1 with no matched row (a version without the catch-all `R3`). Decision `D13` of `null-check` v1 (`RLE-006`) with `income = null`, `tier = null`. Decision `D14` of `loan-pricing` v1 with outcome `UNIQUE_HIT_VIOLATION` and `matchedRuleIds: ["R1", "R2"]` (`RLE-002`).
- **When:** `GET /api/bpm/rules/v1/decisions/{id}/explain` for `D11` to `D14`, with log capture on.
- **Then:** `D11`: the condition on `nationalId` shows the recorded value as `hmac:<keyId>:<hex>` with `masked: true`, reason `VALUE_MASKED` and the word "masked" in `text` (`national id is masked`), the condition on `age` shows `16` against `< 18`, and `079090001234` appears in no response field and no log line. `D12`: "No rule of card-fee version 1 matched. No output was produced." `D13`: the `R2` condition has `missing: true`, reason `VALUE_MISSING` and reads "income has no value", and no row is reported as matched through a condition whose result was `null`. No row that did not match is explained, and no expression is evaluated. `D14`: outcome `UNIQUE_HIT_VIOLATION`, `matched` lists `R1` and `R2` from the stored `matched_rule_ids`, "Rules R1 and R2 of loan-pricing version 1 both matched; this table allows one. No output was produced."
- **Example:** `explain D11 -> nationalId shown as hmac:k1:9f4a... (masked), age 16 < 18, route MANUAL; plain value in 0 fields and 0 log lines`.
- **Test ID:** `RLE_031_explanation_masks_inputs_and_covers_no_match_null_and_failure`

### RLE-072 A decision is explained with the author's sentence in the requested language
- **Traces to:** `TR-OBS-05`, `TR-DET-05`; PD-123, decided 2026-11-20 (option 1)
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Tenant `bank-a`; `card-fee` v1 with `R2` templated (`explain`) in `en` and `vi`, `R1` without a template, and `explanations.noMatch` in both languages. Decision `D7` matched `R2` with `txCount = 5` and `fee = 10000 VND`; `D8` matched `R1`; `D9` matched nothing. v2, published later, changes the `R2` wording. Caller `dan` holds `rules:log:read`.
- **When:** `dan` requests `GET /api/bpm/rules/v1/decisions/D7/explain?lang=vi`, then `?lang=en`, then `?lang=th`; then `D8` and `D9` in `en`.
- **Then:** `D7` in `vi`: `text` is "Phí thẻ của bạn là 10.000 VND vì bạn đã thực hiện 5 giao dịch trong tháng.", `textSource: TEMPLATE`; in `en`: "Your card fee is 10,000 VND because you made 5 transactions this month." Placeholder values are written as in the generated sentence (grouped for the language, `RLE-030`). In `th`, which has no template: the generated sentence in the default language, `textSource: GENERATED`; a template of another language is never shown. `D8`: the generated sentence, `GENERATED`. `D9`: "No card fee applies to this account.", `TEMPLATE`. When several rows matched, the sentences of the rows are joined in rule order by one space, and only when every matched row has one in that language; otherwise the generated sentence explains the whole decision. `reasons` and `matched` are identical to the response without templates; v2 does not change `D7`; a repeated request returns identical bytes and writes nothing.
- **Example:** `explain D7 lang=vi -> "Phí thẻ của bạn là 10.000 VND vì bạn đã thực hiện 5 giao dịch trong tháng." (TEMPLATE)`
- **Test ID:** `RLE_072_decision_is_explained_with_the_authors_template`

### RLE-073 A template is checked at import and is part of the approved content
- **Traces to:** `TR-DET-02`, `TR-SEC-04`, `TR-STD-03`; PD-123
- **Milestone:** M3
- **Kind:** control
- **Given:** Caller `lan` holds `rules:author`. A template is plain text with the placeholders `{input.<name>}`, `{output.<name>}`, `{ruleId}`, `{ruleSet}` and `{version}`, per BCP 47 language, in `rules[].explain` and `explanations.noMatch` (which allows `{input.<name>}`, `{ruleSet}` and `{version}` only).
- **When:** `lan` imports files whose `R2` template uses (a) `{input.txCnt}`, an undeclared input; (b) `{output.fee | upper}`; (c) `{input.nationalId}` where `nationalId` is `mask: true`; (d) a language tag `english`; (e) 501 characters; (f) the valid `card-fee` as v2, where v1 is equal except for the `en` wording of `R2`.
- **Then:** (a) to (e) are `422 VALIDATION_FAILED` with a violation on `R2` and column `explain`, with codes `UNKNOWN_PLACEHOLDER` (a, b), `MASKED_INPUT_IN_TEMPLATE` (c), `INPUT_INVALID` (d) and `TEMPLATE_TOO_LONG` (e); nothing is stored. (f) imports with a canonical hash different from v1's, and `GET .../versions/2/diff?base=1` lists `R2` as `CHANGED` with the field `/explain/en`. The approver is neither author nor requester (`RLE-009`). Export keeps the templates: DMN carries them as `<horus:explain horus:lang>` and `<horus:noMatch horus:lang>` in `extensionElements`, namespace `urn:horus-bpm:dmn:extensions:v1`, and the round trip keeps the hash (`RLE-045`). At most 500 characters per language and 10 languages per template.
- **Example:** `{input.txCnt} -> 422 UNKNOWN_PLACEHOLDER on R2, column explain`
- **Test ID:** `RLE_073_template_is_checked_at_import_and_changes_the_approved_content`

### RLE-074 Templates keep masked values masked, and say what they cannot say
- **Traces to:** `TR-OBS-05`, `TR-SEC-05`; PD-123
- **Milestone:** M3
- **Kind:** control
- **Given:** `decision-log.mask-default: true`. `minor-review` v1 declares `nationalId` with no `mask`, so it is masked by default; `R1`'s template is "Sent to manual review: applicant {input.nationalId} is {input.age}." Decision `D11` has `age = 16`; a decision with `age = null` matches a templated row naming `{input.age}`. `risk-flags` v1 (`collect`) has `R1` and `R3` templated, both matched in `D15`. `loan-pricing` decision `D14` failed with `UNIQUE_HIT_VIOLATION` and its rows have templates.
- **When:** each is explained in `en`.
- **Then:** `D11`: "Sent to manual review: applicant (masked) is 16."; a masked value renders the catalogue word `template.masked`, never the fingerprint or the plain value, which appears in no field. The null input renders the catalogue phrase "no value". `D15`: the sentences of `R1` and `R3`, joined in rule order by one space. `D14`: the generated failure sentence of `RLE-031`; templates explain matches and no match only.
- **Example:** `explain D11 -> "Sent to manual review: applicant (masked) is 16."`
- **Test ID:** `RLE_074_templates_keep_masked_values_masked_and_fall_back_for_failures`

### RLE-070 Decisions are found by the host's case reference across the tenant's rule sets
- **Traces to:** `TR-OBS-01`, `TR-TEN-01`, `TR-SEC-05`
- **Milestone:** M2
- **Kind:** control
- **Given:** Tenant `bank-a`. Loan application `LA-2027-000123` was decided on 2027-01-10 by `kyc-risk` (1 decision), `loan-pricing` (1) and `card-fee` (2), all with `correlation_id = LA-2027-000123`, and with input `nationalId` (`mask: true`) on `kyc-risk`. 500 other `bank-a` decisions in January have other correlation ids. Tenant `bank-b` has one decision with the same correlation id. Caller `dan` holds `rules:log:read`.
- **When:** `dan` sends `GET /api/bpm/rules/v1/decisions?correlationId=LA-2027-000123&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z`. Failure cases: no `from`/`to`; a 32-day period; an empty `correlationId`; one outside the stored format `[A-Za-z0-9._:-]{1,128}` (for example `LA 2027/123`); a caller without `rules:log:read`; the same query by a `bank-b` caller.
- **Then:**
  - `200` with the 4 `bank-a` decisions, newest first, each with `decisionId`, `ruleSet`, `version`, `outcome`, `matchedRuleIds`, `evalClock`, `clockChosen` and `pinned`. `nationalId` shows as `hmac:<keyId>:<hex>`. No other decision and no `bank-b` row appears.
  - The query reads at most the 2 monthly partitions of the period through an index led by tenant and correlation id (migration `V20`), within `query.statement-timeout` (2 s).
  - The missing period, the 32-day period and the bad ids are `400 QUERY_INVALID` naming the parameter. The caller without the permission gets `403 PERMISSION_DENIED`. The `bank-b` caller gets only its own decision.
  - Adding `ruleSet=card-fee` narrows the result to the 2 `card-fee` decisions. The search writes nothing.
  - The search covers all rule sets of the caller's tenant, because one application touches several. A REST call without `X-Correlation-Id` gets a generated id, so a host that wants case search sends its own reference. A correlation id is a business reference and may appear in a URL; a host must not use a national id as one. Paging is as in `RLE-029`; the cursor is signed and bound to tenant, reference and period (`RLE-039`).
- **Example:** `correlationId=LA-2027-000123, January 2027 -> 4 decisions across 3 rule sets; 32 days -> 400 QUERY_INVALID; bank-b -> its own 1 decision`.
- **Test ID:** `RLE_070_decisions_are_found_by_correlation_id_across_rule_sets`

### RLE-071 Decisions are found by the exact value of a plain input, never of a masked one
- **Traces to:** `TR-SEC-05`, `TR-OBS-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** Tenant `bank-a`; `loan-pricing` declares `productCode` (string, `mask: false`), `amount` (money) and `nationalId` (`mask: true`). In January 2027 it has 30 decisions with `productCode = "HOME-30Y"`, 2 of them with `amount = 2500000000 VND`.
- **When:** `dan` posts `POST /api/bpm/rules/v1/decisions:find-by-input` with `{ "ruleSet": "loan-pricing", "input": "productCode", "value": "HOME-30Y", "from": "2027-01-01T00:00:00Z", "to": "2027-02-01T00:00:00Z" }`, then with `"input": "amount", "value": { "currency": "VND", "amount": 2500000000 }`. Failure cases: `"input": "nationalId"`; an input no version of `loan-pricing` declares; an input that a tenant default masks (`decision-log.mask-default: true`, `RLE-052`); a value of the wrong type; a body over 16,384 bytes.
- **Then:**
  - `200` with the 30 decisions, then with the 2 decisions. Values compare by their canonical form: money by currency and minor units, a number by exact value (`30000` equals `30000.00`), a string by code point, with no case folding or trimming.
  - `nationalId` and the input masked by the tenant default are `400 QUERY_INVALID` with a detail pointing to `POST /decisions:find-by-masked-input` and the `rules:masked:search` permission, so a plain-value search can never be used to test a masked value.
  - The unknown input and the wrong type are `400 QUERY_INVALID` naming `input` or `value`. The large body is `413 PAYLOAD_TOO_LARGE`.
  - Paging and the 31-day bound are as in `RLE-070`. The search writes nothing to the decision log. It needs `rules:log:read`; the masked search keeps its own permission and rate limit (`RLE-012`).
- **Example:** `productCode = HOME-30Y -> 30 decisions; amount = 2500000000 VND -> 2; nationalId -> 400 QUERY_INVALID, use find-by-masked-input`.
- **Test ID:** `RLE_071_decisions_are_found_by_a_plain_input_value_and_never_by_a_masked_one`

### RLE-085 Every multi-row read of the Rule Engine store is a registered list query with a declared bound
- **Traces to:** `TR-PERF-07`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** other
- **Given:** `rule-engine-postgres` with `RuleListQueries`, the registry of every statement that returns more than one row: the rule set, version and history pages, the rule set events, the decision log searches by rule set, by correlation id and by input, and the engine-internal sweeps. A seeded PostgreSQL with two tenants, 3,000 rule sets each and three monthly decision partitions.
- **When:** The build runs the ArchUnit rule and the query-plan check on the oldest and the newest supported PostgreSQL; a store method is added that groups every decision of a tenant without the registry; a registered query is declared as a page without `LIMIT :limit`, without a tenant filter, or a decision search without a period on the partition key.
- **Then:**
  - Every multi-row read goes through `ListQuery.list`, and only the registry builds a `ListQuery`; the unregistered method fails the build and names the class and the call. The statements that do not keep their declared bound do not load.
  - Each query declares one bound: a page (tenant, `ORDER BY`, `LIMIT :limit`), a page of the partitioned decision log (also a period `created_at >= :from AND created_at < :to`), the rows of one parent key, a set of named keys of one tenant, or a sweep across tenants that runs at start-up and says why it is small.
  - `EXPLAIN (FORMAT JSON)` of each registered query, with sequential scans off, shows no sequential scan or whole-index walk of an engine table (a sweep may read its small table), a `Limit` for each page, an index condition on the key of each parent-key or key-set query, and for each decision search at most two monthly partitions for a 31-day period, one for an exact month, and never the default partition.
  - No query changes its behaviour or result.
- **Example:** `jdbc.sql("SELECT outcome, count(*) FROM rle_decision WHERE tenant_id = :tenant GROUP BY outcome").list() -> build fails: UnregisteredDecisionRead.outcomeCounts calls JdbcClient$MappedQuerySpec.list()`.
- **Test ID:** `RLE_085_every_multi_row_read_of_the_store_is_a_registered_list_query`, `RLE_085_an_unregistered_group_by_over_the_decision_log_fails_the_build`, `RLE_085_a_page_query_without_order_by_and_limit_does_not_load`, `RLE_085_a_decision_page_without_a_period_on_the_partition_key_does_not_load`, `RLE_085_a_parent_key_or_key_set_query_must_filter_tenant_and_its_key`, `RLE_085_the_registry_holds_every_list_query_with_a_unique_name`, `RLE_085_list_queries_pass_query_plan_check`, `RLE_085_a_31_day_decision_search_reads_two_monthly_partitions_and_a_month_reads_one`, `RLE_085_a_group_by_over_the_decision_log_fails_the_plan_check`, `RLE_085_a_parent_key_without_an_index_fails_the_plan_check`

---

## Batch Evaluation (RE-F-26)

### RLE-067 A batch returns one result per item in order, and a failing item does not stop the rest
- **Traces to:** `TR-DET-01`, `TR-OBS-01`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Tenant `bank-a`; `card-fee` v1 (`first`) published as in `RLE-001`; `loan-pricing` v1 (`unique`, overlapping rows) as in `RLE-002`; caller `svc-eod` holds `rules:evaluate`.
- **When:** `svc-eod` posts `POST /api/bpm/rules/v1/decisions/card-fee:evaluate-batch` with four items: (0) `{ tier: GOLD, txCount: 5 }`, (1) `{ tier: SILVER, txCount: 4 }`, (2) `{ tier: SILVER, txCount: "four" }`, (3) `{ tier: STANDARD, txCount: 0 }`, each with `correlationId` `eod-2027-01-31-000<n>`. Then it posts two `loan-pricing` items: one matching `R1` only, and one matching `R1` and `R2`.
- **Then:**
  - The `card-fee` call answers `200` with `results` in index order. Item 0 is `R1`, fee 0 VND. Item 1 is `R2`, fee 10000 VND. Item 2 is a `problem` with `code: INPUT_INVALID` naming `txCount`. Item 3 is `R3`, fee 50000 VND. Every result carries `version: 1` and the same `evalClock`.
  - Items 0, 1 and 3 each have their own `decisionId` and one `rle_decision` row carrying that item's `correlation_id`. Item 2 writes no row, because an input that fails validation is not a decision, as on `:evaluate`.
  - The `loan-pricing` call answers `200`: the first item is `Matched`, and the second is a `problem` with `code: UNIQUE_HIT_VIOLATION` and `matchedRuleIds [R1, R2]`, which is logged as a decision row with that outcome (`RLE-002`).
  - The response carries `succeeded: 3, failed: 1` and `succeeded: 1, failed: 1`.
  - One version is selected for the whole batch, at the request clock, and items run in order. A `correlationId` outside `[A-Za-z0-9._:-]{1,128}` fails its own item with `INPUT_INVALID`.
- **Example:** `4 card-fee items -> [R1 0 VND, R2 10000 VND, INPUT_INVALID txCount, R3 50000 VND]; succeeded 3, failed 1; 3 rows`.
- **Test ID:** `RLE_067_batch_returns_one_result_per_item_and_a_failing_item_does_not_stop_the_rest`

### RLE-068 A batch is bounded as a whole and per item
- **Traces to:** `TR-SEC-02`, `TR-SEC-07`
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `evaluation.max-batch` at 1,000, and `evaluation.regex-max-reads-per-request` lowered in a test so that it runs out at item 3 of 5.
- **When:** Posting 0 items; 1,001 items; a body over the batch limit (4,194,304 bytes); a batch for a rule set with no version in effect; a batch whose item 3 trips the request regex budget; a batch whose item 1 exceeds the step budget.
- **Then:**
  - 0 items is `400 INPUT_INVALID`. 1,001 items is `422 BATCH_TOO_LARGE` (`evaluation.max-batch` may be lowered, never raised). The oversized body is `413 PAYLOAD_TOO_LARGE`, refused before the body is read when `Content-Length` declares it. No effective version is `404 NO_EFFECTIVE_VERSION`, an unknown rule set `404 RULE_SET_NOT_FOUND`. In these cases nothing is evaluated or written.
  - The regex case follows `RLE-007`: items 0 to 2 keep their results, item 3 fails with `EVALUATION_ERROR` / `REGEX_TIMEOUT` naming `evaluation.regex-max-reads-per-request`, and item 4 is reported failed and "not evaluated", with no decision row.
  - The step budget is per item: item 1 fails with `EVALUATION_BUDGET_EXCEEDED`, and the others succeed.
- **Example:** `1,001 items -> 422 BATCH_TOO_LARGE; 5 long references, budget for 3 -> 3 results, 1 REGEX_TIMEOUT, 1 not evaluated, 4 rows`.
- **Test ID:** `RLE_068_a_batch_is_bounded_as_a_whole_and_per_item`

### RLE-069 A batch is idempotent, commits with its transaction and stays in the caller's tenant
- **Traces to:** `TR-IDEM-01`, `TR-EMB-04`, `TR-TEN-01`
- **Milestone:** M2
- **Kind:** control
- **Given:** The batch of `RLE-067` with `Idempotency-Key: k-701`.
- **When:** It is repeated with `k-701` and the same body, then sent with `k-701` and a changed item. A Java host calls `evaluateBatch` inside `@Transactional` and rolls back. A caller of `bank-b` posts the batch with `"tenantId": "bank-a"` in the body. A caller sends `"clock"` without `rules:simulate`.
- **Then:**
  - The repeat returns the same `200` body, decision ids included, and writes no row. The changed body is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`. The stored response holds outputs and ids only, never inputs.
  - The rolled-back host transaction leaves 0 rows. Over REST the batch's rows commit in one transaction: all of them or none.
  - The `bank-b` caller is served from `bank-b` (`404 RULE_SET_NOT_FOUND` if `bank-b` has no `card-fee`), and the body tenant is ignored.
  - The clock without the permission is `403 CLOCK_NOT_ALLOWED`. Masked inputs are fingerprinted per item exactly as on `:evaluate` (`RLE-012`), and results never echo inputs.
- **Example:** `repeat with k-701 -> same body, 0 new rows; changed item -> 422; host rollback -> 0 rows; bank-b -> 404`.
- **Test ID:** `RLE_069_batch_is_idempotent_transactional_and_tenant_scoped`

---

## Import and Export (RE-F-25)

### RLE-032 An exported version imports back as a draft with the same canonical hash
- **Traces to:** `TR-STD-03`, `TR-STD-28`, `TR-UI-02`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Tenant `bank-a`; `card-fee` v1 `PUBLISHED`, imported from `card-fee.yaml` with comments and `effectiveFrom: 2026-11-01T00:00:00Z`, canonical hash `H1` (computed without `effectiveFrom`); caller `lan` holds `rules:read` and `rules:author` in `bank-a` and in `bank-a-fin`.
- **When:** `lan` exports v1 with `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1/export?format=json` and with `format=yaml`; imports the JSON file with `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` in `bank-a`; and imports the YAML file with `POST /api/bpm/rules/v1/rule-sets` in `bank-a-fin`, where no `card-fee` exists. Failure cases: `format=xlsx`; version 9.
- **Then:** The JSON export is the RFC 8785 canonical document written from the DMN model (`RLE-024`), byte for byte, with a `Content-Disposition` file name `card-fee-v1.json`; the YAML export holds the same model. Neither file carries tenant, status, `lockVersion`, `effectiveFrom`, requester, approver or decision data. The JSON import creates `card-fee` v2 as `DRAFT` in `bank-a` with canonical hash `H1`; the YAML import creates `card-fee` v1 as `DRAFT` in `bank-a-fin` with canonical hash `H1`. Each import stores its own file as the source document (`RLE-020`); the original comments stay available on v1 through `?include=source`. Both drafts have no `effective_from` until `:request-publish` sets one. `format=xlsx` returns `400 INPUT_INVALID`; version 9 returns `404 VERSION_NOT_FOUND`. Export writes nothing.
- **Example:** `export card-fee v1 as JSON -> import into bank-a as v2 DRAFT, canonical hash H1 = H1; YAML into bank-a-fin -> v1 DRAFT, hash H1`.
- **Test ID:** `RLE_032_export_round_trips_to_same_canonical_hash`

### RLE-033 Importing a file as a new version creates the next draft; a mismatched file is refused whole
- **Traces to:** `TR-STD-01`, `TR-IDEM-01`, `TR-UI-02`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Tenant `bank-a`; `card-fee` has v1 `PUBLISHED` and v2 `DRAFT`; caller `lan` holds `rules:author`. Files: `card-fee-v3.yaml` (valid, `ruleSet: card-fee`); `kyc-risk.yaml` (`ruleSet: kyc-risk`); `card-fee-bad.yaml` whose row `R4` has `balance >= 50000000` with no currency.
- **When:** `lan` posts `card-fee-v3.yaml` to `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` with `Idempotency-Key: k-401`, then repeats the call; posts `kyc-risk.yaml` to the same `card-fee` path; posts `card-fee-bad.yaml` to it; posts `card-fee-v3.yaml` to `POST /api/bpm/rules/v1/rule-sets`, where `card-fee` already exists.
- **Then:** The first post returns `201` with `card-fee` v3 `DRAFT`; the repeat returns the same `201` and creates no v4. The `kyc-risk` file returns `400 INPUT_INVALID` naming the mismatch between the path and the document's `ruleSet`. The bad file returns `422 VALIDATION_FAILED` naming row `R4` and column `balance`. The post to `/rule-sets` returns `409 RULE_SET_EXISTS`. In every failure case no version and no source document is stored, and v1 and v2 are unchanged.
- **Example:** `card-fee-v3.yaml -> 201 v3 DRAFT; replay k-401 -> same 201; kyc-risk.yaml on card-fee -> 400; R4 without currency -> 422 at R4/balance`.
- **Test ID:** `RLE_033_import_as_new_version_creates_next_draft_or_refuses_whole`

### RLE-191 A version exports as a spreadsheet file that Excel opens, with the shorthand cells
- **Traces to:** `TR-STD-03`, `TR-UI-02`, PD-107, PD-142, #39
- **Milestone:** M1
- **Kind:** other
- **Given:** Tenant `bank-a`; `card-fee` v1 `PUBLISHED` with hit policy `first`, inputs `tier` (string), `balance` (money) and `opened` (date), outputs `fee` (money) and `waived` (boolean), and rules `R1` (`tier in ['GOLD', 'PLATINUM'] -> fee 0 VND, waived true`, description "Gold and Platinum are free"), `R2` (`tier == 'SILVER'`, `balance >= 50000000 VND -> fee 50000 VND`), `R3` (`opened` between 2025-01-01 and 2025-12-31 -> fee 20000 VND) and `R4` (`{} -> fee 100000 VND`); caller `dan` holds `rules:read`.
- **When:** `dan` calls `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1/export?format=csv`.
- **Then:**
  - The file is UTF-8 with a byte order mark, comma separated, CRLF line ends and RFC 4180 quoting. The header is `Rule,tier,balance,opened,fee,waived,Description`: input and output names, never translated labels, so one file serves both languages.
  - One line per rule in table order: `R1,"GOLD, PLATINUM",-,-,0 VND,true,Gold and Platinum are free`, `R2,SILVER,>= 50000000 VND,-,50000 VND,false,`, `R3,-,-,2025-01-01 to 2025-12-31,20000 VND,false,`, `R4,-,-,-,100000 VND,false,`. Amounts have no thousands separators, dates are ISO dates, `-` is any. A text that looks like shorthand (`-`, `null`, `<5`, one with a comma, a leading `=`) is written in double quotes and reads back as the same text; a description that starts with `=`, `+`, `-`, `@`, a tab or a carriage return is prefixed with `'`, which import removes.
  - The response is `200 text/csv;charset=UTF-8` with `Content-Disposition: attachment; filename="card-fee-v1.csv"`. Export writes nothing and needs `rules:read` in the caller's tenant; another tenant's rule set is `404 RULE_SET_NOT_FOUND`.
  - A version that uses an `any` or `all` group (R4 grouped), a list input or output, or a calculated output cell has no table form: `422 VALIDATION_FAILED` with one violation, code `CSV_NO_TABLE_FORM`, its `ruleId` (or `column`) naming where. Nothing is returned.
- **Example:** `export card-fee v1 as CSV -> header Rule,tier,balance,opened,fee,waived,Description; R1 "GOLD, PLATINUM"; R4 grouped -> 422 CSV_NO_TABLE_FORM`.
- **Test ID:** `RLE_191_a_version_exports_as_utf8_csv_with_a_byte_order_mark_crlf_and_the_shorthand`, `RLE_191_a_version_with_a_grouped_condition_has_no_spreadsheet_form_and_names_the_rule`, `RLE_191_exporting_needs_the_read_permission_and_stays_in_the_callers_tenant`, `RLE_191_export_csv_answers_a_utf8_file_named_after_the_version`, `RLE_191_a_text_that_looks_like_shorthand_is_written_quoted_and_reads_back_the_same`

### RLE-192 A spreadsheet file imports as the next draft against a base version, with the checks of a document
- **Traces to:** `TR-STD-01`, `TR-IDEM-01`, `TR-TEN-01`, `TR-UI-02`, PD-107, PD-142, #39
- **Milestone:** M1
- **Kind:** other
- **Given:** The file of `RLE-191` in which R2's fee reads `30000 VND` instead of `50000 VND`; caller `lan` holds `rules:author` in `bank-a`.
- **When:** `lan` posts it to `POST /api/bpm/rules/v1/rule-sets/card-fee/versions?base=1` with `Content-Type: text/csv` and `Idempotency-Key: k-39-1`, then repeats it.
- **Then:**
  - `201`: `card-fee` v2 as `DRAFT`, created by `lan`. Its hit policy, inputs, outputs, masks and every other part of the document outside the rows are v1's; its rows are the file's, each cell turned into the engine's normal form (`GOLD, PLATINUM` is `in ['GOLD', 'PLATINUM']`, `18 to 65` is `between 18 and 65`, `[18..65)` is `age >= 18 and age < 65`, `!= VN, SG` is `not in ['VN', 'SG']`). A rule's `explain` texts are carried over by rule id, and its description when the file has no `Description` column. The source stored with the draft is the JSON document the rows make, in source format `JSON`.
  - The file is read as it comes: comma or semicolon separated (taken from the header line), a byte order mark optional, a blank row ignored, at most 1,048,576 bytes (`413 PAYLOAD_TOO_LARGE`). A blank `Rule` cell gets `R` plus the first number from its row position upward that no other rule in the file uses; a duplicate id is refused (`RLE-194`).
  - The rows pass the checks of a document (`RLE-041`, the step budget, `evaluation.max-rows` and the mask rules of `RLE-050`), so a type mismatch in a cell is listed as in `:validate`, with its `ruleId` and `column`; the character position of such a problem is dropped, since it would count in text the author did not write.
  - The repeat with `k-39-1` returns the same `201` and creates no v3; the same key with another file is `409 IDEMPOTENCY_KEY_REUSE_MISMATCH`.
  - An export of v1 imported against v1 without an edit has the same canonical hash as v1.
  - Needs `rules:author` (`403` before the body is read); `base` is read in the caller's tenant only (`404 RULE_SET_NOT_FOUND`, `404 VERSION_NOT_FOUND`). A CSV body without `base`, a `base` that is not a version number of 1 or more, or `base` with another content type is `400 INPUT_INVALID`. A failure writes nothing.
- **Example:** `card-fee-v1.csv with R2 at 30000 VND, ?base=1 -> 201 v2 DRAFT, R2 fee 30000 VND; unedited export -> same canonical hash`.
- **Test ID:** `RLE_192_a_file_that_changes_one_fee_becomes_the_next_draft_with_the_same_columns_and_hit_policy`, `RLE_192_an_unedited_export_imports_back_with_the_same_canonical_hash`, `RLE_192_a_repeated_key_returns_the_original_draft_and_a_new_key_makes_the_next_one`, `RLE_192_a_semicolon_file_without_a_byte_order_mark_is_read_and_a_blank_rule_gets_the_next_free_id`, `RLE_192_the_file_needs_the_author_permission_a_base_in_the_callers_tenant_and_an_existing_base`, `RLE_192_a_csv_body_with_a_base_answers_201_and_reaches_the_engine_with_its_key`, `RLE_192_a_csv_body_needs_a_base_and_a_base_needs_a_csv_body`, `RLE_192_a_reader_is_refused_before_the_file_is_read`, `RLE_192_shorthand_cells_become_the_normal_forms_of_the_engine`, `RLE_192_a_quoted_text_keeps_its_commas_and_a_half_open_range_uses_brackets`

### RLE-193 A damaged or refused cell is named by rule and column and never quoted
- **Traces to:** `TR-STD-01`, `TR-SEC-04`, `TR-DET-02`, PD-107, PD-142, #39
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The file of `RLE-191` with one cell changed at a time: R2's `balance` reads `>= 50000000`; `>= 5E+07 VND`; `">= 50,000,000 VND"`; `>= 50.000.000 VND`; R3's `opened` reads `20/11/2026`; R2's `balance` reads `>= about fifty million VND`; R2's `fee` is empty. A second version of `card-fee` has a masked input `nationalId` (integer) and a cell `CCCD 079123456789` in that column.
- **When:** Each file is imported with `?base=1`.
- **Then:**
  - Each is `422 VALIDATION_FAILED` with one violation naming the `ruleId` and the `column` and a stable `code`: `CSV_CELL_NO_CURRENCY`, `CSV_CELL_SCIENTIFIC`, `CSV_CELL_SEPARATORS` (twice), `CSV_CELL_DATE`, `CSV_CELL_NOT_A_NUMBER`, `CSV_CELL_EMPTY`. The catalogue gives each code a plain message in every language it ships (`en`, `vi`), for example "The amount has no valid currency. Write it after the number, for example 50000000 VND."
  - No damaged value is read as another value: `5E+07` is never 50,000,000, `50,000,000` is never the list 50, 0, 0, `50.000.000` is never 50, and a date that is not `year-month-day` is never guessed.
  - A problem lists every bad cell of the file in file order, at most 100, with `violationsTruncated: true` beyond that. Nothing is stored.
  - No message, code or position quotes a cell. The `nationalId` cell problem names `R1` and `nationalId` and holds no digit of the cell; a problem the checks of a document find in a masked column has the plain message "The cell is not accepted".
- **Example:** `R2 balance ">= 50000000" -> 422 CSV_CELL_NO_CURRENCY at R2, balance; "5E+07 VND" -> CSV_CELL_SCIENTIFIC`.
- **Test ID:** `RLE_193_an_amount_without_a_currency_is_refused_with_its_rule_and_column_and_nothing_is_saved`, `RLE_193_excel_damage_is_refused_and_never_read_as_another_value`, `RLE_193_no_problem_quotes_a_cell_so_a_masked_value_never_leaves_in_an_error`, `RLE_193_a_refused_cell_is_422_validation_failed_with_its_rule_column_and_code`

### RLE-194 A file cannot add, remove, rename or reorder a column, and its rows must be well formed
- **Traces to:** `TR-STD-01`, `TR-STD-03`, PD-107, PD-142, #39
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The file of `RLE-191` with, one at a time: `balance` renamed `segment`; the `waived` column removed; `tier` and `balance` swapped; R2's id changed to `R1`; R4 cut to three cells; a file saved as Windows-1252; an empty file.
- **When:** Each is imported with `?base=1`.
- **Then:**
  - The header must list exactly the base version's inputs and outputs, by name, in order, then optionally `Description` last. An added column is `CSV_COLUMN_UNKNOWN` (`column: segment`); a missing one `CSV_COLUMN_MISSING` (`column: balance`, and `waived`); a repeated one `CSV_COLUMN_DUPLICATE`; one out of order `CSV_COLUMN_ORDER`. Inputs and outputs cannot change through a file.
  - A duplicate rule id is `CSV_RULE_DUPLICATE` on the later row; an id that does not start with a letter or uses other characters than letters, digits, `-` and `_` (at most 64) is `CSV_RULE_ID_INVALID`; a row with a different number of cells than the header is `CSV_ROW_SHAPE`; a file that is not UTF-8 is `CSV_ENCODING`; no rows is `CSV_EMPTY`; an unclosed quote is `CSV_FILE_INVALID`.
  - Each is `422 VALIDATION_FAILED`; nothing is stored. A cell that reads, and then fails the checks of a document, is refused with the document's codes (a fee that is a boolean: `TYPE_MISMATCH` at `R1`, `fee`), without position.
- **Example:** `balance renamed segment -> 422 CSV_COLUMN_UNKNOWN segment + CSV_COLUMN_MISSING balance`.
- **Test ID:** `RLE_194_a_file_cannot_add_remove_rename_or_reorder_a_column`, `RLE_194_a_duplicate_rule_id_a_short_row_a_bad_encoding_and_an_empty_file_are_refused`, `RLE_194_a_change_the_engine_checks_on_import_is_refused_by_the_same_checks_as_a_document`

### RLE-047 The author replaces their draft in place, with the import checks and without a new version
- **Traces to:** `TR-STD-01`, `TR-STD-03`, `TR-IDEM-01`, `TR-SEC-04`, `TR-UI-02`, PD-094
- **Milestone:** M1
- **Kind:** semantics
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

### RLE-090 A draft's edited table is validated as a save would validate it, and nothing is stored
- **Traces to:** `TR-DET-02`, `TR-UI-02`, `TR-SEC-02`, `TR-TEN-01`, PD-113
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Tenant `bank-a` with `horus.bpm.rules.authoring.cockpit-drafts: true`; `card-fee` v1 `PUBLISHED`; v2 `DRAFT` created by `lan` from `card-fee.yaml`, `lockVersion 0`; `lan` holds `rules:author`, `dan` holds `rules:read`; a caller of `bank-b` holds `rules:author`. Tenant `bank-a` declares `kyc-routing` v1 `DRAFT` whose input `nationalId` has `mask: true` and whose `R1` tests `nationalId == '079123456789'`.
- **When:** `lan` sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:validate` with (a) `card-fee.yaml` with `R2` writing `fee: "0 VND"`; (b) `card-fee.yaml` with `R2` testing `txCount: ">= tierX"`. Failure cases: the same body for v1; for v9; as `dan`; as the `bank-b` caller; with `ruleSet: fx-margin`; with a body of 1,048,577 bytes; with drafts off. `lan` also validates `kyc-routing`.
- **Then:**
  - (a) is `200` with `valid: true`, `problems: []` and one reading per rule, `R1` to `R3`, in `en` and `vi` (`R3`: "Rule R3 always applies; it has no condition. Then fee is 50,000 VND."), written by the same reader as `RLE-091`. (b) is `200` with `valid: false`, `readings: []` and one problem on `ruleId: R2`, `column: txCount`, `code: UNKNOWN_INPUT`, `message.en` "The cell uses a name that is not a declared input." and `message.vi` "Ô này dùng một tên không phải là đầu vào đã khai báo."; a save of the same body is `422 VALIDATION_FAILED` naming the same rule and column, because both run the same checks.
  - A problem's message is chosen by its code from the message catalogue of the explanations (`en`, `vi`, a host's own languages) and never repeats text of the cell. A problem with no code is `RULE_INVALID`, `COLUMN_INVALID` or `DOCUMENT_INVALID` by where it is. At most 100 problems are listed, with `problemsTruncated` when more were found.
  - A valid draft is never refused for its size. A valid draft of 501 rows, and one of 5,000 rows (the most a draft may have), is `200` with `valid: true`, `problems: []`, `readings: []`, `readingsTruncated: true` and `readingsReason: "TABLE_TOO_LARGE"`; a valid draft of 500 rows or fewer has its readings, `readingsTruncated: false` and `readingsReason: null`; a document with problems has `readings: []`, `readingsTruncated: false` and `readingsReason: null`. `:validate` is never `422 READINGS_TOO_LARGE`; only the readings endpoints of `RLE-091` to `RLE-093` answer that.
  - The reading of `kyc-routing` `R1` says that `nationalId` is masked and its test is not shown; the value `079123456789` appears nowhere in the answer.
  - v1 is `409 INVALID_STATE`; v9 is `404 VERSION_NOT_FOUND`; `dan` is `403 PERMISSION_DENIED`, checked before the body is read; the `bank-b` caller is `404 RULE_SET_NOT_FOUND`; the `fx-margin` file is `400 INPUT_INVALID`; the large body is `413 PAYLOAD_TOO_LARGE`; with drafts off `lan` gets `403 DRAFT_AUTHORING_DISABLED`.
  - No call writes a version, a history row, an event or an idempotency record, and the call takes no `Idempotency-Key` and no `If-Match`. Every `rle_` table is byte for byte as it was.
- **Example:** `lan validates card-fee v2 with R2 testing txCount >= tierX -> 200, valid false, R2 / txCount / UNKNOWN_INPUT with an English and a Vietnamese message; 0 rows written`.
- **Test ID:** `RLE_090_a_cell_problem_is_placed_on_its_rule_and_column_with_a_code_and_both_languages`; `RLE_090_a_valid_draft_of_501_rows_is_valid_and_leaves_its_readings_out_with_a_reason`; `RLE_090_a_valid_draft_of_5000_rows_is_valid_and_leaves_its_readings_out_with_a_reason`; `RLE_090_a_valid_table_of_500_rows_still_has_its_readings`; `RLE_090_a_valid_table_too_large_to_read_is_answered_200_with_readings_truncated_and_a_reason`

### RLE-091 Every row of a version has a plain reading in each language and in two styles
- **Traces to:** `TR-UI-02`, `TR-DET-01`, `TR-SEC-02`, `TR-TEN-01`, PD-027, PD-123, PD-077
- **Milestone:** M2
- **Kind:** other
- **Given:** Tenant `bank-a`; `account-tier` v1 `PUBLISHED` with inputs `balance` (money, label `Balance`), `segment` and `nationalId` (`mask: true`), outputs `tier` (label `Tier`) and `fee`, and rows `R1` (`balance >= 50000000 VND`, `segment == 'VIP'`, then `tier 'GOLD'`, `fee 0 VND`), `R2` (`balance between 10000000 VND and 49999999 VND`, `nationalId == '079123456789'`), `R3` (`segment in ['STAFF', 'PARTNER']`, with the English sentence "Staff and partners hold {output.tier} and pay {output.fee}."), `R4` (`segment == 'MASS'`, with the English sentence "Mass customers: {input.segment}.") and `R5` (no condition). `dan` holds `rules:read`, `teller` holds only `rules:evaluate`, a caller of `bank-b` holds `rules:read`. A version `big-table` has 501 rows.
- **When:** `dan` sends `GET /api/bpm/rules/v1/rule-sets/account-tier/versions/1/readings`. Failure cases: as `teller`; as the `bank-b` caller; for version 7; for `big-table`.
- **Then:**
  - `200` with `languages: ["en", "vi"]` and one entry per row in table order with its `position`. Each entry has, per language, `full`, `short` and `source`. `R1` in `en`: full "Rule R1 applies when Balance is at least 50,000,000 VND and segment is Vip. Then fee is 0 VND; Tier is Gold.", short "Balance at least 50,000,000 VND, Vip: fee 0 VND; Tier Gold". In `vi` the numbers are grouped as `50.000.000 VND`.
  - The short form is a compact phrase in plain words (#189 review): the conditions, a colon, then the result. It holds no `=`, arrow, comparison sign or raw expression. A test on a text value names the case by the value alone in `en` ("Gold: fee 0 VND") and with the label in `vi` ("Hạng Gold: phí 0 VND"); a number test keeps its label ("Tx count at least 3"); a test that is not one of the simple shapes reads "{label} meets a custom test", never the expression. A row with no condition reads "Any other case: ..." in `en` and "Mọi trường hợp khác: ..." in `vi`. A yes or no result reads "waived: Yes". The wording is in the catalogue (`short.*`), so a host changes it without code.
  - A name is the `label` the rule set declares for the input or output, else its name in words; the label is one text and is used in every language. A code such as `GOLD` or `FIRST_TIME` is worded as the rule table shows it, `Gold` and `First time` (a code of one or two characters, such as `VN`, and other text stay as written), in the full sentence and the short form, in conditions and results, so a reading and the table beside it agree. The rule set declares no display label for a value, so none is used. A decision explanation (`RLE-072`) is unchanged and keeps the value as logged.
  - Numbers, money, booleans and input and output labels are worded by the catalogue and the labels that explain a decision (`RLE-072`); an input without a label is its name in words. The sentences are in the message catalogue (`explain_<language>.properties`), so a host adds a language or overrides a sentence without code.
  - When the author wrote a sentence for the row and the language and every placeholder in it can be filled from the table (`{ruleId}`, `{ruleSet}`, `{version}`, `{output.name}` of a literal output), `full` is that sentence and `source` is `TEMPLATE`: `R3` in `en` reads "Staff and partners hold Silver and pay 0 VND." and in `vi` it is `GENERATED`. A sentence that needs the value of an input (`R4`) is not used, because a table holds none: `R4` is `GENERATED`.
  - `R2` says that the national id is masked and its test is not shown; `079123456789` appears nowhere in the answer. A masked input is by its declaration or by the tenant's `decision-log.mask-default`. Masking covers input values: no readings, simulation, `:validate` or decision endpoint writes the value of a masked input (a sample's inputs are not echoed). A literal written in a rule cell, such as `079123456789` above, is rule text that an author typed: the version document and the version diff return it as authored, so an author must not type a real person's number into a rule, and the demo and test data use a made-up value.
  - The same request returns the same body. The reading writes no row.
  - `teller` is `403 PERMISSION_DENIED`; the `bank-b` caller is `404 RULE_SET_NOT_FOUND`; version 7 is `404 VERSION_NOT_FOUND`; `big-table` is `422 READINGS_TOO_LARGE` naming the limit of 500 and the 501 rows, and no row is returned.
- **Example:** `account-tier v1, R1 -> en: "Rule R1 applies when Balance is at least 50,000,000 VND and segment is Vip. Then fee is 0 VND; Tier is Gold."; vi: "Quy tắc R1 áp dụng khi Balance ít nhất là 50.000.000 VND và segment là Vip. Khi đó fee là 0 VND; Tier là Gold."; card-fee R1 (GOLD, labels Hạng and phí) -> en short "Gold: phí 0 VND", vi short "Hạng Gold: phí 0 VND"`
- **Test IDs:** `RLE_091_the_short_form_is_a_compact_phrase_in_plain_words_in_each_language`, `RLE_091_a_code_is_worded_as_the_rule_table_shows_it_and_a_label_is_used_in_every_language`
- **Test ID:** `RLE_091_every_row_reads_in_english_and_vietnamese_as_a_full_sentence_and_a_short_form`

### RLE-092 A diff of two versions has the old and the new reading of each changed row
- **Traces to:** `TR-UI-02`, `TR-DET-01`, `TR-SEC-02`, `TR-TEN-01`, PD-027, PD-099
- **Milestone:** M2
- **Kind:** other
- **Given:** `account-tier` v1 as in `RLE-091`; v2 raises the threshold of `R1` to `60000000 VND` and drops `R4`; a v3 differs from v1 only in a description of `R5`.
- **When:** `dan` sends `GET .../rule-sets/account-tier/versions/2/diff/readings?base=1`, then `versions/3/diff/readings?base=1`. Failure cases: no `base`; `base` equal to the version; a diff that lists 501 rows; as `teller`.
- **Then:**
  - v2 against v1 lists `R1` (`CHANGED`) and `R4` (`REMOVED`), in the order of `GET .../diff`. `R1` has `previous` with "Balance is at least 50,000,000 VND" and `current` with "Balance is at least 60,000,000 VND" in each language (short forms as in `RLE-091`), in both styles, and `readingChanged: true`. `R4` has `previous` and an empty `current`. An `ADDED` row has `current` and an empty `previous`. `unchangedCount` is 3.
  - v3 against v1 lists `R5` with `readingChanged: false`, so a screen strikes nothing.
  - Each side is read as in `RLE-091` in its own version, so a masked input is named as masked and its test is never written on either side.
  - No `base`, or `base` equal to the version, is `400 INPUT_INVALID`; more than 500 listed rows is `422 READINGS_TOO_LARGE`; `teller` is `403`; a version of another tenant is `404 RULE_SET_NOT_FOUND`.
- **Example:** `account-tier v2 vs v1, R1 -> previous.en.short "Balance at least 50,000,000 VND, Vip: ..."; current.en.short "Balance at least 60,000,000 VND, Vip: ..."; readingChanged true`
- **Test ID:** `RLE_092_a_diff_reads_each_changed_row_in_both_versions`

### RLE-093 A simulation result is read in plain language, each side in the version that decided it
- **Traces to:** `TR-UI-02`, `TR-DET-01`, `TR-SEC-02`, `TR-TEN-01`, PD-027
- **Milestone:** M2
- **Kind:** other
- **Given:** `account-tier` v1 in force and v2 a `DRAFT` as in `RLE-092`; `analyst` holds `rules:simulate` and `rules:read`; `viewer` holds only `rules:read`.
- **When:** `analyst` sends `POST .../rule-sets/account-tier/versions/2:simulate-readings` with a sample of a VIP customer whose balance is 55,000,000 VND and whose `nationalId` is `079123456789`, labelled "VIP customer, balance 55,000,000 VND", and one of a MASS customer with no label. A sample with a label of 101 characters, one with a label that is not text and one with an empty label. Failure cases: as `viewer`; as `analyst` without `rules:read`; 501 samples.
- **Then:**
  - `200`, one `results` entry per sample in the order sent, with `changed` as `:simulate` reports it. Sample 0 is changed: `baseline` is `MATCHED` on `R1`, read from v1 ("Rule R1 applies when Balance is at least 50,000,000 VND and segment is Vip. Result: Tier is Gold; fee is 0 VND."), and `simulated` is `MATCHED` on `R5`, read from v2, short form "R5: Tier Basic; fee 50.000 VND" in `vi`. A no-match reads "No rule of account-tier version 1 matched. No output was produced." with the short form "No rule matched"; a failure reads as the explanation of a failed decision with the short form "Failed: EVALUATION_ERROR". With no effective version `baseline` is null.
  - Each entry names its sample (#189 review): `reference` is `S1`, `S2`, ... by position, always present, and `label` is the text the caller sent with the sample, written back as sent, or null. Sample 0 has `label` "VIP customer, balance 55,000,000 VND" and sample 1 has `label: null` and `reference: S2`. The engine never builds a label from the inputs; a caller who writes a masked value into a label sees it echoed. `:simulate` checks the label and ignores it. A label that is not text, is empty or is over 100 characters is `400 INPUT_INVALID` and nothing runs (`VersionService.readSimulation(result, labels)` refuses one over 100 with `IllegalArgumentException`).
  - The inputs of the samples are not echoed: neither `079123456789` nor `55,000,000` is in the answer (the label is the caller's text, not an input).
  - Both permissions are checked before the body is read, so a caller who lacks either is `403 PERMISSION_DENIED` and no body is buffered. 501 samples are `422 READINGS_TOO_LARGE`. Nothing is logged: no decision row, idempotency record or event, and no `Idempotency-Key` is taken.
- **Example:** `sample S1 "VIP customer, balance 55,000,000 VND" -> baseline R1 (Tier Gold, fee 0 VND), simulated R5 (Tier Basic, fee 50,000 VND), changed true`
- **Test IDs:** `RLE_093_a_simulation_result_reads_each_side_in_the_version_that_decided_it_and_never_shows_the_inputs`, `RLE_093_each_simulated_sample_carries_the_label_it_was_sent_with_and_a_reference`, `RLE_093_the_label_of_a_sample_is_passed_on_and_written_back_with_its_reference`, `RLE_093_a_label_that_is_not_text_of_1_to_100_characters_is_400_and_nothing_runs`

### RLE-098 An author discards a draft that is no longer wanted; it stays in the history
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, `TR-TEN-01`, #418
- **Milestone:** M2
- **Kind:** semantics
- **Given:** Tenant `bank-a`. `card-fee` v1 `PUBLISHED`; v2 `DRAFT` created by `lan` (`rules:author`), `lockVersion 0`, canonical hash `H2`. `minh` holds `rules:author`; `bob` holds `rules:approve`; a caller of `bank-b` holds `rules:author`; `dan` holds `rules:read`. The host sets `authoring.cockpit-drafts` to `false` in one case.
- **When:** `lan` sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:discard` with `Idempotency-Key: k-910`, `If-Match: "0"` and the reason "Started from the wrong version", then repeats it. Failure cases: `minh`; `bob`; `dan`; the `bank-b` caller; `lan` on v1 (published); `lan` on v2 again with a new key; a stale `If-Match`; no `If-Match`; no key; a reason of 2,001 characters. Then `lan` imports a new version and lists the versions.
- **Then:**
  - `200`; v2 is `DISCARDED` with `lockVersion 1` and `ETag: "1"`, keeps its number, author, document and hash `H2`, and no row of the version or its rules is deleted. The repeat with `k-910` returns the same body and changes nothing.
  - One history row `DISCARDED` is appended: actor `lan`, reason, `fromStatus DRAFT`, `toStatus DISCARDED`, `lockVersion 1`; it is shown by the history of v2 and the history of the rule set, and the database refuses to change or delete it. One `rules.version.discarded.v1` event carries rule set, version, tenant, `discardedBy: lan`, `canonicalHash: H2` and the reason.
  - `GET .../versions` lists v1 only and the rule set's `latestVersion` stays 1; the next import is v3: a discarded number is never used again. `allowedActions` of a draft holds `discard` for its author with `rules:author` and for nobody else.
  - `minh`, `bob` and `dan` get `403 PERMISSION_DENIED` (only the author discards), `dan` before the body is read; the `bank-b` caller gets `404 RULE_SET_NOT_FOUND`; v1 and an already discarded v2 are `409 INVALID_STATE`, so a published or retired version can never be removed this way; a stale `If-Match` is `409 VERSION_CONFLICT`; no `If-Match` is `428 PRECONDITION_REQUIRED`; no key is `400 INPUT_INVALID`; the long reason is `400 INPUT_INVALID`. A failure writes no history row, event or idempotency record and leaves v2 as it was.
  - With `authoring.cockpit-drafts: false`, `lan` can still discard: it brings in no content.
- **Example:** `lan POSTs :discard on card-fee v2 with If-Match "0" -> 200, v2 DISCARDED, lockVersion 1, 1 DISCARDED history row, 1 discarded event; GET versions -> v1 only; minh -> 403; v1 -> 409`.
- **Test ID:** `RLE_098_the_author_discards_a_draft_which_stays_in_the_history_and_leaves_the_list`

### RLE-099 A syntax error names the expected token, the position and an example, in English and Vietnamese
- **Traces to:** `TR-DET-02`, `TR-UI-02`, `TR-SEC-02`, #419
- **Milestone:** M2
- **Kind:** semantics
- **Given:** As `RLE-090`: `lan` validates draft v2 of `card-fee`. Tenant `bank-a` also declares `kyc-routing` v1 `DRAFT` whose input `nationalId` has `mask: true`.
- **When:** `lan` sends `:validate` with (a) `R2` testing `txCount: ">= (3 + 1"`; (b) `R1` testing `tier: "= 'GOLD'"`; (c) `R2` testing `txCount: ">= tierX"`; (d) `kyc-routing` with `R1` testing `nationalId: "== '079123456789"`.
- **Then:**
  - (a) is `200`, `valid: false`, one problem on `R2` / `txCount` with `code: SYNTAX_ERROR`, `position: 9`, `expected.en` "A closing parenthesis ) to match the opening one.", `expected.vi` "Dấu đóng ngoặc ) khớp với dấu mở ngoặc." and `example` "(balance + 100) > 0". (b) has `position: 0`, `expected.en` "Two equals signs == to compare for equality." and `example` "tier == 'GOLD'".
  - `expected` and `example` are chosen by the kind of syntax error the parser found (`OPERAND`, `OPERATOR_OR_END`, `OPEN_PAREN`, `CLOSE_PAREN`, `OPEN_BRACKET`, `CLOSE_BRACKET`, `KEYWORD_NULL`, `KEYWORD_AND`, `UNTERMINATED_STRING`, `SINGLE_QUOTES`, `EQUALITY`, `LOGIC_WORDS`, `NUMBER_START`, `NUMBER_DIGITS`, `STRING_ESCAPE`, `LITERAL_ARGUMENT`, `CHARACTER`) from the message catalogue, in every language it has; each kind has both in `en` and `vi`. A host language without an entry for a kind has no `expected` for it. They never repeat text of the cell.
  - (c) is `UNKNOWN_INPUT` with no `expected` and no `example`: only a syntax error has them.
  - (d) is a `SYNTAX_ERROR` that expects a closing single quote; the value `079123456789` appears nowhere in the answer. The save of the same body is `422 VALIDATION_FAILED` as before; its `message` is unchanged.
  - No call writes a version, history row, event or idempotency record.
- **Example:** `lan validates R2 with txCount ">= (3 + 1" -> SYNTAX_ERROR, position 9, expected "A closing parenthesis )...", example "(balance + 100) > 0"; a masked cell with an unterminated text -> expects a closing quote, the number appears nowhere`.
- **Test ID:** `RLE_099_a_syntax_error_names_the_expected_token_the_position_and_an_example_in_both_languages`

### RLE-100 From 1.0 an unset decision log retention refuses start-up outside dev
- **Traces to:** `TR-OPS-01`, `TR-SEC-04`, PD-050, PD-130, #373
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A host on Rule Engine `1.0.0` or later with `horus.bpm.rules.decision-log.retention` unset. The same host on `0.3.0` is the case before 1.0.
- **When:** The host starts on the profile `prod`, on no profile, on `dev, prod`, and on `dev` (optionally with `test`); then with `keep-forever` and with `P12M`; then the same unset host on `0.3.0-SNAPSHOT`.
- **Then:**
  - On `prod`, on no profile and on `dev, prod`, start-up fails with a message naming `horus.bpm.rules.decision-log.retention`, saying it is required from 1.0, offering `keep-forever` or whole months of at least 12 such as `P12M`, and pointing to the upgrade note in the wiki page Rule Engine Decision Log and Audit.
  - On `dev` alone (optionally with `test`) the host starts, keeps every decision and logs the WARN of PD-130.
  - With `keep-forever` or `P12M` the host starts on any profile; no partition is dropped for `keep-forever`.
  - Before 1.0, unset starts on every profile and logs the WARN, which now says that 1.0 will refuse to start without the setting.
  - The engine reads its version from `rule-engine.properties`, which the build writes next to the starter's classes (#450), never from the jar manifest. A 1.0 engine repackaged into a shaded jar whose manifest has no `Implementation-Version`, or run from a class directory, is refused the same way. A missing resource or a version whose major number cannot be read fails closed: start-up is refused outside `dev`, never the 0.x fallback. A 0.3.0 engine in a shaded jar warns and starts.
- **Example:** `1.0.0, profile prod, retention unset -> start-up fails naming horus.bpm.rules.decision-log.retention; keep-forever -> starts; 0.3.0 -> WARN and starts; 1.0.0 shaded without a manifest version -> start-up fails`.
- **Test ID:** `RLE_100_from_1_0_an_unset_retention_refuses_start_up_outside_dev_and_until_then_it_warns`, `RLE_100_the_required_from_version_is_read_as_a_major_number`, `RLE_100_a_1_0_engine_in_a_shaded_jar_without_a_manifest_version_refuses_start_up_on_prod`, `RLE_100_a_1_0_engine_run_from_a_class_directory_refuses_start_up_on_prod`, `RLE_100_a_0_3_engine_in_a_shaded_jar_still_warns_and_starts`, `RLE_100_an_unparseable_build_version_fails_closed`, `RLE_100_the_starter_ships_its_build_version_as_a_resource_beside_the_check`

### RLE-062 An earlier version is restored as the next draft and needs four-eyes approval
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`, PD-121
- **Milestone:** M1
- **Kind:** control
- **Given:** Tenant `bank-a`, with `authoring.cockpit-drafts` at its default (`false`). `card-fee` v1 (fee for `SILVER` with 3 or more transactions: 10000 VND, canonical hash `H1`) was `PUBLISHED` and is now `RETIRED`, superseded by v2 (same row at 30000 VND, `H2`), which is in effect. `lan` holds `rules:author`; `minh` holds `rules:approve`.
- **When:** `lan` sends `POST .../rule-sets/card-fee/versions?copyOf=1` with no body and `Idempotency-Key: k-601`, then repeats it. Then `lan` requests publication of v3, `lan` tries to approve it, `minh` reads `GET .../versions/3/diff?base=2` and `?base=1`, and `minh` approves. The Java form is `VersionService.copyVersion("card-fee", 1, options)`.
- **Then:**
  - `201` with `card-fee` v3 `DRAFT`, `createdBy: lan`, canonical hash `H1`, no `effectiveFrom`, and `copiedFrom: 1`. The source document is v1's (format and hash), so `?include=source` shows what was originally imported. No version is reopened or edited, and no approval, effective date or acknowledgement is copied.
  - One history row `CREATED` with actor `lan` and `copied_from = 1`, and one `rules.version.created.v1` event with the additive member `copiedFrom: 1`.
  - The repeat with `k-601` returns the same `201` and creates no v4.
  - v1 and v2 are unchanged: their status, history and hashes stay as they were.
  - `lan`'s approval is `403 SELF_APPROVAL_FORBIDDEN`: whoever restores a version is its maker.
  - The diff against v2 lists `R2` `CHANGED` from 30000 to 10000 VND. The diff against v1 has no row change and equal canonical hashes.
  - `minh`'s approval publishes v3 and, with `effectiveFrom` at approval, retires v2 as superseded (`RLE-027`). Evaluating `{ tier: SILVER, txCount: 5 }` then gives 10000 VND from v3. The history shows v1, v2 and v3, with v3's `CREATED` row naming v1.
  - A restore of a version in any status (`DRAFT`, `PENDING_APPROVAL`, `PUBLISHED`, `RETIRED`) is allowed, and `authoring.cockpit-drafts: false` does not refuse it: it brings in no content that was not already approved (PD-121). The setting goes on governing `PUT` (`RLE-047`).
- **Example:** `lan POSTs ?copyOf=1 on card-fee (v1 retired, v2 in effect) -> 201, v3 DRAFT, copiedFrom 1, hash H1; lan approves v3 -> 403; minh approves -> v3 serves 10000 VND`.
- **Test ID:** `RLE_062_an_earlier_version_is_restored_as_the_next_draft_and_needs_approval`

### RLE-063 A restore passes today's import checks and refuses what it cannot copy
- **Traces to:** `TR-SEC-05`, `TR-DET-02`, `TR-TEN-01`, PD-121
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Tenant `bank-b` sets `masking.tenants.bank-b.require-explicit-mask: true`. Its `kyc-routing` v1, approved before strict mode, has input `nationalId` with no `mask`. Tenant `bank-a`'s `card-fee` as in `RLE-062`. Caller `dan` holds `rules:read` only.
- **When:** An author in `bank-b` sends `?copyOf=1` for `kyc-routing`. In `bank-a`: `?copyOf=9`; `?copyOf=0`; `?copyOf=1` with a non-empty body; `dan` sends `?copyOf=1`; a `bank-b` caller sends `?copyOf=1` for `card-fee`. On a host whose `evaluation.step-budget` was lowered after v1 was approved, an author restores v1.
- **Then:**
  - The `bank-b` copy is `422 VALIDATION_FAILED` naming `nationalId` and `decision-log.require-explicit-mask` (`RLE-050`). The copy is checked as an import of the same document would be today: the step budget (`RLE-004`), the row limit and the mask rules (`RLE-049`, `RLE-050`) apply, so the lowered budget refuses the restore with `EVALUATION_BUDGET_EXCEEDED`.
  - `?copyOf=9` and the `bank-b` caller get `404 VERSION_NOT_FOUND` and `404 RULE_SET_NOT_FOUND`: the source is read in the caller's tenant only.
  - `?copyOf=0` and the request with a body get `400 INPUT_INVALID`; a `copyOf` that is not a whole number of 1 or more is the same.
  - `dan` gets `403 PERMISSION_DENIED`, checked before a body is read.
  - No failure writes a version, history row, event or idempotency record.
- **Example:** `bank-b ?copyOf=1 on kyc-routing -> 422, nationalId, decision-log.require-explicit-mask; ?copyOf=0 -> 400; dan -> 403; 0 rows written`.
- **Test ID:** `RLE_063_a_restore_passes_todays_import_checks_and_refuses_what_it_cannot_copy`

### RLE-048 The diff marks look-alike characters and reports header and column changes
- **Traces to:** `TR-UI-02`, `TR-STD-03`, ADR-0026 decision 6
- **Milestone:** M1
- **Kind:** other
- **Given:** Tenant `bank-a`; `kyc-route` v1 with input `country` (string) and rule `R1` (`country != 'VN' -> route: "MANUAL"`); v2 equal except that `R1` reads `country != 'ВN'` with a Cyrillic `В`; v3 equal to v1 except that `R1` reads `country != 'V\u200BN'` (an escaped zero-width space); v4 equal to v1 except that the hit policy is `unique`, the input `country` has `mask: true` and a new input `segment` (string) is declared. Caller `bob` holds `rules:read`.
- **When:** `bob` sends `GET .../rule-sets/kyc-route/versions/2/diff?base=1`, then `versions/3/diff?base=1` and `versions/4/diff?base=1`.
- **Then:** The v2 diff lists `R1` as `CHANGED` with field `/when/country` from `!= 'VN'` to `!= 'ВN'` and `nonAscii: true`, and different canonical hashes. The v3 diff lists the same field with `to` `!= 'V\u200BN'` and `nonAscii: true`. In both, a field whose values are printable ASCII has `nonAscii: false`. The v4 diff has no row change and `unchangedCount: 1`; `header` holds `{ path: /hitPolicy, from: first, to: unique }`; `inputs.added` is `[segment]`; `inputs.changed` holds `country` with field `/mask` from null to `true`.
- **Example:** `country != 'VN' -> country != 'ВN' (Cyrillic) -> CHANGED /when/country, nonAscii true; hit policy first -> unique -> header /hitPolicy`.
- **Test ID:** `RLE_048_diff_marks_non_ascii_literals_and_reports_header_and_column_changes`

### RLE-049 An undeclared input whose name looks like personal data is a warning the approver sees
- **Traces to:** `TR-SEC-05`, PD-099 (mitigation 1)
- **Milestone:** M1
- **Kind:** control
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
- **Kind:** control
- **Given:** `bank-b` sets `horus.bpm.rules.masking.tenants.bank-b.require-explicit-mask: true`; `bank-a` keeps the default `decision-log.require-explicit-mask: false`. Rule set `kyc-routing` as in `RLE-049`, and the same table with `nationalId` declared `mask: true`. In `bank-a`, a draft of `kyc-routing` imported while strict mode was off.
- **When:** `lan` imports `kyc-routing` in `bank-b`, then the declared table; replaces a declared draft in `bank-b` with the undeclared table; `bank-a` turns strict mode on and `lan` requests publication of its older draft; in `bank-a`, a version requested while strict mode was off is approved by `bob` after strict mode is turned on.
- **Then:** The undeclared import is `422 VALIDATION_FAILED` with one violation per undeclared input, its `column` `nationalId`, the detail naming `decision-log.require-explicit-mask`; no rule set, version, event or idempotency record is stored. The declared import answers `201`. The replacement is refused the same way and the draft is unchanged. The publication request of the older draft is refused naming `nationalId`, and the version stays `DRAFT`. `bob`'s approval is refused the same way: the version stays `PENDING_APPROVAL` with its lock version, and no event is written. `bank-a` without strict mode imports the undeclared table with a warning only. `/api/bpm/meta` shows `decision-log.require-explicit-mask` for the caller's tenant.
- **Example:** `strict bank-b imports nationalId without mask -> 422 VALIDATION_FAILED, column nationalId, nothing stored; every input declared -> 201`.
- **Test ID:** `RLE_050_strict_mode_refuses_an_undeclared_input_naming_it_and_stores_nothing`

### RLE-051 The version diff shows each input's masking status and marks changes
- **Traces to:** `TR-SEC-05`, `TR-UI-02`, PD-099 (mitigation 3)
- **Milestone:** M1
- **Kind:** control
- **Given:** `kyc-routing` v1 `PUBLISHED` with `nationalId` `mask: true`; v2 a draft with `nationalId` `mask: false`. In a tenant that masks undeclared inputs by default, v1 with `nationalId` `mask: true` and v2 with no `mask` on `nationalId` and without the input `segment`.
- **When:** `bob` reads `GET .../rule-sets/kyc-routing/versions/2/diff?base=1`.
- **Then:** `masking` lists every input of either version: `{ input: nationalId, from: MASKED, to: NOT_MASKED, changed: true }`, then `age` and `segment` with `changed: false`. In the tenant that masks by default, `nationalId` is `MASKED` to `MASKED_BY_DEFAULT` with `changed: true` and `personalDataName: "national id"`, and `segment` is `NOT_MASKED` to null with `changed: true`. `inputs.changed` still reports the `/mask` member as before.
- **Example:** `nationalId mask true -> false -> masking: MASKED to NOT_MASKED, changed true`.
- **Test ID:** `RLE_051_the_diff_marks_national_id_going_from_masked_to_not_masked`

### RLE-052 A change of the masking default is logged at start-up and the value in force is shown
- **Traces to:** `TR-SEC-05`, `TR-OBS-01`, PD-099 (mitigation 4)
- **Milestone:** M1
- **Kind:** control
- **Given:** The example host on one database: `bank-a` has rule sets and no masking setting of its own, `bank-b` sets `require-explicit-mask: true`; the host masking default is `false`. Then the host restarts with `horus.bpm.rules.masking.tenants.bank-a.mask-default: true` and a masking key.
- **When:** The host starts, `lan` (`bank-a`) and `binh` (`bank-b`) read `GET /api/bpm/meta`; the host restarts; `lan` reads `/api/bpm/meta` again; the host restarts a third time unchanged.
- **Then:** The first start records the host default and every known tenant in `rle_setting_seen`. `/api/bpm/meta` shows `decision-log.mask-default: false` and `decision-log.require-explicit-mask: false` in the `rules` settings for `lan`, and `require-explicit-mask: true` for `binh`. The restart logs exactly one line, `decision-log.mask-default for tenant bank-a changed from false to true since the last start-up`, at INFO (WARN when masking is turned off), and none for `bank-b`; `/api/bpm/meta` then shows `true` for `lan`. A tenant first seen since the last start-up is compared with the host default it followed then. The third start logs nothing. No line carries the key.
- **Example:** `bank-a mask-default false -> true across a restart -> one log line naming bank-a, false and true; /meta shows true`.
- **Test ID:** `RLE_052_a_changed_masking_default_is_one_line_with_the_tenant_and_the_old_and_new_value`

---

### RLE-053 Approving a version with an unmasked personal-data warning needs an acknowledgement on the record
- **Traces to:** `TR-SEC-04`, `TR-SEC-05`, `TR-OBS-04`, PD-099 (mitigation 1), PD-104
- **Milestone:** M1
- **Kind:** control
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
- **Kind:** control
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

### RLE-084 An approval or a rejection binds to the exact content that was reviewed
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-IDEM-01`
- **Milestone:** M1
- **Kind:** control
- **Given:**
  - Tenant `bank-a`. `lan` authored `card-fee` v1 with `R2` at 10,000 VND and requested publication; `bob` holds `rules:approve` and reviewed that content, whose `canonicalHash` is `H1`, at lock version `L1`.
  - `lan` then withdrew the request, replaced the draft with `R2` at 12,000 VND and requested publication again under the same number: the version is `PENDING_APPROVAL` with `canonicalHash` `H2` and lock version `L2`.
- **When:**
  1. `bob` approves with `If-Match: L2` (the refreshed lock version) and `expectedCanonicalHash: H1`; then rejects the same way.
  2. `bob` sends `expectedCanonicalHash` that is not 64 lower-case hexadecimal characters.
  3. `bob` approves with `expectedCanonicalHash: H2`, under the key step 1 used.
  4. A client that names no `expectedCanonicalHash` approves, or rejects, with the current lock version: first on a host with the default `approval.require-content-hash` (true), then on a host that turned it off on the `dev` profile (PD-139).
  5. A host starts with `approval.require-content-hash=false` on the `prod` profile, on `dev,prod`, and on `dev` alone.
- **Then:**
  - **Step 1** is `409 CONTENT_CHANGED` (not `VERSION_CONFLICT`, so a client that refreshes and retries on a conflict cannot re-bind the decision) for both, carrying `currentCanonicalHash: H2` and `currentLockVersion: L2` and saying the content changed since it was reviewed. The version stays `PENDING_APPROVAL` at `L2`; no history row, no event and no idempotency record is written, so nothing can be replayed. Through the Java API a `ContentChangedException`, with the same two values.
  - **Step 2** is `400 INPUT_INVALID` naming `expectedCanonicalHash`; nothing is written.
  - **Step 3** is `200` and the version is `PUBLISHED`: the key of a refused request is free.
  - **Step 4** with the default is `400 CONTENT_HASH_REQUIRED` naming `expectedCanonicalHash`, for approve and for reject, answered before anything is read; nothing is written. With the setting off it works as before and publishes on the lock version alone.
  - **Step 5** refuses start-up on `prod` and on `dev,prod`, naming `horus.bpm.rules.approval.require-content-hash=false` and the active profiles; `dev` alone (optionally with `test`) starts and logs a WARN. The setting is published in `/api/bpm/meta` as `approval.require-content-hash`.
  - The order of checks is: permission, `If-Match` present, status, four eyes (then a recorded author), lock version, reviewed content, strict mode, acknowledgement. A stale lock version is still `409 VERSION_CONFLICT` first, and now names the current content too; a missing hash is refused before all of these, with the body. The cockpit sends the hash of the content the reviewer opened, never one read again at approval time. The hash is part of the idempotency request hash only when it is named, so a request stored before this field existed still replays.
- **Example:** `approve v1 with If-Match 4 and hash of the 10000 VND content, content now 12000 VND at lock 4 -> 409 CONTENT_CHANGED, currentCanonicalHash = hash of 12000 VND`; `approve v1 with If-Match 4 and no hash -> 400 CONTENT_HASH_REQUIRED`.
- **Test ID:** `RLE_084_an_approval_with_the_hash_of_the_current_content_publishes_as_before`, `RLE_084_a_decision_without_a_hash_is_refused_by_default_and_writes_nothing`, `RLE_084_a_decision_without_a_hash_is_allowed_when_the_requirement_is_off`, `RLE_084_turning_the_requirement_off_is_refused_outside_the_dev_profile`, `RLE_084_a_hash_that_no_longer_matches_is_CONTENT_CHANGED_with_the_current_hash_and_nothing_is_approved` and `RLE_084_approve_and_reject_send_the_hash_of_the_content_the_reviewer_opened_without_reading_it_again` (cockpit), `RLE_084_a_version_withdrawn_replaced_and_requested_again_is_refused_with_the_old_hash_even_with_the_new_lock`, `RLE_084_a_rejection_of_other_content_than_reviewed_is_refused_and_writes_nothing`, `RLE_084_a_stale_lock_version_is_still_refused_first_and_names_the_current_state`, `RLE_084_a_hash_that_is_not_64_lower_case_hexadecimal_characters_is_400_input_invalid`, `RLE_084_the_same_key_with_another_hash_is_not_a_replay`, `RLE_084_an_approval_or_rejection_of_other_content_than_reviewed_is_409_and_writes_nothing` (over HTTP), `RLE_084_an_approval_that_names_no_hash_is_400_content_hash_required` (over HTTP)

### RLE-190 The pending approvals list shows every version waiting for a second person, with the hash to review against
- **Traces to:** `TR-SEC-04`, `TR-SEC-11`, `TR-TEN-01`, `TR-PERF-07`, PD-139, #31
- **Milestone:** M2
- **Kind:** control
- **Given:** At `bank-a` `lan` authors and requests `card-fee` v1 (10 January) and v2 (12 January) and `ana` requests `fx-margin` v1 (11 January); v1 of `card-fee` stays pending when v2 is requested. `bob` holds `rules:read` and `rules:approve`, `dan` holds `rules:read` only, an auditor of `bank-b` holds `rules:read`. The V30 index `idx_rle_version_pending` on `(tenant_id, requested_at, id)` over `PENDING_APPROVAL` versions exists.
- **When:** Each of them calls `GET /approvals`, and `bob` pages it one version at a time with `limit=1` and the `nextCursor` of each page.
- **Then:**
  - `bob` gets the three versions oldest request first: `card-fee` v1, `fx-margin` v1, `card-fee` v2. A pending version that is not the latest of its rule set is included. Each item has `ruleSet`, `version`, `canonicalHash` (the value to send back as `expectedCanonicalHash`, `RLE-084`), `lockVersion` (the `If-Match`), `requestedBy`, `requestedAt`, `allowedActions` and `deniedActions`. It never carries the definition, the source or an input value. The status is `200`, the permission is `rules:read`, and nothing is written.
  - `allowedActions` follows `VersionActions`: `bob` gets `approve` and `reject` and an empty `deniedActions`; `lan`, the requester, gets `withdraw` only, and `deniedActions` is `{approve: SELF_APPROVAL_FORBIDDEN, reject: SELF_APPROVAL_FORBIDDEN}`; the author of a version that another person requested gets `reject` and `approve: SELF_APPROVAL_FORBIDDEN`; `dan` gets none, with `PERMISSION_REQUIRED` for both; a version with no recorded author has `approve: AUTHOR_UNKNOWN` for everyone (`RLE-054`). A version the caller may decide has no entry in `deniedActions`.
  - The pages hold one item each; the first two carry a `nextCursor` signed for the tenant and this list, the last has none and `hasNext` false. Versions requested at one instant page by id without a gap or a repeat. A version that is approved, rejected or withdrawn leaves the list.
  - The `bank-b` auditor gets none of `bank-a`'s versions, and `bank-a`'s cursor is `400 QUERY_INVALID` for them, as are an unsigned payload, an edited signature, the cursor of the rule set list, `limit=0` and `limit=500`. A caller without `rules:read` is `403 PERMISSION_DENIED`.
  - The statement is the registered list query `pending-approvals` (and `pending-approvals-after`) of `RLE-085`: its plan reads `idx_rle_version_pending` in `(requested_at, id)` order with a `Limit` and no sort, and V30 builds that index CONCURRENTLY, repairs an interrupted build and may run twice.
- **Example:** `GET /approvals?limit=1 as bob -> card-fee v1, requestedBy lan, canonicalHash H1, allowedActions [approve, reject]`; `as lan -> allowedActions [withdraw], deniedActions {approve: SELF_APPROVAL_FORBIDDEN, reject: SELF_APPROVAL_FORBIDDEN}`.
- **Test ID:** `RLE_190_lists_every_pending_version_oldest_request_first_including_one_that_is_not_the_latest`, `RLE_190_the_proposer_gets_no_approve_and_no_reject_and_the_reason_as_a_code`, `RLE_190_a_reader_without_approve_sees_the_queue_with_permission_required`, `RLE_190_an_author_who_is_not_the_requester_may_reject_but_not_approve`, `RLE_190_a_pending_version_with_no_recorded_author_cannot_be_approved_by_anyone_and_says_so`, `RLE_190_pages_by_signed_cursor_and_a_decided_version_leaves_the_queue`, `RLE_190_another_tenant_sees_none_and_its_cursor_is_not_ours`, `RLE_190_a_forged_or_cross_list_cursor_a_bad_limit_and_a_missing_permission_are_refused`, `RLE_190_answers_the_page_with_the_hash_the_reviewer_sends_back_and_the_callers_actions`, `RLE_190_a_caller_without_read_is_refused_and_a_bad_limit_or_cursor_is_query_invalid`, `RLE_190_lists_the_callers_tenant_oldest_first_across_pages_with_its_hashes`, `RLE_190_requests_made_in_the_same_instant_page_by_id_without_gap_or_repeat`, `RLE_190_the_pending_approvals_pages_read_the_partial_index_in_order_without_a_sort`, `TR_OPS_02_V30_builds_a_valid_partial_index_and_a_rerun_after_an_interrupted_build_replaces_it`

### RLE-195 Inputs that declare no `mask` are listed as reminders and never block
- **Traces to:** `TR-SEC-05`, PD-146, PD-132, #438
- **Milestone:** M2
- **Kind:** control
- **Given:** `card-fee` with inputs `tier` (no `mask`), `channel` (`mask: false`) and `cardNumber` (`mask: true`); `horus.bpm.rules.classification.warn-unclassified` at its default `true`. `lan` authors, `bob` approves.
- **When:** `lan` imports it, validates an edit that also drops `channel`'s `mask`, and requests publication; `bob` reads the version and approves it; the engine starts outside `dev`; then the same with the setting `false`.
- **Then:**
  - The import, the publication request and `bob`'s read each list one warning `{code: UNCLASSIFIED_INPUT, input: tier, matchedName: null, requiresAcknowledgement: false}`; `:validate` stays `valid: true` and lists `tier` and `channel` in `warnings`, in input order. `mask: false` counts as classified. An input that already carries an `UNMASKED_PERSONAL_DATA` warning (`RLE-049`), which says it declares no `mask`, is not listed a second time on the version; the start-up summary counts it.
  - The approval succeeds without acknowledging anything; `UNCLASSIFIED_INPUT` may not be listed in `lifecycle.acknowledge-warnings`.
  - Outside `dev`, start-up logs one WARN that counts the unclassified inputs per published version (`bank-a/card-fee v1: 1`), names the setting and never an input name or value; drafts are not counted.
  - With the setting `false` nothing is listed or logged. Sensitivity is never inferred from a name or a value: an input named `nationalId` with `mask: false` is not listed.
- **Example:** `inputs [tier, channel mask:false, cardNumber mask:true] -> warnings [UNCLASSIFIED_INPUT tier]; approve -> PUBLISHED`.
- **Test ID:** `RLE_195_an_unclassified_input_is_listed_at_import_request_and_review_and_approval_still_works`, `RLE_195_validate_lists_unclassified_inputs_and_the_document_stays_valid`, `RLE_195_a_silenced_deployment_lists_nothing`, `RLE_195_classification_is_never_inferred_from_a_name`, `RLE_195_an_input_already_warned_as_personal_data_is_not_listed_twice`, `RLE_195_the_start_up_summary_counts_unclassified_inputs_per_approved_version_without_values`

### RLE-196 Readable names of roles, groups and users
- **Traces to:** `TR-UI-08`, PD-110, #442
- **Milestone:** M2
- **Kind:** other
- **Given:** The host's `DisplayNames` (default: `horus.bpm.display-names.roles|groups|users.<id>.<language>`) knows `lan` as "Tran Thi Lan" and no name for `ana`. `lan` requested `card-fee` v1 and v2, `ana` requested `fx-margin` v1.
- **When:** `bob` lists `GET /approvals`; a screen asks `GET /api/bpm/display-names?roles=...&groups=...&users=...` with `Accept-Language: vi`.
- **Then:** Each approval item carries `requestedByName`: "Tran Thi Lan" for `lan`'s versions and `ana` for `ana`'s, while `requestedBy` keeps the id for the four-eyes checks. `/display-names` returns the names it knows in the caller's language, falling back to `display-names.default-language` (`en`), and leaves out identifiers it does not know; a host bean of type `DisplayNames` replaces the default and receives the caller's tenant and language. More than 100 identifiers of one kind, or a malformed one, is `400 INPUT_INVALID`; an anonymous call is `401`. Names grant nothing.
- **Example:** `GET /approvals -> {requestedBy: lan, requestedByName: Tran Thi Lan}`; `display-names?roles=CARD_OFFICER (vi) -> {roles: {CARD_OFFICER: Cán bộ thẻ}}`.
- **Test ID:** `RLE_196_each_item_carries_the_requester_display_name_and_falls_back_to_the_id`, `RLE_196_a_host_directory_replaces_the_default_and_receives_the_callers_tenant_and_language`

## Rule Set Templates, Simulation Reports and Replay Readings (PD-112, PD-115, #83, #96, #109)

### RLE-197 The engine lists the templates a new rule set can start from, and a host adds its own
- **Traces to:** `TR-UI-02`, `TR-EXT-01`, `TR-EXT-04`, PD-112, #83
- **Milestone:** M2
- **Kind:** semantics
- **Given:** `lan` holds `rules:author`; `dan` holds `rules:read` only. Case A: a host with no template folder. Case B: the host's folder `horus.bpm.rules.templates.location` holds `deposit-rate-by-term.json` (English and Vietnamese label and description, one `integer` input `termMonths`, one `decimal` output `annualRate`). Case C: the host sets `horus.bpm.rules.templates.hidden: [blank]` and the folder holds `b-second.json` and `a-first.json`.
- **When:** `lan` sends `GET /api/bpm/rules/v1/rule-set-templates`; `dan` sends it; a caller with no identity sends it; the document of `fee-by-tier` is composed as the cockpit does (a name added, the inputs, outputs and rows copied) and imported.
- **Then:** Case A: `200` with the four built-in templates in the order `fee-by-tier`, `eligibility-with-reason`, `limit-by-segment`, `blank`, each with `id`, `labels` and `descriptions` holding `en` and `vi`, `source: BUILT_IN`, `hitPolicy`, `inputs` (name, type, `allowed`), `outputs` and the first `rows` (`when` and `then` cells). `fee-by-tier` has the input `tier` with `allowed` `GOLD`, `PLATINUM`, `CLASSIC`, the output `fee` of type `money` and a first row `R1` with `tier: "== 'GOLD'"` and `fee: "0 VND"`; `blank` has one input, one output and one default row with an empty `when`; `limit-by-segment` is `unique`. Case B: five templates, `deposit-rate-by-term` last, `source: HOST`, with its own texts in both languages. Case C: `blank` is not listed, the other three are, and the host's follow in file name order (`a-first`, `b-second`); a file that does not end in `.json` and a folder that does not exist add nothing. The composed `fee-by-tier` document is accepted by `POST /rule-sets` as draft version 1. `dan` is `403 PERMISSION_DENIED` and nothing is read; no identity is `401 UNAUTHENTICATED`. Templates belong to the host and the answer is the same for every tenant; listing writes nothing.
- **Example:** `lan -> [fee-by-tier, eligibility-with-reason, limit-by-segment, blank, deposit-rate-by-term]; dan -> 403`.
- **Test IDs:** `RLE_197_the_four_built_in_templates_come_first_with_english_and_vietnamese_texts`, `RLE_197_a_bank_template_is_listed_after_the_built_in_four`, `RLE_197_host_templates_follow_the_order_the_starter_read_them_in`, `RLE_197_the_folder_is_read_by_file_name_and_other_files_are_ignored`, `RLE_197_a_folder_that_does_not_exist_adds_nothing`, `RLE_197_a_template_becomes_a_rule_set_the_engine_accepts`, `RLE_197_listing_needs_rules_author`, `RLE_197_the_templates_come_back_with_both_languages_inputs_outputs_and_first_rows`, `RLE_197_a_reader_without_rules_author_gets_403_and_nothing_is_read`, `RLE_197_the_template_service_offers_the_built_in_four_and_the_host_folder`

### RLE-198 A template that is not valid stops the start and names its file
- **Traces to:** `TR-EXT-04`, `TR-SEC-03`, PD-112
- **Milestone:** M2
- **Kind:** semantics
- **Given:** A template folder with one bad file at a time: `{ not json`; a template with no `vi` label; a document with an input of type `percentage`; a document that carries a `ruleSet` name; a second file that reuses the id `blank` while the built-in `blank` is shown; a file of 65,537 bytes; and a `templates.location` that is a file. Separately a `templates.hidden` of `fee-by-teir`.
- **When:** The host starts.
- **Then:** Start-up fails with a message that names the file (`Template file <name> ...`) and the problem: not well-formed JSON, `labels.vi` missing, "not a valid rule set" for the document the engine would refuse at import, a template has no name of its own, "the id 'blank' is already used by another template; hide the built-in one or rename this one", "larger than 65536 bytes", "is not a folder". A hidden id that is not built in fails naming `horus.bpm.rules.templates.hidden` and the id, so a typo never leaves a template on show. When the host hides the built-in `blank`, a file with the id `blank` takes its place. Every template is checked once, as an imported document is, so a listed template always composes into a rule set the engine accepts; nothing is read per request.
- **Example:** `broken-template.json without labels -> start-up fails: Template file broken-template.json needs labels with an English (en) and a Vietnamese (vi) text`.
- **Test IDs:** `RLE_198_a_file_that_is_not_json_stops_the_start_and_names_itself`, `RLE_198_a_template_without_a_vietnamese_label_is_refused`, `RLE_198_a_document_that_the_engine_would_refuse_is_refused_at_start_up`, `RLE_198_a_document_that_names_the_rule_set_is_refused`, `RLE_198_an_id_used_twice_is_refused_unless_the_built_in_one_is_hidden`, `RLE_198_a_hidden_id_that_is_not_built_in_is_refused`, `RLE_198_a_file_over_the_size_limit_is_refused`, `RLE_198_a_location_that_is_a_file_is_refused`, `RLE_198_the_folder_reader_refuses_a_file_over_the_size_limit`, `RLE_198_a_template_file_that_is_not_valid_stops_the_start_and_names_the_file`

### RLE-199 The engine runs a simulation and stores a report for the approver
- **Traces to:** `TR-OBS-01`, `TR-IDEM-01`, `TR-TEN-01`, `TR-SEC-02`, PD-115, #109
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `card-fee` v1 in force with 100 logged decisions of November 2026, 18 of them for `STANDARD` customers, and draft v2 that lowers the fee from 50,000 VND to 30,000 VND. `lan` holds `rules:author`, `rules:simulate` and `rules:read`; `minh` holds `rules:approve` and `rules:read`; `sim` holds `rules:simulate` only; `audit` holds `rules:read` and `rules:log:read`. `loan-tier` v1 charges `STANDARD` customers 10,000 VND and draft v2 5,000 VND.
- **When:** `lan` sends `POST .../versions/2/simulation-reports` with `{"source":"REPLAY","from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z","limit":100}` and an `Idempotency-Key`, twice. `lan` sends a `SAMPLES` report on `loan-tier` v2 with a `GOLD` and a `STANDARD` sample, one carrying a `label`. `lan` sends a `SAMPLES` report on `card-fee` v2, whose rows call `today()`. `sim` sends the replay. `minh` sends it. A period of 40 days. The same key with another `limit`.
- **Then:**
  - `201` with a report: counts `total 100, changed 18, unchanged 82, failed 0, notReplayable 0`, `status: CURRENT`, `attached: false`, `createdBy: lan`, the `canonicalHash` of v2, the scope (`REPLAY`, period, `limit 100`, `truncated false`), the `languages` and 18 `rows`, each `CHANGED`, with the matched rule and the outcome `before` (the logged decision, read in version 1: "fee 50,000 VND") and `after` (v2: "fee 30,000 VND") as readings in every language. The repeat with the same key returns the same report and stores one row.
  - Each item is in exactly one count: not replayable (a masked input had a value), else failed (the version failed on it), else changed, else unchanged; the four add up to `total`. `rows` holds the changed and failed items in order, at most 50, and `rowsTruncated` says more existed. A `GOLD` sample that does not change and a `STANDARD` one that does give `total 2, changed 1, unchanged 1` and the row `S2` with 10,000 VND before and 5,000 VND after. The `card-fee` samples fail on `today()`: `failed 1`, `changed 0`, the row `FAILED` with `after.kind: FAILED`.
  - The report stores no input value, no sample `label` (a `label` is ignored) and no masked fingerprint: the stored rows contain neither `STANDARD`, nor `GOLD`, nor `label`.
  - The `decisionId` of a replayed row is given only to a holder of `rules:log:read`: `lan` and `minh` see none in the answer, `audit` sees all 18 on a read; the stored report keeps them. `sim`, who holds `rules:simulate` only, can run the replay and sees no decision id.
  - `minh`, without `rules:simulate`, is `403 PERMISSION_DENIED` and nothing is stored; the permission is checked before the body is read. A period of 40 days is `400 QUERY_INVALID` as in `:simulate-history`. The same key with another request is `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`.
- **Example:** `REPLAY November on card-fee v2 -> {counts: {total: 100, changed: 18, unchanged: 82, failed: 0, notReplayable: 0}, rows: 18}`
- **Test IDs:** `RLE_199_a_replay_report_counts_changed_unchanged_and_keeps_readings_of_the_changed_rows`, `RLE_199_decision_ids_are_given_only_to_a_holder_of_log_read`, `RLE_199_a_sample_report_names_samples_and_stores_no_input_or_label`, `RLE_199_a_sample_the_version_fails_on_is_counted_as_failed_and_listed`, `RLE_199_a_repeat_with_the_same_key_returns_the_first_report_and_runs_nothing`, `RLE_199_needs_rules_simulate_and_stores_nothing_without_it`, `RLE_199_a_simulate_only_caller_runs_a_replay_without_seeing_decision_ids`, `RLE_199_the_period_is_bounded_like_the_history_simulation`, `RLE_199_a_replay_report_is_created_with_201_and_carries_counts_and_changed_rows`, `RLE_199_a_sample_report_reads_the_inputs_and_ignores_a_label`, `RLE_199_without_rules_simulate_nothing_is_read_and_a_bad_source_is_400`, `RLE_199_a_replay_report_is_stored_once_bound_to_the_hash_and_read_back`

### RLE-200 A report is immutable, bound to the version's content, and never holds a masked value
- **Traces to:** `TR-SEC-04`, `TR-SEC-02`, `TR-TEN-01`, `TR-OBS-04`, PD-115
- **Milestone:** M3
- **Kind:** control
- **Given:** A report on `card-fee` v2 as in `RLE-199`; `id-check-fee` with the input `nationalId` (`mask: true`), v1 in force with one decision whose `nationalId` was `079201001234` and one with a null one, and draft v2 that lowers the fee; `bank-b` has its own `card-fee` v1.
- **When:** The draft is replaced with a fee of 20,000 VND and the report is read; two reports are listed; SQL runs `UPDATE`, `DELETE` and `TRUNCATE` on the report table; `lan` runs a report on `id-check-fee` v2 for November; `bank-b` asks for `bank-a`'s report by id under its own `card-fee`, under `bank-a`'s version number, and lists it; `bank-c`, with no `card-fee`, lists it.
- **Then:**
  - After the draft changed the report reads `status: OUT_OF_DATE` and keeps the `canonicalHash` it was run on; a list shows the same status; the report itself is unchanged. A list returns the reports newest first, at most 50, each with empty `rows`; one read returns the rows.
  - The database refuses an `UPDATE`, a `DELETE` and a `TRUNCATE` of a stored report (SQLSTATE `55000`, or the foreign key of the attachment table for `TRUNCATE`), and the engine refuses to store an id twice; the counts read as before.
  - The `id-check-fee` report counts `total 2, changed 1, notReplayable 1`; the decision with the masked value is not read at all and its input name is not listed; neither `079201001234` nor `nationalId` is in the stored rows or in the answer.
  - `bank-b` gets `404 SIMULATION_REPORT_NOT_FOUND` under its own rule set, `404 VERSION_NOT_FOUND` under a version it does not have and an empty list; `bank-c` gets `404 RULE_SET_NOT_FOUND`: the same answers whether or not the report exists. A stored row holds readings and ids only: no `STANDARD`, no balance.
  - `GET` needs `rules:read`; `403` otherwise, and an unknown id is `404 SIMULATION_REPORT_NOT_FOUND`. Creating a report is recorded by the report row itself (who, when, over what); no history row or event is written for a report that is never attached.
- **Example:** `v2 replaced after the report -> status OUT_OF_DATE, canonicalHash unchanged; UPDATE rle_simulation_report -> ERROR: rle_simulation_report is append-only`
- **Test IDs:** `RLE_200_a_report_is_out_of_date_once_the_draft_changes`, `RLE_200_a_report_is_never_changed_and_a_list_has_no_rows`, `RLE_200_the_report_of_another_tenant_is_not_found`, `RLE_200_masked_values_are_never_stored_and_masked_decisions_are_counted_as_not_replayable`, `RLE_200_a_report_is_read_by_id_and_listed_without_rows`, `RLE_200_reading_needs_rules_read_and_an_unknown_report_is_404`, `RLE_200_the_database_refuses_to_change_or_delete_a_report`, `RLE_200_a_report_stores_no_decision_input`, `RLE_200_another_tenant_never_finds_the_report`

### RLE-201 A publication request carries a report, and a host can require one
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-EXT-04`, PD-115, #109
- **Milestone:** M3
- **Kind:** control
- **Given:** Reports on `card-fee` v2 as in `RLE-199`. Case A: the default settings. Case B: the host sets `horus.bpm.rules.lifecycle.require-simulation-report: [card-fee]`; `loan-fee` is not listed. Case C: the draft is replaced after the report was run. Case D: draft v3 of `card-fee` and a report of v2.
- **When:** `lan` sends `POST .../versions/2:request-publish` with `{"simulationReportId": "<report>"}`; `lan` does so with the replaced draft; with v2's report on v3, and with an id nobody issued; in case B without a report on `card-fee` v2 and on `loan-fee` v2; with a body member that is not text. The settings are given `Card Fee` and `card-fee` twice. `GET /api/bpm/meta`.
- **Then:**
  - Case A: `200`, v2 is `PENDING_APPROVAL`, the report reads `attached: true`, one attachment row exists and the `rules.version.publish-requested.v1` event carries `simulationReportId`. A request with no body, or a body without the member, is unchanged and the event has no such member.
  - Case C: `409 SIMULATION_REPORT_OUT_OF_DATE`, v2 stays `DRAFT`, nothing is attached. Case D: `404 SIMULATION_REPORT_NOT_FOUND` for the report of another version and for an unknown id, and nothing changes. A `simulationReportId` that is not a non-blank string is `400 INPUT_INVALID`.
  - Case B: `409 SIMULATION_REPORT_REQUIRED` for `card-fee` v2 without a report, which stays `DRAFT` with no attachment; with a report it goes through; `loan-fee`, not listed, needs none. `/api/bpm/meta` lists `lifecycle.require-simulation-report: "card-fee"` (comma-separated, empty by default) and the features `rules.history-readings`, `rules.simulation-reports` and `rules.templates`. A name that is not a kebab-case rule set name, or a name twice, stops the start naming `horus.bpm.rules.lifecycle.require-simulation-report`.
  - The report id joins the idempotency hash of the request only when named, so a key used before reports existed still replays.
- **Example:** `request-publish {simulationReportId: R1} -> 200 PENDING_APPROVAL; listed card-fee without a report -> 409 SIMULATION_REPORT_REQUIRED`
- **Test IDs:** `RLE_201_request_publish_attaches_a_current_report_and_the_event_names_it`, `RLE_201_a_report_of_changed_content_cannot_be_attached`, `RLE_201_a_report_of_another_version_or_an_unknown_id_is_not_found`, `RLE_201_a_rule_set_the_host_lists_needs_a_report_and_others_need_none`, `RLE_201_the_settings_reject_a_name_that_is_not_a_rule_set_name`, `RLE_201_request_publish_sends_the_report_id_to_the_engine`, `RLE_201_request_publish_without_a_body_still_works_and_a_report_member_must_be_text`, `RLE_201_the_three_report_problems_have_their_codes_and_statuses`, `RLE_201_the_rule_sets_that_need_a_simulation_report_are_published_and_checked_at_start_up`, `RLE_201_the_features_the_build_announces_match_the_pattern_the_cockpit_accepts`, `RLE_201_the_publication_request_carries_the_report_and_the_attachment_is_appended`, `RLE_201_a_listed_rule_set_cannot_be_requested_without_a_report`

### RLE-202 A replay is read in plain language, and a decision that cannot be replayed fails closed
- **Traces to:** `TR-UI-02`, `TR-DET-01`, `TR-SEC-02`, `TR-TEN-01`, PD-027, #96
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `card-fee` v1 with 100 logged November decisions and draft v2 as in `RLE-199`; `id-check-fee` as in `RLE-200` with one masked decision; `audit` holds `rules:read`, `rules:simulate` and `rules:log:read`.
- **When:** `audit` sends `POST .../versions/2:simulate-history-readings` with `{"from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z"}`; the same on `id-check-fee`; with a `limit` of 501; as a caller missing each of the three permissions in turn.
- **Then:**
  - `card-fee`: `200` with 100 `results` newest first, `replayedCount 100`, `changedCount 18`, `notReplayableCount 0`; each result names its `decisionId`, `loggedVersion` and `evalClock`, and reads `logged` in the version that made the decision ("fee 50,000 VND") and `simulated` in v2 ("fee 30,000 VND"), each as a `full` sentence and a `short` form in every language. No logged input is in the answer. Without a `limit` the run is 500 decisions, the most one request reads.
  - `id-check-fee`: the decision with the masked value has `code: REPLAY_NOT_POSSIBLE`, `maskedInputs: ["nationalId"]`, `changed: false`, `simulated: null` and `logged: null`: no sentence is written for what was not evaluated, and `079201001234` is nowhere in the answer. The same holds for a decision whose logged version cannot be evaluated again.
  - Each of `rules:simulate`, `rules:log:read` and `rules:read` is checked before the body is read; a caller missing one is `403 PERMISSION_DENIED`. A `limit` of 501 is `422 READINGS_TOO_LARGE`, answered before anything is read. Nothing is logged: no decision row, idempotency record or event, and no `Idempotency-Key` is taken.
- **Example:** `November replay of card-fee v2 -> 18 of 100 changed; newest: logged "R2: fee 50,000 VND", simulated "R2: fee 30,000 VND"; masked decision -> REPLAY_NOT_POSSIBLE [nationalId]`
- **Test IDs:** `RLE_202_history_readings_read_each_decision_in_the_version_that_made_it`, `RLE_202_a_masked_decision_is_listed_as_replay_not_possible_and_never_read`, `RLE_202_reading_needs_rules_read`, `RLE_202_history_readings_read_both_sides_and_name_a_masked_input_without_its_value`, `RLE_202_each_of_the_three_permissions_is_needed_and_a_limit_over_500_is_422`

### RLE-203 A rule set list and detail show a human name, from the document or from the key
- **Traces to:** `TR-UI-02`, #83, #96, #109
- **Milestone:** M3
- **Kind:** other
- **Given:** `card-fee` whose latest version's document has `"displayName": "Card fee"`; `id-check-fee` whose document names none.
- **When:** A caller with `rules:read` lists `GET /rule-sets` and reads `GET /rule-sets/id-check-fee`.
- **Then:** Each item carries `displayName`, never null: "Card fee" for `card-fee`, and "Id check fee" (the key with hyphens as spaces and a capital first letter) for `id-check-fee`. `ruleSet` keeps the key. A `displayName` that is blank or longer than 100 characters is refused at import with `422` naming `displayName`.
- **Example:** `GET /rule-sets -> [{ruleSet: card-fee, displayName: Card fee}, {ruleSet: id-check-fee, displayName: Id check fee}]`.
- **Test ID:** `RLE_203_a_document_may_name_the_rule_set_for_people_and_the_name_is_kept_in_the_canonical_form`, `RLE_203_a_blank_or_over_long_display_name_is_refused`, `RLE_203_without_a_display_name_the_key_is_made_readable`

## Platform Requirements Made Testable (RE-F-10, RE-F-13, X-F-05)

### RLE-034 The compiled cache holds at most `cache.max-entries` versions and heap stays flat
- **Traces to:** `TR-PERF-06`
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The example host with `cache.max-entries = 50`; tenant `bank-a` has 100 published rule sets `fee-001` to `fee-100`, each with one 200-row `first` table; the eviction counter `horus_rle_cache_evictions_total` (Micrometer `horus.rle.cache.evictions`, tag `cause`) reads 0.
- **When:** The host evaluates the 100 rule sets in rotation for 10 rounds (1,000 evaluations) after a warm-up round, measuring retained heap after a full garbage collection at the end of round 2 and of round 10.
- **Then:** The compiled store never holds more than 50 entries; the eviction counter reads at least 950 after the 10 rounds; every evaluation returns the same result as with an unbounded cache; retained heap at the end of round 10 is within 10 percent or 8 MB of the end of round 2, whichever is larger, each the median of three measurements after a full garbage collection; the gauge `horus_rle_cache_size` never exceeds 50.
- **Example:** `max-entries 50, 100 rule sets x 10 rounds -> size <= 50, evictions >= 950, heap round 10 within max(10%, 8 MB) of round 2`.
- **Test ID:** `RLE_034_compiled_cache_is_bounded_and_heap_stays_flat`

### RLE-035 The host refuses to start without a valid masking key, and the key never leaks
- **Traces to:** `TR-SEC-10`
- **Milestone:** M1
- **Kind:** control
- **Given:** Rule set `minor-review` with input `nationalId` (`mask: true`) loaded at start-up through `definitions`. Case A: no `horus.bpm.rules.masking.hmac-key`. Case B: a Base64 key that decodes to 31 bytes. Case C: a Base64 key `k32` that decodes to 32 bytes, with log capture on.
- **When:** The host starts in each case; in case C it evaluates `minor-review` with `nationalId = "079090001234"`, reads the decision through `GET /decisions/{id}` and its explanation, and computes HMAC-SHA256 of `079090001234` under `k32` in the test.
- **Then:** Cases A and B fail start-up with a message that names `horus.bpm.rules.masking.hmac-key` and the rule set `minor-review`, and no decision is accepted. The check covers every version that is not retired, in every tenant, and an input masked by the tenant's `decision-log.mask-default` as well as by `mask: true`; a tenant with its own key setting (`masking.tenants.<tenant>.hmac-key`) is checked against that key only. Case C starts; no log line, response body, problem body, event or metric label contains `k32` or `079090001234`; the stored `masked_inputs.nationalId` equals `hmac:`, the key id (`k1` by default), `:` and the hex of the HMAC-SHA256, under the tenant's HKDF subkey of `k32` (DESIGN section 6), of `079090001234`, which the test computes on its own, so an exact-match lookup by the masked value finds the decision (`RLE-012`). Key ids (#281, `TR-SEC-10`): two keys with the same id for one tenant (or among the host keys), a tenant key id equal to a host key id, or a previous key equal to a configured key fail start-up naming the setting and never the key; a key id already recorded in `rle_masking_key_check` with a different check value fails start-up naming the key id and both check values, never the key; a key id recorded for the host and then configured for a tenant (for example `bank-a` taking `k1` after the host retired it), or the reverse, fails start-up and records nothing; a tenant configured as `*` fails start-up naming the setting; and a host `MaskingPolicy` that hands out a key for a tenant with live rule sets without declaring it in `configuredKeys()` fails start-up naming the policy class, the tenant and the key id. A key that start-up never saw (a host policy that declares nothing, a rule set imported after start-up) is checked the first time `hmacKey(tenant)` returns it to write a decision with a masked input (#340): its check value is recorded or compared by scope and key id in a short transaction of its own, a key id first seen with other bytes (or recorded for the host with another check value) is refused as `422 EVALUATION_ERROR` with `error_code = MASKING_KEY_MISMATCH`; the `detail` is the code and the rule set only (no key id, check value, tenant or key), because a check value is a key-derived confirmation value that would let a caller test guesses offline; the server log names the tenant, rule set and key id with the check values withheld; no plain value is stored; the result is remembered, so 1,000 later decisions read the check value from the database once.
- **Example:** `no key -> start-up fails naming the key setting and minor-review; 31-byte key -> fails; 32-byte key -> starts, 0 occurrences of key or plain value in logs and responses; k1 back with other bytes -> fails naming host masking key id k1`.
- **Test ID:** `RLE_035_masking_key_required_at_startup_and_never_leaked`; first use (#340, `TR-SEC-10`): `TR_SEC_10_the_refusal_an_api_caller_sees_is_a_code_and_the_rule_set_and_no_check_value`, `TR_SEC_10_the_first_decision_with_a_key_id_first_seen_with_other_bytes_is_refused_and_logs_no_plain_value`, `TR_SEC_10_a_key_returned_again_is_checked_against_the_database_once_for_a_thousand_decisions`, `TR_SEC_10_a_declared_host_key_is_recorded_under_the_host_scope_at_first_use_for_a_tenant_without_rule_sets`, `TR_SEC_10_a_key_first_used_after_start_up_is_checked_against_the_id_recorded_for_the_host`

### RLE-036 Every Rule Engine REST error is a Problem Details body with a stable code
- **Traces to:** `TR-SEC-08`
- **Milestone:** M1 (rules; process engine errors are M2)
- **Kind:** semantics
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
- **Kind:** control
- **Given:** Host A embeds the cockpit in its own web application with a server-side login session; the session principal carries the attribute `tenant_id = bank-a`. Host B uses bearer tokens with claim `tenant_id: bank-a`. Both use the default `TenantResolver` with `tenancy.claim = tenant_id` and switching disabled. Rule set `card-fee` exists in `bank-a` and `bank-b`. Host C registers its own `TenantResolver` bean that returns `bank-c` for every caller. Case D: a session principal without the attribute.
- **When:** On hosts A and B the caller sends `GET /api/bpm/rules/v1/rule-sets/card-fee`, then `POST /api/bpm/rules/v1/decisions/card-fee:evaluate` with body `{ "tenantId": "bank-b", "inputs": { "tier": "GOLD", "txCount": 5 } }`, then the read again with header `X-Forwarded-Tenant: bank-b`. On host C the same read. In case D the same read.
- **Then:** Hosts A and B return `bank-a`'s rule set and evaluate `bank-a`'s version; the body `tenantId` and the extra header are ignored and the decision row has `tenant_id = bank-a`. Host C uses its own resolver: the engine's default is not called and the read is served for `bank-c`. Case D returns `401 UNAUTHENTICATED` and reads nothing. The starter registers no resolver that reads a request header.
- **Example:** `session tenant_id=bank-a, body tenantId=bank-b -> served for bank-a; principal without tenant -> 401`.
- **Test ID:** `RLE_037_tenant_from_principal_session_or_token_never_from_browser`

### RLE-038 In `four-eyes` mode a manual retirement needs a second approver, and the version serves until it is confirmed
- **Traces to:** `TR-SEC-04`, `TR-OBS-04`, `TR-EXT-05`
- **Milestone:** M3
- **Kind:** control
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
- **Kind:** control
- **Given:** Tenant `bank-a` has `card-fee` (v1 `PUBLISHED` effective since `2026-12-01T00:00:00Z`, v2 `PENDING_APPROVAL` requested at `2026-12-10T03:00:00Z`), `fx-margin` (v1 `DRAFT`) and `kyc-risk` (v1 `DRAFT`); tenant `bank-b` has its own `aml-score`. Caller `lan` holds `rules:read` in `bank-a`; the host clock reads `2026-12-10T03:00:00Z`.
- **When:** `lan` sends `GET /api/bpm/rules/v1/rule-sets?limit=2`, then the same request with the returned `cursor`. Failure cases: `limit=0`, `limit=201`, a `cursor` that was not issued by the endpoint, a cursor whose decoded key is not a rule set name (for example a NUL byte), a cursor issued to `lan` replayed by a `bank-b` caller, and a caller without `rules:read`.
- **Then:** The first page lists `card-fee` (`latestVersion: 2`, `latestStatus: PENDING_APPROVAL`, `publishedVersion: 1`, `lastChangedAt: 2026-12-10T03:00:00Z`) and `fx-margin` (`latestVersion: 1`, `latestStatus: DRAFT`, `publishedVersion: null`) and a `nextCursor`; the second page lists `kyc-risk` alone and `nextCursor: null`. `aml-score` never appears. `limit=0`, `limit=201`, the forged cursor, the NUL key and the replayed cursor return `400 QUERY_INVALID`, never `500`: a cursor is signed (HMAC-SHA256 over tenant, list and position, with a key stored once per database), so only a cursor issued to the same tenant for the same list is accepted; the caller without the permission gets `403 PERMISSION_DENIED`. `GET /rule-sets/card-fee` returns the same summary as the list item, and a `bank-b` caller reading it gets `404 RULE_SET_NOT_FOUND`. A name that cannot be a rule set name (`card\u0000fee`, upper case) is `404 RULE_SET_NOT_FOUND` on every endpoint that takes one. The list writes nothing.
- **Example:** `limit=2 -> [card-fee v2 PENDING_APPROVAL published 1, fx-margin v1 DRAFT] + cursor; next -> [kyc-risk]; bank-b's aml-score never listed`.
- **Test ID:** `RLE_039_rule_sets_are_listed_per_tenant_by_name_with_a_cursor`

### RLE-040 A caller lists the versions of one rule set, newest first, with who created, requested and approved each
- **Traces to:** `TR-TEN-01`, `TR-SEC-04`, `TR-UI-02`
- **Milestone:** M1
- **Kind:** control
- **Given:** Tenant `bank-a`; `card-fee` v1 created by `lan`, requested by `lan` and approved by `minh`; v2 created and requested by `lan`; v3 `DRAFT` created by `lan`. Caller `minh` holds `rules:read` and `rules:approve`.
- **When:** `minh` sends `GET /api/bpm/rules/v1/rule-sets/card-fee/versions?limit=2`, then follows `nextCursor`. Failure cases: the same request from a `bank-b` caller; `GET /rule-sets/no-such-set/versions`; `limit=500`.
- **Then:** The first page lists v3 (`DRAFT`, `createdBy: lan`, `requestedBy: null`) then v2 (`PENDING_APPROVAL`, `requestedBy: lan`, `approvedBy: null`); the second page lists v1 (`PUBLISHED`, `approvedBy: minh`) and `nextCursor: null`. Items carry no definition. The `bank-b` caller and the unknown name get `404 RULE_SET_NOT_FOUND`; `limit=500` returns `400 QUERY_INVALID`. The list writes nothing.
- **Example:** `card-fee versions, limit 2 -> [v3 DRAFT lan, v2 PENDING_APPROVAL requested by lan] + cursor; next -> [v1 PUBLISHED approved by minh]`.
- **Test ID:** `RLE_040_versions_are_listed_newest_first_with_their_actors`

### RLE-041 A refused document lists every cell problem with its rule id and column
- **Traces to:** `TR-DET-02`, `TR-UI-02`, `TR-SEC-08`
- **Milestone:** M1
- **Kind:** other
- **Given:** Tenant `bank-a`; caller `lan` holds `rules:author`. File `card-fee-bad.yaml` with inputs `tier` (string) and `balance` (money), output `fee` (money), and rows `R2` (`balance: ">= 50000000"`, a number compared with money) and `R4` (`then: { fee: "+ 1" }`, which does not parse). Second file: a document with an unknown root property `maks`.
- **When:** `lan` posts each file to `POST /api/bpm/rules/v1/rule-sets`.
- **Then:** The first returns `422 VALIDATION_FAILED` whose `violations` are, in document order (rule by rule; within a rule the conditions, then the outputs, each in the order written, nested `any` and `all` groups after the plain conditions), `{ ruleId: R2, column: balance, message: ... }` and `{ ruleId: R4, column: fee, message: ... }`; each `message` carries no rule id or column prefix. The second returns `422 VALIDATION_FAILED` with one violation whose `ruleId` and `column` are null and whose `message` names `maks`. Neither stores a rule set, a version or a source document. Both carry `violationsTruncated: false`; a document with 150 bad output cells lists the first 100 in document order with `violationsTruncated: true`.
- **Example:** `R2 balance >= 50000000 and R4 fee "+ 1" -> 422, violations [R2/balance, R4/fee]`.
- **Test ID:** `RLE_041_validation_failure_lists_each_cell_problem_with_rule_and_column`

### RLE-042 A money output written without a currency is refused at import, not at evaluation
- **Traces to:** `TR-DET-02`, `TR-DET-03`
- **Milestone:** M1
- **Kind:** control
- **Given:** Tenant `bank-a`; `card-fee.yaml` whose output `fee` is `money`, with row `R2` (`txCount >= 3 -> fee: "30000"`) and a second file where `R2` writes `fee: 30000` (a YAML number). Third file: output `waived` of type `boolean` with `R1` writing `waived: "'yes'"`. Fourth file: `R2` writes `fee: "30000 VND"`.
- **When:** `lan` imports each file.
- **Then:** The first three return `422 VALIDATION_FAILED` with a violation `{ ruleId: R2, column: fee }` (`{ ruleId: R1, column: waived }` for the third) whose message says the output is `money` and must carry a currency (is `boolean` for the third); nothing is stored, so no decision can ever fail with `EVALUATION_ERROR` for this reason. The fourth imports as `DRAFT`, and once approved, evaluating it with `tier = "SILVER"`, `txCount = 5` returns `fee: { "currency": "VND", "amount": 30000 }` from `R2`.
- **Example:** `fee: "30000" on a money output -> 422 at R2/fee at import; fee: "30000 VND" -> 201 DRAFT`.
- **Test ID:** `RLE_042_money_output_without_currency_is_refused_at_import`

### RLE-043 A version tells the caller which lifecycle actions they may take, with four eyes applied
- **Traces to:** `TR-SEC-04`, `TR-UI-02`
- **Milestone:** M1
- **Kind:** control
- **Given:** Tenant `bank-a`; `card-fee` v2 `PENDING_APPROVAL`, created and requested by `lan`; v3 `DRAFT` created by `lan`; v1 `PUBLISHED`. `lan` holds `rules:read`, `rules:author` and `rules:approve`; `minh` holds `rules:read` and `rules:approve`; `dan` holds `rules:read` only.
- **When:** Each of them reads v1, v2 and v3 with `GET .../versions/{v}` and lists the versions.
- **Then:** On v2 `lan` gets `allowedActions: ["withdraw"]` (`approve` and `reject` are absent because `lan` requested it; `withdraw` is the requester's action, `RLE-046`), `minh` gets `["approve", "reject"]`, `dan` gets `[]`. On v3 `lan` gets `["request-publish"]` and `minh` gets `[]`. On v1 `lan` and `minh` get `["retire"]` (they hold `rules:approve`, #28) and `dan` gets `[]`; a retired version gives everyone `[]`. The list items carry the same values. `lan`'s `approve` of v2 is still refused with `403 SELF_APPROVAL_FORBIDDEN`, because `allowedActions` is a hint and the command checks again. An idempotent replay of a command returns the actions computed for the caller at replay time, not the stored ones.
- **Example:** `v2 requested by lan -> lan: [withdraw], minh: [approve, reject], dan: []`.
- **Test ID:** `RLE_043_allowed_actions_follow_permissions_and_four_eyes`

### RLE-044 Command and evaluation bodies are read up to their limit; a larger body is `413 PAYLOAD_TOO_LARGE`
- **Traces to:** `TR-SEC-07`, `TR-SEC-08`
- **Milestone:** M1
- **Kind:** semantics
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
- **Kind:** semantics
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

### RLE-060 A CMMN file is refused by name at the rule set import, before any other check
- **Traces to:** `TR-STD-04`, `TR-STD-26`, #112
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `claim-case.cmmn`, whose root is `definitions` in namespace `http://www.omg.org/spec/CMMN/20151109/MODEL` (CMMN 1.1), with a `casePlanModel` on line 4.
- **When:** It is posted to `POST /api/bpm/rules/v1/rule-sets/{name}/versions` with `Content-Type: application/xml`.
- **Then:**
  - It answers `422 CMMN_UNSUPPORTED` with the detail "CMMN is not supported; model the case as a BPMN process", the `namespace` and the `line` of the root element (1).
  - The reader stops at the root element: no other check runs, no transaction is opened, and nothing is written.
  - A root element in another unknown namespace, such as XPDL or BPEL, is `422 VALIDATION_FAILED` naming the namespace, as before. A DTD or an entity is refused before the namespace is looked at.
- **Example:** `claim-case.cmmn -> 422 CMMN_UNSUPPORTED, 0 rows`.
- **Test ID:** `RLE_060_a_cmmn_file_is_refused_by_name_with_its_namespace_and_line`, `RLE_060_the_reader_stops_at_the_root_so_no_other_check_runs`, `RLE_060_a_root_in_another_unknown_namespace_is_a_validation_failure_naming_it`, `RLE_060_a_dtd_is_refused_before_the_namespace_is_looked_at`, `RLE_060_cmmn_is_422_with_the_detail_the_namespace_and_the_line`, `RLE_060_a_cmmn_file_is_422_cmmn_unsupported_at_the_rule_set_import_and_writes_nothing`

### RLE-061 A DMN element outside the current stage is refused by name with its id, line and stage
- **Traces to:** `TR-STD-04`, `TR-STD-26`, #112
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `fees.dmn` contains a decision table `fee-table` (supported), a second `decision` `risk-adj` with an `informationRequirement` that names a required decision on line 40 (`S1`), a `businessKnowledgeModel` `bkm-1` on line 55 (`S2`), a `decisionService` `ds-1` on line 70 (`S2`), and a `context` boxed expression on line 81 (`S2`).
- **When:** It is imported as a rule set version.
- **Then:**
  - The answer is `422 DMN_UNSUPPORTED_ELEMENT` with 4 entries `{ element, elementId, line, stage, message }` in `unsupportedElements`, in document order (PD-141: the field and the item shape are the Process Engine's, `PRE-108`; `message` is plain English that may change, the element and the stage are the contract). An element with no `id` of its own is named by the `id` of the decision that holds it. Nothing is written.
  - A wire to input data (`requiredInput`), documentation, vendor extensions and elements in other namespaces are not refused.
  - At most 100 entries are returned, with `violationsTruncated: true` beyond that; ids lose control characters and are cut at 200 characters.
  - An unknown `expressionLanguage` or `typeLanguage` is `422 EXPRESSION_LANGUAGE_UNSUPPORTED` with the element, its line and its stage (`not planned`) in `unsupportedElements`.
- **Example:** `fees.dmn with BKM and decision service -> 422 DMN_UNSUPPORTED_ELEMENT, 4 entries`.
- **Test ID:** `RLE_061_dmn_elements_outside_the_stage_are_listed_with_id_line_and_stage_in_document_order`, `RLE_061_a_wire_to_input_data_is_not_a_refusal`, `RLE_061_at_most_100_entries_are_listed_and_the_rest_set_the_truncated_flag`, `RLE_061_ids_lose_control_characters_and_are_cut_at_200_characters`, `RLE_061_an_unknown_expression_language_is_refused_with_its_line_and_stage`, `RLE_061_each_entry_carries_element_id_line_stage_and_message_in_unsupported_elements`, `RLE_061_a_list_over_100_entries_is_cut_and_says_so`, `RLE_061_dmn_elements_outside_the_stage_are_listed_and_nothing_is_stored`

### RLE-078 A decision table saved by a modelling tool imports as a draft and evaluates as its author expects
- **Traces to:** `TR-STD-25`, `TR-STD-01`, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** Tenant `bank-a`; caller `lan` holds `rules:author`. `card-fee-modeller.dmn` (DMN 1.3, as a widely used modeller writes it: generated rule ids, labels, an empty cell, DMNDI) has decision id `card-fee`, inputs `tier` (string) and `txCount` (integer), output `fee` (number) and hit policy `FIRST`; rows `"GOLD"` gives 0, `>= 3` gives 10000, `-` gives 50000. The corpus `rules/dmn/modellers/` holds one table each in the style of KIE/Drools (DMN 1.2, `UNIQUE`), Trisotech (DMN 1.4, `COLLECT`) and Camunda Modeler 5 (DMN 1.3, `FIRST`, date and boolean inputs, two outputs), each with an `.expected.json` of inputs, matched rule ids and outputs.
- **When:** `lan` imports `card-fee-modeller.dmn`, then each corpus file; the first is exported as DMN and imported again as v2; a second person approves each; each expected case is evaluated.
- **Then:**
  - `card-fee` v1 is a `DRAFT` with `sourceFormat: DMN_XML` and the file kept byte for byte with its `sourceHash`, DMNDI included. Nothing is published by an import.
  - Rule ids are those of the file, labels are kept, a DMN `number` becomes `decimal`.
  - `{ tier: GOLD, txCount: 5 }` gives `fee 0`, `{ tier: SILVER, txCount: 3 }` gives 10000, `{ tier: SILVER, txCount: 2 }` gives 50000.
  - Every corpus file imports and gives exactly its expected outputs and matched rules; export as DMN and import again keeps the canonical hash (`RLE-045`). Extension elements and foreign attributes change neither the table nor the hash.
  - A rule id that does not start with a letter is mapped on import (`RLE-224`).
- **Example:** `card-fee-modeller.dmn -> card-fee v1 DRAFT, fee 10000 for SILVER with 3 transactions`.
- **Test ID:** `RLE_078_a_modeller_decision_table_imports_as_a_draft_and_evaluates_as_expected`

### RLE-079 Anything the release cannot import is refused by name, with its line and the stage that adds it
- **Traces to:** `TR-STD-04`, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** The table of `RLE-078`, varied one feature at a time.
- **When:** `lan` imports it with: hit policy `PRIORITY`, `ANY`, `RULE ORDER` or `OUTPUT ORDER`; `COLLECT` with `aggregation="SUM"`; an output entry `age * 2`; an input expression `applicant.age`; `typeRef="tAge"`; a `businessKnowledgeModel`; a `literalExpression` decision; two decisions; decision id `Decision_1`; `expressionLanguage="javascript"`; a CMMN root; the input entries `? > 18`, `abs(-20)`, `!= 18`, `> 18 and < 65` and `date("2026-02-30")`.
- **Then:**
  - Each is refused and nothing is stored: no version and no source.
  - The message names the feature, its line and the stage that adds it where one does ("Hit policy PRIORITY arrives in stage S1"); an element outside the table is listed as `{ element, elementId, line, stage }` (`RLE-061`); a cell names its rule and column (`RLE-041`).
  - `Decision_1` is refused with "give the decision a kebab-case id such as card-fee", because the decision id becomes the rule set name.
  - Nothing is dropped or ignored silently.
- **Example:** `hitPolicy="PRIORITY" -> 422 "Hit policy PRIORITY arrives in stage S1 (line 5)"`.
- **Test ID:** `RLE_079_unsupported_dmn_content_is_refused_by_name_with_line_and_stage`

### RLE-080 A DMN file is read safely and within limits, in the caller's tenant only
- **Traces to:** `TR-SEC-07`, `TR-TEN-01`, #121
- **Milestone:** M3
- **Kind:** control
- **Given:** Files with a DTD declaring an external entity, a billion-laughs entity chain, elements nested 40 deep, a 70,000-character cell, 1,048,577 bytes, and text that is not well-formed; callers `dan` with `rules:read` only and `binh` of `bank-b`.
- **When:** Each file is imported; `dan` imports the valid table; `binh` reads `card-fee` after `lan`'s import in `bank-a`.
- **Then:**
  - The DTD files are refused with "DTDs are not allowed in a DMN document" and no entity is resolved; the deep, long and malformed files are refused naming the limit (depth 32, text 65,536 characters); the large file is `413 PAYLOAD_TOO_LARGE`.
  - `dan` gets `403 PERMISSION_DENIED` (`RLE-044`); `binh` gets `404 RULE_SET_NOT_FOUND`. Nothing is stored in a refused case.
- **Example:** `DOCTYPE with SYSTEM "file:///etc/passwd" -> 422, nothing read`.
- **Test ID:** `RLE_080_dmn_files_are_read_safely_within_limits_and_per_tenant`

### RLE-081 An imported model evaluates in strict mode, keeps one hash with YAML, and refuses the spec error mode
- **Traces to:** `TR-STD-03`, `TR-STD-25`, PD-125, PD-143, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** `card-fee-modeller.dmn` and a YAML document with the same inputs, output, labels, rule ids and cells.
- **When:** Both are imported, in two tenants; then the DMN file is imported with the root attribute `horus:errorMode` set to `strict`, `spec` and `lenient`.
- **Then:**
  - Both versions have the same `canonical_hash`; the canonical document carries no `errorMode`, because strict is what every schema version 1 rule set evaluates in (`RLE-096`).
  - `strict` keeps the hash. `spec` is refused with its line and "is added in stage S1" and any other value is refused, never read as if absent.
- **Example:** `horus:errorMode="spec" -> 422 "horus:errorMode spec is added in stage S1" (line 2)`.
- **Test ID:** `RLE_081_imported_model_records_its_error_mode_and_keeps_hash_identity`

### RLE-224 A DMN rule id that does not start with a letter is mapped on import, and export gives the original id back
- **Traces to:** `TR-STD-25`, `RLE-024`, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A table whose rules have the ids `_5f3a1` and `_9c2b7` (a modeller writes ids that start with an underscore), and a second table whose rules `_a1` and `r_a1` both exist. The v1 rule id pattern `^[A-Za-z][A-Za-z0-9_-]{0,63}$` (`RLE-024`) is unchanged for YAML, JSON and CSV.
- **When:** Each table is imported as DMN, evaluated, exported as DMN and imported again.
- **Then:**
  - The mapping is made in the DMN reader only and is deterministic: a valid id is kept; otherwise characters other than letters, digits, `_` and `-` are removed, `r` is put in front when the result does not start with a letter, and the result is cut at 64 characters. `_5f3a1` becomes `r_5f3a1`.
  - The original id is kept in the rule description as a trailing note `(DMN id _5f3a1)`, so the diff, the decision log and the cockpit show where the row came from.
  - Two ids that map to the same id are refused with `422 VALIDATION_FAILED`, problem code `DMN_RULE_ID_COLLISION`, naming both ids and the line; nothing is stored. Two rules with the same id stay `Duplicate rule id`.
  - Export writes the original id and the description without the note; importing that file again gives the same canonical hash.
  - An id with other characters (a space, a non-ASCII letter) is refused as before.
- **Example:** `_5f3a1 -> r_5f3a1 with description "(DMN id _5f3a1)"; _a1 with r_a1 -> DMN_RULE_ID_COLLISION`.
- **Test ID:** `RLE_224_a_dmn_rule_id_that_starts_with_an_underscore_is_mapped_and_exported_as_written`, `RLE_224_two_ids_that_map_to_the_same_id_are_refused_with_a_named_code`

### RLE-225 A DMN item definition is read as a type, and what has no v1 type is refused with a named code
- **Traces to:** `TR-STD-25`, `TR-STD-04`, `RLE-079`, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A DMN table whose input, output or decision names an `itemDefinition`: an alias of a built-in type with `allowedValues`, a collection of strings, and a structure of components for a decision with several outputs.
- **When:** The table is imported as DMN.
- **Then:**
  - A definition is read where a column or the decision uses it; one that nothing uses never stops the import.
  - An alias of `string`, `number`, `integer`, `boolean`, `date` or `date and time` gives the v1 type; `isCollection` of a scalar gives `list<T>`. Allowed values on an input become its `allowed` set, a list of strings or integers.
  - A decision typed by a structure (on the table's `typeRef` or the decision variable) gives its output columns the component types; the output names must be the components, else `DMN_TYPE_UNSUPPORTED`. A single unnamed output of a one-component structure takes the component name.
  - `outputValues`, or allowed values on an output type, are checked against the literal of every rule at import: a literal outside the set is refused with `DMN_OUTPUT_NOT_ALLOWED`, so no part of the constraint is dropped.
  - Allowed values are never dropped: those of an alias of an alias reach the input; input values and the allowed values of the input's type both hold (their common values become the set, none in common is refused); the allowed values of a component or of a scalar decision type are checked on the output even when the column has its own type; allowed values on a structure itself are refused with `DMN_ALLOWED_VALUES_UNSUPPORTED`.
  - Refused with `422 VALIDATION_FAILED` and the problem code `DMN_TYPE_UNSUPPORTED`: a type name that is neither built-in nor defined (`time`, `Any`, durations), a definition with a `typeConstraint` or a `functionItem`, one that refers to itself or nests deeper than 16 levels, a collection of structures or of lists, a structure inside a structure, a structured input or output column, and a definition with neither a `typeRef` nor components. Allowed values that are not a list of constants (a range, a comparison) are refused with `DMN_ALLOWED_VALUES_UNSUPPORTED`, as are allowed values on a list input. `defaultOutputEntry` stays refused as `DMN_UNSUPPORTED_ELEMENT`, stage S1.
- **Example:** `tEmployment (string, "SALARIED", "SELF_EMPLOYED") -> input employment: string, allowed [SALARIED, SELF_EMPLOYED]`; `tOffer {status: string, limit: number} -> outputs status string, limit decimal`; `typeRef time -> DMN_TYPE_UNSUPPORTED`.
- **Test ID:** `RLE_225_an_item_definition_of_a_string_with_allowed_values_types_the_column_and_closes_its_set`, `RLE_225_a_collection_item_definition_is_a_list_input`, `RLE_225_a_structure_types_a_decision_with_several_outputs`, `RLE_225_outputs_that_do_not_match_the_components_of_the_decision_structure_are_refused`, `RLE_225_a_built_in_type_with_no_v1_form_is_refused_with_a_named_code`, `RLE_225_a_structured_input_a_type_constraint_and_a_self_reference_are_refused_with_a_named_code`, `RLE_225_an_item_definition_that_no_column_uses_does_not_stop_the_import`, `RLE_225_a_literal_outside_the_output_values_is_refused_and_one_inside_is_kept`, `RLE_225_allowed_values_that_are_a_range_are_refused_not_dropped`

### RLE-226 A DMN rule set name and a single output name are made from the decision when the file gives none that fits
- **Traces to:** `TR-STD-25`, `RLE-079`, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A table whose decision id is a generated one (`_3b2953a3-745f-4d2e-b55d-75c8c5ae653c`), a camel case id (`cardFee`) or a name with spaces, and a table whose single output column has no `name`.
- **When:** The table is imported as DMN.
- **Then:**
  - A decision id or name that is already kebab-case is the rule set name, as before. Otherwise the name is made from the decision id: camel case is split at its humps, runs of other characters become one hyphen, letters are lower case, and `decision-` is put in front when the result does not start with a letter. The decision name, then the names of the definitions, are tried when the id has no letter or digit; the result is cut at 100 characters.
  - When none gives a name, the import is refused with the problem code `DMN_RULE_SET_NAME`.
  - A single output column with no `name` is named from the structure's only component, else from the table's `outputLabel`, the decision variable, the decision name or the decision id, with runs of other characters turned into `_` (`Approval Status` becomes `Approval_Status`). Several output columns without names are refused as before.
- **Example:** `_3b2953a3-745f-4d2e-b55d-75c8c5ae653c -> decision-3b2953a3-745f-4d2e-b55d-75c8c5ae653c; cardFee -> card-fee; output of "Approval Status" -> Approval_Status`.
- **Test ID:** `RLE_226_a_rule_set_name_is_made_from_the_decision_id`, `RLE_226_a_kebab_case_id_is_kept_as_written`, `RLE_226_a_single_output_with_no_name_takes_the_name_of_the_decision`, `RLE_226_two_unnamed_outputs_are_refused`

### RLE-227 A DMN COLLECT table with SUM, COUNT, MIN or MAX imports as a collect output and exports back
- **Traces to:** `TR-STD-25`, `RLE-003`, `RLE-079`, #121
- **Milestone:** M3
- **Kind:** semantics
- **Given:** A fee table with hit policy `COLLECT` and `aggregation="SUM"`, and the same table with `COUNT`, `MIN` and `MAX`; monthly fees of 10000 for every customer, 5000 from 20 transactions and 7000 from 50.
- **When:** The table is imported, evaluated, exported as DMN and imported again.
- **Then:**
  - The single output gets the v1 `collect` of the same name (`sum`, `count`, `min`, `max`) and keeps its scalar type; `COUNT` makes it `integer` whatever the cells hold. Evaluation follows `RLE-003`.
  - A customer with 60 transactions pays 22000 under `SUM`; with 25 transactions the `COUNT` is 2.
  - Export writes `aggregation` on the table and imports to the same canonical hash. A table with a `collect` on several outputs, or on one of several, has no DMN form and export refuses it naming the output.
  - With no matching rule, every aggregate, `COUNT` included, is `null`, as in `RLE-003` (shipped in 1.0.0). TODO: DMN-conformant `COUNT = 0` for no match is deferred to the spec-mode profile (S1) or an opt-in output property.
  - An aggregation on a table that is not `COLLECT`, a value other than `SUM`, `COUNT`, `MIN` and `MAX`, or an aggregation on a table with more than one output is refused with the problem code `DMN_AGGREGATION_UNSUPPORTED`.
- **Example:** `COLLECT SUM, txCount 60 -> fee 22000; COLLECT COUNT, txCount 25 -> 2`.
- **Test ID:** `RLE_227_collect_sum_adds_the_matched_rules`, `RLE_227_collect_count_counts_the_matched_rules_as_an_integer`, `RLE_227_collect_min_and_max_pick_the_smallest_and_the_largest`, `RLE_227_an_aggregate_exports_as_dmn_and_imports_again_with_the_same_hash`, `RLE_227_an_aggregation_with_another_hit_policy_or_name_or_outputs_is_refused_with_a_named_code`, `RLE_227_a_table_with_aggregates_on_several_outputs_has_no_dmn_form`

## Host-Registered Functions (RE-F-27, PD-119)

A host registers named functions that rule cells may call next to the built-in functions. Registration is the typed Java SPI `RuleFunctionRegistry` (DESIGN 4.1): each function declares a name, parameter types, a result type, a version label and a step cost. The functions are pure calculations: a function receives its argument values and nothing else, never the tenant, the caller, the clock or a connection, so a decision replays from its log entry (`TR-DET-01`, `TR-DET-05`). Data the host looks up, such as a customer segment held in core banking, is passed as an input. Functions belong to the host, never to a tenant. The parameter types are `string`, `boolean`, `number` (an `integer` or `decimal` input), `date`, `datetime` and `money`. A rule set that calls a host function has no DMN export, because DMN `external` functions stay refused (`422 DMN_EXTERNAL_FUNCTION_UNSUPPORTED`).

### RLE-055 A registered host function can be called in a rule cell
- **Traces to:** `TR-SEC-01`, `TR-DET-01`, PD-119, #34
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The host registers `segmentOf(balance: money, tenureMonths: number) -> string`, version `2027.1`, step cost 1. It returns `PRIORITY` when the balance is at least 500,000,000 VND and the tenure is at least 24 months, otherwise `MASS`. Tenant `bank-a` publishes `card-fee` v1 (`first`): `R1` (`segmentOf(balance, tenureMonths) == 'PRIORITY' -> fee: 0 VND`), `R2` (`{} -> fee: 50000 VND`).
- **When:** Evaluate (a) `{ balance: 600000000 VND, tenureMonths: 36 }`, (b) `{ balance: 600000000 VND, tenureMonths: 12 }`, (c) `{ balance: null, tenureMonths: 36 }`; then replay (a).
- **Then:**
  - (a) matches `R1`, `fee: { currency: VND, amount: 0 }`. (b) matches `R2`, fee 50000 VND.
  - (c) does not call the function: a `null` argument makes the call `null` (FEEL null rule, `RLE-006`), so `R1` does not match and `R2` gives 50000 VND.
  - Each evaluation writes one decision row. The replay of (a) gives the same rule and fee and writes nothing.
  - A function never sees a tenant: a caller of `bank-b` has no `card-fee`, and the function is never called for it.
- **Example:** `segmentOf(600000000 VND, 36) -> PRIORITY -> R1 -> 0 VND; (600000000 VND, 12) -> MASS -> R2 -> 50000 VND; (null, 36) -> null -> R2`.
- **Test ID:** `RLE_055_a_registered_host_function_is_called_from_a_rule_cell`, `RLE_055_a_function_never_sees_the_tenant_and_functions_are_host_wide`, `RLE_055_a_host_function_prices_a_card_fee_and_an_unregistered_one_is_refused_at_import`

### RLE-056 A call to an unknown function, or with the wrong arguments, is refused at import
- **Traces to:** `TR-SEC-01`, `TR-DET-02`, PD-119, #34
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The registry of `RLE-055`; caller `lan` holds `rules:author`.
- **When:** `lan` imports four files: in the first, `R1` calls `riskScore(balance)`, which is not registered; in the second, `segmentOf(tenureMonths, balance)`; in the third, `segmentOf(balance)`; in the fourth, `segmentOf(balance, tenureMonths) > 3`.
- **Then:**
  - Each answers `422 VALIDATION_FAILED` with a violation on `R1`, its column and the character position. The first has code `FORBIDDEN_CONSTRUCT` ("Unknown function 'riskScore'"), the code an unknown function has always had.
  - The others have `TYPE_MISMATCH`, naming the parameter number and its type, or the argument count and the signature. Nothing is stored.
  - The same check runs at draft replacement, request-publish and approval: a draft imported while `segmentOf` was registered can be neither requested nor approved after the host removes the function, and nothing is approved.
- **Example:** `riskScore(balance) -> FORBIDDEN_CONSTRUCT; segmentOf(tenureMonths, balance) -> TYPE_MISMATCH "parameter 1 is a money"; segmentOf(balance) -> TYPE_MISMATCH "takes 2 arguments"`.
- **Test ID:** `RLE_056_unknown_or_ill_typed_function_call_is_refused_at_import`, `RLE_056_the_check_runs_again_at_request_publish_and_approval_after_the_host_removes_the_function`, `RLE_056_postgres_refuses_a_call_to_a_function_the_host_removed_and_approves_nothing`

### RLE-057 A function that fails, or returns the wrong type, fails the decision closed
- **Traces to:** `TR-SEC-01`, `TR-SEC-08`, PD-119, #34
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `card-fee` v1 of `RLE-055` is published.
- **When:** The implementation (a) throws `IllegalStateException("upstream timeout")`; (b) returns an `Integer` where a `string` is declared; (c) is registered with step cost 20,000 and `card-fee` is imported again as v2 under a step budget of 10,000; (d) costs 20 steps under a budget of 15 for a version approved at the default budget.
- **Then:**
  - In (a) and (b) the outcome is `Failed` with `EVALUATION_ERROR` (`422`), `error_code = FUNCTION_FAILED` on the decision row and no partial outputs. The problem body and the row name the function `segmentOf`, but never carry the exception message, class or stack trace, which could hold an input value. The server log carries them with the `correlationId`.
  - In (c) the import is refused with `EVALUATION_BUDGET_EXCEEDED` (`RLE-004`), because the worst case counts each call's declared step cost.
  - In (d) the decision stops with `EVALUATION_BUDGET_EXCEEDED`, because each call counts its declared step cost against the step budget of the decision, at the same step on every machine.
- **Example:** `segmentOf throws -> 422 EVALUATION_ERROR FUNCTION_FAILED "function segmentOf failed"; step cost 20000 over budget 10000 -> EVALUATION_BUDGET_EXCEEDED at import`.
- **Test ID:** `RLE_057_a_failing_host_function_fails_the_decision_closed_without_leaking`, `RLE_057_a_function_that_returns_the_wrong_type_fails_closed_and_a_costly_one_is_refused_at_import`, `RLE_057_a_call_counts_its_declared_step_cost_against_the_budget_of_each_decision`

### RLE-058 The registry is checked at start-up, and each decision records the function versions it used
- **Traces to:** `TR-SEC-01`, `TR-DET-05`, PD-119, #34
- **Milestone:** M1
- **Kind:** semantics
- **Given:** (a) a host that registers `segmentOf` twice; (b) one that registers `round`, a built-in name; (c) one that registers `Segment-Of`, outside `^[a-z][A-Za-z0-9]{0,63}$`; (d) `card-fee` v1 approved and decision `D1` made with `segmentOf` version `2027.1`, after which the host redeploys with version `2027.2`.
- **When:** The host starts; in (d), `card-fee` is evaluated and `D1` is replayed; then the host starts with `evaluation.function-version-change: refuse`.
- **Then:**
  - (a) to (c) fail start-up with a message naming the function; nothing is served.
  - In (d), start-up logs one WARN naming `segmentOf`, `2027.1`, `2027.2` and the published versions that call it. `/api/bpm/meta` lists the changed function, to the callers of tenants that own a version that calls it. Each new decision row records `segmentOf@2027.2`. The replay of `D1` answers `422 REPLAY_NOT_POSSIBLE` naming the function and both version labels.
  - With `warn` (the default, PD-119) evaluation goes on. With `refuse` evaluation fails closed with `EVALUATION_ERROR`, `error_code = FUNCTION_CHANGED`, until a new version is approved under four eyes. A version approved before its functions were recorded has no baseline and is never refused.
- **Example:** `segmentOf 2027.1 -> 2027.2: WARN once, decision row "segmentOf@2027.2", replay of D1 -> 422 REPLAY_NOT_POSSIBLE; refuse -> FUNCTION_CHANGED`.
- **Test ID:** `RLE_058_registry_is_checked_at_startup_and_decisions_record_function_versions`, `RLE_058_refuse_fails_closed_with_function_changed_until_a_new_version_is_approved`, `RLE_058_postgres_keeps_the_approved_function_versions_and_the_version_each_decision_used`, `RLE_058_postgres_replaces_nothing_it_does_not_own_when_a_version_calls_no_function`, `RLE_058_a_redeploy_with_another_version_warns_once_and_meta_shows_the_change_to_its_tenant`, `RLE_058_a_function_registered_twice_or_a_bad_setting_stops_the_host_naming_it`

## JUnit Rule Tests and Coverage Report (RE-F-28, PD-120)

A test-scope module, `rule-engine-test`, loads a rule set file (YAML, JSON or DMN) in a JUnit 5 test and evaluates it in memory: no database, no Spring context, no masking key. The test uses the engine's own reader, compiler and evaluator, so a test result is what production would decide for the same version, inputs and clock (`TR-DET-01`). The extension records which rows each test matched and writes a row coverage report for the whole test run, which CI keeps as an artifact. The ids `RLE-060` and `RLE-061` are used elsewhere in this file, so this feature is `RLE-059`, `RLE-064` and `RLE-065`.

### RLE-059 A rule set file is tested in JUnit with the engine's own evaluation and a fixed clock
- **Traces to:** `TR-QA-01`, `TR-DET-01`, #35
- **Milestone:** M1
- **Kind:** semantics
- **Given:** `src/test/resources/rules/card-fee.yaml` (`first`): `R1` (`tier == 'GOLD' -> fee: 0 VND`), `R2` (`txCount >= 3 -> fee: 10000 VND`), `R3` (`{} -> fee: 50000 VND`). A test class annotated `@RuleSetTest("rules/card-fee.yaml")` with the default clock `2027-01-01T00:00:00Z`, overridable per class (`clock = "..."`) or per test (`@RuleSetClock("2027-06-30T17:00:00Z")`).
- **When:** A test calls `rules.evaluate(Map.of("tier", "SILVER", "txCount", 5))` and, in a second test, `rules.evaluate(Map.of("tier", "GOLD", "extra", 1))`.
- **Then:**
  - The first returns `matchedRuleIds = [R2]` and `fee = Money.ofMinor("VND", 10000)`, the outcome the engine's evaluator gives for that file, inputs and clock.
  - The second fails with `INPUT_INVALID` naming `extra`, as `evaluation.reject-undeclared-inputs` does by default.
  - `today()` reads the fixed clock in the rule set's time zone: at `2027-06-30T17:00:00Z` it is 1 July in `Asia/Ho_Chi_Minh`. Two runs on any JVM give the same results.
  - A file that fails import fails the test class before any test runs, with the same violations (rule id, column, code, message) as `422 VALIDATION_FAILED`. A file not on the class path fails the class naming the path.
- **Example:** `card-fee.yaml, {tier: SILVER, txCount: 5} -> R2, fee 10000 VND; {tier: GOLD, extra: 1} -> INPUT_INVALID 'extra'`.
- **Test ID:** `RLE_059_rule_set_is_evaluated_in_junit_like_the_engine`, `RLE_059_the_default_clock_is_fixed_and_a_class_or_test_may_change_it`, `RLE_059_a_test_with_a_clock_of_its_own_reads_today_in_the_rule_sets_time_zone`, `RLE_059_the_clock_annotation_overrides_the_default_for_one_test`, `RLE_059_a_clock_that_is_not_an_instant_is_refused_naming_where`

### RLE-064 The run writes a row coverage report that names every row no test matched
- **Traces to:** `TR-QA-01`, #35
- **Milestone:** M1
- **Kind:** semantics
- **Given:** Two test classes over `card-fee.yaml` whose tests match `R1` and `R2` and never `R3`; a third class over `kyc-route.yaml` (`unique`, rows `K1`, `K2`) whose test hits `K1` and `K2` together (`UNIQUE_HIT_VIOLATION`).
- **When:** The tests run (`mvn test`).
- **Then:**
  - `target/horus-rules-coverage/rules-coverage.json` and `rules-coverage.md` list each rule set by name and canonical hash with its rows in document order, each `covered` or `not covered`, and the tests that matched it. The folder is `horus.rules.coverage.report-dir`.
  - The report reads `card-fee: 2 of 3 rows covered; R3 not covered` and `kyc-route: 0 of 2 rows covered; K1, K2 not covered`. One summary line per rule set is printed.
  - A row counts as covered only when a test evaluation returned a match with that row in `matchedRuleIds`. A failed evaluation (`UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR`) covers nothing, because it produced no outputs.
  - The same tests give a byte-identical report: no time, machine or input value is in it. A rule set no test loaded is absent; the report is not a list of the files on the class path.
  - The module is test scope only: the starter never auto-configures it, it has no database, no Spring context and no decision log, and its one tenant is the fixed `test`.
- **Example:** `card-fee: R1, R2 covered, R3 not; kyc-route: K1 and K2 matched together, neither covered`.
- **Test ID:** `RLE_064_coverage_report_names_each_row_no_test_matched`, `RLE_064_the_same_tests_give_a_byte_identical_report_and_an_unloaded_rule_set_is_absent`, `RLE_064_a_file_that_fails_import_fails_the_class_with_the_violations_before_any_test_runs`, `RLE_064_the_module_is_test_scope_only_with_no_database_no_spring_context_and_no_auto_configuration`

### RLE-065 The build fails on uncovered rows only when the project sets a threshold
- **Traces to:** `TR-QA-01`, PD-120, #35
- **Milestone:** M1
- **Kind:** semantics
- **Given:** The run of `RLE-064`, and the setting `horus.rules.coverage.fail-on` (in `junit-platform.properties` or as a system property) with the values `none` (the default), `unique` or `any`.
- **When:** The run ends with each value.
- **Then:**
  - `none` passes and prints one summary line per rule set. This supersedes the earlier rule that an uncovered row in a `unique` table fails the build (PD-120).
  - `unique` fails the run naming `kyc-route` `K1` and `K2`, because only `unique` tables are held to full coverage.
  - `any` fails naming `card-fee` `R3` and `kyc-route` `K1`, `K2`.
  - An unknown value fails the run naming the setting. The report is written in every case.
- **Example:** `fail-on none -> pass; unique -> kyc-route K1, K2; any -> card-fee R3; kyc-route K1, K2; most -> fails naming horus.rules.coverage.fail-on`.
- **Test ID:** `RLE_065_uncovered_rows_fail_the_build_only_at_the_configured_threshold`, `RLE_065_a_run_that_covers_every_row_passes_whatever_the_threshold`

### RLE-210 A publication request can name the date the version takes effect, and the approver sees it
- **Traces to:** `TR-SEC-04`, `TR-AUD-01`, PD-139, #470
- **Milestone:** M3
- **Kind:** control
- **Given:** Draft v2 of `card-fee` by `lan`, who requests publication at 2026-12-15T01:30:00Z; `ha` holds `rules:approve`.
- **When:** `lan` sends `:request-publish` with `{"effectiveFrom": "2027-01-01T00:00:00Z"}`; with `2026-12-14T00:00:00Z`; with `"tomorrow"`; with no date. `ha` reads `GET /approvals`, then approves with the `canonicalHash` of the list item.
- **Then:**
  - `200`, v2 is `PENDING_APPROVAL` with `effectiveFrom` 2027-01-01T00:00:00Z, shown in the version, in the pending approvals item and in `rules.version.publish-requested.v1`. The date is not part of the content hash.
  - A date before the request time is `422 EFFECTIVE_DATE_IN_PAST` and v2 stays `DRAFT`; a value that is not an ISO 8601 instant with an offset is `400 INPUT_INVALID`.
  - Once pending the date cannot change: a draft is the only version that can be replaced, so another date needs a withdrawal and a new request. `lan` still cannot approve it (`403 SELF_APPROVAL_FORBIDDEN`) and an approval naming another hash is `409 CONTENT_CHANGED`.
  - Approved, v2 is `PUBLISHED` and is the version in force from 2027-01-01T00:00:00Z, not before. With no date the version takes effect at approval, as before. The date joins the idempotency hash only when named.
- **Example:** `request-publish {effectiveFrom: 2027-01-01T00:00:00Z} -> 200, GET /approvals shows effectiveFrom; approve -> in force from 2027-01-01`
- **Test IDs:** `RLE_210_a_requested_effective_date_is_stored_shown_to_the_approver_and_applied`, `RLE_210_a_past_effective_date_is_refused_and_the_draft_stays_a_draft`, `RLE_210_the_effective_date_does_not_change_the_hash_or_the_four_eyes`, `RLE_210_a_date_asked_for_at_request_decides_which_version_is_in_force_and_a_past_date_is_refused`

### RLE-211 The decision log refuses UPDATE, DELETE and TRUNCATE in the database
- **Traces to:** #365, AUD-03, `TR-OBS-01`
- **Milestone:** M3
- **Kind:** control
- **Given:** `rle_decision` holds a `card-fee` decision of `bank-a` in `rle_decision_2027_01` and a straggler of March 2027 in `rle_decision_default`.
- **When:** (a) The application's database role runs `UPDATE`, `DELETE` or `TRUNCATE` on `rle_decision` or on a monthly partition, and `UPDATE` or `TRUNCATE` on the default partition. (b) The clock reaches March and the partition job creates `rle_decision_2027_03`. (c) Retention drops `rle_decision_2027_01`.
- **Then:** (a) Each statement fails with SQLSTATE `55000` ("rle_decision is append-only") and the rows are unchanged. (b) The job still moves the straggler out of the default partition, the only `DELETE` the database allows on the log, and the new partition refuses `DELETE` and `TRUNCATE` as well: a row trigger on the parent is cloned onto each partition at `ATTACH`, and the job adds the `TRUNCATE` trigger of each partition it creates. (c) Retention by `DROP TABLE` of a whole month works as before and is recorded in `rle_decision_log_drop`. The triggers are part of the V1 baseline. **Limit:** `DELETE` on `rle_decision_default` stays allowed because the roll-over needs it, so a role that can `DELETE` there can erase rows that fall outside every monthly partition (a straggler not yet moved); the control does not cover that table. A table owner can also drop a trigger. The application runs with a role that does not own the tables (wiki, Multi-Tenancy and Security).
- **Example:** `UPDATE rle_decision SET outcome = 'X' -> 55000; DELETE FROM rle_decision_default -> allowed`.
- **Test ID:** `RLE_211_control_the_decision_log_refuses_update_delete_and_truncate_and_the_roll_over_still_works` (`DecisionLogPartitionMaintenanceIT`)

### RLE-220 A decision replay by ids reads each id by key and never scans the period
- **Traces to:** `TR-SEC-04`, `TR-PERF-01`, #365 (AUD-06)
- **Milestone:** M3
- **Kind:** control
- **Given:** `card-fee` v1 with 100 logged November decisions at bank-a, draft v2, and a decision log that counts period scans; `pricing-approver` holds `rules:simulate` and `rules:log:read`.
- **When:** She replays two chosen ids over 1 November to 1 December, then the same two ids over the first two hours of November.
- **Then:** Both replay, newest first, with no period scan: the work follows the number of ids (at most `simulation.max-inputs`), not the length of the period. An id of another tenant or rule set, or logged outside the period, is left out as before.
- **Example:** `decisionIds [oldest, newest], 31 days -> 2 items, 0 scans; first 2 hours -> 1 item`
- **Test IDs:** `RLE_220_a_selection_reads_each_id_by_key_and_never_scans_the_period`

### RLE-221 A list cursor names the key that signed it, so keys rotate and old cursors fail as 400
- **Traces to:** `TR-SEC-04`, RLE-039, #365 (AUD-13)
- **Milestone:** M3
- **Kind:** control
- **Given:** The host sets `horus.bpm.rules.cursor.keys` with `k2026` and `k2027` and `cursor.active-key-id=k2027`; a cursor for bank-a's rule set list was signed with `k2026`.
- **When:** The cursor is replayed to a replica that has both keys; to one that dropped `k2026`; to one whose `k2027` has other bytes; a cursor of the format before key ids is replayed; the host sets a key of 8 bytes or an active id that names no key.
- **Then:** New cursors carry `k2027` and the `k2026` cursor still works while that key is listed. After `k2026` is removed, a cursor of a changed key and a cursor with no key id are `400 QUERY_INVALID`, never `500`. The short key and the unknown active id stop start-up. With keys configured the engine neither creates nor reads `rle_engine_key`; with none, the key stays in that table and its id is derived from its bytes.
- **Example:** `cursor ...k2026... with keys {k2027} -> 400 QUERY_INVALID`
- **Test IDs:** `RLE_221_a_cursor_names_its_key_and_a_rotation_keeps_the_cursors_of_the_key_still_listed`, `RLE_221_a_cursor_without_a_key_id_or_signed_with_another_key_of_the_same_id_is_query_invalid`, `RLE_221_a_key_that_is_too_short_or_not_the_active_one_stops_start_up`, `RLE_221_configured_keys_are_decoded_and_a_wrong_setting_names_the_property`, `RLE_221_a_start_without_cursor_keys_logs_a_warning_that_names_the_properties`

### RLE-222 Display names need a permission of an installed engine
- **Traces to:** `TR-SEC-04`, RLE-196, #365 (AUD-09)
- **Milestone:** M3
- **Kind:** control
- **Given:** `customer-7` of bank-a is signed in and holds no `rules:*` or `processes:*` permission; `minh` holds `rules:read`; the host directory knows `lan` as "Tran Thi Lan".
- **When:** Each asks `GET /api/bpm/display-names?users=lan`.
- **Then:** `customer-7` gets `403 PERMISSION_DENIED` and no name; `minh` gets the name.
- **Example:** `customer-7 -> 403; minh -> {users: {lan: "Tran Thi Lan"}}`
- **Test IDs:** `RLE_222_a_signed_in_account_with_no_engine_permission_cannot_look_up_names`

