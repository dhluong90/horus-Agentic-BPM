# How to author a decision table

Write a rule set as a YAML (or JSON) document, validate it, and load it into the engine. The same structure is what the cockpit grid editor edits from Cockpit 0.2.

Design preview: describes the 0.1 target.

## Before you start

- You know the terms rule set, rule, hit policy and version: [[Concepts]].
- You have a service with the starter configured: [[Getting Started]].

## 1. Name the rule set and choose the hit policy

```yaml
ruleSet: card-fee               # unique within a tenant, kebab-case
description: Monthly fee by card tier and balance
hitPolicy: first                # first | unique | collect
effectiveFrom: 2026-12-01T00:00:00+07:00   # optional ISO instant; otherwise effective when approved
```

| Policy | Behaviour | Use it for |
|---|---|---|
| `first` | Rows are checked top to bottom; the first match wins | Eligibility, routing, tiered fees with a default row |
| `unique` | Exactly one row should match: several matches fail the decision with `UNIQUE_HIT_VIOLATION`; zero matches is no match, as with `first` | Pricing tables that must be complete and non-overlapping |
| `collect` | Every matching row contributes; each output becomes a list or an aggregate (`collect: sum`, `min`, `max`, `count`) | Accumulating fees, collecting reason codes or risk flags |

## 2. Declare inputs and outputs

```yaml
inputs:
  - { name: tier,       type: string,  allowed: [CLASSIC, GOLD, PLATINUM] }
  - { name: balance,    type: money }
  - { name: txCount,    type: integer, description: transactions in the month }
  - { name: nationalId, type: string,  mask: true }      # stored as a keyed hash in the decision log
outputs:
  - { name: fee,    type: money }
  - { name: waived, type: boolean, default: false }
```

| Type | Example value in a condition | Note |
|---|---|---|
| `string` | `'GOLD'` | `allowed:` restricts the values and lets the validator detect unreachable rows |
| `integer` | `10` | |
| `decimal` | `0.015` | |
| `boolean` | `true` | |
| `date` | `date('2026-12-01')` | |
| `datetime` | `datetime('2026-12-01T00:00:00Z')` | |
| `money` | `50000000 VND` | Currency plus amount; stored in minor units; never compared across currencies |
| `list<T>` | `['VN', 'SG']` | |

Inputs are validated against their type before evaluation. A wrong type is a `400 INPUT_INVALID` over REST or a `DecisionInputException` in Java, never a silent conversion.

## 3. Write the rows

```yaml
rules:
  - id: R1
    when: { tier: "== 'PLATINUM'" }
    then: { fee: "0 VND", waived: true }
  - id: R2
    when: { tier: "== 'GOLD'", balance: ">= 50000000 VND" }
    then: { fee: "0 VND", waived: true }
  - id: R3
    when: { tier: "== 'GOLD'" }
    then: { fee: "30000 VND" }
  - id: R4
    when: { txCount: ">= 10" }
    then: { fee: "0 VND", waived: true }
  - id: R5
    when: {}
    then: { fee: "50000 VND" }
```

| Element | Rule |
|---|---|
| `when` | A map from input name to a condition. Conditions on one row are combined with AND. An input that is not mentioned matches anything. |
| `when: {}` | Matches everything. Under `first`, put it last as the default row. |
| `then` | One value per output: a literal (`true`, `30000 VND`, `'OK'`) or an expression over the inputs (`balance * 0.01`). An output you omit takes its `default`; without a default the row fails validation. |
| `id` | Stable and unique within the rule set. Tests, the decision log and the diff view refer to it. |

Condition syntax is in [[Rule Engine Expression Language]]. A condition may omit the input name (`balance: ">= 50000000 VND"`) or spell it out (`balance: "balance >= 50000000 VND and tier == 'GOLD'"`).

### OR across inputs

Write two rows, or use `any:`:

```yaml
when:
  any:
    - { tier: "== 'GOLD'" }
    - { balance: ">= 50000000 VND" }
```

### Aggregating with `collect`

```yaml
hitPolicy: collect
outputs:
  - { name: flags,   type: list<string> }
  - { name: penalty, type: integer, collect: sum }
```

With rows R1 (`penalty: 10`) and R3 (`penalty: 5`) both matching, `penalty` is `15` and `flags` lists both rows' values in row order.

| Output under `collect` | Declared type | Result |
|---|---|---|
| No `collect:` | `list<T>`; each row gives one `T` | The matched rows' values in row order |
| `collect: sum` | `integer`, `decimal` or `money` | The exact sum. Money must be in one currency; a number sum that needs more than 34 significant digits fails. Either failure is `EVALUATION_ERROR` |
| `collect: min`, `collect: max` | Any scalar type except `boolean` | The smallest or largest value; strings in Unicode code point order, as `<` |
| `collect: count` | `integer` | How many rules matched, `null` values included |

A list keeps one entry per matched rule, `null` included, so parallel lists line up with each other and with `matchedRuleIds`. `count` counts every matched rule, `null` values included, as FEEL `count` does. A `null` value under `sum`, `min` or `max` fails the decision with `EVALUATION_ERROR` naming the output and the rule (a v1 rule set evaluates in strict mode), so give every row a value for those outputs, for example `0`. Aggregates are over all matched values, not distinct ones. When no row matches, every output is `null`, lists and `count` included. `collect:` on an output of a `first` or `unique` table, a list output outside `collect`, or a type that does not fit the aggregate is refused at import with `422 VALIDATION_FAILED`.

### Exactly one row with `unique`

Under `unique`, several matching rows fail the decision with `422 UNIQUE_HIT_VIOLATION`: no outputs are returned, and the decision row records that outcome and the matched rule ids. Zero matches is no match, as with `first`: every output is `null` and the decision is logged as `OK` with no matched rules (DMN 1.5). Add a catch-all row if no match must be handled differently. A pricing table where two rows overlap fails loudly instead of quietly charging the first row's price. The example [`loan-pricing.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-engine-only/src/main/resources/rules/loan-pricing.yaml) overlaps on purpose.

## 4. Validate

Validation runs on every save and on every publication request. It reports each problem with the row id.

| Check | Severity |
|---|---|
| Unknown input or output name, type mismatch, unparsable expression | Error; the version cannot be published |
| `unique` table with two rows that provably overlap (for `allowed` enumerations) | Error |
| Missing output without a `default` | Error |
| `first` table with a row that can never be reached | Warning; shown in the cockpit |

To validate without saving: `POST /api/bpm/rules/v1/rule-sets/card-fee/versions/2:validate` with the document as the body. Expected: `200` with an empty `errors` list, or `422 VALIDATION_FAILED` with `errors: [{ "ruleId": "R2", "message": "..." }]`.

## 5. Load it into the engine

| Method | When to use it | How |
|---|---|---|
| Files at start-up | Rule sets live in Git next to the service | Set `horus.bpm.rules.definitions: classpath:rules/**/*.yaml`. Files load as drafts; publish them as in [[Rule Engine Versioning and Publishing]] |
| Import by API | A business user or a pipeline proposes a change | `POST /api/bpm/rules/v1/rule-sets` (first version) or `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` (next version) with the YAML as the body and `Content-Type: application/yaml` creates a new draft. Available now; see [[Rule Engine Decision API]] |
| Export | Review or back up a version | `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/2/export` returns this document |
| Cockpit grid editor | Interactive editing | Cockpit 0.2 target; see [[Cockpit]] |

Endpoint details, headers and error codes are in [[Rule Engine Decision API]].

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `422 VALIDATION_FAILED` at row `R2`, "unknown identifier" | A condition refers to an input that is not declared | Add the input in `inputs` or fix the spelling |
| `422 VALIDATION_FAILED`, "money + integer" | Arithmetic mixes money and a plain number | Multiply money by a number (`balance * 0.01`) or add money to money of the same currency |
| `422 UNIQUE_HIT_VIOLATION` at run time | A `unique` table had several matches for real inputs | Remove the overlap, or switch to `first` if the order of rows is intended |
| Default row never matches | It is not the last row under `first` | Move `when: {}` to the end |
| Validation warns about an unreachable row | An earlier row under `first` already covers it | Delete the row or reorder |
