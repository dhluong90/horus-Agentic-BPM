---
name: clear-technical-prose-and-runbooks
description: "Write crisp, human-grade technical prose, READMEs, 15-minute DEMO scripts, and operations RUNBOOKs using Strunk & White rules and strict Anti-AI-Slop filtering. Eliminates puffy LLM clichés, enforces concrete commands with expected output, and guarantees every guide is runnable from a fresh clone. Use when writing or editing any doc, wiki page, ADR, error message, DEMO.md, or RUNBOOK.md. Adapted from softaworks/agent-toolkit (writing-clearly-and-concisely)."
---

# Clear Technical Prose, Demos & Operations Runbooks

Write technical documentation the way senior principal engineers and Stripe/PostgreSQL documentation authors write: short active sentences, concrete numbers, zero AI fluff, and every command verified against a clean environment.

## When to Use
- Writing or editing `README.md`, `DEMO.md`, `RUNBOOK.md`, `wiki/*`, `docs/adr/*`, `demos/sprint-*/SCRIPT.md`, or API error messages.
- Copyediting verbose or generic AI-generated documentation before review.

---

## 1. Strunk & White Technical Writing Rules
1. **Use active voice:** *"The engine evaluates the table in 80 µs"* instead of *"The table is evaluated by the engine."*
2. **Omit needless words:** Cut filler openings (*"In order to achieve this, it is important to note that..."* → *"To do this,"* or delete altogether).
3. **Put statements in positive form:** *"Reject mixed-currency comparisons"* instead of *"Do not allow mixed-currency comparisons to proceed without failing."*
4. **Be concrete:** Always supply real values, ISO currency codes, HTTP codes, and limits (`5000000 VND`, `PT5M`, `409 UNIQUE_HIT_VIOLATION`, `p99 < 100 µs`).
5. **Prefer tables & lists over walls of prose:** If comparing 3+ options, parameters, headers, or failure modes, use a Markdown table.

---

## 2. Anti-AI-Slop Blocklist (Instant Rewrite Triggers)
Never let these LLM clichés survive into HORUS documentation:
- **Promotional adjectives:** *seamless, seamlessly, robust, cutting-edge, state-of-the-art, next-generation, world-class, revolutionary, holistic, comprehensive, game-changing*.
- **Empty business verbs:** *leverage, empower, unlock, harness, foster, streamline, supercharge, delve into*.
- **Puffy "-ing" tails:** *"...ensuring reliability and scalability"*, *"...highlighting key capabilities"*, *"...fostering seamless collaboration"*.
- **Vague claims without proof:** Replace *"highly performant and secure"* with *"evaluates 200 rules in < 100 µs p99; runs zero reflection or scripting engines (`TR-SEC-01`)"*.
- **Formatting noise:** Do not bold every third word or decorate technical specs with emojis.

---

## 3. Standard for `DEMO.md` (15-Minute Presales / PO Acceptance Script)
Every HORUS Accelerator `DEMO.md` and `demos/sprint-<NN>/SCRIPT.md` must be playable solo by someone who never met the authors:
1. **Preconditions:** Exact commit/tag, required tools (`docker`, `curl`, `jq`), 1 command to start stack + load seed data, and expected ready check (`curl -sf localhost:8080/actuator/health`).
2. **Named Test Users / Tokens:** Exact credentials or dev-token command per role (e.g. `maker-alice`, `checker-bob`, `ops-charlie`, `tenant-vn`).
3. **Numbered Journey Steps:** Table or numbered blocks with:
   - Exact command (`curl` or exact UI click path)
   - Exact expected output / HTTP status / Cockpit screen state (showing real numbers & rule IDs proven)
4. **Failure / Control Proof Step:** Demonstrate at least one bank-grade guardrail live (e.g. `maker-alice` attempts self-approval → `403/409` rejected; duplicate `Idempotency-Key` → returns same `201` without double-posting).
5. **Cleanup / Reset Command:** Single command to wipe and re-seed before the next demo.

---

## 4. Standard for `RUNBOOK.md` (3 AM Production Incident Guide)
Operations engineers read runbooks under stress. Never bury recovery steps in theory:
1. **Service Overview & Health Endpoints:** Ports, `/actuator/health`, key Micrometer metrics & alert thresholds (Green / Amber / Red).
2. **Environment Variables & Secrets:** Table of every required env var, format, safe default (if any), and impact if missing.
3. **Common Failure Scenarios (Symptom → Diagnosis → Safe Remediation → Verify):**
   - *Stuck `agentTask` / expired timer leases:* Exact read-only SQL query to inspect `pre_timer` / `pre_incident`, how to inspect root cause, and API/Cockpit `:retry` / `:resolve` command.
   - *Stale rule cache across replicas:* How to check `cache_epoch` vs active published version and trigger reload.
   - *Flyway migration lock / rollback:* Exact steps (`TR-OPS-01`) without corrupting history.
   - *Database connection pool saturation:* What metrics to inspect (`hikaricp.connections.active`) and immediate mitigation.

## HORUS Program Integration
- Default prose and runbook standard for `tech-writer`, `devops-engineer`, and `program-architect`.
- Pairs with `diataxis-living-documentation` (structure & SSOT) and `sprint-demo` (video recording & sprint report).
