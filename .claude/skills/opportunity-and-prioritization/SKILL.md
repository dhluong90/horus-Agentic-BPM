---
name: opportunity-and-prioritization
description: "Combine Opportunity Solution Trees (Teresa Torres) and 9 Prioritization Frameworks (Opportunity Score, RICE, ICE, Cost of Delay / CD3, Kano, MoSCoW, Weighted Decision Matrix) to map outcomes to customer problems, evaluate solutions, and sequence backlogs cleanly. Use when structuring discovery, deciding which accelerator or feature to build first, or sequencing roadmap dependencies. Adapted from phuryn/pm-skills."
---

# Opportunity Mapping & Prioritization

Connect measurable **Outcomes** to validated **Opportunities**, **Solutions**, and **Fast Experiments**, then sequence initiatives using rigorous prioritization and dependency analysis rather than opinions or loudness.

---

## Part 1: Opportunity Solution Tree (Teresa Torres)

Prevent teams from jumping straight to solutions ("let's build a fancy UI editor") by mapping the opportunity space first:

1. **Desired Outcome (Top):** One single, measurable product or business metric (e.g., *"Reduce time for a banking service to adopt governed rules from 3 weeks to < 1 day"*).
2. **Opportunities (Level 2):** Unmet customer/developer/operator needs or pain points — NOT features. Frame from the user's perspective (*"I struggle to test a rule change against last month's production traffic before approving it"*).
   - Score opportunities using **Opportunity Score** (Dan Olsen): `Importance × (1 − Satisfaction)` (normalized 0–1). High importance + low satisfaction = top priority.
3. **Solutions (Level 3):** Always generate **at least 3 candidate solutions** per top opportunity (e.g., YAML + Simulation API vs Full Drag-and-Drop Grid UI vs Excel Import) to avoid the "first idea" trap.
4. **Experiments (Level 4):** Fast, cheap assumption tests (Value, Usability, Viability, Feasibility) with real evidence or skin-in-the-game before committing multi-sprint engineering capacity.

---

## Part 2: Selecting the Right Prioritization Framework

**Core Principle:** Never allow stakeholders or customers to dictate solutions directly. Prioritize **opportunities and outcomes**, then pick the highest-leverage solution. Collect votes/scores independently before discussion to avoid seniority anchoring.

| Framework | Best For | Formula / Mechanism | Caution |
|---|---|---|---|
| **Opportunity Score** | Customer problems & gaps | `Importance × (1 − Satisfaction)` | Great for deciding *which problem* to solve, not effort |
| **Cost of Delay (CD3)** | Platform dependencies & sequencing | `(Value per week of delay) / Duration` | **Best for B2B platforms & shared foundations** (e.g. Rule Engine blocking Wallet) |
| **RICE** | Initiatives at scale | `(Reach × Impact × Confidence%) / Effort (person-months)` | Reach must reflect real target tenants/services, not vanity guesses |
| **ICE** | Fast triage of ideas/experiments | `Impact (1-10) × Confidence (1-10) × Ease (1-10)` | Quick and lightweight; calibrate scales across the team |
| **Kano Model** | Understanding expectations | Must-be (table stakes) vs Performance vs Delighters vs Indifferent | Use to separate non-negotiables (tenancy, idempotency) from differentiators (`agentTask` guardrails) |
| **Weighted Decision Matrix** | Architecture & build-vs-buy choices | Weighted sum of scored criteria | Make weights explicit before scoring options |
| **Impact vs Effort** | Quick 2×2 sprint triage | High/Low Value vs High/Low Effort | Too coarse for strategic roadmap bets |
| **MoSCoW** | Scope locking within a fixed release | Must / Should / Could / Won't | Beware everything drifting into "Must" |

---

## Part 3: Platform & Dependency Sequencing Rules

In multi-product programs (like a Banking Accelerator catalogue + embedded BPM/Rule engines), naive RICE scores often miss **dependency blocking**:
1. **Unblock the Critical Path First:** If Accelerator B (`Digital Wallet`) depends on Platform Capability A (`Rule Engine 0.1` + `Ledger`), calculate A's **Cost of Delay** across *all blocked consumers*, not just A in isolation.
2. **Minimum Viable Foundation, Not Gold-Plated Foundation:** Ship the **thinnest usable version** of a dependency (e.g., Rule Engine 0.1 Core + Postgres + YAML + Simulation + Approve Cockpit) to unblock consumers early, rather than holding all consumers hostage while building nice-to-have editors.
3. **Verify Every "Must" Against Kano Must-Bes:** Security, multi-tenancy, idempotency, and audit logging are Kano *Must-Bes* in banking — they cannot be traded away for speed, though scope breadth (number of payment rails, number of BPMN symbols) CAN be sliced cleanly.

## Process When Prioritizing a Backlog or Roadmap

1. **Confirm the Desired Outcome** and the strategic horizon (e.g., first 5-sprint block vs 12-month platform vision).
2. **List Candidate Opportunities / Initiatives** and separate **Foundational Blockers** from **Customer-Facing Accelerators** and **Delighters**.
3. **Check Dependencies & Cost of Delay:** Flag any sequence paradox (e.g., scheduling a consumer before its core dependency, or delaying the flagship differentiator to the end).
4. **Score Using the Best-Fit Method** (CD3 / RICE / Opportunity Score + Confidence) and state explicit **Explicit Non-Focus / Cut Items** (what drops below the line and why).
5. **Output:** A ranked priority table with scores, dependency notes, and recommended sprint/milestone order.

## HORUS Program Integration
- Used by `product-manager` and `delivery-lead` when updating `docs/roadmap.md`, `horus-Agentic-BPM/wiki/Roadmap.md`, or sequencing a 5-sprint `PLAN-<n>.md`.
- Always check cross-repo dependency alignment between `horus-Agentic-BPM` (`Rule Engine`, `Process Engine`) and `Banking-Accelerator-Program` (`Platform Core` -> `Digital Wallet` -> `Onboarding & KYC` -> `Deposits & Lending`).
