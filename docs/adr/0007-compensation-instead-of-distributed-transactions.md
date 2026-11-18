# ADR-0007: Saga-style compensation instead of distributed transactions

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: process-engine

## Context
Banking processes call systems that commit independently (core banking, card processors, partners). When a later step fails, earlier effects cannot be rolled back by the engine's transaction.

## Decision
Steps that cause external effects declare a `compensate` handler. When a step fails after retries, or a process throws compensation explicitly, the engine runs the compensate handlers of completed steps in reverse order, each with its own retry and incident handling, and records every compensation in history. A `saga` block scopes which steps compensate together. Two-phase commit is not supported.

## Options considered
1. Declared compensation handlers, reverse order (chosen): explicit, auditable, matches how banks already reconcile.
2. XA / two-phase commit: unsupported by most counterparties; blocks resources; hides failures.
3. Leave compensation to the host: every process reimplements it and the cockpit cannot show it.

## Consequences
Compensate handlers must be idempotent (ADR-0006). The engine cannot compensate what a handler does not know how to undo; such steps are marked `compensate: manual` and raise an incident for operations.
