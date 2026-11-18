# Roadmap

| Phase | Outcome | Status |
|---|---|---|
| Architect | Module decomposition, public APIs of both engines, storage model, expression language grammar, cockpit architecture; ADRs | Next |
| Milestone 1 | Rule Engine core: decision tables, versions, expression language, decision API and log; cockpit for rules | Not started |
| Milestone 2 | Process Engine core: definitions, instances, user and service tasks, gateways, timers, maker-checker; cockpit for processes | Not started |
| Milestone 3 | Simulation, SLA and escalation, examples, documentation site, first public release | Not started |

## Decided
- Two engines, two artifacts, one repository. Rule Engine never depends on Process Engine.
- Libraries embedded in the host service; state in the host PostgreSQL under prefixed schemas. No standalone server.
- Licence: PolyForm Noncommercial 1.0.0 for the public; commercial use only under a HORUS commercial licence (dual licensing). Contributions under CLA.

## Open decisions
- Maven coordinates `com.horus.bpm` (proposed; repository renamed to horus-Agentic-BPM).
- Rule Engine first or Process Engine first in milestone 1 (Rule Engine proposed: smaller and immediately useful on its own).
- Expression language: own grammar versus a restricted subset of an existing one.
