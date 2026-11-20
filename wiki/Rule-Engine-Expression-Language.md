# Expression Language

Reference for the language used in `when` conditions and `then` outputs of a decision table. The language is parsed at publication time into a typed evaluation tree (ADR-0003).

Design preview: describes the 0.1 target.

## Guarantees

| Property | Statement |
|---|---|
| No code execution | No method calls, member access, reflection, class loading, file or network access (TR-SEC-01). A call of a function the language does not have, such as `T(java.lang.Runtime)`, or a statement character is refused at import as `FORBIDDEN_CONSTRUCT`, before anything inside it is read. In this version of the language a `.` outside a number or a string is refused the same way; FEEL path expressions (ADR-0019) may make forms such as `applicant.age` legal in a later version |
| Termination | No loops, no recursion, no unbounded allocation. An expression is at most 4,096 characters and 64 levels deep, counted on the tree, so a chain such as `a + a + ...` of more than 64 terms is `EXPRESSION_TOO_DEEP` too. One decision visits at most 100,000 nodes of its expressions (step budget), with a 10-second backstop, or fails with `EVALUATION_BUDGET_EXCEEDED`; regular expressions are bounded in length and time (TR-SEC-02) |
| Step budget | A step is one expression node visited. The contract is the default budget, its ceiling and the reason code; the exact count of a given expression may change between minor releases (constant folding, FEEL). There is one budget per top-level evaluation, shared by every decision that evaluation requires (ADR-0027); each item of a batch has its own. The language has no loops, so a table's worst case is the sum of its node counts: every condition, plus the outputs of the rules its hit policy can evaluate (one for `first` and `unique`, all for `collect`). Import, draft replacement and `:request-publish` refuse a table whose worst case is over the host's budget, as `VALIDATION_FAILED` with code `EVALUATION_BUDGET_EXCEEDED` on the first rule from which it is over. Example: a `unique` tariff of 5,000 rules over 8 inputs, each cell such as `>= 18`, can take 155,001 steps and is refused at import |
| Determinism | Same inputs and clock give the same result (TR-DET-01); `today()` and `now()` read the decision's clock, which the decision log records |
| Static typing | Unknown identifiers and type errors are rejected at import and again at publication request (TR-DET-02), with the rule, column, `code` and character position: `balance + 100 > 0` with money `balance` is `TYPE_MISMATCH` at the `+`. Operands are checked under every operator, in conditions and in outputs computed from inputs; an operand that is the literal `null` passes and gives `null` at evaluation |

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
| String | `startsWith(s, 'x')`, `endsWith(s, 'x')`, `contains(s, 'x')`, `matches(s, 'regex')` | Plain function form only; there is no method-call syntax. `matches` is an unanchored search (use `^` and `$` to anchor); the pattern is a string literal of at most 1,000 characters in the part of the XPath regex dialect that Java reads the same way, checked at import: back-references, look-around, inline flags such as `(?i)`, possessive quantifiers such as `a++`, nested classes and `&&`, Unicode block names (use general categories such as `\p{L}`) and Java-only escapes such as `\x41` are refused. In a condition cell, `matches('^FT[0-9]{8}$')` tests the cell's own input. A null text gives null. Each match is bounded by `evaluation.regex-max-reads` (deterministic), all the matches of one decision together by `evaluation.regex-max-reads-per-decision`, `evaluation.regex-timeout-ms` and `evaluation.regex-max-input-length`; past any of them the decision fails with `EVALUATION_ERROR` (`REGEX_TIMEOUT`, `REGEX_INPUT_TOO_LONG`, or `REGEX_TOO_DEEP` when backtracking runs out of stack). A v1 string escapes a backslash as `\\`, so `\d` is written `'\\d'` |
| Null | `is null`, `is not null` | Always `true` or `false`. Other operators follow [Null values](#null-values) |

Precedence from lowest to highest: `or`, `and`, `not`, comparison, `+ -`, `* /`, unary minus. Parentheses override.

## Numbers, rounding and text

These rules follow FEEL, so a rule gives the same result in this engine and in any DMN engine (ADR-0019). A test checks this on every change: generated expressions are evaluated here and, translated to FEEL, by a reference FEEL implementation, which runs in tests only and never ships.

| Topic | Rule | Example |
|---|---|---|
| Numbers | `integer` and `decimal` are one number type, exact decimal with 34 significant digits (IEEE 754 `decimal128`), rounded half-even past that. Never binary floating point | `7 / 2` is `3.5`; `0.1 + 0.2 == 0.3` is `true` |
| Division by zero | An evaluation error. FEEL gives `null`; a v1 rule set runs in strict error mode, so the decision fails instead | `1 / 0` fails with `EVALUATION_ERROR` |
| Rounding | `round(x, scale)` rounds half-even (to the nearest, ties to the even digit), as FEEL `decimal(x, scale)` and `round half even(x, scale)`. The scale is a whole number from -34 to 34; a negative scale rounds left of the point | `round(2.5, 0)` is `2`; `round(3.5, 0)` is `4`; `round(12.345, 2)` is `12.34`; `round(1250, -2)` is `1200` |
| Money rounding | Money keeps its own rule: `money * number` and `money / number` round to the currency's scale with the rule set's `moneyRounding` (default half-even) | `12.50 USD * 0.015` is `0.19 USD` |
| Text order | `<`, `>`, `min` and `max` compare strings by Unicode code point, never by locale or by UTF-16 unit. No Unicode normalisation is applied | `'GOLD' < 'SILVER'` is `true`; `'Z' < 'a'` is `true` |
| Days | `daysBetween(d1, d2)` counts calendar days from `d1` to `d2`, negative when `d2` is earlier; FEEL `(d2 - d1).days` | `daysBetween(date('2027-01-01'), date('2027-03-01'))` is `59` |

### Money is a HORUS extension

FEEL has no money type. Money values carry their currency, and an operation never mixes currencies:

| Case | Result |
|---|---|
| Two money literals of different currencies in one cell, such as `x + 5 USD >= 50000000 VND` or `in [5 USD, 10 VND]` | Refused at import: `422 VALIDATION_FAILED` naming the rule and column |
| `balance >= 50000000 VND` evaluated with `balance` = 3,000.00 USD | `EVALUATION_ERROR` (`422` over REST), no partial outputs, logged with outcome `EVALUATION_ERROR` |

Exported to DMN, money is written with the HORUS function `money(amount, 'CUR')`, which other DMN engines do not know.

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
| `income + 1 VND > 0 VND`, `round(score, 0) > 3` with `score = null` (arithmetic and functions give `null`) | `null` | No |
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

In 0.2.0 the engine evaluates `round` and `daysBetween`; the other functions are planned. An unknown function is refused at import.

Hosts register additional functions through the `RuleFunctionRegistry` SPI. A registered function must be deterministic and side-effect free. The cockpit lists registered functions with their signatures.

## Errors

| When | Condition | Result |
|---|---|---|
| Publication | Syntax error, unknown identifier, type mismatch, unknown function | `422 VALIDATION_FAILED` with the row id; the version stays a draft |
| Publication | Two money literals of different currencies in one cell; a function argument of the wrong type | `422 VALIDATION_FAILED` with the row id |
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
primary  := literal | ident | call | '(' expr ')' | money
call     := ('round' | 'daysBetween') '(' expr ',' expr ')'
```

Examples of use are in [[Rule Engine Authoring Decision Tables]].
