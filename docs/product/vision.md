# Product Vision

| Field | Value |
|---|---|
| Type | Explanation |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

Design preview: nothing is released. Milestone dates are owned by the [product roadmap](roadmap.md) and feature scope by the [feature map](feature-map.md); this page links to them and never restates dates as commitments. Product decisions are in the [decision log](decisions.md).

## The problem

Java teams that build regulated services need three things that today come from three places. Business rules that product, risk and compliance staff can change under a second approver. Approval flows with human task queues, SLAs and compensation of earlier calls. And, increasingly, AI agents that take process steps whose output someone must be able to check and answer for.

The usual answers each cost something the team cannot pay:

| Today | What hurts |
|---|---|
| A separate BPM or decision cluster | A second system to operate, a network hop on every decision, engine state outside the business transaction |
| An embedded BPMN or DMN engine | Rule changes are deployments; a second approver and effective dates are left to the team |
| Hand-coded rules and status columns | Every team rebuilds versioning, audit and approval, differently |
| Prompt instructions as agent controls | An auditor cannot verify a prompt, and a crafted input can override it |

## Who it is for, and who it is not for

| For | Not for |
|---|---|
| Java 21 or 25 teams on Spring Boot 4.x (3.5.x supported until 1.0, PD-007) and PostgreSQL 14+ | Other languages, frameworks or databases before 1.0 |
| Services whose rules change under four-eyes approval with effective dates | Teams that need full BPMN 2.0, DMN FEEL, CMMN or a diagram modeller (the modeller part is under review in [PD-041](decisions.md#pd-041-conflict-vision-non-goal-diagram-modeller)) |
| Approval flows, task queues with SLAs and compensating service steps inside one service | One central orchestration platform shared by many teams |
| AI agent steps that must pass rules a compliance team can read | Durable execution of arbitrary code across many services, or rule inference and chaining |

## One-liner

Decision tables and approval processes that run as a library inside your Spring Boot service, in its PostgreSQL transaction. Business users change rules, the engine enforces a second approver, and AI agent output must pass your deterministic rules before the process continues.

The first sentence alone is true of other embeddable engines. The line depends on all three clauses.

## Three differentiators

| # | Differentiator | Rests on |
|---|---|---|
| 1 | Runs inside the host service and its PostgreSQL transaction: no engine cluster, no broker, no separate identity | Principles 1 and 2, [ADR-0002](../adr/0002-state-in-host-database.md), [ADR-0004](../adr/0004-no-standalone-server.md) |
| 2 | Rule changes by business users with maker-checker, effective dates and simulation enforced in the service layer, not only in a UI | Principles 5 and 6, `TR-SEC-04` |
| 3 | Agent output validated by a JSON Schema and a guardrail rule set before the process continues; on violation, a human approves or the step fails | Principle 7, `TR-AGT-01`, [ADR-0008](../adr/0008-async-wait-state-for-external-steps.md) |

## What the engine will never be

| Never | Why |
|---|---|
| A standalone server or shared engine service | Principle 1, [ADR-0004](../adr/0004-no-standalone-server.md). Remote access is the host's optional REST layer |
| The owner of a separate data store | Principle 2, [ADR-0002](../adr/0002-state-in-host-database.md) |
| A host for scripts or arbitrary code in definitions | Principle 4, [ADR-0003](../adr/0003-own-expression-language.md), `TR-SEC-01` |
| A model runtime: it never embeds or calls a model | Principle 7, `TR-AGT-03`, `TR-EMB-05` |
| A full BPMN 2.0, DMN FEEL or CMMN implementation | Principle 9. Supported is what is documented |
| A distributed transaction coordinator | [ADR-0007](../adr/0007-compensation-instead-of-distributed-transactions.md). Compensation, not two-phase commit |
| A cockpit with its own backend, database or login | [ADR-0005](../adr/0005-cockpit-as-static-assets.md) |

## Product shape

Two libraries and one cockpit. The Rule Engine is useful alone and is also the guardrail mechanism for `agentTask`. The Process Engine depends on the Rule Engine API; the Rule Engine never depends on the Process Engine. The cockpit is static assets that talk only to the REST layers the host exposes.

## How the pieces fit

| Module | Job it does | First version | Artifact | PRD |
|---|---|---|---|---|
| Rule Engine | Evaluate governed decision tables in process; change them without a deployment | 0.1 in M1 | `rule-engine-spring-boot-starter` | [prd-rule-engine.md](prd-rule-engine.md) |
| Process Engine | Run human, service, decision and agent steps with idempotency, compensation and SLAs | 0.1 in M2 | `process-engine-spring-boot-starter` | [prd-process-engine.md](prd-process-engine.md) |
| Cockpit | Let authors, approvers, operators and auditors act on both engines | 0.1 in M1, 0.2 in M2 | `cockpit-spring-boot-starter` and a static archive | [prd-cockpit.md](prd-cockpit.md) |

Release requirements are in [requirements.md](requirements.md); system design is in [architecture/overview.md](../architecture/overview.md).

## Success measures for 1.0

| Measure | Target | Source |
|---|---|---|
| External adoption | Two adopters outside the maintainers in production or pilot, at least one using `agentTask` | Roadmap M3 exit |
| Release gates | Every "must" requirement green in CI | Roadmap M3 exit, [requirements.md](requirements.md) |
| Time to first decision | A newcomer completes Getting Started in under 30 minutes | Roadmap M1 exit, kept through 1.0 |
| Contract stability | API frozen at 1.0; deprecations overlap one minor release | Roadmap M3, principle 8 |

Proposed, not yet adopted: a North Star of governed decisions and process steps executed per week by adopters' services. It needs adopter-reported numbers, since the engine sends no telemetry. Pending as [PD-019](decisions.md#pd-019-north-star-metric).
