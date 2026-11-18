# ADR-0006: Idempotency keys and business keys are enforced in the engine

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: project

## Context
Hosts retry on network failures. Without engine-level protection a retry can start a second process instance for the same loan application or complete the same task twice.

## Decision
Every mutating operation (start instance, complete task, publish version, evaluate with logging) accepts an idempotency key; the engine stores the key with the original result per tenant and returns that result on replay for a configurable window (default 24 hours). Process instances may carry a business key that is unique per tenant and definition; a second start with the same business key returns the existing instance instead of creating one. Tasks and instances carry an optimistic-lock version; a stale completion is rejected with a conflict.

## Options considered
1. Engine-enforced keys and locks (chosen).
2. Leave it to the host: every adopter reimplements it, differently, and the cockpit cannot rely on it.
3. Exactly-once transport: not available over plain HTTP; the engine is the natural place.

## Consequences
One small table per engine (`pre_idempotency`, `rle_idempotency`) with time-based cleanup. REST clients send `Idempotency-Key`; the Java API takes it as a parameter.
