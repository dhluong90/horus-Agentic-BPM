# When to use HORUS Agentic BPM

This page helps you decide whether HORUS Agentic BPM fits your system, and when it does not.

HORUS Agentic BPM is a release candidate: the 1.0 release is pending final acceptance. [[Feature status|Feature-Status]] lists what is built on `main` and what is not.

## Use it when

| Situation | Why it fits |
|---|---|
| Your service is Java and Spring Boot on PostgreSQL, and you want rules and processes inside it, not beside it | The engines are libraries. State lives in your database, in your transactions. A decision log entry rolls back with your business write |
| Product, risk or compliance staff must change rules without a code deployment, and every change needs a second approver | Rule set versions move through draft, pending approval and published; process definitions get the same lifecycle. The engine rejects approval by the requester (`403 SELF_APPROVAL_FORBIDDEN`) whatever the caller's roles. Versions take effect on an effective date |
| You want AI agents to perform process steps, and their output must be checked before anything else happens | An `agentTask` validates the agent's output against a JSON Schema, then evaluates a rule set on it. A rule that fires routes the step to a human or fails it. See [[Guarded agent steps|Guarded-Agent-Steps]] |
| Your approval flows need human task queues with SLAs on business hours, four-eyes approval and compensation of earlier service calls | The Process Engine provides these ([[Process Engine Overview]]) |
| You cannot run another cluster, search index or identity service for workflow alone | The deployment is your service plus your PostgreSQL plus static cockpit files |
| You serve several businesses or tenants whose rules, roles and calendars differ, and you will not maintain a fork | Generic by design ([[Principles]] 13): each business behaviour is a setting with a safe default or an extension point; business values live in your definitions and configuration ([[Configuration and Extension Points]]) |
| You have models drawn in a standard BPMN 2.0 or DMN modeler | Import them as files: [[Importing BPMN|Process-Engine-Importing-BPMN]]. An element the engine cannot run is refused by name |

## Do not use it when

| Situation | What to do |
|---|---|
| You need production references today. 1.0 is pending final acceptance | Wait for 1.0, or choose a product with a published production record |
| You need full DMN with FEEL or DMN conformance levels 2 and 3, a graphical BPMN 2.0 modeller, or CMMN | These are not built, or come after 1.0. CMMN is never supported. See [[Feature status|Feature-Status]] and [[Roadmap]] |
| You run one central orchestration platform for many teams and systems, with a platform team to operate it | HORUS Agentic BPM is built for governed change inside one service, not for a shared platform |
| You orchestrate long-running work across many services written in several languages | Not the target. The engines are Java libraries with a REST API for the rest |
| Your stack is not Java and Spring Boot, or your database is not PostgreSQL | Not supported before 1.0 |
| Your rules need inference: chaining, pattern matching over many facts, or complex event processing | The Rule Engine evaluates decision tables. It does not infer |
| You have a handful of rules that change once a year and no audit duty | Rules written in your code are simpler |
| Your licence policy accepts only OSI-approved open source licences | The project is source-available under PolyForm Noncommercial 1.0.0, not open source. Commercial use needs a commercial licence. See [[Licensing and commercial use|Licensing-and-Commercial-Use]] |

## What to check next

| Question | Read |
|---|---|
| Which features exist today? | [[Feature status|Feature-Status]] |
| How is it built, and how does it deploy? | [[Architecture]] |
| What does it do with a first process and decision? | [[Getting Started|Getting-Started]] |
| What is planned after 1.0? | [[Roadmap]] |

Corrections are welcome through an issue (see [[Contributing]]).
