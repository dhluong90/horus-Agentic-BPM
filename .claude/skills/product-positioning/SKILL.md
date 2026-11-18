---
name: product-positioning
description: Builds a positioning canvas (competitive alternatives, differentiated value, best-fit customer, category) plus a strategic narrative and sales-pitch storyboard, and grades existing positioning or messaging. Use for 'positioning', 'how do we describe our product', 'messaging', 'our pitch isn't landing', 'category', 'differentiation', 'one-liner', 'value prop', 'old game new game'. Adapted from menkesu/awesome-pm-skills (April Dunford, Andy Raskin, Arielle Jackson).
---

# Product Positioning

Turn a fuzzy product description into a defensible positioning canvas and a pitch narrative your whole team can tell the same way. **Core frameworks:** April Dunford (Obviously Awesome), Andy Raskin (Strategic Narrative), Arielle Jackson (Copy & Bar Test).

## When to use
- Prospects say 'say that again', mislabel you ('you're just like X'), or say 'I can build that in house'
- Founders/PMs pitch brilliantly but engineers, docs, or presales tell a different story
- The market shifted and you are positioned for what it used to be
- You are about to write README/landing copy, brief presales, or launch (positioning comes first)
- You are debating whether to create a new category or join an existing one
- You have a deck, wiki Home, `BRIEF.md`, or one-liner and want it graded

## Step 1 — Diagnose (ask before answering)
Read existing docs (`README.md`, `wiki/Home.md`, `docs/research/*`, `BRIEF.md`) first and infer answers. Ask at most 4 questions you cannot infer:
1. **B2B or consumer? Who buys (one person or a buying committee)?** Enterprise B2B/committee buyers route to Dunford + Raskin.
2. **Stage: pre-launch / few customers / growth-stage with a sales team?** Pre-launch gets a positioning thesis and loose net; growth-stage gets tight best-fit and alignment work.
3. **What do prospects do today instead of buying you, and who is on the shortlist?** This is the input to everything; include both status quo and named vendors.
4. **What is wrong right now?** (confusing pitch / lost to no decision / team tells different stories / crowded lookalike category / need a category call).

## Step 2 — Pick the play
| If… (situation) | Use | From | Why |
|---|---|---|---|
| Any B2B product, no clear positioning | Five-step canvas: alternatives, capabilities, value themes, best-fit customer, category | April Dunford | Category last; the order is the method |
| Team/customers describe you in different ways | Ten-person test, then word-for-word alignment | Arielle Jackson, Claire Butler | Most weak positioning is misalignment |
| Complex B2B, group buyers, buyers inert ('no budget this quarter') | Strategic narrative: old game to new game | Andy Raskin | Sells a movement, not a feature comparison |
| Lookalike competitors keep getting compared to you | Narrative shift or force-a-choice differentiator | Raskin, Mike Maples Jr. | Stop arguing you are better; be different |
| Pre-launch or first handful of customers | Positioning thesis + loose fishing net | April Dunford | Thesis is partially wrong initially; refine with buyers |
| Technical/product copy sounds like jargon | Bar Test + benefit-as-H1 + swappable one-liner test | Arielle Jackson | Copy must be language people actually say |
| Considering a new category | Category readiness test (budget, words, competition, scope) | Barbra Gago | Most B2B teams should join an existing category |
| Efficiency/cost-saving product, weak pull | Reframe savings as growth or faster time-to-market | Jason Cohen | Buyers pay a fraction of savings, full value of growth/risk reduction |
| Enterprise buyer, premium product, no reason to pay more | Challenger insight (hidden business problem first) | Matt Dixon, Jeanne Grosser | ~80% of B2B deals buy to avoid pain or reduce risk |

## Step 3 — Run it
### Play A: The five-step canvas (April Dunford)
1. **Competitive alternatives.** Ask: *if we did not exist, what would the customer do?* Write two lists: the **status quo** (hardcoded if/else, internal scripts, spreadsheets, doing nothing) and the **shortlist** (named vendors/open-source projects). B2B loses ~40% of deals to no decision, so you must beat both plus indecision. Do not start from 'why does everybody love us'; that yields untestable opinions.
2. **Differentiated capabilities.** List everything you have that the alternatives do not (architecture, pricing/licensing model, governance, deployment footprint, integrations). If a capability is shared, it is not differentiation.
3. **Value themes via so-what ladder.** For each capability ask *so what, why does anyone care?* until you reach value others cannot deliver. Cluster into 2-3 themes.
4. **Best-fit customer.** Ask what characteristics make a target account care a lot about that value. Express them as queryable company attributes (tech stack, team size, regulatory pressure, architecture constraints), not demographic personas.
5. **Market category, last.** Pick the context that makes the value obvious to those buyers. Test 2-3 candidate categories against the value themes. Default: a niche play inside an existing category buyers already understand, then expand later.
6. **Positioning statement.** Write: *We are the best in the world at [value] for [best-fit customer], unlike [alternatives], because [capabilities], in the [category] context.*

### Play B: Strategic narrative (Andy Raskin) — B2B only
1. **Name the shift** in two or three words (e.g. *monolith clusters to embedded libraries*, *unconstrained AI scripts to governed agentic workflows*).
2. **Name the stakes.** Show winners already playing the new game and losers struggling. The buyer must see the future as a fork between very bad and very good, not sort of okay.
3. **Name the object** of the new game as a rallying-cry question.
4. **Name the obstacles** that make winning hard. Your problems-you-solve become obstacles to that goal.
5. **Show how you help** overcome each obstacle with product proof and customer/demo scenarios.

### Play C: From positioning to pitch (Dunford)
1. Write the **insight**: your point of view on the market, the problem inside the problem, phrased so the target customer nods.
2. Storyboard: insight with evidence, how alternatives handle it (worse for this customer), your differentiated value in that context.
3. Turn it into deck/README/demo script; test with qualified buyers. If presales cannot tell the story they will make things up.
4. Arm the champion with talking points for IT/Security, Procurement/Legal, Architects, and the Economic Buyer.

### Play D: Copy tests (Arielle Jackson)
1. Define the target. Role-play two of them talking: *I just started using [product], it's a [category] that [benefit].*
2. Anything that sounds unnatural (*leverages*, *empowers*, *next-gen synergy*) gets rewritten.
3. **Swappable test:** paste your one-liner onto a competitor site; if it still works, rewrite it.

### Play E: Category call
Check four factors: do buyers have a budget line, what words do they use for the pain, who competes, how big is the scope you want. If budget and vocabulary already map to an existing category, join it and position as the clear best fit for a specific buyer segment. If spanning multiple labels, run **dual track**: sell in the existing budget category, educate on the umbrella narrative.

## Deliverable
```markdown
# Positioning Canvas: [Product]  (date, owner, participants)
## 1. Diagnosis
Symptoms observed: [confusion signals / no-decision rate / inconsistent stories]
## 2. Competitive alternatives
Status quo: [..]   Shortlist: [..]
## 3. Differentiated capabilities -> value themes (so-what ladder)
| Capability | So what | Value theme |
|---|---|---|
## 4. Best-fit customer (queryable attributes)
[institution size, stack, regulatory triggers, budget; who loves us and why]
## 5. Market category
Chosen: [..]  Rejected: [..] and why  Dual track? [y/n]
## 6. Positioning statement + one-liner (passes Bar Test and swappable test: y/n)
## 7. Strategic narrative
Old game -> new game: [..]  Stakes: [..]  Rallying question: [..]
Obstacles: [..]  How we help: [..]  Proof: [..]
## 8. Pitch storyboard (insight / alternatives / differentiated value / proof / champion kit)
## 9. Thesis status (pre-PMF only)
Assumptions to validate: [..]  Tighten trigger: [shared attribute among early adopters]
## Next 3 actions
1. [Test the narrative with 5 qualified prospects; log lean-in vs confusion]
2. [Align README, wiki Home, and DEMO.md wording word-for-word]
3. [Schedule a positioning review in 6 months, or on major competitor shift]
```

## Grade existing work
Score each 1-5 (1 / 3 / 5), total /40.
| Criterion | 1 | 3 | 5 |
|---|---|---|---|
| Alternatives | Only lookalike vendors | Shortlist but no status quo | Status quo plus shortlist, grounded in reality |
| Differentiation | Generic benefits any rival could claim | Features differ, value unclear | So-what ladder to value only you deliver |
| Value themes | Long feature list | 5+ themes | 2-3 customer-worded themes |
| Best-fit customer | Demographic persona | Segment with some attributes | Queryable company attributes, evidence |
| Category | Chosen first or invented without budget evidence | Reasonable but untested | Chosen last, makes value obvious |
| Language | Jargon, swappable one-liner | Clear but flat | Passes Bar Test and Swappable Test |
| Narrative | Problem, solution, better than rivals | Insight present, no stakes | New game, stakes, obstacles, proof |
| Alignment and testing | One person's doc | Team saw it | Team agreed; presales can tell it; buyers lean in |

## Red flags
- Starting from market category or brand values instead of competitive alternatives.
- Pitching 'we're better' without saying better at what, for whom.
- Cool technology with no "so-what"; refusing to position at a sharp micro-level.
- Stopping at a positioning doc while `README.md`, `wiki/Home.md`, and `DEMO.md` tell conflicting stories.

## HORUS Program Integration
- Every accelerator `BRIEF.md` and `horus-Agentic-BPM/wiki/Home.md` must pass the **Swappable Test** and April Dunford's **5-step canvas**.
- For `horus-Agentic-BPM`: alternatives are *Camunda 8 (Zeebe cluster + ES + Enterprise license), Camunda 7 forks (CIB seven / Operaton), Flowable, and custom Spring/DB code*. Do NOT compete on BPMN symbol count; anchor positioning on *zero-cluster embeddable library in host PostgreSQL + business-user rule governance + deterministic rule guardrails for AI agents*.
- For `Banking-Accelerator-Program`: alternatives are *custom SI rebuilds from scratch, full core banking replacement (Temenos/Finacle), or SaaS cores (Mambu)*. Position on *composable, independently deployable accelerators that sit beside an existing core or compose a digital bank without a core rewrite*.
