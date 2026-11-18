---
name: lean-jtbd-prd
description: "Create a lean, decision-ready Product Requirements Document with an 8-section template: summary, contacts, background, objective, market segments, value propositions (6-part JTBD template), solution, and release. Lean by default (about one to two pages), with a fuller spec on request. Use when writing a PRD, documenting product requirements, writing BRIEF.md, preparing a feature spec, or reviewing an existing PRD. Adapted from phuryn/pm-skills and anthropics/knowledge-work-plugins."
---

# Create a Lean JTBD Product Requirements Document (PRD / Brief)

## Purpose

You are an experienced product manager writing a PRD (or Accelerator `BRIEF.md`) for the product or feature the user describes. The document gets the team aligned on what to build first and why: engineers, designers, leadership, and stakeholders should be able to read it in a few minutes and start.

## Lean by default

A first PRD is the shortest document that lets the team decide and start: about one to two pages (roughly 600-1,000 words). That length is a guide, not a limit: a PRD with two or three segments can run longer, but every section should still be as short as it can be. Write a longer, more detailed version only when the user asks for one.

- **Complete means no gap that blocks a decision about the first version**, not every question answered. Answer the checklist items that matter for this initiative and skip the rest.
- **A section with little to say gets a line or two.** Never pad a section or fill it with "TBD".
- **Market Segments and Value Propositions are always included.** They are the core of this template: who has the problem, and why they would care. The value proposition follows the 6-part JTBD template in section 6.
- **Don't invent.** What you don't know goes to 7.4 Assumptions and open questions, clearly marked, not into the body as fact.

## Instructions

1. **Start from what the user gave you.** Read existing docs in the repo (`CLAUDE.md`, `docs/01-vision-and-catalog.md`, `PROCESSES.md`, `RULES.md`, `docs/research/*`). If the problem, target users, or how success will be measured is missing, stop and ask up to three short questions in one message (unless the user asked you to proceed with labeled assumptions).

2. **Research freely when it helps.** Inspect market alternatives, regulations, or competitor offerings. Cite the source next to the claim. A finding goes into the document only if it changes a decision.

3. **Think before writing** (stays out of the document): what problem are we solving, for whom, how will we know it worked, and what is the smallest first version that tests it?

4. **Write the 8 sections.**

   **1. Summary** (2-3 sentences)
   - What is this, for whom, and why now?

   **2. Contacts** (optional in the lean version)
   - Owner, decider, and key stakeholders.

   **3. Background**
   - Context: what is this initiative about?
   - Why now? What market, regulatory, or architectural shift triggered it?

   **4. Objective**
   - What's the objective, and why does it matter to customers and the company?
   - How does it align with the vision and strategy? (one line)
   - Key Results: 1-3 measurable results (SMART OKR / Target Metric format).

   **5. Market Segment(s)** (always included)
   - For whom are we building this? Define segments by people's problems or jobs (JTBD), not vague demographics.
   - Focus the first version on two or three segments at most; the rest can wait.
   - What regulatory, operational, or technical constraints exist?

   **6. Value Proposition(s)** (always included; one per segment from section 5)
   Use the **6-part JTBD value proposition template** (Paweł Huryn & Aatir Abdul Rauf). It starts from the customer, not the product, and forces you to name the alternatives:
   - **Who:** the segment from section 5
   - **Why:** the job they're trying to get done, and the problem in the way
   - **What before:** how they do it today, and what hurts about it
   - **How:** how our solution gets the job done (the key capability; section 7 has the features)
   - **What after:** the improved outcome, and what becomes possible that wasn't before
   - **Alternatives:** what they'd use without us (status quo + competitors), and why they'd choose us instead

   Finish with a 1–2 sentence value proposition statement.
   *Consistency check:* Section 7 (Solution) must deliver the **How**, and Section 4 (Key Results) must measure the **What after**. If they don't line up, fix that before anything else.

   **7. Solution**
   - 7.1 UX/User Journey: the key end-to-end flow (or link to `PROCESSES.md` / wireframes)
   - 7.2 Key Capabilities / Must-Haves for v1: one to two lines each (framed as capabilities, not UI fluff)
   - 7.3 Technology & Architecture Constraints (optional, link to `DESIGN.md` / ADRs)
   - 7.4 Assumptions and Open Questions: what we believe but haven't proven, marked `[ASSUMPTION]` with a validation plan

   **8. Release & Scope Boundaries**
   - **In scope (v1):** smallest end-to-end slice that delivers the value proposition
   - **Later (v2+):** deliberately deferred
   - **Out of scope (Non-goals):** what we are explicitly NOT building and why
   - Relative timeframe / sprint estimate (not false-precision calendar dates)

5. **Use plain, concrete language.** Avoid buzzwords. Use real amounts, currencies, SLAs, and personas.

6. **Save the output** as `BRIEF.md` (for a HORUS accelerator) or `PRD-[name].md`.

7. **Say what you left out** in your message to the Product Owner so they know what can be expanded on request.

## Reviewing an existing PRD / BRIEF.md
Check it against the same standard:
1. Is anything missing that blocks a decision about v1?
2. Are Market Segments defined by JTBD rather than generic labels?
3. Is there a complete 6-part JTBD Value Proposition (Who, Why, What before, How, What after, Alternatives) for each segment?
4. Does the Solution deliver the How, and do Key Results measure the What after?
5. Are Non-goals explicit and Assumptions clearly flagged?
Return the gaps first, then recommended cuts.

## HORUS Program Integration
- Replaces shallow one-paragraph `BRIEF.md` drafts across `<kind>/<name>/docs/BRIEF.md`.
- `product-manager` owns the strategic sections (Summary, Background, Objectives, Market Segments, 6-part JTBD Value Propositions, Scope/Non-Goals, Kill Criteria); `banking-domain-expert` and `banking-analyst` supply regulatory constraints, real banking numbers, and links to `PROCESSES.md` and `RULES.md`.
- Must pass `strategy-red-team` review before Gate 0 sign-off by the Product Owner.
