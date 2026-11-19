# ADR-0019: A HORUS-built, deterministic FEEL; our expression language as a semantic subset of FEEL

- Status: accepted, 2026-11-19
- Date: 2026-11-18 (proposed), 2026-11-19 (accepted)
- Deciders: maintainers; accepted by the product owner (PD-061 option 1; FEEL null semantics PD-058 option 1)
- Module: rule-engine, process-engine

## Context
ADR-0017 requires DMN Conformance Level 3, which means full FEEL. ADR-0003 built a small closed language (`horus-expr-v1`) with a hand-written parser, a typed tree, publish-time type checks, money safety, bounded regex and an explicit clock, and rejected full FEEL. M1 ships `horus-expr-v1`; decisions logged from M1 must replay to the same result for years (`TR-DET-05`), so any semantic difference between `horus-expr-v1` and FEEL that remains after M1 is permanent.

Licences checked on 2026-11-18: `camunda/feel-scala` Apache 2.0 (with the Scala runtime, Apache 2.0, and `fastparse`, MIT); Apache KIE (DMN and FEEL) Apache 2.0; DMN TCK Apache 2.0.

## Decision
1. Build FEEL (DMN 1.5) in `rule-engine-core` on the existing typed tree, type checker and evaluator: hand-written recursive-descent parser with scope-aware name resolution, every built-in function without reflection, a compiled profile for S-FEEL (today's sub-100 us path) and a general profile with a counted step budget and recursion depth limit, external Java and PMML functions refused, explicit clock and time zone, `DECIMAL128` numbers, a per-model `errorMode` (`spec` or `strict`).
2. Keep `horus-expr-v1` as an authoring dialect, defined as a semantic subset of FEEL with the translation table of [`standards-compliance.md`](../architecture/standards-compliance.md) section 4.4. Both dialects parse into one tree; DMN export prints FEEL.
3. Align `horus-expr-v1` semantics with FEEL before M1 ships: numbers, rounding, regex, strings, date-times and time zones (applied), and FEEL three-valued null logic (decided as PD-058 option 1 on 2026-11-19; `RLE-006` changes for `!=`, `not` and `not in`).
4. Expose expression languages through one `ExpressionLanguage` SPI; the Process Engine uses only that SPI.
5. Use `camunda/feel-scala` in test scope only, as a differential oracle, with the DMN TCK.

## Options considered
1. HORUS-built FEEL on our typed tree (chosen). Determinism, sandboxing, money and slot compilation designed in; no runtime dependency; the evaluator is the product, which the program builds itself. About 22 engineer-weeks (assumption) and a large specification to get right, mitigated by the TCK and the differential oracle.
2. Embed `camunda/feel-scala`. Mature and permissive; but it is the product itself, adds about 6 MB of Scala runtime with version conflicts to every host, has its own value model, clock and error semantics, no money type, and interprets per call, which puts `TR-PERF-01` at risk.
3. Embed Apache KIE FEEL. Complete and permissive; large dependency tree, ANTLR-generated parser, reflection-based external functions in the runtime, same product-boundary objection.
4. Keep only `horus-expr-v1` and translate a FEEL subset at import (ADR-0003 as written). No work; cannot reach Level 3, so it contradicts ADR-0017.
5. Replace `horus-expr-v1` by FEEL syntax before M1. One dialect forever; but FEEL's double-quoted strings and spaced function names make YAML authoring noisier (PD-005), and the M1 documentation and examples would be rewritten.

## Consequences
- `TR-STD-02` (Must, M1): every `horus-expr-v1` expression and its FEEL translation give the same result, checked by property tests against the oracle.
- `RLE-006` changes for `!=`, `not` and `not in` with `null` (PD-058); the wiki expression-language page changes with it.
- `TR-DET-02` keeps its meaning for `horus-expr-v1`; for FEEL, everything statically determinable is checked at publish time and the rest at run time.
- `TR-PERF-01` applies to the compiled profile; the general profile has the targets of `TR-STD-24`.
- ADR-0003 stays for the parser approach, the closed operations and the explicit clock; its option 4 is superseded by ADR-0017 and this ADR.

## History
- 2026-11-18: proposed by the maintainers, with a decision needed by the product owner (about 22 engineer-weeks after 1.0, and a change to `RLE-006` in M1). The null alignment was listed as pending decision P1.
- 2026-11-19: accepted by the product owner as PD-061 option 1. P1 decided the same day as PD-058 option 1: FEEL null semantics from M1.
