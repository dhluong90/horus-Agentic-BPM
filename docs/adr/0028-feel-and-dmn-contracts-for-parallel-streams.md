# ADR-0028: FEEL and DMN contracts: five layers with one interface each, language ids that never change meaning

- Status: proposed
- Date: 2026-11-28
- Deciders: maintainers; the product owner approves
- Module: rule-engine, process-engine (`bpm-expression`)

## Context
The DMN TCK score is level 2 15/116 and level 3 0/3321. The FEEL `literalExpression` blocks 130 of the 150 models, so FEEL is the critical path. Six streams will build it from Sprint 2: the parser (#197), types and evaluator (#198), built-ins (#199), DMN XML (#200), decision tables and boxed expressions (#201) and the decision requirements graph (#202), with model imports (#217) after them. Without contracts they edit the same files and wait for each other: the evaluator needs a tree, the tree needs the parser, the DMN model needs both.

What exists in 1.0 and constrains the design:
- `bpm-expression` (ADR-0027) holds `ExpressionLanguage`, `CompiledExpression`, `EvaluationContext`, `TypeEnvironment`, `ValueType` (an enum of eight constants) and `Money`. Its internal `Expr` is a tree **bound to slots** (`Var(slot, name)`), with no source positions. `HorusExprV1` already offers a **FEEL dialect** (`Parser.parseFeelWithPaths`) over process variables, under the OMG FEEL URI, sharing that tree and the evaluator. Process definitions stored in 1.0 may carry that URI.
- `rule-engine-core` reads single decision tables (`dmn/ruleset`, with its own cell subset `Feel.java`) into `internal.model` (`Definitions`, one `Decision`, `DecisionTable`). Its hash covers the printed canonical model (ADR-0026), and decisions are replayed from their log entry (`TR-DET-05`).
- ADR-0019 says both dialects parse into one tree and that `rule-set.v1` refuses FEEL until a later schema version. The roadmap allows no breaking change before 2.0, and stored decisions must replay byte for byte.

## Decision

### 1. Five layers, one interface each
Each layer depends only on the interface of the layer above it. The stubs of this decision are on `main` before Sprint 2, one file per interface or record family, so no stream edits another's file.

| Layer | Interface (package) | Owner | Depends on | Reference test |
|---|---|---|---|---|
| Syntax | `FeelParser`, `FeelPrinter`, sealed `FeelNode` (`bpm-expression`, `expr.feel`) | #197 | the JDK; `FeelNameScope` supplied by the caller | Golden tree per grammar rule; `parse(print(t)) == t`; every FEEL text in the TCK models parses (parse-only count) |
| Types, values, evaluation | `FeelEngine`, `FeelCompiled`, `FeelType`, `FeelValues`, `FeelOptions`, `FeelOutcome` (`expr.feel`) | #198 | `FeelNode`, `FeelBuiltins` | TCK FEEL folders; differential test against the current language (`TR-STD-02`) |
| Built-ins | `FeelBuiltins`, `FeelBuiltins.Builtin`, `FeelFunction`, `FeelCall` (`expr.feel`) | #199 | `FeelType`, `FeelCall` only; never the parser or the engine | The specification's examples, one table per function; TCK built-in folders |
| DMN model and XML | `DmnModel` records, `DmnXmlReader`, `DmnXmlWriter` (`rules.internal.dmn`) | #200, #217 | nothing from FEEL: expressions stay text with a language | Every TCK model reads (count); read, write, read gives equal models; XML attacks refused |
| Tables, boxed expressions, graph | `BoxedExpressionCompiler`, `DmnEvaluator` (`rules.internal.dmn`) | #201, #202 | `DmnModel`, the `feel` interfaces | TCK level 2 (#201), level 3 (#202) |

Consequence for scheduling: the evaluator (#198) starts from hand-built `FeelNode` fixtures and does not wait for the parser; the built-ins (#199) need no tree at all; the DMN reader (#200) needs no FEEL. Only #201 and #202 integrate the others, and they do so through the interfaces.

### 2. Syntax tree and compiled tree are two trees
`FeelNode` is the parser output: names not slots, an `offset` on every node for line and column, one record per grammar production (literals, name and path, binary, between, in, instance of, if, for, quantified, list, context, function definition, invocation, filter, range, unary tests, type nodes). The existing slot-bound `Expr` stays the **compiled** tree of the S-FEEL profile. The compiler lowers a `FeelNode` to `Expr` when the whole tree is in the subset (unary tests, simple arithmetic, comparisons, names, paths) and otherwise runs the general profile as a walk over immutable frames. This amends ADR-0019 decision 2: the two dialects share the evaluator's semantics and the compiled tree, not a single syntax tree, because a slot-bound tree cannot carry positions, scopes or multi-word names. The profile is a pure function of the normal form, is an optimisation only and is outside the hash. Both profiles must give the same value on every input: one differential test over generated trees (`RLE-021` harness) is a gate of #198.

### 3. Values and types
`null` is Java `null`; there is no wrapper value type. The Java value of each FEEL type is fixed, so every stream and every host sees the same objects:

| FEEL type | Java value | Relation to what exists |
|---|---|---|
| number | `BigDecimal` in `DECIMAL128`, half-even | as `ExpressionValues`; `Double` and `Float` still refused |
| string, boolean | `String` (code-point order), `Boolean` | unchanged |
| date | `LocalDate` | unchanged |
| date and time | `LocalDateTime` (no offset), `OffsetDateTime`, `ZonedDateTime` | an incoming `Instant` is read as an `OffsetDateTime` at `Z`, the meaning ADR-0026 already gives the v1 `datetime`; v1 callers still receive `Instant` |
| time | `LocalTime`, `OffsetTime` | new |
| days and time duration, years and months duration | `Duration`, `Period` with zero days | new |
| list, context | immutable `List`, insertion-ordered `Map<String, Object>` | list as before; context is the S2 mapping of DESIGN 3.5 |
| range | `FeelRange(start, startIncluded, end, endIncluded)` | new; a null bound is open |
| function | `FeelFunction` | new; closures are pure and charged to the budget |
| money (HORUS extension) | `Money` | unchanged; `money(12.50, "USD")` builds it, `m.amount` is a number in major units, `m.currency` a string; arithmetic and rounding follow DESIGN 3.4 |

`FeelType` is a sealed root (any, null, scalar kinds including money, list, context, range, function, constrained item). The existing `ValueType` enum is **not extended**: a new constant would break exhaustive `switch` in hosts. A FEEL expression reports `ValueType.ANY` where `ValueType` has no name for the type, and the richer type through `FeelCompiled.feelResultType()`. Item definitions become `FeelType.Constrained`.

### 4. Evaluator interface, errors and null
- FEEL implements the existing `ExpressionLanguage` and `CompiledExpression`, so the Process Engine changes nothing. The DMN side uses `FeelEngine.compile(tree, names, builtins)` and `FeelCompiled.evaluate(context, FeelOptions)`, which returns a `FeelOutcome` (value and warnings).
- `FeelOptions` (error mode, zone, step budget, depth) belongs to the versioned decision model, so replay is deterministic. `EvaluationContext` is not changed (it is SPI): the clock stays in it, the rest travels in `FeelOptions` and `FeelCall`.
- **Null logic** is the three-valued logic already decided (PD-058, DESIGN 3.2 item 3); FEEL and the current language give the same result for every translatable expression (`TR-STD-02`).
- **Errors.** Recoverable errors (`FeelOptions.ErrorKind`: type mismatch, division by zero, unknown name, wrong arguments, invalid argument, invalid temporal, out of range, not a list) follow `errorMode`: `spec` gives `null` and a `FeelOutcome.Warning` with the node offset; `strict` throws `ExpressionEvaluationException` (`EVALUATION_ERROR`). **Guardrails never recover**, in either mode: step budget, recursion depth, regular-expression budget, input size and a **currency mismatch of money** (a silent `null` instead of a refused addition of 10 USD and 10 VND is not acceptable in banking). A guardrail throws and carries a reason code (`EVALUATION_BUDGET_EXCEEDED` and kin). Money is never converted implicitly. Conversion is only an explicit pure function that takes the rate as a stated input (`convert(money, "CUR", rate)`), and every result is rounded to the scale of its own currency (ISO 4217, DESIGN 3.4), never to a global scale. The mismatch rule is Decision needed 1, proposed and awaiting the product owner.
- FEEL calls nothing outside the JDK (no network, no I/O, no host callback except registered pure functions), so it may run inside a transaction without holding a lock on anything slow, and the transaction rules of the engines are unchanged.

### 5. Determinism rules (every stream, enforced by ArchUnit and review)
- The clock is `FeelCall.clock()`, supplied by the caller; `today()` and `now()` read it in `FeelOptions.zone`. No `Clock.system*`, `Instant.now`, `ZoneId.systemDefault`, `TimeZone.getDefault`.
- No default locale: no `Locale.getDefault`, no `toUpperCase()` or `toLowerCase()` without `Locale.ROOT`, no `String.format`, no `DecimalFormat`. Strings compare and measure by code point; `upper case` and `lower case` use `Locale.ROOT` and full Unicode mapping. No Unicode normalisation.
- No iteration order from a hash: contexts and registries are insertion- or name-ordered. `sort` uses the library's own stable merge sort, so a bad comparator fails the same way on every JVM.
- No reflection, no `javax.script`, no I/O; regular expressions only through `BoundedRegex`.
- The tz database version that the evaluation used is recorded with the decision (`TR-STD-23`).
- Two of these rules are enforced now by ArchUnit (`ExpressionArchitectureTest`); the rest are listed as a gate in each issue's definition of done.

### 6. FEEL beside the current syntax, with nothing broken
Language ids are contracts: **a stored id always resolves to the same semantics.**
- `urn:horus-bpm:expr:v1` is unchanged, and its rule sets and process definitions run on it forever.
- The OMG FEEL URIs and `feel`, as already shipped in 1.0 for gateway conditions and scripts over process variables, keep resolving to **that** dialect, frozen and named `feel-1.0-subset` in the code. Nothing stored under them changes behaviour. Its tests move unchanged.
- The full engine is selected only by a new id, `urn:horus-bpm:expr:feel:1` (HORUS FEEL, revision 1). A DMN XML import and a rule set of `rule-set` schema version 2 carry it; a new BPMN definition may select it. No stored version is ever migrated to it automatically. A host moves a decision model by publishing a new version under four-eyes.
- Until the 1.2 gate, the id is **preview**: accepted only when `horus.bpm.expression.feel.preview=true` (default false), otherwise refused by name with its stage (`TR-STD-04`, `RLE-095`). Nothing is stored under it by default, so nothing needs to replay.
- The `expr.feel` package is not part of the 1.x promise until the 1.2 gate (no `api` or `spi` type refers to it; ArchUnit checks). Before then a contract change is allowed but must be additive and labelled `contract-change`; after 1.2 the package is promoted to `api` and `spi` and japicmp guards it.

### 7. Hash and replay
- v1 documents: no change. Hashes, printers and parsers stay frozen by the golden files of ADR-0026 decision 3.
- v2 documents and DMN XML imports: cell and expression text is printed by `FeelPrinter`, a printer frozen per **language revision** by its own golden file (both directions, own SHA-256 pinned, coverage gate over every production, property tests `parse(print(t)) == t`), under the same CODEOWNERS rule as ADR-0026. `expressionLanguage` is part of the canonical document and the hash, as today. `errorMode` follows DESIGN 4.11: `strict` is omitted from the canonical form, `spec` is printed.
- **A published revision never changes behaviour.** Once `urn:horus-bpm:expr:feel:1` ships in a release, any change to a result or to an error of an input that is accepted today (including a corrected wrong value) goes in a new revision id `:feel:2`. The engine keeps revisions as flags on one implementation, not copies. Moving a decision model from one revision to another is a new version of that model: the request carries a reason code, and it is approved by a second person (four eyes) like any other change; nothing moves automatically, and the audit entry records the old and new revision. At most two revisions are live per major version; 2.0 may retire the oldest with the migration notes. This, not "fix the bug", is what keeps stored decisions replayable.
- A **replay corpus** pins this: CI keeps decisions (model, inputs, clock, options, result and warnings) for each revision and checks they evaluate to the same bytes on JDK 21 and 25. The compiled and general profiles are compared on the same corpus.
- The decision log keeps the language id, the error mode, the zone and the tz database version per decision, so a replay selects the right revision and settings.

### 8. DMN model and the single-table path
`DmnModel` is the full DMN 1.5 semantic model (records only, expressions as text and language, diagram shapes kept as read). `rules.internal.model.Definitions` stays the single-table model of schema version 1, and the v1 reader still produces it. A v1 rule set lowers to `DmnModel` with one decision for #201 to evaluate, as ADR-0018 requires, but the stored v1 document and its hash never pass through `DmnModel`. Java and PMML functions parse into `FunctionDefinition.Kind` and are refused by name at import (`422 DMN_EXTERNAL_FUNCTION_UNSUPPORTED`).

### 9. Working rules for the parallel streams
- A stream edits only its own files and its own tests. A change to a contract file (anything in `expr.feel` or `dmn.model` or an interface above) is a separate small pull request labelled `contract-change`, reviewed by the architect, additive only, merged the same day, and the streams merge `main` into their branch.
- A stream that needs something from another stream builds against the interface with a fixture; it never copies the other stream's internals.
- Each stream reports its TCK numbers in the daily report as counts, and claims no conformance before the suite passes (`TR-STD-05`).

### 10. Definition of done per issue
| Issue | Done when |
|---|---|
| #195 | Contracts and stubs on `main`; every stub fails by name and issue; the current language passes its existing tests unchanged; ArchUnit rules for determinism and no leak into `api` and `spi` pass; security review of step budget, error mode and regular-expression seam recorded |
| #197 | Every grammar production has a golden test (coverage gate); `parse(print(t)) == t` property test; 64 KB and depth 64 refused without excessive backtracking; multi-word names resolved by scope; every TCK FEEL text parses (count reported); `FeelPrinter` golden file pinned |
| #198 | The named requirements of section 12 for the evaluator; value and type table implemented and covered; null logic, error kinds and guardrails per section 4; differential test against the current language on translatable expressions; compiled and general profiles agree; step budget and depth counted, not timed; TCK FEEL folders reported as a count |
| #199 | The named requirements of section 12 for built-ins; every function named in DMN 1.5 has its specification examples as a table-driven test; `FeelBuiltins` resolves by specification name including spaces; regular-expression functions stopped on catastrophic patterns; no `FeelType` or engine import beyond the contract; counts per folder reported |
| #200 | Every TCK model reads (count); read, write, read gives an equal model; entity expansion, external entities and DTDs refused; Java and PMML refused by name with id and line; diagram shapes kept |
| #201 | All eleven hit policies with unit tests including priority ties; input and output values; the six boxed kinds; TCK level 2 as a count in the daily report; `DmnEvaluator` not required |
| #202 | Graph compiled with cycle refusal; each requirement kind has a unit test; knowledge models as functions; decision services callable by name; TCK level 3 as a count |
| #217 | Imports resolved by namespace and location with a size and depth bound, no network or file path outside the supplied resolver; diagram information kept for every element; level 3 import folders at 100 percent except listed exclusions |

### 11. Order of the slices
S2: #197 slice 1 (literals, paths, operators), #201 hit policies R and A. S3: #197 slice 2 (full 1.5 grammar), #201 P and O. S4: #198 slice 1 (types, operators, null logic, compiled profile), #201 default output entry and first boxed kinds. S5: #198 slice 2 (general profile: if, for, quantified, filter, functions). S6: #198 Money, dates, durations; #199 string, number, boolean; #201 context and relation; #202 graphs. S7: #198 context, list, range; #199 list, context, date and time; #202 services; #200 read. S8: #199 complete; #198 FEEL beside the current syntax (selectable per version, nothing migrated); #201 complete; #200 write; #217 slice 1. The contracts allow #198 slice 1, #199 and #200 to start at S2 or S3; the architect recommends moving them forward, because the critical path then no longer waits for the parser, and the plan's kill criterion (level 2 below 60/116 at the end of S8) has more room.

### 12. Named requirements for the built-ins and evaluator slices
Added at the architect's review. Each is a named acceptance criterion of #199 or #198 (posted on those issues as comments; no new issues), with specification examples as tests:
- **Business-calendar date arithmetic** (#199): add and subtract business days, next and previous business day, and business days between, over a calendar supplied as an input (the tenant calendar of #125 or a list given by the caller). The engine ships no holiday data and never reads a calendar from the system.
- **Explicit rounding** (#198, #199): a function with a stated mode and scale, default `HALF_EVEN`; the modes are those of `java.math.RoundingMode`. No operation rounds without saying so, except money arithmetic, which rounds to the currency scale with the model's `moneyRounding` (DESIGN 3.4).
- **Percentages and basis points** (#199): helper functions for percent of an amount and basis points of an amount, exact in `DECIMAL128`, rounded only through the explicit rounding function.
- **Day-count conventions** (#199): year fraction and day count for ACT/360, ACT/365 and 30/360, each with worked examples from its definition, as pure functions of two dates.
- **Money safety** (#198): the cross-currency refusal of section 4 for comparison, `min`, `max` and `sum`, and explicit conversion with a stated rate.

## Options considered
1. **Separate syntax tree, shared semantics, one interface per layer (chosen).** Parallel streams with fixtures; positions and scopes in the syntax tree; the existing compiled path keeps its speed and its tests. Against: two trees and a lowering step to keep equal, paid for by the differential test.
2. **Extend the existing `Expr` with FEEL nodes (one tree, ADR-0019 as written).** No lowering. But a slot-bound tree has no positions, no scope for multi-word names and no place for contexts or closures, and every FEEL change touches the file the v1 path uses, so the streams collide and the v1 hashes are at risk.
3. **Reuse the OMG FEEL URI for the full engine.** No new id. But stored process definitions already use that URI under the 1.0 dialect; changing what it means breaks replay, which no release before 2.0 may do.
4. **A wrapper value hierarchy (`FeelValue`).** Exhaustive `switch` and a place for null and error values. But every host value converts twice, `Money` and the process engine's variables need adapters, and the object count grows on the hot path. Java natural types with the mapping above cost nothing and match `ExpressionValues`.
5. **Put the contracts in `api` and `spi` now.** Hosts could use them at once. But anything there is frozen additive from 1.0, and FEEL will need contract changes before the TCK passes. A preview package with a stated promotion date costs nothing and keeps 1.x additive.
6. **Fix bugs in place and rely on re-evaluation tests.** Simpler code. But a corrected value changes a stored decision's replay; revisions as flags (chosen) keep the bug-compatible behaviour and cost a flag per fix.

## Consequences
- ADR-0019 decision 2 is amended as in section 2 of this ADR; its other decisions stand. ADR-0026 gains a printer for each FEEL revision under the same rules. ADR-0027 is unchanged.
- The rule engine DESIGN gets section 3.6 (this ADR in short, with the diagram). The process engine DESIGN is not changed in this pull request.
- New requirement text needed in the next requirements update: language revisions never change behaviour (`TR-STD-30`), and the replay corpus (`TR-STD-31`). Until then they are carried by this ADR.
- Cost: a lowering and a differential test; at most two live revisions per major; the replay corpus grows with each revision.
- Security review covers the step budget, the error mode, the regular-expression seam and the money mismatch guardrail. Decision needed 1 is part of it.

## Decisions needed (product owner)
1. (Proposed, awaiting product owner.) **A currency mismatch of money is a guardrail** (fails in both error modes) and is not turned into `null` in `spec` mode. Recommended. The alternative gives literal FEEL behaviour for money, which has no FEEL meaning, and risks a silent wrong total.
2. (Proposed, awaiting product owner.) **The FEEL id stays preview until the 1.2 gate** and is off by default. Recommended. The alternative stores decisions under it earlier and freezes revision 1 earlier, before the TCK has found its first bugs.
