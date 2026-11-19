# Technical Stack

Reference for the libraries and tools the project uses, what each one is for, and what was rejected. Chosen for small footprint, long support and permissive licences; the decisions are ADRs in `docs/adr/`.

![Containers](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/c4-container.svg)

## Engines
| Area | Choice | Licence | Rejected, and why |
|---|---|---|---|
| Language | Java 21 baseline, Java 25 in CI | | Kotlin: a runtime in every host |
| Integration | Spring Boot 4.x starters, also tested on 3.5.x until 1.0 (ADR-0011) with `@AutoConfiguration`; `*-core` is framework-free | Apache 2.0 | Quarkus, Micronaut, Jakarta EE: later if asked |
| Data access | Spring JDBC `JdbcClient` | Apache 2.0 | JPA/Hibernate: hidden queries and entity lifecycle inside a library; jOOQ: code generation and dual licence |
| Database | PostgreSQL 14+ until 1.0, 15+ from 1.0, behind a storage port. On 14 we fix engine defects, not database ones | | Multi-database in 0.x: doubles the matrix before the design is proven |
| Migrations | Flyway community edition, one history table per engine | Apache 2.0 | Liquibase: heavier |
| JSON | Jackson 3, inside the engine only (ADR-0021); never in the public Java API | Apache 2.0 | Jackson 2: the deprecated line on Spring Boot 4; a port with one adapter per Jackson major: two adapters to keep equal, kept as the fallback |
| JSON Schema (agentTask output) | `networknt/json-schema-validator` behind an `OutputSchemaValidator` port in the Process Engine starter; `*-core` sees only the port | Apache 2.0 | Writing a validator: Draft 2020-12 is large; `everit`: older drafts, less active |
| Expression language | Hand-written recursive-descent parser, sealed typed tree, zero dependencies (ADR-0003) | | ANTLR: fine licence, more machinery than the grammar needs; SpEL, MVEL, JEXL: allow method calls and reflection |
| Identifiers | ULID, generated in `*-core` (Crockford base32, monotonic within a millisecond) | | UUIDv4: index churn; sequences: not replica-safe; a ULID library: 60 lines are not worth a dependency |
| Compiled cache | Caffeine in the starter behind a `CompiledCache` port; bounded LRU default in `*-core` | Apache 2.0 | Guava cache: larger artifact; unbounded maps: heap growth with tenant count |
| Masking | JDK `javax.crypto.Mac` HMAC-SHA256 with a host-provided key | | Plain SHA-256: enumerable inputs such as national ids |
| Errors | Spring `ProblemDetail` (RFC 9457) | Apache 2.0 | Custom envelopes: one more thing for clients to learn |
| Observability | Micrometer; OpenTelemetry API optional | Apache 2.0 | Vendor SDKs |
| Scheduling | Database timers and jobs with `SKIP LOCKED` leases, one scheduler thread per host replica | | Quartz: own tables and clustering; brokers: a new dependency |
| Cache coherence | PostgreSQL `LISTEN/NOTIFY` plus a `cache_epoch` sweep (ADR-0009) | | Broker topic: new infrastructure; TTL only: stale for the whole TTL |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL, PgBouncer), jqwik, ArchUnit, JMH | EPL 2.0 (JUnit), Apache 2.0, MIT | Mockito for the evaluator: pure functions need no mocks. Test-scope licences do not ship |
| Build and supply chain | Maven reactors plus `bpm-bom`; Error Prone, Checkstyle, SpotBugs, Dependency-Check, CycloneDX SBOM, licence allowlist | | Gradle: fine, Maven's rigidity suits a library |

New runtime dependencies need a one-paragraph justification in the pull request and a permissive licence (`TR-LIC-01`).

## Cockpit
| Area | Choice |
|---|---|
| UI | React 18, TypeScript 5, Vite |
| Data | TanStack Query and Table |
| Forms | React Hook Form, Zod schemas generated from OpenAPI 3.1 |
| Styling | CSS variables, small internal component set, light and dark |
| Testing | Vitest, Testing Library, Playwright with axe-core against an example host |
| Packaging | Jar served by `cockpit-spring-boot-starter`, and a plain archive for standalone hosting |

## Delivery
| Area | Choice |
|---|---|
| CI | GitHub Actions: Java 21/25 x PostgreSQL 14/16/17, one job through PgBouncer in transaction mode, ArchUnit, benchmark trend, SBOM, licence check, wiki publish |
| Versioning | One project-wide semantic version for every module (PD-087); tags `vX.Y.Z`; `bpm-bom` with every release |
| Distribution | Maven Central via the Sonatype Central Portal, `groupId` `io.github.dhluong90.bpm` (ADR-0022) |

## Open decisions
- **Java 25 as baseline** once host adoption allows.
- **JSON Schema validator.** `networknt/json-schema-validator` is the proposed default; the port keeps the choice reversible. Decided before M2 starts.
