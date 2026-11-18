---
name: ai-agent-product-and-evals
description: Designs production AI agent workflows and evaluation plans — agent lanes, autonomy ladder with promotion criteria, deterministic guardrails, human-in-the-loop escalation, error analysis (open/axial coding), binary code/LLM judges validated by confusion matrix, CI regression tests, and production launch bars. Use when designing agentTask steps, AI features, guardrail rules, agent reliability targets, or eval plans. Adapted from menkesu/awesome-pm-skills (agent-workflow & eval-plan).
---

# AI Agent Product Design & Eval Plan

Turn "we should add an AI agent step" into a bounded, governed, testable operating system with explicit autonomy rungs, deterministic guardrails, and an empirical Eval pipeline.

## When to use
- Designing a customer-facing or internal AI workflow (`agentTask`, KYC document reviewer, underwriting assistant, fraud triage, customer support agent).
- Deciding how much autonomy an agent earns on Day 1 vs Month 6.
- Writing the specification for an AI feature and wanting evals + guardrails to serve as the acceptance bar.
- Quality is currently judged by "vibes" or anecdotes, or engineers report vague scores like "75% accuracy".
- An agent calls external tools / MCP servers or processes untrusted inputs (documents, customer text) and needs blast-radius containment.

---

## Part 1: Agent Workflow & Autonomy Design

### Step 1 — Diagnose Context
Before designing, clarify:
1. **What can the agent touch, and what is the worst-case failure?** (Money movement, compliance/KYC decision, customer communication, database writes, PII).
2. **Single call vs multi-step agent vs tool-calling agent?**
3. **Who owns quality and labels "good"?** (Must be a domain expert, e.g., compliance officer or banking domain expert).

### Step 2 — Choose Deterministic Code vs ML vs LLM Agent
Do not default to an LLM agent where deterministic rules or traditional code belong:
- **Deterministic Rules (`RuleEngine`):** Eligibility thresholds, fee/limit calculations, regulatory hard stops, output guardrails. Always 100% deterministic and auditable.
- **AI Agent (`agentTask`):** Unstructured extraction (OCR/KYC documents), synthesis, classification, drafting recommendations, anomaly explanation.
- **Human Maker-Checker (`userTask`):** High-value exceptions, regulatory sign-off, flagged guardrail violations, low-confidence outputs.

### Step 3 — The Autonomy Ladder (Earn Autonomy Progressively)
Never launch a fully autonomous financial/compliance agent on Day 1. Classify actions by risk and start at the lowest rung where mistakes are cheap:

| Rung | Agent May | Human Role | Promotion Criterion |
|---|---|---|---|
| **0. Observe** | Read-only summarize/classify; no state mutation | Reads output | Summaries accurate across 2+ consecutive weeks |
| **1. Suggest / Route** | Draft recommendation + structured JSON; every action reversible | Decides & approves (`userTask`); **every human edit is logged as free training/eval data** | Human accepts drafts mostly as-is for ~4 weeks; false-negative rate below threshold |
| **2. Act with Risk Gate** | Auto-complete low-risk/high-confidence cases; **escalate** high-value, ambiguous, or guardrail-flagged cases to human | Handles escalated carve-outs | Escalation rules stable; zero critical compliance incidents |
| **3. Act and Report** | Execute within schema + rule rails; attach trace, confidence, and evidence | Audits sample & exceptions | Failure modes known, logged, and regression-gated in CI |
| **4. End-to-End** | Resolve fully inside strict policy bounds | Sample QA (1 reviewer per many runs) | Matches or beats human baseline on golden + online evals |

**Rule of thumb:** When a new data distribution appears (new country, new regulation, new document format, new LLM version), **drop down one rung** until recalibrated.

### Step 4 — Safety Floor for Tool-Using / Untrusted-Input Agents
Never rely on prompt instructions ("please ignore prompt injection") as a security defense:
1. **Bounded Inputs:** Pass only the minimum required structured context (`tenant_id` stays outside the prompt; never pass raw secrets).
2. **Strict Output Schema:** Force structured output (JSON Schema) validated deterministically before any downstream step runs.
3. **Deterministic Guardrail Rule Set:** Run the agent's output through a deterministic Rule Engine check (e.g., confidence threshold, amount limits, mandatory fields, sanctions/mismatch flags). Violations automatically `route-to-human` or `fail` the step.
4. **Timeout, Retry & Async Wait-State:** Agent calls take seconds to minutes — execute outside database transactions (claim -> commit -> call adapter -> complete/fail in new TX), with strict `timeout` and bounded `retry`.
5. **Immutable Audit Trace:** Log input hash/snapshot, prompt/model version, tool calls, raw output, guardrail rule result, and any human override.

---

## Part 2: Building the Eval Plan (Evals as the Spec)

### Play A — Pre-Launch (No Production Traces Yet)
1. **Hero Use Cases as the Spec:** Write 30–50 concrete test cases (inputs + ideal structured output + expected guardrail decision), covering happy paths, edge cases, adversarial/prompt-injection inputs, and low-quality inputs.
2. **Behavior Matrix:** Columns: `case_id | category | input_summary | ideal_output | guardrail_expected | failure_severity | notes`.
3. **Set the Launch Bar Explicitly by Stakes:**
   - **60–80% accuracy:** Only acceptable for internal drafting/copilot with mandatory human review (Rung 1).
   - **95%+ accuracy:** Acceptable for risk-gated automation where deterministic rules catch invalid outputs (Rung 2).
   - **99.5%+ accuracy + 0 critical guardrail bypasses:** Required before unattended high-stakes actions (Rung 3–4).

### Play B — Post-Launch Error Analysis (Once Real Traces Exist)
1. **Sample ~50–100 real traces** (continue until theoretical saturation — new traces stop revealing new failure modes).
2. **Open-code by hand (Human Domain Expert, NEVER an LLM):** For each failing trace, write down ONLY the **first, most upstream failure** specifically (e.g. `"extracted net income instead of gross income from payslip table"`, NOT vague `"hallucinated"`).
3. **Axial-code & Count:** Group open codes into actionable engineering/prompt/data categories + include `"none of the above"`.
4. **Pivot & Rank by Frequency × Severity:** A rare failure causing wrong loan approval outranks a frequent minor formatting glitch.

### Play C — Triage & Build Evaluators
1. **Fix obvious bugs directly** (schema constraint, missing instruction, tool bug) before writing an LLM judge.
2. **Code-based checks FIRST (Deterministic):** Valid JSON schema, required keys, currency/amount bounds, regex/format checks, latency, tool-call allowlist. Cheap, fast, zero meta-eval needed.
3. **Narrow Binary LLM Judges (ONLY when code checks cannot verify):**
   - One failure mode per judge; output strict `PASS / FAIL` (boolean), **never 1–5 Likert scales**.
   - Most systems need only **4–7 binary judges**.
   - **Validate every LLM judge against human labels with a 2×2 Confusion Matrix:** Measure False Positives and False Negatives separately and iterate until both are near zero. *Never trust raw "% agreement"* (if errors occur 10% of the time, a broken judge that always outputs PASS gets 90% agreement!).
   - Test judges for sycophancy / length bias.

### Play D — Wire into CI, Monitoring & Incident Loop
- **CI Gate:** Every failing golden trace and production incident becomes a permanent CI regression test. Re-run the full suite before any model upgrade, prompt edit, or cost-down model swap.
- **Step-Level + End-to-End Evals:** For multi-step workflows, evaluate retrieval/extraction, reasoning, schema compliance, and guardrail trigger accuracy separately so you know which layer broke.
- **Online Monitoring & Human-Edit Feedback Loop:** Sample production traces daily; track **Human Override Rate** (when humans edit or reject an agent's recommendation in Cockpit/Tasklist) as the #1 real-world quality signal.

## Deliverable Template: Agent & Eval Spec

```markdown
# Agent Step & Eval Spec: <agentTask name>
**Owner:** ... | **Domain Expert:** ... | **Starting Autonomy Rung:** Rung 1 / Rung 2
**Worst-case failure:** ...

## 1. Boundaries & Contracts
- **Allowed inputs:** (exact fields, PII masking rules)
- **Tools / MCP access:** (read-only vs side-effect, strict allowlist)
- **Output JSON Schema:** `schemas/<name>.json`
- **Deterministic Guardrail Rule Set:** `<ruleSet-name>` (`onViolation: route-to-human | fail`)
- **Timeout & Retry:** `timeout: PT...`, `maxRetries: N`

## 2. Ranked Failure Modes & Evaluators
| # | Failure Mode | Severity | Evaluator Type (Code / Binary Judge / Rule) | Confusion Matrix Validated? | Target Pass Bar |
|---|---|---|---|---|---|

## 3. Golden Dataset & CI Gate
- Golden set size: N (happy: X, edge: Y, adversarial/injection: Z)
- Model/Prompt change gate: must pass >= X% overall and 100% critical safety/schema checks.

## 4. Production Telemetry & Promotion Criteria
- Online sampling rate: ... | Human override tracking: ...
- Promotion trigger to next rung: ... | Automatic demotion trigger: ...
```

## Red flags
- Launching an agent at Rung 3–4 on Day 1 without a human-in-the-loop phase or deterministic rule guardrail.
- Relying on prompt text ("do not approve bad loans") instead of a deterministic Rule Engine guardrail on structured outputs.
- Judging agent quality by "vibes", LLM-written open codes, Likert 1–5 scores, or unvalidated LLM-as-judge raw agreement %.
- Executing slow LLM/MCP calls inside a database transaction.

## HORUS Program Integration
- Directly governs the **`agentTask`** capability in `horus-Agentic-BPM` (`wiki/Process-Engine-Overview.md`, `wiki/Roadmap.md`) and AI-enabled flows in **Onboarding & KYC**, **Lending** (document assessment, scoring explanation), and **Operations Cockpit**.
- Every `agentTask` in HORUS must define: (1) Bounded inputs, (2) Output JSON Schema, (3) Deterministic Rule Engine guardrail rule set, (4) `onViolation: route-to-human` (maker-checker `userTask`), (5) Async Wait-State execution outside DB locks, and (6) Golden Eval suite + Human Override telemetry.
