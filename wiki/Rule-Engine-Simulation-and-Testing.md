# How to simulate a draft and test rule sets

Check what a draft would decide before anyone approves it, and keep rule sets covered by unit tests in CI.

Simulation against sample inputs over REST and the Java API is built (0.2.0), and so is replaying logged decisions through a draft. So is the cockpit's Simulate view of a draft; the test library describes the 0.1 target; the cockpit replay screen is a Cockpit 0.2 target, and attaching simulation reports to publication requests is a 1.0 target. Milestone scope is owned by the [[Roadmap]].

## Before you start

- A draft version exists (see [[Rule Engine Versioning and Publishing]]).
- For simulation by API you need the `rules:simulate` permission ([[Rule Engine Configuration]]). An approver needs it too, to see what a change does before approving it.
- Simulation never writes anything: no decision row, no idempotency record, no event. The same inputs through the normal evaluate call would log decisions.

## Simulate a draft against sample inputs

![Simulation versus evaluate](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/simulation-vs-evaluate.svg)

In the cockpit: open the rule set, choose the draft and open Simulate. Type a few samples, or paste rows from a spreadsheet or CSV with an optional name and then one column per input, and choose Run simulation. The cockpit sends them in one `:simulate-readings` call, so nothing is logged. The screen says how many results change and shows each sample by its reference (`S1`, `S2`) and name, with the draft's outcome beside the version in effect, read in plain words by the engine. A masked input is typed as dots and never shown in a result; more than 500 samples reads "Run fewer samples".

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

Simulation is the only way to run a draft. The pinned evaluate, `POST /decisions/{ruleSet}/versions/{v}:evaluate` (`rules:pin`), logs a decision like any evaluate, so it accepts approved versions only: a draft or a version pending approval is `409 INVALID_STATE`, and so is a `RETIRED` version unless `evaluation.pin-retired` is `true`.

### After approval

Approving v2 with an `effectiveFrom` at or before now puts it in force and retires v1 as superseded in the same transaction. From then on `:simulate` compares any version with v2, and v1 can still be simulated, because simulation reads a version in any status and logs nothing. A pinned evaluate of v1 is refused, as above.

## Simulate a future-dated version

`:simulate` takes no `clock`: the baseline is the version in force at the host clock. To see what a version scheduled for a later date decides on that date, add `"clock": "2027-01-01T00:00:00Z"` to an `:evaluate` request. `today()` and `now()` in the expressions use that instant, and the version lookup uses it too. `clock` needs `rules:simulate` (`403 CLOCK_NOT_ALLOWED` without it), and that decision is logged.

## Replay history through a draft

Sample customers miss edge cases. Replay last month's real decisions through the draft and see whose outcome would change. You need `rules:simulate` and `rules:log:read`, because the answer is built from logged inputs.

```bash
curl -s -X POST http://localhost:8080/api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate-history \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z","limit":100}'
```

```json
{"ruleSet":"card-fee","version":2,"status":"DRAFT","decisionCount":100,"replayedCount":100,"changedCount":18,
 "notReplayableCount":0,"truncated":false,"results":[{"decisionId":"01KB2R6Q3C0000000000000001","loggedVersion":1,
 "evalClock":"2026-11-29T22:00:00Z","changed":true,"maskedInputs":[],
 "simulated":{"outcome":"MATCHED","matchedRuleIds":["R3"],"outputs":{"fee":{"currency":"VND","amount":30000}}},
 "logged":{"outcome":"MATCHED","matchedRuleIds":["R3"],"outputs":{"fee":{"currency":"VND","amount":50000}}}}]}
```

- **Both sides at the decision's own clock.** Each decision is evaluated with the version it was made with and with the draft, both at its logged clock, so `today()` is the day of the decision. The logged side is what was decided.
- **Nothing is written.** No decision row, idempotency record or event; the decision log count is the same afterwards. The response holds no logged input.
- **Masked inputs.** A decision whose masked input had a value cannot be evaluated again. It is listed with `maskedInputs` naming the inputs, never their values, and counted in `notReplayableCount`; the run goes on.
- **Bounds.** The period is at most `query.max-range` (31 days) and the run at most `simulation.max-inputs` (1,000) decisions, newest first. `truncated` says the period held more. All the decisions of one run share one regular expression budget (`evaluation.regex-max-reads-per-request`).
- **Tenancy.** The tenant is the signed-in one. Another tenant's version is `404 VERSION_NOT_FOUND`, and its decisions are never read.

The Java API is `ruleEngine.simulateHistory("card-fee", 2, HistorySimulation.between(from, to).withLimit(100))`.

### Read the replay in plain language

`POST .../versions/2:simulate-history-readings` takes the same body and runs the same replay, then reads each decision as a sentence, so a screen shows "fee was 50,000 VND, would be 30,000 VND" without building the text itself. It needs `rules:simulate`, `rules:log:read` and `rules:read`, all checked before the body is read. Add `decisionIds` (up to 1,000) to replay only those decisions of the period; an id that is not your tenant's decision of this rule set in the period is left out, and a decision with a masked value is still listed as not replayable.

```json
{"ruleSet":"card-fee","version":2,"decisionCount":2,"replayedCount":1,"changedCount":1,"notReplayableCount":1,
 "languages":["en","vi"],"results":[
  {"decisionId":"01KB2R6Q3C0000000000000001","loggedVersion":1,"changed":true,"code":null,"maskedInputs":[],
   "simulated":{"kind":"MATCHED","ruleIds":["R2"],"readings":{"en":{"full":"Rule R2 always applies; it has no condition. Result: fee is 30,000 VND.","short":"R2: fee 30,000 VND","source":"GENERATED"}}},
   "logged":{"kind":"MATCHED","ruleIds":["R2"],"readings":{"en":{"full":"Rule R2 always applies; it has no condition. Result: fee is 50,000 VND.","short":"R2: fee 50,000 VND","source":"GENERATED"}}}},
  {"decisionId":"01KB2R6Q3C0000000000000000","loggedVersion":1,"changed":false,"code":"REPLAY_NOT_POSSIBLE",
   "maskedInputs":["nationalId"],"simulated":null,"logged":null}]}
```

- **Each side is read in its own version.** The logged outcome in the version that made the decision, the simulated outcome in the draft, each as a `full` sentence and a `short` form in every language of the message catalogue (`en` and `vi` ship).
- **A decision that cannot be replayed fails closed.** A masked input that had a value, or a logged version that cannot be evaluated again, gives `code: REPLAY_NOT_POSSIBLE`, the masked input names and no sentence. The reading never contains a logged input.
- **At most 500 decisions.** Without a `limit` the run is 500; a larger `limit` is `422 READINGS_TOO_LARGE`, answered before anything is read. Nothing is written and no `Idempotency-Key` is taken.

## Attach a simulation report to a publication request

An approver should see evidence, not take it on trust. The engine runs the simulation and stores the result as a report bound to the exact content of the draft. You need `rules:simulate` to run one, and `rules:read` to read it.

```bash
curl -s -X POST http://localhost:8080/api/bpm/rules/v1/rule-sets/card-fee/versions/2/simulation-reports \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "Idempotency-Key: rep-2026-12-15-1" \
  -d '{"source":"REPLAY","from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z","limit":100}'
```

```json
{"id":"01KB2R6Q3C0000000000000001","ruleSet":"card-fee","version":2,"status":"CURRENT","attached":false,
 "createdBy":"lan","scope":{"source":"REPLAY","limit":100,"truncated":false},
 "counts":{"total":100,"changed":18,"unchanged":80,"failed":0,"notReplayable":2},"rows":[{"category":"CHANGED","decisionId":null,"before":{"kind":"MATCHED"},"after":{"kind":"MATCHED"}}]}
```

Use `"source":"SAMPLES"` with a `samples` list to report over sample customers instead.

- **Counts.** Each item falls in one count: not replayable (a masked input), failed, changed or unchanged; they add up to `total`.
- **Rows.** Up to 50 changed or failed rows, each with the matched rule and the outcome before and after as sentences in every language. Inputs are never part of a report, a masked value is never stored, and a `label` on a sample is ignored. `rowsTruncated` says more changed.
- **Decision ids.** A replayed row carries its `decisionId` only for a caller with `rules:log:read`.
- **Immutable and bound to the content.** A report is written once; the database refuses to change it. When the draft is replaced afterwards, the report reads `status: OUT_OF_DATE` and cannot be attached.
- **Attach it.** `POST .../versions/2:request-publish` with `{"simulationReportId":"01KB2R6Q3C0000000000000001"}`. The report then reads `attached: true`, and the `rules.version.publish-requested.v1` event carries the id. `GET .../versions/2/simulation-reports` lists a version's reports, newest first; `GET .../simulation-reports/{id}` reads one.
- **Require it.** A host lists rule sets in `horus.bpm.rules.lifecycle.require-simulation-report` (see [[Rule Engine Configuration]]); a request for those without a report is `409 SIMULATION_REPORT_REQUIRED`. The list is shown in `/api/bpm/meta`.

## Unit-test a rule set

`rule-engine-test` loads a rule set file (YAML, JSON or DMN, by its extension) in memory and evaluates it with the engine's own reader, compiler and evaluator. No database, no Spring context, no masking key, no events; the tenant is a fixed `test`. A file that fails import fails the test class before any test runs, with the same violations (rule id, column, code, message) as the `422 VALIDATION_FAILED` answer.

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

`rules.evaluate(...)` returns the same `DecisionResult` the engine gives for version 1 of the file. An input the rule set does not declare fails with `INPUT_INVALID` naming it, as `evaluation.reject-undeclared-inputs` does by default. Name each test after the row or behaviour it proves so the coverage report can map tests to rows (see `*-test` in [[Architecture]]).

The clock is fixed, so tests of rules that read `today()` or `now()` repeat on every machine. The default is `2027-01-01T00:00:00Z`. Change it for a class with `@RuleSetTest(value = "rules/card-fee.yaml", clock = "2027-06-30T17:00:00Z")`, or for one test with `@RuleSetClock("2027-06-30T17:00:00Z")`. `today()` is that instant's date in the rule set's `timeZone`.

## Report row coverage in CI

There is no extra command. The same test run writes the report when it ends, to `target/horus-rules-coverage/rules-coverage.json` and `rules-coverage.md`, and prints one line per rule set:

```bash
mvn test
# Rule coverage card-fee: 2 of 3 rows covered; R3 not covered
# Rule coverage kyc-route: 0 of 2 rows covered; K1, K2 not covered
```

A row counts as covered only when a test evaluation matched it, so a test that fails the evaluation (`UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR`) covers nothing. The report lists each rule set by name and canonical hash with its rows in document order and the tests that matched each. It never shows an input value, and the same tests give the same bytes, so a CI job can keep it as an artifact. A rule set that no test loaded is not in it. Change the folder with `horus.rules.coverage.report-dir`.

By default coverage only reports (PD-120). To make the build fail, set `horus.rules.coverage.fail-on` in `src/test/resources/junit-platform.properties`:

| Value | The build fails when |
|---|---|
| `none` (default) | Never. The report is written and one line per rule set is printed |
| `unique` | A row of a `unique` rule set was not matched by any test |
| `any` | Any row of any rule set was not matched by any test |

An unknown value fails the run, naming the setting. The report is written in every case. A bank that must prove every row is tested sets `any` in its rule set repository:

```properties
horus.rules.coverage.fail-on=any
```

## Rely on determinism

Given the same version, inputs and clock, outputs and matched rule ids are identical on every run and every JVM (TR-DET-01 in [[Technical Requirements]]). A test that observes otherwise has found a bug; report it as described in [[Contributing]].

## Compare two versions over last month

A pricing committee decides on totals, not on single cases. `POST /rule-sets/card-fee:compare` takes the decisions the engine really made in a past period (at most 31 days) and decides each one again, at its logged time, through two versions in memory: the one in force and a draft, say. It needs both `rules:simulate` and `rules:log:read`, because it runs versions that may not be approved and reads logged inputs. It writes nothing.

```bash
curl -s -X POST http://localhost:8080/api/bpm/rules/v1/rule-sets/card-fee:compare \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{ "base": 1, "candidate": 2, "from": "2027-01-01T00:00:00Z", "to": "2027-02-01T00:00:00Z" }'
```

Expected: `200` with `decisionsCompared`, `changedCount`, `outcomes` per side, `totals` and `transitions`. For 10,000 January decisions where v2 lowers the default fee from 50,000 to 30,000 VND:

```json
{ "decisionsCompared": 10000, "changedCount": 5000,
  "totals": [ { "output": "fee", "currency": "VND", "base": "280000000", "candidate": "180000000", "difference": "-100000000" } ],
  "transitions": [ { "output": "fee", "from": { "currency": "VND", "amount": 50000 }, "to": { "currency": "VND", "amount": 30000 }, "count": 5000 } ],
  "skipped": {} }
```

- **Totals** are exact decimals written as text, in minor units for money, per currency. They cover the compared decisions only.
- **What is skipped.** A masked input is stored only as a fingerprint, so a decision whose masked value one of the versions reads cannot be decided again: it is counted under `skipped.MASKED_INPUT`. A logged `null` is compared. An input a version reads that an older decision never held is `skipped.MISSING_INPUT`. A masked input neither version reads does not matter.
- **Too many decisions.** A period holding more than `simulation.max-history-decisions` (default 50,000, up to 200,000) is `422 HISTORY_TOO_LARGE`, with `found` saying how many. The engine never takes a sample, because sampled totals look exact and are not. Narrow the period.
- **The response holds counts, totals and changes only**, never an input, a decision id or a fingerprint, so it can go into a committee pack.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `403 PERMISSION_DENIED` on `:simulate` | Caller lacks `rules:simulate`; nothing was evaluated | Grant the permission to the author and approver roles |
| `404 VERSION_NOT_FOUND` on `:simulate` | The version does not exist in your tenant (another tenant's version gives the same answer) | Check the rule set name, the version number and the signed-in tenant |
| `400 INPUT_INVALID` naming `samples[3]` | That sample has an input the version does not declare, a value of the wrong type, or a number out of bounds; or there are no samples or more than `simulation.max-inputs` | Fix that sample, or split the samples |
| `422 HISTORY_TOO_LARGE` on `:compare` | The period holds more decisions than `simulation.max-history-decisions` | Narrow the period, or raise the setting up to 200,000; the engine never samples |
| `403 PERMISSION_DENIED` on `:compare` | Caller lacks `rules:simulate` or `rules:log:read`; it needs both | Grant both to the committee role |
| `409 INVALID_STATE` on `/decisions/{ruleSet}/versions/{v}:evaluate` for a draft | A pinned evaluation is logged, so it accepts only approved versions (`PUBLISHED` or `RETIRED`); a draft never enters the decision log | Simulate the draft with `POST /rule-sets/{name}/versions/{v}:simulate`, which writes no decision row |
| `403 CLOCK_NOT_ALLOWED` when sending `clock` | `clock` requires `rules:simulate`, on `:evaluate` and on `/versions/{v}:evaluate` alike | Grant the permission to the simulation role, or leave `clock` out to use the host clock |
| Simulation rows appear in the Decisions screen | The call went to an `:evaluate` endpoint, which logs every decision | Use `:simulate` for drafts and what-if runs; it writes no decision row |
| The coverage report lists a row you believe is tested | The test matches a different row first under `first`, or the evaluation failed (`unique` overlap, `EVALUATION_ERROR`), which covers nothing | Check `matchedRuleIds` in the test; reorder rows or adjust inputs |
| The build fails after the tests pass, with a message naming `horus.rules.coverage.fail-on` | The setting asks for more coverage than the tests give, or its value is not `none`, `unique` or `any` | Add tests for the rows the message names, or relax the setting |
| Replay shows 0 changed outcomes for a changed row | The period contains no decisions that reach that row | Choose a longer period (up to 31 days) or add sample inputs that hit the row |
| `403 PERMISSION_DENIED` on `:simulate-history` | Replay needs both `rules:simulate` and `rules:log:read` | Grant `rules:log:read` to the role that replays, or replay with sample inputs instead |
| `409 SIMULATION_REPORT_REQUIRED` on `:request-publish` | The host lists the rule set in `lifecycle.require-simulation-report` and the request carries no report | Run `POST .../simulation-reports` on the draft and send its id as `simulationReportId` |
| `409 SIMULATION_REPORT_OUT_OF_DATE` on `:request-publish` | The draft changed after the report was run | Run a new report on the draft as it is now |
| `notReplayableCount` above 0 | Those decisions had a masked input with a value; the log holds only its fingerprint | Expected for masked inputs; cover those cases with sample inputs |
