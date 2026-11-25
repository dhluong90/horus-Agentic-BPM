# HORUS Agentic BPM

Decision tables and approval processes that run as a library inside your Spring Boot service, in its PostgreSQL transaction. Business users change rules, the engine enforces a second approver, and AI agent output must pass your deterministic rules before the process continues.

**Status: release candidate.** The engines and the cockpit have passed acceptance testing, and the 1.0 release is pending final acceptance. The API may change until 1.0. The state of each feature: [[Feature status|Feature-Status]].

**Licence:** Licensed under the PolyForm Noncommercial License 1.0.0. For any other use, contact HORUS. See [[Licensing and commercial use|Licensing-and-Commercial-Use]].

![System context](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/system-context.svg)

## What it looks like

A decision table in `src/main/resources/rules/loan-eligibility.yaml`:

```yaml
schemaVersion: 1
ruleSet: loan-eligibility
hitPolicy: first
inputs:
  - { name: age,    type: integer }
  - { name: income, type: money }
outputs:
  - { name: eligible, type: boolean }
  - { name: reason,   type: string }
rules:
  - id: R1
    when: { age: "< 18" }
    then: { eligible: false, reason: "'UNDER_AGE'" }
  - id: R2
    when: { income: "< 5000000 VND" }
    then: { eligible: false, reason: "'LOW_INCOME'" }
  - id: R3
    when: {}
    then: { eligible: true, reason: "'OK'" }
```

Evaluated from your service:

```java
DecisionResult r = rules.evaluate("loan-eligibility",
    DecisionInput.of("age", 25, "income", Money.ofMinor("VND", 8_000_000)));
r.matchedRuleIds();                   // ["R3"]
r.output("eligible", Boolean.class);  // true
```

The decision, its inputs and the matched row are written to the decision log in your transaction: if your service rolls back, the log entry rolls back with it. A change to this table takes effect only after a second person approves it, and the engine refuses self-approval whatever the caller's roles. The full walkthrough is in [[Getting Started]].

## The three capabilities

| Capability | What it means in practice | Detail |
|---|---|---|
| Runs inside your service and PostgreSQL transaction | Add a Spring Boot starter. Engine state lives in your database under the `rle_` and `pre_` table prefixes. No engine cluster, no search index, no separate identity service, no message broker | [[Architecture]] |
| Rule changes by business users, second approver enforced in the engine | Draft, simulate, request publication, a different identity approves, the version takes effect on its effective date. No code deployment. The check is in the service layer, not only in the user interface. Process definitions get the same lifecycle in Process Engine 0.1 | [[Rule Engine Versioning and Publishing]] |
| Agent output checked by deterministic rules before the process continues | An `agentTask` step must return output that matches a JSON Schema and passes a rule set you own. If a rule fires, the step goes to a human approver or fails. The engine never calls a model itself | [[Guarded agent steps|Guarded-Agent-Steps]] |

## Who it is for, and who it is not for

| A good fit if you | Not a good fit if you |
|---|---|
| Build Java 21 or 25 services on Spring Boot 4.x (3.5.x supported until 1.0) with PostgreSQL 14 or later (15 or later from 1.0) | Run on another language, framework or database before 1.0 |
| Need business rules that product, risk or compliance staff change under four-eyes approval with effective dates | Need full BPMN 2.0 execution or DMN with FEEL before the staged releases after 1.0, CMMN, or a general-purpose diagram tool |
| Need approval flows, human task queues with SLAs, and compensating service steps inside one service | Want one central orchestration platform shared by many teams and run by a platform team |
| Want AI agents to take process steps, but only within rules your compliance team can read | Need durable execution of arbitrary code across many services |
| Must keep the decision log and process history in your own database, in your own transactions | Need a product with production references: this is a release candidate, pending final acceptance |
| Serve several businesses or tenants whose rules, roles and calendars differ, and want settings rather than a fork | Need an engine that ships one industry's business rules built in |

Not sure? Read [[Compare and choose|Compare-and-Choose]]. It sets this project against Camunda 8, the Camunda 7 forks, Flowable, Drools and Kogito, Temporal and hand-coded rules, including where each of them is the better choice.

## Start here

| You are | Your question | Read |
|---|---|---|
| Evaluating or choosing an architecture | Does it fit, what does it replace, what does it not do? | [[Compare and choose|Compare-and-Choose]], [[Architecture]], [[Feature status|Feature-Status]] |
| A developer adding it to a service | How do I get a first process and decision running? | [[Getting Started: your own service|Getting-Started-Your-Own-Service]], [[Developer guides|Guides]], [[Troubleshooting]] |
| A rule author or business analyst | How do I change a rule and get it approved? | [[Rule Engine Authoring Decision Tables]], [[Rule Engine Versioning and Publishing]], [[Cockpit]] |
| Designing AI agent steps | How is agent output checked, and who reviews it? | [[Guarded agent steps|Guarded-Agent-Steps]], [[Process Engine Overview]] |
| An operator | What runs in my service, and what fails how? | [[Architecture]] sections 8 and 9, [[Cockpit]] |
| A security or compliance reviewer | How are tenants isolated and approvals enforced? | [[Multi-Tenancy and Security]], [[Rule Engine Decision Log and Audit]] |
| Buying or approving a licence | Does my use need a commercial licence? | [[Licensing and commercial use|Licensing-and-Commercial-Use]] |
| A contributor | How do I propose a change? | [[Contributing]], [[Roadmap]] |
