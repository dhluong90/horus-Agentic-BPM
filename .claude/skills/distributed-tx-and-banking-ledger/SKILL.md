---
name: distributed-tx-and-banking-ledger
description: "Architect double-entry accounting ledgers, transactional outbox + Kafka idempotent consumers, Saga reverse compensation (ADR-0007), hot-account lock contention mitigation, and Sync In-Transaction vs Async Wait-State boundaries so slow external calls never hold DB connections. Use when designing Ledger, Digital Wallet, Payments, Deposits, Lending, or Process Engine saga/compensation flows. Grounded in DDIA and HORUS banking standards."
---

# Distributed Transactions, Sagas & Banking Ledger Architecture

Design financial state transitions and multi-system workflows that preserve strict accounting correctness without distributed two-phase commit (XA) or held database locks across network calls.

## When to Use
- Designing `platform/ledger`, `accelerators/digital-wallet`, `deposits`, `lending`, or `payments`.
- Designing `compensate` handlers, `saga` blocks, or `agentTask` / `serviceTask` transaction boundaries in `horus-Agentic-BPM` (`ADR-0007`).

---

## 1. Double-Entry Ledger Pattern (Immutable Core)

### Invariants (Never Violated)
1. **Zero-Sum Journal Entries:** Every financial movement writes one immutable `journal_entry` header and $\ge 2$ `posting_leg` rows (`DEBIT` or `CREDIT`) in a single ACID transaction where `SUM(debits) == SUM(credits)` per currency. Reject unbalanced postings in domain code *and* enforce via DB trigger/constraint if applicable.
2. **Append-Only (Never UPDATE/DELETE Postings):** Errors or refunds NEVER edit historical postings. Post an explicit **Reversal / Storno** journal entry referencing `reverses_entry_id` with flipped debit/credit legs.
3. **Available Balance vs Posted (Ledger) Balance:**
   - Card authorizations, pending transfers, and escrow workflows create an explicit **Hold / Reservation** record (`available_balance = posted_balance - active_holds`) rather than moving cash prematurely.
   - Settlement converts/releases the hold and creates the final `journal_entry`.
4. **Hot-Account Lock Contention Mitigation:**
   - System-wide accounts (Fee Revenue, Master Suspense, Merchant Settlement) receive thousands of postings/sec. Locking a single balance row with `SELECT ... FOR UPDATE` serializes the entire bank!
   - **Fix:** (a) Lock only customer-side accounts eagerly (low contention); (b) Shard high-throughput GL/suspense accounts into $N$ sub-buckets (`fee-gl-00..15`) or compute GL summaries asynchronously from the append-only posting log; (c) Sort multi-account lock acquisition deterministically (`ORDER BY account_id ASC`) to eliminate deadlocks.

---

## 2. Sync In-Transaction vs Async Wait-State (The Golden Boundary Rule)

**Rule:** NEVER perform HTTP requests, Kafka network calls (outside transactional outbox), MCP calls, or LLM inference inside an open `@Transactional` database boundary.

| Execution Mode | Used For | Transaction Protocol | Why |
|---|---|---|---|
| **Sync In-Transaction** | Local domain writes, `RuleEngine.evaluate`, outbox inserts, `userTask` state updates | Executes inside host's active `@Transactional` boundary; commits or rolls back atomically with host data | Guarantees zero drift between host business state and engine/outbox state |
| **Async Wait-State** | `agentTask` (LLM/OCR/MCP), external `serviceTask` (core banking, eKYC, payment rail) | **TX 1:** Claim step with lease (`CLAIMED`, `locked_until`) & commit → **Outside TX:** Invoke adapter with bounded timeout → **TX 2:** Verify lease/version, record result, evaluate guardrails, advance or raise incident | Prevents slow external calls (5s–5m) from starving HikariCP connection pools or holding Postgres row locks |

---

## 3. Saga & Reverse Compensation (`ADR-0007`)
Banking workflows span independent systems (Wallet Ledger, Card Processor, External Rail). Since XA/2PC is brittle and unsupported across external REST APIs:
1. **Forward Recovery + Backward Compensation:** Every external-effect step declares an idempotent `compensate` handler (or `compensate: manual` to raise an operations incident).
2. **Idempotency Keys on Every Leg (`ADR-0006`):** Pass `(instance_id, step_id, attempt_id)` as the deterministic idempotency key to both forward handlers and compensate handlers.
3. **Reverse Execution Order:** On unrecoverable failure (retries exhausted) or explicit `throwCompensation`, walk the completed step stack in **strict LIFO reverse order**, persisting a history record and dedicated retry policy for each compensation step.

---

## 4. Transactional Outbox + Idempotent Consumers
- **Publish Safely:** Insert domain event into `outbox_event` inside the **same DB transaction** as the business/ledger write. Relay asynchronously to Kafka via polling (`SKIP LOCKED`) or Debezium CDC.
- **Consume Idempotently:** Consumers store `(consumer_group, event_id)` in a `processed_event` deduplication table inside the consumer's local transaction so at-least-once Kafka delivery becomes effectively-once state mutation.

## HORUS Program Integration
- Enforces HORUS Non-negotiable #4 (*idempotent financial operations, double-entry ledger, immutable audit trail*) and `horus-Agentic-BPM` `ADR-0006` / `ADR-0007`.
- `architecture-critic-and-review` flags any external I/O inside `@Transactional` or missing debit/credit balance check as a **BLOCKING** finding.
