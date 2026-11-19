# Expression Language

Reference for the language used in `when` conditions and `then` outputs of a decision table. The language is parsed at publication time into a typed evaluation tree (ADR-0003).

Design preview: describes the 0.1 target.

## Guarantees

| Property | Statement |
|---|---|
| No code execution | No method calls, reflection, class loading, file or network access (TR-SEC-01) |
| Termination | No loops, no recursion, no unbounded allocation; regular expressions are bounded in length and time (TR-SEC-02) |
| Determinism | Same inputs and clock give the same result (TR-DET-01); `today()` and `now()` read the decision's clock, which the decision log records |
| Static typing | Unknown identifiers and type errors are rejected at publication time (TR-DET-02) |

## Literals

| Kind | Syntax | Examples |
|---|---|---|
| number | Digits with optional `.` and `_` separators | `18`, `0.015`, `5_000_000` |
| money | Amount in major units followed by an ISO 4217 currency code; stored in minor units with the currency's scale | `5000000 VND`, `12.50 USD` |
| string | Single or double quotes | `'GOLD'`, `"VN"` |
| boolean | | `true`, `false` |
| date | `date(` ISO 8601 date `)` | `date('2026-12-01')` |
| datetime | `datetime(` ISO 8601 instant `)` | `datetime('2026-12-01T00:00:00Z')` |
| list | Comma-separated values in brackets, one type | `['VN', 'SG']`, `[1, 2, 3]` |
| null | | `null` |

## Operators

| Group | Operators | Rules |
|---|---|---|
| Comparison | `==` `!=` `<` `<=` `>` `>=` | Both sides must have the same type. Money compares only within one currency; a currency mismatch is an evaluation error (TR-DET-03) |
| Logic | `and` `or` `not` | Three-valued with `null`, as in FEEL; see [Null values](#null-values) |
| Membership | `in [..]`, `not in [..]` | List element type must match |
| Range | `between a and b` | Inclusive on both ends; means `x >= a and x <= b`, as in DMN |
| Arithmetic | `+` `-` `*` `/` | Number with number; money with money of the same currency for `+` and `-`; `money * number` and `money / number`; `money / money` is not allowed |
| String | `startsWith(s, 'x')`, `endsWith(s, 'x')`, `contains(s, 'x')`, `matches(s, 'regex')` | Plain function form only; there is no method-call syntax. `matches` is bounded by `evaluation.regex-timeout-ms` and `evaluation.regex-max-input-length` |
| Null | `is null`, `is not null` | Always `true` or `false`. Other operators follow [Null values](#null-values) |

Precedence from lowest to highest: `or`, `and`, `not`, comparison, `+ -`, `* /`, unary minus. Parentheses override.

## Null values

A missing input or a `null` value follows FEEL, the DMN expression language, so every expression gives the same result when it is exported to DMN (ADR-0019). A condition matches only when it evaluates to `true`; a `null` result never matches.

| Expression with `tier = null` and `income = null` | Result | Matches |
|---|---|---|
| `income < 5000000 VND`, `income between 1 VND and 9 VND` | `null` | No |
| `txCount between 1 and limit` with `txCount = 5`, `limit = null` | `null` (`true and null`) | No |
| `txCount between 1 and limit` with `txCount = 0`, `limit = null` | `false` (`false and null`) | No |
| `tier == 'GOLD'` | `false` | No |
| `tier != 'GOLD'` | `true` | Yes |
| `tier in ['GOLD', 'PLATINUM']` | `false` | No |
| `tier not in ['GOLD', 'PLATINUM']` | `true` | Yes |
| `tier in ['GOLD', null]` | `true` (an element equals the missing value) | Yes |
| `tier is null` | `true` | Yes |
| `not(income < 5000000 VND)` | `null` | No |
| `income + 1 VND > 0 VND`, `len(tier) > 3` (arithmetic and functions give `null`) | `null` | No |
| `coalesce(tier, 'STANDARD') == 'STANDARD'` | `true` | Yes |

`and` and `or` with `null`:

| Left | Right | `and` | `or` |
|---|---|---|---|
| `true` | `null` | `null` | `true` |
| `false` | `null` | `false` | `null` |
| `null` | `null` | `null` | `null` |

`!=` and `not in` match a missing value. If a rule must not match when the input is missing, write it out: `tier is not null and tier != 'GOLD'`. Publication reports a warning when an input that may be missing is used with `!=` or `not in`.

## Shorthand in `when`

| Written | Meaning |
|---|---|
| `age: "< 18"` | `age < 18` |
| `country: "in ['VN', 'SG']"` | `country in ['VN', 'SG']` |
| `age: "age < 18 and country == 'VN'"` | Full expression; may refer to any input |

A `when` entry is a boolean expression. A `then` entry is an expression whose type must equal the output's declared type.

## Functions

| Group | Functions |
|---|---|
| Numeric | `min(a, b)`, `max(a, b)`, `abs(x)`, `round(x, scale)`, `floor(x)`, `ceil(x)` |
| String | `len(s)`, `lower(s)`, `upper(s)`, `trim(s)`, `startsWith(s, 'x')`, `endsWith(s, 'x')`, `contains(s, 'x')`, `matches(s, 'regex')` |
| Null | `coalesce(a, b)` |
| Clock | `today()`, `now()` |
| Date | `daysBetween(d1, d2)`, `monthsBetween(d1, d2)`, `addDays(d, n)`, `yearOf(d)`, `monthOf(d)` |
| Money | `amount(m)`, `currency(m)`, `money(amount, 'VND')` |

Hosts register additional functions through the `RuleFunctionRegistry` SPI. A registered function must be deterministic and side-effect free. The cockpit lists registered functions with their signatures.

## Errors

| When | Condition | Result |
|---|---|---|
| Publication | Syntax error, unknown identifier, type mismatch, unknown function | `422 VALIDATION_FAILED` with the row id; the version stays a draft |
| Evaluation | Currency mismatch, division by zero, regex timeout | The decision fails with `EVALUATION_ERROR` (`422` over REST), returns no partial outputs, and is logged with that outcome |

Error codes and HTTP mapping are owned by [[Rule Engine Decision API]].

## Grammar sketch

```
expr     := or
or       := and ('or' and)*
and      := not ('and' not)*
not      := 'not' not | cmp
cmp      := sum (('=='|'!='|'<'|'<='|'>'|'>=') sum | 'in' list | 'not' 'in' list | 'between' sum 'and' sum | 'is' 'null' | 'is' 'not' 'null')?
sum      := term (('+'|'-') term)*
term     := unary (('*'|'/') unary)*
unary    := '-' unary | primary
primary  := literal | ident | ident '(' args ')' | '(' expr ')' | money
```

Examples of use are in [[Rule Engine Authoring Decision Tables]].
