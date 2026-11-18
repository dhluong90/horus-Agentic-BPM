# Technical Requirements

Reference list of the numbered, testable requirements that gate a release. "Must" requirements block a release; "should" requirements are targets reported in release notes. IDs are stable; new requirements get new IDs. Module specifications (`RLE-*`, `PRE-*`, `CKP-*`) trace to these IDs.

## Embedding and compatibility
| ID | Requirement | Verification |
|---|---|---|
| TR-EMB-01 | A host adds one starter dependency and one configuration block, starts, and the engine's schema exists and is usable. Must. | Example host in `examples/` boots in CI from a clean database |
| TR-EMB-02 | Supported: Java 21 and 25 (LTS), Spring Boot 3.4 and later 3.x, PostgreSQL 14 and later. Must. | CI matrix |
| TR-EMB-03 | `*-core` artifacts have no Spring dependency. Must. | ArchUnit rule and dependency analysis in CI |
| TR-EMB-04 | The engine never opens its own database connections; it uses the host's `DataSource` and participates in the host's transactions. Must. | Integration test with transaction rollback |
| TR-EMB-05 | No engine code path performs network I/O. Must. | ArchUnit rule forbidding `java.net` and HTTP clients in core and postgres modules |
| TR-EMB-06 | Engine migrations run in their own Flyway history table (`rle_schema_history`, `pre_schema_history`) and never read or write the host's `flyway_schema_history`. Must. | Boot test with a host that has its own Flyway history; both histories unchanged by the other |

## Determinism and correctness
| ID | Requirement | Verification |
|---|---|---|
| TR-DET-01 | Evaluation of a version with the same inputs and clock yields identical outputs and matched rules on every run and JVM. Must. | Property-based tests over generated tables and inputs |
| TR-DET-02 | Expressions are parsed and type-checked at publish time; an unparsable or ill-typed expression cannot be published. Must. | Validation test suite |
| TR-DET-03 | Money comparisons across currencies are rejected, never coerced. Must. | Unit tests |
| TR-DET-04 | `unique` hit policy with zero or multiple matches produces an error result, never partial outputs. Must. | Unit tests |
| TR-DET-05 | A decision records the version and clock it used so that it can be re-evaluated to the same result after any number of later publications. Must. | Replay test |

## Security
| ID | Requirement | Verification |
|---|---|---|
| TR-SEC-01 | No arbitrary code execution: no scripting engine, reflection-based invocation, class loading or deserialisation of arbitrary types from definitions or inputs. Must. | ArchUnit rules; dependency allowlist; security review per release |
| TR-SEC-02 | Regex matching is bounded (length and time); a timeout fails the decision with an error code. Must. | Adversarial pattern tests |
| TR-SEC-03 | Tenant is taken only from the configured resolver; a request body tenant is ignored. Must. | REST tests |
| TR-SEC-04 | Maker-checker: the requesting identity cannot approve its own publication, through any API. Must. | Service-layer and REST tests |
| TR-SEC-05 | Inputs marked for masking are stored as keyed hashes and rendered masked in the cockpit and the REST API. Must. | Integration tests |
| TR-SEC-06 | Every release ships an SBOM; dependencies with high or critical vulnerabilities block the release. Must. | CI scan |
| TR-SEC-07 | OWASP ASVS level 2 for the REST layer and cockpit. Should. | Checklist review per minor release |
| TR-SEC-08 | Every REST error is an RFC 9457 `application/problem+json` body with a stable `code` and a `correlationId`; no response contains a stack trace or SQL. Must. | WebMvc tests for every code in the error matrix; injected-failure test on `500` |
| TR-SEC-09 | The embedded cockpit is served only to authenticated sessions with `Content-Security-Policy`, `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` on API responses; standalone hosting requires an explicit CORS origin allowlist and CORS is off by default. Must. | Header assertions in starter tests; CORS preflight test with an unlisted origin |
| TR-SEC-10 | Masking uses HMAC-SHA256 with a host-provided key of at least 32 bytes; start-up fails when a loaded definition masks a field and no key is configured; the key never appears in logs, responses or the cockpit. Must. | Start-up test without a key; log capture test |

## Reliability under retries
| ID | Requirement | Verification |
|---|---|---|
| TR-IDEM-01 | Every mutating operation accepts an idempotency key; a replay within the retention window returns the original result and performs no second effect. Must (M1 for rules, M2 for processes). | Concurrent replay tests |
| TR-IDEM-02 | Business key unique per tenant and definition; a second start returns the existing instance. Must (M2). | Integration tests |
| TR-IDEM-03 | Task and instance mutations use optimistic locking; a stale completion is rejected with a conflict. Must (M2). | Concurrency tests |
| TR-IDEM-04 | Compensate handlers and service handlers receive the step attempt id so hosts can make them idempotent. Must (M2). | API tests |
| TR-IDEM-05 | A replay of an idempotency key with a different request hash is rejected with `422 IDEMPOTENCY_KEY_REUSE_MISMATCH`; a duplicate that arrives while the first request is in flight gets `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After`; a replay with the same hash returns the original status code and body. Must (M1 for rules, M2 for processes). | Two-thread test with a paused first request; mismatch test |

## Concurrency and asynchronous steps
| ID | Requirement | Verification |
|---|---|---|
| TR-ASY-01 | No adapter, HTTP, MCP or model call runs inside an open engine database transaction or while a row lock is held. Async steps claim with a lease in one transaction, call the adapter with no connection, and complete in a second transaction. Must (M2). | Testcontainers test with a 2-connection pool and 10 concurrent 2 s stub adapters: zero pool timeouts; ArchUnit rule that adapter ports are not invoked from classes annotated or wrapped by a transaction |
| TR-ASY-02 | A job whose lease expired is reclaimed by another replica exactly once; a completion from the original claimant after expiry is rejected and leaves no state change; each attempt carries a new attempt id. Must (M2). | Two-replica fault-injection test with a paused claimant |
| TR-ASY-03 | Concurrent arrivals at a `parallelGateway` join from different replicas produce exactly one outgoing token: never zero (lost wakeup), never two. Must (M2). | Two-replica test with synchronised branch completion, 1 000 iterations |
| TR-ASY-04 | After a publish or retire commits, every replica serves the new effective version within `cache.epoch-check-interval` (default 5 s) with or without `LISTEN/NOTIFY` delivery, including behind a transaction-mode connection pooler. Must (M1). | Two-replica test run twice: direct connection, and through PgBouncer in transaction mode with `cache.listen-notify=false` |

## Compensation
| ID | Requirement | Verification |
|---|---|---|
| TR-COMP-01 | A step may declare a compensate handler; after retries are exhausted or on explicit throw, completed steps' handlers run in reverse order. Must (M2). | Integration tests with failing steps |
| TR-COMP-02 | Each compensation has its own retry policy and raises an incident on failure without losing the record of what was compensated. Must (M2). | Fault-injection tests |
| TR-COMP-03 | `compensate: manual` raises an incident with the step context instead of silently skipping. Must (M2). | Tests |

## Business calendars
| ID | Requirement | Verification |
|---|---|---|
| TR-CAL-01 | SLA durations are computed through the `BusinessCalendar` SPI; the built-in implementation supports weekly hours, holidays and time zone per tenant. Must (M2). | Unit tests across holiday and DST boundaries |
| TR-CAL-02 | Calendar changes do not move deadlines already computed unless a recompute is requested explicitly. Must (M2). | Tests |

## Instance migration
| ID | Requirement | Verification |
|---|---|---|
| TR-MIG-01 | A migration plan between two versions can be validated as a dry run reporting instances per step, unmapped steps and conflicts, without changing anything. Must (M2). | Integration tests |
| TR-MIG-02 | Execution is batched; each instance is locked, remapped, pinned to the target version and gets a history row; a failure affects only that instance. Must (M2). | Fault-injection tests with 10 000 instances |
| TR-MIG-03 | Explicit step mapping for renamed steps and cancellation for removed steps; cockpit support. Should (M3). | End-to-end tests |

## Agentic steps
| ID | Requirement | Verification |
|---|---|---|
| TR-AGT-01 | An `agentTask` cannot continue the process until its output validates against the declared JSON Schema and the guardrail rule set returns `allow`; `route-to-human` creates an approval task carrying the output and the matched rules; `fail` raises an incident. Must (M2). | Integration tests with a stub adapter |
| TR-AGT-02 | Agent inputs, outputs, schema result, guardrail decision id and adapter latency are recorded in history; inputs marked for masking are hashed. Must (M2). | History tests |
| TR-AGT-03 | Adapters run under the declared timeout and retry policy; the engine never embeds or calls a model directly. Must (M2). | ArchUnit rule; timeout tests |
| TR-AGT-04 | An MCP tool adapter and reference LLM adapters are provided as examples, outside the engine artifacts. Should (M3). | Example tests |

## Multi-tenancy
| ID | Requirement | Verification |
|---|---|---|
| TR-TEN-01 | Every table has `tenant_id`; every query filters by it; no cross-tenant read path exists. Must. | Schema lint; integration tests with two tenants |
| TR-TEN-02 | Per-tenant rule sets, versions, processes, instances and logs. Must. | Integration tests |

## Observability and audit
| ID | Requirement | Verification |
|---|---|---|
| TR-OBS-01 | Append-only decision log and process history; the engine exposes no update or delete operations on them. Must. | Code review rule; integration tests |
| TR-OBS-02 | Micrometer metrics for evaluations, errors, durations, active instances, incidents, timer lag. Must. | Metrics test |
| TR-OBS-03 | OpenTelemetry spans for evaluation and step execution when a tracer is present. Should. | Integration test with an in-memory exporter |
| TR-OBS-04 | Events for version published and retired, instance started, completed, incident raised. Must. | Unit tests on publisher |
| TR-OBS-05 | A decision can be explained in plain language from the log alone. Should. | Explain endpoint tests |
| TR-OBS-06 | Every event carries the envelope `eventId` (ULID), `eventType` with a version suffix, `occurredAt`, `tenantId`, `correlationId`, `causationId`, `producer`, `schemaVersion`, `payload`; changes within an event type version are additive; the event is published inside the transaction that changes state. Must. | JSON schema test per event type; compatibility test against the previous release's schemas |

## Performance and scalability
| ID | Requirement | Verification |
|---|---|---|
| TR-PERF-01 | In-process evaluation of a 200-row `first` table: p99 under 100 µs after warm-up, excluding the log write. Must. | JMH benchmark in CI, trend published |
| TR-PERF-02 | 1 000-row table compiles in under 500 ms. Should. | Benchmark |
| TR-PERF-03 | Decision log write adds exactly one insert in transactional mode. Must. | SQL capture test |
| TR-PERF-04 | Process engine: 1 000 step transitions per second per host replica on a 4-vCPU database, with timers and history. Should (milestone 2). | Load test |
| TR-PERF-05 | Multiple host replicas share timers and jobs without duplicate execution. Must (milestone 2). | Multi-replica integration test |
| TR-PERF-06 | The compiled-version cache is bounded by `cache.max-entries`; heap use stays flat when more versions than the bound are evaluated in rotation. Must (M1). | Heap test evaluating 2 x `cache.max-entries` versions; eviction counter asserted |

## Operability
| ID | Requirement | Verification |
|---|---|---|
| TR-OPS-01 | Migrations are forward-only; a release notes every migration it adds; downgrade steps are documented when a migration is destructive. Must. | Release checklist |
| TR-OPS-02 | Zero-downtime upgrade within a major version: old and new module versions tolerate each other's schema for one minor release. Should. | Upgrade test in CI |
| TR-OPS-03 | Incidents list cause, retry count, next action; retry and resolve are available by API and cockpit. Must (milestone 2). | Tests |
| TR-OPS-04 | Partitioning of append-only tables by month is available by configuration. Should. | Migration test |
| TR-OPS-05 | The engines work behind a transaction-mode connection pooler: no session state, no prepared-statement names, no advisory locks across transactions; `LISTEN` is optional. Must. | CI job with PgBouncer in transaction mode running the integration suite |

## Cockpit
| ID | Requirement | Verification |
|---|---|---|
| TR-UI-01 | Works against any host that exposes the REST layer; configuration by one base URL per service. Must. | End-to-end tests |
| TR-UI-02 | Authoring a decision table, validating, simulating, requesting publication and approving are possible without leaving the cockpit. Must. | End-to-end tests |
| TR-UI-03 | Keyboard operable, visible focus, WCAG 2.1 AA contrast in light and dark themes. Should. | Automated accessibility checks |
| TR-UI-04 | English and Vietnamese labels, extensible. Should. | i18n coverage check |

## Quality and documentation
| ID | Requirement | Verification |
|---|---|---|
| TR-QA-01 | Every specification statement in `docs/SPEC.md` has at least one test named by its id. Must. | Coverage script in CI |
| TR-QA-02 | Public API changes are documented in the wiki in the same pull request. Must. | Pull request checklist |
| TR-QA-03 | Line coverage of core modules at or above 85 percent; the evaluator at or above 95 percent. Should. | Coverage report |

## Licensing
| ID | Requirement | Verification |
|---|---|---|
| TR-LIC-01 | Third-party dependencies limited to Apache 2.0, MIT and BSD families; the build fails on anything else. Must. | Licence plugin in CI |
| TR-LIC-02 | Every artifact carries the project licence notice and the commercial-licence pointer. Must. | Release checklist |
