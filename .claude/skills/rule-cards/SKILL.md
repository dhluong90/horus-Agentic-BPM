---
name: rule-cards
description: Writing testable banking business rules as Given/When/Then rule cards with ids, configurable parameters, worked examples and sources, and deriving acceptance criteria and tests from them. Use when capturing business rules, writing RULES.md, defining acceptance criteria, or mapping tests to rules.
---
# Rule cards

## Card format (`RULES.md`)
```
## <ACC>-R-<nnn> <rule name>
- Given: the state and configuration in force
- When: the triggering action
- Then: the observable result, including the posting, the event and the message
- Parameters (configurable per product or tenant): name, type, default, range
- Example: concrete numbers, currency, dates, tenant
- Source: regulation (named) | market practice | assumption
- Open questions:
```

## Rules for writing rules
- One behaviour per card. If "and" appears in Then for two different outcomes, split.
- Every card has a worked example with real amounts in minor units and a currency. Cards without numbers are not testable.
- Separate what the bank requires from how one bank did it. Anything two banks might want differently is a parameter.
- Cover failure paths as their own cards: insufficient funds, limit breached, duplicate request, rejected approval, adapter timeout.
- State the posting (debit, credit) for any money-moving rule; the domain expert validates it against the posting scheme.
- Keep ids stable forever. Retire with a "Retired" note; never renumber.

## From cards to tests
- Acceptance criteria in an issue quote the card id and its example.
- Tests are named by rule id, for example `WAL_R_012_daily_limit_blocks_fifth_topup`.
- `TEST-PLAN.md` maps every rule id to its tests; an unmapped rule is reported as unverified.
