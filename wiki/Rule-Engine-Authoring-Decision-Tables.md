# Authoring Decision Tables

A rule set is a YAML (or JSON) document, or the same structure authored in the [[Cockpit]]. _Design preview._

## Document structure
```yaml
ruleSet: card-fee            # unique name within a tenant, kebab-case
version: 2                   # assigned by the engine when created through the API; informational in files
description: Monthly fee by card tier and balance
hitPolicy: first             # first | unique | collect
effectiveFrom: 2026-12-01    # optional; otherwise effective when published
inputs:
  - { name: tier,    type: string,  allowed: [CLASSIC, GOLD, PLATINUM] }
  - { name: balance, type: money }
  - { name: txCount, type: integer, description: transactions in the month }
outputs:
  - { name: fee,     type: money }
  - { name: waived,  type: boolean, default: false }
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

## Types
`string`, `integer`, `decimal`, `boolean`, `date`, `datetime`, `money` (currency + amount in minor units), `list<T>`. Inputs are validated against the type before evaluation; a wrong type is a `400` or `DecisionInputException`, never a silent coercion.

## Conditions (`when`)
- A map from input name to a condition expression. An input not mentioned matches anything.
- An empty `when: {}` matches everything; use it as the last rule under `first`.
- Conditions on the same row are combined with AND. For OR across inputs, write two rows or use the `any:` form:
  ```yaml
  when:
    any:
      - { tier: "== 'GOLD'" }
      - { balance: ">= 50000000 VND" }
  ```

## Outputs (`then`)
- A value per output: a literal (`true`, `30000 VND`, `'OK'`) or an expression over inputs (`balance * 0.01`).
- Missing outputs take the output's `default`; if there is none, the rule fails validation.

## Hit policies
| Policy | Behaviour | Typical use |
|---|---|---|
| `first` | Rows evaluated top to bottom; first match wins | Eligibility, routing, tiered fees |
| `unique` | Exactly one row must match; zero or more than one is an error reported in the log | Pricing tables that must be complete and non-overlapping |
| `collect` | All matching rows; outputs become lists, or are aggregated with `collect: sum|min|max|count` per output | Accumulating fees, collecting reasons |

## Validation at save and publish
Unknown input names, type mismatches, unparsable expressions, `unique` tables with provably overlapping rows (for enumerated inputs), and `first` tables with unreachable rows are reported with row ids. A version cannot be published with errors; warnings are allowed and shown in the cockpit.

## Importing and exporting
`GET /api/bpm/rules/rule-sets/{name}/versions/{v}/export` returns this YAML; `POST …/import` creates a draft from it. Keep rule sets in Git next to the service that owns them, and let the cockpit be the place business users propose changes.
