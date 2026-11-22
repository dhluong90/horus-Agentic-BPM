# Process Engine Overview

This page explains what the Process Engine does, which guarantees it gives for human, service and agent steps, and how it keeps those guarantees across replicas. It ships in milestone 2 with the core of `agentTask`; milestone 3 adds the MCP adapter, reference LLM adapters and bounded multi-step planning. Internals: `process-engine/docs/DESIGN.md` and `SPEC.md` (`PRE-001` to `PRE-015`).

![Process instance states](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/process-instance-states.svg)

## Scope of the first release
| Area | Content |
|---|---|
| Definitions | JSON/YAML, versioned; running instances pin their version |
| Steps | `start`, `end`, `userTask`, `serviceTask`, `agentTask`, `decision` (Rule Engine), `exclusiveGateway`, `parallelGateway`, `timer`, `callProcess` |
| Human tasks | Assign by user, role or group; claim, complete, delegate, reassign; SLA timers and escalation; four-eyes approval enforced by the engine |
| Runtime | State in the host database (`pre_*`); execution tokens; database-backed timers and async jobs with leases; history of every transition; retry and incidents |
| Reliability | Idempotency keys on every mutating call; unique business key per tenant and definition; optimistic locking on tasks, instances and incidents |
| Compensation | `compensate` handlers on `serviceTask` and `agentTask`; `saga` blocks (BPMN `transaction`); reverse-order execution with retry and incidents (0.3.0, #67) |
| SLA | Deadlines computed through a `BusinessCalendar` SPI: working hours, holidays, time zone per tenant |
| Migration | Plan, dry run and batched execution of running instances from one definition version to another |
| Out of scope | Full BPMN 2.0, CMMN, cross-process message correlation (later), distributed job executor, standalone server |

## Instance states and execution tokens

| Level | States | Meaning |
|---|---|---|
| `pre_instance.status` | `RUNNING`, `COMPENSATING`, `COMPLETED`, `CANCELLED`, `FAILED` | `FAILED` is the terminal state after compensation. An open incident does not change the instance state |
| `pre_token.status` | `ACTIVE`, `WAITING_USER`, `WAITING_ASYNC`, `WAITING_TIMER`, `WAITING_SCOPE`, `JOINED`, `COMPLETED`, `COMPENSATED`, `CANCELLED` | Flow tokens: one row per position; `parallelGateway` creates one token per outgoing branch. Scope tokens: one per instance and one per entered `saga` block, `WAITING_SCOPE` while their steps run (ADR-0020) |

The transition table with guards and emitted events is in [[Architecture]] section 3.3.

## Two kinds of steps: sync and Async Wait-State

| Kind | Steps | Transaction rule |
|---|---|---|
| Sync in-transaction | `decision`, `exclusiveGateway`, `parallelGateway`, `userTask` state changes, a local `serviceTask` bean that writes only to the host database | Runs inside the caller's or the scheduler's transaction. Engine state and host writes commit or roll back together |
| Async Wait-State | `agentTask`, `serviceTask` with `mode: remote` (HTTP, MCP), compensation handlers that call other systems | TX1 claims the job with a lease and commits. The adapter runs with no database connection and no row lock. TX2 verifies the lease, records the result and advances, retries or opens an incident |

![Async wait-state protocol](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/async-wait-state.svg)

Numbers that follow from the rule: 100 concurrent agent calls of 10 s each hold 0 connections while waiting; the same calls inside a transaction would need 100 connections. The engine part of an async step costs two short transactions, about 10 ms in total. Adapters receive the job id as idempotency key, which a retry keeps, and an `attemptId` that changes on every attempt; a result that arrives after the lease expired is discarded because another attempt owns the step by then (`TR-IDEM-04`, `TR-ASY-01`, `TR-ASY-02`, ADR-0008).

A `serviceTask` with no `horus:mode` is a local step: its `ServiceTaskHandler` runs inside the caller's or the scheduler's transaction, its own writes to the host database commit or roll back with the step, and it needs no idempotency key. It must not call the network; a call above `handlers.slow-call-warning` (200 ms) is logged as `process.step.slow` without its inputs. A handler that throws rolls the step back with everything it wrote: a request answers `500 INTERNAL` with a `correlationId` only, and a timer or job firing is retried after `jobs.retry-backoff`. One process may mix both modes, for example a local limit reservation followed by a remote posting to core banking (`PRE-070`).

Since 0.2.0 a `serviceTask` with `horus:mode="remote"` (and an optional `horus:timeout`, default `adapters.default-timeout`) runs this way through a `ServiceAdapter` bean named by `horus:handler`. A job is claimed only when one of the replica's `adapters.executor-threads` slots is free; its lease is the timeout plus `jobs.lease-margin`. A crashed replica's attempt is claimed again when its lease ends, as attempt 2 with a new attempt id. A failed attempt is retried after `jobs.retry-backoff` times the attempt, up to `jobs.max-attempts`. An adapter that answers `Accepted` frees its thread at once; the remote side later reports the result through `POST /api/bpm/processes/v1/async-steps/{jobId}:complete` ([[Process Engine API]]) before the lease ends, with the attempt's completion token. The caller needs `processes:step:complete` and a role listed for that adapter in `horus.bpm.processes.adapters.completer-roles`.

Each async job kind has its own runner: remote service steps (`ASYNC_SERVICE`) and agent steps (`ASYNC_AGENT`) each get their own slots, default timeout, attempts and back-off under `horus.bpm.processes.runners.async-service.*` and `runners.async-agent.*`. Each kind is claimed separately, up to its own free slots, so five slow agent calls on two agent slots never hold up a credit bureau call. Every value you do not set falls back to `adapters.executor-threads`, `adapters.default-timeout`, `jobs.max-attempts` and `jobs.retry-backoff`; with nothing set, the engine behaves as before. Timers keep `jobs.*`.

The engine checks a result where it records it, so a result sent through the completion API is checked like one from the engine's own executor. Remote service results have no check. An agent step's answer is validated against its output schema and then judged by its guardrail rule set (output `allow`, optional `reasons`), in the result transaction and in-process, within `runners.async-agent.guard-budget` (default `500ms`).

An answer that breaks its schema gets a fresh attempt, up to `runners.async-agent.schema-retries` (default `1`), without using up `max-attempts`; the completion API answers `ACCEPTED` for it, because the step is not decided yet. The completion API answers `REFUSED`, which is always final, when:

- the answer still breaks its schema after the schema retries (`AGENT_SCHEMA_INVALID`);
- the guardrail says `allow: false` (`GUARDRAIL_VIOLATION`);
- the guardrail cannot decide, because the rule set is missing, nothing matched, it failed, it overran the budget or there is no Rule Engine (`GUARDRAIL_EVALUATION_ERROR`).

Nothing of a refused answer is accepted. The refusal is recorded in the history with the answer masked: only fields the output schema declares, and does not mark `mask: true`, keep their values. The step moves to a review task for the step's reviewer. It is always four-eyes: neither the instance's starter nor the caller who submitted the refused answer can take it, and the job payload cannot switch it off. The reviewer's answer must meet the output schema (`422 FORM_VALIDATION_FAILED`) and then completes the step. With `onViolation: fail`, or without a reviewer, the job fails instead. If the review task cannot be created, the refusal is still recorded and the job fails.

```java
public interface ServiceAdapter {
  String name();                                         // the horus:handler name
  AdapterResult execute(AdapterCall call);               // call: tenantId, instanceId, nodeId, businessKey, jobId, attemptId, completionToken, attempt, deadline, inputs; idempotencyKey() is the jobId
}
// AdapterResult: Completed(output) | BusinessError(code, message) | Failed(errorCode, detail, retryable) | Accepted()
public interface AgentAdapter {                          // with the agent step (#52)
  AgentResult execute(AgentInvocation invocation);       // adds outputSchema id; result carries raw output and latency
}
```

## `exclusiveGateway` routing: exactly one branch

![Exclusive gateway routing](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/exclusive-gateway-routing.svg)

An exclusive gateway takes exactly one outgoing flow, and the same one every time for the same variables (`PRE-004`, `TR-DET-01`).

| Rule | Behaviour |
|---|---|
| Order | Conditions are tested in document order; the first `true` wins and later ones are not evaluated |
| Default | Taken only when no condition is `true`; a default flow carries no condition (`DEFAULT_FLOW_HAS_CONDITION` at import) |
| Missing condition | Every non-default outgoing flow of a diverging gateway needs a condition with text (`CONDITION_MISSING` at import) |
| Null | A comparison with a missing variable is null, which does not hold, so routing moves on to the next flow or the default |
| Type error | Text against a number, or money of two currencies, is `CONDITION_ERROR`, never a silent `false` |
| No path | No `true` condition and no default fails the step with `422 STEP_FAILED`, reason `NO_PATH`. No instance, token, task, history or idempotency row is kept, so the caller can correct the data and retry with the same key |
| Audit | `pre_history` records `NODE_COMPLETED` on the gateway with the flow taken |

Example: with `amount >= 100000000 VND` to `senior-approval` and the default to `auto-approve`, 150,000,000 VND activates only `senior-approval` and 99,999,999 VND only `auto-approve`. The cases run with:

```
mvn -B -pl process-engine/process-engine-test,process-engine/process-engine-postgres -am verify \
  -Dtest='ExclusiveGatewayTest' -Dit.test='ExclusiveGatewayPostgresIT' -Dsurefire.failIfNoSpecifiedTests=false
```

## Timers and jobs with leases

`pre_timer` holds timers now, and SLA escalations, async step attempts and effective-date jobs later. Each replica runs one poller thread (`horus.bpm.processes.jobs.*`). The claim is one short transaction:

```sql
WITH due AS (
  SELECT tenant_id, id FROM pre_timer
  WHERE status IN ('DUE', 'CLAIMED') AND next_fire_at <= :now
    AND (status = 'DUE' OR locked_until <= :now)
  ORDER BY next_fire_at, id LIMIT :batchSize FOR UPDATE SKIP LOCKED)
UPDATE pre_timer t SET status = 'CLAIMED', locked_by = :replicaId,
       locked_until = :now + t.lease_duration, attempt = t.attempt + 1
FROM due WHERE t.tenant_id = due.tenant_id AND t.id = due.id RETURNING t.*;
```

Each claimed job then fires in its own transaction: the instance row is locked, then the job row, and the timer fires only if the job is still `CLAIMED` by this replica with the same attempt number. The job becomes `DONE` in the same transaction as the steps it ran.

| Property | Value |
|---|---|
| Lease length | `jobs.timer-lease`, 60 s; a crashed replica's timers are claimed by another replica after that (`PRE-006`) |
| Duplicate protection | `SKIP LOCKED` on the claim; the fire and `DONE` commit together; the attempt number fences an old claim, so a replica that comes back after its lease was taken over fires nothing |
| Failed firing | Rolled back; due again after `jobs.retry-backoff` times the attempt; `FAILED` after `jobs.max-attempts` (3) |
| Poll cadence | `jobs.poll-interval`, 1 s when idle; at once again while polls find due jobs; `jobs.batch-size` 32 |
| Decision after a timer | No one is signed in when a timer fires. A `decision` step that follows runs in the tenant of the stored instance row, as `system:process-engine`, never in the tenant the poller thread's security context resolves; a rule set that exists only in another tenant fails the step closed and the firing rolls back, with any decision row already written (`PRE-055`, `PRE-056`). The Rule Engine's entry point for this is handed only to the Process Engine; host code cannot reach it |
| Timer events in this release | Intermediate timer catch event with `timeDuration` (for example `P3D`) or `timeDate` (for example `2027-04-01T00:00:00+07:00`); cycles and boundary timers follow |

## `parallelGateway` join without lost wakeups

![Parallel join](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/parallel-join.svg)

When two branches finish on two replicas at the same instant, each transaction locks the parent `pre_instance` row `FOR UPDATE` before it marks its token `JOINED` and counts the remaining tokens under the same scope token (the instance, or the enclosing `saga` block). The lock serialises the two counts, so exactly one transaction sees zero remaining and creates the single outgoing token (`PRE-005`, `TR-ASY-03`). The lock is per instance and held for about 2 ms, so it never contends across instances.

| Rule | Behaviour |
|---|---|
| Fork | A parallel gateway with one incoming flow sends a token down every outgoing flow, one `FLOW` token per flow, all under the scope token of the arriving token |
| Join | With several incoming flows, each arriving token becomes `JOINED`. The arrival that brings the `JOINED` tokens on the gateway, under the same scope token, to the number of incoming flows marks them `COMPLETED` and creates one new token that leaves the gateway (and forks again if the gateway has several outgoing flows) |
| Scope | Tokens of another scope token are never counted (`PRE-044`), so two instances, or a `saga` block and its parent, never complete each other's join |
| Conditions | A condition on an outgoing flow of a parallel gateway is refused at import (`CONDITION_ON_PARALLEL_FLOW`); use an exclusive gateway to choose |
| Audit | `pre_history` records `TOKEN_JOINED` per arrival with the count, and `NODE_COMPLETED` once, by the identity whose completion fired the join |
| Modelling | The join counts arrivals, as BPMN engines usually do. If a forked branch ends at an end event instead of reaching the join, the join never fires and the instance ends with that end event; model every forked branch back into the join |

The race is proven with two engine instances on two connection pools against one PostgreSQL: the two checks of a loan application are completed at the same moment, one on each, 1,000 times, and every run ends with exactly one token at `underwrite`. Removing the row lock makes the first run fail. The test runs with:

```
mvn -B -pl process-engine/process-engine-test,process-engine/process-engine-postgres -am verify \
  -Dtest='ParallelGatewayTest' -Dit.test='ParallelJoinPostgresIT' -Dsurefire.failIfNoSpecifiedTests=false
```

## Idempotency and business keys

![Idempotent start and complete](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/idempotent-start-complete.svg)

```
POST /api/bpm/processes/v1/instances
Idempotency-Key: 01JD2X5K9T1Q4N8V2M7B3C6D0E
{ "definition": "loan-origination", "businessKey": "LN-2027-000123", "variables": { } }
201 Created, or 200 OK with the existing instance when the business key already exists for this tenant
```

| Situation | Response |
|---|---|
| Same key, same payload hash, finished | Original status and body, no second effect |
| Same key, different payload | `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` |
| Same key, first request still running | `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1` |
| Task completion with a stale `version` (`If-Match` or body field) | `409 VERSION_CONFLICT` |

Java API methods take the same keys as parameters (ADR-0006). The idempotency record lives in `pre_idempotency` for 24 hours by default.

## Compensation

![Compensation flow](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/compensation-flow.svg)

Since 0.3.0 (#67) a `saga` block is a BPMN `transaction`, and each step names its undo and its failure action:

```xml
<bpmn:transaction id="transfer" name="Move the money">
  <bpmn:startEvent id="transfer-start"/>
  <!-- local: release-hold is a ServiceTaskHandler, run in its own transaction -->
  <bpmn:serviceTask id="hold-funds" horus:handler="place-hold" horus:compensate="release-hold"/>
  <!-- remote: credit-back is a ServiceAdapter, called with no transaction open -->
  <bpmn:serviceTask id="debit-source" horus:handler="core-banking" horus:mode="remote"
                    horus:compensate="credit-back"/>
  <!-- when this step fails for good, the completed steps of the block are undone newest first -->
  <bpmn:serviceTask id="credit-target" horus:handler="core-banking" horus:mode="remote"
                    horus:onFailure="compensate"/>
  <bpmn:endEvent id="transfer-end"/>
  <!-- sequence flows inside the block -->
</bpmn:transaction>
```

How it runs:

- Each entered block gets its own scope token. A completed step with `horus:compensate` pushes one entry onto the stack of that scope token, and a failure inside the block undoes only the entries of that block, newest first (ADR-0020, `PRE-044`). Steps outside any block are undone under the instance's root scope.
- A step with `horus:onFailure="compensate"` starts the compensation when it fails for good: a business error, a failure that is not retryable, or its last attempt. Without it, the failure opens an incident as before. A `throwCompensation` (an `intermediateThrowEvent` with a `compensateEventDefinition`) inside a block starts it explicitly.
- The instance is `COMPENSATING` while the walk runs and ends `FAILED`. A `COMPENSATING` instance cannot be cancelled (`409 INVALID_STATE`): an undo is never abandoned half way.
- Each undo is its own `COMPENSATION` job, keyed by its own job id on every retry, with the `runners.compensation.*` retry policy. A remote undo receives `forwardJobId` (the key the forward call was posted under) and `forwardAttemptId` on `AdapterCall`, so the remote side can find and reverse the original. A local undo receives `forwardAttemptId` on `ServiceCall`. Both receive the forward inputs and, under `forwardResult`, the forward result.
- An undo that fails for good opens `COMPENSATION_FAILED` and pauses the walk; `horus:compensate="manual"` opens `COMPENSATION_MANUAL_REQUIRED`. An operator retries the first, which runs the same job again, or resolves either with a second person's approval, which marks the undo done by hand. The walk then goes on.
- Every step of the walk is a history row: `COMPENSATION_STARTED`, `COMPENSATION_STEP_SCHEDULED`, `COMPENSATION_STEP_COMPLETED`, `COMPENSATION_STEP_FAILED`, `COMPENSATION_STEP_MANUAL` and `COMPENSATED` (ADR-0007).

## Business calendar for SLA
```xml
<bpmn:userTask id="credit-review" horus:assignee="role:credit-officer" horus:resultVariable="review"
               horus:sla="PT4H" horus:calendar="vn-banking-hours"/>
```
```java
public interface BusinessCalendar {
  Instant add(String tenantId, String calendarKey, Instant start, Duration workingTime);
  boolean isBusinessTime(String tenantId, String calendarKey, Instant at);
}
```
The default implementation reads weekly hours, holidays and time zone from `pre_calendar` and `pre_holiday`. A calendar is created or changed by a proposal that a second holder of `processes:calendar:manage` approves (four eyes by default; one person only in the `dev` profile). A task without `horus:calendar` counts elapsed time. A computed `due_at` does not move when the calendar changes later (`TR-CAL-02`). An overdue task escalates to the next level of its `horus:escalation` on the same calendar (0.3.0). See [[Process Engine API]] for an example.

## Instance migration
![Instance migration](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/instance-migration.svg)

```
POST /api/bpm/processes/v1/migrations                 # create plan: source v1 to target v2, step mapping
POST /api/bpm/processes/v1/migrations/{id}:validate   # dry run: instances per step, unmapped steps, conflicts
POST /api/bpm/processes/v1/migrations/{id}:execute    # batches of 100; each instance locked, tokens remapped, pinned to v2, history row written
```
Steps with the same id map implicitly; renamed steps need an explicit mapping; removed steps must be mapped or the affected instances cancelled. Instances with an open incident can be migrated. A failure on one instance raises an incident on that instance; the batch continues (`PRE-014`, `PRE-015`).

## The `agentTask` step

![Guardrail gate: two short transactions around the agent call](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/process-engine-guardrail-gate.svg)

![Agent task guardrail flow](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/agent-task-flow.svg)

```yaml
- id: assess-documents
  type: agentTask
  agent: kyc-document-reviewer           # adapter registered in the host: HTTP, MCP tool or in-process bean
  inputs: { documents: "${instance.documents}", customerType: "${instance.customerType}" }
  outputSchema: schemas/document-assessment.json
  guardrails:
    decision: agent-output-guardrails    # Rule Engine rule set evaluated on the agent's output
    onViolation: route-to-human          # or fail
  timeout: PT5M
  retry: { max: 2, backoff: PT30S }
```

| Stage | Where | Outcome |
|---|---|---|
| Resolve bounded inputs, mask fields flagged `mask: true` | TX1 | Inputs stored hashed in `pre_history` (`TR-AGT-02`) |
| Claim lease, commit | TX1 | Token `WAITING_ASYNC` |
| Call adapter under `timeout` | Outside any transaction | Raw output and latency, or timeout |
| Validate output against `outputSchema` | TX2 | Failure counts as an attempt (`AGENT_SCHEMA_INVALID`) |
| `RuleEngine.evaluate(guardrails.decision, output)` | TX2, in-process | `allow`: advance. Violation with `route-to-human`: approval `userTask` carrying inputs, output and matched rule ids; the identity that triggered the step cannot approve it. Violation with `fail`: incident or compensation (`TR-AGT-01`) |
| Retries exhausted | TX2 | `pre_incident` with `AGENT_TIMEOUT` or `AGENT_SCHEMA_INVALID` and `retry_count` (`PRE-011`) |
| Human edits or rejects on the approval task | Task completion | `human_override` diff recorded in history for offline evaluation (`PRE-012`) |

The engine never embeds a model and never calls the network itself (`TR-AGT-03`, `TR-EMB-05`). An agent has the same obligations as a service: bounded inputs, a schema for outputs, a timeout, an audit record.

## Permissions (preview)

| Permission | Grants |
|---|---|
| `processes:read` | Read definitions, instances, tokens, history |
| `processes:deploy` | Import a complete definition as a draft |
| `processes:author` | Create and edit drafts, request approval |
| `processes:approve` | Approve, reject and retire versions; a retirement needs a reason, and the last version in effect needs a confirmation (PD-101, PD-102) |
| `processes:start` | Start instances |
| `processes:cancel` | Cancel a running instance, with a reason (PD-103) |
| `processes:task:work` | Claim, unclaim and complete tasks in the caller's own queues |
| `processes:task:manage` | List every task of the tenant, and reassign an open task or delegate a claimed one to a named user, with a reason (0.3.0, PD-110) |
| `processes:incident:manage` | Retry an incident, propose its resolution, and approve or reject another person's proposal (0.3.0, PD-126) |
| `processes:migration:manage` | Create, validate and execute migration plans |
| `processes:calendar:manage` | Propose, approve and reject changes of business calendars and holidays; a proposer never approves their own |

Four-eyes is enforced in `TaskService`: a task with `fourEyes: true` refuses the identity that started the instance and every identity that completed an earlier task of it, at claim and again at completion, with `403 SELF_APPROVAL_FORBIDDEN`, whatever the caller's permissions (`PRE-007`). The check at completion catches a claimant who completed a parallel task after claiming. See [[Multi Tenancy and Security]].
