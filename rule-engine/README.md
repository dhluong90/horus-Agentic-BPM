# Rule Engine

Code: `RLE` · Version: 0.1.0-SNAPSHOT · Status: pre-alpha

Embeddable decision library: decision tables, versioned rule sets, safe expression language, decision API and log, simulation.

Documents: `docs/DESIGN.md`, `docs/SPEC.md`, `docs/TEST-PLAN.md`, `docs/adr/`. Public documentation is in the project wiki.

## Modules

| Module | Contents today | Depends on |
|---|---|---|
| `rule-engine-core` | `com.horus.bpm.rules.api`: storage names the engine owns. Model, compiler and evaluator arrive with the Sprint 1 features | JDK only (ArchUnit enforced) |
| `rule-engine-postgres` | `V1__rle_baseline.sql` (all `rle_*` tables, monthly partitioned `rle_decision`), `RuleEngineSchemaMigrator` (Flyway on the host `DataSource`, history `rle_schema_history`), `RuleEngineTransactions` | core, Spring TX, Flyway |
| `rule-engine-spring-boot-starter` | `RuleEngineAutoConfiguration`, `horus.bpm.rules.*` properties, `ruleEngine` health contributor | core, postgres, Spring Boot 4 |

`rule-engine-rest` and `rule-engine-test` are added with the first features that need them.

Build and test this engine only, from the repository root: `mvn -B -pl rule-engine/rule-engine-spring-boot-starter -am verify` (the storage integration tests need Docker).
