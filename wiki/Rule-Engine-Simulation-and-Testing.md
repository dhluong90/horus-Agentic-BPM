# How to simulate a draft and test rule sets

Check what a draft would decide before anyone approves it, and keep rule sets covered by unit tests in CI.

Design preview: simulation against sample inputs and the test library describe the 0.1 target; replay against decision history is a Cockpit 0.2 target, and attaching simulation reports to publication requests is a 1.0 target. Milestone scope is owned by the [[Roadmap]].

## Before you start

- A draft version exists (see [[Rule Engine Versioning and Publishing]]).
- For simulation by API you need the `rules:simulate` permission ([[Rule Engine Configuration]]).
- Simulation never writes to the decision log. The same inputs through the normal evaluate call would.

## Simulate a draft against sample inputs

![Simulation versus evaluate](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/simulation-vs-evaluate.svg)

In the cockpit: open the rule set, choose the draft, open Simulation, paste rows or upload a CSV with one column per input, run. The screen shows outputs and matched rule ids per row.

By API, one row at a time:

```bash
curl -s -X POST http://localhost:8080/api/bpm/rules/v1/decisions/card-fee/versions/3:evaluate \
  -H 'Content-Type: application/json' \
  -d '{ "inputs": { "tier": "GOLD", "balance": { "currency": "VND", "amount": 60000000 }, "txCount": 3 } }'
```

Expected: `200` with `"version": 3`, `"matchedRuleIds": ["R2"]`, `"outputs": { "fee": { "currency": "VND", "amount": 0 }, "waived": true }`. No row is added to the decision log.

## Simulate a future-dated version

Add `"clock": "2027-01-01T00:00:00Z"` to the request body. `today()` and `now()` in the expressions use that instant, and the effective-version lookup uses it too. `clock` is accepted only with `rules:simulate`.

## Replay history through a draft (Cockpit 0.2 target)

In the cockpit Simulation screen choose "Replay last N decisions". The engine evaluates the last N decisions of the effective version through the draft and reports how many outcomes change, grouped by output value, for example `18 of 100 changed: fee 30000 VND to 0 VND`. This is the pre-publication check for a fee or eligibility change.

## Unit-test a rule set

`rule-engine-test` loads a YAML file in memory. No database, no Spring context.

```java
@RuleSetTest("rules/card-fee.yaml")
class CardFeeRulesTest {

  @Test
  void gold_with_high_balance_is_waived(RuleSetUnderTest rules) {
    var r = rules.evaluate(Map.of("tier", "GOLD", "balance", Money.of("VND", 60_000_000), "txCount", 3));
    assertThat(r.matchedRuleIds()).containsExactly("R2");
    assertThat(r.output("waived", Boolean.class)).isTrue();
  }
}
```

Name each test after the row or behaviour it proves so the coverage report can map tests to rows. The test extension runs with a fixed clock, so tests of future-dated rules are repeatable (see `*-test` in [[Architecture]]).

## Report row coverage in CI

```bash
./mvnw rules:coverage
```

Expected output lists each rule set with rows never matched by any test, for example `card-fee: R4 not covered`. An uncovered row is a warning; in a `unique` table it fails the build.

## Rely on determinism

Given the same version, inputs and clock, outputs and matched rule ids are identical on every run and every JVM (TR-DET-01 in [[Technical Requirements]]). A test that observes otherwise has found a bug; report it as described in [[Contributing]].

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `403` on `versions/{v}:evaluate` for a draft | Caller lacks `rules:simulate` | Grant the permission to the simulation role |
| `403` when sending `clock` | `clock` requires `rules:simulate` even on a published version | Same |
| Simulation rows appear in the Decisions screen | The call went to `/decisions/{ruleSet}:evaluate` instead of the versioned path | Use `/versions/{v}:evaluate` for drafts |
| `rules:coverage` reports a row you believe is tested | The test matches a different row first under `first` | Check `matchedRuleIds` in the test; reorder rows or adjust inputs |
| Replay shows 0 changed outcomes for a changed row | The history window contains no decisions that reach that row | Increase N or add sample inputs that hit the row |
