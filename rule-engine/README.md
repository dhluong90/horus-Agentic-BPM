# Rule Engine

Code: `RLE` · Version: 0.2.0 · Status: pre-alpha

Embeddable decision library: decision tables, versioned rule sets, safe expression language, decision API and log, simulation.

Documents: `docs/DESIGN.md`, `docs/SPEC.md`, `docs/TEST-PLAN.md`, `docs/adr/`. Public documentation is in the project wiki.

## Modules

| Module | Contents today | Depends on |
|---|---|---|
| `rule-engine-core` | `api`: `RuleEngine`, `VersionService`, `Money`, `DecisionInput`, `DecisionResult`, one exception per error code. `spi`: `TenantResolver`, `CallerResolver`. `internal`: YAML reader into the DMN-shaped model, RFC 8785 canonical form, expression parser and evaluator (FEEL null semantics), `first` hit policy, the two services over storage ports | JDK and Jackson 3 in `internal` only (ArchUnit and Maven Enforcer) |
| `rule-engine-postgres` | `V1__baseline.sql` (all `rle_*` tables; `rle_decision` partitioned by month with a default partition), `RuleEngineSchemaMigrator`, `DecisionLogPartitions`, `RuleEngineTransactions`, and the `JdbcClient` adapters for versions, the decision log, the outbox and idempotency, joined to the caller's transaction | core, Spring JDBC, Flyway |
| `rule-engine-rest` | Controllers under `/api/bpm/rules/v1` (import, read, request publication, approve, evaluate), RFC 9457 errors, the engine's own JSON. Contract: [`api/openapi.yaml`](api/openapi.yaml) | core; Spring MVC from the host |
| `rule-engine-spring-boot-starter` | `RuleEngineAutoConfiguration` (single host `DataSource`, checked against the transaction manager), `RuleEngine` and `VersionService` beans, the caller from Spring Security, the controllers in servlet hosts, `RuleEngineInactiveAutoConfiguration`, `horus.bpm.rules.enabled` and `migrate-on-start`, `ruleEngine` health contributor | core, postgres, rest, Spring Boot auto-configuration |

`rule-engine-test` (test scope) lets a developer test a rule set file in JUnit 5 with `@RuleSetTest` and writes a row coverage report: see the wiki page "How to simulate a draft and test rule sets" and `docs/DESIGN.md` section 4.10. It is never a dependency of the starter. A host registers its own pure functions for rule cells as `ExpressionFunction` beans: see section 3.2.1 of the design.

### What works today

Import a YAML rule set as a draft (source kept byte for byte), request publication, approve as a second person, and evaluate the `first` hit policy with one decision row in the caller's transaction, from Java or REST. The walkthrough is in [`examples/rule-engine-only/README.md`](../examples/rule-engine-only/README.md). Not yet: `unique` and `collect`, functions such as `today()` and `matches()`, validation reports, simulation, reject and retire, JSON import, masking by keyed hash, cache invalidation across replicas.

Build and test this engine only, from the repository root: `mvn -B -pl rule-engine/rule-engine-spring-boot-starter -am verify` (the storage integration tests need Docker).
