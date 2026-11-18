# Technical Stack

Chosen for small footprint, long support windows and permissive licences. Each row names what was rejected and why. Decisions are recorded as ADRs in the repository.

## Engines
| Area | Choice | Why | Rejected |
|---|---|---|---|
| Language | Java 21 (LTS), source and target 21; Java 25 in the CI matrix | Virtual threads, records, pattern matching; the host ecosystem we target | Kotlin (adds a runtime dependency to every host) |
| Framework integration | Spring Boot 3.4+ starters; core modules framework-free | Where our adopters already are; auto-configuration makes TR-EMB-01 possible | Jakarta EE integration (later, if asked); Quarkus or Micronaut (not now) |
| Database access | Spring JDBC `JdbcClient` in the postgres modules; plain JDBC in tests | Full control over SQL, prefixed schemas, locking (`SKIP LOCKED`), no lazy loading or dirty checking surprises | JPA/Hibernate (hidden queries, entity lifecycle in a library is a liability); jOOQ (code generation step, dual licence for some databases) |
| Database | PostgreSQL 14+ | JSONB, advisory and row locks, partitioning, ubiquity | Multi-database support in 0.x (storage SPI keeps the door open) |
| Migrations | Flyway (Apache 2.0 community edition), separate history table per engine | Standard in Spring hosts; the engine's migrations run beside the host's | Liquibase (XML/YAML changelogs, heavier) |
| JSON | Jackson | Already in every Spring host | Gson, Jakarta JSON-B |
| Expression language | Hand-written recursive-descent parser and typed evaluation tree, zero dependencies | Full control of the grammar, no code generation, easy to audit for TR-SEC-01 | ANTLR (BSD, acceptable, but a generated parser and runtime for a grammar of this size is more than needed); SpEL, MVEL, JEXL (allow method calls and reflection) |
| Identifiers | ULID generated in the engine | Time-ordered, index friendly, safe to generate in any replica | UUIDv4 (index churn), database sequences (not portable across replicas) |
| Metrics and tracing | Micrometer, OpenTelemetry API (optional dependency) | What Spring hosts already expose | Proprietary APM SDKs |
| Scheduling (process engine) | Database-backed timers claimed with `FOR UPDATE SKIP LOCKED`, one scheduler thread per host instance | No external scheduler, correct across replicas | Quartz (own tables and clustering model), message brokers |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL), jqwik (property tests for determinism), ArchUnit (architecture rules), JMH (benchmarks) | Standard, permissive, covers TR-DET and TR-EMB | Mockito for the evaluator (pure functions need no mocks) |
| Build | Maven, one reactor per engine plus a `bpm-bom`; reproducible builds; Error Prone and Checkstyle | Familiar to Java adopters, strong release tooling | Gradle (fine, but Maven's rigidity suits a library) |
| Static analysis and supply chain | SpotBugs, OWASP Dependency-Check, CycloneDX SBOM, licence-maven-plugin allowlist | TR-SEC-06, TR-LIC-01 | |

## Cockpit
| Area | Choice | Why |
|---|---|---|
| UI | React 18, TypeScript 5, Vite | Mainstream, fast builds, static output that any host can serve |
| Data | TanStack Query for server state, TanStack Table for decision-table and log grids | Small, well maintained, MIT |
| Forms and validation | React Hook Form with Zod schemas generated from the OpenAPI document | One source of truth for request shapes |
| Styling | CSS variables and a small internal component set; light and dark themes | No heavy design-system dependency to keep in step |
| Testing | Vitest, Testing Library, Playwright end-to-end against an example host | TR-UI-01, TR-UI-02 |
| Packaging | Built assets published as a jar served by `cockpit-spring-boot-starter`, and as a plain archive for standalone hosting | Both deployment shapes in [[Architecture]] |

## Delivery and release
| Area | Choice |
|---|---|
| CI | GitHub Actions: build, test matrix (Java 21/25, PostgreSQL 14/16/17), ArchUnit, benchmarks trend, SBOM, licence check, wiki publish |
| Versioning | Semantic versioning per module; tags `<module>/vX.Y.Z`; `bpm-bom` released with every module release |
| Distribution | Maven Central through the Sonatype Central Portal. The Maven namespace must be one the project can verify (a domain or a GitHub namespace); the groupId is therefore an open decision, see below |
| Documentation | This wiki, published from `wiki/` on every merge; OpenAPI documents attached to releases |

## Open decisions
- **Maven groupId.** `com.horus.bpm` requires verified ownership of `horus.com` for Maven Central. If that domain is not available to the project, the candidates are a domain the project does control, or `io.github.<owner>.bpm`. Decided before the first published artifact.
- **Java 25 as baseline** once adoption in hosts allows; until then Java 21 is the floor.
