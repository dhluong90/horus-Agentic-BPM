# Guide: embedding in Spring Boot

Add one starter per engine to your Spring Boot service and set properties under `horus.bpm`. The engines then run inside your service, in your transactions, on your PostgreSQL. The walkthrough with working code is [[Getting Started: your own service|Getting-Started-Your-Own-Service]].

## What you add

| Dependency (`io.github.dhluong90.bpm`) | Gives you |
|---|---|
| `process-engine-spring-boot-starter` | The Process Engine, its REST API under `/api/bpm/processes/v1`, its `pre_*` tables |
| `rule-engine-spring-boot-starter` | The Rule Engine, its REST API under `/api/bpm/rules/v1`, its `rle_*` tables |
| `cockpit-spring-boot-starter` | The cockpit at `/bpm/cockpit` ([[Cockpit: embed, approve, operate|Cockpit]]) |
| `rule-engine-test` (test scope) | `@RuleSetTest` ([[Guide: testing|Guide-Testing]]) |

Either engine works alone. You also need a `DataSource`, Spring Security (the host signs users in), and `flyway-database-postgresql`. The engines run their own migrations.

## Properties you set first

| Property | Default | Meaning |
|---|---|---|
| `horus.bpm.host-id` | unset | Name of this service, shown in the cockpit |
| `horus.bpm.tenancy.dev-tenant` | unset | Fixed tenant for local work. `dev` profile only |
| `horus.bpm.tenancy.claim` | `tenant_id` | The token claim or principal attribute that holds the tenant |
| `horus.bpm.rules.security.role-permissions` | none | Map of role to rule permissions, for example `ROLE_PRICING_APPROVER: [rules:read, rules:approve]` |
| `horus.bpm.processes.security.role-permissions` | none | The same, for process permissions |
| `horus.bpm.processes.enabled` | `true` | Turn the Process Engine off |
| `horus.bpm.processes.migrate-on-start` | `true` | Run the engine's own migrations at start-up |
| `horus.bpm.processes.database.owner-url`, `.owner-username`, `.owner-password` | unset | The schema owner's connection. Set the username to migrate as this role while requests use the host's `DataSource`, a role that owns nothing. The password comes from a secret |
| `horus.bpm.processes.database.app-role` | unset | The role the host's `DataSource` connects as; after each migration the engine grants it data rights on the `pre_*` tables and takes `UPDATE` and `DELETE` away on the append-only ones |
| `horus.bpm.processes.database.role-check` | `warn` (`off` in `dev`) | `warn` logs, `enforce` stops the start, `off` skips: whether the request role owns a `pre_*` table, holds `TRIGGER` on one or is a superuser |
| `horus.bpm.processes.definitions.locations` | none | BPMN files to import at start-up |
| `horus.bpm.processes.definitions.startup-tenants` | none | Tenants for that import. Empty means no import |
| `horus.bpm.processes.definitions.auto-approve` | `false` | Import as `ACTIVE` without approval. `dev` profile only |
| `horus.bpm.processes.tasks.form-resource-root` | `forms/` | Classpath folder of form files |
| `horus.bpm.processes.idempotency-retention` | `PT24H` | How long an `Idempotency-Key` is remembered |

Every other property has a default and its own table: rules in [[Rule Engine Configuration]], processes in the settings tables of [[Process Engine API]] and [[Process Engine Overview]], tenants in [[Multi-Tenancy and Security]]. Settings that relax a control (`dev-tenant`, `auto-approve`) make the service refuse to start outside the `dev` profile. Do not set them in production.

The rule-set start-up loader some earlier pages described does not exist: rule sets enter through `POST /rule-sets` (or `VersionService` in Java) and are published by two people. The Process Engine alone imports BPMN files at start-up.

## Jackson

The engines read and write their payloads with their own JSON library. Your `spring.jackson.*` settings change your endpoints only. Engine endpoints always use camelCase names and ISO 8601 times.

## Install order

Each engine keeps its migrations in its own history table (`rle_schema_history`, `pre_schema_history`) and never reads or writes the host's `flyway_schema_history`.

| Situation | What happens | What you do |
|---|---|---|
| New service, host Flyway and engine together | The host's Flyway runs first, then the engine records a baseline at version 0 and applies its migrations | Nothing |
| Existing database with host tables, engine added | The engine baselines its own history at version 0 below its first migration, so every engine migration runs | Nothing |
| Engine tables exist, host adds Flyway later | Flyway refuses a schema that is not empty but has no history table | For that first run set `spring.flyway.baseline-on-migrate=true` and `spring.flyway.baseline-version=0`. With Spring's default baseline version `1`, Flyway would record your own `V1` as the baseline and never run it |
