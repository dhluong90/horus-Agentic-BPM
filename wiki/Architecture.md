# Architecture

This page explains how the engines are built, where state lives, how they behave under load and failure, and which checks prove it. Module depth lives in `rule-engine/docs/DESIGN.md`, `process-engine/docs/DESIGN.md` and `cockpit/docs/DESIGN.md`; this page links there instead of repeating them. Every choice below follows from the [[Principles]] and the ADRs in `docs/adr/`.

![System context](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/system-context.svg)

## 1. Context, boundaries and non-goals

| Aspect | Rule Engine | Process Engine | Cockpit |
|---|---|---|---|
| Inbound callers | Host Java code (`RuleEngine`), REST `/api/bpm/rules/v1`, cockpit | Host Java code (`ProcessEngine`), REST `/api/bpm/processes/v1`, cockpit, its own scheduler thread | Operators in a browser |
| Outbound dependencies | Host `DataSource` and transaction manager, host `SecurityContext`, host event publisher, `MeterRegistry` | Same, plus host-registered `AgentAdapter` and `ServiceAdapter` beans, `BusinessCalendar`, optional `RuleEngine` | The two REST APIs only |
| State it owns | Tables `rle_*` and the Flyway history table `rle_schema_history` | Tables `pre_*` and `pre_schema_history` | None (ADR-0005) |
| State it never touches | `pre_*`, host tables | `rle_*` (uses the `RuleEngine` API), host tables | Any database |
| Network I/O | None (`TR-EMB-05`) | None inside engine artifacts; adapters are host code and run outside transactions (`TR-ASY-01`) | HTTPS to the host |

### Security zones and integration

A bank runs the host service in its internal zone, behind its own gateway. The engines add no network listener, no server and no store of their own. Every call to core banking, partners or a model leaves through a host adapter, after the engine's first transaction has committed.

![Four-zone security and integration view](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/architecture-security-zones.svg)

| Step | Path | Rule |
|---|---|---|
| 1, 2 | Channel or cockpit to your gateway, then to the host | HTTPS; the host validates the bearer token itself |
| 3 | Business code or REST layer to the engines | In-process call, no network hop |
| 4 | Engines to PostgreSQL | The host's `DataSource` and transaction: business rows, `rle_*` and `pre_*` commit together (`TR-EMB-04`) |
| 5, 6 | Engines to host adapters, adapters to core banking, partners or a model | Only after TX1 commits, outside any transaction, under a timeout (`TR-ASY-01`) |
| 7 | Engines to the host event publisher | Inside the transaction; a host that forwards events writes them to its own outbox |

Non-goals, fixed by ADR: no standalone server (ADR-0004), no engine-owned database or connection pool (ADR-0002), no scripting engine or reflection in definitions (ADR-0003), no two-phase commit (ADR-0007), no message broker or search index requirement, no built-in identity provider, no full BPMN 2.0 or DMN FEEL.

## 2. Containers and modules

![Containers](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/c4-container.svg)

![Module structure](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/module-structure.svg)

| Artifact | Role | Allowed dependencies | Verified by |
|---|---|---|---|
| `*-core` | Model, parser and compiler, evaluator, token runtime, lifecycle, storage and log ports | Java 21, Jackson 3 inside the engine only (ADR-0021); Process Engine core also sees the `RuleEngine` interface | ArchUnit: no `org.springframework..`, `java.sql..`, `java.net..`, `javax.script..`, `java.lang.reflect.Method` (`TR-EMB-03`, `TR-SEC-01`) |
| `*-postgres` | Storage adapters on `JdbcClient`, Flyway migrations, `SKIP LOCKED` poller, `LISTEN/NOTIFY` listener | `*-core`, Spring JDBC, Flyway | ArchUnit: no HTTP clients (`TR-EMB-05`) |
| `*-rest` | Controllers, RFC 9457 problem responses, OpenAPI 3.1 document | `*-core`, Spring WebMvc | WebMvc contract tests |
| `*-spring-boot-starter` | `@AutoConfiguration`, host `DataSource` binding, isolated Flyway run, `TenantResolver`, Micrometer | `*-core`, `*-postgres`, optional `*-rest` | Starter boot test on a clean database (`TR-EMB-01`) |
| `*-test` | In-memory ports, fixed `Clock`, JUnit 5 extension, stub adapters | `*-core` | |
| `cockpit`, `cockpit-spring-boot-starter` | Static React build; the starter serves it under host authentication | REST contracts only | Playwright against an example host |
| `bpm-bom` | Pins every module version | | |

Dependency direction is inward to `*-core`. The Rule Engine never depends on the Process Engine. The Process Engine depends only on the `RuleEngine` interface and starts without a Rule Engine bean when no `decision` step or `agentTask` guardrail is used. The `*-core` and `*-rest` modules of both engines build in parallel; the starters build last.

## 3. Domain model and state machines

### 3.1 Aggregates and value objects

| Engine | Aggregates | Value objects and records |
|---|---|---|
| Rule Engine | `RuleSet` (per tenant), `RuleSetVersion` (immutable once published), `Decision` (append-only) | `Money(currency, minorUnits)`, `TenantId`, `DecisionInput`, `DecisionResult`, `Expr` (sealed AST), `CompiledRuleSet` |
| Process Engine | `ProcessDefinition` (versioned), `ProcessInstance`, `ExecutionToken`, `Task`, `Job` (timer or async step), `Incident` | `BusinessKey`, `IdempotencyKey`, `AttemptId`, `Lease(lockedBy, lockedUntil)`, `CompensationEntry` |

Money is never a floating-point number: `{ "currency": "VND", "amount": 60000000 }` in JSON, `BIGINT` minor units plus an ISO code in SQL. Identifiers are ULIDs.

### 3.2 Rule set version lifecycle

![Version lifecycle](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/version-lifecycle.svg)

| From | Event | Guard | To | Emitted event |
|---|---|---|---|---|
| none | `createDraft`, `import`, copy from `RETIRED` | Tenant resolved; schema valid | `DRAFT` | none |
| `DRAFT` | `updateDraft` | `lock_version` matches (`409 VERSION_CONFLICT` otherwise) | `DRAFT` | none |
| `DRAFT` | `requestPublish(effectiveFrom)` | Zero parse or type errors (`TR-DET-02`); caller has `rules:author` | `PENDING_APPROVAL` | `rules.version.publish-requested.v1` |
| `PENDING_APPROVAL` | `approve(comment)` | `approver != requestedBy` (`TR-SEC-04`); caller has `rules:approve`; `lock_version` matches | `PUBLISHED` | `rules.version.published.v1`; `cache_epoch + 1`; `pg_notify` |
| `PENDING_APPROVAL` | `reject(reason)` | caller has `rules:approve`; reason not blank; the requester may reject (withdraw) their own request | `DRAFT` | `rules.version.rejected.v1` |
| `PUBLISHED` | a newer version's `effectiveFrom` passes | Evaluation selects the version with the latest `effective_from <= clock` that was not retired at that clock, so the switch needs no wake-up; the approval itself, or a sweep every `lifecycle.sweep-interval` (1 minute), retires the superseded row with one conditional update, exactly once across replicas (ADR-0025) | `RETIRED` | `rules.version.retired.v1` with actor `system:supersession`, reason `SUPERSEDED` |
| `PUBLISHED` | `retire(reason)` | caller has `rules:approve`; reason not blank; older published versions are retired with it, so none comes back (ADR-0025) | `RETIRED` | `rules.version.retired.v1`; `cache_epoch + 1`; `pg_notify` |

A `RETIRED` version never changes state again; it stays selectable for clocks before its retirement and always replays. "Restore" copies its content into a new `DRAFT` with a new version number. Every transition also writes a row to the version's history (`GET .../versions/{v}/history`).

### 3.3 Process instance and token lifecycle

![Process instance states](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/process-instance-states.svg)

Positions in a process are rows in `pre_token`, not a column on the instance, because `parallelGateway` forks several active positions.

| From (instance / token) | Event | Guard | To | Emitted event |
|---|---|---|---|---|
| none | `start(definition, businessKey, idempotencyKey)` | Unique `(tenant_id, definition_key, business_key)`; idempotency key new, or same payload hash | `RUNNING`; token `ACTIVE` at `start` | `processes.instance.started.v1` |
| token `ACTIVE` | sync step done (`decision`, `exclusiveGateway`, local `serviceTask`) | Runs inside the current transaction | token `ACTIVE` at the next node | `processes.step.completed.v1` |
| token `ACTIVE` | reaches `userTask` | Assignee resolved; SLA `due_at` computed through `BusinessCalendar` | token `WAITING_USER`; `pre_task OPEN` | `processes.task.created.v1` |
| token `WAITING_USER` | `complete(taskId, version)` | `lock_version` matches; four-eyes: actor not in `forbidden_users` | token `ACTIVE` | `processes.task.completed.v1` |
| token `ACTIVE` | reaches `agentTask` or remote `serviceTask` | Job row created with lease; TX1 commits before the adapter call | token `WAITING_ASYNC` | none |
| token `WAITING_ASYNC` | adapter result, TX2 | Lease still held; output validates; guardrail `allow` | token `ACTIVE` | `processes.step.completed.v1` |
| token `WAITING_ASYNC` | guardrail violation, `route-to-human` | | token `WAITING_USER`; approval task created | `processes.task.created.v1` |
| token `WAITING_ASYNC` | failure, attempts remaining | `attempt < retry.max` | token `WAITING_ASYNC`; job `DUE` at `now + backoff` | none |
| token `WAITING_ASYNC` or `ACTIVE` | failure, attempts exhausted, `onFailure: incident` | | token paused; `pre_incident OPEN`; instance stays `RUNNING` | `processes.incident.raised.v1` |
| any paused token | `incident:retry` or `:resolve` | `lock_version` matches | token `ACTIVE` (retry) or as resolved | `processes.incident.resolved.v1` |
| token `ACTIVE` | reaches a `parallelGateway` join | `pre_instance` row locked `FOR UPDATE`; all sibling tokens under the same scope token `JOINED` | one new `ACTIVE` token; siblings `COMPLETED` | none |
| token `ACTIVE` | reaches `timer` | Timer row `DUE` | token `WAITING_TIMER` | none |
| `RUNNING` | failure with `onFailure: compensate`, or `throwCompensation` | | `COMPENSATING`; compensation stack walked LIFO | `processes.compensation.started.v1` |
| `COMPENSATING` | all handlers done, or the manual-compensation incident resolved | | `FAILED` (terminal, compensated) | `processes.instance.failed.v1` |
| `RUNNING` | last token reaches `end` | No other active or waiting token | `COMPLETED` | `processes.instance.completed.v1` |
| `RUNNING` | `cancel` | | `CANCELLED` | `processes.instance.cancelled.v1` |

## 4. Transaction model: sync steps and the Async Wait-State protocol

![Async wait-state protocol](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/async-wait-state.svg)

| Step kind | Runs in | Holds a DB connection during | Examples |
|---|---|---|---|
| Sync in-transaction | The caller's or the scheduler's current transaction | Milliseconds: the engine's own writes | `decision`, gateways, `userTask` state changes, a local `serviceTask` bean that writes only to the same database |
| Async wait-state | TX1 (claim with a 60 s lease, commit), then the adapter call with no connection, then TX2 (verify lease, record, advance or fail) | Never during the adapter call | `agentTask`, remote HTTP or MCP `serviceTask`, compensation handlers that call other systems |

TX2 re-reads the job `WHERE locked_by = me AND locked_until > now()`. If another replica took the lease after it expired, the late result is discarded and the other attempt owns the step. Adapters receive the job id as `idempotencyKey()`, the same on every retry, so hosts can make their side idempotent (`TR-IDEM-04`); the `attemptId` changes on every attempt and only fences late results. ADR-0008 records the decision. The idempotent start and complete calls:

![Idempotent start and complete](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/idempotent-start-complete.svg)

## 5. Storage

| Engine | Tables | Notes |
|---|---|---|
| Rule Engine | `rle_rule_set`, `rle_version`, `rle_rule`, `rle_decision`, `rle_decision_outbox`, `rle_idempotency` | `rle_decision` partitioned monthly by `created_at`; `rle_rule_set.cache_epoch` drives invalidation |
| Process Engine | `pre_definition`, `pre_definition_layout`, `pre_instance`, `pre_token`, `pre_task`, `pre_timer`, `pre_incident`, `pre_history`, `pre_node_stats_hourly`, `pre_engine_watermark`, `pre_compensation_stack`, `pre_idempotency`, `pre_calendar`, `pre_holiday` | `pre_history` partitioned monthly; `pre_timer` holds timers, SLA escalations and async job leases; `pre_definition_layout` keeps append-only diagram layout revisions apart from the hashed definition; `pre_token.definition_id` follows the instance through migration so live counts per node read one index; `pre_node_stats_hourly` is the hourly per-node rollup behind the heatmap, with `pre_engine_watermark` recording how far the rollup has read. Migration plan storage gets its table before M2 execution work |

Rules for every table: `tenant_id TEXT NOT NULL`, first in every unique constraint and index (`TR-TEN-01`); ULID `VARCHAR(26)` keys; `TIMESTAMPTZ` only; money as `BIGINT` minor units plus `CHAR(3)` currency; definitions stored as canonical JSONB whose SHA-256 is part of the version identity. Migrations are forward-only Flyway scripts in a history table per engine, applied expand-then-contract across one minor release (`TR-OPS-01`, `TR-OPS-02`); indexes on populated tables are created `CONCURRENTLY`. Full DDL: the module `DESIGN.md` files.

## 6. Contracts

| Layer | Audience | Stability rule |
|---|---|---|
| Java API and SPIs | Host developers | Semver per module; deprecations overlap one minor release |
| REST `/api/bpm/rules/v1`, `/api/bpm/processes/v1` | Cockpit, remote callers | Major version in the path; additive changes stay in `v1`; OpenAPI 3.1 per release; errors are RFC 9457 `application/problem+json` |
| Definition formats (YAML, JSON) | Authors, tooling | `schemaVersion` field; old schemas import forever |
| Events | Host audit and analytics | Envelope `eventId, eventType (suffix .v1), occurredAt, tenantId, correlationId, causationId, producer, schemaVersion, payload`; additive only; published inside the state-changing transaction through the host publisher |

See [[Rule Engine Decision API]] for the REST reference.

## 7. Trade-offs the ADRs leave open

| Choice | Option A | Option B | Default and why |
|---|---|---|---|
| Decision log write | `transactional`: one `INSERT` in the caller's transaction, zero orphans | `async`: insert into `rle_decision_outbox`, relayed by a `SKIP LOCKED` poller; lower latency on read paths, log can lag | `transactional`; switch per host for read-heavy paths above about 2 000 decisions/s per replica |
| Cache coherence transport | `LISTEN/NOTIFY`: sub-second, needs one session-mode connection per replica | Epoch poll only: works behind transaction-mode poolers; staleness up to the poll interval | Both: NOTIFY when available, epoch poll every 5 s as the bound (ADR-0009) |
| Remote step execution | Async Wait-State with lease (TX1, call, TX2) | Call inside the step transaction: simpler, but one slow call per connection saturates the pool | Async Wait-State for every network call (ADR-0008) |
| Compiled cache implementation | Bounded LRU map in `*-core`, no dependency | Caffeine in the starter (Apache 2.0, weight-based eviction, metrics) | Caffeine in the starter behind a `CompiledCache` port; `*-core` ships a small default |

The two decision log modes differ in what a host rollback does to the decision row:

![Decision log modes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/decision-log-modes.svg)

## 8. Scale and bottleneck math

Targets, not measurements. Assumptions: one host replica with a 20-connection pool, PostgreSQL on 4 vCPU, 1 ms round trip for a short write.

| Resource | Baseline load | Cost model | 10x load | Where it breaks and what to do |
|---|---|---|---|---|
| Pool, sync transitions | 1 000 transitions/s per replica (`TR-PERF-04`) | about 2 ms DB time each (lock token, update, one history insert) = 2 connections busy on average | 10 000/s = 20 connections busy | Pool saturates at 10x on one replica; run 3 replicas or batch history inserts per transaction |
| Pool, async steps | 100 concurrent agent calls of 10 s each | Held in-TX: 100 connections (a pool of 20 fails at 20 concurrent calls). Wait-State: TX1 + TX2 about 10 ms per step = 1 connection at 100 steps/s | 1 000 concurrent calls | 0 connections while waiting; the limit moves to adapter thread pools and the external service |
| Pool, decision log | 2 000 decisions/s per replica | 1.5 ms per insert = 3 connections | 20 000/s = 30 connections | Switch that path to `async` mode or add replicas; partitions keep inserts append-only |
| `pre_instance` row lock at a join | 2 to 5 branches per join | Lock held about 2 ms per arrival = at most 500 arrivals/s per instance | Same | Per-instance lock; no cross-instance contention |
| `pre_timer` claim | 1 scheduler thread per replica, 500 ms poll, batch 50 = 100 jobs/s per replica | `SKIP LOCKED` on the partial index `(status, next_fire_at, locked_until)` | 10 replicas = 1 000 jobs/s | Partial-index bloat under heavy churn: tune `autovacuum` per table; shorten the poll interval before adding threads |
| Compiled cache memory | 50 tenants x 20 rule sets x 2 active versions = 2 000 entries; about 1.5 KB per compiled row, 50 rows on average = 75 KB per entry = 150 MB | Bounded by `cache.max-entries` (default 1 000) with LRU eviction; a miss costs one compile (`TR-PERF-02`: under 500 ms for 1 000 rows) | 20 000 entries = 1.5 GB if unbounded | Eviction keeps the heap flat; measure the per-row footprint in the M1 heap test |
| Cache invalidation | 1 publish per minute | NOTIFY delivers in milliseconds; the epoch sweep is one indexed read per cached tenant per 5 s = 10 reads/s per replica at 50 tenants | 500 tenants x 10 replicas = 1 000 reads/s | Raise the interval, or sweep only tenants evaluated since the last sweep |
| Idempotency tables | One row per mutating call, 24 h retention | PK `(tenant_id, operation, idempotency_key)`; cleanup by the `expires_at` index in batches of 10 000 | Same | Cleanup runs on the scheduler thread; never a full-table delete |

Latency budget per hot path (p50 / p99 targets after warm-up):

| Path | Steps | p50 | p99 |
|---|---|---|---|
| In-process `evaluate`, 200-row `first` table, cached | slot binding, tree walk, result record | 20 us | 100 us (`TR-PERF-01`) |
| Same plus transactional decision log | plus one `INSERT` | 1.5 ms | 5 ms |
| REST `:evaluate` | auth, tenant resolve, JSON, evaluate, log | 3 ms | 10 ms in the same data centre |
| Sync process transition | lock token, run step, history insert, commit | 3 ms | 25 ms |
| Async step, engine part only | TX1 claim and TX2 completion | 5 ms | 20 ms; adapter time is the host's budget |
| Timer firing lag | poll interval plus claim | 300 ms | 1 s at the default 500 ms poll |

## 9. Failure modes, blast radius and recovery

| Scenario | Blast radius | Detection | Recovery |
|---|---|---|---|
| Replica crashes after TX1, before TX2 of an async step | One step of one instance | `locked_until < now()` | Another replica reclaims the job with `SKIP LOCKED`; the attempt counter increments; incident after `retry.max` |
| Adapter returns after the lease expired | One step | TX2 lease check fails | Late result discarded; the reclaiming attempt owns the step; host side idempotent by the job id |
| Client retries a start or a complete | One request | `pre_idempotency` primary key; `request_hash` comparison | Stored status and body returned; `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` on a different payload; `409` with `Retry-After` while in flight |
| Two operators complete the same task | One task | `lock_version` predicate updates 0 rows | `409 VERSION_CONFLICT`; the client reloads and decides |
| Two replicas finish both branches of a join at once | One instance | `pre_instance` row lock serialises the two transactions | Exactly one outgoing token; no lost wakeup (`TR-ASY-03`) |
| Replica A publishes, replica B holds a stale compiled version | One replica, until the bound | `pg_notify` missed; the epoch sweep sees `cache_epoch` moved | B evicts and recompiles within 5 s (`TR-ASY-04`); decisions in between still log the version they used |
| Host runs behind a transaction-mode pooler | All replicas | `LISTEN` unavailable or silently dropped | The epoch sweep is the only transport; same 5 s bound |
| Step fails after earlier external effects | One instance | retries exhausted with `onFailure: compensate` | Compensation stack walked LIFO with per-handler retry; `compensate: manual` opens an incident instead of skipping (ADR-0007) |
| Regex on adversarial input | One evaluation | Bounded matcher hits `regex-max-reads`, `regex-max-reads-per-decision` or the `regex-timeout-ms` backstop | `422 EVALUATION_ERROR` with reason `REGEX_TIMEOUT`; the thread is not stuck (`TR-SEC-02`) |
| Flyway migration fails mid-way | Host start-up | Engine history table shows the failed version | Fix forward with a new migration; the engine never edits applied scripts (`TR-OPS-01`) |
| Decision log partition for next month missing | Query and drop performance for that month | Rows land in `rle_decision_default`; the `ruleEngine` health contributor reports the missing partition | Start-up creates the current and next month's partitions; the scheduler creates them 7 days ahead and moves stragglers out of the default partition when it attaches a month |
| Host rolls back a business transaction after `evaluate` | That transaction | none needed | The decision row rolls back with it in `transactional` mode (`TR-EMB-04`) |

## 10. Deployment shapes

![Deployment shapes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/deployment-shapes.svg)

Engines keep no state outside the database. Replicas coordinate only through PostgreSQL: `SKIP LOCKED` leases for jobs, row locks for joins, `cache_epoch` plus `NOTIFY` for compiled caches. A standalone cockpit needs a CORS allowlist on each host it talks to (ADR-0005).

## 11. Verification gates

| Gate | Proves | Tooling |
|---|---|---|
| Module boundaries | `*-core` free of Spring, SQL, network, scripting, reflection | ArchUnit in every build (`TR-EMB-03`, `TR-EMB-05`, `TR-SEC-01`) |
| Determinism | Same version, inputs and clock give the same result | Property tests with the project's seeded generator (`TR-DET-01`), replay tests (`TR-DET-05`) |
| Async Wait-State | No connection held during adapter calls; lease takeover exactly once | Testcontainers with a 2-connection pool and a 2 s stub adapter (`TR-ASY-01`, `TR-ASY-02`) |
| Concurrency | Join without lost wakeup; stale completion rejected; duplicate-key semantics | Two-replica Testcontainers tests (`TR-ASY-03`, `TR-IDEM-03`, `TR-IDEM-05`) |
| Cache coherence | A replica serves the new version within the bound, with and without NOTIFY | Two-replica test, once direct and once through PgBouncer in transaction mode (`TR-ASY-04`) |
| Latency | Evaluation p99 and compile time | JMH in CI with a published trend (`TR-PERF-01`, `TR-PERF-02`) |
| Schema | `tenant_id` first in every index; forward-only migrations; old and new module versions tolerate each other's schema | Schema lint, migration tests (`TR-TEN-01`, `TR-OPS-01`, `TR-OPS-02`) |
| Spec traceability | Every `RLE-*`, `PRE-*`, `CKP-*` statement has a named test | Coverage script (`TR-QA-01`) |

## Deliberately absent

Separate engine process, message broker requirement, search index, built-in identity provider, scripting engine, full BPMN 2.0 or DMN FEEL.
