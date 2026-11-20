# How to simulate a draft and test rule sets

Check what a draft would decide before anyone approves it, and keep rule sets covered by unit tests in CI.

Simulation against sample inputs over REST and the Java API is built (0.2.0). The cockpit Simulation screen and the test library describe the 0.1 target; replay against decision history is a Cockpit 0.2 target, and attaching simulation reports to publication requests is a 1.0 target. Milestone scope is owned by the [[Roadmap]].

## Before you start

- A draft version exists (see [[Rule Engine Versioning and Publishing]]).
- For simulation by API you need the `rules:simulate` permission ([[Rule Engine Configuration]]). An approver needs it too, to see what a change does before approving it.
- Simulation never writes anything: no decision row, no idempotency record, no event. The same inputs through the normal evaluate call would log decisions.

## Simulate a draft against sample inputs

![Simulation versus evaluate](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/simulation-vs-evaluate.svg)

In the cockpit (Sprint 2): open the rule set, choose the draft, open Simulation, paste rows or upload a CSV with one column per input, run. The screen shows outputs and matched rule ids per row.

By API, up to 1,000 customers at once (`simulation.max-inputs`), each run through the draft and through the version in force today. Here `card-fee` v1 is in force and v2 lowers the default fee from 50,000 VND to 30,000 VND:

```bash
curl -s -X POST http://localhost:8080/api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate \
  -H 'Content-Type: application/json' \
  -d '{ "samples": [
        { "inputs": { "tier": "GOLD",     "txCount": 5 } },
        { "inputs": { "tier": "SILVER",   "txCount": 4 } },
        { "inputs": { "tier": "SILVER",   "txCount": 3 } },
        { "inputs": { "tier": "SILVER",   "txCount": 0 } },
        { "inputs": { "tier": "STANDARD", "txCount": 1 } } ] }'
```

Expected: `200` with `"version": 2`, `"baselineVersion": 1`, `"sampleCount": 5` and `"changedCount": 2`. Each entry of `results` has the sample's `index`, `changed`, and for `simulated` (v2) and `baseline` (v1) the `outcome`, `matchedRuleIds` and `outputs`:

```json
{ "index": 3, "changed": true,
  "simulated": { "outcome": "MATCHED", "matchedRuleIds": ["R3"], "outputs": { "fee": { "currency": "VND", "amount": 30000 } } },
  "baseline":  { "outcome": "MATCHED", "matchedRuleIds": ["R3"], "outputs": { "fee": { "currency": "VND", "amount": 50000 } } } }
```

A sample is `changed` when the outcome kind, an output value or the failure code differs. A different matched row that gives the same fee is not flagged; both rule lists are shown. When nothing is in force yet, `baseline` is `null` and every sample is `changed`. The same draft, samples and clock always give the same response. Before any sample runs, the version is checked against its stored hash and compiled; a failure is `422 VALIDATION_FAILED`. In Java: `ruleEngine.simulate("card-fee", 2, samples)` returns a `SimulationResult`.

One row at a time through the versioned evaluate (planned):

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
    var r = rules.evaluate(Map.of("tier", "GOLD", "balance", Money.ofMinor("VND", 60_000_000), "txCount", 3));
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
| `403 PERMISSION_DENIED` on `:simulate` | Caller lacks `rules:simulate`; nothing was evaluated | Grant the permission to the author and approver roles |
| `404 VERSION_NOT_FOUND` on `:simulate` | The version does not exist in your tenant (another tenant's version gives the same answer) | Check the rule set name, the version number and the signed-in tenant |
| `400 INPUT_INVALID` naming `samples[3]` | That sample has an input the version does not declare, a value of the wrong type, or a number out of bounds; or there are no samples or more than `simulation.max-inputs` | Fix that sample, or split the samples |
| `409 INVALID_STATE` on `/decisions/{ruleSet}/versions/{v}:evaluate` for a draft | A pinned evaluation is logged, so it accepts only approved versions (`PUBLISHED` or `RETIRED`); a draft never enters the decision log | Simulate the draft with `POST /rule-sets/{name}/versions/{v}:simulate`, which writes no decision row |
| `403 CLOCK_NOT_ALLOWED` when sending `clock` | `clock` requires `rules:simulate`, on `:evaluate` and on `/versions/{v}:evaluate` alike | Grant the permission to the simulation role, or leave `clock` out to use the host clock |
| Simulation rows appear in the Decisions screen | The call went to an `:evaluate` endpoint, which logs every decision | Use `:simulate` for drafts and what-if runs; it writes no decision row |
| `rules:coverage` reports a row you believe is tested | The test matches a different row first under `first` | Check `matchedRuleIds` in the test; reorder rows or adjust inputs |
| Replay shows 0 changed outcomes for a changed row | The history window contains no decisions that reach that row | Increase N or add sample inputs that hit the row |
