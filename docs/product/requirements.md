# Technical Requirements

| Field | Value |
|---|---|
| Type | Reference |
| Owner area | Architecture and QA |
| Last reviewed | 2026-11-18 |

Reference list of the numbered, testable requirements that gate a release. "Must" requirements block a release; "should" requirements are targets reported in release notes. IDs are stable; new requirements get new IDs. Module specifications (`RLE-*`, `PRE-*`, `CKP-*`) trace to these IDs.

## How requirements are used

- A "Must" requirement gates a release. A module version does not ship while a Must that applies to it fails its verification.
- A Must with a milestone, for example "Must (M2)", gates releases from that milestone on. Module specifications follow the same rule: a statement whose milestone has not been reached is not a release gate yet.
- A "Should" requirement is a target. Release notes report which Should items a release meets.
- Each Must maps to tests through specification ids. A statement in a module `SPEC.md` (`RLE-*`, `PRE-*`, `CKP-*`) names the `TR-*` id it traces to in its "Traces to" line, and at least one automated test carries the statement's id in its name (TR-QA-01).
- A requirement marked "Proposed" names the decision it waits for (a pending `PD-*` entry in [`decisions.md`](decisions.md) or a proposed ADR). It is not a release gate until that decision is recorded; its level and milestone show what it becomes if the decision is accepted as proposed.
- This page is the only list of `TR-*` ids and their text. Other documents quote the id and link here.

## Embedding and compatibility
| ID | Requirement | Verification |
|---|---|---|
| TR-EMB-01 | A host adds one starter dependency and one configuration block, starts, and the engine's schema exists and is usable. Must. | Example host in `examples/` boots in CI from a clean database |
| TR-EMB-02 | Supported: Java 21 and 25 (LTS); Spring Boot 4.x as the primary line (the starters build against the newest generally available 4.x minor and CI tests every 4.x minor in open-source support) and Spring Boot 3.5.x (latest patch) until release 1.0 (ADR-0011, PD-007); PostgreSQL 14 and later. The PostgreSQL range from 1.0 is pending PD-055 (proposed: 15 and later from 1.0, 14 tested until 1.0). Must. | CI matrix: Java 21 and 25 x Spring Boot 4.x minors in support plus 3.5.x x PostgreSQL 14, 16 and 17, with one job through PgBouncer in transaction mode |
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
| TR-SEC-03 | Tenant is taken only from the configured resolver; a request body tenant is ignored. From M2, with tenant switching enabled, an `X-Tenant-Id` header is accepted only when the tenant is in the token's allowed-tenants claim; any other header value is refused with `403 TENANT_NOT_ALLOWED`, returns no data and writes a security log entry; no API call or screen combines tenants (ADR-0014, PD-037). Must. | REST tests; tests that a header outside the allowed list is refused and logged (`RLE-018`, `PRE-038`, `CKP-022`) |
| TR-SEC-04 | Maker-checker: the requesting identity cannot approve its own rule set version or, from M2, its own process definition version, through any API (ADR-0012, PD-033). Must. | Service-layer and REST tests (`RLE-009`, `PRE-019`) |
| TR-SEC-05 | Inputs marked for masking are stored as keyed hashes and rendered masked in the cockpit and the REST API. Must. | Integration tests |
| TR-SEC-06 | Every release ships an SBOM; dependencies with high or critical vulnerabilities block the release. Must. | CI scan |
| TR-SEC-07 | OWASP ASVS level 2 for the REST layer and cockpit. Should. | Checklist review per minor release |
| TR-SEC-08 | Every REST error is an RFC 9457 `application/problem+json` body with a stable `code` and a `correlationId`; no response contains a stack trace or SQL. Must. | WebMvc tests for every code in the error matrix; injected-failure test on `500` |
| TR-SEC-09 | The embedded cockpit is served only to authenticated sessions with `Content-Security-Policy`, `X-Content-Type-Options: nosniff` and `Cache-Control: no-store` on API responses; standalone hosting requires an explicit CORS origin allowlist and CORS is off by default. Must. | Header assertions in starter tests; CORS preflight test with an unlisted origin |
| TR-SEC-10 | Masking uses HMAC-SHA256 with a host-provided key of at least 32 bytes; start-up fails when a loaded definition masks a field and no key is configured; the key never appears in logs, responses or the cockpit. Must. | Start-up test without a key; log capture test |
| TR-SEC-11 | Outside the `dev` profile the process engine starter refuses to start with `definitions-auto-approve: true` or `maker-checker.enabled: false`, as the rule engine starter does for rule sets (ADR-0012, PD-033). Must (M2). | Start-up tests per property and profile (`PRE-022`) |
| TR-SEC-12 | The standalone cockpit signs in with OIDC authorization code and PKCE as a public client and keeps tokens in memory only; by default it uses one access token per host. Each host validates the token audience and rejects a token issued for another host with `401`; with a CORS origin configured, the starter refuses to start outside `dev` unless an audience is configured (ADR-0014, PD-035). Must (M1). | Cockpit test of token storage and per-host tokens (`CKP-013`); host tests with a token for another audience; start-up test |

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
| TR-OBS-04 | Events for rule set version publish requested, published, rejected and retired; process definition publish requested, approved, rejected and retired (from M2, ADR-0012); instance started, completed, cancelled and failed; incident raised and resolved. Must. | Unit tests on publisher (`PRE-037`) |
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
| TR-PERF-07 | Every list API takes a tenant plus an equality on a leading index column or a time range of at most 31 days; pages use keyset cursors with at most 200 items; reads run with a 2 s statement timeout; a CI query-plan check fails the build on an unbounded query (ADR-0015, PD-038, X-F-24). Must (M2). | Query-plan check on a seeded database (`PRE-030`, `PRE-036`) |
| TR-PERF-08 | Cockpit read APIs answer within p99 150 ms per host on the reference dataset; instance activities are read only from the history partitions between the instance's start and end (ADR-0015). Must (M2). | Load test; captured plan shows partition pruning (`PRE-027`) |
| TR-PERF-09 | Live counts per node come from the token index, never from history: p99 200 ms up to 100,000 active tokens per definition version, then served from a cache of at most 15 s with an `asOf` time (ADR-0015, PD-038). Must (M2). | SQL capture and load test (`PRE-028`) |
| TR-PERF-10 | The hourly node statistics are at most 10 minutes behind history; no counter is updated inside a step transaction; no request path runs a `GROUP BY` over history (ADR-0015, PD-038). Must (M3). | Rollup test with two replicas and SQL capture of a transition (`PRE-029`) |
| TR-PERF-11 | The Rule Engine benchmark covers: 200-row `first`; 1,000-row worst case with the last row matching; `unique`; `collect`; regex-heavy; money-heavy; and evaluation with the decision log write on Testcontainers (p99 5 ms, reported, not gated). Must (M1). | JMH suite in CI |
| TR-PERF-12 | Benchmarks run on a dedicated runner (4 vCPU, fixed JDK build and heap) with 3 forks, 5 x 1 s warm-up and 10 x 1 s measurement in `SampleTime` mode and `-prof gc`; the build fails if p99 or bytes per operation regress more than 10 percent against the last release on the same runner, or p99 exceeds 100 us; results and a trend page are published per release. Must (M1). | Benchmark job; release checklist |
| TR-PERF-13 | The published performance contract states the conditions of `TR-PERF-01` (reference runner, JDK 21, after 10,000 warm-up evaluations, cached version, 200-row `first` table, up to 20 inputs, regex inputs within the 4,096-character limit) and what is not promised (first-call latency, database and host transaction time, host GC pauses, host functions, large `collect` outputs, other hardware), and publishes the harness. Must (M1). | Documentation review per release |

## Operability
| ID | Requirement | Verification |
|---|---|---|
| TR-OPS-01 | Migrations are forward-only; a release notes every migration it adds; downgrade steps are documented when a migration is destructive. Must. | Release checklist |
| TR-OPS-02 | Zero-downtime upgrade within a major version: old and new module versions tolerate each other's schema for one minor release. Should. | Upgrade test in CI |
| TR-OPS-03 | Incidents list cause, retry count, next action; retry and resolve are available by API and cockpit. Must (milestone 2). | Tests |
| TR-OPS-04 | The append-only tables `rle_decision` and `pre_history` are range-partitioned by month from their first migration, with a default partition; the scheduler creates monthly partitions 7 days ahead; configuration controls retention only (`decision-log.retention`, `history.retention`): when set, the scheduler drops whole monthly partitions that lie entirely before `now() - retention`, and no row is deleted by date (ADR-0010, PD-004). With retention unset nothing is dropped; the retention default and a minimum are pending PD-050. Must (M1 for `rle_decision`, M2 for `pre_history`). | Migration and partition job tests (`RLE-017`) |
| TR-OPS-05 | The engines work behind a transaction-mode connection pooler: no session state, no prepared-statement names, no advisory locks across transactions; `LISTEN` is optional. Must. | CI job with PgBouncer in transaction mode running the integration suite |

## Cockpit
| ID | Requirement | Verification |
|---|---|---|
| TR-UI-01 | Works embedded in one host, or standalone against several hosts listed in the host registry `cockpit-hosts.json` with one base URL per service; signs in once for all hosts; in M1 one host at a time, from M2 task and incident lists merged across hosts in exact order, with unreachable hosts named (ADR-0014, PD-035). Must. | End-to-end tests (`CKP-001`, `CKP-012`, `CKP-015`, `CKP-019`) |
| TR-UI-02 | Authoring a decision table, validating, simulating, requesting publication and approving are possible without leaving the cockpit. Must. | End-to-end tests |
| TR-UI-03 | Keyboard operable, visible focus, WCAG 2.1 AA contrast in light and dark themes. Should. | Automated accessibility checks |
| TR-UI-04 | English and Vietnamese labels, extensible. Should. | i18n coverage check |
| TR-UI-05 | Proposed, pending PD-046: a standalone cockpit supports at most 15 hosts and 2,000 active operators, with a merged inbox p95 of 300 ms; above that the documentation names one cockpit per group of hosts or the bank's gateway. Must (M2). | Load test with 15 simulated hosts (stub p99 150 ms) and 2,000 operators' refresh load |
| TR-UI-06 | The initial cockpit bundle is at most 500 KB gzipped; diagram code loads on demand; the first screen is usable within 2 s at 10 Mbit/s. Should (M2). | CI bundle check; Playwright with throttling (`CKP-028`) |
| TR-UI-07 | A definition is shown as a diagram, laid out automatically when it has no stored layout (or for the nodes a stored layout does not cover); an instance diagram shows token positions and per-node activity; a 200-node diagram renders within 1 s on the reference laptop profile; status is conveyed in text and icon as well as colour (ADR-0013, PD-032). Must (M2). | Playwright render timing; component tests (`CKP-016`, `CKP-017`) |
| TR-UI-08 | Every host answers `/api/bpm/meta` and `/api/bpm/me`; the cockpit supports the current and the previous engine minor version, turns screens on by the host's feature flags and the caller's permissions, and marks older hosts unsupported (ADR-0014, PD-035, X-F-22). Must (M1). | Contract tests against the JSON schemas (`RLE-019`); cockpit tests (`CKP-014`) |

## Definitions and diagrams
| ID | Requirement | Verification |
|---|---|---|
| TR-DEF-01 | Our process definition JSON (or YAML) is the canonical notation (to become the canonical serialisation of the BPMN semantic model if ADR-0018 is accepted; the subset below is the M3 scope, full BPMN follows under `TR-STD-*`, ADR-0017); a documented BPMN 2.0 subset is imported and exported in Java with the JDK StAX parser and no new dependency, with DTDs and external entities refused; anything outside the subset is rejected with an error naming the element, its id and line; export then import gives the same definition hash (ADR-0013, PD-034). Must (M3; export is a cut candidate). | Import and round-trip tests over every example definition; exported XML validated against the BPMN 2.0 XSD (`PRE-033`, `PRE-034`) |
| TR-DEF-02 | Diagram layout is stored apart from the definition and its hash, as append-only revisions with author and time; saving a layout needs no approval and never changes the definition; a definition without layout is drawn with automatic layout (ADR-0013, PD-032). Must (M3 for stored layouts; automatic layout from M2 under `TR-UI-07`). | Layout tests (`PRE-024`, `PRE-026`) |
| TR-DEF-03 | A process definition version moves `DRAFT`, `PENDING_APPROVAL`, `ACTIVE`, `RETIRED`; it becomes effective on its approved effective date; an older version cannot be approved after a newer one is active; the approver can read a graph diff of nodes and edges added, removed and changed (ADR-0012, PD-033). Must (M2). | Lifecycle tests (`PRE-018` to `PRE-021`, `PRE-025`) |
| TR-DEF-04 | Imports from Git or a pipeline and drafts made in the cockpit both arrive as drafts; every version exposes its content hash (SHA-256 of the RFC 8785 form of the document without its diagram part) so it can be compared with the file in Git; a per-environment host setting turns off draft creation and editing from the cockpit, default on (ADR-0012, PD-036). Must (M2). | Hash and setting tests (`PRE-023`, `PRE-024`, `CKP-023`) |
| TR-DEF-05 | A definition has at most 500 steps and 1 MB; larger definitions are rejected with `422 DEFINITION_TOO_LARGE`; hosts may lower the limits, never raise them (ADR-0013). Must (M2). | Import tests (`PRE-035`) |

## Standards conformance (ADR-0017)

Full BPMN 2.0 and DMN are a staged target: foundations in M1 to M3, stages S1, S2 and S3 in releases 1.1, 1.2 and 1.3 (dates pending the product owner). The target architecture, the element lists per stage and the deviations are in [`standards-compliance.md`](../architecture/standards-compliance.md). A requirement whose milestone is a release (1.1, 1.2, 1.3) gates that release.

| ID | Requirement | Verification |
|---|---|---|
| TR-STD-01 | Every imported or authored definition version stores the source document as received (JSON, YAML, from M3 BPMN XML and DMN XML) byte for byte, with its format and SHA-256, beside the canonical document; the source document is never executed. Must (M1 for rule sets, M2 for processes). | Import tests (`RLE-020`, `PRE-039`) |
| TR-STD-02 | Proposed with ADR-0019 and decision P1: every `horus-expr-v1` expression has a FEEL translation that gives the same result on the same inputs and clock (numbers as `DECIMAL128`, half-even rounding, unanchored XPath regex, code-point string order, FEEL null logic); money is a documented HORUS extension. Must (M1). | Property-based differential test against a reference FEEL implementation in test scope, 1,000 tries per property in CI and 100,000 nightly (`RLE-021`) |
| TR-STD-03 | A stored definition's hash never changes: it is computed in the schema version the document is stored in, is never recomputed when a newer schema version exists, and a BPMN or DMN import produces the lowest schema version that can express the model; step ids of the BPMN id form are kept unchanged in definitions, layouts, tokens and history. Must (M1 for rule sets, M2 for processes). | Hash and id tests (`PRE-024`, `PRE-034`, `PRE-039`, `PRE-042`, `RLE-020`) |
| TR-STD-04 | An element, attribute or expression language that the running release cannot import or execute is refused by name, with its id, line and the stage that adds it; nothing is dropped or ignored silently. Must (M3). | Import tests over one file per unsupported element (`PRE-033`) |
| TR-STD-05 | Each release publishes its deviation register against BPMN 2.0.2 and DMN, as in `standards-compliance.md` section 12, and its `/api/bpm/meta` `standards` object states the BPMN stage, the executable elements and the DMN conformance level. Must (1.1). | Release checklist; contract test of `bpm-meta.v1` |
| TR-STD-10 | Every BPMN MIWG reference model (A, B and C series, at a pinned commit) imports, is displayed and exports again with semantic equality of elements, attributes, references and BPMNDI, and the export validates against the BPMN 2.0.2 XSD. Must (1.1). | MIWG round-trip job in CI (licence of the test data pending PD-053) |
| TR-STD-11 | The HORUS executable BPMN conformance suite passes 100 percent of the scenarios of the stages released: S1 at 1.1, S1 and S2 at 1.2, all at 1.3; concurrency-sensitive scenarios also pass on two replicas; property tests over generated graphs show no lost token and joins that fire exactly once. Must (1.1, 1.2, 1.3). | Conformance suite and jqwik graph invariants in CI |
| TR-STD-12 | An interrupting boundary event on a wait state cancels the task or job and starts the boundary flow in one transaction under the instance lock; a late async result for a cancelled job is discarded and recorded with its attempt id, and raises `LATE_RESULT_AFTER_INTERRUPT` when the step is compensable. Must (M2 for instance cancel, 1.1 for boundary events). | Fault-injection tests with a slow stub adapter (`PRE-041`) |
| TR-STD-13 | Message correlation runs in the caller's transaction with no engine network I/O; a redelivered `messageId` has no second effect; at most one waiting instance subscription exists per message name and correlation key; a message with no receiver returns `NotCorrelated` and stores nothing but the idempotency record. Must (1.2). | Concurrent redelivery tests; ArchUnit network rule |
| TR-STD-14 | A signal broadcast reaches every subscription that existed when it was thrown exactly once, in delivery transactions of at most 100 instance locks each; a failure on one instance raises an incident and does not stop delivery. Must (1.2). | Broadcast test with 10,000 subscriptions on two replicas |
| TR-STD-15 | Multi-instance creates at most `multi-instance.max` (default 1,000, may be lowered only) children; the output collection is ordered by `loopCounter`; standard loops require `loopMaximum` (default 1,000). Must (1.1). | Unit and integration tests |
| TR-STD-16 | Script tasks execute only FEEL or `urn:horus-bpm:expr:v1`; any other `scriptFormat` is refused with `422 BPMN_SCRIPT_FORMAT_UNSUPPORTED`. Service tasks bind only to registered handlers by name; vendor class attributes and method-call expressions are refused with `422 BPMN_IMPLEMENTATION_UNSUPPORTED` and never evaluated. Must (1.1). | Import tests; ArchUnit (`TR-SEC-01`) |
| TR-STD-20 | DMN TCK `compliance-level-2` test cases at a pinned commit: 100 percent pass. Must (1.1). | TCK runner in CI, results published in the TCK format |
| TR-STD-21 | DMN TCK `compliance-level-3` test cases: at least 95 percent pass at 1.2; 100 percent at 1.3 except the policy exclusions (external Java and PMML functions), each listed by test id. Must (1.2, 1.3). | TCK runner in CI |
| TR-STD-22 | Decision tables support every DMN hit policy (`U`, `A`, `P`, `F`, `R`, `O`, `C`, `C+`, `C<`, `C>`, `C#`) with input values, output values and priorities. Must (1.1). | Unit tests per hit policy; TCK |
| TR-STD-23 | FEEL evaluation is deterministic: `now()` and `today()` read the evaluation clock in the model's time zone; the tz database version is recorded with the decision; a counted step budget (default 100,000) and a recursion depth of 64 end runaway evaluations with `EVALUATION_BUDGET_EXCEEDED`; external Java and PMML functions are refused at import. Must (1.1 for S-FEEL, 1.2 for full FEEL). | Replay tests across JVMs; budget tests |
| TR-STD-24 | Decisions in the compiled profile (S-FEEL) meet `TR-PERF-01`; a DRG of 10 S-FEEL decisions and a 20-entry boxed context over 100-item lists evaluate with p99 under 1 ms. Should (1.2). | JMH suite of `TR-PERF-11` extended |
| TR-STD-25 | DMN XML of versions 1.2 to 1.5 imports and exports with DMNDI kept and semantic round-trip equality; a v1 rule set exported as DMN evaluates to the same outputs as the original. Must (M3 for single decision tables, 1.1 for DRDs). | Round-trip and differential tests |
| TR-STD-26 | A CMMN document is refused with `422 CMMN_UNSUPPORTED`. Must (M3). | Import test |
| TR-STD-27 | The HORUS FEEL engine agrees with a reference FEEL implementation (test scope only) on generated expressions, with every difference either fixed or listed as a deviation. Should (1.2). | Nightly differential property test |

## Java API
| ID | Requirement | Verification |
|---|---|---|
| TR-API-01 | Proposed with ADR-0016: one entry-point interface per job, usable as Spring beans and through a builder without Spring; records with validating constructors and static factories for values; builders for requests with optional fields; no `Manager`, `Helper` or `Util` types. Must (M1). | API review at Gate 1 |
| TR-API-02 | Proposed with ADR-0016: every value and result type is a record or final class and deeply immutable. Must (M1). | Unit tests; ArchUnit |
| TR-API-03 | Proposed with ADR-0016: JSpecify `@NullMarked` on every `api` and `spi` package, enforced by NullAway; collections are empty, never null; `Optional` only as a lookup return type. Must (M1). | Build; ArchUnit |
| TR-API-04 | Proposed with ADR-0016: unchecked exceptions only, one root type whose `code()` equals the REST error code, one subtype per case; logged decision outcomes are returned in the result, not thrown. Must (M1). | One test per code in the error matrices |
| TR-API-05 | Proposed with ADR-0016: engine entry points are safe for concurrent use and hold no per-call state; SPI contracts state their thread-safety requirement. Must (M1). | Concurrency tests |
| TR-API-06 | Proposed with ADR-0016: public `api` and `spi` signatures use only JDK, engine and JSpecify types; no JSON library, Spring, JDBC or cache library types (ADR-0011). Must (M1). | ArchUnit on signatures |
| TR-API-07 | Proposed with ADR-0016: packages `com.horus.bpm.<engine>.api`, `.spi` and `.internal`; only `api` and `spi` are exported; examples never import `internal`. Must (M1). | ArchUnit; `module-info` check |
| TR-API-08 | Proposed with ADR-0016: from 1.0 a patch release is binary and source compatible and a minor release is binary compatible and additive, with new SPI methods as `default` methods; breaking changes only in a major release; checked against the last release. Must (1.0). | japicmp on `api` and `spi` in CI |
| TR-API-09 | Proposed, pending PD-054 and ADR-0016: a deprecated API element names its replacement, stays for at least one minor release and six months, and is removed only in a major release. Must (1.0). | japicmp; release notes check |

## Documentation
| ID | Requirement | Verification |
|---|---|---|
| TR-DOC-01 | Proposed with ADR-0016: every public type and method in `api` and `spi` has Javadoc stating purpose, contract (preconditions, rows written, whether it joins the caller's transaction), parameters with units and nullability, return, exceptions with error codes, thread safety, `@since`, and one example per entry point; doclint runs with all checks as errors on those packages. Must (M1). | Build fails on a doclint warning |
| TR-DOC-02 | Proposed with ADR-0016: Javadoc examples are snippet regions of compiled example sources, so they cannot drift from the code. Must (M1). | Build |
| TR-DOC-03 | Proposed with ADR-0016: a Javadoc jar is published per release, with HTML per version; every `horus.bpm.*` property has a description and a default in the Spring configuration metadata. Must (M1). | Release checklist; metadata test |
| TR-DOC-04 | Proposed with ADR-0016: one owner per fact: Javadoc owns signatures and per-method contracts; the wiki owns concepts, configuration, REST and the error-code catalogue and links to Javadoc instead of copying signatures; exception Javadoc links to its catalogue entry. Must (M1). | Documentation review; extends `TR-QA-02` |

## Test kit
| ID | Requirement | Verification |
|---|---|---|
| TR-TEST-01 | `rule-engine-test` and `process-engine-test` provide JUnit 5 extensions (`@RuleSetTest`, `@ProcessTest`) that load definitions from the classpath and inject engines on in-memory storage, and an injectable `TestClock` whose `advance` fires due timers and SLA escalations without waiting. Must (M1 for rules, M2 for processes). | Test-kit tests |
| TR-TEST-02 | The test kits provide fluent assertions for decisions and instances and a `StubAgentAdapter` that returns, fails or times out; the guardrail runs the real rule set, so a test can prove that `route-to-human` creates the approval task. Must (M1 for rules, M2 for processes). | Test-kit tests |
| TR-TEST-03 | The rule test kit reports which rows of each rule set a test suite matched. Should (M2). | Report test |
| TR-TEST-04 | The in-memory adapters pass the same storage contract suite as the PostgreSQL adapters. Must (M1 for rules, M2 for processes). | Shared contract suite run on both |
| TR-TEST-05 | The engines' own tests follow a stated pyramid: unit, property-based (1,000 tries per property in CI, 100,000 nightly), storage contract, integration and fault on PostgreSQL 14, 16 and 17 and PgBouncer, contract tests per error code, ArchUnit in every build, and end-to-end cockpit journeys. The licence scope of test tools is pending PD-053. Must (M1). | CI configuration review |

## Examples
| ID | Requirement | Verification |
|---|---|---|
| TR-EX-01 | Besides the rule-only and combined examples, the repository has `examples/process-engine-only` (four-eyes approval, SLA timer, saga compensation, no `RuleEngine` bean) and `examples/multi-host-cockpit` (two hosts, an OIDC provider, standalone cockpit with PKCE, CORS allowlist, allowed tenants, merged inbox). Must (M2). | Example smoke tests in CI |
| TR-EX-02 | Every example is a module of the main build, compiles on every pull request, runs a smoke test against Testcontainers, and has a README with the run command and the expected output. Must (M1). | CI |
| TR-EX-03 | Code blocks in the wiki are taken from tagged regions of example sources; a CI script fails when they differ. Must (M1). | CI script |

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
