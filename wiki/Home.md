# HORUS Agentic BPM

Embeddable Process Engine and Rule Engine for Java and Spring Boot, with a cockpit. Humans, services and AI agents take steps in the same process.

Design preview: this wiki describes the 0.1 target. Nothing is released yet and the API may change until 1.0. Licence: PolyForm Noncommercial 1.0.0 for noncommercial use, commercial licence from HORUS otherwise (see [[FAQ]]).

![System context](images/system-context.svg)

Two libraries inside your service, your PostgreSQL, one static cockpit. No engine cluster, no search index, no separate identity. The [[Architecture]] page explains how.

## Tutorials: learn by doing

| Page | You will |
|---|---|
| [[Getting Started]] | Add the starter to a Spring Boot service, load a loan-eligibility table, evaluate it from a test, over HTTP and from Java |

## How-to guides: get a task done

| Page | Task |
|---|---|
| [[Rule Engine Authoring Decision Tables]] | Write a decision table in YAML: inputs, outputs, rows, hit policy |
| [[Rule Engine Versioning and Publishing]] | Change rules without a deployment: draft, request, approve, schedule, roll back |
| [[Rule Engine Simulation and Testing]] | Check a draft before publishing and unit-test rule sets in CI |
| [[Rule Engine Decision Log and Audit]] | Find a decision, explain it, mask personal data, manage retention |
| [[Cockpit]] | Embed or host the cockpit and perform operator tasks in it |
| [[Contributing]] | Propose a change and get it merged |

## Reference: look something up

| Page | Contents |
|---|---|
| [[Rule Engine Expression Language]] | Literals, operators, functions, grammar, error codes |
| [[Rule Engine Decision API]] | Java API, REST endpoints, request and response schemas, error codes |
| [[Rule Engine Configuration]] | Every `horus.bpm.rules.*` property and the REST permissions |
| [[Technical Requirements]] | Numbered requirements (`TR-*`) with their verification method |
| [[Technical Stack]] | Libraries and tools chosen, and what was rejected |
| [[Release Notes]] | What shipped in each module version |

## Explanation: understand the design

| Page | Answers |
|---|---|
| [[Concepts]] | What do rule set, version, hit policy, decision, tenant and agent task mean? |
| [[Principles]] | Which twelve rules decide every trade-off? |
| [[Architecture]] | Where does state live, how do the modules depend on each other, how does it deploy? |
| [[Process Engine Overview]] | What will the Process Engine and the `agentTask` step do? |
| [[Multi-Tenancy and Security]] | How are tenants isolated and what can expressions never do? |
| [[Roadmap]] | What ships when, and what would make the maintainers change course? |
| [[FAQ]] | Why not Drools or Camunda, is it open source, what does "agentic" mean here? |
