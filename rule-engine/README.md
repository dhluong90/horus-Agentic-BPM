# Rule Engine

Code: `RLE` · Version: 0.1.0-SNAPSHOT · Status: pre-alpha

Embeddable decision library: decision tables, versioned rule sets, safe expression language, decision API and log, simulation.

Documents: `docs/DESIGN.md`, `docs/SPEC.md`, `docs/TEST-PLAN.md`, `docs/adr/`. Public documentation is in the project wiki.

## Modules

| Module | Contents today | Depends on |
|---|---|---|
| `rule-engine-core` | `com.horus.bpm.rules.api`: storage names the engine owns. Model, compiler and evaluator arrive with the Sprint 1 features | JDK only (ArchUnit and Maven Enforcer) |
| `rule-engine-postgres` | `V1__rle_baseline.sql` (all `rle_*` tables; `rle_decision` partitioned by month with a default partition), `RuleEngineSchemaMigrator` (Flyway on the host `DataSource`, history `rle_schema_history`, schema status), `DecisionLogPartitions` (current and next UTC month from the host `Clock`), `RuleEngineTransactions` | core, Spring JDBC, Flyway |
| `rule-engine-spring-boot-starter` | `RuleEngineAutoConfiguration` (single host `DataSource`, checked against the transaction manager), `RuleEngineInactiveAutoConfiguration` (warns when the engine backs off), `horus.bpm.rules.enabled` and `migrate-on-start`, `ruleEngine` health contributor | core, postgres, Spring Boot auto-configuration |

`rule-engine-rest` and `rule-engine-test` are added with the first features that need them.

Build and test this engine only, from the repository root: `mvn -B -pl rule-engine/rule-engine-spring-boot-starter -am verify` (the storage integration tests need Docker).
