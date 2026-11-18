---
name: diataxis-living-documentation
description: "Structure documentation suites using the Diataxis 4-quadrant framework (Tutorials, How-To Guides, Reference, Explanation) and Living Docs Governance (assigning Constitution, Map, Status, and History roles with one canonical owner per fact so wiki, READMEs, specs, and code never drift). Use when organizing wikis, docs/, or READMEs, or auditing documentation consistency across repositories. Adapted from affaan-m/ECC (living-docs-governance), softaworks/agent-toolkit (crafting-effective-readmes), and Diataxis."
---

# Diátaxis & Living Documentation Governance

Keep long-lived engineering documentation accurate, navigable, and synchronized with code — without duplicating facts across five Markdown files.

## When to Use
- Organizing or auditing `horus-Agentic-BPM/wiki/`, `Banking-Accelerator-Program/docs/`, or product `README.md` suites.
- When documentation drifts from code (e.g., `Getting-Started.md` using `/api/...` while `Reference` says `/api/.../v1`).
- Before adding a new top-level doc or updating public APIs (`TR-QA-02`).

---

## 1. The Diátaxis 4-Quadrant Structure
Never mix all four modes in one messy page. Separate documentation cleanly by the reader's need:

| Quadrant | Reader State | Purpose | HORUS Artifacts | Rules |
|---|---|---|---|---|
| **1. Tutorials** *(Learning-oriented)* | Newcomer starting cold | Safe, guided first win in `< 30 min` | `Getting-Started.md`, `examples/*/README.md` | Every command & payload MUST work verbatim against the real release; zero architectural lectures |
| **2. How-To Guides** *(Task-oriented)* | Engineer/Ops solving a task | Step-by-step recipe to achieve a specific goal | `DEMO.md`, `RUNBOOK.md`, `Rule-Engine-Authoring-Decision-Tables.md`, `Rule-Engine-Simulation-and-Testing.md` | Assumes basic knowledge; concrete inputs, expected outputs, troubleshooting steps |
| **3. Reference** *(Information-oriented)* | Developer coding or integrating | Accurate, dry, complete ground truth | `SPEC.md`, `openapi.yaml`, `Rule-Engine-Decision-API.md`, `Rule-Engine-Configuration.md`, `Rule-Engine-Expression-Language.md`, `Technical-Requirements.md` | Must match code & tests 100%; no opinions or marketing prose |
| **4. Explanation** *(Understanding-oriented)* | Architect or evaluator | *Why* choices and trade-offs were made | `Architecture.md`, `Principles.md`, `docs/adr/*`, `docs/01-vision-and-catalog.md`, `docs/research/*` | Explains alternatives rejected, invariants, and context |

---

## 2. Living Docs Governance: Four Non-Overlapping Roles & SSOT

Assign **one canonical owner per fact**. Every other page links to that owner instead of copy-pasting numbers, endpoints, or dependency versions:

| Role | Purpose | Canonical Files in HORUS | Must NOT Become |
|---|---|---|---|
| **Constitution** | Rules agents & devs must obey | `CLAUDE.md`, `.claude/rules/*.md`, `docs/03-working-process.md` | A product spec or live status log |
| **Map** | What exists, where it lives, how to navigate | `wiki/Home.md`, `wiki/_Sidebar.md`, `docs/05-repository-structure.md`, `README.md` | A copy of architectural essays |
| **Status** | Current milestones, blockers, delete-zone | `wiki/Roadmap.md`, `docs/roadmap.md`, `demos/sprint-*/REPORT.md` | Structural API reference |
| **History** | Durable decisions, removals, releases | `docs/adr/*`, `CHANGELOG.md`, `wiki/Release-Notes.md` | Raw git commit dump |

### Anti-Drift Maintenance Rules
1. **Same-PR Sync (`TR-QA-02`):** Any PR that changes a REST path, config key (`horus.bpm.*`), error code, metric name, or architectural invariant MUST update the canonical Reference doc and Tutorial example in the **same PR**.
2. **Evidence Over Claims:** Verify commands, endpoints, Spring Boot / Java versions, and schema names against actual `pom.xml`, Flyway SQL, and `openapi.yaml` before declaring docs up to date.
3. **Delete-Zone Tracking:** When removing or deferring a capability (e.g., deferring interactive Grid Editor from M1 to M2), record the decision explicitly in `Roadmap.md` / `ADR` so future sessions do not accidentally re-introduce it into the wrong milestone.
4. **Tailor READMEs to Audience:**
   - *Public OSS / Library README (`horus-Agentic-BPM`):* 1-sentence value prop, quick code/YAML snippet, module table, link to wiki, licence & commercial notice.
   - *Accelerator README (`Banking-Accelerator-Program`):* Product kind, prerequisites, 1-command local start, env vars table (no hardcoded secrets), link to `DEMO.md` & `RUNBOOK.md`.

## HORUS Program Integration
- Core organizing principle for `tech-writer` and `program-architect` across `horus-Agentic-BPM/wiki/` and `Banking-Accelerator-Program/docs/`.
- Check cross-file consistency across `Getting-Started.md` ↔ `Rule-Engine-Decision-API.md` ↔ `Technical-Requirements.md` ↔ `openapi.yaml` on every release.
