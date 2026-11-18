# HORUS Agentic BPM

Lightweight, embeddable **Process Engine** and **Rule Engine** for Java and Spring Boot, with an operations cockpit, where **humans, services and AI agents are first-class process participants**. Source-available and free for noncommercial use. Built by HORUS for banking and financial-services systems, usable anywhere a service needs approvals, journeys, decisions or limits without a heavyweight BPM suite.

**Documentation: see the [project wiki](../../wiki)** (source in [`wiki/`](wiki/)).

> Status: design preview. The public API is being reviewed in the wiki before the code is written. Nothing here is production-ready yet.

## Why

Most teams need a fraction of what a full BPMN or DMN platform offers, but they need it inside their own service, under their own licence, with their own database. HORUS Engines are libraries, not servers:

- **Embeddable**: add a Spring Boot starter, run a migration, done. State lives in your PostgreSQL under a prefixed schema (`pre_`, `rle_`).
- **Small and readable**: process and rule definitions in JSON/YAML you can diff and review.
- **Operable**: a cockpit to see instances, tasks, rule versions and decision logs, with maker-checker on publishing.
- **Independent**: Rule Engine never depends on Process Engine. Use one or both.
- **Agentic**: an `agentTask` lets an AI agent perform a step with bounded inputs, a structured output schema, rule-based guardrails and maker-checker approval where the rules require it. The engine never embeds a model; adapters live in your service.

## Modules

| Module | What it does | Artifact |
|---|---|---|
| [`process-engine/`](process-engine/) | Process definitions (start, end, user task, service task, agent task, decision, exclusive and parallel gateways, timers, sub-process), human tasks with claim/complete/delegate/reassign, SLA timers and escalation, maker-checker, history and retry | `com.horus.bpm:process-engine-spring-boot-starter` |
| [`rule-engine/`](rule-engine/) | Decision tables (first, unique, collect), versioned rule sets with draft/published/retired and effective dates, a safe expression language, decision API, decision log, simulation | `com.horus.bpm:rule-engine-spring-boot-starter` |
| [`cockpit/`](cockpit/) | React dashboard for both engines, embeddable in a host admin UI or served standalone for the examples | `com.horus.bpm:cockpit` |
| [`examples/`](examples/) | Minimal host services showing each engine alone and both together | |

## Out of scope (for now)
Full BPMN 2.0 or DMN FEEL compliance, CMMN, a distributed job executor, a standalone server mode.

## Roadmap
See [`docs/roadmap.md`](docs/roadmap.md). Decisions are recorded in [`docs/adr/`](docs/adr/).

## Contributing
See [`CONTRIBUTING.md`](CONTRIBUTING.md). Security issues: [`SECURITY.md`](SECURITY.md).

## Licence
**PolyForm Noncommercial License 1.0.0.** Free to use, modify and share for noncommercial purposes. Commercial use of any kind requires a commercial licence from HORUS: see [`COMMERCIAL-LICENSE.md`](COMMERCIAL-LICENSE.md). Contributions are accepted under the [`CLA.md`](CLA.md). Full text in [`LICENSE`](LICENSE).
