# Process Engine Overview

_Planned for milestone 2. This page states the scope so that the Rule Engine API fits it._

## Scope of the first release
- Definitions in JSON/YAML, versioned; running instances pin their version.
- Nodes: `start`, `end`, `userTask`, `serviceTask`, `agentTask`, `decision` (calls a Rule Engine rule set), `exclusiveGateway`, `parallelGateway` (fork and join), `timer`, `callProcess`.
- Human tasks: assign by user, role or group; claim, complete, delegate, reassign; SLA timers and escalation.
- Maker-checker as a first-class pattern: `approval` is a `userTask` with the four-eyes constraint enforced by the engine.
- Persistence in the host database (`pre_` prefix); database-backed timers; history of every transition; retry for failed service and agent tasks.

## The `agentTask` node
```yaml
- id: assess-documents
  type: agentTask
  agent: kyc-document-reviewer          # a named adapter registered in the host (HTTP, MCP tool, in-process bean)
  inputs: { documents: "${instance.documents}", customerType: "${instance.customerType}" }
  outputSchema: schemas/document-assessment.json   # structured output, validated before the process continues
  guardrails:
    decision: kyc-agent-guardrails      # a Rule Engine rule set evaluated on the agent's output
    onViolation: route-to-human         # or fail
  timeout: PT5M
  retry: { max: 2, backoff: PT30S }
```
An agent is a participant with the same obligations as a service: bounded inputs, a schema for outputs, a timeout, and an audit record. The Rule Engine supplies deterministic guardrails over what the agent returns, and a `userTask` approval can be required for outcomes the rules flag. The engine never embeds a model; adapters live in the host.

## Out of scope
Full BPMN 2.0, CMMN, message correlation across processes (later), distributed job executor, standalone server mode.
