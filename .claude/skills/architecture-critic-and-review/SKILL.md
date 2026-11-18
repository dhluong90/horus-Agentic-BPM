---
name: architecture-critic-and-review
description: "Perform evidence-backed impact analysis and rigorous architecture/PR reviews before implementation and before merge: blast-radius mapping, cross-module coupling checks, tenant-isolation verification, transaction-boundary leak detection, OpenAPI drift checks, and post-implementation alignment. Output structured BLOCKING / SHOULD-FIX / NIT findings with file:line evidence. Use when reviewing DESIGN.md, ADRs, or pull requests. Adapted from affaan-m/ECC (change-impact) and architecture-critic."
---

# Architecture Critic & Change-Impact Review

Read-only verification gate for the Chief Architect (`program-architect`). Prove blast radius from real imports, SQL queries, routes, and schemas — never guess from directory names.

## When to Use
- Reviewing a `DESIGN.md`, `ADR`, or schema migration before implementation starts.
- Performing Gate 1 design review or pre-merge pull request review (`program-architect` sign-off).
- Assessing blast radius for cross-module, public-API, database-migration, or security changes.

---

## Step 1: Pre-Change Impact Analysis (6 Dimensions)
Answer in order from real code/docs (`git grep`, `git diff`, SQL, OpenAPI):
1. **Code & Boundaries:** Which public interfaces, SPIs, direct callers, or Modulith boundaries change? Does any accelerator import another accelerator's source (`ADR-0002` violation)?
2. **Data & Schema:** Do tables, `tenant_id` indexes, migrations, serialized JSON formats, caches, or idempotency keys change? Are migrations safe during rolling upgrades?
3. **Contracts (API / Events):** Do REST paths, RFC 9457 error codes, or event payloads change? Is the change strictly additive, or does it break generated clients?
4. **Concurrency & Transaction Boundaries:** Does ANY `@Transactional` block perform HTTP calls, Kafka sync sends, MCP calls, or LLM inference? Are `FOR UPDATE` locks ordered consistently and `SKIP LOCKED` queries lease-bounded?
5. **Security & Tenancy:** Does every query filter by `tenant_id` from security context? Is maker-checker enforced at the service layer? Are PII fields masked with keyed HMAC?
6. **Tests & Living Docs:** Are `SPEC.md` / `RULES.md` IDs mapped 1:1 to tests (`TR-QA-01`), and are `wiki/` / `openapi.yaml` / `DESIGN.md` updated in the same PR (`TR-QA-02`)?

---

## Step 2: Mandatory Architectural Red-Flag Checklist

Automatically mark as **`BLOCKING`** if ANY of the following holds:
- **Source coupling across product folders** (must consume versioned packages/APIs only).
- **Framework/Network leak into `*-core`** (Spring imports, JDBC, HTTP clients, `Instant.now()` without `Clock`, reflection/scripting gadgets).
- **Missing `tenant_id` in table or SQL `WHERE` clause**, or accepting `tenantId` from request body.
- **External I/O inside `@Transactional` / DB lock** (slow LLM `agentTask` or HTTP `serviceTask` holding DB connection).
- **Floating-point money** (`float` / `double` in Java, SQL, TypeScript, or tests) or unbalanced ledger posting.
- **Unbounded or un-migrated breaking schema change** (editing an already-applied Flyway script, `NOT NULL` without default on populated table, dropping a column in the same deploy as code still reading it).
- **Contract drift** (controller payload/status differs from `openapi.yaml` or `wiki/` docs without updating the spec).
- **Self-approval bypass** (maker-checker only checked in React UI, missing service-layer identity check).

---

## Step 3: Post-Implementation Alignment Check
Compare the planned impact against `git diff`:
1. Did the PR solve the exact issue/spec IDs without unintended scope creep?
2. Do real test outputs (JUnit, Testcontainers, ArchUnit, OpenAPI build) prove the invariants — not merely mocked unit tests?
3. Are `DESIGN.md`, `SPEC.md`, `wiki/`, and `ADRs` synchronized with the code?

---

## Structured Review Output Format

Always format findings clearly so engineers can act without ambiguity:

```markdown
## Architecture Review: <PR / Design / RFC>
**Verdict:** APPROVE | REQUEST CHANGES (N blocking, M should-fix)

### Blast Radius & Alignment
- Modules / Tables / Contracts touched: ...
- Spec / Rule IDs verified: ...

### BLOCKING Findings
1. **[Short Title]** — `path/to/File.java:L42`
   - **Rule / ADR violated:** e.g. `TR-EMB-04` / `Async Wait-State boundary`
   - **Failure / Exploit Scenario:** What breaks under load, concurrency, or attack.
   - **Concrete Fix:** Exact structural or code change required.

### SHOULD-FIX Findings
1. ...

### NITs
1. ...
```

## HORUS Program Integration
- Standard review rubric for `program-architect` across both `Banking-Accelerator-Program` and `horus-Agentic-BPM`.
- Every `BLOCKING` finding blocks `delivery-lead` from merging the PR.
