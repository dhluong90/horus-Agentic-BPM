# ADR-0020: Execution tree with scope tokens, event subscriptions and cancellation

- Status: accepted, 2026-11-19
- Date: 2026-11-18 (proposed), 2026-11-19 (accepted)
- Deciders: maintainers; accepted by the product owner, including the M2 effort (PD-059 option 1)
- Module: process-engine

## Context
The M2 runtime keeps positions as `pre_token` rows with a text `scope_id`, serialises every transition of an instance on the `pre_instance` row lock, and runs timers and async steps through the `pre_timer` queue with leases. Full BPMN (ADR-0017) adds subprocesses, event subprocesses, multi-instance, boundary events on wait states, message and signal waits, inclusive joins and scoped compensation. If M2 writes joins and compensation against flat scopes, S1 rewrites them and migrates every in-flight instance.

## Decision
1. Every scope (process, subprocess, transaction, event subprocess, multi-instance body and instance) is a token row (kind `SCOPE` or `MI_BODY`); flow tokens reference their scope through `scope_token_id`. M2 creates the root scope token and one per `saga`; joins and compensation are keyed by scope token.
2. Token status `CANCELLED` and job status `CANCELLED` exist from M2. Instance cancel, migration off a node, and from S1 interrupting boundaries and terminate end events cancel tokens, tasks and jobs under the instance lock; a late async result finds `CANCELLED` in TX2 and is discarded and recorded (applied now, `PRE-041`).
3. Timers of every kind stay jobs in `pre_timer`. Message, signal and conditional waits are rows in `pre_event_subscription` (S2), with one unique index probe per message correlation and batched, cursor-driven signal delivery of at most 100 instance locks per transaction.
4. Error and escalation are resolved by walking the execution tree upward under the instance lock; link events at compile time.
5. Correlation and broadcast are in-process, in-transaction APIs; throws go to the host outbox through `MessageAdapter`. The engine owns no transport.

Details and DDL: [`standards-compliance.md`](../architecture/standards-compliance.md) section 5.

## Options considered
1. Scope tokens in M2, subscriptions in S2 (chosen). About 4 days in M2; joins and compensation are written once; later stages add rows and tables, never reshape existing ones.
2. Keep flat scopes in M2 and introduce scope tokens in S1. Nothing now; S1 rewrites the join and compensation code and migrates every in-flight instance's tokens.
3. One generic subscription table for timers too, replacing `pre_timer` for waits. Uniform, but splits the job queue that already gives one poller and one partial index for timers, SLA escalations and async leases; no gain for the cost.

## Consequences
- `pre_token.scope_id TEXT` is replaced by `token_kind` and `scope_token_id VARCHAR(26)` with a self-reference in the first M2 migration, and `pre_compensation_stack.scope_id` by `scope_token_id` (process engine DESIGN section 7). Requirement `TR-STD-29`; statement `PRE-044`.
- Each instance gets one extra token row: 1 million instances a month add about 150 MB a month.
- TX2 of the Async Wait-State checks `status = 'CLAIMED'` in addition to the lease.
- New S2 tables `pre_event_subscription` and `pre_signal`, new job kinds `BOUNDARY_TIMER`, `START_TIMER`, `SIGNAL_DELIVERY`, new APIs `MessageService` and `SignalService`, all additive.

## History
- 2026-11-18: proposed by the maintainers, with a decision needed by the product owner for the M2 part (about 4 days).
- 2026-11-19: accepted by the product owner as PD-059 option 1, before any M2 migration exists.
