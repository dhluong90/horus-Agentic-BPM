# Process Engine API

Reference for the Process Engine's two surfaces: the in-process Java API and the REST API under `/api/bpm/processes/v1`. The contract file is [`process-engine/api/openapi.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/process-engine/api/openapi.yaml).

**Preview.** The Process Engine ships as a walking skeleton (0.1.0) with the read, cancel and task claim endpoints of 0.2.0. An imported definition is a `DRAFT` until a second person approves it (the definition lifecycle, 0.2.0). On `dev`, `horus.bpm.processes.definitions.auto-approve` activates imports without approval. Forms, delegate and reassign, incidents, asynchronous steps and masking of instance variables are not in this release.

**Added in 0.2.0.** The intermediate timer catch event (`timeDuration` or `timeDate`) and replica-safe timer jobs: a timer fires once across copies of the host, and a crashed copy's timers are taken over after the lease (see Process Engine Overview, Timers and jobs with leases).

## Endpoints

| Method and path | Java | Permission | Notes |
|---|---|---|---|
| `POST /definitions` | `DefinitionService.importDefinition(ImportRequest)` | `processes:deploy` | Body is a BPMN 2.0 document (`application/xml`), UTF-8 only, at most 1 MB. A document identical to the latest version returns that version, also with `201` |
| `GET /definitions` | `ProcessQueryService.definitions(PageRequest)` | `processes:read` | One item per key, ordered by key: `latestVersion`, `latestStatus`, `effectiveVersion` (the version a start uses now, or `null`), `lastChangedAt`. Since 0.2.0 |
| `GET /definitions/{key}/versions/{version}` | `DefinitionService.version(key, version)` | `processes:read` | Status, hashes, `approvedBy`, `approvedAt`, `effectiveFrom` and the lifecycle fields (`lockVersion`, `requestedBy`, `retiredBy`, `supersededBy`); no source document. `ETag` is the definition hash; the lifecycle commands take the `lockVersion` field as `If-Match`. This is the `Location` an import returns. `404 DEFINITION_NOT_FOUND`. Since 0.2.0 |
| `POST /definitions/{key}/versions/{v}:request-approval` | `requestApproval(ref, lockVersion, effectiveFrom, idempotencyKey)` | `processes:author` or `processes:deploy` | `DRAFT` to `PENDING_APPROVAL`; optional `effectiveFrom`, never in the past. `If-Match` required. 0.2.0 |
| `POST /definitions/{key}/versions/{v}:approve` | `approve(ref, lockVersion, comment, idempotencyKey)` | `processes:approve` | To `ACTIVE`, effective from `effectiveFrom` or now. The requester gets `403 SELF_APPROVAL_FORBIDDEN`; an older version than the active one gets `409 VERSION_SUPERSEDED`. 0.2.0 |
| `POST /definitions/{key}/versions/{v}:reject` | `reject(ref, lockVersion, reason, idempotencyKey)` | `processes:approve` | Back to `DRAFT`; `reason` required (`400` without). 0.2.0 |
| `POST /definitions/{key}/versions/{v}:retire` | `retire(ref, lockVersion, reason, confirmNoVersionInEffect, idempotencyKey)` | `processes:approve` (active), `processes:author` (draft) | To `RETIRED`; an active version needs a `reason`; the last version in effect needs `confirmNoVersionInEffect: true` (`409 LAST_VERSION_IN_EFFECT` otherwise). Only new starts stop; running instances keep their version. 0.2.0 |
| `GET /definitions/{key}/versions/{v}/history` | `history(ref)` | `processes:read` | Every transition with actor and comment, oldest first. 0.2.0 |
| `POST /instances` | `ProcessEngine.start(StartRequest)` | `processes:start` | Needs a `businessKey` or an `Idempotency-Key` (`428` without either). `201` for a new instance, `200` when one exists for the business key |
| `GET /instances/{id}` | `ProcessEngine.instance(id)` | `processes:read` | Execution tree (root scope token and flow tokens) and tasks; variables unmasked in 0.1.0 |
| `GET /instances` | `ProcessQueryService.instances(InstanceQuery)` | `processes:read` | `status` (comma separated, `RUNNING` when absent), `definitionKey`, `startedFrom` (inclusive) and `startedTo` (exclusive), at most 31 days apart; with one bound the other is 31 days away. Newest first. Items carry no variables, tokens or tasks. Since 0.2.0 |
| `POST /instances/{id}:cancel` | `ProcessEngine.cancel(id, lockVersion, reason, idempotencyKey)` | `processes:cancel` | Its own permission: `processes:start` does not grant it (PD-103), and `/api/bpm/me` lists it so a client shows Cancel only to its holders. `If-Match` (the instance `ETag`) and `Idempotency-Key` are required (`428`). Body `{"reason": "..."}`, required, at most `lifecycle.reason-max-length` (2,000) characters; the reason and the actor are on the `INSTANCE_ENDED` history entry and on `processes.instance.cancelled.v1`. Cancels every live token and open task; `409 INVALID_STATE` when the instance is not running. Cancel makes no adapter call and runs no compensation in 0.2.0; compensation on cancel is planned for 1.2. Since 0.2.0 |
| `GET /tasks/{id}` | `TaskService.task(id)` | `processes:task:work` | The task with its instance, step, assignment, `fourEyes`, claim and completion, and `allowedActions` for this caller (`claim`, `unclaim`, `complete`). `ETag` is the task `lockVersion`. Since 0.2.0 |
| `POST /tasks/{id}:claim` | `TaskService.claim(id, lockVersion, idempotencyKey)` | `processes:task:work` | `OPEN` to `CLAIMED` by the caller, who must be an assignee (`403 TASK_NOT_ASSIGNED`); a four-eyes task refuses the starter and earlier completers (`403 SELF_APPROVAL_FORBIDDEN`). `If-Match` and `Idempotency-Key` required (`428`). Since 0.2.0 |
| `POST /tasks/{id}:unclaim` | `TaskService.unclaim(id, lockVersion, idempotencyKey)` | `processes:task:work` | `CLAIMED` to `OPEN`; only the claimant (`403 TASK_NOT_ASSIGNED`). Same headers. Since 0.2.0 |
| `POST /tasks/{id}:complete` | `TaskService.complete(id, result, lockVersion, idempotencyKey)` | `processes:task:work` | Only the claimant completes: an `OPEN` task is `409 INVALID_STATE` (claim it first, since 0.2.0), a task claimed by someone else is `403 TASK_NOT_ASSIGNED`. `If-Match` (the task's `lockVersion`) and `Idempotency-Key` are required (`428`). A stale `If-Match` is `409 VERSION_CONFLICT` and writes nothing. A four-eyes task refuses the starter and earlier completers (`403 SELF_APPROVAL_FORBIDDEN`) |

The tenant always comes from the authenticated caller, never from a body; another tenant's definition, instance or task is answered as not found, and nothing is written. Bodies are read and written with the engine's own JSON mapper, so the host's `spring.jackson.*` settings (snake case, dates as timestamps) never change an engine payload: keys are camelCase and times ISO 8601 strings in UTC.

## Lists

Lists are keyset pages:

```json
{ "data": [ { "id": "01JD2X5K9T1Q4N8V2M7B3C6D0E", "definitionKey": "card-fee-review", "status": "RUNNING", "lockVersion": 1, "startedAt": "2027-02-01T09:00:00Z" } ], "nextCursor": "aW5zdGFuY2Vz...", "hasNext": true }
```

`limit` is 1 to `query.max-page-size` (200), 50 when absent. Pass `nextCursor` back unchanged as `cursor`, with the same filters. A cursor is signed for the tenant, the list and its filters, so an edited cursor, one issued to another tenant, or one sent with other filters is `400 QUERY_INVALID`. There is no total count.

| Setting | Default | Meaning |
|---|---|---|
| `horus.bpm.processes.query.max-page-size` | `200` | Most items per page; may be lowered, never raised |
| `horus.bpm.processes.query.max-range` | `P31D` | Longest start-time range of `GET /instances`; may be lowered, never raised |
| `horus.bpm.processes.lifecycle.reason-max-length` | `2000` | Longest cancellation reason; may be lowered, never raised |

The cursor key is one random 32-byte secret per database, in `pre_engine_key`, created at start-up after the migrations. The runtime database user needs `SELECT` and `INSERT` on it.

## Errors

Every endpoint answers errors as RFC 9457 problem details with a `code`:

| Status | Codes |
|---|---|
| 400 | `INPUT_INVALID`, `QUERY_INVALID` |
| 401 | `UNAUTHENTICATED` |
| 403 | `PERMISSION_DENIED`, `TASK_NOT_ASSIGNED`, `SELF_APPROVAL_FORBIDDEN`, `TENANT_NOT_ALLOWED` |
| 404 | `NO_EFFECTIVE_VERSION`, `DEFINITION_NOT_FOUND`, `INSTANCE_NOT_FOUND`, `TASK_NOT_FOUND` |
| 409 | `VERSION_CONFLICT`, `VERSION_SUPERSEDED`, `LAST_VERSION_IN_EFFECT`, `INVALID_STATE`, `IDEMPOTENCY_IN_FLIGHT` (with `Retry-After: 1`) |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `VALIDATION_FAILED`, `BPMN_UNSUPPORTED_ELEMENT`, `BPMN_UNSAFE_XML`, `DEFINITION_TOO_LARGE`, `IDEMPOTENCY_KEY_REUSE_MISMATCH`, `STEP_FAILED` |
| 428 | `PRECONDITION_REQUIRED` |
| 500 | `INTERNAL`, `DEFINITION_INTEGRITY_FAILED`, `EVENT_LISTENER_FAILED` (correlation id only) |

A request the framework refuses before the engine runs (an unreadable body, an unsupported media type) keeps its 4xx status with code `INPUT_INVALID`. `403 TENANT_NOT_ALLOWED` and `401 UNAUTHENTICATED` from the host contract carry the host's correlation id and, on a `401`, the `WWW-Authenticate` challenge. No body ever carries a stack trace, a class name or SQL.

`BPMN_UNSUPPORTED_ELEMENT` and `VALIDATION_FAILED` carry a `problems` list, each with element, id and line. `STEP_FAILED` carries `nodeId` and `reason` (for example `UNHANDLED_BUSINESS_ERROR`, `DECISION_NO_MATCH`, `STEP_LIMIT`); handler texts stay in the log.

## Definition lifecycle (0.2.0)

An import is a `DRAFT` and never the effective version. A person with `processes:author` or `processes:deploy` requests approval, optionally with `effectiveFrom`; a second person with `processes:approve` approves it. The approver must differ from every maker of the version: the importer and the requester (and every editor once draft editing exists); the shared host setting `horus.bpm.lifecycle.approver-must-differ-from: requester` relaxes this to the requester only, and is reported in `/api/bpm/meta` and on every approval. The requester can never approve, through Java or REST, and nothing is written when refused. The requester cannot reject their own request either (`403 SELF_APPROVAL_FORBIDDEN`); withdraw comes later. The approved version is the effective one from its `effectiveFrom`; at that time each older active version is retired once (`retiredBy: system:effective-date`, `supersededBy`), by a job that runs on whichever copy of the host claims it. Instances keep the version they started on. A reject needs a reason and returns the version to `DRAFT`. Each transition writes a history row and publishes `processes.definition.publish-requested.v1`, `approved.v1`, `rejected.v1` or `retired.v1` inside its transaction.

```mermaid
stateDiagram-v2
  direction LR
  [*] --> DRAFT: import
  DRAFT --> PENDING_APPROVAL: request-approval
  PENDING_APPROVAL --> DRAFT: reject with reason
  PENDING_APPROVAL --> ACTIVE: approve by another person
  ACTIVE --> RETIRED: newer version effective, or retire
  DRAFT --> RETIRED: withdraw
```

Retiring an active version needs `processes:approve` and a reason, in the `single` mode of the shared host setting `horus.bpm.lifecycle.retire-approval`; the history row and `retired.v1` record the mode. It stops new starts only; running instances keep their version. Retiring the last version in effect of a key follows `horus.bpm.lifecycle.retire-last-version`: `confirm` (default) needs `confirmNoVersionInEffect: true`, `refuse` never allows it. The `four-eyes` value of either setting is refused at start-up in this release.

Outside the `dev` profile the starter refuses `definitions.auto-approve: true` and `maker-checker.enabled: false`, and on every profile it refuses the removed key `definitions-auto-approve`.

## Task lifecycle

```mermaid
stateDiagram-v2
  direction LR
  [*] --> OPEN: token reaches the userTask
  OPEN --> CLAIMED: claim, If-Match
  CLAIMED --> OPEN: unclaim by the claimant
  CLAIMED --> COMPLETED: complete by the claimant, four eyes passes
  OPEN --> CANCELLED: instance cancelled
  CLAIMED --> CANCELLED: instance cancelled
  COMPLETED --> [*]
  CANCELLED --> [*]
```

Every change carries the task's `lockVersion` as `If-Match` and advances it by one: a claim of a task at `"0"` answers `ETag: "1"`, which the completion then sends. Two officers who read the same version and act at once get one `200` and one `409 VERSION_CONFLICT`, and only one transition and history entry is written. Read `GET /tasks/{id}` for the current `ETag` and the actions this caller may take.

## Transactions

A start or a completion runs every synchronous step, up to the next user task or the end, in the caller's transaction. That covers local service tasks, business rule tasks (one `rle_decision` row each) and gateway conditions. If any step fails, everything rolls back, the host's own writes included. Concurrent requests with one `Idempotency-Key` do the work once: the second waits for the first and replays its answer.

## Events and the listener contract

Every state change hands an event (`processes.instance.started.v1`, `processes.task.created.v1`, `processes.task.completed.v1`, `processes.instance.completed.v1`, `processes.instance.cancelled.v1`, `processes.task.claimed.v1`, `processes.task.unclaimed.v1`, `processes.definition.publish-requested.v1`, `processes.definition.approved.v1`, `processes.definition.rejected.v1`, `processes.definition.retired.v1`) to the host's `ProcessEventPublisher` **inside the transaction of the state change**. The default implementation is the Spring `ApplicationEventPublisher`. So the event and the state change commit or roll back together.

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant PE as Process Engine
    participant L as Plain listener
    participant DB as Host database
    participant N as AFTER_COMMIT listener
    C->>PE: start or complete
    PE->>DB: tokens, tasks, history
    PE->>L: event, inside the transaction
    L->>DB: outbox row, same transaction
    PE->>DB: commit
    DB-->>N: after commit only
    N->>N: notify, at most once
```

1. The caller starts an instance or completes a task.
2. The engine writes tokens, tasks and history in the caller's transaction.
3. The engine hands each event to listeners inside that transaction.
4. A durable consumer writes an **outbox row** in that transaction, and the host relays it after commit. This is the only way to use events for money movement, audit or integration between services.
5. The transaction commits.
6. A notification listener uses `@TransactionalEventListener(phase = AFTER_COMMIT)`.
7. It runs at most once, and never for work that rolled back.

A **plain listener** (`@EventListener`) runs inside the engine transaction. It must not do network I/O, and an exception it throws rolls the state change back; the caller gets `500 EVENT_LISTENER_FAILED`. Each publish call is timed, and a listener slower than `handlers.slow-call-warning` (200 ms) is logged at WARN as `process.event.listener.slow` with the event type, instance and duration, never the payload.

Every event carries an envelope `(eventId, type, tenantId, occurredAt, payload)`:

- **Deduplicate on `eventId`.** A host outbox relay delivers at least once. The id is derived from the instance and its sequence, the way the engine derives token ids, so the same transition always carries the same id.
- **Scope by `event.tenantId()`.** The publisher is application-wide and sees every tenant's events. A listener scopes every read and write, and every outbox row, by the envelope's tenant, never by the ambient security context.
- **`AFTER_COMMIT` writes use `REQUIRES_NEW`.** A `REQUIRED` write from an `AFTER_COMMIT` listener joins the finished transaction and is never committed.
- **No variables.** Payloads never carry instance variables or handler output. `businessKey` and the task assignee may be personal data, so a host outbox follows the retention and masking rules of the instance data.
- **Replays publish nothing.** An idempotent replay returns the stored answer without running the work, so no event is published twice. The sample host [`examples/rule-and-process`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/README.md) shows both kinds of listener: `ProcessEventOutbox` writes the host's `event_outbox` row and logs after commit.

## Conditions

Gateway conditions use `urn:horus-bpm:expr:v1` and follow the Rule Engine's rules. Null follows FEEL three-valued logic, only a true condition is taken (otherwise the default flow), numbers are decimals, and money compares only within one currency. A type error fails the step. A document whose `expressionLanguage` is another language is refused until that language is supported.
