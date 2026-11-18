---
name: adr-writing
description: Writing architecture decision records for a HORUS program - when a decision needs one, the template, numbering (program-wide ADR-nnnn or product-level ACC-ADR-nnnn), statuses and how to supersede. Use when recording a technical or architectural decision, or when asked to document why something was chosen.
---
# ADR writing

## When
Any choice a future engineer would ask "why?" about: stack, decomposition, data ownership, tenancy model, build strategy (build vs embed vs buy), contract versioning, security model. If two reasonable engineers could choose differently, write one.

## Where and numbering
- Program-wide: `docs/adr/NNNN-kebab-title.md`, id `ADR-NNNN`.
- Product-level: `<kind>/<name>/docs/adr/NNNN-kebab-title.md`, id `<ACC>-ADR-NNNN`.
- Next number = highest existing + 1. Never reuse or renumber.

## Template (copy `docs/adr/0000-template.md`)
Title, Status (proposed | accepted | superseded by X), Date, Deciders, Accelerator, Context, Decision (one or two sentences), Options considered (at least two, with pros, cons, cost), Consequences (easier, harder, obligations).

## Rules
- The decision is a sentence, not an essay. The reasoning goes in Options and Consequences.
- Name who decided and when. Agents propose; the product owner or architect accepts.
- To change a decision, write a new ADR and mark the old one "superseded by". Never delete.
- Link the ADR from the design document and from the issue that triggered it.
