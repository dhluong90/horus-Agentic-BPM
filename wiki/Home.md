# HORUS Agentic BPM

**Lightweight, embeddable Process Engine and Rule Engine for Java and Spring Boot, where humans, services and AI agents are first-class participants.** Source-available: free for noncommercial use under the PolyForm Noncommercial License 1.0.0; commercial use under a HORUS commercial licence.

> **Design preview.** This wiki describes the 0.1 target. It is written before the code so that the public API is reviewed first. Anything may change until 1.0; each page says what is implemented.

## Start here
- [[Getting Started]] — add the Rule Engine starter, write a decision table, evaluate it in ten minutes.
- [[Concepts]] — decisions, rule sets, versions, hit policies, the decision log.
- [[Architecture]] — libraries not servers, host database, cockpit, how the two engines relate.

## Rule Engine guide
1. [[Rule Engine Authoring Decision Tables]]
2. [[Rule Engine Expression Language]]
3. [[Rule Engine Decision API]]
4. [[Rule Engine Versioning and Publishing]]
5. [[Rule Engine Simulation and Testing]]
6. [[Rule Engine Decision Log and Audit]]
7. [[Rule Engine Configuration]]

## Process Engine
- [[Process Engine Overview]] — scope and the `agentTask` node (planned for block 2).

## Operate and integrate
- [[Cockpit]] · [[Multi Tenancy and Security]] · [[FAQ]]

## Project
- [[Roadmap]] · [[Contributing]] · [[Release Notes]]

## Why "Agentic"
Classic BPM assigns tasks to people and systems. Agentic BPM adds a third participant: an AI agent that can pick up a task, reason over bounded inputs, and return a structured result, while the Rule Engine provides deterministic guardrails (limits, eligibility, pricing) and the Process Engine provides maker-checker approvals and a complete audit trail. Agents get autonomy inside rails that risk and compliance teams can read.
