---
name: b2b-battlecard-and-pricing
description: "Create sales-ready B2B competitive battlecards (comparison, win/loss patterns, objection handling, landmines to plant) and design commercial pricing / licensing strategies (value metrics, tiered packaging, dual-licensing, willingness-to-pay tests). Use when evaluating competitors, preparing presales materials, responding to 'why not Camunda/Mambu?', or designing commercial licensing and packaging. Adapted from phuryn/pm-skills."
---

# B2B Competitive Battlecards & Pricing Strategy

Equip presales, leadership, and engineering with sharp competitive battlecards and value-grounded commercial packaging for enterprise banking buyers.

---

## Part 1: Competitive Battlecard

Create a scannable, honest, field-ready battlecard comparing HORUS against a specific competitor or status quo.

### Instructions
1. **Gather Evidence First:** Check existing research in `docs/research/*`, competitor docs, pricing/licensing terms, deployment topology, and customer pain points. Never fabricate competitor capabilities.
2. **Build the Battlecard:**

```markdown
# Battlecard: HORUS vs. [Competitor]

## 1. Competitor Snapshot
- **Category & Model:** [e.g. Distributed BPM cluster / SaaS Core Banking / OSS Fork]
- **Licensing & Cost Structure:** [e.g. Non-prod source-available; Enterprise metered per instance]
- **Best-Fit Customer (Where they truly shine):** ...

## 2. Quick Comparison
| Dimension | HORUS | [Competitor] | Advantage |
|---|---|---|---|
| Deployment Footprint | ... | ... | ... |
| Database & Transaction Model | ... | ... | ... |
| Business-User Governance (Maker-Checker / Replay) | ... | ... | ... |
| AI Agent Guardrails | ... | ... | ... |
| Licensing & TCO Predictability | ... | ... | ... |

## 3. Where We Win (with Architectural/Business Proof)
- **[Advantage 1]:** ...
- **[Advantage 2]:** ...

## 4. Where They Win (Honest Assessment + Counter-Positioning)
- **[Their Strength 1]:** [Acknowledge honestly; show why it is overkill or wrong trade-off for our ICP, or how we mitigate]
- **[Their Strength 2]:** ...

## 5. Common Buyer Objections & Responses
| Buyer / Architect Says | Respond With |
|---|---|
| "They have full BPMN 2.0 / more features" | ... |
| "We can use the free open-source version / fork" | ... |
| "Why build/adopt a newer engine instead of market leader?" | ... |

## 6. Landmines to Plant in Discovery / RFP
Questions to ask the prospect's architect, security, or procurement team that expose competitor hidden costs:
- "..."
- "..."

## 7. Win / Loss / Walk-Away Rules
- **We win when:** ...
- **We lose or should walk away when:** ... (never waste presales cycles on mismatches)
```

---

## Part 2: Pricing & Commercial Packaging Strategy

Design pricing and licensing models grounded in **value delivered**, **alternative cost (TCO)**, and **enterprise procurement reality**.

### Instructions
1. **Quantify Value Delivered vs Alternative Cost:**
   - What is the customer's next-best alternative and its true 3-year TCO (licensing + cluster ops + Elasticsearch/Infra + SI engineering months saved)?
   - Quantify concrete value: months saved on delivery, infrastructure eliminated, predictable cost without per-transaction tax, compliance audit readiness.
2. **Evaluate Commercial & Licensing Models:**

| Model | Best For | Pros / Cons for Banking & SI |
|---|---|---|
| **Source-Available + Commercial Dual License** | `horus-Agentic-BPM` (PolyForm Noncommercial + HORUS Commercial) | Builds public trust & developer adoption; protects commercial moat; eliminates per-instance metering friction |
| **Per-Deployment / Per-Tenant Enterprise Subscription** | HORUS Accelerators & BPM Commercial License | Predictable budget for mid-size banks/fintechs; aligns with multi-tenant expansion |
| **Platform Implementation + Annual Support & Upgrade License** | System Integrator engagements | Combines SI services margin with recurring IP license & SLA support |
| **Usage / Per-Instance Metered** | High-volume SaaS (Camunda 8 model) | **Warning:** Banks hate unpredictable success taxes on transaction volume |

3. **Design Packaging & Value Metrics:**
   - **Tiers (2–4 clear tiers):** e.g., *Community / Noncommercial* (evaluation & OSS) → *Standard Commercial* (1 production service/tenant, SLA) → *Enterprise / SI Bundle* (multi-tenant, commercial redistribution rights across accelerators, priority patches, custom compliance review).
   - **Feature / Value Gating:** Gate on **enterprise governance, scale, commercial rights, and support SLAs** (e.g., commercial production rights, partitioning, SSO/enterprise audit exporters, migration support) — NEVER cripple core correctness or security in lower tiers.
4. **Test Assumptions Before Locking Price:**
   - Validate willingness-to-pay in 3–5 discovery calls with target bank CIOs / fintech CTOs / SI partners.
   - State explicit assumptions and risks (e.g., legal review friction with PolyForm vs Apache 2.0, procurement preference for perpetual vs annual subscription).

## HORUS Program Integration
- Use to turn technical competitor research (`docs/research/camunda-assessment.md`, future Flowable / CIB seven / Operaton / Mambu assessments) into actionable **Presales Battlecards**.
- Use to define the commercial licensing & packaging structure between `horus-Agentic-BPM` (`COMMERCIAL-LICENSE.md`) and `Banking-Accelerator-Program` customer engagements.
