# Guarded agent steps (`agentTask`)

An `agentTask` is a process step performed by an AI agent. The agent's output cannot move the process forward until it has passed two deterministic checks: a JSON Schema and a rule set that you own. When a rule fires, the step goes to a human approver or fails.

Design preview: `agentTask` is planned for Process Engine 0.1 in milestone M2, target 26 March 2027. Milestone M3 (1.0, target 21 May 2027) adds adapters and planning depth. See [What ships when](#what-ships-when) and [[Feature status|Feature-Status]].

## The problem

| Problem | What goes wrong without a control |
|---|---|
| Agent output is plausible but can be wrong | A misread document or an invented field flows into the next step and changes a customer's outcome |
| Instructions in a prompt are not a control | "Do not approve expired documents" in a prompt is a request to the model. An auditor cannot verify it, and a crafted input can override it |
| Model calls are slow | A 10-second call inside a database transaction holds a connection for 10 seconds. With 100 concurrent calls, a 20-connection pool runs out at the 20th |
| Someone must answer for each outcome | After the fact, you need to show what the agent saw, what it returned, which rule decided, and who approved |

## How the guardrail works

![Guardrail gate: two short transactions around the agent call](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/process-engine-guardrail-gate.svg)

```yaml
- id: assess-documents
  type: agentTask
  agent: kyc-document-reviewer           # adapter registered by the host (in-process bean or HTTP)
  inputs: { documents: "${instance.documents}", customerType: "${instance.customerType}" }
  outputSchema: schemas/document-assessment.json
  guardrails:
    decision: kyc-document-guardrails    # Rule Engine rule set evaluated on the agent's output
    onViolation: route-to-human          # or fail
  timeout: PT5M
  retry: { max: 2, backoff: PT30S }
```

| Stage | Where it runs | Outcome |
|---|---|---|
| 1. Resolve the bounded inputs; mask fields flagged `mask: true` | Transaction 1 | Only the declared inputs reach the agent. Masked inputs are stored hashed in history |
| 2. Claim the step with a lease, commit | Transaction 1 | The step waits; no lock and no connection is kept |
| 3. Call the adapter under `timeout` | Outside any transaction | Raw output and latency, or a timeout |
| 4. Validate the output against `outputSchema` | Transaction 2 | Invalid output counts as a failed attempt (`AGENT_SCHEMA_INVALID`) |
| 5. Evaluate the guardrail rule set on the output | Transaction 2, in process | `allow: true`: the process continues. Violation with `route-to-human`: an approval task carrying the inputs, the output and the matched rule ids. Violation with `fail`: an incident or compensation |
| 6. Retries exhausted | Transaction 2 | An incident with `AGENT_TIMEOUT` or `AGENT_SCHEMA_INVALID` and the retry count |
| 7. A human edits or rejects the output on the approval task | Task completion | The difference between agent output and human decision is recorded in history (`human_override`) |

Rules the engine enforces:

| Rule | Requirement |
|---|---|
| The process cannot continue until the output validates and the guardrail allows it | `TR-AGT-01`, `PRE-010` |
| The identity that triggered the step cannot approve its escalation | `PRE-007` |
| Inputs, output, schema result, guardrail decision id and adapter latency are recorded; masked inputs are hashed | `TR-AGT-02`, `PRE-012` |
| No adapter or model call runs inside a database transaction or while a row lock is held | `TR-ASY-01`, `PRE-009` |
| A result that arrives after its lease expired is discarded; another attempt owns the step | `TR-ASY-02` |
| The engine never embeds a model and never calls the network itself; your adapter does | `TR-AGT-03` |

The engine's share of an agent step is the two short transactions, about 10 ms in total. 100 concurrent agent calls of 10 seconds each hold no database connection while they wait ([[Architecture]] section 8).

### A guardrail is an ordinary rule set

The guardrail is a Rule Engine rule set with hit policy `first` or `unique`. It must declare the output `allow` (boolean) and may declare `reasons` (list of strings); the engine reads no other output. Deploying a process definition whose guardrail rule set is missing or breaks this contract fails with `422 GUARDRAIL_CONTRACT_INVALID`. At run time the check fails closed: a decision error counts as `allow: false` with reason `GUARDRAIL_EVALUATION_ERROR` and follows `onViolation`.

Because it is a rule set, it has everything a rule set has:

- versions, effective dates and a decision log entry for every check;
- a second approver for every change: loosening a guardrail needs the same four-eyes approval as changing a fee;
- simulation of a draft before publication; replay of past checks is planned for M3.

Your compliance team reads the guardrail as a table, not as code or prompt text.

## Autonomy ladder

Give an agent the least autonomy at which its mistakes are cheap, and widen it only on evidence. In HORUS, each step up the ladder is a new version of the guardrail rule set, approved by a second person.

| Rung | The agent may | The human | How to configure it | Move up when |
|---|---|---|---|---|
| 0. Observe | Produce output that nothing downstream uses | Does the work as before | Process design: the agent's output goes to history only, and a `userTask` decides | Output matches the human decision over at least two weeks |
| 1. Suggest | Draft every decision | Reviews and approves every case; each edit is recorded as `human_override` | Guardrail's last row returns a violation for every case, so every step routes to a human | Reviewers accept most drafts unchanged for about four weeks, and missed problems stay below your threshold |
| 2. Act with a risk gate | Complete low-risk cases | Handles escalations: low confidence, high value, any rule hit | Guardrail allows only cases that pass every rule; the rest route to a human | Escalation rules stable, no critical incident |
| 3. Act and report | Complete all cases inside the schema and rules | Audits a sample and the exceptions | Guardrail allows most cases; the host samples completed steps from history for review. The engine has no built-in sampling | Failure modes known and covered by regression tests |
| 4. End to end | Resolve cases fully inside policy | Samples for quality | As rung 3, with a smaller sample | Matches or beats the human baseline on your evaluation set |

When the input changes (a new document type, a new country, a new regulation, a new model version), move down one rung until the agent is measured again.

For regulated decisions, start at rung 1. Human override rate, read from history, is the first measure of whether to move up.

## Worked example: KYC document review

A bank's onboarding process asks an agent to read identity documents. The agent returns a structured assessment; a guardrail decides whether a person must look.

### 1. The output schema

`schemas/document-assessment.json` requires these fields. An output that misses one, or has the wrong type, fails stage 4.

```json
{
  "documentType": "PASSPORT",
  "nameMatches": true,
  "expiryDate": "2031-05-01",
  "tamperingSuspected": false,
  "confidence": 0.97
}
```

### 2. The guardrail rule set

```yaml
schemaVersion: 1
ruleSet: kyc-document-guardrails
hitPolicy: first
inputs:
  - { name: documentType,       type: string,  allowed: [NATIONAL_ID, PASSPORT, OTHER] }
  - { name: nameMatches,        type: boolean }
  - { name: expiryDate,         type: date }
  - { name: tamperingSuspected, type: boolean }
  - { name: confidence,         type: decimal }
outputs:
  - { name: allow,   type: boolean }
  - { name: reasons, type: "list<string>" }
rules:
  - id: R1
    when: { tamperingSuspected: "== true" }
    then: { allow: false, reasons: "['TAMPERING_SUSPECTED']" }
  - id: R2
    when: { nameMatches: "== false" }
    then: { allow: false, reasons: "['NAME_MISMATCH']" }
  - id: R3
    when: { expiryDate: "< today()" }
    then: { allow: false, reasons: "['DOCUMENT_EXPIRED']" }
  - id: R4
    when: { documentType: "== 'OTHER'" }
    then: { allow: false, reasons: "['UNSUPPORTED_DOCUMENT']" }
  - id: R5
    when: { confidence: "< 0.90" }
    then: { allow: false, reasons: "['LOW_CONFIDENCE']" }
  - id: R6
    when: {}
    then: { allow: true }
```

This is version 2, at rung 2. Version 1, at rung 1, was identical except that R6 returned `allow: false` with reason `HUMAN_REVIEW_REQUIRED`, so every case went to a person. Publishing version 2 needed a second approver. From M3 the approver can first replay the checks made under version 1 to see how many cases version 2 would have let through.

### 3. Three runs

| Run | Agent output | Guardrail | What happens |
|---|---|---|---|
| A | Passport, name matches, expires 2031-05-01, no tampering, confidence 0.97 | R6, allow | The process continues. History holds the hashed inputs, the output, schema result `PASS`, the guardrail decision id and the latency |
| B | National ID, name matches, valid, no tampering, confidence 0.72 | R5, reason `LOW_CONFIDENCE` | An approval task is created for the compliance role, carrying the inputs, the output and rule id `R5`. The reviewer opens the scan, finds that the agent misread the expiry date, corrects `expiryDate` and approves. History records the `human_override` difference |
| C | The adapter returns text that is not valid JSON on every attempt | None: stage 4 fails | Each failure counts as an attempt; the step is retried after 30 seconds. When retries are exhausted, an incident `AGENT_SCHEMA_INVALID` opens with its retry count. An operator fixes the cause and retries the incident from the cockpit |

If the adapter does not answer within 5 minutes (`timeout: PT5M`), the attempt fails the same way and the incident code is `AGENT_TIMEOUT`.

## What you own and what the engine owns

| You own | The engine owns |
|---|---|
| The adapter that calls a model, an HTTP service or, from M3, an MCP tool | Calling your adapter outside any transaction, with timeout, retry and lease |
| The prompt, the model and its version | Nothing about prompts or models; it never embeds or calls one |
| The output schema | Validating every output against it |
| The guardrail rule set and its approvers | Evaluating it on every output; enforcing four-eyes on its changes |
| Your evaluation set and the decision to move up the ladder | Recording inputs, outputs, guardrail decisions and human overrides so you can measure |

## What ships when

| Capability | M2 (Process Engine 0.1, target 26 Mar 2027) | M3 (1.0, target 21 May 2027) |
|---|---|---|
| Adapters | Adapter SPI: in-process bean and HTTP | MCP tool adapter; reference adapters for common LLM APIs in `examples/` (outside the engine artifacts) |
| Checks | JSON Schema validation; guardrail rule set; `onViolation: route-to-human` or `fail` | Same checks, also applied to agent-chosen steps |
| Reliability | Async wait-state, timeout, retry, incidents | |
| Audit | Inputs (masked fields hashed), output, schema result, guardrail decision, latency, human override | |
| Cockpit | Agent task inspector: inputs, schema verdict, output, fired rules; approve or override with a reason (Cockpit 0.2) | |
| Agent context | Bounded inputs per step | Bounded context passed as agent memory |
| Planning | One agent call per step | Agent chooses steps inside a bounded block, under the same guardrails |
| Evaluation | History and human overrides available for your own analysis | Offline runner that replays recorded agent traces and human overrides against a changed prompt or guardrail |

Multi-agent planning is a candidate after 1.0 ([[Roadmap]]). Dates are targets, not commitments; [[Feature status|Feature-Status]] links to the current state of each item.

## Related pages

- [[Process Engine Overview]]: execution model, async wait-state, incidents, compensation
- [[Architecture]]: transaction model and connection math
- [[Rule Engine Versioning and Publishing]]: how a guardrail change is approved
- [[Multi-Tenancy and Security]]: four-eyes and audit controls
- [[Concepts]]: agent task and guardrail definitions
