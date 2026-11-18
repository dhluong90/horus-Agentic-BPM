---
name: epic-vertical-slicing
description: Break down large epics, modules, or platform initiatives into thin, end-to-end vertical slices using Richard Lawrence's 9 Humanizing Work splitting patterns and INVEST validation. Prevents horizontal technical-layer slicing ("API story" + "DB story" + "UI story") and exposes low-value scope that can be cut. Use when planning sprints, sizing Slice issues, or breaking down large features. Adapted from deanpeters/Product-Manager-Skills.
---

# Epic Vertical Slicing (Humanizing Work 9 Patterns)

## Purpose
Break down large initiatives or epics into small, sprint-ready **vertical slices**—increments that cut across data, business logic, API, and UI/demo layers to deliver observable, testable value in 1–5 days. Prevent horizontal slicing (technical-layer tasks that demo nothing until the end of a quarter).

---

## Step 1: Pre-Split Validation (INVEST Check except "Small")
Before splitting any epic or feature, verify it passes:
1. **Independent:** Can it be built and demonstrated without deadlocking on unrelated work? (If hard-blocked, flag the dependency or reorder.)
2. **Negotiable:** Does it focus on the outcome/behavior rather than over-prescribing internal code?
3. **Valuable:** Does this slice deliver observable value to an end user, operator, or host service developer? *If NOT, do not split it as a technical task — merge it into a vertical slice.*
4. **Estimable:** Does the team know enough to size it? (If not, break out a time-boxed Spike first.)
5. **Testable:** Does it have concrete Given/When/Then acceptance criteria (or `RLE-*`/`PRE-*`/`WAL-R-*` rule IDs)?

---

## Step 2: Apply the 9 Splitting Patterns (In Order)

Walk through these patterns sequentially until one cleanly splits the epic while preserving end-to-end value:

1. **Pattern 1 — Workflow Steps (Thin End-to-End, NOT Step-by-Step)**
   - *Wrong:* Slice 1 = Build step 1 only, Slice 2 = Build step 2 only (nothing works end-to-end until Slice 3).
   - *Right:* Slice 1 = Simplest happy-path end-to-end journey (e.g., auto-approve rule without maker-checker); Slice 2 = Add maker-checker approval step; Slice 3 = Add SLA timer & escalation.

2. **Pattern 2 — Operations (CRUD / Lifecycle Verbs)**
   - Words like *"Manage rule sets"* or *"Manage wallets"* hide 4–5 distinct operations.
   - Split into: Create/Import, View/List, Simulate/Test, Publish/Approve, Retire/Rollback.

3. **Pattern 3 — Business Rule Variations**
   - When the same flow behaves differently across rules, hit policies, tiers, or exceptions, make each rule group a slice.
   - Example: Slice 1 = `first` hit policy with basic comparisons; Slice 2 = `unique` and `collect` hit policies; Slice 3 = `Money` cross-currency error & bounded regex timeout.

4. **Pattern 4 — Data Variations**
   - Split by input/entity complexity: Slice 1 = primitive types (`string`, `integer`, `boolean`); Slice 2 = `Money` (currency + minor units) and `date`/`datetime`; Slice 3 = `list<T>` and masked fields.

5. **Pattern 5 — Data Entry / Authoring Interfaces**
   - Separate core engine capability from fancy interactive UI.
   - Example: Slice 1 = Author/import via YAML/JSON + REST API + read-only Cockpit view + Approve button; Slice 2 = Interactive browser spreadsheet Grid Editor with inline autocomplete.

6. **Pattern 6 — Major Effort (First Adapter + Remaining Adapters)**
   - When the first integration builds all the shared plumbing and subsequent ones are small:
   - Example: Slice 1 = `agentTask` SPI + In-Process/HTTP reference adapter + guardrail check; Slice 2 = MCP Tool adapter.

7. **Pattern 7 — Simple / Complex**
   - Strip optional filters, bulk modes, and advanced options to find the simplest working core.
   - Example: Slice 1 = Single decision evaluation; Slice 2 = Batch evaluation (`:evaluate-batch`) & historical replay simulation.

8. **Pattern 8 — Defer Performance / Scale Optimization**
   - "Make it work, make it right, make it fast" — *except* non-negotiable banking correctness (tenancy, idempotency, double-entry balance, no-eval security) is NEVER deferred!
   - What CAN be deferred to a follow-up slice: JMH optimization from 2 ms to <100 µs, monthly table partitioning, async outbox batching.

9. **Pattern 9 — Break Out a Spike**
   - Last resort when deep uncertainty blocks splitting: run a time-boxed 1–2 day investigation (e.g. benchmarking PostgreSQL `LISTEN/NOTIFY` vs epoch polling under multi-pod failover), then return to Pattern 1.

---

## Step 3: Evaluate Split Quality (Kill the Low-Value 20%)
After splitting, ask two mandatory PM questions:
1. **Did this split expose low-value work we can defer or cut completely?** (80% of value usually sits in 20–30% of the slices — cut or move the rest to Milestone 2/3!)
2. **Are slices roughly equal-sized (1–5 days) and independently demoable?** If any slice is still >1 sprint or cannot be demonstrated at sprint end, **re-apply Pattern 1**.

## Output Format: Vertical Slice Breakdown

```markdown
# Vertical Slice Breakdown: <Epic / Module>
**Pattern(s) Applied:** ...
**Core Outcome:** ...

| # | Slice Title (Vertical Outcome) | Pattern | Rule / Spec IDs | Demoable Artifact | Priority (Now / Later / Cut) |
|---|---|---|---|---|---|
| 1 | ... (Simplest E2E walking skeleton) | Workflow / Simple | ... | Working CLI/API/Cockpit flow | Now (Sprint 1) |
| 2 | ... | ... | ... | ... | Now |
| 3 | ... (Fancy UI / edge variant) | Data Entry / Complex | ... | ... | Later (M2) |
```

## Common Pitfalls
- **Horizontal slicing:** Never create standalone slices for *"Create PostgreSQL tables"*, *"Write DTOs"*, or *"Build React shell with mock JSON"* unless it is a genuine Sprint 1 walking-skeleton tracer bullet that runs end-to-end.
- **Step-by-step workflow slicing:** Building step 1 without a minimal end-to-end path delays integration risk until it's too late.

## HORUS Program Integration
- Used by `product-manager`, `delivery-lead`, `program-architect`, and `banking-analyst` when writing `docs/sprints/PLAN-<n>.md` and creating `type:slice` GitHub issues.
- Every slice must satisfy HORUS's rule: *each sprint (~10 features) ends demoable via Playwright or recorded terminal walkthrough in `demos/sprint-<NN>/`.*
