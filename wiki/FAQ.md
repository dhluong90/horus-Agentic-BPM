# FAQ

Short answers with links to the page that owns the detail.

Design preview: answers describe the 0.1 target unless they say otherwise.

## Positioning

**Why not Drools, Camunda or Flowable?**
They are mature products with a far larger scope. HORUS Agentic BPM is for teams that need decision tables and approval flows inside their own service, with a footprint they control, and that want AI agents as process participants with deterministic guardrails. The trade-offs are in [[Principles]].

**What does "agentic" mean here?**
Classic BPM assigns steps to people and systems. Here an AI agent can take a step too, through an `agentTask`: it receives bounded inputs, must return output that matches a JSON Schema, that output is checked by a rule set, and a human approves when the rules say so. The engine never embeds or calls a model; the host registers an adapter. See [[Concepts]] and [[Process Engine Overview]].

**Is it a server?**
No. Two Spring Boot starters and a React cockpit built to static assets. State lives in your PostgreSQL (ADR-0002, ADR-0004). See [[Architecture]].

## Behaviour

**Can business users change rules without a deployment?**
Yes. Draft in the cockpit, simulate, request publication, a second person approves, the version becomes effective on its date. Steps: [[Rule Engine Versioning and Publishing]].

**Is evaluation deterministic?**
Yes. Same version, inputs and clock give the same outputs, and the decision log records all three (TR-DET-01, TR-DET-05 in [[Technical Requirements]]).

**Does it read DMN or BPMN files?**
Not in 0.1. Both are planned for M3, which ends on 21 May 2027: import and export of a BPMN subset for process definitions, and import of DMN decision tables. A BPMN file with elements outside the subset is rejected, and the error names those elements. The engine's own process notation stays the source of truth. BPMN export and DMN import can move after 1.0 if the M3 checkpoint fails; see [[Roadmap]].

**Which databases?**
See [[Technical Stack]]. The storage layer sits behind a small SPI so that others can follow.

**Can I run the engines without Spring?**
The `*-core` artifacts have no Spring dependency (TR-EMB-03). Wiring without Spring is possible but not documented yet (ADR-0001).

**Where is the cockpit's state?**
It has none. The cockpit talks only to the REST layer of your services (ADR-0005). Hosted standalone, it reaches several services after one sign-in. See [[Cockpit]].

## Licence

**What licence does it use?**
Licensed under the PolyForm Noncommercial License 1.0.0. For any other use, contact HORUS. See [LICENSE](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/LICENSE) and [COMMERCIAL-LICENSE.md](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/COMMERCIAL-LICENSE.md). The licence text is the only authority.

**Who decides what goes in?**
The maintainers. Decisions are recorded as ADRs in `docs/adr/`; scope and dates are on the [[Roadmap]].
