# HORUS Agentic BPM

Lightweight, embeddable **Process Engine** and **Rule Engine** for Java 21 (Java 25 tested) / Spring Boot 4.x (ADR-0011; 3.5.x support lands with the compatibility matrix, #9), PostgreSQL 14+, and an embeddable React Operations Cockpit, where **humans, services, and AI agents are first-class process participants**.

Source-available under **PolyForm Noncommercial License 1.0.0**; commercial use under `COMMERCIAL-LICENSE.md`.

Read these before doing product, design, or implementation work:
- `README.md` and `wiki/Home.md` — product overview, positioning, and design principles
- `wiki/Architecture.md` — library-only model, modules, storage prefixes (`rle_`, `pre_`)
- `wiki/Technical-Requirements.md` — numbered `TR-*` release gates (`Must` vs `Should`)
- `wiki/Technical-Stack.md` — approved stack (`JdbcClient`, Flyway, Jackson, recursive-descent expression parser, ULID)
- `wiki/Roadmap.md` — outcome-driven milestones (`M0`–`M3`) and Red-Team kill assumptions
- `docs/adr/` — Architecture Decision Records (`ADR-0001` onwards; `ADR-0011` sets the Spring Boot 4.x baseline)
- `process-engine/docs/`, `rule-engine/docs/`, `cockpit/docs/` — per-module `SPEC.md`, `DESIGN.md`, `TEST-PLAN.md`

## Non-negotiables (Architectural & Product Guardrails)

1. **Libraries, never a separate server (ADR-0004).** Engines run inside the host Spring Boot JVM (`*-core` has zero Spring dependency; `*-postgres` uses Spring JDBC `JdbcClient`). No standalone engine cluster, no Elasticsearch, no external message broker requirement.
2. **Host PostgreSQL ownership & Transaction boundary split.** State lives in the host PostgreSQL under `rle_*` and `pre_*` with `tenant_id` first in every index.
   - **Sync In-Transaction steps** (state updates, `RuleEngine.evaluate`, local transactional hooks) participate in the host's transaction.
   - **Async Wait-State steps** (`agentTask`, remote HTTP/MCP `serviceTask`) MUST NEVER hold DB connections/locks during LLM or network I/O: claim & lock with lease -> commit TX -> execute adapter outside TX -> complete/fail/raise incident in a fresh TX.
3. **Multi-replica safety & Cache Coherence.** Timers/jobs use `SELECT ... FOR UPDATE SKIP LOCKED` with lease expiration; `parallelGateway` joins lock `pre_instance` row to prevent lost wakeups; compiled rule caches invalidate across replicas via PostgreSQL `LISTEN/NOTIFY` paired with lightweight version-epoch fallback.
4. **Zero arbitrary code execution (`TR-SEC-01`, ADR-0003).** Expressions use a hand-written recursive-descent parser and typed evaluation tree with whitelisted pure functions, `Money` currency safety, bounded/timed regex, and explicit evaluation `Clock` for deterministic replay.
5. **Agentic with deterministic rails.** Every `agentTask` enforces bounded inputs, JSON Schema output validation, deterministic Rule Engine guardrails (`onViolation: route-to-human` via maker-checker `userTask` or `fail`), timeout/retry, immutable trace, and offline/online Eval gates.
6. **Tenant & Maker-Checker enforcement at the service layer.** `tenant_id` comes only from the configured resolver (never request body); publication requester cannot approve their own version across Java or REST APIs.
7. **Supply-chain & Spec-to-Test traceability.** Permissive third-party licences only (Apache 2.0, MIT, BSD); every `SPEC.md` statement has a named test (`TR-QA-01`).


## Build
- `mvn -B verify` from the repository root builds every module and runs unit, ArchUnit and Testcontainers tests (needs JDK 21 or later and Docker). CI runs the same command (`.github/workflows/ci.yml`).
- Layout: `pom.xml` (parent, Spring Boot BOM import), `bpm-bom/` (pins engine modules for hosts, no parent), `bpm-host-metadata/` (serves `/api/bpm/meta` and `/api/bpm/me` once per host; each engine starter contributes a `ModuleDescriptor`), `bpm-expression/` (money, value mapping and the expression language shared by both engines, JDK only, ADR-0027), `rule-engine/rule-engine-{core,postgres,spring-boot-starter}`, `examples/rule-engine-only/`. Java packages `io.github.dhluong90.bpm.rules.*`; groupId `io.github.dhluong90.bpm` (set once in the parent, ADR-0022).
- Test names start with the id they prove, upper case, hyphens as underscores: `RLE_013_...` for a `SPEC.md` statement, `TR_EMB_01_...` for a requirement. This is the prefix the spec-to-test gate (TR-QA-01) matches; tests that prove no id carry no prefix. See `rule-engine/docs/TEST-PLAN.md`.

## Conventions
- Branches: `feat/<module>/<slug>`, `fix/...`, `docs/...`, `chore/...`. Keep `main` buildable.
- Commits: Conventional Commits with module scope (`rule-engine`, `process-engine`, `cockpit`, `wiki`, `docs`).
- Keep `wiki/` and `<module>/docs/` consistent: `wiki/` documents public contracts and user-facing specs; `<module>/docs/` holds `SPEC.md`, `DESIGN.md`, `TEST-PLAN.md`, and module ADRs.
