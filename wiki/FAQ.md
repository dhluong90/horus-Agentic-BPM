# FAQ

Short answers with links to the page that owns the detail.

## Positioning

**Why not Drools, Camunda or Flowable?**
They are mature products with a far larger scope. HORUS Agentic BPM is for teams that need decision tables and approval flows inside their own service, with a footprint they control, and that want AI agents as process participants with deterministic guardrails. The trade-offs are in [[Principles]].

**What does "agentic" mean here?**
Classic BPM assigns steps to people and systems. Here an AI agent can take a step too, through an `agentTask`: it receives bounded inputs, must return output that matches a JSON Schema, that output is checked by a rule set, and a human approves when the rules say so. The engine never embeds or calls a model; the host registers an adapter. See [[Concepts]] and [[Process Engine Overview]].

**Is it a server?**
No. Two Spring Boot starters and a React cockpit built to static assets. State lives in your PostgreSQL (ADR-0002, ADR-0004). See [[Architecture]].

**Can I fit it to my business without forking it?**
Yes. That is principle 13, generic by design. Every business behaviour is optional and configurable per host or tenant, with a safe default. Your fee codes, roles, currencies, calendars and wording live in your rule sets, your configuration and your own beans, never in engine code. Some guarantees are never settings: tenant isolation, append-only audit, determinism, no code in definitions, fail-closed guardrails and four-eyes on publication. See [[Principles]] and [[Configuration and Extension Points]].

## Behaviour

**Can business users change rules without a deployment?**
Yes. They edit the cells and rows of a draft in the cockpit grid, or export the rows to a spreadsheet (CSV), edit them and import the file back as a draft. Then they simulate, request publication, a second person approves, and the version becomes effective on its date. A host that keeps production rule changes in Git can turn off cockpit editing per environment. Steps: [[Rule Engine Versioning and Publishing]].

**Does retiring a live rule version need a second approver?**
Not by default. One holder of `rules:approve` retires it with a mandatory reason, because a retirement only stops behaviour and nothing is deleted. A host can require a second approver for retirement by setting; the version keeps serving until a second identity confirms. See [[Rule Engine Configuration]].

**Is evaluation deterministic?**
Yes. Same version, inputs and clock give the same outputs, and the decision log records all three (TR-DET-01, TR-DET-05 in [[Technical Requirements]]).

**Does it read DMN or BPMN files?**
Yes, in part. BPMN 2.0 files import, run and export, and DMN decision tables import and export; BPMN 2.0 and DMN are the models behind the engines' own JSON (principle 9). An element the engine cannot execute is refused, and the error names it. DMN conformance levels 2 and 3 come after 1.0, with no dates set, with a published list of deviations. Choreography and conversation diagrams are kept untouched with a warning, not executed. CMMN is never supported. See [[Roadmap]].

**Is the expression language FEEL?**
Not fully. The Rule Engine uses its own typed expression language, which follows FEEL's rules for missing values (null). Process conditions and scripts and DMN decision tables accept FEEL, a subset in tables. The full FEEL language is not built. See [[Rule Engine Expression Language]].

**Which databases?**
See [[Technical Stack]]. The storage layer sits behind a small SPI so that others can follow.

**Can I run the engines without Spring?**
The `*-core` artifacts have no Spring dependency (TR-EMB-03). Wiring without Spring is possible but not documented yet (ADR-0001).

**Where is the cockpit's state?**
It has none. The cockpit talks only to the REST layer of your services (ADR-0005). Hosted standalone, it reaches several services after one sign-in. See [[Cockpit]].

**Can the cockpit run inside my own web application, with a server-side login session?**
Yes. The engine asks a tenant resolver for the tenant. The default reads it from the authenticated principal in your Spring Security context, which covers a session login and a validated token alike. You can replace the resolver with your own bean. The engine never takes the tenant from anything the browser sends. See [[Multi-Tenancy and Security]].

## Licence

**What licence does it use?**
Licensed under the PolyForm Noncommercial License 1.0.0. For any other use, contact HORUS. See [LICENSE](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/LICENSE) and [COMMERCIAL-LICENSE.md](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/COMMERCIAL-LICENSE.md). The licence text is the only authority.

**Who decides what goes in?**
The maintainers. Decisions are recorded as ADRs in `docs/adr/`; scope and dates are on the [[Roadmap]].
