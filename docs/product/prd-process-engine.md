# PRD: Process Engine

| Field | Value |
|---|---|
| Type | Product requirements |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

Design preview. Behaviour is specified in [`process-engine/docs/SPEC.md`](../../process-engine/docs/SPEC.md) (`PRE-*`); release requirements in [requirements.md](requirements.md) (`TR-*`); milestone dates in the [product roadmap](roadmap.md); feature scope and status in the [feature map](feature-map.md); decisions in the [decision log](decisions.md).

## 1. Summary

A process library that runs human, service, decision and AI agent steps inside a Spring Boot service, with state in the service's PostgreSQL. Its `agentTask` step lets an agent act only after its output passes a JSON Schema and a guardrail rule set. It ships in M2, after the Rule Engine it uses for `decision` steps and guardrails.

## 2. Maintainers

The maintainers own this PRD and decide its scope. Proposals arrive as issues labelled `type:proposal`.

## 3. Background

Approval flows in regulated services mix people, internal calls, external systems that commit independently, and now AI agents. Agents add two risks: a slow model call inside a database transaction exhausts a shared connection pool, and a prompt is not a control an auditor can verify. [ADR-0007](../adr/0007-compensation-instead-of-distributed-transactions.md) and [ADR-0008](../adr/0008-async-wait-state-for-external-steps.md) set the answers: compensation instead of two-phase commit, and every external call outside any transaction.

## 4. Objective

Let teams orchestrate approvals, compensating sagas and bounded AI agents inside their own service and database.

| # | Key result | Target | Source |
|---|---|---|---|
| KR1 | An end-to-end guarded agent flow runs in the reference host | `examples/both` runs a process with a `decision` step and an `agentTask` whose guardrail escalates to a human | Roadmap M2 exit |
| KR2 | Slow external steps never exhaust the host pool | Zero pool timeouts with 10 concurrent 2 s adapters on a 2-connection pool, and with 20 concurrent 5 s adapters on a 10-connection pool | `TR-ASY-01`, Roadmap assumption 1 |
| KR3 | Adoption with agents | Two adopters outside the maintainers in production or pilot, at least one using `agentTask` | Roadmap M3 exit |

## 5. Segments by job

| Segment | Job | Constraints |
|---|---|---|
| S1 Host developer | Model an approval or onboarding flow in the service that owns the data, with retries that never double-start or double-complete | Shared connection pool; multiple replicas |
| S2 Operations team | Work task queues within SLA, and fix stuck or failed instances | Business hours and holidays per tenant; four-eyes |
| S3 Agent step designer (developer with a compliance owner) | Let an agent take a step without letting it decide unchecked | Output must be checkable and every override auditable |

## 6. Value propositions

| Part | S1 Host developer | S2 Operations team | S3 Agent step designer |
|---|---|---|---|
| Why | Flows span people and systems that fail independently | Work must finish within SLA and failures must be visible | Agents are useful on unstructured work but wrong in plausible ways |
| What before | Status columns and hand-written retries, or a remote engine | Spreadsheets of stuck cases; SLAs in calendar hours | Prompt instructions as controls |
| How | Versioned definitions; sync steps in the host transaction; business keys and idempotency keys; declared `compensate` handlers | Queues by user, role or group; SLA on a `BusinessCalendar`; incidents with retry and resolve | Bounded inputs, schema validation, a guardrail rule set, `route-to-human` or `fail`, full audit |
| What after | Retries are safe; failed sagas undo in reverse order with a record | Deadlines respect holidays; a stuck step is an incident with a next action | Autonomy grows one guardrail version at a time, each approved by a second person |
| Alternatives | Camunda 8 (separate cluster); Camunda 7 forks or Flowable (embedded); Temporal (workflows as code) | A BPM vendor's task list; host-built inboxes | BPM agent connectors where the modeller builds the checks |

Statement: for Java teams whose processes include people, external systems and AI agents, the Process Engine runs those steps in the service's own database, with safe retries, compensation, and agent output that must pass rules before the process moves.

## 7. Solution

### 7.1 User journey

Developer deploys `kyc-onboarding` v1; deploy rejects a guardrail that breaks the contract → client starts an instance with a business key; a retry returns the same instance → `decision` step runs in the host transaction → `agentTask` calls the adapter with no connection held, validates the output, evaluates the guardrail → on violation, an approval task goes to the compliance role → reviewer corrects a field; the override is recorded → a later remote step fails after retries; completed steps compensate in reverse order → operator retries the incident from the cockpit.

### 7.2 Must-have capabilities by platform layer

| Layer | Capability | SPEC | TR |
|---|---|---|---|
| Data | Definitions versioned; instances pin their version | PRE-001 | TR-TEN-02 |
| Data | Append-only history, including agent I/O and human overrides | PRE-012 | TR-OBS-01, TR-AGT-02 |
| Logic | Business key and idempotent start; optimistic locking | PRE-002, PRE-003 | TR-IDEM-01 to TR-IDEM-03, TR-IDEM-05 |
| Logic | `exclusiveGateway`; `parallelGateway` join without lost wakeups | PRE-004, PRE-005 | TR-DET-01, TR-ASY-03 |
| Logic | Replica-safe timers and leases | PRE-006, PRE-017 | TR-PERF-05, TR-ASY-02 |
| Logic | Four-eyes on approval tasks | PRE-007 | TR-SEC-04 |
| Logic | SLA deadlines on a business calendar | PRE-008 | TR-CAL-01, TR-CAL-02 |
| Logic | Async wait-state for agent and remote steps | PRE-009 | TR-ASY-01, TR-AGT-03 |
| Logic | Agent output gate and guardrail contract | PRE-010, PRE-016 | TR-AGT-01 |
| Logic | Agent retries and incidents | PRE-011 | TR-OPS-03 |
| Logic | Reverse-order compensation with attempt ids | PRE-013 | TR-COMP-01 to TR-COMP-03, TR-IDEM-04 |
| Logic | Migration dry run and batched execution | PRE-014, PRE-015 | TR-MIG-01, TR-MIG-02 |
| API | `/api/bpm/processes/v1`; `ServiceAdapter`, `AgentAdapter` and `BusinessCalendar` SPIs | partial | TR-SEC-08 |
| Application | Cockpit 0.2 process screens | See [prd-cockpit.md](prd-cockpit.md) | TR-UI-01 |
| Observability | Metrics for active instances, incidents, timer lag; events for start, completion, incident | none | TR-OBS-02, TR-OBS-04, TR-OBS-06 |

### 7.3 `agentTask`: autonomy ladder and eval bar

The engine enforces bounded inputs, output schema, guardrail rule set (`allow`, optional `reasons`, hit policy `first` or `unique`), `route-to-human` or `fail`, timeout, retry and audit. A guardrail that errors counts as `allow == false` (fail closed).

Adopters set autonomy through guardrail versions, each needing a second approver. This ladder is adoption guidance built on M2 capabilities; the engine enforces no percentage in it.

| Rung | Agent may | Configured as | Move up when |
|---|---|---|---|
| 1 Suggest | Draft every case | Guardrail's default row returns `allow: false`; every case routes to a human | Override rate low and stable over about four weeks |
| 2 Act with risk gate | Complete cases that pass every rule | Rules flag low confidence, high value, mismatches; the rest allow | No critical guardrail bypass; escalation rules stable |
| 3 Act and report | Complete most cases | As rung 2; the host samples completed steps from history | Failure modes known and covered by regression cases |

For rungs 0 (observe) and 4 (end to end), see open questions. When inputs, model or regulation change, move down one rung.

Recommended eval bar, measured by the adopter on 30 to 50 labelled cases including malformed and adversarial inputs: rung 1 needs full review, not accuracy; rung 2 needs 95 percent correct and every seeded critical error caught by schema or guardrail; rung 3 needs 99.5 percent and zero critical bypasses. The engine's own bar, tested in CI with stub adapters: no output that fails the schema or the guardrail ever advances a process (`TR-AGT-01`, PRE-010, PRE-011, PRE-016). The M2 signal is the human override rate read from history; the M3 offline runner replays recorded traces and overrides against a changed prompt or guardrail.

### 7.4 Specification gaps

| Capability | Milestone | Requirement |
|---|---|---|
| `userTask` assignment, claim, delegate, reassign; `serviceTask`, `decision`, `timer`, `callProcess` behaviour | M2 | TR-IDEM-03 |
| SLA escalation firing (PRE-008 covers only the deadline) | M2 | TR-CAL-01 |
| `onViolation: fail` path; escalation task refuses the identity that triggered the step | M2 | TR-AGT-01, TR-SEC-04 |
| Late result after lease expiry is discarded | M2 | TR-ASY-02 |
| `saga` block scope and `throwCompensation` | M2 | TR-COMP-01 |
| Incident resolve; history of every transition; process tenant isolation | M2 | TR-OPS-03, TR-OBS-01, TR-TEN-01 |
| Throughput of 1 000 transitions per second per replica | M2 | TR-PERF-04 |
| MCP adapter, bounded agent memory, agent-chosen steps in a bounded block, offline runner | M3 | TR-AGT-04 |
| Renamed-step mapping and cancellation | M3 | TR-MIG-03 |
| Definition lifecycle, graph diff, activity history, live node counts, task query, task forms (decided 2026-11-18, PD-032 to PD-038; sections 5 to 7 to be revised with journeys for the diagram views) | M2 | TR-SEC-04, TR-OBS-01 |
| Layout storage, step catalogue, hourly rollup, BPMN subset export | M3 | TR-DET-01 |

### 7.5 Open questions

- Proposal: `AgentResult` carries optional model and prompt version so the offline runner can compare like with like. `TR-AGT-02` records neither today. Pending as [PD-014](decisions.md#pd-014-model-and-prompt-version-in-agentresult).
- Proposal: a documented shadow (observe-only) pattern for rung 0, and whether rung 4 needs anything beyond rung 3. Pending as [PD-015](decisions.md#pd-015-autonomy-rung-0-shadow-mode-and-rung-4).
- Does parent failure compensate a `callProcess` child automatically? (DESIGN open question, gate M2 start.)

## 8. Release and scope

| Milestone | In scope |
|---|---|
| M2 (Process Engine 0.1) | Everything in 7.2; `agentTask` with in-process and HTTP adapters; same-id migration; definition lifecycle with maker-checker, import as draft and content hash (PE-F-45), graph diff (PE-F-46), activity history and live node counts (PE-F-47, PE-F-48), task query and task forms (PE-F-50, PE-F-51) |
| M3 (1.0) | MCP adapter and reference LLM adapters in `examples/`; bounded agent memory; agent-chosen steps under the same guardrails (after 1.0 if PD-028 is decided as recommended); offline runner; renamed-step migration; BPMN subset import (protected) and export (cut candidate); layout storage and step catalogue for the modeller (PE-F-44, PE-F-52); hourly node rollup (PE-F-49); zero-downtime upgrade tests; API freeze |
| After 1.0 (candidates) | Message correlation; multi-agent planning; other databases |

Non-goals: full BPMN 2.0 or CMMN; a full BPMN 2.0 modeller or any canvas element the engine cannot run ([PD-041](decisions.md#pd-041-conflict-vision-non-goal-diagram-modeller)); two-phase commit ([ADR-0007](../adr/0007-compensation-instead-of-distributed-transactions.md)); a distributed job executor or broker; any model call inside a transaction ([ADR-0008](../adr/0008-async-wait-state-for-external-steps.md)); an embedded model or prompt store (`TR-AGT-03`).

## Red-team

| # | Assumption | Fails if | Evidence | Kill criterion | Cheapest test |
|---|---|---|---|---|---|
| 1 | Guardrail rules over structured output catch the agent errors that matter | The costly errors (a misread date with high confidence) pass every rule, so rung 2 is unsafe | Run the KYC example with 30 labelled outputs, 10 with seeded critical errors | More than 2 of 10 seeded critical errors pass schema and guardrail | One day with a stub adapter. If killed, publish rung 1 as the only guidance until the M3 offline runner exists |
| 2 | Evaluators accept a YAML or JSON definition subset without a modeller | Architects shortlist only engines with BPMN diagrams | Ask three architects evaluating BPM options | Two of three would not shortlist without a modeller | Three conversations during M1. If killed, move BPMN subset import ahead of agentic depth in M3 |
| 3 | The M2 scope fits ten weeks (PD-042) | Async wait-state, compensation, migration and the diagram views slip together and `agentTask` lands late | Track the PRE statements green per week against plan | Fewer than 9 of PRE-001 to PRE-017 green by the end of week 7 (5 Mar 2027) | Weekly count. If killed, apply the M2 cut list: migration execution (PRE-015), live counts on the diagram, `callProcess` (PD-017, PD-042) |
