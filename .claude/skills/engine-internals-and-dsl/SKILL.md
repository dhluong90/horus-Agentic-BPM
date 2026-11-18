---
name: engine-internals-and-dsl
description: "Design safe DSL compilers (recursive-descent parser, Java 21 sealed interface + record typed AST, publish-time static type checking, sub-100us evaluation, Money currency safety, ReDoS-bounded regex), Execution Token Trees for BPM parallelGateway fork/join without lost wakeups, and batched process instance migration. Use when designing horus-Agentic-BPM rule-engine or process-engine internals. Adapted from affaan-m/ECC (latency-critical-systems & regex-vs-llm-structured-text)."
---

# Engine Internals, Safe DSL Compiler & State Machine Runtime

Architect sub-100µs deterministic rule evaluators and race-free workflow execution engines in pure Java 21 (`*-core`).

## When to Use
- Designing `rule-engine-core` (lexer, recursive-descent parser, type checker, compiled evaluator, hit policies).
- Designing `process-engine-core` (execution tokens, `exclusiveGateway` / `parallelGateway`, timers, compensation stack, instance migration).
- Optimizing hot paths to meet strict p99 latency and determinism requirements (`TR-DET-01..05`, `TR-PERF-01..05`).

---

## Part 1: Safe Rule DSL Compiler & Sub-100µs Evaluator

### 1. Security & Determinism Guarantees (`TR-SEC-01`, `TR-DET-01`)
- **Zero Code Execution:** Never embed SpEL, MVEL, JEXL, Groovy, or reflection (`Method.invoke`).
- **Two-Phase Compile vs Evaluate:**
  - **Phase A — Parse & Static Type-Check (at Save / Publish time):** Hand-written lexer + recursive-descent parser turns expression strings into an immutable Java 21 `sealed interface Expr` AST (`BinaryExpr`, `UnaryExpr`, `LiteralExpr`, `VarRefExpr`, `CallExpr`, `InListExpr`, `BetweenExpr`, `IsNullExpr`). Validate every variable name against declared inputs, every operator against operand types (rejecting `money + number`, `money / money`, unknown functions), and pre-compile regex patterns. Reject invalid rules *before* publish.
  - **Phase B — Compiled Runtime Evaluation (Hot Path `<100 µs`):** Resolve input variable names to integer **slot indices** (`Object[] slots`) at compile time rather than `HashMap<String, Object>` string hashing on every row!
- **Money Arithmetic & Currency Safety (`TR-DET-03`):**
  - Store as `(String currency, long minorUnits)` (or `BigDecimal` scaled by ISO currency minor digits during multiplication/division with explicit `RoundingMode.HALF_EVEN`).
  - Reject currency mismatches (`VND` vs `USD`) at publish time if static, and fail evaluation deterministically with `EVALUATION_ERROR` (never coerce or silently return partial outputs).
- **ReDoS-Bounded Regex (`TR-SEC-02`):**
  - Reject catastrophic nested quantifiers at publish time (or limit pattern length & input length, and run with a bounded CharSequence / interruptible matcher with strict microsecond/millisecond timeout failing with `EVALUATION_ERROR`).
- **Explicit Clock Replay (`TR-DET-05`):**
  - Never call `Instant.now()` or `LocalDate.now()` inside evaluators. Pass `Clock` / `Instant evaluationTime` explicitly so `today()` and `now()` reproduce exact historical outputs years later.

### 2. Optimization Discipline (Hot Path vs Cold Path)
- Measure p50 / p95 / p99 via **JMH** after JVM warm-up (`TR-PERF-01`: 200-row `first` table `<100 µs`, excluding DB log insert).
- Pre-compile and cache `CompiledRuleSet` keyed by `(tenantId, ruleSetName, version)`.
- Keep evaluation allocation-free on the hot path where practical; write decision logs via single batchable `INSERT` (transactional or outbox).

---

## Part 2: Process Engine Token Tree & Concurrency Semantics

### 1. Execution Token Model (Handling `parallelGateway` & `callProcess`)
A process instance is NOT a single `current_step` string — `parallelGateway` forks multiple active execution branches simultaneously:
- Represent active paths as rows in `pre_token (id, tenant_id, instance_id, parent_token_id, scope_id, current_node_id, status)`.
- **Preventing Lost Wakeups on `parallelGateway` Join:**
  - When Branch A and Branch B complete concurrently on two different replicas:
    1. Acquire row-level lock on the parent `pre_instance` (`SELECT ... FROM pre_instance WHERE tenant_id = ? AND id = ? FOR UPDATE`).
    2. Mark arriving token `JOINED` / `WAITING_AT_JOIN`.
    3. Count remaining active/arrived tokens for that join gateway inside the locked instance scope.
    4. Only the thread that observes all incoming branches arrived advances past the join and emits the outgoing token(s).
  - Never check join readiness without locking `pre_instance`, or both threads will miss the trigger.

### 2. Batched In-Flight Process Instance Migration (`TR-MIG-01/02`)
When migrating running instances from `v1` to `v2`:
1. **Plan & Dry-Run (`:validate`):** Compute active node histogram on `v1`; verify every active node maps explicitly or by identical ID in `v2`, flag unmapped tokens or active `parallelGateway` scope mismatches before mutating anything.
2. **Batched Execution (`:execute`):** Process in small chunks (e.g. 100 instances per transaction), locking each `pre_instance` row individually, updating `definition_version` + remapping `pre_token.current_node_id`, and writing a `MIGRATED` `pre_history` record. Failure on one instance creates an incident on that instance only without rolling back the entire batch.

## HORUS Program Integration
- Core technical guide for `horus-Agentic-BPM/rule-engine/` (`SPEC.md`/`DESIGN.md`) and `process-engine/` (`SPEC.md`/`DESIGN.md`).
- Pairs with `postgres-and-migration-architecture` for lock/index design and `distributed-tx-and-banking-ledger` for Async Wait-State and Compensation semantics.
