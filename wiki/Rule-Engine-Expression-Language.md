# Expression Language

A small, safe language for conditions and outputs. It cannot call methods, access the file system or network, loop, or allocate unbounded memory. Every expression is parsed at publish time. _Design preview._

## Literals
| Kind | Examples |
|---|---|
| number | `18`, `0.015`, `5_000_000` |
| money | `5000000 VND`, `12.50 USD` (amount in major units with the currency's scale; stored as minor units) |
| string | `'GOLD'`, `"VN"` |
| boolean | `true`, `false` |
| date / datetime | `date('2026-12-01')`, `datetime('2026-12-01T00:00:00Z')` |
| list | `['VN', 'SG']`, `[1, 2, 3]` |
| null | `null` |

## Operators
- Comparison: `==  !=  <  <=  >  >=` (money compares only within the same currency; mixing currencies is an evaluation error).
- Logic: `and  or  not`.
- Membership and ranges: `in [..]`, `not in [..]`, `between a and b` (inclusive).
- Arithmetic: `+ - * /` on numbers and money (`money * number`, `money + money` same currency; `money / money` is not allowed).
- String: `startsWith('x')`, `endsWith('x')`, `contains('x')`, `matches('regex')` (regex is bounded and timed out).
- Null handling: comparisons with `null` are `false`; use `is null` / `is not null`.

## Shorthand in `when`
A condition written against an input may omit the input name: `age: "< 18"` means `age < 18`. Full expressions are also allowed: `age: "age < 18 and country == 'VN'"`.

## Functions (whitelist)
`min, max, abs, round(x, scale), floor, ceil, len, lower, upper, trim, coalesce(a, b), today(), now(), daysBetween(d1, d2), monthsBetween(d1, d2), addDays(d, n), yearOf(d), monthOf(d), amount(money), currency(money), money(amount, 'VND')`.

Hosts can register additional pure functions through `RuleFunctionRegistry`; a registered function must be deterministic and side-effect free, and it is listed in the cockpit with its signature.

## Determinism
`today()` and `now()` are taken from the decision's evaluation clock, which is recorded in the decision log, so a decision can be replayed exactly. Simulations can set the clock.

## Errors
Type errors and unknown identifiers are rejected at publish time. Runtime errors (currency mismatch, division by zero, regex timeout) fail the decision with an error code, are written to the decision log, and never return partial outputs.

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
