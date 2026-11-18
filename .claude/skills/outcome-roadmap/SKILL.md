---
name: outcome-roadmap
description: "Transform an output-focused roadmap into an outcome-focused one that communicates strategic intent. Rewrites initiatives as outcome statements reflecting user and business impacts. Use when shifting to outcome roadmaps, making a roadmap more strategic, or rewriting feature lists as outcomes. Adapted from phuryn/pm-skills."
---

# Transform Roadmap to Outcome-Focused Format

## Purpose

You are an experienced product manager helping $ARGUMENTS shift from output-focused roadmaps (which emphasize features) to outcome-focused roadmaps (which emphasize customer and business impact). This skill rewrites initiatives as outcome statements that inspire and measure what matters.

## Context

Output-focused roadmaps create false precision and misalign teams around features rather than results. Outcome-focused roadmaps clarify the customer problems being solved and the business value expected, enabling flexible execution and strategic thinking.

## Instructions

1. **Gather Information**: Read any current roadmap (`docs/roadmap.md`, `wiki/Roadmap.md`), vision docs, ADRs, and competitive research carefully before proposing changes.

2. **Think Step by Step**:
   - For each initiative, ask: "What outcome are we trying to achieve?"
   - What customer problem are we solving?
   - What business metric or engineering/adoption metric will improve?
   - How will this impact the customer experience or business?
   - Is there a simpler, faster, or differently sequenced way to achieve the same outcome?

3. **Transformation Process**: For each initiative on the roadmap:
   - **Identify the Output**: What feature or module is planned?
   - **Uncover the Outcome**: Why are we building it? What changes for customers or business?
   - **Rewrite as Outcome Statement**: Use this format:
     ```
     Enable [customer segment] to [desired customer outcome] so that [business impact]
     ```

4. **Example Transformation**:
   - **Output (Old)**: Q2: Build decision table editor, expression language, audit log, REST API
   - **Outcome (New)**:
     - Milestone 1: Enable banking product teams to deploy and change pricing/eligibility rules in minutes with dual-control approval and historical replay, eliminating code deployments for rule changes (measured by: <100 µs p99 evaluation, 100% maker-checker enforcement, <30 min newcomer onboarding).

5. **Structure Output**: Present the transformed roadmap with:
   - Strategic Positioning & Moat summary (why this sequence wins)
   - Outcome statements for each milestone/phase
   - Key measurable metrics / exit gates (not just "build X")
   - Explicit dependency & sequencing rationale (why A comes before B)
   - Key assumptions & Red-Team kill criteria

6. **Include Strategic Context**: For the overall roadmap, add:
   - How outcomes align with company strategy
   - Key assumptions about customer needs and technical bottlenecks
   - Flexible delivery horizons (Now / Next / Later or sprint-paced milestones, not rigid 12-month date promises)

7. **Save the Output**: Update the target roadmap document (`docs/roadmap.md` or `wiki/Roadmap.md`) or save as `Outcome-Roadmap-[year].md`.

## Notes

- An outcome should be testable and measurable
- Multiple outputs may achieve one outcome; focus on the outcome, not an exhaustive feature wishlist
- Outcome roadmaps are more resilient to change—embrace flexibility
- If unsure what outcome a feature drives, ask: "So what?" until you reach real customer/business value
- Always validate sequencing: never schedule a consumer product (e.g., Digital Wallet) before a dependency it relies on (e.g., Rule Engine 0.1) unless a stub/adapter contract is explicitly planned.

## HORUS Program Integration
- Governs `/Users/luongdh/Mylab/banking program/docs/roadmap.md`, `/Users/luongdh/Mylab/horus-Agentic-BPM/wiki/Roadmap.md`, and sprint goals in `docs/sprints/PLAN-<n>.md`.
- Every milestone in HORUS roadmaps must state:
  1. **Customer/Operator Outcome** (who gets what capability and why it matters),
  2. **Measurable Exit Criteria** (tied to `Technical Requirements` or `RULE` / `DEMO` gates),
  3. **What Ships (Thin Vertical Scope)** vs **Explicitly Deferred** (to avoid bloated scope),
  4. **Load-Bearing Assumptions & Kill Criteria** (via `strategy-red-team`).
