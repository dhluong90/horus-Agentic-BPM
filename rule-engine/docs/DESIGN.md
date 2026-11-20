# Rule Engine: Design

Status: architecture baseline (M0), updated 2026-11-18 and 2026-11-19 for the accepted ADR-0009, ADR-0016, ADR-0018, ADR-0019 and the new ADR-0021 to ADR-0023. Conforms to ADR-0001 to ADR-0006, ADR-0009 (cache coherence), ADR-0010 (contract conventions), ADR-0011 (Spring Boot 4.x baseline), ADR-0014 (tenant switching and host metadata), ADR-0016 (Java API conventions), ADR-0017 (full DMN on the roadmap; the do-now items of [`standards-compliance.md`](../../docs/architecture/standards-compliance.md) section 9 are applied below and tagged with their milestone), ADR-0018 (the DMN semantic model is canonical), ADR-0019 (HORUS-built FEEL; FEEL null semantics from M1, PD-058), ADR-0021 (Jackson 3 inside the core), ADR-0022 (package root) and ADR-0023 (fixed table names), and to [`docs/product/requirements.md`](../../docs/product/requirements.md). Cross-module facts are owned by [`docs/architecture/overview.md`](../../docs/architecture/overview.md).

Contents: 1 Context and boundaries. 2 Modules, ports and adapters. 3 Domain model and compiler. 4 Java API and REST contract. 5 Storage model. 6 Runtime mechanics and capacity math. 7 Open questions.

## 1. Context and boundaries

![Rule Engine in the banking security zones](images/security-zones.svg)

Source: [`diagrams/security-zones.mmd`](diagrams/security-zones.mmd). Edges 6 to 8 run inside the host transaction: the evaluation and its one decision row commit or roll back with the host's business data. Edge 9 is the cache coherence path (ADR-0009). The engine makes no call to core or external systems; edge 10 is host code.

`horus-rule-engine` is an embeddable, deterministic decision-table library for Java 21 and 25 hosts on Spring Boot 4.x (every 4.x minor in open-source support; 3.5.x tested until 1.0, ADR-0011) and PostgreSQL 14 or later (the range from 1.0 is pending PD-055).

- **Inbound callers:** host Java services (`RuleEngine` interface), the optional REST controllers (`/api/bpm/rules/v1`), and the cockpit.
- **Outbound dependencies:** host `DataSource` and `PlatformTransactionManager` through Spring `JdbcClient`, host security context (`TenantResolver`, `CallerResolver`), host event publisher (`RuleEventPublisher`), `MeterRegistry` and an optional OpenTelemetry `Tracer`.
- **State ownership:** only tables named `rle_*` and the Flyway history table `rle_schema_history` (`TR-EMB-06`). The names are fixed; there is no prefix property (ADR-0023). If an adopter needs isolation later, the path is a dedicated PostgreSQL schema as an additive option, which leaves migration checksums unchanged.
- **Explicit non-goals:** no standalone server (ADR-0004), no JPA or Hibernate, no SpEL, MVEL or Groovy scripting (ADR-0003, `TR-SEC-01`), no network I/O (`TR-EMB-05`), no cross-tenant inheritance at evaluation time.

## 2. Modules, ports and adapters

![Rule Engine modules, ports and adapters](images/components.svg)

Source: [`diagrams/components.mmd`](diagrams/components.mmd).

| Module | Contents | May depend on |
|---|---|---|
| `rule-engine-core` | `api`, `spi` and `internal` packages: parser, type checker, compiler, evaluator, `Money`, ports | JDK and Jackson 3 in `internal` only (ADR-0021, `TR-EMB-07`) |
| `rule-engine-postgres` | `JdbcClient` adapters, Flyway `rle_*`, `LISTEN` and epoch sweep, partition maintenance, outbox relay | core, Spring JDBC, Flyway |
| `rule-engine-rest` | Controllers under `/api/bpm/rules/v1`, RFC 9457 errors, idempotency filter | core, Spring WebMvc |
| `rule-engine-test` | In-memory adapters passing the storage contract suite, `TestClock`, `@RuleSetTest`, row coverage report | core |
| `rule-engine-spring-boot-starter` | Auto-configuration, profile guards, scheduler, Micrometer | all of the above |

Enforced by ArchUnit (`TR-EMB-03`, `TR-EMB-05`, `TR-SEC-01`): `rule-engine-core` imports no `org.springframework..`, `java.sql..`, `java.net..`, `javax.script..` or `java.lang.reflect.Method`; `rule-engine-postgres` imports no HTTP client or `java.net.http..`; public signatures in `..api..` and `..spi..` use only JDK, engine and JSpecify types (ADR-0011, ADR-0016); `tools.jackson..` is allowed in `..internal..` of the core only and `com.fasterxml.jackson.databind..` nowhere in the core (ADR-0021).

## 3. Domain model and compiler (`rule-engine-core`)

### 3.1 Immutable rule set and typed tree
- `RuleSetDefinition` (`record`): `schemaVersion`, `expressionLanguage` (only `urn:horus-bpm:expr:v1` before S1), `timeZone` (IANA zone id, default `UTC`; M1, DN-06), `ruleSetName`, `version` (the rule set version number, 1, 2, 3; never the optimistic lock), `hitPolicy` (`FIRST | UNIQUE | COLLECT`), the aggregate of each `collect` output (`NONE | SUM | MIN | MAX | COUNT`, declared per output as `collect:` in `rule-set.v1.schema.json`; `OutputClause.aggregate` in the model, #18), `moneyRounding` (default `HALF_EVEN`), `inputs` (`List<InputSchema>`), `outputs` (`List<OutputSchema>`), `rules` (`List<RuleRow>`), `canonicalHash` (SHA-256).
- `Expr` (`sealed interface`):
  - `LiteralExpr(ValueType type, Object value)`
  - `VarRefExpr(int slotIndex, String name, ValueType type)` *(bound to slot index at compile time)*
  - `UnaryExpr(UnaryOp op, Expr operand)`
  - `BinaryExpr(BinaryOp op, Expr left, Expr right)`
  - `InListExpr(Expr target, List<Expr> elements, boolean negated)`
  - `BetweenExpr(Expr target, Expr low, Expr high)`
  - `IsNullExpr(Expr target, boolean negated)`
  - `CallExpr(String functionName, List<Expr> args, PureFunction impl)`
- `Money` (`record(String currency, long minorUnits)`, scale derived from the currency): the canonical contract is section 3.4.
- The definition document format is owned by `rule-engine/api/schema/rule-set.v1.schema.json` (JSON Schema 2020-12). `tools/validate-examples.sh` checks every YAML example in `wiki/` against it.
- **Decision model identity (M1, DN-08).** A rule set is a DMN decision model (`definitions`) named after the rule set with exactly one decision, a decision table with the rule set's name. `RuleSetDefinition` is built as that DMN-shaped model (`Definitions`, `Decision`, `DecisionTable`, `InputClause`, `OutputClause`, `Rule`) by the v1 reader, so DRDs with several decisions (S1) extend the model instead of replacing it (ADR-0018, PD-060, [`standards-compliance.md`](../../docs/architecture/standards-compliance.md) section 3.6). The canonical writer prints the model back as the rule set's canonical JSON; for a v1 document the result is its RFC 8785 form unchanged, so `canonical_hash` does not depend on the model (`RLE-024`, `TR-STD-28`).
- **Effective date is version metadata, not content (M1).** `effectiveFrom` is stored in `rle_version.effective_from`, never in `canonical_json`, and is not part of `canonical_hash`. A document may still carry `effectiveFrom` (the v1 schema allows it): import moves the value to the draft's `effective_from` as the proposed date and removes it from the canonical document; the source document keeps it (`TR-STD-01`); `:request-publish` may override it, and from then on it is frozen with the version, shown to the approver and carried in `rules.version.publish-requested.v1` and `rules.version.published.v1`. Export never writes it. So the same rules scheduled for different dates in two environments have one hash, a date change is not a content change in the diff, and an exported version imports back with its hash (`RLE-032`). A pipeline that checks Git against production compares `source_hash` with the SHA-256 of the committed blob, and `canonical_hash` for behaviour (ADR-0026); it removes nothing before hashing.
- **Error mode per model (PD-066).** `errorMode` (`strict` or `spec`) is a declared property of the decision model, part of the canonical document and the hash, default `strict` for v1 rule sets and `spec` for imported DMN models. Changing it is a new version under four-eyes; each decision records the mode it used and replays with it; the cockpit decision view shows it. Guardrails fail closed in both modes; that is not a setting. `spec` evaluates from M3, when DMN table import ships (RE-F-30); before that a document that declares `spec` is refused at import with a message naming the milestone (`TR-STD-04`). The v1 schema gains the optional member `errorMode` additively.
- **Hash identity (M1, DN-04, ADR-0026).** `canonical_hash` is the SHA-256 of the RFC 8785 form of the canonical document written from the model, with every cell and value printed in the normal form of ADR-0026 decision 2 (non-integer numbers as strings with their scale, never through `double`; the column name dropped only when it is the left operand; escaped strings without Unicode normalisation; the M1 `datetime` in `Z`; money at the currency scale), without `effectiveFrom`, in the schema version it is stored in. The compiled rule set is built only by parsing the stored `canonical_json`; import refuses a model that does not survive `parse(print(model))`, and loading refuses to serve a version whose `canonical_json` no longer hashes to `canonical_hash` (`EVALUATION_ERROR` naming the failed integrity check, alert and audit entry). Git comparison uses `source_hash`. It is never recomputed when a later schema version exists; upgrading is an author action that creates a new draft. A DMN XML import (RE-F-30, M3) produces the lowest schema version that can express the model, so a v1 rule set exported to DMN and imported again keeps its hash, in any authoring style (`RLE-045`).

### 3.2 Two-phase compilation and slot-indexed evaluation
1. **Compile & Static Type-Check (Save / Publish):**
   - Recursive-descent parser builds `Expr` tree without reflection.
   - Type-checker binds every identifier to an integer input slot `0..K-1`, verifies operand types (`string`, `integer`, `decimal`, `boolean`, `date`, `datetime`, `money`, `list<T>`), validates `RuleFunctionRegistry` signatures, pre-compiles bounded regexes, and computes static reachability / overlap warnings.
   - Invalid expressions fail validation and cannot transition to `PENDING_APPROVAL` or `PUBLISHED` (`TR-DET-02`).
2. **Runtime Evaluation (`< 100 µs` hot path):**
   - Map `DecisionInput` into `Object[] slots` once; evaluate compiled `CompiledRuleSet` tree with zero regex compilation and zero `HashMap` lookups per row.
   - Pass explicit `Clock` / `Instant evaluationTime` so `today()` and `now()` are deterministic (`TR-DET-01`, `TR-DET-05`). `today()` is the date of the evaluation instant in the rule set's `timeZone` (default `UTC`); the zone is part of the versioned definition, so the decision row's version identifies it (M1, DN-06, `RLE-022`).
3. **Semantics aligned with FEEL (M1, DN-07, ADR-0019).** These rules were unspecified and are fixed now so that every expression has a FEEL equivalent with the same result ([`standards-compliance.md`](../../docs/architecture/standards-compliance.md) sections 4.3 and 4.4, `RLE-021`):
   - Numbers: `integer` and `decimal` are declared constraints on one number type, evaluated as `BigDecimal` with `MathContext.DECIMAL128` (34 significant digits, `HALF_EVEN`); `7 / 2` is `3.5`; division by zero is `EVALUATION_ERROR`.
   - `round(x, scale)` rounds half-even, as FEEL `decimal(x, scale)` (DMN 1.5 `round half even`); the scale is a whole number from -34 to 34, refused at import otherwise when it is a literal and `null` when it is not. Money arithmetic keeps its own rounding (`moneyRounding`, section 3.4). `daysBetween(d1, d2)` is FEEL `(d2 - d1).days`, negative when `d2` is earlier.
   - `matches(s, p)` is an unanchored search with the XPath `fn:matches` regex dialect: no back-references, no look-around; bounded as `TR-SEC-02`.
   - Strings compare by Unicode code point. `datetime` is a FEEL `date and time` with offset `Z` (Java `Instant`).
   - Null (PD-058, from M1, `RLE-006`): FEEL three-valued logic. `<`, `<=`, `>`, `>=`, arithmetic and functions with a `null` operand give `null`, except `coalesce`, which exists to handle `null`. `x between a and b` is `x >= a and x <= b` (DMN 1.5), evaluated as `and` is: a null target gives `null`, and a null bound gives `false` when the other comparison is already `false`. `==` and `!=` are two-valued: `null == null` is `true`, `null == 'GOLD'` is `false`, `null != 'GOLD'` is `true`. `x in [...]` is `true` only when an element equals `x` (FEEL `list contains`), so it is `false` for a null `x` unless the list holds `null`, and `x not in [...]` (FEEL `not(x in [...])`) is its negation. `is null` and `is not null` are always `true` or `false`. `and`, `or` and `not` follow the FEEL truth tables: `false and null` is `false`, `true and null` is `null`, `true or null` is `true`, `false or null` is `null`, `not(null)` is `null`. A `when` cell or a gateway condition matches only when it evaluates to `true`; `null` never matches. The type checker reports a warning, not an error, when an input that may be null is used with `!=` or `not in`, because those now match a missing value.
   - Regex evaluation wraps input in a bounded `InterruptibleCharSequence` enforcing `evaluation.regex-timeout-ms` (`50 ms` default, `TR-SEC-02`). Built as `BoundedRegex` (#20): the pattern of `matches(text, 'pattern')` must be a string literal of at most 1,000 characters in the subset of the XPath dialect that Java reads the same way, compiled once at import. Refused: back-references, look-around, inline flags and every `(?` group except `(?:`, possessive quantifiers, character class nesting and intersection, Unicode block names (only general categories such as `\p{L}`) and Java-only escapes. At evaluation a text over `evaluation.regex-max-input-length` is refused before matching. The matcher reads its text through a `CharSequence` that counts every character read: the primary bound is `evaluation.regex-max-reads` (1,000,000, lower only), deterministic, so a logged `REGEX_TIMEOUT` replays the same on any machine (`TR-DET-05`); the clock, checked every 1,024 reads against `evaluation.regex-timeout-ms`, is the backstop. The same reads are charged to the decision (#290): each evaluation of a compiled rule set runs on an evaluator scoped to one counter of `evaluation.regex-max-reads-per-decision` (5,000,000, lower only), confined to the evaluating thread, while the compiled tree stays shared and immutable. A match may read the lower of `regex-max-reads` and what is left to the decision, so the cost of one decision is bounded whatever the number of `matches` rows, and the stop is deterministic too; when the decision's remainder is what ran out, the `detail` names `evaluation.regex-max-reads-per-decision`. The same reads are charged a third time to the request (#309): a request that evaluates many decisions (`:simulate` now, `:evaluate-batch` when it is built) shares one counter of `evaluation.regex-max-reads-per-request` (50,000,000; 1 to 5,000,000,000, the old worst case of 1,000 items times 5,000,000, so a host may raise it up to there) between its decisions. A match may read the lowest of the three remainders; when the request's ran out, the item fails with `REGEX_TIMEOUT`, the `detail` names `evaluation.regex-max-reads-per-request`, and the request evaluates no further item. Each item after it is reported `EVALUATION_ERROR` with a `detail` beginning `REGEX_TIMEOUT: not evaluated`; items before it keep their results. The count is the same deterministic one, so the same request stops at the same item on every replay, and one WARN line names the rule set, tenant and item where it stopped. A `StackOverflowError` from deep backtracking (for example `(a|b)*c` on a few thousand characters on a small stack) is caught and fails the decision with `REGEX_TOO_DEEP`. The match stops on the calling thread without interrupts, and the thread and its connection are free at once. In a condition cell `matches('pattern')` tests the cell's own input. The decision row's `error_code` is the reason (`REGEX_TIMEOUT`, `REGEX_INPUT_TOO_LONG`, `REGEX_TOO_DEEP`) and `outcome` stays `EVALUATION_ERROR`. A `matches` cell has no DMN unary-test form yet, so DMN export refuses such a table, as it does other compound cells.

     ![Bounded regular expressions](images/regex-bounds.svg)

     Source: [`diagrams/regex-bounds.mmd`](diagrams/regex-bounds.mmd).
   - Money is a HORUS extension with no FEEL type: two money operands whose currencies are fixed by literals and differ are refused at import (`422 VALIDATION_FAILED`, rule and column); otherwise a mismatch is `EVALUATION_ERROR` at run time (`RLE-005`).
   - The differential test of `RLE-021` (`FeelDifferentialTest`) evaluates generated trees here and their FEEL translation in a reference FEEL implementation, in test scope only. Money and the clock readings are not generated; `matches` is, with patterns that the XPath dialect and Java read the same way. It accepts two documented differences: strict error mode (PD-066) fails where FEEL gives `null` for a division by zero; the reference orders strings by UTF-16 unit, so the generators leave out U+E000 to U+FFFF.

### 3.3 Version lifecycle

![Version lifecycle](images/version-lifecycle.svg)

Source: [`diagrams/version-lifecycle.mmd`](diagrams/version-lifecycle.mmd).

| From State | Command / Trigger | Guard | To State | Side Effect / Event |
|---|---|---|---|---|
| *(none)* | `POST /rule-sets` (new rule set) or `POST /rule-sets/{name}/versions` (document, or `?copyOf={v}`) | Valid schema; document `ruleSet` equals `{name}`; `rules:author` | `DRAFT` | Next `version_num`; canonical JSON and hash stored; `effectiveFrom` of the document moved to `effective_from` (section 3.1); history row `CREATED`; emit `rules.version.created.v1` (minimal outbox row from #1) |
| `DRAFT` | `replaceDraft` (`PUT /rule-sets/{name}/versions/{v}`, #165, `RLE-047`) | `rules:author`; caller is the draft's author (`created_by`), else `403 PERMISSION_DENIED` with a detail pointing to importing their own document as a new version (`POST .../rule-sets/{name}/versions`); `authoring.cockpit-drafts` on, else `403 DRAFT_AUTHORING_DISABLED`; the import checks; `lockVersion` matches (`If-Match`, required); `Idempotency-Key` required | `DRAFT` | Canonical JSON and hash, source document, format and hash, hit policy, `effective_from` and the `rle_rule` rows replaced in one transaction; `lock_version + 1`; version number, author and `created_at` kept; history row `DRAFT_UPDATED` with actor, the `lockVersion` after and the canonical hash before and after (migration `V6`); emit `rules.version.draft-updated.v1`. What is approved is still the canonical hash frozen at `requestPublish` |
| `DRAFT` | `requestPublish(effectiveFrom)` | Zero validation errors; `effectiveFrom` absent (means at approval) or not in the past; valid `Idempotency-Key` | `PENDING_APPROVAL` | Freeze snapshot and effective date; record `requested_by`, `requested_at`; history row `REQUESTED`; emit `rules.version.publish-requested.v1` |
| `PENDING_APPROVAL` | `approve(comment)` | The approver is no maker of the version: not `created_by`, not `requested_by`; only the author may replace a draft, so there is no other editor (PD-096, `TR-SEC-04`), else `403 SELF_APPROVAL_FORBIDDEN`. A version with no recorded author (written before `V2`) cannot be approved by anyone, `409 AUTHOR_UNKNOWN`, checked right after four eyes; no author is ever assigned, and the change is imported again (PD-105, `RLE-054`). The Rule Engine always applies `makers`, whatever `lifecycle.approver-must-differ-from` says: its constraint `rle_version_four_eyes` (`V2`, replaced by `V12` without any exemption, #299) refuses an approval by the author in the database, and in the Rule Engine `requester` mode would only let the author approve their own content once someone else requested it, which is the case four eyes exists to stop. Its `/api/bpm/meta` setting therefore reports `makers`, and a host that sets `requester` for the Process Engine gets one WARN saying the Rule Engine keeps `makers` (architecture decision, #273); `rules:approve`; `lockVersion` given (`If-Match`, required in REST and the Java API, else `428 PRECONDITION_REQUIRED`) and matching, else `409 VERSION_CONFLICT`: one version number can carry other content over time (replace in place, withdraw, request again), so the lock version is what ties the approval to the content reviewed (#234 security review, #275); every warning listed in `lifecycle.acknowledge-warnings` that needs an acknowledgement is named in `acknowledgedWarnings`, else `409 WARNINGS_NOT_ACKNOWLEDGED` (PD-104, section 3.3.5) | `PUBLISHED` | Set `approved_at`, `approved_by`, `approval_comment`, and `effective_from = approved_at` when it was absent; history row `APPROVED` with `acknowledged_warnings` and `masked_by_default_inputs` (section 3.3.5); emit `rules.version.published.v1`; increment `cache_epoch`; `pg_notify` after commit. If `effective_from <= now`, the versions it supersedes are retired in the same transaction (row below) |
| `PENDING_APPROVAL` | `reject(reason)` | `rules:approve`; caller is not `requested_by` (`403 SELF_APPROVAL_FORBIDDEN`, detail pointing to `:withdraw`; PD-089); `reason` not blank (at most 2,000 characters, else `400 INPUT_INVALID`); `lockVersion` given (`If-Match`, required, else `428 PRECONDITION_REQUIRED`) and matching | `DRAFT` | Clear `requested_by`, `requested_at`; history row `REJECTED` with actor and reason; emit `rules.version.rejected.v1` |
| `PENDING_APPROVAL` | `withdraw(reason?)` (`POST .../versions/{v}:withdraw`, PD-089) | `rules:author`; caller is `requested_by`, anyone else, an approver included, gets `403 PERMISSION_DENIED`; `reason` optional (at most 2,000 characters); `lockVersion` matches (`If-Match`); `Idempotency-Key` | `DRAFT` | Clear `requested_by`, `requested_at`; history row `WITHDRAWN` with actor and the reason if given; emit `rules.version.withdrawn.v1`; the effective version is unchanged and nothing is published |
| `PUBLISHED` | Superseded: a newer `PUBLISHED` version of the rule set has `effective_from <= now` | Conditional update, exactly once across replicas (section 3.3.2) | `RETIRED` | `retired_at = now`; history row `RETIRED`, actor `system:supersession`, reason `SUPERSEDED`; emit `rules.version.retired.v1` with `supersededBy`; keep readable forever for explain and replay |
| `PUBLISHED` | `retire(reason)` with `lifecycle.retire-approval: single` (default, M1) | `rules:approve`; `reason` not blank (at most 2,000 characters); `lockVersion` matches. One identity is enough (ADR-0025, PD-080); the PD-012 emergency path governs publication and does not apply. If the version is the last in effect (section 3.3.4), `lifecycle.retire-last-version` applies (PD-101): `confirm` (default) needs `confirmNoVersionInEffect: true` in the body, else `409 LAST_VERSION_IN_EFFECT`; `four-eyes` turns the call into the request of the `four-eyes` row below; `refuse` always answers `409 LAST_VERSION_IN_EFFECT` | `RETIRED` | `retired_at = now`; history row `RETIRED` with actor, reason, `approval_mode = single` and `no_version_in_effect`; emit `rules.version.retired.v1`; increment `cache_epoch`; `pg_notify` after commit. Older `PUBLISHED` versions with `effective_from <= now` are retired in the same transaction as `SUPERSEDED`, so a retire never brings an older version back Built in #28: a version that a newer version in effect already supersedes is `409 INVALID_STATE`, naming that version, before anything is written; the versions it had superseded retire first, as `SUPERSEDED`, so none comes back; every evaluation selects in the database, so every replica stops serving it at commit. |
| `PUBLISHED` | `retire(reason)` with `lifecycle.retire-approval: four-eyes` (M3), or the last version in effect with `lifecycle.retire-last-version: four-eyes` | As above, including the last-version rule; no pending request exists (`409 INVALID_STATE` otherwise) | `PUBLISHED`, retirement pending | `retire_requested_by`, `retire_requested_at`, `retire_reason` set; history row `RETIRE_REQUESTED`; emit `rules.version.retire-requested.v1`. The version keeps serving; selection does not read the request |
| `PUBLISHED`, retirement pending | `confirmRetire()` | `rules:approve`; caller is not `retire_requested_by` (`403 SELF_APPROVAL_FORBIDDEN`); `lockVersion` matches; the last-version rule is checked again, so a confirmation that would now leave no version in effect needs `confirmNoVersionInEffect: true` unless the request carried it | `RETIRED` | Same effects as the `single` row, with actor the confirmer, the requester and reason from the request, and `approval_mode = four-eyes` |
| `PUBLISHED`, retirement pending | `cancelRetire(reason)` | `rules:approve`; reason not blank | `PUBLISHED` | Request columns cleared; history row `RETIRE_CANCELLED` |

Event types follow `<module>.<aggregate>.<pastTense>.v<N>`, with module `rules` for this engine and `processes` for the Process Engine. The Rule Engine emits exactly these types: `rules.version.created.v1` (a draft was imported), `rules.version.draft-updated.v1` (a draft was replaced in place, section 4.2), `rules.version.publish-requested.v1`, `rules.version.published.v1`, `rules.version.rejected.v1`, `rules.version.withdrawn.v1`, `rules.version.retire-requested.v1` (from M3, `four-eyes` mode), `rules.version.retired.v1` (with `approvalMode`: `single`, `four-eyes` or `system`), and `rules.decision.evaluated.v1` (off by default, `events.decision-evaluated`). A breaking payload change publishes a `.v2` type alongside `.v1`; additive fields stay in `.v1`.

| Event | Payload beyond the envelope (`eventId`, `eventType`, `occurredAt`, `tenantId`, `correlationId`, `causationId`, `producer`, `schemaVersion`) |
|---|---|
| `rules.version.created.v1` | `ruleSet`, `version`, `actor` (the author); schema `rule-engine/api/schema/rules.version.created.v1.schema.json` |
| `rules.version.draft-updated.v1` | `ruleSet`, `version`, `canonicalHash` (after the replacement), `previousCanonicalHash`, `lockVersion` (after the update), `updatedBy`; schema `rule-engine/api/schema/rules.version.draft-updated.v1.schema.json` |
| `rules.version.publish-requested.v1` | `ruleSet`, `version`, `canonicalHash`, `requestedBy`, `effectiveFrom` (null when the version takes effect at approval); schema `rule-engine/api/schema/rules.version.publish-requested.v1.schema.json` |
| `rules.version.published.v1` | `ruleSet`, `version`, `canonicalHash`, `effectiveFrom`, `requestedBy`, `approvedBy`, `acknowledgedWarnings` (list of `{ code, input }`, empty when none) and `maskedByDefaultInputs` (inputs masked only by the tenant default at approval, empty when none) (PD-104, additive; both lists on every publication); schema `rule-engine/api/schema/rules.version.published.v1.schema.json` |
| `rules.version.rejected.v1` | `ruleSet`, `version`, `rejectedBy`, `reason`; schema `rule-engine/api/schema/rules.version.rejected.v1.schema.json` |
| `rules.version.withdrawn.v1` | `ruleSet`, `version`, `withdrawnBy`, `reason` (null when none was given); schema `rule-engine/api/schema/rules.version.withdrawn.v1.schema.json` |
| `rules.version.retire-requested.v1` (M3, `four-eyes` mode) | `ruleSet`, `version`, `requestedBy`, `reason`; the request instant is the envelope's `occurredAt` |
| `rules.version.retired.v1` | `ruleSet`, `version`, `retiredBy` (`lifecycle.system-actor`, `system:supersession` by default, when superseded), `reason` (`SUPERSEDED` when superseded), `supersededBy` (version or null), `retiredAt`, `noVersionInEffect` (PD-101, section 3.3.4), `approvalMode` (`single`, `four-eyes` or `system`), `requestedBy` (the four-eyes requester, else null); schema `rule-engine/api/schema/rules.version.retired.v1.schema.json` |
| `rules.decision.evaluated.v1` | `decisionId`, `ruleSet`, `version`, `outcome` (`MATCHED`, `NO_MATCH` or `FAILED`); no inputs or outputs, which stay in the decision log; schema `rule-engine/api/schema/rules.decision.evaluated.v1.schema.json` |

Envelope (0.2.0, #241). Every outbox payload carries the envelope as members next to the fields above, so the 0.1.0 fields (`eventType`, `tenantId`, `occurredAt` and, on lifecycle events, `actor`) keep their place and the additions stay within `.v1`:

| Member | Value |
|---|---|
| `eventId` | The outbox row id (`rle_decision_outbox.id`), a ULID string. Consumers de-duplicate by it |
| `eventType` | The type with its `.v<N>` suffix |
| `occurredAt` | When the state change committed, ISO 8601 UTC |
| `tenantId` | The tenant from the host security context, never from a request body |
| `correlationId` | The caller's: the `X-Correlation-Id` header on REST (a missing or malformed header is replaced by a generated id, as on `:evaluate`), or `ImportRequest.withCorrelationId` and `WriteOptions.withCorrelationId` on the Java API, where a malformed id is `400 INPUT_INVALID`. When a Java caller passes none, the event id: the event starts its own chain. It is not part of the idempotent request, so a replay with another correlation id returns the original result and writes no event |
| `causationId` | The id of the command or event that caused this one, or null. A caller's command has no id of its own, so it is null on every event the engine writes in 0.2.0 |
| `producer` | `rule-engine/<engine version>`, for example `rule-engine/0.2.0` |
| `schemaVersion` | `1`, the version of the envelope layout |

Every type has a schema file in `rule-engine/api/schema/`, named `<eventType>.schema.json`, with `additionalProperties: true`. Core tests check every payload the engine writes against its file, and each file's example against the file itself, so the types whose emitting features come later are checked too.

A host that wants tamper evidence for approvals relays these events to a store outside the database administrator's control (an append-only log or a separate audit system) and reconciles the stored versions against it, because rows in the host database, outbox included, can be changed by whoever administers it. The publication events carry `canonicalHash`, `effectiveFrom`, `requestedBy` and `approvedBy` for that purpose.

Optimistic locking uses `lockVersion` (column `lock_version`), sent as `If-Match: "<lockVersion>"` or a `lockVersion` body field and returned as `ETag: "<lockVersion>"`. It is unrelated to `version`, the rule set version number.

Approval, cache invalidation after commit, and the epoch fallback when a notification is lost:

![Approve and invalidate caches](images/publish-approval.svg)

Source: [`diagrams/publish-approval.mmd`](diagrams/publish-approval.mmd).

#### 3.3.1 Effective version at an instant (ADR-0025)

The effective version of a rule set at instant `T` (the decision's clock) is the version with the highest `version_num` among those that were approved (`approved_at IS NOT NULL`; approval fills an absent `effective_from` with the approval instant), have `effective_from <= T`, and were not retired at `T` (`retired_at IS NULL OR retired_at > T`). The status column is not read. So:

- A newer version takes over at its effective date on every replica whether or not the superseded version's status has changed yet (`RLE-008`, `RLE-027`).
- A version retired by hand stops being selected from `retired_at` on, and stays selected for any clock before it: evaluating with `clock = 2027-02-01T09:00:00Z` (`rules:simulate`) selects `tet-fee-waiver` v1 retired on 2027-02-17, because it was effective then (`RLE-026`). A retire cannot be back-dated; `retired_at` is the transaction's clock.
- Replay never selects: it uses the logged version number (`TR-DET-05`), so a retired version always replays.

The lookup reads `idx_rle_version_effective`; a rule set has tens of versions, so the scan is a few index entries.

#### 3.3.2 Automatic retirement of superseded versions (ADR-0025, `TR-OPS-05`)

Status `RETIRED` and its event are bookkeeping; selection does not depend on them. Two paths write them, both in an ordinary transaction with no advisory lock and no session state:

1. **At approval.** When the approved version's `effective_from` is not after the approval instant, the approve transaction also runs the statement below for its rule set.
2. **Lifecycle sweep.** The starter's scheduler thread runs the statement every `lifecycle.sweep-interval` (default `PT1M`) across tenants (an engine-internal sweep, like partition maintenance), with the engine `Clock` as `:now`:

```sql
UPDATE rle_version old
   SET status = 'RETIRED', retired_at = :now, lock_version = old.lock_version + 1
  FROM (SELECT o.tenant_id, o.id, s.name, max(n.version_num) AS superseded_by
          FROM rle_version o
          JOIN rle_version n ON n.tenant_id = o.tenant_id AND n.rule_set_id = o.rule_set_id
          JOIN rle_rule_set s ON s.tenant_id = o.tenant_id AND s.id = o.rule_set_id
         WHERE o.status = 'PUBLISHED' AND n.status = 'PUBLISHED'
           AND n.version_num > o.version_num AND n.effective_from <= :now
           AND (:tenant IS NULL OR o.tenant_id = :tenant) AND (:ruleSet IS NULL OR o.rule_set_id = :ruleSet)
         GROUP BY o.tenant_id, o.id, s.name) newer
 WHERE old.tenant_id = newer.tenant_id AND old.id = newer.id AND old.status = 'PUBLISHED'
RETURNING old.tenant_id, old.id, newer.name, old.version_num, old.lock_version, newer.superseded_by;
```

`superseded_by` is the highest newer version in effect, the one in force, even when several newer versions are (a join without the grouping would update the row once with an arbitrary partner). The approve and retire transactions pass their tenant and rule set; the sweep passes neither.

In the same transaction, for each returned row only, the engine inserts the history row (`RETIRED`, actor `lifecycle.system-actor`, reason `SUPERSEDED`, `superseded_by`, `approval_mode = system`) and emits `rules.version.retired.v1` with `supersededBy`; the `cache_epoch` increment arrives with `RLE-010`. Exactly once across replicas: under `READ COMMITTED`, a second replica's `UPDATE` waits on the first one's row lock, re-checks `old.status = 'PUBLISHED'` on the committed row, finds `RETIRED` and returns nothing, so it writes no history row and no event. Retirement at evaluation time was rejected: evaluation is a read plus one insert in the host's transaction, and a status write there would put a row lock on the hottest path. The status may lag the effective date by up to one sweep interval; no decision is affected.

![Retirement by supersession on two replicas, and by hand](images/retire-and-supersede.svg)

Source: [`diagrams/retire-and-supersede.mmd`](diagrams/retire-and-supersede.mmd). The second replica's statement waits on the row lock and returns nothing; a retire by hand runs the supersession of its rule set first, so no older version comes back.

#### 3.3.3 Lifecycle history

`rle_version` holds the current state; every transition also appends one row to `rle_version_history` (section 5, migration `V3`, #147) in the transition's own transaction: `CREATED`, `REQUESTED`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `RETIRED`, and `DRAFT_UPDATED` for each replacement of a draft's document (migration `V6`, with `canonical_hash` and `previous_canonical_hash`), with actor, reason or comment, the status before and after, and the `lockVersion` after; a `RETIRED` row also records `approval_mode` (`single` by hand, `system` when superseded), `no_version_in_effect` (PD-101) and, when superseded, `superseded_by`. A version rejected twice and approved on the third request keeps all three requests, both rejection reasons and the approval comment; `approval_comment` on `rle_version` is only the latest approval's copy. `GET /rule-sets/{name}/versions/{v}/history` returns the rows oldest first (`rules:read`). Migration `V3` creates the table and backfills one `CREATED` row per version that existed before it (actor `created_by`, or `system` when it was not recorded); their later transitions were not recorded and are not invented. Migration `V4` adds triggers that refuse `UPDATE`, `DELETE` and `TRUNCATE` on the table (SQLSTATE `55000`), so the trail is append-only in the database, not only by convention; hosts that separate migration and runtime roles also revoke those rights from the runtime role. During a rolling upgrade to the release with `V3`, replicas still on the old release write no history rows, so request and approval transitions made in that window are missing for good; drain lifecycle commands until every replica runs the new release if the gap matters.

#### 3.3.4 Retiring the last version in effect (PD-101, `RLE-026`)

A version is the last in effect when, at the retirement instant, the version selected by section 3.3.1 is this one or there is none. Lower-numbered versions it supersedes are retired in the same transaction, before it, so they do not count; a version approved with a future `effective_from` does not count either, because evaluations fail until it starts. The problem detail then names it and its start.

- **Setting.** The host property is `horus.bpm.lifecycle.retire-last-version`, shared with the Process Engine and published as `lifecycle.retire-last-version` in `/api/bpm/meta` (section 4.4). Its values are `confirm` (default), `four-eyes` and `refuse`.
- **Refusal.** The refusal is `409 LAST_VERSION_IN_EFFECT`. Its problem body carries:
  - `ruleSet` and `version`;
  - `setting`, the value in force;
  - `nextEffectiveFrom`, the start of the next approved version, or null;
  - a `detail` such as "No version of card-fee will be in effect. Evaluations will fail until a new version is approved."
  - The cockpit shows the same sentence in the retire dialog before the click.
- **Who must confirm.** The flag is needed on whichever call makes the version retire while it is the last in effect:
  - the `single` retire;
  - in `four-eyes` mode, the request if the version was last when it was made;
  - otherwise the confirmation, if the version became last in the meantime.
  - When it is not the last, the flag is ignored and not stored.
- **Concurrency.** The retire transaction locks the `rle_rule_set` row first with `SELECT ... FOR UPDATE`. It is the same row whose `cache_epoch` the transaction increments anyway. The transaction then reads the versions in effect, so two concurrent retirements of the last two versions run one after the other, and the second one sees that it is now the last. The lock is held for two indexed reads and one update, with no network call.
- **Recorded.** The `RETIRED` history row stores `no_version_in_effect = true`, and `rules.version.retired.v1` carries `noVersionInEffect: true`. Every other manual retirement stores `false`. Supersession never leaves a gap, so it stores `false`.
- **`four-eyes` is not available yet.** That value of either retirement setting needs four-eyes retirement (RE-F-49, M3, may move to 1.1). Until that ships in the Rule Engine, a host that runs it and sets `four-eyes` fails at start-up with a message naming the setting, so a control that would do nothing is never set silently. The shared reader in bpm-host-metadata accepts the value, because the Process Engine supports it; the Rule Engine's settings refuse it.

#### 3.3.5 Acknowledging warnings at approval (PD-104, `RLE-053`)

An approval of a version that carries a warning listed in `lifecycle.acknowledge-warnings` needs the approver to accept it on the record.

- **Which warnings need it.** The engine computes the version's warnings again at approval, with the personal-data names and the masking default in force then (section 4.4).
  - An `UNMASKED_PERSONAL_DATA` warning needs an acknowledgement only when its input would be stored unmasked under that default.
  - A warning on an input that is masked only by the tenant's `decision-log.mask-default` is shown, but needs none, because its values never reach the log in plain text.
  - Under strict mode (`decision-log.require-explicit-mask`) no such warning exists: the version is refused with `422` instead (PD-099).
- **The request.** The approve body takes `"acknowledgedWarnings": [{ "code": "UNMASKED_PERSONAL_DATA", "input": "nationalId" }]`: the exact pairs the approver was shown (security review of #316).
  - **Pairs, not codes.** `If-Match` freezes the content, but configuration can still change between screen and click: the host's or tenant's personal-data names, or the masking default. With codes alone, one acknowledged code would silently cover an input the approver never saw.
  - **Exact match.** The engine recomputes the set of pairs that need an acknowledgement and accepts the approval only when the sent set equals it, compared as sets: order and duplicates do not matter.
    - A missing pair is refused. So is an extra pair that no longer needs an acknowledgement, for example because a name was removed from the list.
    - The refusal returns the current set, so the client shows it and the approver accepts again.
  - **Validation.** At most 100 pairs. A code that is not a known warning code, an empty input name, or an input name over 100 characters is `400 INPUT_INVALID`.
  - **Recorded.** Exactly the sent set, which is also the recomputed set, is recorded.
  - **Idempotency.** The sorted pairs are part of the idempotency request hash.
- **Order of checks**, so a refusal writes nothing and names the most useful problem first:
  1. permission;
  2. `If-Match` present (`428`);
  3. status `PENDING_APPROVAL` (`409 INVALID_STATE`);
  4. four eyes (`403`);
  5. lock version (`409 VERSION_CONFLICT`), so an approver looking at stale content reloads before anything else is judged;
  6. strict mode again (`422`, security review of #308);
  7. the acknowledgement: sent set equals recomputed set.
- **The refusal.** `409 WARNINGS_NOT_ACKNOWLEDGED`. Its body carries `warnings`, the full current set needing an acknowledgement as `{ code, input, matchedName }` (empty when the sent pairs were extra but nothing is needed now), and `setting`, the codes the host requires. The problem body carries `warnings`, the unacknowledged `{ code, input, matchedName }` entries, and `setting`, the codes the host requires. The client shows them again and the approver sends the request with the codes. The client never retries on its own.
- **Recorded.** The `APPROVED` history row stores two JSON arrays, and `rules.version.published.v1` carries the same values:
  - `acknowledged_warnings` (`acknowledgedWarnings` in the event): the `{ code, input }` entries the approver accepted;
  - `masked_by_default_inputs` (`maskedByDefaultInputs`): every input that declares no `mask` and is masked only by the tenant's `decision-log.mask-default` at approval. A warned input in this list needed no acknowledgement. The list also shows which inputs would reach the log in plain text if the default were later turned off.
  - Both are empty when they do not apply, so an auditor can tell "nothing to accept", "accepted" and "masked by the default" apart, and can see why no acknowledgement was asked (PD-104 amendment).
  - If the tenant later turns the default off, that change is the moment the risk is accepted. It is logged at start-up with the old and new value (`rle_setting_seen`, PD-099 mitigation 4) and needs no new approval of each version.
  - **That WARN names what is affected** (security review of #316). When `decision-log.mask-default` changes from `true` to `false` for a tenant, the start-up line is followed by one WARN listing that tenant's `PUBLISHED` versions whose latest `APPROVED` history row has a non-empty `masked_by_default_inputs`, as `ruleSet vN [inputs]`. That is the inputs whose values reach the log in plain text from now on. It is capped at 20 entries, with "and N more" after that. It is one read-only query per changed tenant, at start-up, outside any request.
- **Marked in the version (#318).** Every warning in a version response carries `requiresAcknowledgement`. One method computes it for the version view and the approve check alike, from the same inputs: the code is listed in `lifecycle.acknowledge-warnings` and the input is not masked only by the tenant's default. So the flag and the check cannot disagree, also when a tenant's default or names change or a new warning code is added. A replay recorded before the field existed reads it as `false`.
- **Cockpit.** The approval screen lists the warnings with one checkbox, worded "I accept that these inputs are logged without masking: <inputs>". Approve stays unavailable until it is ticked. The box covers exactly the warnings the engine marks `requiresAcknowledgement`; the cockpit does not read `lifecycle.acknowledge-warnings` or `decision-log.mask-default` from `/meta` for this. After a `409 WARNINGS_NOT_ACKNOWLEDGED` it covers the set the refusal returned. This supersedes the earlier rule in #312 that Approve stays enabled beside a warning. The engine is the authority, so a direct API call gets the same refusal.

### 3.4 Money contract (canonical)

This section is the single statement of how money is represented; the Process Engine and the cockpit use the same contract.

| Surface | Unit | Example | Rule |
|---|---|---|---|
| Java factories | Named in the method | `Money.ofMinor("VND", 5000000)`, `Money.ofMinor("USD", 1250)`, `Money.ofMajor("USD", "12.50")` | `ofMinor(String currency, long minorUnits)`; `ofMajor(String currency, String amount)` takes a decimal string, never a `double`. `ofMajor` rejects more fraction digits than the currency scale (`"12.505"` USD, `"10.5"` VND) with `IllegalArgumentException`; construction never rounds. There is no constructor without a unit in its name |
| JSON (REST, events, decision log, canonical definition JSON) | Minor units | `{ "currency": "USD", "amount": 1250 }` is 12.50 USD; `{ "currency": "VND", "amount": 5000000 }` is 5 000 000 VND | Always the object `{currency, amount}`. `currency` is an ISO 4217 alphabetic code; `amount` is a JSON integer in minor units within plus or minus 2^53 - 1 so JavaScript clients read it exactly. A float, a string amount or a value outside that range is `400 INPUT_INVALID` |
| Expression literals (`when`, `then`, gateway conditions) | Major units with the currency scale | `12.50 USD` (1 250 minor), `5000000 VND` (5 000 000 minor, VND has scale 0) | More fraction digits than the currency scale (`12.505 USD`, `10.5 VND`) is a publish-time validation error. The literal is converted to minor units at compile time |
| Currency scale | ISO 4217 minor unit | USD 2, VND 0, JPY 0, KWD 3 | From `java.util.Currency#getDefaultFractionDigits`; a currency without a defined minor unit (for example `XAU`) is rejected unless the host registers a scale |
| Arithmetic | Exact in minor units | `money + money`, `money - money` (same currency), `money * number`, `money / number` | Mixed currencies are `EVALUATION_ERROR` at run time and a validation error when both sides are literals (`TR-DET-03`). `money / money` is rejected at publish time. Results of `*` and `/` round to the currency scale with the rule set's `moneyRounding`, default `HALF_EVEN`; the mode is part of the versioned definition, so replay is deterministic |

### 3.5 Expression language SPI and value mapping (M1, DN-05)

The parser and evaluator are reached through one interface, in `io.github.dhluong90.bpm.expr.api` of the `bpm-expression` library that both engines depend on (ADR-0027), so a second language (FEEL, ADR-0019) registers beside the first and the Process Engine never depends on parser classes:

```java
public interface ExpressionLanguage {
  String id();                                                     // "urn:horus-bpm:expr:v1"; FEEL from S1
  CompiledExpression compile(String source, TypeEnvironment types); // throws ExpressionInvalidException with position
}
public interface CompiledExpression {
  ValueType resultType();
  EvaluationProfile profile();                                     // COMPILED in M1; GENERAL for full FEEL
  Object evaluate(EvaluationContext context);                      // clock, zone, slots, functions, step budget
}
```

Java value mapping in `api` (ADR-0016), fixed now so FEEL types are additive later:

| Type | Java | From |
|---|---|---|
| `string`, `boolean` | `String`, `Boolean` | M1 |
| `integer`, `decimal` (FEEL number) | `Long` or `BigDecimal` on input; always `BigDecimal` on output | M1 |
| `date`, `datetime` | `LocalDate`, `Instant` | M1 |
| `money` | `Money` | M1 |
| `list<T>` | `List<T>` (immutable) | M1 |
| FEEL `time`, zoned `date and time` | `LocalTime` or `OffsetTime`, `OffsetDateTime` or `ZonedDateTime` | S1 |
| FEEL days and time duration, years and months duration | `Duration`, `Period` | S1 |
| FEEL context | `Map<String, Object>` (immutable, insertion order) | S2 |
| FEEL range | `Range` record | S2 |

## 4. Java API and REST contract

### 4.1 Java API (`io.github.dhluong90.bpm.rules.api`, ADR-0022)
```java
public interface RuleEngine {
  DecisionResult evaluate(String ruleSet, DecisionInput input);
  DecisionResult evaluate(DecisionRequest request); // clock, version (pin), idempotencyKey, correlationId
  DecisionResult replay(String decisionId);         // the logged version, inputs and clock; writes nothing (#22)
  <T> T evaluateAs(String ruleSet, DecisionInput input, Class<T> outputType); // throws instead of returning Failed
  SimulationResult simulate(String ruleSet, int version, List<DecisionInput> samples); // rules:simulate; logs nothing (RLE-016)
}

// package io.github.dhluong90.bpm.rules.embedded: engine to engine, not for hosts
public interface TenantScopedRuleEngine { // for an embedded engine's own stored work (ADR-0027)
  Set<String> REGISTERED_ENGINE_IDS = Set.of("system:process-engine");
  String engineId();                                              // fixed when built
  DecisionResult evaluate(String tenantId, DecisionRequest request);
}
```
`TenantScopedRuleEngine` is the tenant-scoped entry point of ADR-0027. It is not part of the host API: it lives in the engine-to-engine package `rules.embedded`, the `RuleEngine` bean does not implement it, and it is never registered as a bean. The only route to it is `EmbeddedEngineGrant.claim(engineId, claimant)` (#336). The grant hands out one entry point per registered engine identity (today only `system:process-engine`), and only to the claimant class registered for that identity: the Process Engine's package-private `RuleEngineDecisionPort`, compared by class identity (the class the thread's context class loader resolves for that name, falling back to the grant's own loader, so a split-loader layout starts). The claimant's package must also be sealed and its jar's `Implementation-Version` must equal rule-engine-core's; both core jars carry it. It refuses any other id (`system:timer` and `system:dev-auto-approve` included), any other claimant (`SecurityException`), a second claim, and a claim after `closeUnclaimed()` (`IllegalStateException`). So the identity written to `rle_decision` is fixed when the object is built and cannot be chosen per call. `RuleEngines` is not a bean and carries no grant: `RuleEngines.openEmbeddedEngineGrant()`, called by the starter, opens at most one per Rule Engine through the package-private `EmbeddedEngineGrants.open`. The starter keeps `RuleEngines` in a bean of a package-private type. It registers the grant only when the decision port class resolves, sealed and of the Rule Engine's version (otherwise it logs why, and the host runs without the grant), and closes unclaimed identities once all singletons exist. The process starter's decision port claims at start-up, and the process starter fails start-up if it did not. The packages `rules.internal.service`, `rules.embedded` and `process.internal.service` are sealed in their jars (`Sealed: true` manifest sections), so a host class in a split package fails to load, on a class path and in a Spring Boot executable jar. Out of scope: reflection. A same-named class from a class directory, an unsealed jar or another version gets no grant, so running the engines from class directories (an IDE) leaves decision steps with no caller failing with `TENANT_NOT_ALLOWED`. The Process Engine uses it for a decision step that has no caller, such as a step after a timer: the tenant is the one it read from its locked instance row. A blank tenant is refused. No permission is checked, because the calling engine authorises its own step; there is no REST endpoint for it, and a host never passes a tenant taken from a request. Input limits, idempotency and the decision log are those of `RuleEngine.evaluate`, and the decision row is written in the caller's transaction. ArchUnit keeps `rules.api` and `rules.spi` free of `rules.embedded`. Only the grant constructs the entry point, only `EmbeddedEngineGrants` constructs a grant and only `RuleEngines` calls it, neither has a public implementation, and the grant is the only core class allowed one uninitialised class lookup. In the Process Engine, only the decision port claims or holds the entry point (`TR-TEN-01`). It refuses a request that pins a version or chooses a clock: those need `rules:pin` or `rules:simulate`, which an engine identity does not hold.

A pinned evaluation is `evaluate(DecisionRequest.of(ruleSet, input).withVersion(v))`, which replaces the earlier `evaluate(String, int, DecisionInput)` overload. Its permission is `rules:pin`, not `rules:evaluate`: a pin bypasses the selection of the four-eyes-approved version in force, so no role holds it by default and a host grants it only to a service identity, such as the account its process steps run under. A `RETIRED` version is refused (`INVALID_STATE`) unless `horus.bpm.rules.evaluation.pin-retired` is `true`, so a rule taken out of service produces no new decisions; replay is not affected. A decision at a chosen clock is marked back-dated: `DecisionResult.clockChosen()` and the response's `clockChosen` are true, from `eval_clock` differing from `created_at` on the row. `DecisionRequest.clock` (needs `rules:simulate`, else `CLOCK_NOT_ALLOWED`) selects the version in force (section 3.3.1) and feeds `today()` and `now()`. A pinned version must be `PUBLISHED` (or `RETIRED` with `evaluation.pin-retired`); a `DRAFT` or `PENDING_APPROVAL` version is `INVALID_STATE` and is simulated instead, so an unapproved rule never enters the decision log (`RLE-015`, `RLE-016`). The decision row stores the clock in `eval_clock` and the run instant in `created_at`; `DecisionResult` returns both, as `evalClock` and `evaluatedAt`, and the decision-log reads (`LoggedDecision` of `GET /decisions/{id}` and the masked-input search) return the same `evalClock`, `clockChosen` and `pinned`, so a back-dated or pinned decision never looks normal when the log is read. `replay` needs `rules:log:read`, reads the row in the caller's tenant (`DECISION_NOT_FOUND` otherwise), loads the logged version by number and evaluates the logged inputs at the logged clock. It refuses with `REPLAY_NOT_POSSIBLE` when a masked input had a value, because the log holds only `masked`. The row is read by id within the millisecond its ULID starts with, which is the partition key's millisecond, and only if the engine clock went back within the process is a second, tenant-wide read needed. An idempotent replay of an evaluation restores the declared `date` and `datetime` output types from the logged version, because the stored JSON holds them as text (`RLE-008`, `RLE-022`).

![Replay a logged decision](images/replay-decision.svg)

Source: [`diagrams/replay-decision.mmd`](diagrams/replay-decision.mmd). Replay is a read: it never selects, so a version superseded or retired since the decision still replays, and it adds no decision row.
`DecisionRequest` carries an optional `decision` (the id of a decision inside the decision model) in its builder before the 1.0 freeze; in M1 it may be absent or equal to the rule set name, anything else is `400 INPUT_INVALID`; from S1 it selects a decision of a DRD (DN-08). `DecisionResult.outcome()` is a sealed type (`Matched`, `NoMatch`, `Failed`). `UNIQUE_HIT_VIOLATION` and `EVALUATION_ERROR` are returned as `Failed` with a `code()`, because they are logged decisions; `evaluateAs` throws `DecisionFailedException` with the same code (ADR-0016). Outbound SPIs, each with an engine default that is `@ConditionalOnMissingBean` in the starter (ADR-0024, `TR-EXT-02`): `TenantResolver` (default: the `tenancy.claim` attribute of the authenticated principal, session or token; never anything the browser sends, PD-079), `CallerResolver`, `MaskingPolicy` (default: the `masking.*` and `decision-log.*` masking settings of section 4.4; returns the tenant's `MaskingKey`, masking default, strict mode and personal-data names), `RuleFunctionRegistry`, `RuleEventPublisher`, `CurrencyRegistry` (default: ISO 4217 scales from the JDK; a host adds or overrides a currency's scale, for example `XAU`), `DecisionExplainer` (default: the English template of section 4.7) and `LifecycleGuard` (default: allows everything; a host guard may veto a transition with its own code, for example to require a change ticket on retire, and can never make a refused transition succeed, `TR-EXT-06`). Time comes from the host's `java.time.Clock` bean when it has exactly one, otherwise `Clock.systemUTC()`; the starter never registers a `Clock` bean. That clock is the default of `DecisionRequest.clock` and drives partition creation and the health check (`TR-DET-01`).

Permissions are checked in `VersionService` and `RuleEngine` through `CallerResolver`, not only over REST: `rules:author` for import and request-publish, `rules:approve` for approve, `rules:read` for reads, `rules:evaluate` for evaluation, `rules:pin` for an evaluation pinned to a version; the approver must be neither the requester nor the author (`created_by`). Until the shared permission seam (#133) lands, the starter's defaults follow the host contract: `TenantResolver` asks the host's `io.github.dhluong90.bpm.host.spi.TenantResolver`, with the same tenant format and token-claim checks as `/api/bpm/me`, and `CallerResolver` grants `rules:*` authorities plus `horus.bpm.rules.security.role-permissions`, so the services allow exactly what `/api/bpm/me` lists. The seam replaces both defaults without touching the services.

### 4.2 REST endpoints (`/api/bpm/rules/v1`)

Permissions follow ADR-0010: `rules:evaluate`, `rules:read`, `rules:author`, `rules:approve`, `rules:simulate`, `rules:log:read`, `rules:pin`, `rules:masked:search` (the masked-input search only; `rules:log:read` does not imply it and no role holds it unless the host grants it). Permission names keep to the published `/api/bpm/me` pattern `^(rules|processes)(:[a-z]+){1,2}$`, unchanged: a 0.1.0 cockpit federated with a newer host validates `/me` against it and would reject the whole response, so a name such as `rules:log:search-masked` is not allowed (architect ruling on #255). `rules:pin` (0.2.0, security review of #261) evaluates one named approved version. No role holds it by default, and no example grants it until a process step pins a version (security review of #261), so a user who may evaluate cannot choose an older version over the one approved for today. A pinned decision is marked on its row (`pinned`, migration `V10`), as a decision at a chosen clock is (`eval_clock` differs from `created_at`); the evaluation result, replay and the decision-log reads return both as `pinned` and `clockChosen`, beside `evalClock`. One permission per endpoint, checked with `@PreAuthorize`; the host maps the strings to its roles.

| Endpoint | Permission | Idempotency and concurrency | Error codes |
|---|---|---|---|
| `POST /decisions/{ruleSet}:evaluate` | `rules:evaluate`; a body `clock` also needs `rules:simulate` | Optional `Idempotency-Key`; body at most 1,048,576 bytes (`RLE-044`); optional `clock` (ISO 8601 instant) selects the version in force and feeds `today()` and `now()` (`RLE-008`, `RLE-022`) | `400 INPUT_INVALID`, `403 CLOCK_NOT_ALLOWED`, `404 NO_EFFECTIVE_VERSION`, `413 PAYLOAD_TOO_LARGE`, `422 UNIQUE_HIT_VIOLATION`, `422 EVALUATION_ERROR` |
| `POST /decisions/{ruleSet}/versions/{v}:evaluate` | `rules:pin`, plus `rules:simulate` when the body sets `clock` | Optional `Idempotency-Key`. Only approved versions: a `DRAFT` is `409 INVALID_STATE` and is tried with `:simulate`; a `RETIRED` version is `409 INVALID_STATE` unless `evaluation.pin-retired` is `true`. A decision evaluated with a chosen clock carries `clockChosen: true` in the response and keeps `eval_clock` apart from `created_at` in the log, so a back-dated decision is visible | `403 PERMISSION_DENIED`, `403 CLOCK_NOT_ALLOWED`, `404 VERSION_NOT_FOUND`, `409 INVALID_STATE`, `422 UNIQUE_HIT_VIOLATION`, `422 EVALUATION_ERROR` |
| `POST /decisions/{decisionId}:replay` | `rules:log:read` | No body; writes nothing; the logged version, inputs and clock (`TR-DET-05`, section 4.1) | `404 DECISION_NOT_FOUND`, `422 REPLAY_NOT_POSSIBLE` (a masked input had a value), `422 UNIQUE_HIT_VIOLATION` and `422 EVALUATION_ERROR` when the logged decision failed |
| `POST /decisions/{ruleSet}:evaluate-batch` | `rules:evaluate` | At most 1,000 items; `Idempotency-Key`; per-item outcome in a `207`-style body with `200` status | `400 INPUT_INVALID`, `404 NO_EFFECTIVE_VERSION`; item errors `UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR` |
| `POST /rule-sets/{name}/versions/{v}:simulate` | `rules:simulate`, checked before the body is read | Body `{ "samples": [{ "inputs": {...} }] }`, 1 to `simulation.max-inputs` sets (default and ceiling 1,000), at most 1,048,576 bytes. Runs the version in any status and the version effective at the host clock in memory; writes nothing (no decision row, idempotency record or event), so no `Idempotency-Key`; no generated id in the response, so the same version, samples and clock give the same body (`RLE-016`, `TR-DET-01`). See section 4.2.1 | `400 INPUT_INVALID` (no samples, too many, or a sample invalid, named `samples[i]`), `404 VERSION_NOT_FOUND` (also for another tenant's version and an unknown rule set), `413 PAYLOAD_TOO_LARGE`, `422 VALIDATION_FAILED` |
| `GET /rule-sets`, `GET /rule-sets/{name}` | `rules:read` | Signed keyset cursor by name (byte order), `limit` 1 to `query.max-page-size` (default 50, at most 200); each item: `ruleSet`, `latestVersion`, `latestStatus`, `publishedVersion` (the version selected at the host clock, section 3.3.1, or null), `lastChangedAt` (latest version timestamp or history row) (`RLE-039`) | `400 QUERY_INVALID` (limit or cursor), `404 RULE_SET_NOT_FOUND` |
| `GET /rule-sets/{name}/versions` | `rules:read` | Keyset cursor by version number, newest first, same `limit`; items carry the lifecycle fields and `allowedActions`, never the definition (`RLE-040`) | `400 QUERY_INVALID`, `404 RULE_SET_NOT_FOUND` |
| `GET /rule-sets/{name}/versions/{v}` | `rules:read` | `ETag`; `?include=source` adds `sourceFormat`, `sourceDocument` and `sourceHash` (M1, DN-01). Every version response carries `allowedActions` for the caller (`RLE-043`) | `404 VERSION_NOT_FOUND` |
| `GET /rule-sets/{name}/versions/{v}/history` | `rules:read` | Lifecycle history rows oldest first (section 3.3.3) | `404 VERSION_NOT_FOUND` |
| `GET /rule-sets/{name}/versions/{v}/diff?base={w}` | `rules:read` | Row-level diff of two versions of one rule set (section 4.5, `RLE-028`, `RLE-048`); writes nothing | `400 INPUT_INVALID` (`base` missing or equal to `v`), `404 RULE_SET_NOT_FOUND`, `404 VERSION_NOT_FOUND` |
| `GET /rule-sets/{name}/versions/{v}/export?format=yaml\|json` | `rules:read` | Canonical document, default `yaml` (section 4.6); `ETag: "<lockVersion>"`; writes nothing | `400 INPUT_INVALID` (other `format`), `404 VERSION_NOT_FOUND` |
| `POST /rule-sets` (create the rule set and its version 1 from a document), `POST /rule-sets/{name}/versions` (next version from a document, or `?copyOf={v}` with no body), `PUT /rule-sets/{name}/versions/{v}` (replace a draft, `RLE-047`) | `rules:author`; on `PUT` also the draft's author | `Content-Type: application/yaml` or a DMN XML type; `Idempotency-Key` optional on `POST`; on `PUT` both `Idempotency-Key` (`400 INPUT_INVALID` without it) and `If-Match: "<lockVersion>"` (`428 PRECONDITION_REQUIRED` without it) are required; a refused document stores nothing (`RLE-033`) | `400 INPUT_INVALID` (document `ruleSet` differs from `{name}`), `403 PERMISSION_DENIED` (`PUT` by another author), `403 DRAFT_AUTHORING_DISABLED` (`PUT` with `authoring.cockpit-drafts` off), `409 RULE_SET_EXISTS`, `409 INVALID_STATE` (`PUT` on a non-draft), `409 VERSION_CONFLICT`, `413 PAYLOAD_TOO_LARGE`, `422 VALIDATION_FAILED` with `violations` naming row and column, `428 PRECONDITION_REQUIRED` |
| `POST /rule-sets/{name}/versions/{v}:request-publish` | `rules:author` | `Idempotency-Key`; `If-Match` | `409 VERSION_CONFLICT`, `409 INVALID_STATE`, `422 VALIDATION_FAILED` |
| `POST /rule-sets/{name}/versions/{v}:approve` | `rules:approve` | `Idempotency-Key`; `If-Match` required (the lock version reviewed); approver is not the requester; body at most 16,384 bytes (`RLE-044`), optional `comment` and `acknowledgedWarnings`, an array (at most 100) of `AcknowledgedWarning { code: enum [UNMASKED_PERSONAL_DATA], input: string 1..100 }`, both required (section 3.3.5) | `403 SELF_APPROVAL_FORBIDDEN`, `409 AUTHOR_UNKNOWN` (no recorded author, PD-105), `409 INVALID_STATE`, `409 VERSION_CONFLICT`, `409 WARNINGS_NOT_ACKNOWLEDGED`, `413 PAYLOAD_TOO_LARGE`, `428 PRECONDITION_REQUIRED` |
| `POST /rule-sets/{name}/versions/{v}:confirm-retire` (M3, `four-eyes` mode) | `rules:approve` | `Idempotency-Key`; `If-Match`; confirmer is not the requester | `403 SELF_APPROVAL_FORBIDDEN`, `409 INVALID_STATE` (no pending request, or mode `single`), `409 LAST_VERSION_IN_EFFECT` (section 3.3.4), `409 VERSION_CONFLICT` |
| `POST /rule-sets/{name}/versions/{v}:cancel-retire` (M3) | `rules:approve` | `Idempotency-Key`; `If-Match`; body `{ "reason": "..." }` | `400 INPUT_INVALID`, `409 INVALID_STATE`, `409 VERSION_CONFLICT` |
| `POST /rule-sets/{name}/versions/{v}:reject`, `:retire` | `rules:approve` | `Idempotency-Key`; `If-Match` (required on `:reject`: `428 PRECONDITION_REQUIRED` without it); body `{ "reason": "..." }` required, at most 16,384 bytes; `:retire` also takes `"confirmNoVersionInEffect": true` for the last version in effect (PD-101, section 3.3.4); the requester gets `403 SELF_APPROVAL_FORBIDDEN` and withdraws instead (PD-089, section 3.3). `:reject` ships in #147; `:retire` with its feature | `400 INPUT_INVALID` (blank or longer than `lifecycle.reason-max-length`), `409 INVALID_STATE`, `409 LAST_VERSION_IN_EFFECT` (`:retire`), `409 VERSION_CONFLICT`, `413 PAYLOAD_TOO_LARGE` |
| `POST /rule-sets/{name}/versions/{v}:withdraw` (PD-089, `RLE-046`) | `rules:author`, and the caller is the version's requester | `Idempotency-Key` and `If-Match` both required (`400 INPUT_INVALID` without the key, `428 PRECONDITION_REQUIRED` without `If-Match`); optional body `{ "reason": "..." }`, at most 16,384 bytes and `lifecycle.reason-max-length` characters; the permission is checked before the body is read | `400 INPUT_INVALID`, `403 PERMISSION_DENIED` (not the requester, an approver included), `404 VERSION_NOT_FOUND`, `409 INVALID_STATE`, `409 VERSION_CONFLICT`, `413 PAYLOAD_TOO_LARGE`, `428 PRECONDITION_REQUIRED` |
| `GET /decisions/{id}`, `GET /decisions/{id}/explain`, `GET /decisions` | `rules:log:read` | Cursor; `GET /decisions` requires a rule set and a time range of at most 31 days (ADR-0015); masked inputs stay hashed; explain is built from the row and the stored version only (section 4.7) | `400 QUERY_INVALID`, `404 DECISION_NOT_FOUND` |
| `POST /decisions:find-by-masked-input` | `rules:masked:search` | Body `{ ruleSet, input, value, from, to, limit? }`, at most 16,384 bytes; the plain `value` travels in the body so it never sits in a URL or an access log. The engine fingerprints it with the caller's tenant key (section 4.4, `masking.*`) and compares fingerprints only, `masked_inputs ->> input = 'hmac:<keyId>:<hex>'` inside tenant, rule set and period on `idx_rle_decision_lookup`; period at most 31 days, `limit` 1 to `query.max-page-size` (default 50), newest first, `truncated: true` when more match (RLE-012). The value is fingerprinted with every key the tenant holds (`MaskingPolicy.searchKeys`), so rows written before a key rotation are found. Writes nothing to the decision log; writes one audit entry `rules.decision-log.masked-input-searched.v1` to the outbox in the same transaction (actor, tenant, rule set, input, period, matches, key ids, correlation id; never the value or a fingerprint) | `400 QUERY_INVALID` (period, limit, unknown member, tenant without a key), `413 PAYLOAD_TOO_LARGE`, `429 RATE_LIMITED` with `Retry-After` past `masking.search.max-per-minute` (audited with `outcome = RATE_LIMITED`) |
| Every endpoint | | An unexpected failure | `500 INTERNAL_ERROR` with `correlationId` only: no stack trace, class name, SQL or table name; the same `correlationId` is in the server log (`TR-SEC-08`, `RLE-036`) |

`422 VALIDATION_FAILED` carries `violations` (at most 100, with `violationsTruncated` when more were found), at least one entry of `{ ruleId, column, message }` in document order (`RLE-041`): `ruleId` is the rule (row) and `column` the input or output name, either null when the problem is outside them, and `message` has no location prefix. The compiler checks every cell before it answers, so one response lists every cell problem; a structural problem (an unknown property, a missing key) stops reading and is the only entry. Bodies are read through a bounded reader: a declared `Content-Length` over the limit is refused before reading, and otherwise at most one byte past the limit is read (`413 PAYLOAD_TOO_LARGE`, `RLE-044`); the limits are `EngineDefaults.COMMAND_BODY_MAX_BYTES` (16,384) for `:approve`, `:reject` and `:withdraw`, `EngineDefaults.EVALUATE_BODY_MAX_BYTES` (1,048,576) for `:evaluate`, and `RuleSetReader.MAX_SOURCE_BYTES` (1,048,576) for imports. Every endpoint checks its permission before it reads the body. The approval comment is optional but bounded by `lifecycle.reason-max-length`, like the reason, because it is stored in the history.

List cursors are `base64url("<list>:<last key>")` plus the first 16 bytes of HMAC-SHA256 over tenant, scope (the rule set, for a version list) and payload. The key is one random 32-byte secret per database in `rle_engine_key` (migration `V5`). The starter creates or reads it once at start-up, after the migrations, in one short transaction outside any request (`INSERT ... ON CONFLICT DO NOTHING`, then `SELECT`), so replicas that start together all hold the same key; a database user without SELECT and INSERT on `rle_engine_key` fails start-up with a message naming the table. Requests only read memory: signing and verifying cost one HMAC, and a forged, edited, cross-list or cross-tenant cursor is `400 QUERY_INVALID` before any transaction or connection is opened. A daemon thread re-reads the key every 5 minutes (`EngineDefaults.CURSOR_KEY_REFRESH`) in a read-only transaction and swaps it in; the holder keeps the previous key, so cursors issued just before a change still verify for one period. No request path suspends a transaction or takes a second connection (architecture decision on #152). Rotation is not offered in 0.1.0. When `migrate-on-start` is `false` and the schema is not current, the key is not loaded and list calls answer `503 SCHEMA_NOT_READY` naming the schema state; no request creates the key. A name that cannot be a rule set name (a NUL byte, upper case, over 100 characters) is `404 RULE_SET_NOT_FOUND` before any query runs. Any other exception in an engine controller is `500 INTERNAL_ERROR` with only a `correlationId`, logged with the same id; a failure Spring already classifies as 4xx keeps its status as `INPUT_INVALID` (`RLE-036`). An engine exception whose code is not in this matrix (for example one a host extension raised) is answered the same way, so its message never reaches a `500` body. `428 PRECONDITION_REQUIRED` is the code for a command that must be conditional and was sent without `If-Match`, the same code the Process Engine uses for a missing `If-Match` or `Idempotency-Key`; keys stay optional on every Rule Engine endpoint.

Violations are ordered rule by rule; within a rule the conditions, then the outputs, each in the order written, nested `any` and `all` groups after the plain conditions. Cell-level problems (an unknown input or output, a condition that is not a non-empty string, a cell of the wrong JSON type) are reported by the compiler on their cell, so they never hide later cells.

`allowedActions` is a hint computed at response time from the version's status, `createdBy`, `requestedBy`, the caller and the caller's permissions (`VersionActions`): `request-publish` on a draft for `rules:author`; `approve` on a pending version for `rules:approve` when the caller is neither requester nor author; `reject` on a pending version for `rules:approve` when the caller is not the requester; `withdraw` on a pending version for `rules:author` when the caller is the requester (PD-089), so the requester sees `withdraw` and never `approve` or `reject`. It is never stored with an idempotency record, so a replay shows the caller's current actions. Commands check again and are the authority.

A caller without the endpoint's permission gets `403 PERMISSION_DENIED`; an unauthenticated call, or one whose tenant cannot be resolved, gets `401 UNAUTHENTICATED`. The REST module checks the permission against the caller's `rules:*` authorities itself, so it does not depend on the host enabling method security. All errors are RFC 9457 `application/problem+json` with `code`, `detail` and `correlationId`; `type` is `https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#<code in lower case>` (ADR-0010). `409` is used only for state conflicts a client resolves by reloading; `UNIQUE_HIT_VIOLATION` is `422` because it is an authoring defect in the published rule set (ADR-0010, PD-008). Every endpoint that writes accepts `Idempotency-Key`, and also returns `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` for a key reused with a different request hash and `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1` while the first request runs (ADR-0006, `RLE-014`). The first request holds a transaction-scoped advisory lock on tenant, operation and key; a duplicate does not wait for it, and once the first commits, the same request replays its stored status and body. Every endpoint returns `403 TENANT_NOT_ALLOWED` when `X-Tenant-Id` names a tenant outside the token's allowed list (ADR-0014, `RLE-018`).

#### 4.2.1 Simulation over sample inputs (`RLE-016`)

An approver asked to publish a changed fee table needs to see which customers get a different fee, which a row diff does not show. `:simulate` answers per sample with the simulated version's `matchedRuleIds` and `outputs`, the same for the baseline, and a `changed` flag.

- **Validation first.** The stored version is integrity-checked against its `canonical_hash` and compiled before any sample is evaluated; a failure is `422 VALIDATION_FAILED` and nothing is evaluated. The baseline is the version selected at the host clock (section 3.3.1), checked the same way.
- **What counts as changed.** The outcome kind (`MATCHED`, `NO_MATCH`, `FAILED`), any output value compared by exact value (`30000` equals `30000.00`; money by currency and minor units), or the failure code. A different matched rule giving the same outputs is not a change, because the approver's question is who pays a different fee; both rule lists are shown. With no effective version, `baselineVersion` and every `baseline` are null and every sample is `changed`.
- **Inputs.** Each sample passes the evaluation input bounds and, with `evaluation.reject-undeclared-inputs`, may name only inputs the simulated version declares. Inputs the baseline does not declare (a new input of the draft) are left out of the baseline's side.
- **Regex budget per request (#309).** All the decisions of one simulation, both sides of every sample, read from one `evaluation.regex-max-reads-per-request` counter (section 3.2). The sample whose match passes it fails with `REGEX_TIMEOUT` naming the setting; earlier samples keep their results and later ones are not evaluated, each reported failed with a `detail` saying so.
- **No side effects.** Both sides run in memory in one read-only use of the connection; the decision log, the idempotency table, the outbox and the history are untouched. The compiled cache is keyed by version id and canonical hash, so a cached entry is never served for other content.
- **Beside the lifecycle.** Simulation reads a version in any status. Approving v2 with an `effectiveFrom` at or before now retires v1 as superseded in the same transaction (`RLE-027`), so from then on the baseline is v2 and v1 can still be simulated. The pinned evaluate (DESIGN 4.2) is logged, so it refuses a draft, a version pending approval and, unless `evaluation.pin-retired` is on, a `RETIRED` version; simulation is how those are run.

```mermaid
sequenceDiagram
    autonumber
    participant C as Approver (rules:simulate)
    participant R as SimulationController
    participant E as RuleEngine.simulate
    participant S as rle_version (read only)
    C->>R: POST .../card-fee/versions/2:simulate {samples}
    R->>R: permission, bounded body, 1 to 1,000 samples
    R->>E: simulate("card-fee", 2, samples)
    E->>S: find v2 in the caller's tenant
    alt not in this tenant
        E-->>C: 404 VERSION_NOT_FOUND
    end
    E->>E: integrity check and compile v2 (else 422 VALIDATION_FAILED)
    E->>S: find the version effective at the host clock (v1)
    E->>E: evaluate v2 and v1 per sample, compare outputs
    E-->>C: 200 results with changed flags, nothing written
```

### 4.3 Host metadata

The starter contributes the `rules` entry of `GET /api/bpm/meta` (engine version, API version `v1`, feature flags such as `rules.simulation`, `rules.replay`, `rules.grid-editor`) and the `rules:*` permissions the caller holds to `GET /api/bpm/me`. Both endpoints and their contract are owned by [`cockpit/docs/DESIGN.md`](../../cockpit/docs/DESIGN.md) section 6 and ship in M1 (X-F-22, `RLE-019`). Settings whose value depends on the tenant are added to the `rules` settings for the caller's tenant through the host metadata module's `TenantModuleSettings` seam: `decision-log.mask-default` and `decision-log.require-explicit-mask` in force, both always present and boolean (`RLE-050`, `RLE-052`). Because `/meta` now differs per tenant, it is sent like `/me`: `Vary: Authorization, Cookie, X-Tenant-Id`, and a weak ETag over the caller's tenant and the body, so a cached body or a `304` of one tenant is never served in another, even when both bodies are equal. It stays `private, max-age=60`. `TenantModuleSettings` implementations read settings held in memory, with no database query per request.

### 4.4 Configuration (`horus.bpm.rules.*`)

| Property | Default | Meaning |
|---|---|---|
| `definitions`, `definitions-tenant` | empty, `default` | Rule sets loaded from the classpath at start-up as drafts |
| `definitions-auto-publish` | `false` | Refused unless the `dev` profile is active |
| `maker-checker.enabled` | `true` | `false` refused outside the `dev` profile |
| `decision-log.mode` | `transactional` | `transactional` keeps the decision row in the business transaction; `async` (outbox) for high-volume hosts. `async` ships in M2 (RE-F-15, PD-075); in M1 only `transactional` is accepted and `async` fails start-up naming the milestone |
| `authoring.cockpit-drafts` | `false` in 0.1.0 (PD-094; back to `true` by a new decision entry once replacing a draft in place, #165, ships) | Rule set draft creation and editing from the cockpit (CK-F-43, PD-075, PD-036, PD-094). `false` refuses `POST .../versions?copyOf=` and `PUT .../versions/{v}` from `rules:author` callers with `403 DRAFT_AUTHORING_DISABLED`, hides the edit actions in the cockpit, and leaves import with a document, approvals and simulation working; a host that keeps production changes in Git sets it per environment. Shown in `/api/bpm/meta` as `authoring.cockpit-drafts` in the `rules` module's settings, always present and boolean; a client that does not find it treats it as off. In 0.1.0 the cockpit is the only enforcement point; from #165 the engine refuses `PUT .../versions/{v}` itself (service layer, Java API included), and `?copyOf=` when it ships |
| `readings.style` | `sentence` | Style of plain-language readings of rules and of explanation `text` (CK-F-44, PD-077): `sentence` (patterns from the message catalogue) or `shorthand` (`tier: GOLD, PLATINUM`). If sentences miss the M1 checkpoint of 8 Jan 2027, M1 ships with `shorthand` only and the property accepts only that value until sentences ship. Shown in `/api/bpm/meta` as `rules.readings.style` |
| `explain.default-language` | `en` | Language of `text` when the request names none available (PD-027, PD-077) |
| `publication.require-simulation-report` | `false` | From M3 (RE-F-23, PD-022): when `true`, per host or per rule set (`publication.require-simulation-report.rule-sets`), `:request-publish` is refused with `422 VALIDATION_FAILED` unless a stored simulation report of the draft is attached. Off by default; in M1 the approver runs the simulation herself |
| `decision-log.retention` | none; required outside `dev` from M3 | Whole months, ISO 8601 (`P12M`, `P120M`). The scheduler drops whole monthly partitions of `rle_decision` that lie entirely before `now() - retention` (ADR-0010). From M3, outside the `dev` profile, start-up is refused until it is set, and a value below `decision-log.retention-floor` or not in whole months is refused naming the property (PD-050, decided by the product owner). The `dev` profile needs no value and drops nothing. Before M3 nothing is deleted and the property is ignored with a start-up note |
| `decision-log.partitions-ahead` | `P7D` | How far ahead monthly partitions are created |
| `cache.max-entries`, `cache.epoch-check-interval`, `cache.listen-notify` | `1000`, `PT5S`, `true` | ADR-0009 |
| `evaluation.regex-max-reads` | `1000000` | Characters one `matches()` may read, `1` to `1000000`, lower only: the deterministic primary bound (`TR-SEC-02`, `RLE-007`, `TR-DET-05`). Past it the decision fails with `EVALUATION_ERROR`, `error_code = REGEX_TIMEOUT` |
| `evaluation.regex-max-reads-per-decision` | `5000000` | Characters all the `matches()` of one decision may read together, `1` to `5000000`, lower only, counted the same deterministic way as `regex-max-reads` (`TR-SEC-02`, `RLE-007`, `TR-DET-05`). Bounds a rule set of many `matches` rows, whose cost would otherwise be rows times `regex-max-reads`. Past it the decision fails with `EVALUATION_ERROR`, `error_code = REGEX_TIMEOUT`, no partial outputs |
| `evaluation.regex-max-reads-per-request` | `50000000` | Characters all the `matches()` of one request of many decisions may read together (`:simulate` now, `:evaluate-batch` when built), `1` to `5000000000`, counted the same deterministic way (`TR-SEC-02`, `RLE-007`, `TR-DET-05`). May be raised up to the ceiling, which is 1,000 items times the per-decision ceiling. Past it the item fails with `EVALUATION_ERROR`, `REGEX_TIMEOUT`, and no later item is evaluated |
| `evaluation.regex-timeout-ms` | `50` | Longest time one `matches()` may run, `1` to `1000`: the backstop of the read budget on a slow machine. Past it the decision fails with `EVALUATION_ERROR`, `error_code = REGEX_TIMEOUT` |
| `evaluation.regex-max-input-length` | `4096` | Longest text `matches()` looks at, `1` to `evaluation.max-string-length` (and lowered with it by default). A longer text fails the decision with `EVALUATION_ERROR`, `error_code = REGEX_INPUT_TOO_LONG`, without running the match |
| `evaluation.max-input-scale` | `34` | Absolute scale bound of number inputs, `0` to `34`, lower only; precision is always at most 34 digits (`EngineDefaults`, ADR-0024) |
| `evaluation.reject-undeclared-inputs` | `true` | Refuse undeclared input names with `INPUT_INVALID`; undeclared inputs are never logged |
| `evaluation.max-rows` | `5000` | Rules per version, checked at import; lower only |
| `evaluation.pin-retired` | `false` | Whether a pinned evaluation (`rules:pin`) may name a `RETIRED` version. `false` refuses it with `409 INVALID_STATE`, so a rule taken out of service never makes a new logged decision; replay of a logged decision is not affected (security review of #261) |
| `evaluation.max-output-bytes` | `1048576` | Estimated JSON size of one decision's outputs, checked as `collect` values accumulate; a breach is a logged `EVALUATION_ERROR`; lower only |
| `evaluation.max-string-length` | `65536` | String inputs (`INPUT_INVALID`) and strings built by `+` (`EVALUATION_ERROR`); lower only. The three limits bound the heap one decision can use, which every tenant of the host shares |
| `idempotency.retention` | `PT24H` | Lifetime of idempotency records, `PT1M` to `P30D` |
| `evaluation.max-batch`, `simulation.max-inputs` | `1000`, `1000` | Items per `:evaluate-batch` and per `:simulate`; may be lowered, never raised. `simulation.max-inputs` is built (RLE-016) |
| `query.max-range`, `query.max-page-size`, `query.statement-timeout` | `P31D`, `200`, `PT2S` | Bounds of `GET /decisions` and list reads (ADR-0015); range and page size may be lowered only |
| `idempotency.retention` | `PT24H` | How long an idempotency record answers replays |
| `tenancy.claim` | `tenant_id` | Attribute of the session principal, or claim of a validated token, that the default `TenantResolver` reads (PD-079, ADR-0014 decision 6). No header-based resolver is shipped; a host replaces the resolver with its own bean |
| `tenancy.dev-tenant` | unset | Fixed tenant in the `dev` profile only; start-up is refused when set outside `dev` |
| `lifecycle.reason-required`, `lifecycle.reason-max-length` | `true`, `2000` | Reason on `:reject` and `:retire`; a host may make it optional for reject only, never for retire (a retire must say why, ADR-0025) |
| `lifecycle.system-actor` | `system:supersession` | Actor recorded for automatic supersession. It must start with `system:`, a namespace reserved for the engine's own actors: a caller whose id starts with `system:` is refused with `403 PERMISSION_DENIED` on every operation, so no host user can act or be recorded under an engine actor's name (security review of #269) |
| `decision-log.retention-floor` | `P12M` | Shortest `decision-log.retention` accepted (PD-050); a host may raise it to its own policy, never set it below `P12M` (`TR-EXT-05`) |
| `lifecycle.retire-approval` (host property `horus.bpm.lifecycle.retire-approval`, shared with the Process Engine, PD-102) | `single` | Approval mode of manual retirement (PD-080, ADR-0025): `single` (one `rules:approve` holder with a reason) or `four-eyes` (a second holder confirms; from M3). Shown in `/api/bpm/meta` settings and recorded on each retirement event and history row. Independent of the PD-012 emergency publication path |
| `lifecycle.retire-last-version` (host property `horus.bpm.lifecycle.retire-last-version`, shared with the Process Engine) | `confirm` | Retiring the last version in effect (PD-101, section 3.3.4): `confirm` needs `confirmNoVersionInEffect: true`, else `409 LAST_VERSION_IN_EFFECT`; `four-eyes` makes it a retirement request a second `rules:approve` holder confirms; `refuse` always answers `409 LAST_VERSION_IN_EFFECT`. Shown in `/api/bpm/meta` settings. `four-eyes` fails start-up until four-eyes retirement ships (RE-F-49) |
| `lifecycle.approver-must-differ-from` (host property `horus.bpm.lifecycle.approver-must-differ-from`) | `makers` | Shared by both engines: host property `horus.bpm.lifecycle.approver-must-differ-from` (PD-096), published as `lifecycle.approver-must-differ-from` in each engine module's `/api/bpm/meta` settings. `makers` (default): the approver differs from every maker of the version, that is the importer or creator, every editor of the draft and the requester. `requester`: the approver differs from the requester only; this relaxes a control, so it is never the default, it is logged at WARN at start-up and recorded on every approval history row. The requester can never approve under either value (`403 SELF_APPROVAL_FORBIDDEN`, with a detail naming why) |
| `lifecycle.sweep-interval` | `PT1M` | How often the scheduler retires superseded versions (section 3.3.2), `PT1S` to `PT1H`; status only, never selection. Runs on the daemon thread `horus-rle-lifecycle-sweep` of every replica, started after the migrations |
| `masking.hmac-key` | unset | Base64 key for HMAC-SHA256 of masked inputs; at least 32 bytes after decoding. Start-up fails when a loaded rule set masks an input and the key is missing, not Base64 or shorter than 32 bytes; the message names `horus.bpm.rules.masking.hmac-key` and the rule set, never the value. Shared with the Process Engine as `horus.bpm.masking.hmac-key` when both run in one host. The value is never logged, returned, put in an event or used as a metric tag (`TR-SEC-10`, `RLE-035`) |
| (start-up checks of the keys) | | Generate every key with `openssl rand -base64 32` and keep it in a secret store. Start-up is refused, naming the setting and never the value, when a tenant key equals the host key or another tenant's key (compared in constant time, `MaskingKey.sameKeyAs`), or when a tenant key's id equals the host key id. A tenant id used for a subkey may not be blank or contain a control character or an unpaired surrogate (`MaskingKey.forTenant`) |
| `masking.hmac-key-id`, `masking.tenants.<tenant>.hmac-key-id` | `k1` | Label of the key, 1 to 32 letters, digits, `_` or `-`, stored in every fingerprint (`hmac:<keyId>:<hex>`) so a row always says which key made it. Not secret. Change it with the key, and keep the old key under `previous-keys` (below) while old rows must stay findable. A key id names one key for good: start-up refuses a known id with a different check value (section 6) |
| `masking.previous-keys[n].hmac-key`, `masking.previous-keys[n].hmac-key-id`, and the same under `masking.tenants.<tenant>.` | empty | Earlier keys kept after a rotation (#281), each with the id its tokens carry (required). Search only, never writing: `searchKeys` returns the current key first; for a tenant on the host key, then its HKDF subkey of each previous host key and its own previous keys as configured; for a tenant with its own key, then its own previous keys and its HKDF subkey of the current and each previous host key, so a tenant that moved off the host key keeps its earlier rows findable (architecture and security review of #281). Host and tenant key ids never overlap, so a host subkey matches only the tenant's rows from its time on the host key. A key removed from the list is no longer searched; its id stays reserved. Start-up refuses, by setting name, two keys with one id within the host's keys or one tenant's keys, a tenant key id equal to a host key id, any two configured keys with the same bytes (constant-time), and a tenant named `*` |
| `masking.tenants.<tenant>.hmac-key` | unset | The tenant's own key, same format; a tenant with this setting never uses the host key, even when its own value is invalid. A host that keeps one key per tenant in a vault declares a `MaskingPolicy` bean instead (`@ConditionalOnMissingBean`), which replaces all `masking.*` settings |
| `decision-log.mask-default` (per host, overridable per tenant) | `false` | Whether an input that declares no `mask` is masked in the decision log (PD-099). An input declaring `mask: true` is always masked and one declaring `mask: false` never is, whatever this says. A change is logged at start-up with the old and new value, one line per tenant whose value changed (INFO, WARN when masking is turned off), from the values recorded in `rle_setting_seen` (`V14`): one row per tenant plus a row with an empty tenant id for the host default, which a tenant first seen since the last start-up is compared with. Limit: the start-up record knows the tenants that have a rule set that is not retired, those recorded before and, with the property-backed policy, those named under `masking.tenants`. A host `MaskingPolicy` bean has no list of tenants, so a tenant whose default comes from such a bean and that has no rule set yet is covered only through the host row (`maskByDefault` of the empty tenant id) until its first rule set exists; its own value is recorded, and compared, from the next start-up after that. The value in force for the caller's tenant is shown in `/api/bpm/meta` (PD-099 mitigation 4, `RLE-052`). Per tenant: `masking.tenants.<tenant>.mask-default` |
| `decision-log.require-explicit-mask` (per host, overridable per tenant) | `false` | Strict mode (PD-099 mitigation 2, #283): when `true`, every input must declare `mask: true` or `mask: false`, and an undeclared input is a `422 VALIDATION_FAILED` at import and at request-publish, naming the input. Off by default because it adds a control rather than relaxing one (PD-074); a bank turns it on for production. Checked at import, at draft replacement, at request-publish and again at approval, so a draft imported or a request made before strict mode was turned on can be neither sent for approval nor approved; one violation per undeclared input, its `column` naming it, nothing stored (`RLE-050`). Shown in `/api/bpm/meta`. Per tenant: `masking.tenants.<tenant>.require-explicit-mask` |
| `lifecycle.acknowledge-warnings` | `[UNMASKED_PERSONAL_DATA]` | Warning codes an approver must accept, as `{ code, input }` pairs in `acknowledgedWarnings`, before a version is published, else `409 WARNINGS_NOT_ACKNOWLEDGED` (PD-104, section 3.3.5, `RLE-053`). A warning on an input masked only by the tenant's `decision-log.mask-default` needs none. A host may add codes as new warnings appear. A code this release never raises (`VersionWarning.CODES`) stops start-up, naming the setting. A list without `UNMASKED_PERSONAL_DATA` relaxes a control: it is accepted and logged at WARN at start-up. Published in the `rules` settings of `/api/bpm/meta` as `lifecycle.acknowledge-warnings`, always present, as a comma-separated string because `/meta` values are only booleans, integers or strings: `"UNMASKED_PERSONAL_DATA"` by default, `""` when the host lists no code. A client that does not find the setting assumes `UNMASKED_PERSONAL_DATA` |
| `decision-log.personal-data-names`, `decision-log.additional-personal-data-names` (per host, overridable per tenant) | the built-in list; empty | Input names that look like personal data (PD-099 mitigation 1, `RLE-049`). An input that declares no `mask` and matches one gets an `UNMASKED_PERSONAL_DATA` warning in `warnings` of the version, on import, draft replacement, request-publish and every read, so the approver sees what the author saw. The built-in list (`PersonalDataNames.BUILT_IN`) covers full name, `ho ten`, `ho va ten`, national id, citizen id, `cccd`, `cmnd`, passport, `ho chieu`, phone, `so dien thoai`, `sdt`, email, address, `dia chi`, date of birth, `dob`, `ngay sinh`, account number, `so tai khoan`, `stk`, IBAN, card number, tax id, `ma so thue`, `mst`, driver licence, `giay phep lai xe`, `gplx` and `bang lai`. `personal-data-names` replaces the list, `additional-personal-data-names` extends it; `masking.tenants.<tenant>.personal-data-names` replaces the host's list for that tenant only and `masking.tenants.<tenant>.additional-personal-data-names` extends whichever applies. Names are compared as words: both sides are split at spaces, punctuation, case changes and digits, lower-cased and stripped of accents, and a name matches when its words, joined, equal consecutive words of the input name, joined (`nationalId`, `national_id`, `NATIONAL_ID` and `nationalid` match "national id"; `company` never matches "pan"). No regular expression, so configuration cannot slow matching down. At most 500 names of 1 to 100 characters; a blank name stops start-up naming the setting. Only names are checked, never values |
| `masking.search.max-per-minute`, `masking.tenants.<tenant>.search.max-per-minute` | `30` | Masked-input searches one caller may run per minute on one replica, at least `1`; it cannot be switched off (#24, architect condition (c)). Past it the search is refused with `429 RATE_LIMITED` and `Retry-After`, and an audit entry with `outcome = RATE_LIMITED` is written. The count is in memory per replica, a sliding window on the engine clock |

There is no `clock` property. A fixed instant in configuration could reach production and freeze effective-version selection and partition months, and it would bypass the `rules:simulate` check that guards a caller-chosen instant. Tests declare a fixed `Clock` bean (or use `TestClock` from `rule-engine-test`); a single decision reasons about another instant through `DecisionRequest.clock` (section 4.1).

### 4.5 Version diff (`RLE-028`)

`GET /rule-sets/{name}/versions/{v}/diff?base={w}` compares the canonical models of two versions of the same rule set (section 3.1, `RLE-024`); source text, comments and `effectiveFrom` never show as content changes. Any statuses may be compared. Rows are matched by rule id, never by position:

| `change` | Meaning |
|---|---|
| `ADDED` | Rule id only in `v`; `position` is its 1-based place in `v` |
| `REMOVED` | Rule id only in `w`; `basePosition` is its place in `w` |
| `CHANGED` | Same rule id, different `when` or `then` cells; `fields` lists `{ "path", "from", "to" }` with JSON Pointer paths into the row (`/then/fee`, `/when/tier`) and canonical values (money as `{ "currency", "amount" }` in minor units) |
| `MOVED` | Same rule id, same cells, and its order relative to the other common rows changed. The moved set is the smallest one: the common rule ids not in the longest common subsequence of the two orders. Inserting or removing rows never moves the rows around them. A row that is both moved and changed is `CHANGED` with `"moved": true`. Under `first` order is behaviour, so the cockpit shows `MOVED` as a change that needs review |

```json
{ "ruleSet": "card-fee", "version": 2, "base": 1,
  "canonicalHash": "4be1...", "baseCanonicalHash": "9c07...", "sourceHashChanged": true,
  "effectiveFrom": { "from": "2026-11-01T00:00:00Z", "to": null },
  "header": [],
  "inputs": { "added": [], "removed": [], "changed": [], "reordered": false },
  "outputs": { "added": [], "removed": [], "changed": [], "reordered": false },
  "rows": [
    { "ruleId": "R2", "change": "CHANGED", "position": 2, "basePosition": 2, "moved": false,
      "fields": [ { "path": "/then/fee", "from": { "currency": "VND", "amount": 30000 }, "to": { "currency": "VND", "amount": 0 }, "nonAscii": false } ] },
    { "ruleId": "R6", "change": "ADDED", "position": 3 } ],
  "unchangedCount": 2 }
```

`header` lists only changed document members other than `inputs`, `outputs`, `rules` and `ruleSet` (hit policy, aggregate, time zone, money rounding, expression language, description, schema version), each a `{ path, from, to, nonAscii }` field like a row's. Columns are matched by name: `added` and `removed` list names, `changed` lists each common column with its changed members (`/type`, `/mask`, `/default`), and `reordered` says whether the common columns changed order. A value is the canonical node; a `then` cell or an output `default` that is one money literal (`"0 VND"`) is shown as `{ currency, amount }` in minor units, any other cell as its canonical text. `nonAscii` is true when either value's text holds a character outside printable ASCII (U+0020 to U+007E) or an escape such as `\u200B`, so a Cyrillic look-alike or a hidden format character is visible to the approver (ADR-0026 decision 6, `RLE-048`). The moved set is found in O(n log n): the common rows of `v` are mapped to their places in `w`, the longest increasing subsequence starting at each row is measured right to left, and a single left-to-right pass keeps the earliest row that can still complete a longest subsequence, which is the tie-break of `RLE-028`. `effectiveFrom` is reported as version metadata beside the content diff. Rows are ordered by `position` in `v`, then removed rows by `basePosition`; field order follows the canonical document, so the same request returns a byte-identical body. Cost: two canonical documents of at most 1,000 rows, O(n log n) for the subsequence, under 5 ms.

![Personal-data guards: the start-up record of the masking default, strict mode and warnings at import, and what the approver sees](images/personal-data-guards.svg)

Source: [`diagrams/personal-data-guards.mmd`](diagrams/personal-data-guards.mmd). **Guards against unmasked personal data (PD-099, `RLE-049` to `RLE-052`).** The decision log is append-only, so an input that holds personal data and was never marked `mask: true` would leave data that can never be erased. Four guards catch it before publication: a warning for an undeclared input whose name looks like personal data, strict mode that refuses any undeclared input, the masking status per input in the diff, and a start-up log line when a tenant's masking default changes.

`masking` (`RLE-051`, PD-099 mitigation 3) lists every input of either version, those of `v` in its order and then those only in `w`, as `{ input, from, to, changed, personalDataName }`. `from` and `to` are the status in `w` and `v`: `MASKED` or `NOT_MASKED` when the input declares `mask: true` or `mask: false`, `MASKED_BY_DEFAULT` or `NOT_MASKED_BY_DEFAULT` when it declares nothing and the tenant's `decision-log.mask-default` in force when the diff is read decides, null when the input is absent. `changed` is true whenever they differ, an added or removed input included, so the approver sees "nationalId: MASKED to NOT_MASKED" before approving. `personalDataName` is the personal-data name an undeclared input of `v` matches (section 4.4), or null. The `/mask` member change in `inputs.changed` is reported as before.

### 4.6 Export and import (`RLE-032`, `RLE-033`)

| Rule | Detail |
|---|---|
| Export path | `GET /rule-sets/{name}/versions/{v}/export?format=yaml\|json`, default `yaml`; any other value is `400 INPUT_INVALID`. A query parameter rather than `Accept`, so a download link, a log line and a cache key all name the format |
| JSON content | The canonical document, RFC 8785 bytes, written from the model; `Content-Disposition: attachment; filename="<name>-v<v>.json"` |
| YAML content | The same model as YAML 1.2 with keys in canonical order; comments of the source are not reproduced (they stay available through `?include=source`) |
| Never exported | Tenant, status, `lockVersion`, `effectiveFrom`, requester, approver, comments and decision data |
| Import paths | `POST /rule-sets` creates the rule set and version 1 (`409 RULE_SET_EXISTS` when the name exists); `POST /rule-sets/{name}/versions` creates the next version (`400 INPUT_INVALID` when the document's `ruleSet` differs from `{name}`, `404 RULE_SET_NOT_FOUND` when it does not exist). Both store the file as the source document (`TR-STD-01`) and arrive as `DRAFT` |
| Round trip | Export then import gives the same `canonical_hash` in any tenant, because the hash excludes `effectiveFrom` and the writer prints the canonical form |
| Refusal | Validation runs before the transaction opens; a refused document writes no version, no source and no idempotency success record |

### 4.7 Decision explanation (`RLE-030`, `RLE-031`, PD-027, PD-077)

The explanation is built from the `rle_decision` row and the stored version it names. Nothing is evaluated: every condition of a matched row was `true` by definition, so the engine formats the recorded value and the cell's test. Rows that did not match are not explained, because showing why they failed would mean evaluating again, which is impossible for masked inputs and would be a second evaluation path that could disagree with the first. The cockpit shows the whole table with the matched rows marked (`CKP-005`).

```json
{ "decisionId": "D7", "ruleSet": "card-fee", "version": 1, "hitPolicy": "FIRST", "outcome": "OK", "errorCode": null,
  "clock": "2026-11-20T10:00:00Z",
  "matched": [ { "ruleId": "R2", "position": 2,
      "conditions": [ { "input": "txCount", "test": ">= 3", "value": 5, "masked": false, "missing": false, "result": true } ],
      "outputs": { "fee": { "currency": "VND", "amount": 10000 } } } ],
  "outputs": { "fee": { "currency": "VND", "amount": 10000 } },
  "reasons": [ { "code": "RULE_MATCHED", "params": { "ruleId": "R2", "version": 1 } } ],
  "text": "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Result: fee 10000 VND." }
```

| Rule | Detail |
|---|---|
| Contract | `matched`, `outputs` and `reasons` are the contract. Reason codes: `RULE_MATCHED`, `NO_RULE_MATCHED`, `UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR`, `VALUE_MISSING`, `VALUE_MASKED`, each with named parameters |
| Language | `text` is rendered from `reasons` through a message catalogue per language, shared with the plain-language readings (CK-F-44, PD-027, PD-077): English and Vietnamese ship, `?lang=` or `Accept-Language` chooses among available catalogues, default `explain.default-language` (`en`), and a missing entry falls back to English. A host overrides or adds entries, or a whole language, by placing a catalogue resource on its classpath, with no code; a `DecisionExplainer` bean replaces the renderer entirely. `reasons` never change. The cockpit renders `reasons` with the same catalogue format and does not parse `text` |
| Numbers in `text` | Locale-neutral, as in the expression language: plain digits with no grouping, `.` as the decimal mark, money in major units with the currency code (`10000 VND`, `12.50 USD`), dates and instants in ISO 8601. Grouping such as `10,000` or `10.000` is the cockpit's job per locale. JSON fields keep canonical values (money in minor units) |
| Masked inputs | `value` is the stored `hmac:<keyId>:<hex>`, `masked: true`, reason `VALUE_MASKED`; the plain value is never available to the engine at explain time |
| Null inputs | `missing: true` and reason `VALUE_MISSING`; text "income has no value" |
| No match | `matched: []`, reason `NO_RULE_MATCHED`, no outputs |
| Failures | `UNIQUE_HIT_VIOLATION` lists every matched row (the log stores them, section 5); `EVALUATION_ERROR` names the error code and, when recorded, the row |
| Stability | Same decision, same bytes: the version is immutable and the template is versioned with the engine; a template change ships only in a minor release and is listed in the release notes |

### 4.8 Metrics

Micrometer names, exported to Prometheus with the usual suffixes; no tenant, rule set or input value is ever a tag value unless listed.

| Micrometer name | Type | Prometheus | Tags |
|---|---|---|---|
| `horus.rle.cache.size` | Gauge | `horus_rle_cache_size` | none |
| `horus.rle.cache.evictions` | Counter | `horus_rle_cache_evictions_total` | `cause` (`size`, `epoch`, `notify`) |
| `horus.rle.partition.default.rows` | Gauge | `horus_rle_partition_default_rows` | none |
| `horus.rle.lifecycle.retired` | Counter | `horus_rle_lifecycle_retired_total` | `reason` (`SUPERSEDED`, `MANUAL`) |

## 5. Storage model (`rule-engine-postgres`)

Every table has `tenant_id` first in its primary key, unique constraints and indexes (`TR-TEN-01`); foreign keys include `tenant_id`, so a row can never reference another tenant's row. Ids are ULIDs (`VARCHAR(26)`), times `TIMESTAMPTZ` in UTC. Engine-internal sweeps (partition maintenance, outbox relay, idempotency cleanup) are the only reads across tenants and never return rows to a caller.

```sql
CREATE TABLE rle_rule_set (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  name            TEXT        NOT NULL,
  description     TEXT,
  cache_epoch     BIGINT      NOT NULL DEFAULT 1,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, name)
);

CREATE TABLE rle_version (
  tenant_id         TEXT        NOT NULL,
  id                VARCHAR(26) NOT NULL,
  rule_set_id       VARCHAR(26) NOT NULL,
  version_num       INT         NOT NULL,
  status            TEXT        NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED')),
  hit_policy        TEXT        NOT NULL CHECK (hit_policy IN ('FIRST','UNIQUE','COLLECT')),
  effective_from    TIMESTAMPTZ,                 -- version metadata, never in canonical_json; set to approved_at when absent at approval
  canonical_json    JSONB       NOT NULL,        -- without effectiveFrom (section 3.1); JSONB reorders keys, so reads write the RFC 8785 form again
  canonical_hash    CHAR(64)    NOT NULL,        -- SHA-256 of RFC 8785 form, in the stored schema version (DN-04)
  source_format     TEXT        NOT NULL CHECK (source_format IN ('JSON','YAML','DMN_XML')),   -- M1, DN-01
  source_document   TEXT        NOT NULL,        -- the document as received, never executed (TR-STD-01); UTF-8 only, no NUL or BOM (ADR-0026)
  source_hash       CHAR(64)    NOT NULL,        -- SHA-256 of the raw request bytes, before any decoding
  lock_version      BIGINT      NOT NULL DEFAULT 0,
  created_by        TEXT,                        -- author (V2); null for versions written before V2
  requested_by      TEXT,
  requested_at      TIMESTAMPTZ,
  approved_by       TEXT,
  approved_at       TIMESTAMPTZ,
  approval_comment  TEXT,                        -- latest approval only; full history in rle_version_history
  retired_at        TIMESTAMPTZ,                 -- added by V11 (#28, additive); selection reads it (section 3.3.1);
                                                 -- CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL))
  retire_requested_by TEXT,                      -- four-eyes retirement request (M3, PD-080); selection never reads it
  retire_requested_at TIMESTAMPTZ,
  retire_reason     TEXT,
  created_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, rule_set_id, version_num),
  FOREIGN KEY (tenant_id, rule_set_id) REFERENCES rle_rule_set (tenant_id, id),
  -- Four eyes in the database (TR-SEC-04): V2 added the author; V12 (#299) dropped the 'system:dev-auto-publish' exemption.
  CONSTRAINT rle_version_four_eyes CHECK (approved_by IS NULL
    OR (approved_by IS DISTINCT FROM requested_by AND approved_by IS DISTINCT FROM created_by))
);
CREATE INDEX idx_rle_version_effective
  ON rle_version (tenant_id, rule_set_id, status, effective_from DESC);

-- Append-only lifecycle history (section 3.3.3); one row per transition, in the transition's transaction.
CREATE TABLE rle_version_history (
  tenant_id         TEXT        NOT NULL,
  id                VARCHAR(26) NOT NULL,
  version_id        VARCHAR(26) NOT NULL,
  action            TEXT        NOT NULL CHECK (action IN ('CREATED','REQUESTED','APPROVED','REJECTED','WITHDRAWN','DRAFT_UPDATED','RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED')),  -- DRAFT_UPDATED added by V6
  from_status       TEXT,
  to_status         TEXT        NOT NULL,
  actor             TEXT        NOT NULL,        -- caller id, or lifecycle.system-actor (default system:supersession) for supersession
  reason            TEXT,                        -- reject and retire reason, approval comment, or 'SUPERSEDED'
  superseded_by     INT,                         -- version_num, for automatic retirement
  approval_mode     TEXT        CHECK (approval_mode IN ('single','four-eyes','system')),  -- RETIRED rows: mode in force (PD-080)
  acknowledged_warnings JSONB,                   -- APPROVED rows: [{code, input}] accepted by the approver, [] when none (PD-104, additive migration)
  masked_by_default_inputs JSONB,                -- APPROVED rows: inputs masked only by the tenant default, [] when none (PD-104)
  -- V15: CHECK (action = 'APPROVED' OR both columns null); CHECK both are JSON arrays when set
  no_version_in_effect BOOLEAN,                  -- RETIRED rows: true when no version remained in effect (PD-101, additive migration)
  lock_version      BIGINT      NOT NULL,        -- after the transition
  occurred_at       TIMESTAMPTZ NOT NULL,
  canonical_hash          CHAR(64),             -- V6: DRAFT_UPDATED rows only, the hash after the replacement
  previous_canonical_hash CHAR(64),             -- V6: DRAFT_UPDATED rows only, the hash before it
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version (tenant_id, id),
  CHECK ((action = 'DRAFT_UPDATED') = (canonical_hash IS NOT NULL AND previous_canonical_hash IS NOT NULL))  -- V6
);
CREATE INDEX idx_rle_version_history_version ON rle_version_history (tenant_id, version_id, occurred_at);
CREATE INDEX idx_rle_version_published           -- lifecycle sweep, crosses tenants (section 3.3.2)
  ON rle_version (tenant_id, rule_set_id, version_num) WHERE status = 'PUBLISHED';

CREATE TABLE rle_rule (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  version_id      VARCHAR(26) NOT NULL,
  row_order       INT         NOT NULL,
  rule_id         TEXT        NOT NULL,
  when_expr_json  JSONB       NOT NULL,
  then_expr_json  JSONB       NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, version_id, rule_id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version (tenant_id, id)
);

-- Append-only decision log, range-partitioned by month from the first migration (ADR-0010, PD-004).
CREATE TABLE rle_decision (
  tenant_id        TEXT        NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  id               VARCHAR(26) NOT NULL,
  rule_set_name    TEXT        NOT NULL,
  version_num      INT         NOT NULL,
  hit_policy       TEXT        NOT NULL,
  outcome          TEXT        NOT NULL CHECK (outcome IN ('OK','UNIQUE_HIT_VIOLATION','EVALUATION_ERROR')),  -- OK with no matched rule is NoMatch,
  error_code       TEXT,
  masked_inputs    JSONB       NOT NULL,        -- inputs with mask=true stored as keyed HMAC-SHA256
  outputs          JSONB,
  matched_rule_ids JSONB       NOT NULL,        -- every matched row, also for UNIQUE_HIT_VIOLATION; [] for no match and EVALUATION_ERROR
  caller_id        TEXT,
  correlation_id   TEXT,
  eval_clock       TIMESTAMPTZ NOT NULL,
  duration_micros  BIGINT      NOT NULL,
  log_mode         TEXT        NOT NULL CHECK (log_mode IN ('TRANSACTIONAL','ASYNC')),
  PRIMARY KEY (tenant_id, created_at, id)
) PARTITION BY RANGE (created_at);

-- Catches rows outside every monthly partition, so an insert never fails for lack of a partition.
CREATE TABLE rle_decision_default PARTITION OF rle_decision DEFAULT;

-- Created by the first migration for the current and the next month, then by the scheduler 7 days ahead.
-- Created standalone with a CHECK constraint and attached, so ATTACH skips the validation scan
-- and takes SHARE UPDATE EXCLUSIVE on the parent instead of ACCESS EXCLUSIVE.
CREATE TABLE rle_decision_2027_01 (LIKE rle_decision INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE rle_decision_2027_01 ADD CONSTRAINT rle_decision_2027_01_range
  CHECK (created_at >= '2027-01-01 00:00:00+00' AND created_at < '2027-02-01 00:00:00+00');
ALTER TABLE rle_decision ATTACH PARTITION rle_decision_2027_01
  FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');

CREATE INDEX idx_rle_decision_lookup
  ON rle_decision (tenant_id, rule_set_name, created_at DESC, outcome);

CREATE TABLE rle_decision_outbox (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  payload         JSONB       NOT NULL,
  status          TEXT        NOT NULL CHECK (status IN ('PENDING','CLAIMED','SENT')),
  locked_by       TEXT,
  locked_until    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_rle_outbox_poll                 -- engine sweep, crosses tenants
  ON rle_decision_outbox (created_at, locked_until) WHERE status IN ('PENDING','CLAIMED');

CREATE TABLE rle_idempotency (
  tenant_id       TEXT        NOT NULL,
  operation       TEXT        NOT NULL,
  idempotency_key TEXT        NOT NULL,
  request_hash    CHAR(64)    NOT NULL,
  status_code     INT         NOT NULL,
  response_body   JSONB       NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, operation, idempotency_key)
);
CREATE INDEX idx_rle_idempotency_expiry ON rle_idempotency (expires_at);   -- engine sweep, crosses tenants

-- Engine-internal secrets (V5), not tenant data: one row per purpose, today 'cursor-hmac' (section 4.2).
CREATE TABLE rle_engine_key (
  name        TEXT        NOT NULL PRIMARY KEY,
  key_bytes   BYTEA       NOT NULL CHECK (octet_length(key_bytes) >= 32),
  created_at  TIMESTAMPTZ NOT NULL
);

-- Acknowledged warnings on approval (V15, RLE-053, PD-104), on rle_version_history: written on every APPROVED row,
-- null on other actions (CHECK rle_version_history_ack_on_approval) and always a JSON array (rle_version_history_ack_arrays).
--   acknowledged_warnings     JSONB  -- [{ "code": "UNMASKED_PERSONAL_DATA", "input": "nationalId" }], [] when nothing needed it
--   masked_by_default_inputs  JSONB  -- inputs masked by the tenant's decision-log.mask-default, not by declaration
-- The append-only triggers of V4 keep both unchangeable once written.

-- The value of a setting each tenant last started with (V14, RLE-052): today decision-log.mask-default, so a change
-- is logged at start-up with the old and new value (section 4.4). Written at start-up only, never read by a request.
CREATE TABLE rle_setting_seen (
  tenant_id    TEXT        NOT NULL,
  setting      TEXT        NOT NULL CHECK (length(setting) BETWEEN 1 AND 100),
  value        TEXT        NOT NULL CHECK (length(value) <= 200),
  recorded_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, setting)
);

-- Key check values of masking keys (V16, section 6, TR-SEC-10): one row per scope and key id, written at start-up
-- only, never updated or deleted, so a key id names one key for good. tenant_id is the tenant of its own key, or
-- '*' for a host key (recorded once, before tenant derivation). A key id recorded under '*' is never accepted for a
-- tenant, and the reverse. No secret: the check value is not the key.
CREATE TABLE rle_masking_key_check (
  tenant_id      TEXT        NOT NULL CHECK (tenant_id <> ''),
  key_id         TEXT        NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
  check_value    TEXT        NOT NULL CHECK (check_value ~ '^[0-9a-f]{16}$'),
  first_seen_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, key_id)
);
```

Partition maintenance (`TR-OPS-04`, `RLE-017`):

![Partition maintenance](images/partition-maintenance.svg)

Source: [`diagrams/partition-maintenance.mmd`](diagrams/partition-maintenance.mmd).

| Rule | Detail |
|---|---|
| Boundaries | Calendar months in UTC, half-open `[first day, first day of next month)`; names `rle_decision_YYYY_MM` |
| Creation | Daily and at start-up, months up to `now() + decision-log.partitions-ahead` (7 days), and always the current and the next month; one replica at a time through a transaction-scoped advisory lock (no session state, `TR-OPS-05`); `lock_timeout = 5s`, retried on the next run. Runs on the starter's daemon thread `horus-rle-partitions` (`DecisionLogPartitions.ensureAhead`) |
| Default partition | Should stay empty. If it holds rows of a month being created, they are copied into the new partition and removed from the default in the same transaction before `ATTACH`; this moves rows without losing any, and the bounded `DELETE` names `rle_decision_default` only, never `rle_decision`. Gauge `horus_rle_partition_default_rows` alerts above 0; it is read from `rle_decision_default` when the registry asks and is bound only when the host has Micrometer |
| Retention | From M3 (PD-050): partitions whose upper bound is before `now() - decision-log.retention` are dropped with `lock_timeout = 2s`; the period is required outside `dev`, in whole months, at least `P12M`. In `dev`, and before M3, nothing is dropped. No row is ever deleted by date (`TR-OBS-01`) |
| Lock impact | `ATTACH` takes `SHARE UPDATE EXCLUSIVE` on the parent and does not block inserts. `DROP TABLE` of a partition takes `ACCESS EXCLUSIVE` on the parent; inserts queue behind it for at most the 2 s `lock_timeout`, once a month |

## 6. Runtime mechanics and capacity math

![Evaluate a decision in the host transaction](images/evaluate-decision.svg)

Source: [`diagrams/evaluate-decision.mmd`](diagrams/evaluate-decision.mmd). Evaluation is a sync in-transaction step: it adds one insert to the caller's transaction and never calls the network, so nothing in this engine needs the Async Wait-State.

![Masked inputs: start-up check, evaluation and audit lookup](images/masked-inputs.svg)

Source: [`diagrams/masked-inputs.mmd`](diagrams/masked-inputs.mmd). **Masked inputs (RLE-012, RLE-035).** An input with `mask: true`, or one that declares nothing when the tenant masks by default, is written to `masked_inputs` as `hmac:<keyId>:<hex>`: the HMAC-SHA256, under the tenant's `MaskingKey`, of the value's plain form (a string as its UTF-8 bytes; a number as its digits without trailing zeros; money as `VND:50000000`; a boolean, date or datetime as its ISO text). A null value stays null. The token is `hmac:<keyId>:<hex>`; the key id is a host label, never the key. The key never leaves `MaskingKey` (no accessor, redacted `toString`). An output that reads a masked input is refused at import, or fails the decision closed when the input is masked only by the tenant default (`MASKED_INPUT_IN_OUTPUT`), so outputs never carry the plain value. Without a key the decision fails closed instead of logging plain text, and the start-up check refuses a host where that could happen. The lookup fingerprints the auditor's plain value with the caller's tenant key, so fingerprints of two tenants with different keys never match. Rows keep the fingerprint of the key in force when they were written and are never re-fingerprinted (the log is append-only); after a rotation the search also tries every earlier key the policy still returns (`searchKeys`), so they stay findable until that key is retired.

**Key check values (#281, security review of #255).** A key id alone cannot tell two keys apart, so every `MaskingKey` has a check value: `HMAC-SHA256(HKDF-SHA256(key, salt = none, info = "horus-bpm/masking/v1/kcv", 32), empty message)`, its first 8 bytes as 16 hex digits. The key is used for the derivation only, so the check value lives in a domain of its own and is never equal to a token, which is always an HMAC under the key or a tenant subkey (`info` prefix `.../tenant/`). It is not secret and reveals neither the key nor any token. At start-up, after the migrations and the key check of RLE-035, the engine (1) asks the policy for `configuredKeys()` (host keys as configured under scope `*`, a tenant's own keys under its id) and refuses to start when a key that `searchKeys` returns for a tenant with live rule sets is not declared there, as it is or as the tenant subkey of a declared host key; the message names the policy class, the tenant and the key id. The SPI default is empty, which only a policy without keys may keep; the starter logs a warning naming a host policy that declares none. (2) In one short transaction, in scope then key id order so replicas starting together lock rows in the same order, it inserts each check value into `rle_masking_key_check` with `ON CONFLICT DO NOTHING`, then reads it back. A recorded value that differs, compared in constant time, fails start-up. (3) In the same transaction, a host key id already recorded under a tenant, or a tenant key id already recorded under `*`, fails start-up, so `hmac:<keyId>:` never names two keys of one tenant over time; tenants may share an id among themselves, since tokens are searched within one tenant. A failure rolls the transaction back and names the scope, the key id and the check values, never the key. This holds across replicas sharing a database and over time, including for a retired id. Test vector (`TR_SEC_10_key_check_value_comes_from_a_key_derived_for_it_alone_and_never_the_key`): key `000102…1f` gives check value `471be5b6391b902f`.

**The masked-input search is a lookup of personal data, and with low-entropy identifiers also a test of whether a person was decided on.** It therefore needs its own permission, `rules:masked:search`, held by no role by default (the example host grants it to one operations role only); it is limited to `masking.search.max-per-minute` searches per caller per replica (default 30, at least 1, never off), answered `429 RATE_LIMITED` with `Retry-After` past it; and every search, refused or not, leaves one audit entry without the value (architect conditions (a) and (c) on #255). The limit is per replica: with N replicas a caller can reach N times the limit, which the audit trail shows.

**Tenant subkeys (architect ruling on #255).** The host key is never used directly. A tenant without a key of its own uses `HKDF-SHA256(IKM = host key, salt = none, info = "horus-bpm/masking/v1/tenant/" + tenantId, L = 32)` (RFC 5869, the JDK's `Mac` only), so tenants that share the host key never get the same token for one value. A key configured for one tenant (`masking.tenants.<tenant>.hmac-key`, or a `MaskingPolicy` bean) is used unchanged; a bean that shares one root key derives with `MaskingKey.forTenant`, as `MaskingPolicy.withKey` does. The subkey keeps the key id, so the token format stays `hmac:<keyId>:<hex>`; the `v1` in `info` is part of the contract and never changes for this format. The search derives the subkey of every key the tenant can use. Test vector (`TR_SEC_10_hkdf_matches_rfc_5869_and_the_documented_tenant_vector`): host key `000102…1f` (bytes 0 to 31), key id `k1`, value `079090001234`; tenant `bank-a` gives subkey `c8e45e83d5ee7574102467fccc635e7113045b1b05714cca14a9aab5f01c2ecb` and token `hmac:k1:e33165b4a825adb209efdafa2da3414acec8cc341b041985c539c537f69beeac`; tenant `bank-b` gives `hmac:k1:4edfc45a138147c7adcfee3a67777fc17e3ac39db08af486b31929448eb809d7`.

**Decided non-change: no input-level separation in the fingerprint.** The HMAC message is the value's plain form only, not the rule set or input, so the same person has one token across a tenant's rule sets and an auditor holding the tenant's key can compute a token from a plain value (`RLE-035`). Tenant separation comes from the tenant subkeys above, and the key id keeps rotation possible. Decided by the architect on #255.

- **Cache coherence (ADR-0009):** on `approve` and `retire` the same transaction increments `rle_rule_set.cache_epoch` and calls `pg_notify('horus_rle_cache', {tenantId, ruleSet, epoch})`, delivered after commit; the approving replica evicts its own entry in an after-commit callback. A listener (`CacheNotificationListener`, one pooled connection, `cache.listen-notify`) evicts the compiled entry; the epoch check every `cache.epoch-check-interval` (5 s, fixed rate, `CacheCoherence`) is the bound everywhere and the only path behind transaction-mode poolers (`TR-ASY-04`). The compiled store (`CompiledCache`) holds one entry per tenant and rule set: the version in force (the selection of section 3.3.1), the epoch it was read with and its window, read in one statement. The window runs from the latest start or retirement of any approved version at or before the evaluation instant to the next one after it, so the selection cannot change inside it; a chosen `clock` is served from the cache when the window holds it and reloads otherwise. A hit inside the window reads nothing; the window's end is the hand-over to a later effective version without any epoch change. A pinned evaluation and a replay never use this selection cache. An entry older than an epoch already notified is never installed, so a load that raced an approval cannot outlive it. LRU eviction at `cache.max-entries` (`TR-PERF-06`). A manual retire moves the epoch in its own transaction and evicts after commit, as approve does; the approve that supersedes a version moves it once for both; the lifecycle sweep moves it once per rule set it retires in.

Targets, not measurements, on the reference shape (one replica, 20-connection pool, PostgreSQL on 4 vCPU):

| Resource | Baseline | Cost model | 10x | Action |
|---|---|---|---|---|
| Evaluation CPU | 200-row `first` table, cached | 15 to 40 us p50, under 100 us p99 (`TR-PERF-01`) | Same per call; 10x rate is 10x CPU | Add replicas; no database cost |
| Pool, transactional log | 2,000 decisions/s per replica | 1.5 ms per insert = 3 busy connections | 20,000/s = 30 busy, above the pool | `decision-log.mode: async` for that path, or replicas |
| Decision log growth | 2,000/s at a sustained peak, about 1.2 KB per row | 207 GB/day at peak; size from the daily average | 2 TB/day | Monthly partitions; retention drops whole months |
| Monthly partition size | Average of 200 decisions/s over a month | 200 x 2.6 million s = 518 million rows x 1.2 KB = about 620 GB per partition | 6.2 TB per partition | The 200/s average is a planning input to confirm with pilots; the partition count stays 12 a year, and a dropped month frees its space at once |
| Regex work of one request | `:simulate` of 1,000 samples, both sides: 2,000 decisions | Without a request budget 2,000 x 5,000,000 = 10 billion characters read; with `evaluation.regex-max-reads-per-request` 50,000,000, a few nanoseconds a read, a fraction of a second of one thread | `:evaluate-batch` of 1,000 items is bounded by the same counter | Lower the setting for hosts that expose simulation widely; the stop is deterministic (`RLE-007`, #309) |
| Compiled cache | 2,000 entries x 75 KB unbounded = 150 MB | Bounded at `cache.max-entries` 1,000: 75 MB | 20,000 entries: still 75 MB | Eviction keeps the heap flat (`TR-PERF-06`) |
| Lifecycle sweep | 1,000 published versions across all tenants, every `PT1M` | One index-only scan of `idx_rle_version_published` (about 1,000 entries, under 5 ms) and normally zero rows updated | 10,000 published versions: about 20 ms a minute, 0.03 percent of one connection | None needed; the interval can be raised |
| Epoch sweep | 50 cached tenants, every 5 s | 10 indexed reads/s per replica | 500 tenants x 10 replicas: 1,000 reads/s | Sweep only tenants evaluated since the last sweep |
| `GET /decisions` | One rule set, at most 31 days, page 200 | `idx_rle_decision_lookup` range in at most 2 partitions | Same per request | ADR-0015 bounds |
| Partition `DROP` | Once a month | Inserts wait at most 2 s | Same | Run in the host's quiet hours with `decision-log.retention-window` if needed (open question 7.4) |

## 7. Open questions

| # | Question | Recommendation | Owner and gate |
|---|---|---|---|
| 7.1 | Which JSON library major does `*-core` use, given Spring Boot 4 defaults to Jackson 3 and 3.5 uses Jackson 2 (ADR-0011)? | **Decided** 2026-11-19 (PD-071 option 1, ADR-0021): Jackson 3 (`tools.jackson`) in `internal` only, as the core's only compile dependency. On a 3.5 host the engine brings Jackson 3 as a private dependency and the REST module reads and writes bodies with the engine's own mapper rather than the host's message converters (`RLE-023`, `TR-EMB-07`). No Jackson type appears in `api` or `spi` (ADR-0016). Condition: the Spring Boot 3.5 coexistence test passes in the first parsing pull request of Sprint 1; otherwise option 3 (no JSON library in the core, a parsed-tree port with one adapter per Jackson major) returns before that pull request merges | Closed; condition checked by the maintainers in Sprint 1 |
| 7.2 | Maven `groupId` and Java package root | **Decided** 2026-11-19 (PD-070 option 1, ADR-0022): `io.github.dhluong90.bpm` for the `groupId` and the Java package root; the rename in code lands before Sprint 1 code and the namespace is registered before the release slice of Sprint 3 | Closed; rename and registration by the maintainers |
| 7.3 | Default async outbox flush cadence: batch size 100 or 500 | Measure in the M1 JMH and Testcontainers runs | Maintainers, M1 exit |
| 7.4 | Should partition drops run only in a configured window? | Add `decision-log.retention-window` only if a pilot reports insert stalls | Architect, M2 |
| 7.5 | P1: adopt FEEL three-valued null logic in M1 (changes `RLE-006` for `!=`, `not` and `not in`) | **Decided** 2026-11-19 (PD-058 option 1): applied in section 3.2 (`RLE-006`, `RLE-021`, `TR-STD-02`) | Closed |
| 7.6 | P3: build `RuleSetDefinition` as the DMN-shaped model of ADR-0018 | **Decided** 2026-11-19 (PD-060 option 2): applied in section 3.1 (`RLE-024`, `TR-STD-28`) | Closed |

### Open design work from the delegated decisions of 2026-11-19

| # | Item | Plan | Owner and gate |
|---|---|---|---|
| 7.7 | Where the spreadsheet shorthand conversion runs (`GOLD, PLATINUM`, `>= 50000000 VND`, `18 to 65`) and the CSV header and quoting rules (PD-043, CK-F-35, CK-F-43) | Recommendation: in `rule-engine-core` behind a `ShorthandParser` used by import, the cockpit cell editor and the CSV reader, so one grammar and one message catalogue serve all three; CSV per RFC 4180, UTF-8 with BOM accepted, header row of input and output names | Architect, before CK-F-43 is sized (7 Dec 2026) |
| 7.8 | Reading contract UX-API-01: plain-language readings of rules in both styles, per language, from the same catalogue as explanations (PD-056, PD-077) | Endpoint `GET .../versions/{v}/readings?lang=` returning structured reading tokens and the rendered text; catalogue format shared with section 4.7 | Architect, M1 week 1 |
| 7.9 | Mapping of nested And, Or and Not groups of the builder lens to table rows, where a row is a conjunction of cells (PD-076, CK-F-45) | Disjunctive normal form expansion into rows, bounded by a row limit; a group that cannot be expressed in the table is refused with a plain message, never stored in a second format | Architect, before CK-F-45 is sized (M3) |
| 7.10 | Tenant purge against monthly partitions (PD-051, `TR-TEN-03`) | Per-partition `DELETE ... WHERE tenant_id` in batches under the four-eyes purge job, with the purge record in its own table; cost math before 1.1 planning | Architect, 1.1 planning |
| 7.11 | Partition sealing mechanism and its cost at 2,000 inserts per second (PD-052, `TR-OBS-09`) | Seal at partition close only (no insert-path cost): an ordered hash over `(created_at, id, row hash)`; `SealStore` extension point with a table default | Architect, 1.1 planning |
| 7.12 | Licence check of shipped artifacts separately from tools, and the allow-list file (PD-053, PD-064, `TR-LIC-01`) | Allow-list in `tools/`; CI inspects the dependency trees of shipped artifacts and of the cockpit bundle only | QA and architect, M1 |
| 7.13 | Deprecation annotations and the API compatibility check (PD-054, `TR-API-09`) | japicmp against the last release on `api` and `spi` from 0.2; `@Deprecated(since, forRemoval)` rule in ArchUnit | Maintainers, M2 |

### Design questions closed on 2026-11-19 for `RLE-025` to `RLE-036` and `CKP-030`

| # | Question | Decision | Where |
|---|---|---|---|
| Q1 | Diff path and shape | `GET /rule-sets/{name}/versions/{v}/diff?base={w}`; rows matched by rule id; `ADDED`, `REMOVED`, `CHANGED`, `MOVED`, where the moved set is the common rows outside the longest common subsequence, so inserts never move neighbours; changed and moved is `CHANGED` with `moved: true`; JSON Pointer field paths | 4.5, `RLE-028` |
| Q2 | Export path | `GET /rule-sets/{name}/versions/{v}/export?format=yaml\|json`, default `yaml`, other values `400 INPUT_INVALID`; `Accept` is not used | 4.6, `RLE-032` |
| Q3 | Is `effectiveFrom` in the canonical document | No. Version metadata in `effective_from`; removed from the canonical document and the hash at import; frozen at request-publish and shown to the approver; never exported. The round-trip hash holds | 3.1, `RLE-032` |
| Q4 | Who may retire a live version | Setting `lifecycle.retire-approval` (PD-080): `single` by default, one `rules:approve` holder with a mandatory reason; `four-eyes` from M3. In `single` mode, an event and a history row; no four-eyes, because a retire only stops behaviour; older versions are retired with it, so nothing comes back. Separate from the PD-012 emergency path, which is about publishing | 3.3, ADR-0025, `RLE-026` |
| Q5 | Self-reject | Superseded by PD-089 (19 Nov): the requester takes a request back with `:withdraw` (`rules:author`), and `:reject` by the requester is `403 SELF_APPROVAL_FORBIDDEN`, so the audit trail keeps "the author took it back" apart from "an approver refused it" | 3.3, 4.2, `RLE-025`, `RLE-046` |
| Q6 | Automatic retirement | Conditional `UPDATE ... RETURNING` at approval (when effective at once) and in a sweep every `lifecycle.sweep-interval`; exactly once through the row lock and the status predicate, no advisory or session lock (`TR-OPS-05`); actor `system:supersession`, reason `SUPERSEDED`; not at evaluation time | 3.3.2, ADR-0025, `RLE-027` |
| Q7 | Past-clock evaluation of a hand-retired version | Selection is time-aware through `retired_at`: a version is selected for clocks before its retirement and never after; replay uses the logged version | 3.3.1, `RLE-026` |
| Q8 | Rejection history | Append-only `rle_version_history`, one row per transition; `approval_comment` keeps only the latest approval | 3.3.3, 5 |
| Q9 | Explanation language and numbers | Structured `matched` and `reasons` are the contract; `text` is one English rendering; the cockpit renders `reasons` in English or Vietnamese. Numbers in `text` are locale-neutral (no grouping, `.` decimal, major units with currency code) | 4.7, `RLE-030` |
| Q10 | Explain earlier non-matching rows | No. Only matched rows; no re-evaluation, which masked inputs make impossible | 4.7, `RLE-031` |
| Q11 | `matched_rule_ids` on `UNIQUE_HIT_VIOLATION` | Yes, every matched row; `[]` for no match and `EVALUATION_ERROR` | 5, `RLE-031` |
| Q12 | Heap margin and metric name | Hard gates: entry count and evictions. Heap: retained heap after full GC at round 10 within 10 percent or 8 MB of round 2, whichever is larger, median of three measurements. Counter `horus.rle.cache.evictions`, Prometheus `horus_rle_cache_evictions_total` | 4.8, `RLE-034` |
| Q13 | `INTERNAL_ERROR` missing from the matrix | Added as an every-endpoint row in 4.2 | 4.2, `RLE-036` |
| Q14 | Masking key setting | `horus.bpm.rules.masking.hmac-key` (shared `horus.bpm.masking.hmac-key`), Base64, at least 32 bytes decoded | 4.4, `RLE-035` |

