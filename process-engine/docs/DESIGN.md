# Process Engine: Design

Status: architecture baseline (M0). Conforms to `ADR-0001` through `ADR-0007`, the proposed `ADR-0008` and `ADR-0009`, and `wiki/Technical-Requirements.md`. Money values follow the canonical contract in `rule-engine/docs/DESIGN.md` section 3.4.

## 1. Context, Boundaries & Explicit Non-Goals

`horus-process-engine` is an embeddable workflow library for Java 21 / Spring Boot 3.4+ hosts using the host's PostgreSQL 14+ database (`pre_*`), where humans (`userTask`), host services (`serviceTask`), deterministic decisions (`decision` via Rule Engine SPI), and AI agents (`agentTask`) collaborate under explicit transactional, concurrency, and compensation guarantees.

- **Rule Engine independence:** `process-engine-core` depends ONLY on the `RuleEngine` API interface (optional bean if no `decision` or `agentTask` guardrail step exists). Rule Engine never depends on Process Engine (`ADR-0003`).
- **Explicit non-goals:** No standalone Zeebe-like server (`ADR-0004`), no Elasticsearch exporter cluster, no XA/2PC distributed transactions (`ADR-0007`), no full BPMN 2.0 / CMMN symbol coverage.

## 2. Module Decomposition & Dependency DAG

```text
process-engine-core                (Java 21 + Jackson + optional RuleEngine SPI; zero Spring/SQL/net)
  ▲
  ├── process-engine-postgres      (Spring JDBC JdbcClient, Flyway pre_*, SKIP LOCKED poller, row-locked joins)
  ├── process-engine-rest          (Spring WebMvc /api/bpm/processes/v1, RFC 9457, OpenAPI)
  ├── process-engine-test          (In-memory ports, fixed Clock, stub AgentAdapter, JUnit 5 extension)
  ▲
process-engine-spring-boot-starter (@AutoConfiguration, scheduler lifecycle, Micrometer, OTel hooks)
```

## 3. Domain Model, Execution Token Tree & Transaction Split

### 3.1 Sync In-Transaction vs Async Wait-State Protocol
To guarantee zero lost state on local DB commits AND zero HikariCP pool exhaustion during slow LLM/MCP/HTTP calls:

```text
[Client/Timer]
   │
   ▼  TX 1 (Short ACID TX in host DataSource)
[Advance Token] ──► Sync Step (decision / exclusiveGateway / local bean)?
   │                   └── Run inline in TX 1, commit business + engine state together
   │
   └──► Async Wait-State (agentTask / remote serviceTask)?
           └── Claim pre_timer job (status=CLAIMED, locked_until=now()+timeout+lease-margin), COMMIT TX 1!
                  │
                  ▼  OUTSIDE ANY DB TRANSACTION OR ROW LOCK
           [Invoke AgentAdapter / ServiceAdapter, cancelled at the step timeout]
                  │
                  ▼  TX 2 (Fresh ACID TX)
           [Verify lease/version] ─► [Validate JSON Schema] ─► [RuleEngine.evaluate(guardrails.decision)]
                  ├── allow == true                     ──► Complete step, advance token
                  ├── allow == false, route-to-human    ──► Create maker-checker userTask with output, reasons + matched rules
                  └── fail / timeout ──► Retry with backoff or open pre_incident / run Saga compensate
```

### 3.2 Process Instance & Execution Token State Machine
Processes fork via `parallelGateway`; therefore active positions live in **`pre_token`** rows rather than a single column on `pre_instance`:
- `pre_instance.status`: `RUNNING | COMPLETED | COMPENSATING | CANCELLED | FAILED`
- `pre_token.status`: `ACTIVE | WAITING_USER | WAITING_ASYNC | WAITING_TIMER | JOINED | COMPLETED | COMPENSATED`
- **Events** (`<module>.<aggregate>.<pastTense>.v<N>`, module `processes`): `processes.definition.deployed.v1`, `processes.instance.started.v1`, `processes.step.completed.v1`, `processes.task.created.v1`, `processes.task.completed.v1`, `processes.incident.raised.v1`, `processes.incident.resolved.v1`, `processes.compensation.started.v1`, `processes.instance.failed.v1`, `processes.instance.completed.v1`, `processes.instance.cancelled.v1`. Each is handed to the host event publisher inside the transaction of the state change it reports. A breaking payload change publishes a `.v2` type alongside `.v1`.
- **Concurrency rule for `parallelGateway` join:** When a branch arrives at a converging `parallelGateway`, the engine locks `SELECT ... FROM pre_instance WHERE tenant_id = ? AND id = ? FOR UPDATE`, transitions the arriving token to `JOINED`, checks if all incoming sequence branches in that `scope_id` are `JOINED`, and only then emits the post-join `ACTIVE` token (`TR-PERF-05`).

### 3.3 `agentTask` Execution & Guardrail Pipeline (`TR-AGT-01..03`)
1. **Bounded Inputs:** Resolve `${instance.<path>}` references (syntax defined in `process-engine/api/schema/process-definition.v1.schema.json`, `$defs/variableRef`) into an explicit input DTO; apply PII masking flags before audit logging.
2. **Adapter SPI (Outside TX):** Call `AgentAdapter.execute(AgentInvocation)` (in-process bean, HTTP, or M3 MCP tool) under `timeout` (`PT5M` etc.). Engine never embeds LLM SDKs (`TR-AGT-03`).
3. **Output Schema Gate (In TX 2):** Validate adapter JSON payload against declared `outputSchema`. Schema violation → retry up to `retry.max`, then `pre_incident` (or `onViolation` policy).
4. **Deterministic Guardrail Gate (In TX 2):** Call `RuleEngine.evaluate(guardrails.decision, agentOutput)`.
   - **Guardrail contract:** the rule set must have hit policy `first` or `unique` and declare the output `allow: boolean`, and may declare `reasons: list<string>`; no other output is read. `POST /definitions` checks the rule set's effective version against this contract at deploy time and rejects the definition with `422 GUARDRAIL_CONTRACT_INVALID` if the rule set is missing or does not comply. At run time the engine fails closed: a decision error (`UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR`) or a later version that no longer complies counts as `allow == false` with reason `GUARDRAIL_EVALUATION_ERROR` and follows `onViolation`.
   - `allow == true` → advance process, record `guardrailDecisionId` in `pre_history`.
   - `allow == false` + `onViolation: route-to-human` → create a `userTask` (maker-checker) carrying inputs, agent output, `reasons`, and matched guardrail rule IDs (`TR-AGT-01`).
   - `allow == false` + `onViolation: fail` → open `pre_incident` (or trigger `compensate`).
5. **Eval Feedback Loop:** Whenever a human edits or overrides an `agentTask` recommendation on the escalated `userTask`, persist `human_override` metadata in `pre_history` for offline/CI Eval replay.

### 3.4 Saga Reverse Compensation (`ADR-0007`, `TR-COMP-01..03`)
- Whenever a `serviceTask` or `agentTask` with a declared `compensate` handler completes inside a process or `saga` scope, push `(step_id, attempt_id, compensate_handler, input_snapshot)` onto `pre_compensation_stack`.
- On unrecoverable step failure (`onFailure: compensate`) or `throwCompensation`, iterate the stack in **strict LIFO reverse order**, invoking each idempotent compensate handler with `(instanceId, stepId, attemptId)` (`TR-IDEM-04`).
- `compensate: manual` raises an immediate `pre_incident` (`COMPENSATION_MANUAL_REQUIRED`) rather than silently skipping (`TR-COMP-03`).

### 3.5 Business Calendar SPI & Batched Instance Migration
- `BusinessCalendar` (`add`, `isBusinessTime`) computes `userTask` SLA `due_at` using tenant-configured working hours, time zones, and holidays (`pre_calendar`, `pre_holiday`) (`TR-CAL-01/02`).
- `MigrationService` validates `(sourceVersion -> targetVersion)` as a read-only dry run (`:validate`), then executes in batches of 100 instances (`:execute`), locking one `pre_instance` at a time (`TR-MIG-01/02`). M2 maps steps with the same id implicitly and reports every instance on a step that has no same-id target. Explicit `stepMappings` for renamed steps and cancellation of instances on removed steps are M3 (`TR-MIG-03`).

## 4. Public Java SPI & REST Contract Summary (`/api/bpm/processes/v1`)

| Endpoint | Purpose | Idempotency / Concurrency |
|---|---|---|
| `POST /definitions` | Deploy JSON/YAML versioned definition; validated against `process-definition.v1.schema.json` and the guardrail contract (section 3.3) | `Idempotency-Key`; `422 VALIDATION_FAILED`, `422 GUARDRAIL_CONTRACT_INVALID` |
| `POST /instances` | Start instance (`businessKey` unique per tenant+def) | `Idempotency-Key` + `business_key` upsert (`ADR-0006`) |
| `GET /instances/{id}`, `GET /instances` | Inspect state, tokens, variables, history | Cursor pagination |
| `POST /tasks/{id}:claim`, `:complete`, `:delegate`, `:reassign` | Human task lifecycle & four-eyes check | `Idempotency-Key` + `If-Match: "<lockVersion>"` (`409 VERSION_CONFLICT`) |
| `POST /incidents/{id}:retry`, `:resolve` | Retry failed service/agent/comp step or mark resolved | `Idempotency-Key` + `If-Match: "<lockVersion>"` |
| `POST /migrations`, `/{id}:validate`, `/{id}:execute` | Plan, dry-run, and execute instance version migration | `Idempotency-Key` |

Optimistic locking uses `lockVersion` (column `lock_version`), sent as `If-Match: "<lockVersion>"` or a `lockVersion` body field and returned as `ETag`; `version` always means the definition version number. Every endpoint that accepts `Idempotency-Key` returns `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` when the key was used with a different request hash and `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1` while the first request is still running (`ADR-0006`).

## 5. Storage Model (`process-engine-postgres`)

All tables place `tenant_id` first in indexes (`TR-TEN-01`), use ULID IDs, and `TIMESTAMPTZ`.

```sql
CREATE TABLE pre_definition (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  key_name        TEXT NOT NULL,
  version_num     INT NOT NULL,
  status          TEXT NOT NULL, -- ACTIVE | RETIRED
  definition_json JSONB NOT NULL,
  definition_hash CHAR(64) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  UNIQUE (tenant_id, key_name, version_num)
);

CREATE TABLE pre_instance (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  definition_id   VARCHAR(26) NOT NULL REFERENCES pre_definition(id),
  definition_key  TEXT NOT NULL,
  definition_ver  INT NOT NULL,
  business_key    TEXT,
  status          TEXT NOT NULL, -- RUNNING | COMPLETED | COMPENSATING | CANCELLED | FAILED
  variables       JSONB NOT NULL,
  lock_version    BIGINT NOT NULL DEFAULT 0,
  started_by      TEXT,
  started_at      TIMESTAMPTZ NOT NULL,
  ended_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_pre_instance_business_key
  ON pre_instance (tenant_id, definition_key, business_key) WHERE business_key IS NOT NULL;
CREATE INDEX idx_pre_instance_status
  ON pre_instance (tenant_id, status, started_at DESC);

CREATE TABLE pre_token (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  instance_id     VARCHAR(26) NOT NULL REFERENCES pre_instance(id),
  parent_token_id VARCHAR(26),
  scope_id        TEXT NOT NULL,
  current_node_id TEXT NOT NULL,
  status          TEXT NOT NULL, -- ACTIVE | WAITING_USER | WAITING_ASYNC | WAITING_TIMER | JOINED | COMPLETED | COMPENSATED
  created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_pre_token_instance ON pre_token (tenant_id, instance_id, status);

CREATE TABLE pre_task (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  instance_id     VARCHAR(26) NOT NULL REFERENCES pre_instance(id),
  token_id        VARCHAR(26) NOT NULL REFERENCES pre_token(id),
  node_id         TEXT NOT NULL,
  task_kind       TEXT NOT NULL, -- USER_TASK | APPROVAL_TASK
  assignee_user   TEXT,
  assignee_role   TEXT,
  assignee_group  TEXT,
  forbidden_users JSONB NOT NULL DEFAULT '[]', -- enforces four-eyes / maker-checker
  context_payload JSONB NOT NULL,
  status          TEXT NOT NULL, -- OPEN | CLAIMED | COMPLETED | CANCELLED
  due_at          TIMESTAMPTZ,
  lock_version    BIGINT NOT NULL DEFAULT 0,
  completed_by    TEXT,
  created_at      TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ
);
CREATE INDEX idx_pre_task_queue
  ON pre_task (tenant_id, status, assignee_role, assignee_user, due_at);

CREATE TABLE pre_timer (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  instance_id     VARCHAR(26) NOT NULL REFERENCES pre_instance(id),
  token_id        VARCHAR(26),
  job_kind        TEXT NOT NULL, -- TIMER | ASYNC_AGENT | ASYNC_SERVICE | SLA_ESCALATION
  node_id         TEXT NOT NULL,
  status          TEXT NOT NULL, -- DUE | CLAIMED | DONE | FAILED
  next_fire_at    TIMESTAMPTZ NOT NULL,
  locked_by       TEXT,
  locked_until    TIMESTAMPTZ,
  attempt         INT NOT NULL DEFAULT 0,
  max_attempts    INT NOT NULL DEFAULT 3,
  payload         JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_pre_timer_poll
  ON pre_timer (status, next_fire_at, locked_until) WHERE status IN ('DUE', 'CLAIMED');

CREATE TABLE pre_incident (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  instance_id     VARCHAR(26) NOT NULL REFERENCES pre_instance(id),
  token_id        VARCHAR(26),
  node_id         TEXT NOT NULL,
  error_code      TEXT NOT NULL,
  cause_summary   TEXT NOT NULL,
  retry_count     INT NOT NULL DEFAULT 0,
  status          TEXT NOT NULL, -- OPEN | RESOLVED
  lock_version    BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL,
  resolved_at     TIMESTAMPTZ
);
CREATE INDEX idx_pre_incident_open ON pre_incident (tenant_id, status, created_at DESC);

CREATE TABLE pre_history (
  id              VARCHAR(26) NOT NULL,
  tenant_id       TEXT NOT NULL,
  instance_id     VARCHAR(26) NOT NULL,
  event_type      TEXT NOT NULL,
  node_id         TEXT,
  actor_id        TEXT,
  attempt_id      VARCHAR(26),
  details         JSONB NOT NULL, -- agent inputs/outputs (masked), decisionId, override diff, migration note
  occurred_at     TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, occurred_at, id)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX idx_pre_history_instance ON pre_history (tenant_id, instance_id, occurred_at);

CREATE TABLE pre_idempotency (
  tenant_id       TEXT NOT NULL,
  operation       TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_hash    CHAR(64) NOT NULL,
  status_code     INT NOT NULL,
  response_body   JSONB NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, operation, idempotency_key)
);

CREATE TABLE pre_calendar (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  calendar_key    TEXT NOT NULL,
  timezone        TEXT NOT NULL,
  weekly_hours    JSONB NOT NULL,
  UNIQUE (tenant_id, calendar_key)
);

CREATE TABLE pre_holiday (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  calendar_id     VARCHAR(26) NOT NULL REFERENCES pre_calendar(id),
  holiday_date    DATE NOT NULL,
  description     TEXT,
  UNIQUE (tenant_id, calendar_id, holiday_date)
);
```
*(Plus `pre_compensation_stack` storing completed compensable steps per `instance_id` / `scope_id`.)*

## 6. Runtime Mechanics & 10x Capacity Math

- **Timer & Async Job Poller (`TR-PERF-04`, `TR-PERF-05`):** Each host replica runs a single scheduler thread polling `pre_timer` in batches (e.g. 50 rows) using `SELECT ... FOR UPDATE SKIP LOCKED`. Lease length (`locked_until`, always computed with the database `now()`) depends on the job kind (`ADR-0008`):
  - `ASYNC_AGENT`, `ASYNC_SERVICE` and remote compensation: step `timeout` plus `jobs.lease-margin` (default `30 s`). A `PT5M` agent step holds a 5 min 30 s lease; the worker cancels the adapter call at 5 min, so a live attempt always finishes or fails before its lease ends and is never taken over. The margin covers TX2 (about 5 ms), a GC pause and scheduling delay. Steps without a declared timeout use `adapters.default-timeout` (default `PT30S`), giving a 60 s lease.
  - `TIMER` and `SLA_ESCALATION`: fixed `jobs.timer-lease` (default `60 s`); they run no external call (`PRE-006`).
  - Cost: a crashed replica's async job is reclaimed after `timeout + 30 s` rather than a fixed 60 s. For a `PT5M` step recovery takes 5 min 30 s instead of 60 s, which is acceptable because the same step legitimately takes up to 5 min.
- **Connection Pool Protection:** Because `agentTask` and remote `serviceTask` release their claim TX *before* calling external HTTP/MCP/LLM endpoints, **100 concurrent 10-second LLM agent calls consume 0 DB connections while waiting**, keeping a 20-connection HikariCP pool below 10% utilization.
- **Throughput Target (`TR-PERF-04`):** Batching sync transitions + single `INSERT` history writes limits per-step DB time to ~1 ms, sustaining >1,000 transitions/sec on a 4-vCPU PostgreSQL instance.

## 7. Open Questions
- **Max compensation stack depth & nested `callProcess` propagation:** Confirm whether parent process failure triggers child process compensation automatically or via explicit `saga` handler (Owner: maintainers, Gate: M2 start).
