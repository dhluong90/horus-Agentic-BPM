# Compare and choose

This page helps you decide whether HORUS Agentic BPM fits your system, and points you to a better option when it does not. It compares the project with Camunda 8, the Camunda 7 forks, Flowable, Drools and Kogito, Temporal, and rules written by hand in your service.

HORUS Agentic BPM is a release candidate: the 1.0 release is pending final acceptance. The HORUS column describes what is built on `main` and marks what is not; [[Feature status|Feature-Status]] lists the unbuilt items. The other columns describe other products.

## Use it when

| Situation | Why it fits |
|---|---|
| Your service is Java and Spring Boot on PostgreSQL, and you want rules and processes inside it, not beside it | The engines are libraries. State lives in your database, in your transactions. A decision log entry rolls back with your business write |
| Product, risk or compliance staff must change rules without a code deployment, and every change needs a second approver | Rule set versions move through draft, pending approval and published; process definitions get the same lifecycle. The engine rejects approval by the requester (`403 SELF_APPROVAL_FORBIDDEN`) whatever the caller's roles. Versions take effect on an effective date |
| You want AI agents to perform process steps, and their output must be checked before anything else happens | An `agentTask` validates the agent's output against a JSON Schema, then evaluates a rule set on it. A rule that fires routes the step to a human or fails it. See [[Guarded agent steps|Guarded-Agent-Steps]] |
| Your approval flows need human task queues with SLAs on business hours, four-eyes approval and compensation of earlier service calls | The Process Engine provides these ([[Process Engine Overview]]) |
| You cannot run another cluster, search index or identity service for workflow alone | The deployment is your service plus your PostgreSQL plus static cockpit files |
| You serve several businesses or tenants whose rules, roles and calendars differ, and you will not maintain a fork | Generic by design ([[Principles]] 13): each business behaviour is a setting with a safe default or an extension point; business values live in your definitions and configuration ([[Configuration and Extension Points]]) |

## Do not use it when

| Situation | Use instead |
|---|---|
| You need production references today (1.0 is pending final acceptance), or need a vendor with years of production references | Camunda 8, a Camunda 7 fork, Flowable, Drools, or Temporal, depending on the rows below |
| You need full DMN with FEEL or DMN conformance levels 2 and 3, a graphical BPMN 2.0 modeller, or CMMN | Camunda 8, a Camunda 7 fork, Flowable, or Drools and Kogito for DMN |
| You run one central orchestration platform for many teams and systems, with a platform team to operate it | Camunda 8 |
| You orchestrate long-running work across many services written in several languages | Temporal or Camunda 8 |
| Your stack is not Java and Spring Boot, or your database is not PostgreSQL | Temporal (several language SDKs), Camunda 8 (job workers in several languages) |
| Your rules need inference: chaining, pattern matching over many facts, or complex event processing | Drools |
| You have a handful of rules that change once a year and no audit duty | Hand-coded rules |
| Your licence policy accepts only OSI-approved open source licences | Camunda 7 forks, Flowable, Drools and Kogito (Apache 2.0), Temporal (MIT) |

## Comparison

Legend: **Yes** built in. **Partial** possible with limits stated. **No** not provided; you build it. **Not assessed** not verified for this page.

### Deployment and licence

| | HORUS Agentic BPM | Camunda 8 | Camunda 7 forks (CIB seven, Operaton) | Flowable (open source engines) | Drools and Kogito | Temporal | Hand-coded rules |
|---|---|---|---|---|---|---|---|
| Runs as | Library in your Spring Boot service | Remote cluster: Zeebe brokers and gateway, Elasticsearch or OpenSearch, Identity; Kubernetes is the usual target. SaaS available | Library in your Java service, or standalone | Library in your Java service, or standalone | Library in your Java service; Kogito services on Quarkus or Spring Boot | Your workers plus a Temporal Service (self-hosted cluster or Temporal Cloud) | Your code |
| Engine state | Your PostgreSQL, in your transactions | Zeebe's replicated log; history exported to Elasticsearch or OpenSearch. Relational secondary storage announced | Your relational database; can share the host transaction | Your relational database; can share the host transaction | Rules in memory (stateless or stateful sessions); process state depends on the runtime | The Temporal Service's own database (for example Cassandra, MySQL or PostgreSQL) | Your database |
| Licence | PolyForm Noncommercial 1.0.0; commercial use needs a commercial licence. Source-available, not open source | Camunda License 1.0: source-available, non-production only. Production needs an Enterprise agreement | Apache 2.0 | Apache 2.0 | Apache 2.0 | MIT (server and SDKs) | Yours |
| Production cost model | Commercial licence; terms on request | Enterprise by quotation, typically throughput-based (process instances, decision instances, task users) | Free; paid support offered for some forks | Free; commercial products sold separately | Free; vendor support available | Free self-hosted; Temporal Cloud is usage-priced | Engineering time |
| Languages | Java (Spring Boot starters; `*-core` artifacts have no Spring dependency) | Job workers in Java, Go, Node.js, C#, Python and others | Java | Java | Java | Go, Java, TypeScript, Python, .NET and others | Any |
| Maturity | New: a release candidate; 1.0 is pending final acceptance | Camunda 8 since 2022; Camunda since 2013 | Forks of Camunda 7, whose engine has run in production since 2013 | Since 2016 (fork of Activiti) | Drools for over 15 years; now part of Apache KIE (incubating) at the Apache Software Foundation | Since 2019 (fork of Cadence) | Yours |

### Rules

| | HORUS Agentic BPM | Camunda 8 | Camunda 7 forks | Flowable | Drools and Kogito | Temporal | Hand-coded rules |
|---|---|---|---|---|---|---|---|
| Decision tables | Yes: `first`, `unique`, `collect` hit policies; own typed expression language with money and dates, following FEEL's null rules; DMN decision tables import and export, with FEEL conditions in processes and a FEEL subset in tables; the full FEEL language is not built | Yes: DMN 1.3 with FEEL, decision requirement diagrams | Yes: DMN with FEEL | Yes: DMN | Yes: DMN with FEEL, plus DRL rules with inference | No | No |
| Rule change without a code deployment | Yes: edit a draft's cells and rows in the cockpit grid editor or round-trip it through a spreadsheet (CSV), or import it through the API; approve in the API or cockpit | Partial: a new DMN version is a deployment to the cluster, not a code release | Partial: same model as Camunda 7, a deployment | Partial: a deployment | Partial: depends on how you load rule artifacts | No | No |
| Second approver enforced before a rule change takes effect | Yes, in the service layer; also for process definitions | No, not out of the box | No in Camunda 7; check each fork | Not assessed | No, not in the engine libraries | No | No, unless you build it |
| Effective dating of rule versions | Yes | No, not out of the box | No in Camunda 7; check each fork | Not assessed | No, not in the engine libraries | No | No, unless you build it |
| Simulation of a draft before publishing | Yes: sample inputs, and replay of past decisions | Partial: Web Modeler can run a model with sample data before deployment | Not assessed | Not assessed | Partial: test tooling | No | Unit tests |
| Decision log | Yes: append-only, in your transaction, with explain | Partial: decision instances in Operate and Optimize | Partial: history tables | Partial: history tables | No, not in the engine libraries | No | No, unless you build it |
| Inference, rule chaining, complex event processing | No | No | No | No | Yes | No | No |

### Processes and agents

| | HORUS Agentic BPM | Camunda 8 | Camunda 7 forks | Flowable | Drools and Kogito | Temporal | Hand-coded rules |
|---|---|---|---|---|---|---|---|
| Process notation | BPMN 2.0 is the model, stored as JSON or YAML (principle 9). BPMN 2.0 files import, run and export; the executable BPMN 2.0 elements run, and an element the engine cannot run is refused by name. No CMMN | BPMN 2.0, broad coverage | BPMN 2.0, broad coverage; CMMN | BPMN 2.0 and CMMN | BPMN through jBPM and Kogito | Workflows written as code | Status columns in your tables |
| Diagrams and graphical modeller | Partial: read-only definition and instance diagrams with the current step; no graphical modeller (import from a standard modeller; every change is a draft that needs a second approver) | Yes: Desktop Modeler (MIT) and Web Modeler | Yes: Camunda Desktop Modeler works with Camunda 7 models | Yes | Yes: tooling for BPMN and DMN | No | No |
| Human tasks | Yes: queues by user, role or group, SLA on a business calendar, escalation, four-eyes enforced by the engine | Yes: Tasklist, forms, candidate groups | Yes: Tasklist, forms | Yes | Yes through jBPM | No: you build an inbox on signals or updates | No |
| Compensation of earlier steps | Yes: `compensate` handlers and `saga` blocks | Yes: BPMN compensation | Yes: BPMN compensation | Yes: BPMN compensation | Partial: depends on runtime | Yes, in your workflow code (saga pattern) | No, unless you build it |
| Operations user interface | Cockpit: rule editing and approvals, decision log, process diagrams, one task inbox and one incident list across services, instance migration | Operate, Tasklist, Optimize (Enterprise) | Cockpit, Tasklist | Flowable UI applications; more in commercial products | Not assessed | Temporal Web UI | No |
| Scale model | Your service replicas on one PostgreSQL. Design target: about 100 jobs per second per replica | Partitioned and replicated; designed for very high throughput | Relational database; job executor across nodes | Relational database; async executor across nodes | Not assessed | Sharded service; designed for very high throughput | Your service |
| AI agent as a process step | Yes: `agentTask` through an adapter you register. The engine never calls a model | Yes since 8.8: AI Agent connector, ad-hoc sub-process for agent-chosen tasks | No agent step in the open-source line | Not assessed | No | Partial: you write agent calls as activities in workflow code | No |
| Agent output checked by deterministic rules before the process continues | Yes: JSON Schema validation, then a rule set; on violation, route to a human or fail | No by default: guardrails are what the modeller builds around the agent | No | Not assessed | No | No, unless you write the check | No |
| Slow external calls hold no database connection | Yes: async wait-state, two short transactions around the call | Not applicable: no relational database in the engine; job workers run outside it | Partial: async continuations; you configure them per step | Partial: async jobs; you configure them per step | Not assessed | Yes: activities run in your workers | Depends on your code |

## Where each alternative wins

| Alternative | Where it wins over HORUS Agentic BPM | Choose it if |
|---|---|---|
| Camunda 8 | Broadest BPMN 2.0 execution and full DMN 1.3; very high throughput across partitions; mature operations tooling (Operate, Tasklist, Optimize); graphical modellers; ready-made connectors; job workers in many languages; AI agent orchestration released today; SaaS option; large community and documentation | You build a central orchestration platform across many systems, have a platform team, and the Enterprise licence fits your budget |
| Camunda 7 forks (CIB seven, Operaton) | Embeddable like HORUS, and released; Apache 2.0; broad BPMN, DMN and CMMN; years of production use of the underlying engine; existing Camunda 7 models, skills and tools carry over | You run Camunda 7 today and want to stay embedded without migrating to Camunda 8, or you need a permissive licence |
| Flowable | Embeddable and released; Apache 2.0; BPMN, CMMN and DMN; several relational databases; commercial products and support from the vendor | You need standard BPMN or CMMN inside a Java service, on a database other than PostgreSQL, or under a permissive licence |
| Drools and Kogito | Rule expressiveness: inference, forward chaining, complex event processing; DMN with FEEL; Apache 2.0; long history in production | Your problem is rule reasoning over many facts, not rule governance by business users |
| Temporal | Durable execution of long-running code across many services; SDKs in several languages; very high throughput; MIT licence; managed cloud option | Your workflows are code owned by engineers across a polyglot system, and you do not need decision tables, a task inbox or business-user rule changes |
| Hand-coded rules | No dependency, no licence, no new concepts; fastest for a few rules that rarely change | Rules change rarely, engineers own them, and no auditor asks who changed what and when |

## Moving from another product

| From | What carries over | What does not |
|---|---|---|
| DMN decision tables (Camunda, Flowable, Drools) | Concepts: hit policy, inputs, outputs, rows. DMN decision-table import and export are built | FEEL is read as a subset and rewritten in the HORUS expression language; decision requirement diagrams are not executed, and higher DMN levels come after 1.0 |
| BPMN processes (Camunda, Flowable) | Vocabulary: user task, service task, gateway, timer, incident. BPMN import and export are built | Elements the engine does not execute are refused by name; CMMN is never supported |
| Hand-coded rules and status columns | Your tests become rule set tests with the `rule-engine-test` library | Nothing is migrated automatically |

## What this comparison does not cover

Price levels, support quality and the commercial editions of Flowable and Drools vendors were not assessed. Facts about other products change with their releases; check their current documentation before you decide. Corrections are welcome through an issue (see [[Contributing]]).
