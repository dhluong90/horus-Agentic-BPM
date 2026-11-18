# Roadmap

Design preview: nothing is released. Dates are targets, not commitments; [[Release Notes]] state what shipped.

This page is the short public view. The full plan, with the features in each milestone, the release gates and the assumptions that could change it, is the [product roadmap](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/roadmap.md).

![Milestones](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/roadmap-timeline.svg)

## Milestones

| Milestone | Target date | What you can do when it ships |
|---|---|---|
| M0 Architecture | 4 Dec 2026 | Read the decided storage schema, transaction rules and expression grammar, and boot an example host that creates the engine tables in PostgreSQL |
| M1 Rule Engine 0.1, Cockpit 0.1 | 15 Jan 2027 | Evaluate versioned decision tables inside your service's transaction; import rule changes as YAML or JSON, simulate them, and publish them only after a second person approves in the cockpit; look up and explain any past decision; reach several of your services from one standalone cockpit with one sign-in |
| M2 Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 26 Mar 2027 | Run approvals with task queues and SLAs, compensating service steps, and AI agent steps whose output must pass a JSON Schema and your guardrail rules; see every process definition and instance as a diagram with its current step; work one task inbox and one incident list across your services; complete tasks through declared forms; process definitions take effect only after a second person approves them; edit tables in a grid |
| M3 Agentic depth, compatibility, 1.0 | 21 May 2027 | Plug in MCP and model adapters from the examples, test a changed prompt or guardrail against recorded traces before it goes live, approve process changes as a graph diff in the cockpit, see per-step durations on the diagram, import and export a BPMN subset, upgrade without downtime, and build on a frozen API. A browser modeller limited to the steps your service registers is planned for M3 and may move to 1.1 |

## What may move

Each milestone has a checkpoint and a list of features that move out, in order, if the checkpoint fails. Never moved: tenant isolation, idempotency, maker-checker, append-only audit, masking, the async wait-state protocol and the `agentTask` output gate.

| Milestone | Checkpoint | Moves out first |
|---|---|---|
| M1 | 8 Jan 2027 | Async decision log mode, batch evaluate, extra decision log filters (moved to M2) |
| M2 | 5 Mar 2027 | Batched migration execution, live counts on the diagram, `callProcess` (moved to M3) |
| M3 | 23 Apr 2027 | Calendar editor screen, the modeller canvas (to 1.1), the historical heatmap, BPMN export, DMN import, what-if comparison, the documentation site |

## Why this order

| Question | Answer |
|---|---|
| Why rules first | The Rule Engine is useful alone, and `agentTask` guardrails and `decision` steps are rule set evaluations |
| Why `agentTask` in M2 | It is a service step plus schema validation plus a guardrail decision, all available once the Process Engine core and the Rule Engine exist |
| Why the grid editor waits for Cockpit 0.2 | YAML authoring with import and export is enough for a GitOps flow and lets Rule Engine 0.1 ship six weeks earlier. A spreadsheet path for business users in M1 is under review |
| Why diagrams before the modeller | Operators need "where is this instance now" every day; the modeller's engine APIs land before the 1.0 freeze so the canvas can follow in 1.1 without breaking the API |

## What we are testing

| Assumption | Test | If it fails |
|---|---|---|
| A read-only diagram matters more to evaluators and operators than a modeller | Show spike screenshots to architects and operations leads during M1 | Bring the modeller forward in M3 and protect it |
| Process owners, not only developers, change process definitions in banks | Ask process owners who changes an approval flow today | The modeller moves to after 1.0; diagrams and governed change stay |
| Pilot banks run at most about 15 services and one identity provider whose tokens every service accepts | Ask pilot candidates for their setup | That pilot uses one cockpit per service |
| The diagram work fits the M2 estimate | Two-day diagram spike in M0 | Live counts on the diagram leave M2 |

## Not before 1.0

Message correlation between processes; multi-agent planning; databases other than PostgreSQL; Jakarta EE integration; a hosted cockpit; process analytics beyond per-step counts and durations; a shared server that aggregates several services. [[Principles]] states the limits the engines keep at every version.

## Proposing a feature

Open an issue labelled `type:proposal`; see [[Contributing]]. Accepted proposals are recorded as decisions and then move into a milestone.
