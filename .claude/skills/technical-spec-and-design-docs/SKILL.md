---
name: technical-spec-and-design-docs
description: "Author concrete, unambiguous, testable SPEC.md, DESIGN.md, and TEST-PLAN.md documents with numbered specification IDs (RLE-001, PRE-001, CKP-001), Given/When/Then scenarios, State Transition Tables, Error Code Matrices, SQL DDL specs, and a 100-point Requirements Clarity Gate. Ensures every spec statement maps 1:1 to an automated test name (TR-QA-01). Adapted from affaan-m/ECC (ralphinho-rfc-pipeline) and softaworks/agent-toolkit (requirements-clarity)."
---

# Technical Spec & Design Document Authoring

Eliminate empty specification stubs and ambiguous prose ("should be fast", "handle errors gracefully"). Write specs that engineers, QA, and security reviewers can implement and verify without guessing.

## When to Use
- Filling out `<module>/docs/SPEC.md`, `<module>/docs/DESIGN.md`, or `<module>/docs/TEST-PLAN.md` in `horus-Agentic-BPM`.
- Writing `<kind>/<name>/docs/DESIGN.md` and `TEST-PLAN.md` in `Banking-Accelerator-Program`.
- Auditing a draft spec before handing work units to `backend-engineer` / `frontend-engineer` / `qa-engineer`.

---

## 1. The 100-Point Clarity Gate (Before Finalizing Any Spec)
Score your draft specification before declaring it ready for implementation (`>= 90` required):
- **Functional & Behavioral Clarity (30 pts):** Concrete inputs/outputs, exact state transitions, explicit failure paths (`unique` 0/2+ matches, expired leases, self-approval rejection).
- **Technical Specificity (25 pts):** Exact Java interfaces, HTTP paths/methods/codes, SQL tables/indexes, concurrency/locking protocol, clock/determinism guarantees.
- **Completeness & Edge Cases (25 pts):** Boundary values, currency mismatch, null handling, concurrent duplicate keys, timeout/retry exhaustion, rollback/compensation.
- **Traceability (20 pts):** Every statement carries a stable ID mapped 1:1 to a named automated test (`TR-QA-01`) and linked back to `Technical Requirements` (`TR-*`) or `RULES.md`.

---

## 2. Format for `SPEC.md` (Testable Behavior Contract)

Never leave generic placeholders. Group numbered specification statements logically and write every single one in testable **Given / When / Then + Concrete Example** format:

```markdown
## <PREFIX>-001 <Concise behavioral rule statement>
- **Traces to:** `TR-DET-01` / `<ACC>-R-001`
- **Given:** Exact initial state, tenant, version, clock, and configuration.
- **When:** Exact Java call, HTTP request (method + path + headers + body), or timer tick.
- **Then:** Exact return value, HTTP status + JSON body, DB rows inserted, and emitted event.
- **Example:** Concrete inputs with real currency/minor units (e.g. `5000000 VND`) and exact expected output.
- **Test ID:** `<PREFIX>_001_<snake_case_test_name>`
```

### Mandatory Edge-Case Categories in Every `SPEC.md`
1. **Happy Path & Variations** (all hit policies / gateway types / UI workflows)
2. **Validation & Type Safety** (publish-time rejection, currency mismatch, null rules, invalid schema)
3. **Concurrency, Idempotency & Locking** (same `Idempotency-Key` with same vs different payload; stale `version` -> `409 Conflict`; concurrent `parallelGateway` join; `SKIP LOCKED` lease takeover)
4. **Security & Multi-Tenancy** (cross-tenant isolation; request-body `tenantId` ignored; maker-checker self-approval rejected at service & REST layers; PII masking via keyed HMAC)
5. **Failure, Timeout & Recovery** (regex timeout, adapter timeout, retries exhausted -> incident/compensation)

---

## 3. Format for `DESIGN.md` (Internal Engineering Architecture)
Pair every `SPEC.md` with a `DESIGN.md` containing:
1. **Context & Module Boundaries** (package/module DAG + `ArchUnit` invariants)
2. **Domain Model & State Machine Tables** (`From` | `Trigger` | `Guard` | `To` | `Side Effect`)
3. **Public Java SPIs & REST Contracts** (exact method signatures, records, error code table)
4. **Storage Model (Complete PostgreSQL DDL)** (`CREATE TABLE`, `tenant_id` leading indexes, partitioning notes, Flyway migration numbering)
5. **Runtime & Concurrency Mechanics** (caching & `LISTEN/NOTIFY`, Sync TX vs Async Wait-State steps, 10x capacity math)
6. **Open Decisions & ADR Links** (never leave unowned "TBD" items — name decider and target gate)

---

## 4. Format for `TEST-PLAN.md` (1:1 Traceability Matrix)
```markdown
| Spec / Rule ID | Requirement | Test Level (Unit / Property / Testcontainers / WebMvc / Playwright / JMH) | Exact Test Name |
|---|---|---|---|
| `RLE-001` | `TR-DET-01` | Unit + jqwik | `RLE_001_first_hit_policy_selects_top_matching_rule` |
```
An unmapped spec ID is a **build blocker** in CI (`TR-QA-01`).

## HORUS Program Integration
- Replaces empty 9-line stubs in `horus-Agentic-BPM/*/docs/SPEC.md` and `DESIGN.md`.
- Gives `program-architect`, `banking-analyst`, `qa-engineer`, and `tech-writer` a unified, machine-verifiable specification contract.
