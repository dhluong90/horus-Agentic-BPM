---
name: hexagonal-and-modulith-architecture
description: "Design, implement, and enforce Ports & Adapters (Hexagonal Architecture) and Spring Modulith boundaries with Functional Core / Imperative Shell, pure framework-free domain cores, dependency inversion, and ArchUnit verification. Use when structuring Java 21 / Spring Boot 3.4+ libraries or banking accelerators, decoupling domain rules from persistence/HTTP, or writing ArchUnit checks. Adapted from affaan-m/ECC (hexagonal-architecture & module-governance)."
---

# Hexagonal & Spring Modulith Architecture

Keep business rules, DSL evaluators, and state machines pure, deterministic, and independent of Spring, HTTP, SQL, and external networks. Push all I/O to explicit adapters at the edges.

## When to Use
- Designing module boundaries for embeddable libraries (`*-core`, `*-postgres`, `*-rest`, `*-spring-boot-starter`) in `horus-Agentic-BPM`.
- Structuring internal bounded contexts inside a HORUS Banking Accelerator (`Spring Modulith`) without creating distributed-monolith coupling.
- Refactoring framework-coupled services where `@Transactional`, HTTP controllers, or JPA/JDBC calls bleed into domain logic.

---

## Core Architectural Rules (Functional Core, Imperative Shell)

1. **Inward Dependency Direction:**
   - `Adapters (rest, postgres, kafka, mcp)` → `Application (use cases / services)` → `Ports (inbound/outbound interfaces)` → `Domain Core`.
   - **`*-core` depends ONLY on Java 21 standard library (+ Jackson where explicitly permitted for canonical JSON/YAML model).** Zero Spring, zero `java.sql`, zero `java.net` / HTTP clients (`TR-EMB-03`, `TR-EMB-05`).
2. **Ports Model Capabilities, Not Technologies:**
   - Good outbound ports: `RuleRepositoryPort`, `DecisionLogPort`, `TenantContextPort`, `ClockProvider`, `AgentAdapterPort`, `BusinessCalendarPort`.
   - Bad outbound ports: `JdbcRuleTableDao`, `HttpAgentClient`, `KafkaMessageSender`.
3. **Immutable Domain Transformations:**
   - Core evaluation and state transitions take `(CurrentState, Command, Clock)` and return `(NewState, DomainEvents, OutboundActions)` without mutating shared globals or holding hidden thread-locals.
4. **Composition Root Wiring:**
   - Only `*-spring-boot-starter` (`@AutoConfiguration`) or an application `@Configuration` wires concrete PostgreSQL/REST adapters to `*-core` use cases. Never scatter `new ConcreteAdapter()` or static singletons inside core classes.

---

## Multi-Module Java Layout

### Pattern A: Embeddable Library (`horus-Agentic-BPM`)
```text
<engine>/
  <engine>-core/                  # Pure Java 21: domain model, AST, evaluator, state machine, inbound/outbound SPIs
  <engine>-postgres/              # Outbound adapters: Spring JDBC JdbcClient, Flyway migrations (rle_*/pre_*), LISTEN/NOTIFY
  <engine>-rest/                  # Inbound adapters: Spring WebMvc controllers, OpenAPI DTOs, RFC 9457 error handler
  <engine>-spring-boot-starter/   # Composition root: @AutoConfiguration, properties binding, Micrometer/OTel hooks
  <engine>-test/                  # Test adapters: in-memory repositories, fixed Clock, JUnit 5 extension, coverage report
```

### Pattern B: Banking Accelerator Service (Spring Modulith)
```text
com.horus.bap.<product>/
  shared/                         # Kernel value types (Money, TenantId, IdempotencyKey) or imported from libs/
  <feature>/                      # Modulith module (e.g. wallet, posting, limit)
    api/                          # Public module interface & published domain events ONLY
    domain/                       # Pure entities, aggregates, policies (package-private internals)
    application/                  # Use-case orchestrators + outbound SPIs
    infrastructure/               # JdbcClient repos, Kafka outbox listeners, external client adapters
```
- **Module Governance Rule:** One Spring Modulith module NEVER queries another module's database tables or imports another module's `infrastructure/` or `domain/` internals — communicate via the module's `api/` facade or Spring Modulith `ApplicationModuleListener` events.

---

## Automated Boundary Enforcement (ArchUnit)

Every Java project must encode architectural boundaries in executable `ArchUnit` tests (`TR-EMB-03`, `TR-EMB-05`, `TR-SEC-01`):
1. **No Spring in `*-core`:** `noClasses().that().resideInAPackage("..core..").should().dependOnClassesThat().resideInAnyPackage("org.springframework..")`
2. **No Network I/O in Engines:** `noClasses().that().resideInAnyPackage("..core..", "..postgres..").should().dependOnClassesThat().resideInAnyPackage("java.net..", "java.net.http..", "org.springframework.web.client..")`
3. **No Scripting / Reflection Gadgets (`TR-SEC-01`):** Forbid `javax.script..`, `org.springframework.expression..`, `java.lang.reflect.Method.invoke` in rule evaluation packages.
4. **Repository Tenancy Discipline:** Controllers/REST DTOs must never pass `tenantId` from request payloads down into service/repository methods; `TenantContextPort` resolves it from security context.

---

## Testing Pyramid by Hexagonal Boundary
- **Core / Domain Tests:** Pure JUnit 5 + `jqwik` property-based tests (zero mocks, microsecond execution).
- **Use-Case / Engine Orchestration Tests:** Run with `*-test` in-memory port fakes and a fixed `Clock`.
- **Adapter Integration Tests:** Run `*-postgres` against real PostgreSQL via `Testcontainers` (concurrency, `SKIP LOCKED`, constraint checks, multi-tenant isolation).
- **Contract / Inbound Tests:** Spring `@WebMvcTest` or end-to-end REST tests validating OpenAPI schemas, HTTP status codes, and maker-checker guards.

## HORUS Program Integration
- Enforces `ADR-0002` (shared libs as versioned packages, no source coupling across accelerators) and `ADR-0003`/`ADR-0004` (`*-core` framework-free embeddable libraries).
- Run `architecture-critic-and-review` against these boundary rules on every PR before merge.
