# bpm-expression

Money, the Java value mapping and the expression language shared by the Process Engine and the Rule Engine (ADR-0027). It depends on the JDK only: no storage, no Spring, no JSON library.

| Package | Contents | Promise |
|---|---|---|
| `io.github.dhluong90.bpm.expr.api` | `Money`, `ExpressionValues` (the value mapping), `ExpressionInvalidException`, `ExpressionEvaluationException` | Public API |
| `io.github.dhluong90.bpm.expr.spi` | `ExpressionLanguage`, `CompiledExpression`, `TypeEnvironment`, `EvaluationContext`, `ValueType`, `EvaluationProfile` | Extension point |
| `io.github.dhluong90.bpm.expr.internal` | The `urn:horus-bpm:expr:v1` parser, tree, evaluator and bounded regular expressions, and `HorusExprV1`, the language over them | None; for the two engines only |

Hosts do not add this artifact themselves: both engine cores depend on it, and `bpm-bom` pins its version.

## Example

```java
ExpressionLanguage language = new HorusExprV1();
CompiledExpression senior = language.compile("amount >= 100000000 VND and customer.tier != 'GOLD'",
        TypeEnvironment.untyped());
boolean toSenior = senior.holds(EvaluationContext.of(
        Map.of("amount", Money.ofMinor("VND", 150_000_000), "customer", Map.of("tier", "SILVER")), clock.instant()));
// true
```

A declared environment (`TypeEnvironment.declared(Map.of("income", ValueType.MONEY))`) refuses unknown names at compile time, as a rule set does. An untyped one accepts any name or dotted path and refuses a value of the wrong type for an operator at evaluation, as a process condition does. `today()` and `now()` read the clock of the `EvaluationContext`; there is no system clock.

## Build

`mvn -pl bpm-expression verify` runs the unit and ArchUnit tests. No configuration, no environment variables.
