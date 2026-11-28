# Design documents

This folder holds the product and technical design of HORUS Agentic BPM: what the engines must do, how they are built, and why. It is a map. Each fact below has one owning document; every other document links to the owner instead of repeating it.

## docs/ or the wiki

| You want to | Read |
|---|---|
| Use the engines: install, author rules, call the API, run the cockpit | The [wiki](https://github.com/dhluong90/horus-Agentic-BPM/wiki) (source in [`wiki/`](../wiki/)) |
| Change the engines, review a design, or evaluate how they are built | This folder and the module docs |

Users never need this folder. Contributors and evaluators start here.

## The tree

| Path | Contents |
|---|---|
| [`product/vision.md`](product/vision.md) | Who the engines serve, the problem, what is in and out of scope |
| [`product/prd-rule-engine.md`](product/prd-rule-engine.md) | What to build in the Rule Engine |
| [`product/prd-process-engine.md`](product/prd-process-engine.md) | What to build in the Process Engine |
| [`product/prd-cockpit.md`](product/prd-cockpit.md) | What to build in the Cockpit |
| [`product/requirements.md`](product/requirements.md) | The numbered `TR-*` requirements that gate a release, with their verification |
| [`architecture/overview.md`](architecture/overview.md) | System design across modules: boundaries, state, dependencies, deployment; its diagrams live under `architecture/` |
| [`adr/`](adr/) | Project-wide architecture decision records `ADR-nnnn`, from [`0000-template.md`](adr/0000-template.md) |
| [`proposals/`](proposals/README.md) | Design proposals for new features, before they reach a PRD and a SPEC |
| [`DEPLOYING.md`](DEPLOYING.md) | Run an example host with PostgreSQL in docker compose, from a fresh clone, with the image gates |
| [`OBSERVABILITY.md`](OBSERVABILITY.md), [`DISASTER-RECOVERY.md`](DISASTER-RECOVERY.md) | Meters, alerts and dashboards; backup, restore and the recovery drill. Both feed the wiki [Operations runbook](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Operations-Runbook) |
| [`roadmap.md`](roadmap.md) | Pointer to the wiki Roadmap |

Each module keeps its own design beside its code:

| Path | Contents |
|---|---|
| `<module>/docs/DESIGN.md` | Module internals: domain model, storage, runtime mechanics, open questions |
| `<module>/docs/SPEC.md` | Testable behaviour as numbered statements (`RLE-*`, `PRE-*`, `CKP-*`), each tracing to `TR-*` ids |
| `<module>/docs/TEST-PLAN.md` | Test strategy, coverage of each SPEC statement, known gaps |
| `<module>/docs/adr/` | Decisions that concern only that module |
| `<module>/api/schema/` | JSON Schemas of the definition formats |

The modules are [`rule-engine/docs/`](../rule-engine/docs/), [`process-engine/docs/`](../process-engine/docs/) and [`cockpit/docs/`](../cockpit/docs/). The schemas are [`rule-engine/api/schema/rule-set.v1.schema.json`](../rule-engine/api/schema/rule-set.v1.schema.json) and [`process-engine/api/schema/process-definition.v1.schema.json`](../process-engine/api/schema/process-definition.v1.schema.json).

## Reading order for a new contributor

1. [`../README.md`](../README.md) for what the project is, then the wiki [Getting Started](https://github.com/dhluong90/horus-Agentic-BPM/wiki/Getting-Started) to run it once.
2. [`product/vision.md`](product/vision.md) for why the engines exist.
3. The PRD of the module you will work on.
4. [`architecture/overview.md`](architecture/overview.md) for how the modules fit together.
5. The ADRs in [`adr/`](adr/), in number order.
6. The module `DESIGN.md`, then its `SPEC.md` and `TEST-PLAN.md`.
7. [`product/requirements.md`](product/requirements.md) for the release gates your change must keep green.
8. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) before you open a pull request.

## Who owns which fact

| Fact | Owning document | Others |
|---|---|---|
| Why the engines exist, scope and non-goals | `product/vision.md` | Link to it |
| What to build in a module | The module PRD in `product/` | SPEC and DESIGN refine it, never widen it |
| Release requirements (`TR-*`) | `product/requirements.md` | Quote the id and link |
| Testable behaviour (`RLE-*`, `PRE-*`, `CKP-*`) | The module `SPEC.md` | Tests are named by these ids |
| How behaviour is tested | The module `TEST-PLAN.md` | |
| System design across modules | `architecture/overview.md` | |
| Module internals: model, storage, runtime | The module `DESIGN.md` | |
| Definition document format | The JSON Schemas under `<module>/api/schema/` | `tools/validate-examples.sh` checks wiki examples against them |
| Architecture decisions and their reasons | `adr/` and `<module>/docs/adr/` | A decision changes only by a new ADR that supersedes the old one |
| Features under discussion | `proposals/` | |
| How to use the API, configuration and expression language | The wiki reference pages | Must match the SPEC and the schemas |
| Milestones and dates | The wiki Roadmap page | `roadmap.md` points to it |
| What shipped in each version | The wiki Release Notes page | |
| Feature status per module | The wiki Feature Status page, once created | |

When two documents disagree, the owner in this table is right and the other one is a defect to fix.

## How documents change

- A change to code updates the documents it affects in the same pull request: the SPEC statement and its test, the DESIGN section, the schema, and the wiki page that owns any REST path, configuration key, error code or metric name it touches (TR-QA-02).
- A new feature starts as a proposal in `proposals/`. Once accepted, it enters the module PRD, then SPEC statements with tests, then DESIGN.
- A decision that changes the architecture gets a new ADR. An accepted ADR changes only by a later ADR that supersedes it.
- A new requirement gets a new `TR-*` id in `product/requirements.md`. Existing ids keep their meaning.
