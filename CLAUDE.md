# HORUS Agentic BPM

Lightweight, embeddable **Process Engine** and **Rule Engine** for Java 21 / Spring Boot 3.4+, PostgreSQL 14+, and an embeddable React Operations Cockpit, where **humans, services, and AI agents are first-class process participants**.

Source-available under **PolyForm Noncommercial License 1.0.0** (commercial use, including HORUS Banking Accelerators, under `COMMERCIAL-LICENSE.md`).

Read these before doing product, design, or implementation work:
- `README.md` and `wiki/Home.md` — product overview, positioning, and design principles
- `wiki/Architecture.md` — library-only model, modules, storage prefixes (`rle_`, `pre_`)
- `wiki/Technical-Requirements.md` — numbered `TR-*` release gates (`Must` vs `Should`)
- `wiki/Technical-Stack.md` — approved stack (`JdbcClient`, Flyway, Jackson, recursive-descent expression parser, ULID)
- `wiki/Roadmap.md` — outcome-driven milestones (`M0`–`M3`) and Red-Team kill assumptions
- `docs/adr/` — Architecture Decision Records (`ADR-0001` through `ADR-0007`)
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

## Agent Team & Skills

Agent definitions live in `.claude/agents/` and skills live in `.claude/skills/`.

- **Product & Strategy (`product-manager`):** Leads positioning (`product-positioning`), strategy & moat testing (`product-strategy-and-moat`), outcome roadmaps (`outcome-roadmap`), Lean JTBD specs (`lean-jtbd-prd`), 5-layer platform & cockpit UX (`platform-product-design`), `agentTask` autonomy & evals (`ai-agent-product-and-evals`), prioritization (`opportunity-and-prioritization`), vertical slicing (`epic-vertical-slicing`), and competitive battlecards (`b2b-battlecard-and-pricing`). Runs `strategy-red-team` before any major plan or ADR approval.
- **Architecture & Domain (`program-architect`, `banking-domain-expert`, `banking-analyst`):** Own module boundaries (`hexagonal-and-modulith-architecture`), blueprints & 10x math (`system-architecture-blueprint`), API/event contracts (`api-and-event-contracts`), PostgreSQL & migrations (`postgres-and-migration-architecture`), Async Wait-State & sagas (`distributed-tx-and-banking-ledger`), DSL compiler & token engine internals (`engine-internals-and-dsl`), starter & security arch (`springboot-starter-and-security-arch`), blast-radius reviews (`architecture-critic-and-review`), `SPEC.md`/`DESIGN.md` (`technical-spec-and-design-docs`), and ADRs (`adr-writing`).
- **Delivery, Docs & Quality (`delivery-lead`, `backend-engineer`, `frontend-engineer`, `qa-engineer`, `devops-engineer`, `security-reviewer`, `tech-writer`):** Own vertical slice delivery, ArchUnit/JMH/jqwik/Testcontainers/Playwright verification, security review, C4/integration diagrams (`c4-and-banking-integration-diagrams`), Diátaxis living docs (`diataxis-living-documentation`), clear technical prose & runbooks (`clear-technical-prose-and-runbooks`), and wiki sync (`TR-QA-02`). All 30 shared skills live in `.claude/skills/`.

## Conventions
- Branches: `feat/<module>/<slug>`, `fix/...`, `docs/...`, `chore/...`. Keep `main` buildable.
- Commits: Conventional Commits with module scope (`rule-engine`, `process-engine`, `cockpit`, `wiki`, `docs`).
- Keep `wiki/` and `<module>/docs/` consistent: `wiki/` documents public contracts and user-facing specs; `<module>/docs/` holds `SPEC.md`, `DESIGN.md`, `TEST-PLAN.md`, and module ADRs.
