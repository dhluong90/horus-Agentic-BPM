---
name: product-strategy-and-moat
description: Builds a product strategy and stress-tests your moat, then writes a one-page strategy doc (diagnosis, where to play, how to win, guiding policy, coherent actions, moat verdicts). Use for 'we need a product strategy', 'is our moat real', 'do we have a competitive advantage', 'where should we play', 'defensibility', 'should we expand into X', 'our roadmap has no strategy', or 'write a product vision'. Adapted from menkesu/awesome-pm-skills (Rumelt, Roger Martin, Hamilton Helmer).
---

# Product Strategy + Moat Stress Test

Turn a vague challenge, a goal list or a gut feeling into a written strategy and a verdict on whether your advantage is real. **Core frameworks:** Richard Rumelt (Kernel of Strategy), Roger Martin (Choice Cascade), Hamilton Helmer (7 Powers & AI Moat Stress Test).

## When to use
- You were handed a revenue target, an OKR list or 'priorities' and told to produce a strategy.
- Leadership asks 'what is our moat?' and the honest answer is features, speed or the team.
- You must choose where to play: new segment, upmarket, new product, adjacent category, new market.
- A competitor, a bundler, a foundation-model lab or a platform shift just changed your odds.
- Roadmap debates keep turning into arguments about values, which means the strategy is missing or unwritten.
- A team needs a vision (6 months to 5 years) that engineers and designers can act on.
- You are about to pitch or defend a strategy to leadership, a customer executive, or an investor.

## Step 1 — Diagnose (ask before answering)
First read anything already in the repo (`CLAUDE.md`, `docs/01-vision-and-catalog.md`, `docs/roadmap.md`, `docs/research/*`, `BRIEF.md`, `wiki/Roadmap.md`). Then ask only what you cannot infer, max 4:

1. **Stage?** Pre-PMF, post-PMF growth, or scaled/incumbent. *Why:* Think about power from day one only as tilted probabilities; pre-PMF the strategy is one priority. It decides plan depth.
2. **What triggered this?** (a) a target or list with no logic, (b) choose where to play or expand, (c) a competitive or AI threat, (d) a moat claim to defend, (e) a vision gap. *Why:* it picks the play in Step 2.
3. **Is AI your product, your threat, or both?** *Why:* switches on the AI moat tests (Step 3, Play 3).
4. **What time and authority do you have?** An afternoon, a week, or 8-12 weeks; and who is the one accountable decider.

## Step 2 — Pick the play
| If… (situation) | Use | From | Why |
|---|---|---|---|
| Handed goals, targets or 17 priorities | **Play 1:** Diagnosis, guiding policy, coherent actions (action agenda) | Richard Rumelt | Goals are ambitions, not strategy; the missing parts are diagnosis and what you will not do |
| Need to choose segment, positioning, how to win vs rivals | **Play 2:** Choice cascade + can't-or-won't test | Roger Martin | Forces linked choices on what you control that compel customer action |
| A deck claims a moat, or an AI feature 'can't be copied' | **Play 3:** Benefit + barrier + materiality test, then AI add-ons | Hamilton Helmer, Anish Acharya, Scott Wu | Most claimed advantages fail one of the three tests |
| Need a real strategy for a team/area within weeks | **Play 4:** Small-S sprint or foundry | Chandra Janakiraman, Rumelt | Procedural; ends in 3 pillars and explicit non-focus |
| Tempted by a new product, market, upmarket move or acquisition | **Play 5:** Right-to-win gates | Jeetu Patel, Dharmesh Shah, Vijay Iyengar | Expansion kills cores; gates catch it early |
| Team needs a picture of the future, not a plan | **Play 6:** Vision in one page | Ebi Atawodi, Melissa Perri, Jenny Wen | Vision must be concrete, lofty and free of today's limits |
| Pre-PMF, tiny team | Founding hypothesis sentence only, then execute | Jake Knapp + John Zeratsky | A full strategy assumes you can do many things at once |
| Under attack from a bundler or platform | Play 3 (barrier + business-model attack check), then Play 1 | Drew Houston, Michael Truell | UI features get copied and bundled free; the economics are the target |

Most requests need Play 1 or 2 plus Play 3. Do not run every play.

## Step 3 — Run it

### Play 1 — Action agenda (Rumelt)
1. **State the problem, not the goal.** Ask 'what makes it hard?' until the real obstacle is named; the first answer is usually a symptom. Rewrite 'grow 50%' as the obstacle to growing.
2. **Pressure-test the diagnosis** against real constraints (headcount, runway, platform dependency). Most bad strategies come from willful disbelief, e.g. assuming you can do every project at once.
3. **Find the crux:** the hardest part you could actually get around. Look for the insight that goes around it; do not pick a strategy from a list of common strategies.
4. **Write the guiding policy** in one paragraph, including what you will NOT do. Test: could a reasonable person disagree with it? 'Be the best' fails. Principles need a trade-off: *we will sacrifice X for Y*.
5. **List 3-5 coherent actions** for 6 months to 2-3 years, phrased as things we will do, not goals. Conflict check: does any action fight another?
6. **Connect the dots:** business target → ~20 things you could do → which fit and why → fill the missing links. Cut to **3 pillars max**.
7. **Done when** three people from different functions can say what they will stop doing on Monday and you can state what changes for customers.

### Play 2 — Choice cascade (Roger Martin)
Work the five questions iteratively, toggling until they reinforce each other; do not spend long on aspiration first.
1. **Winning aspiration:** anchored in what customers lack that you can provide. 'The market is big and we want some' is rejected.
2. **Where to play:** which customers (and which you will not serve), finished product or component, channel, value-chain stage. Segment on needs and willingness to pay, not persona; product, sales, marketing and finance must act differently per segment.
3. **How to win:** better or cheaper, with a written theory of why customers will perceive it. Coin-flip test: would customers flip a coin between you and the nearest rival? If yes, you have no strategy.
4. **Capabilities:** what must you do that rivals **can't or won't** (won't = it cannibalizes them; Martin's fault lines). Interlock capabilities so copying means wholesale change.
5. **Management systems** that build and maintain them (the moat needs upkeep).
6. **Compress** to the founding hypothesis: *If we solve [problem] for [customer] with [approach], they will choose it over [competitors] because of [differentiator 1] and [differentiator 2].* Add a backup approach.

### Play 3 — Moat stress test (Helmer, with Martin and AI voices)
1. **List 3-5 claimed advantages** from the deck, board memo or README, plus the things the team spends most time on. Ask of each: castle or shack?
2. **Benefit:** does it let you charge more or cost less than the best alternative? Quantify.
3. **Barrier:** why can't a funded rival copy it? Use can't-or-won't. Does 'they can't' still hold in two years? If not, plan for margin compression. Incumbents typically take 12-24 months to respond, so a barrier shorter than that is a head start, not a moat.
4. **Materiality:** does the effect move margins or returns materially into the future? Flywheels exist but rarely matter; network *effects* are not network *economies*.
5. **Classify** into: counter-positioning, scale economies, switching costs, network economies, process power, branding, cornered resource. Startups and challengers almost always begin with **counter-positioning** (the incumbent cannot copy without hurting its own business model).
6. **Disqualify:** operational excellence and speed (necessary, copyable), bought brand recognition, copyable UI, team quality, a prototype that took three months and now takes a weekend.
7. **Verdict per claim:** Castle (benefit + barrier + material) / Head start (barrier under 24 months) / Shack / Unproven. For each non-castle write the one thing that would upgrade it.

**AI add-ons (run all five):**
- *Replicability:* none of the classic moats were based on how hard the software is to build. Could a rival rebuild it in a week?
- *Proprietary signal:* do usage, traces or feedback give you data others lack? Do not train a model for ego where frontier models win.
- *Stickiness:* does the product accumulate customer-specific knowledge and get used by teams? Enterprise lock-in lives in configured business rules and governance workflows.
- *Layer risk:* is the model the whole product or an input? Commodity layers lack pricing power at steady state; vertical, data-gated, workflow-deep use cases are where foundation labs will not go.
- *Scaffolding:* list what exists only because models are weak; delete it as models improve and revisit failed ideas after 1-2 model generations. **Re-run this test at every major model release.**

### Play 4 — Team process (pick by time)
- **One afternoon:** Play 1 solo, then pressure-test with engineering and design. Repeated feature fights are a strategy gap; disagreements are different assumptions, so debate assumptions.
- **2-4 days (Rumelt's foundry):** leader plus 7-8 people put 25-50 problems on a wall, pick the 1-2 that are important *and* addressable, leave with an action agenda.
- **8-12 weeks, small-S:** prep ~4 weeks, 3-5 day strategy sprint (top 3 pillars, explicit non-focus), 1-2 weeks writing, 2-3 weeks rollout.
- **Always:** one accountable decider; write it down so it can be debugged; test coherence by asking every team what it is doing and why.

### Play 5 — Right-to-win gates for expansion
Pass all or kill: (1) customer lack, not TAM; (2) permission to play and a route to distribution, plus reusable components; (3) company-product fit and sponsorship; (4) suites: value must come from real integration, not bundling sixth-best tools; (5) third-order cost of slicing reports/ops across new products; (6) core health: never starve a churning core; (7) unit economics modeled now; (8) staged funding inside a 70/20/10 portfolio.

### Play 6 — Vision in one page
Four tests: lofty, realistic, free of today's technical limits, grounded in a clear user problem. Write *once upon a time / one day / because of that / finally*, or the headline of the article announcing it shipped. A vision must be concrete, different from today, and say what you will not do.

## Deliverable
Produce this and offer to save it as `strategy.md` (or update the relevant `BRIEF.md` / `01-vision-and-catalog.md`):

```markdown
# Strategy one-pager: <product/area>   Date / Owner / Decider

## 1. Context
Stage, horizon, the decision this serves.

## 2. Diagnosis (the crux)
The challenge in one paragraph. What makes it hard. Constraints we are not pretending away.

## 3. Winning aspiration
What customers lack that we can provide. Success looks like (headline, 18-24 months).

## 4. Where to play / where NOT to play
Customers, product, channel, value-chain stage. Explicit non-focus list with the reason.

## 5. How to win
Better or cheaper, and the theory. Founding hypothesis sentence. Capabilities rivals can't or won't build.

## 6. Moat stress test
| Claim | Benefit | Barrier (months) | Material? | Power type | Verdict | Upgrade move |
|---|---|---|---|---|---|---|

## 7. Guiding policy
The rule that constrains future decisions, including what we will not do.

## 8. Coherent actions (max 3 pillars)
| Pillar | Actions (6 mo / 1-2 yr) | Owner | What we stop doing | Leading indicator |
|---|---|---|---|---|

## 9. Assumptions and kill criteria
What must be true. Cheapest test for each. The result that makes us change course.

## 10. Re-test triggers
Competitor bundles us, model release, churn in core, quarterly review date.

## Next 3 actions
1. ... (owner, date)
2. ...
3. ...
```

## Grade existing work
Score each 1-5; total /40. Return per-criterion score, total, and the top 3 fixes.

| Criterion | 1 | 3 | 5 |
|---|---|---|---|
| Diagnosis | Goal or slogan, no obstacle | Problem named, obstacle vague | Crux named, constraints honest |
| Choices / trade-offs | Everything is a priority | Some cuts, no stated not-doing | Explicit non-focus with reasons; it hurts |
| Customer anchor | Market size is the rationale | Segment named by persona | Customer lack and need-based segment |
| Defensibility | 'Great team, fast execution' | Benefit named, barrier asserted | Benefit + barrier + materiality tested |
| Coherence | Actions conflict or are goals | Mostly consistent | Pairwise conflict check passed; actions reinforce |
| Focus | 5+ pillars | 4 pillars | Max 3 pillars, each with owner and stop-doing |
| Testability | No assumptions listed | Assumptions, no kill criteria | Hypothesis, cheapest test, kill criteria, re-test triggers |
| Behavior change | Cannot say what changes | Changes for the team only | Teams can say what they stop; customers see a change |

## Red flags
- Abstract goals, profit targets or ambitions labeled strategy; a list of 17 priorities means nothing was chosen.
- 'Better' as the strategy: a good version of what exists needs a reason rivals can't copy it quickly.
- Market size as the reason to enter.
- Counting operational excellence, a bought brand, copyable UI or flywheel talk as power.
- Bolt-on products ending up the sixth-best tool in their category.
- A strategy with no change in what gets built, staffed or funded.

## HORUS Program Integration
- Apply this skill before creating or updating `docs/01-vision-and-catalog.md`, any `<kind>/<name>/docs/BRIEF.md`, or `horus-Agentic-BPM/wiki/Roadmap.md`.
- Always run **Play 3 (Moat stress test + AI add-ons)** against Camunda 8, Camunda 7 forks (CIB seven, Operaton), Flowable, and core banking incumbents (Finacle, Mambu): test whether *embeddability + host PostgreSQL transactions + deterministic rule guardrails for AI agents + predictable commercial licensing* truly pass the **Can't or Won't** test.
- Pair with `strategy-red-team` before presenting strategic documents to the Product Owner (Luong) for approval.
