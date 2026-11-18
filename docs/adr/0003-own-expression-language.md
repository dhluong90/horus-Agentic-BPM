# ADR-0003: A small, deterministic expression language with a hand-written parser

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: rule-engine

## Context
Rule conditions and outputs need expressions. General-purpose expression libraries allow method calls, reflection and I/O, which contradicts the no-code-execution principle and makes security review hard.

## Decision
Define a closed grammar (comparison, logic, membership, ranges, arithmetic on numbers and money, a whitelist of pure functions, explicit clock functions). Implement a recursive-descent parser and a typed evaluation tree with no third-party dependency. Type-check at publish time. Allow hosts to register additional pure functions through an SPI, listed in the cockpit.

## Options considered
1. Own grammar, hand-written parser (chosen): auditable, dependency-free, exactly the semantics documented.
2. ANTLR-generated parser: acceptable licence, but adds a generated parser and runtime for a small grammar.
3. SpEL, MVEL, JEXL, Groovy: powerful, and precisely therefore unsafe for business-user-authored rules.
4. Full DMN FEEL: large specification; a compatible subset would mislead users about compliance. FEEL remains an inspiration for syntax.

## Consequences
Some expressions users expect from general languages are unavailable by design. DMN import (milestone 3) maps FEEL subsets onto this language and rejects what it cannot express.
