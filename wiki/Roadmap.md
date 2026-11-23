# Roadmap

Design preview: nothing is released. Dates are targets, not commitments; [[Release Notes]] state what shipped.

This page is the short public view. The full plan, with the features in each milestone, the release gates and the assumptions that could change it, is the [product roadmap](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/roadmap.md).

![Milestones](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/roadmap-timeline.svg)

## Milestones

| Milestone | Target date | What you can do when it ships |
|---|---|---|
| M0 Architecture | 4 Dec 2026 | Read the decided storage schema, transaction rules and expression grammar, and boot an example host that creates the engine tables in PostgreSQL |
| M1 Rule Engine 0.1, Cockpit 0.1 | 15 Jan 2027 | Evaluate versioned decision tables inside your service's transaction; change rules by editing the cells and rows of a draft in the cockpit, by a spreadsheet round trip (CSV), or by importing YAML or JSON; simulate them, read the change in plain language, and publish them only after a second person approves in the cockpit; look up and explain any past decision; reach several of your services from one standalone cockpit with one sign-in |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 26 Mar 2027 | Run approvals with task queues and SLAs, compensating service steps, and AI agent steps whose output must pass a JSON Schema and your guardrail rules; see every process definition and instance as a diagram with its current step; work one task inbox and one incident list across your services; complete tasks through declared forms; process definitions take effect only after a second person approves them; edit tables in a grid with templates; write the decision log asynchronously on high-volume paths, by setting |
| M3 Agentic depth, compatibility, 1.0 | 21 May 2027 | Plug in MCP and model adapters from the examples, test a changed prompt or guardrail against recorded traces before it goes live, approve process changes as a graph diff in the cockpit, see per-step durations on the diagram, 1.0 executes every executable BPMN 2.0 element, import BPMN and export it, upgrade without downtime, and build on a frozen API; optionally require a second approver to retire a live rule version. A browser modeller limited to the steps your service registers is planned for M3 and may move to 1.1 |

## What may move

Each milestone has a checkpoint and a list of features that move out, in order, if the checkpoint fails. Never moved: tenant isolation, idempotency, maker-checker, append-only audit, masking, the async wait-state protocol and the `agentTask` output gate.

| Milestone | Checkpoint | Moves out first |
|---|---|---|
| M1 | 8 Jan 2027 | Decision log filters beyond rule set and period; then plain-language readings show the condition shorthand instead of sentences (both moved to M2) |
| M2 | 5 Mar 2027 | Async decision log mode, batched migration execution, live counts on the diagram, `callProcess`, batch evaluate, decision search by correlation id or input value (moved to M3) |
| M3 | 23 Apr 2027 | Condition builder lens, calendar editor screen, the modeller canvas (built-in modeller after 1.0; models are imported from Camunda Modeler or bpmn.io), the historical heatmap, BPMN export, DMN import, what-if comparison, the documentation site, version history and evidence export, the second-approver mode for retirement (to 1.1) |

## Configured, not built in

Decisions on scope follow principle 13, generic by design: where business needs differ, the roadmap ships a setting with a safe default or an extension point, not fixed behaviour. Examples on this roadmap: the decision log write mode (M2), the cockpit's opening screen per role, the reading style and message catalogues, the tenant resolver, an optional condition builder lens (off by default), a required simulation report per rule set (M3, off by default) and the approval mode for retiring a rule version (M3, one approver by default).

## Why this order

| Question | Answer |
|---|---|
| Why rules first | The Rule Engine is useful alone, and `agentTask` guardrails and `decision` steps are rule set evaluations |
| Why `agentTask` in M2 | It is a service step plus schema validation plus a guardrail decision, all available once the Process Engine core and the Rule Engine exist |
| Why the grid editor waits for Cockpit 0.2 | Cell and row editing of a draft and a spreadsheet round trip are enough for business users in M1, and YAML import serves a GitOps flow. The full grid with templates needs the 0.2 cockpit and would push Rule Engine 0.1 past January |
| Why diagrams before the modeller | Operators need "where is this instance now" every day; the modeller's engine APIs land before the 1.0 freeze so a built-in modeller can follow after 1.0 without breaking the API; until then models are imported from Camunda Modeler or bpmn.io |

## What we are testing

| Assumption | Test | If it fails |
|---|---|---|
| A read-only diagram matters more to evaluators and operators than a modeller | Show spike screenshots to architects and operations leads during M1 | Bring the modeller forward in M3 and protect it |
| Process owners, not only developers, change process definitions in banks | Ask process owners who changes an approval flow today | The modeller moves to after 1.0; diagrams and governed change stay |
| Pilot banks run at most about 15 services and one identity provider whose tokens every service accepts | Ask pilot candidates for their setup | That pilot uses one cockpit per service |
| The diagram work fits the M2 estimate | Two-day diagram spike in M0 | Live counts on the diagram leave M2 |

## 1.0 baseline: BPMN 2.0 and the Rule Engine

1.0 is one baseline release. You bring BPMN 2.0 models from a standard modeller such as Camunda Modeler or bpmn.io and run every executable element, including pools, lanes and message flows, next to a complete Rule Engine with simple UI rule editing. A built-in modeller comes after 1.0. Only the product owner decides when it ships.

Full DMN levels and conformance evidence come after 1.0, with no dates yet. CMMN is never supported. Choreography and conversation diagrams are kept untouched with a warning, not executed.

## Not before 1.0

Multi-agent planning; agent-chosen steps; databases other than PostgreSQL; Jakarta EE integration; a hosted cockpit; process analytics beyond per-step counts and durations; a shared server that aggregates several services. [[Principles]] states the limits the engines keep at every version.

## Proposing a feature

Open an issue labelled `type:proposal`; see [[Contributing]]. Accepted proposals are recorded as decisions and then move into a milestone.
