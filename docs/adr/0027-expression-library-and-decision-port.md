# ADR-0027: Expressions and value types in their own library; the Process Engine reaches the Rule Engine through a decision port

- Status: accepted
- Date: 2026-11-19
- Deciders: maintainers
- Module: project, rule-engine, process-engine

## Context
The first Process Engine slice (pull request #149) depends on `rule-engine-core` for three different things:
1. `Money` and the value types, used by conditions and by the instance variable JSON;
2. the expression language, which is the only way to compile gateway conditions (process engine DESIGN section 2; the `ExpressionLanguage` SPI of rule engine DESIGN section 3.5);
3. `RuleEngine.evaluate`, used by `businessRuleTask`.

As built, a host that wants processes and no decision tables still pulls in the whole Rule Engine core: its lifecycle, its storage ports and its compiler. A host that has no Rule Engine schema cannot mark the dependency optional, because the first condition would fail at run time. ADR-0019 placed FEEL in `rule-engine-core`, so the same coupling would grow with every stage of the standards work. The coordinates of 0.1.0 are about to be published, and the package rename (#21, ADR-0022) already moves every package once.

## Decision
1. **New library `bpm-expression`** (artifact `io.github.dhluong90.bpm:bpm-expression`, packages `io.github.dhluong90.bpm.expr.api`, `.spi` and `.internal`). It holds:
   - `Money` and the Java value mapping of rule engine DESIGN section 3.5;
   - the `ExpressionLanguage`, `CompiledExpression`, `TypeEnvironment` and `EvaluationContext` SPI;
   - the `horus-expr-v1` parser, type checker and evaluator, and from S1 the HORUS FEEL engine (ADR-0019 decision 1 moves here unchanged: HORUS-built, the same tree, the same rules).

   It depends on the JDK only. It has no storage and no Spring, and ArchUnit rules equal to those of `rule-engine-core`.
2. **`rule-engine-core` depends on `bpm-expression`.** Decision tables, the lifecycle and the decision log stay in the Rule Engine.
3. **`process-engine-core` depends on `bpm-expression`, and not on `rule-engine-core`.** A `businessRuleTask` calls a `DecisionPort` in the process core:

   `DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef, Map<String, Object> inputs, Instant evaluationTime, String idempotencyKey)`

   The result is sealed: matched, no match, or failed. The port's rules:
   - **Tenant.** Explicit, read from `pre_instance.tenant_id` under the instance lock, never from variables, inputs or the thread's security context.
   - **Caller.** The engine identity `system:process-engine`, acting on behalf of the human who triggered the step (`onBehalfOf`, which is null for a timer or poller). Both are written to `rle_decision`, so the log stays attributable without granting `rules:evaluate` to task workers.
   - **Idempotency key.** Derived from the instance id, the node id and the attempt id, so a retried step replays the logged decision instead of writing a second one.
   - **Evaluation time.** The engine clock at the step, never a value taken from instance data.
   - **The adapter.** The process starter provides it when a `RuleEngine` bean is present (`@ConditionalOnBean`). It calls a tenant-scoped Rule Engine entry point. When a request context exists, it asserts that the `EngineAccess` tenant equals the instance tenant, and refuses with `403 TENANT_NOT_ALLOWED` otherwise.
   - **No Rule Engine.** Activating a definition that contains a `businessRuleTask` is refused with `422 BPMN_NOT_EXECUTABLE`, naming the step and the reason, and nothing fails at run time. The starter declares `rule-engine-core` as an optional dependency.
   - **Tests.** A decision step evaluated with no principal uses the instance tenant (`PRE-046`). A tenant mismatch between the request and the instance is refused (`PRE-047`).
4. **Timing.** The extraction is the first change of 0.2.0, documented in the 0.2.0 release notes as a breaking change of a 0.x release (moved packages for `Money` and the expression types). The package rename (#21, pull request #155) lands at the 0.1.0 freeze without it, so that a structural move is not on the release's critical path (decided 19 Nov in the review of #155). 0.1.0 publishes these types under `io.github.dhluong90.bpm.rules`.
5. **Until then.** Pull request #149 may keep its `rule-engine-core` dependency and its internal `ExpressionEvaluator` port. That port's condition subset must follow the `horus-expr-v1` semantics already decided: FEEL null logic (PD-058), `DECIMAL128` numbers, and money compared only within one currency. Tests named by the rule engine ids prove it (`RLE-006` cases for conditions). Once this library exists, the port is replaced by the `ExpressionLanguage` SPI.

## Options considered
1. **Shared expression library and a decision port (chosen).**
   - For: each engine is usable alone. Conditions, mappings, timers and script tasks have one implementation, and FEEL serves both engines without the Process Engine pulling in decision-table storage. The coupling to decisions is one small port.
   - Against: one more artifact to version and release. ADR-0019's module placement changes. The conformance work breakdown's FEEL packages move from `rules.internal.feel` to `expr.internal.feel`.
2. **Optional port only, with a process-side money type.**
   - For: no new artifact.
   - Against: two money types and two value mappings, converted at every decision call. Conditions still need an expression engine, so the Process Engine would either carry a second evaluator or still depend on the Rule Engine. That contradicts the single expression language of ADR-0019.
3. **Keep the hard dependency.**
   - For: no work.
   - Against: every process host carries the Rule Engine core. The dependency cannot be made optional. FEEL growth enlarges the coupling, and the coordinates published in 0.1.0 would bake it in.

## Consequences
- ADR-0019 decision 1: "in `rule-engine-core`" reads "in `bpm-expression`"; nothing else in ADR-0019 changes.
- Rule engine DESIGN sections 2 and 3.5 and process engine DESIGN section 2 list the new module. The BOM lists it. The licence and dependency gates cover it.
- The `ExpressionLanguage` SPI contract (conformance plan, contract C-R) is written in `bpm-expression` from the start.
- The extraction gets its own issue for 0.2.0. #21 (pull request #155) does not carry it.
- `businessRuleTask` behaviour is unchanged for a host that has both engines. A host with processes only can now deploy definitions without decisions.
