# Architecture

## Libraries, not servers
Each engine is a Spring Boot starter. It runs inside your service's JVM, uses your service's `DataSource`, and creates its tables under a prefixed schema (`rle_` for rules, `pre_` for processes) through its own Flyway migrations. There is no separate engine process to deploy, scale or secure.

```
┌──────────────── your Spring Boot service ────────────────┐
│  your code ──▶ RuleEngine API        ProcessEngine API   │
│                 │                      │                 │
│                 ▼                      ▼                 │
│            rle_* tables           pre_* tables           │
│                 └──── your PostgreSQL ──┘                │
└──────────────────────────────────────────────────────────┘
          ▲ REST (optional)           ▲ REST (optional)
      ┌───┴──────────────────────────┴───┐
      │          Cockpit (React)         │  embedded in your admin UI or standalone
      └──────────────────────────────────┘
```

## Modules
| Module | Artifact | Depends on |
|---|---|---|
| `rule-engine` | `com.horus.bpm:rule-engine-core`, `rule-engine-spring-boot-starter` | Spring, JDBC, Flyway |
| `process-engine` | `com.horus.bpm:process-engine-core`, `process-engine-spring-boot-starter` | Spring, JDBC, Flyway; calls Rule Engine only through its public API when a process needs a decision |
| `cockpit` | `com.horus.bpm:cockpit` (static assets) and `cockpit-spring-boot-starter` | the engines' REST endpoints |

Rule Engine never depends on Process Engine. Use either alone.

## Inside the Rule Engine
- **Definition model**: rule sets, versions, rules, typed inputs and outputs. Stored as rows, exportable as YAML.
- **Compiler**: parses expressions once per version into an evaluation tree; invalid expressions fail at publish time, not at evaluation time.
- **Evaluator**: pure, deterministic, no I/O. Same inputs and version produce the same outputs.
- **Decision log writer**: appends in the caller's transaction by default, or asynchronously when configured.
- **Version service**: lifecycle, effective dates, maker-checker.
- **REST layer** (optional): evaluation, authoring and log endpoints for the cockpit and remote callers.

## Inside the Process Engine (milestone 2)
Definition model (JSON/YAML, versioned), instance state machine, task service (human, service, agent), timer service (database-backed, no external scheduler), history. See [[Process Engine Overview]].

## Design rules
1. Deterministic evaluation; everything that touches the outside world lives in the host service, not in the engine.
2. No arbitrary code execution anywhere in definitions.
3. Every state change is recorded. Nothing in the log is updated or deleted.
4. Multi-tenant at the row level; tenant comes from the host's security context.
5. Permissively licensed dependencies only.
