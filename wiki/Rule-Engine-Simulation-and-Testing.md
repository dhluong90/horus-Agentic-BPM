# Simulation and Testing

## Simulation (business users, in the cockpit or by API)
- **Against sample inputs**: paste or upload a CSV of inputs; see outputs and matched rules per row for a draft version.
- **Against history**: replay the last N decisions of the currently effective version through the draft; see how many outcomes change, grouped by output value. This is the pre-publish check for a fee or eligibility change.
- **With a clock**: set `clock` to see what a future-dated version would decide.
Simulation never writes to the decision log; it writes a simulation report that is attached to the publish request.

## Unit testing (engineers)
```java
@RuleSetTest("rules/card-fee.yaml")
class CardFeeRulesTest {
  @Test void gold_with_high_balance_is_waived(RuleSetUnderTest rules) {
    var r = rules.evaluate(Map.of("tier", "GOLD", "balance", Money.of("VND", 60_000_000), "txCount", 3));
    assertThat(r.matchedRuleIds()).containsExactly("R2");
    assertThat(r.output("waived", Boolean.class)).isTrue();
  }
}
```
`rule-engine-test` loads a YAML file in memory with no database. Name tests by rule id or rule card id so coverage can be reported.

## Coverage report
`rules:coverage` (Maven/Gradle task) runs the test suite and reports rows never matched by any test. Unmatched rows are a warning in CI; in `unique` tables they are an error.

## Contract: evaluation is deterministic
Given the same version, inputs and clock, outputs are identical. A test that asserts otherwise has found a bug.
