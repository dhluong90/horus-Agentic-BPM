---
name: system-architecture-blueprint
description: "Create complete system architecture blueprints and DESIGN.md documents using arc42 and Designing Data-Intensive Applications (DDIA) principles plus multi-step dependency DAGs: Context & Boundaries, Bounded Context Decomposition, Trade-off Matrices, 10x Scale & Bottleneck Math (connections, lock contention, p99 latency budget, memory), Failure Modes & Blast Radius, and Verification Gates. Use when designing a new module/accelerator, completing DESIGN.md, or planning multi-PR architectural work. Adapted from affaan-m/ECC (blueprint) and arc42/DDIA."
---

# System Architecture Blueprint

Turn product requirements and ADRs into an implementation-ready `DESIGN.md` or multi-PR construction blueprint that engineers can execute cold without guessing boundaries, concurrency behavior, or failure handling.

## When to Use
- Writing `DESIGN.md` for a new HORUS module (`rule-engine`, `process-engine`, `cockpit`) or banking accelerator (`ledger`, `digital-wallet`, `onboarding-kyc`).
- Designing cross-module boundaries, storage models, or deployment topologies before code is written.
- Estimating system behavior when traffic, tenant count, or rule/process volume grows **10x**.

**Do not use** for single-file bug fixes or trivial UI styling tweaks.

---

## Mandatory Sections of a Complete Architecture Blueprint (`DESIGN.md`)

Every `DESIGN.md` must answer seven concrete engineering questions with numbers, schemas, and contracts — never vague prose:

### 1. Context, Boundaries & Explicit Non-Goals
- Who calls this system (Java SPI, REST, events, scheduled jobs) and what downstream dependencies does it call?
- **State Ownership:** Exactly which PostgreSQL tables or schemas does this component own exclusively? (Zero shared-database reads across services.)
- **Explicit Non-Goals:** What is deliberately excluded from this boundary (e.g., no standalone server, no JPA/Hibernate, no arbitrary scripting)?

### 2. Bounded Context & Module Decomposition
- Break internal structure into modules with strict inward dependency direction (e.g. `*-core` -> `*-postgres` / `*-rest` / `*-spring-boot-starter`).
- Show a **Dependency DAG**: highlight which layers can be built in parallel vs serially, and state the invariants verified at each edge (e.g. ArchUnit checks).

### 3. Domain Model & State Machines
- List core aggregates, value objects (`Money`, `TenantId`, `RuleSetVersion`, `ProcessInstance`), and immutable records.
- Document every stateful entity's lifecycle as a **State Transition Table** (`From State` | `Event/Command` | `Guard` | `To State` | `Emitted Event`).

### 4. Trade-Off Decision Matrix (with Real Consequences)
When choosing between approaches, compare at least 2 options across concrete axes:
- Operational footprint (extra infra vs host PostgreSQL)
- Failure semantics (atomicity, idempotency, lock hold duration)
- Upgrade & backward-compatibility cost
- Link every significant choice to an `ADR-NNNN` record.

### 5. 10x Scale & Bottleneck Math ("Numbers Before Conclusions")
Never claim *"the design scales"* without back-of-the-envelope capacity math:
- **DB Connection Pool Budget:** Max concurrent sync TXs vs async wait-state calls (`agentTask` / HTTP must run *outside* DB transactions so HikariCP pools never saturate).
- **Lock Contention Hotspots:** Which rows take `FOR UPDATE` locks (e.g., hot GL suspense accounts, `parallelGateway` parent instances, timer queues using `SKIP LOCKED`)? Compute max ops/sec per row.
- **Memory & Cache Footprint:** Compiled AST size per rule set × max active versions per tenant; invalidation propagation latency across pods.
- **Latency Budget (p50 / p99):** Break down the hot path step-by-step (e.g. `<100 µs` rule evaluation + 1 DB `INSERT` for decision log).

### 6. Failure Modes, Blast Radius & Recovery
For every external dependency, timer, or multi-step transition, fill:
| Failure Scenario | Blast Radius | Detection | Recovery / Compensation |
|---|---|---|---|
| Pod crashes mid-`agentTask` | Single instance | Lease expiration (`locked_until < now()`) | Reclaim via `SKIP LOCKED`; retry up to `max` then raise incident |
| Duplicate client retry | Single request | `idempotency_key` unique index | Return stored response without re-executing side effects |
| Pod 1 publishes rule, Pod 2 has stale cache | One replica | Postgres `LISTEN/NOTIFY` + epoch check | Invalidate local compiled entry; reload on mismatch |

### 7. Verification & Cold-Start Construction Plan
- List executable verification gates: `ArchUnit` rules, `Testcontainers` multi-replica/concurrency tests, `jqwik` property-based tests, `JMH` latency budgets, and migration rollback tests.
- Split construction into one-PR vertical increments with explicit `depends_on`, scope, and verification commands.

---

## HORUS Program Integration
- `program-architect` must apply this skill whenever authoring or reviewing `<module>/docs/DESIGN.md` in `horus-Agentic-BPM` or `<kind>/<name>/docs/DESIGN.md` in `Banking-Accelerator-Program`.
- Cross-reference `c4-and-banking-integration-diagrams` for visual C4/Sequence/DMZ diagrams and `technical-spec-and-design-docs` for pairing `DESIGN.md` with numbered `SPEC.md` statements (`RLE-*`, `PRE-*`, `CKP-*`).
