# ADR-0008: External steps run as Async Wait-State with a lease, never inside a database transaction

- Status: proposed
- Date: 2026-11-18
- Deciders: maintainers
- Module: process-engine

## Context
`agentTask` and remote `serviceTask` steps call models, MCP tools and other services. Such calls take seconds to minutes. The engine runs inside the host JVM and shares the host's connection pool (ADR-0002). A pool of 20 connections is exhausted by 20 concurrent calls if each call holds its transaction open, and a row lock held during a call blocks every other replica touching that instance.

## Decision
Every step that leaves the JVM runs in three phases. TX1 claims the step: it writes a job row with `status = CLAIMED`, `locked_by`, `locked_until = now() + timeout + lease margin` (step `timeout`, default `PT30S`; `jobs.lease-margin`, default 30 s; `now()` is the database clock, never the JVM clock) and an incremented attempt, then commits. The adapter runs with no connection and no lock and receives the `attemptId`; the worker cancels the call when the step timeout elapses, so a live attempt reaches TX2 before its lease ends and is never taken over. TX2 re-reads the job `WHERE locked_by = me AND locked_until > now()`, then records the result, validates output and guardrails, and advances, retries with backoff, or opens an incident. If the lease was taken over, TX2 discards the result. Compensation handlers follow the same protocol. Sync steps (`decision`, gateways, `userTask` state changes, local beans) stay inside the current transaction.

## Options considered
1. Async Wait-State with lease (chosen): bounded connection use (0 connections while waiting), crash recovery by lease expiry, replica-safe by `SKIP LOCKED`; costs two short transactions per step and requires idempotent adapters.
2. Call inside the step transaction: simplest code and atomic with engine state; one slow call per connection, pool exhaustion at low concurrency, locks held for the call duration, no recovery when the JVM dies mid-call.
3. External job executor or broker: scales workers independently; adds infrastructure the project exists to avoid (ADR-0004) and splits state between the database and the broker.

Lease length, within option 1:

| Option | Takeover of a live attempt | Recovery after a crash | Cost |
|---|---|---|---|
| A. Fixed 60 s lease | Yes, for any step whose timeout exceeds 60 s: a `PT5M` agent step is reclaimed at 60 s while the first call is still running, so the model runs twice and the first result is discarded | 60 s | None extra |
| B. Step timeout plus 30 s margin (chosen) | No: the call is cancelled at the timeout and TX2 needs about 5 ms; the 30 s margin absorbs GC pauses, scheduling delay and TX2 queueing | Timeout plus 30 s (5 min 30 s for `PT5M`) | None extra; the lease is computed once at claim |
| C. Short lease renewed by a heartbeat | No, while the heartbeat runs | About two heartbeat intervals | One `UPDATE` per in-flight call per interval (100 calls at 10 s = 10 writes/s); a heartbeat thread that can stall independently of the call; a renewal is a database write during the call, which this ADR exists to minimise |

B is chosen because the timeout already bounds every call, so a lease derived from it is correct without extra writes. The longer crash recovery for long steps is acceptable: the same step legitimately takes up to its timeout. C is revisited only if steps with timeouts above about 15 minutes appear, where crash recovery latency would matter. Timers and SLA escalations make no external call and keep a fixed lease (`jobs.timer-lease`, default 60 s).

## Consequences
Adapters must be idempotent by `attemptId` (`TR-IDEM-04`): with option B a takeover only happens after a crash or a stall longer than the margin, but then a late result is discarded while its side effect may have happened. Changing a step's `timeout` changes its lease and its crash recovery time together. The engine part of an async step costs about 10 ms of database time. Hosts size adapter thread pools, not the connection pool, for agent concurrency. Verified by `TR-ASY-01`, `TR-ASY-02`, `PRE-009` and `PRE-017`; documented in `wiki/Architecture.md` section 4.
