# ADR-0008: External steps run as Async Wait-State with a lease, never inside a database transaction

- Status: proposed
- Date: 2026-11-18
- Deciders: maintainers
- Module: process-engine

## Context
`agentTask` and remote `serviceTask` steps call models, MCP tools and other services. Such calls take seconds to minutes. The engine runs inside the host JVM and shares the host's connection pool (ADR-0002). A pool of 20 connections is exhausted by 20 concurrent calls if each call holds its transaction open, and a row lock held during a call blocks every other replica touching that instance.

## Decision
Every step that leaves the JVM runs in three phases. TX1 claims the step: it writes a job row with `status = CLAIMED`, `locked_by`, `locked_until = now() + 60 s` and an incremented attempt, then commits. The adapter runs with no connection and no lock, under the declared timeout, and receives the `attemptId`. TX2 re-reads the job `WHERE locked_by = me AND locked_until > now()`, then records the result, validates output and guardrails, and advances, retries with backoff, or opens an incident. If the lease was taken over, TX2 discards the result. Compensation handlers follow the same protocol. Sync steps (`decision`, gateways, `userTask` state changes, local beans) stay inside the current transaction.

## Options considered
1. Async Wait-State with lease (chosen): bounded connection use (0 connections while waiting), crash recovery by lease expiry, replica-safe by `SKIP LOCKED`; costs two short transactions per step and requires idempotent adapters.
2. Call inside the step transaction: simplest code and atomic with engine state; one slow call per connection, pool exhaustion at low concurrency, locks held for the call duration, no recovery when the JVM dies mid-call.
3. External job executor or broker: scales workers independently; adds infrastructure the project exists to avoid (ADR-0004) and splits state between the database and the broker.

## Consequences
Adapters must be idempotent by `attemptId` (`TR-IDEM-04`), because a late result after lease takeover is discarded while its side effect may have happened. The engine part of an async step costs about 10 ms of database time. Hosts size adapter thread pools, not the connection pool, for agent concurrency. Verified by `TR-ASY-01` and `TR-ASY-02`; documented in `wiki/Architecture.md` section 4.
