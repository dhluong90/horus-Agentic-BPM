# Technical Requirements

Numbered, testable, and tied to a verification method. "Must" requirements gate a release; "should" requirements are targets reported in release notes. IDs are stable.

## Embedding and compatibility
| ID | Requirement | Verification |
|---|---|---|
| TR-EMB-01 | A host adds one starter dependency and one configuration block, starts, and the engine's schema exists and is usable. Must. | Example host in `examples/` boots in CI from a clean database |
| TR-EMB-02 | Supported: Java 21 and 25 (LTS), Spring Boot 3.4 and later 3.x, PostgreSQL 14 and later. Must. | CI matrix |
| TR-EMB-03 | `*-core` artifacts have no Spring dependency. Must. | ArchUnit rule and dependency analysis in CI |
| TR-EMB-04 | The engine never opens its own database connections; it uses the host's `DataSource` and participates in the host's transactions. Must. | Integration test with transaction rollback |
| TR-EMB-05 | No engine code path performs network I/O. Must. | ArchUnit rule forbidding `java.net` and HTTP clients in core and postgres modules |

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

## Performance and scalability
| ID | Requirement | Verification |
|---|---|---|
| TR-PERF-01 | In-process evaluation of a 200-row `first` table: p99 under 100 µs after warm-up, excluding the log write. Must. | JMH benchmark in CI, trend published |
| TR-PERF-02 | 1 000-row table compiles in under 500 ms. Should. | Benchmark |
| TR-PERF-03 | Decision log write adds exactly one insert in transactional mode. Must. | SQL capture test |
| TR-PERF-04 | Process engine: 1 000 step transitions per second per host replica on a 4-vCPU database, with timers and history. Should (milestone 2). | Load test |
| TR-PERF-05 | Multiple host replicas share timers and jobs without duplicate execution. Must (milestone 2). | Multi-replica integration test |

## Operability
| ID | Requirement | Verification |
|---|---|---|
| TR-OPS-01 | Migrations are forward-only; a release notes every migration it adds; downgrade steps are documented when a migration is destructive. Must. | Release checklist |
| TR-OPS-02 | Zero-downtime upgrade within a major version: old and new module versions tolerate each other's schema for one minor release. Should. | Upgrade test in CI |
| TR-OPS-03 | Incidents list cause, retry count, next action; retry and resolve are available by API and cockpit. Must (milestone 2). | Tests |
| TR-OPS-04 | Partitioning of append-only tables by month is available by configuration. Should. | Migration test |

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
