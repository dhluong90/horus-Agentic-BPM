# Rule Engine — Design

Status: architecture baseline (M0). Conforms to `ADR-0001` through `ADR-0006` and `wiki/Technical-Requirements.md`.

## 1. Context, Boundaries & Explicit Non-Goals

`horus-rule-engine` is an embeddable, deterministic decision-table library for Java 21 / Spring Boot 3.4+ hosts running against PostgreSQL 14+.

- **Inbound callers:** Host Java services (`RuleEngine` interface), optional REST controllers (`/api/bpm/rules/v1`), and the static Operations Cockpit.
- **Outbound dependencies:** Host `DataSource` / `PlatformTransactionManager` via Spring `JdbcClient`, host `SecurityContext` (`TenantResolver`, `CallerResolver`), host event publisher (`RuleEventPublisher`), and `MeterRegistry` / optional OpenTelemetry `Tracer`.
- **State ownership:** Exclusively owns tables prefixed `rle_` (configurable via `horus.bpm.rules.schema-prefix`) and its isolated Flyway table `rle_schema_history`.
- **Explicit non-goals:** No standalone server (`ADR-0004`), no JPA/Hibernate, no SpEL/MVEL/Groovy scripting (`ADR-0003`, `TR-SEC-01`), no network I/O (`TR-EMB-05`), no cross-tenant inheritance at evaluation time.

## 2. Module Decomposition & Dependency DAG

```text
rule-engine-core                  (Java 21 + Jackson only; zero Spring, zero java.sql, zero java.net)
  ▲
  ├── rule-engine-postgres        (Spring JDBC JdbcClient, Flyway rle_*, LISTEN/NOTIFY + epoch cache)
  ├── rule-engine-rest            (Spring WebMvc controllers, RFC 9457 Problem Details, OpenAPI spec)
  ├── rule-engine-test            (In-memory ports, fixed Clock, JUnit 5 @RuleSetTest, coverage report)
  ▲
rule-engine-spring-boot-starter   (@AutoConfiguration composition root, Micrometer, property binding)
```

- **Enforced by ArchUnit (`TR-EMB-03`, `TR-EMB-05`, `TR-SEC-01`):**
  1. `rule-engine-core` must not import `org.springframework..`, `java.sql..`, `java.net..`, `javax.script..`, or `java.lang.reflect.Method`.
  2. `rule-engine-postgres` must not import HTTP clients or `java.net.http..`.

## 3. Domain Model & Expression Compiler (`rule-engine-core`)

### 3.1 Immutable Rule Set & AST
- `RuleSetDefinition` (`record`): `ruleSetName`, `version`, `hitPolicy` (`FIRST | UNIQUE | COLLECT`), `collectAggregate` (`NONE | SUM | MIN | MAX | COUNT`), `effectiveFrom`, `inputs` (`List<InputSchema>`), `outputs` (`List<OutputSchema>`), `rules` (`List<RuleRow>`), `canonicalHash` (SHA-256).
- `Expr` (`sealed interface`):
  - `LiteralExpr(ValueType type, Object value)`
  - `VarRefExpr(int slotIndex, String name, ValueType type)` *(bound to slot index at compile time)*
  - `UnaryExpr(UnaryOp op, Expr operand)`
  - `BinaryExpr(BinaryOp op, Expr left, Expr right)`
  - `InListExpr(Expr target, List<Expr> elements, boolean negated)`
  - `BetweenExpr(Expr target, Expr low, Expr high)`
  - `IsNullExpr(Expr target, boolean negated)`
  - `CallExpr(String functionName, List<Expr> args, PureFunction impl)`
- `Money` (`record(String currency, long minorUnits, int scale)`): currency comparisons reject mismatched ISO codes (`TR-DET-03`); `money * decimal` uses `RoundingMode.HALF_EVEN`; `money / money` is rejected at publish time.

### 3.2 Two-Phase Compilation & Slot-Indexed Evaluation
1. **Compile & Static Type-Check (Save / Publish):**
   - Recursive-descent parser builds `Expr` tree without reflection.
   - Type-checker binds every identifier to an integer input slot `0..K-1`, verifies operand types (`string`, `integer`, `decimal`, `boolean`, `date`, `datetime`, `money`, `list<T>`), validates `RuleFunctionRegistry` signatures, pre-compiles bounded regexes, and computes static reachability / overlap warnings.
   - Invalid expressions fail validation and cannot transition to `PENDING_APPROVAL` or `PUBLISHED` (`TR-DET-02`).
2. **Runtime Evaluation (`< 100 µs` hot path):**
   - Map `DecisionInput` into `Object[] slots` once; evaluate compiled `CompiledRuleSet` tree with zero regex compilation and zero `HashMap` lookups per row.
   - Pass explicit `Clock` / `Instant evaluationTime` so `today()` and `now()` are deterministic (`TR-DET-01`, `TR-DET-05`).
   - Regex evaluation wraps input in a bounded `InterruptibleCharSequence` enforcing `evaluation.regex-timeout-ms` (`50 ms` default, `TR-SEC-02`).

### 3.3 Version Lifecycle State Machine

| From State | Command / Trigger | Guard | To State | Side Effect / Event |
|---|---|---|---|---|
| *(none)* | `createDraft` / `importYaml` | Valid schema & tenant | `DRAFT` | Assign sequential `version`, store canonical JSON + hash |
| `DRAFT` | `updateDraft` | `version` matches optimistic lock | `DRAFT` | Re-validate AST; reject if parse/type errors on publish prep |
| `DRAFT` | `requestPublish(effectiveFrom)` | Zero validation errors; valid `Idempotency-Key` | `PENDING_APPROVAL` | Freeze snapshot; record `requested_by` |
| `PENDING_APPROVAL` | `approve(comment)` | `approverId != requestedBy` (`TR-SEC-04`); `rules:approve` | `PUBLISHED` | Set `published_at`, `approved_by`; emit `rules.version.published` + `pg_notify` + increment `cache_epoch` |
| `PENDING_APPROVAL` | `reject(reason)` | `rules:approve` | `DRAFT` | Record rejection comment; allow edits |
| `PUBLISHED` | Newer version becomes effective | `new.effectiveFrom <= now` | `RETIRED` | Emit `rules.version.retired`; keep readable forever for explain/replay |
| `PUBLISHED` | `retire` | `rules:approve` (if maker-checker enabled) | `RETIRED` | Emit `rules.version.retired` + invalidate cache |

## 4. Public Java SPI & REST Contract Summary

### 4.1 Java API (`rule-engine-core`)
```java
public interface RuleEngine {
  DecisionResult evaluate(String ruleSet, DecisionInput input);
  DecisionResult evaluate(String ruleSet, int version, DecisionInput input);
  DecisionResult evaluate(DecisionRequest request); // tenant override for batch, clock, idempotencyKey, correlationId
  <T> T evaluateAs(String ruleSet, DecisionInput input, Class<T> outputType);
}
```
Outbound SPIs (`@ConditionalOnMissingBean` in starter): `TenantResolver`, `CallerResolver`, `RuleFunctionRegistry`, `RuleEventPublisher`, `Clock`.

### 4.2 REST Endpoints (`/api/bpm/rules/v1`)
| Endpoint | Permission | Idempotency / Concurrency | Error Codes |
|---|---|---|---|
| `POST /decisions/{ruleSet}:evaluate` | `rules:evaluate` | Optional `Idempotency-Key` | `400 INPUT_INVALID`, `404 NO_EFFECTIVE_VERSION`, `409 UNIQUE_HIT_VIOLATION`, `422 EVALUATION_ERROR` |
| `POST /decisions/{ruleSet}/versions/{v}:evaluate` | `rules:simulate` (if draft or custom `clock`) / `rules:evaluate` | Optional `Idempotency-Key` | `403`, `404`, `409`, `422` |
| `POST /decisions/{ruleSet}:evaluate-batch` | `rules:evaluate` | Max 1,000 items; `Idempotency-Key` | `400`, `404`, `422` |
| `GET /rule-sets`, `GET /rule-sets/{name}` | `rules:read` | Cursor/bounded pagination | `404 RULE_SET_NOT_FOUND` |
| `POST /rule-sets`, `POST /rule-sets/{name}/versions`, `PUT /rule-sets/{name}/versions/{v}` | `rules:author` | `Idempotency-Key` on POST; `version` lock on PUT | `400`, `409 VERSION_CONFLICT` |
| `POST /rule-sets/{name}/versions/{v}:request-publish` | `rules:author` | `Idempotency-Key` | `422 VALIDATION_FAILED` |
| `POST /rule-sets/{name}/versions/{v}:approve` / `:reject` / `:retire` | `rules:approve` | `Idempotency-Key`; enforces `approver != requester` | `403 SELF_APPROVAL_FORBIDDEN`, `409 INVALID_STATE` |
| `GET /decisions/{id}`, `GET /decisions/{id}/explain`, `GET /decisions` | `rules:log:read` | Cursor pagination; masked inputs remain hashed | `404 DECISION_NOT_FOUND` |

All errors return RFC 9457 `application/problem+json` with `code`, `detail`, and `correlationId`.

## 5. Storage Model (`rule-engine-postgres`)

Every table carries `tenant_id` first in all unique constraints and indexes (`TR-TEN-01`), ULID (`VARCHAR(26)`) IDs, and `TIMESTAMPTZ` UTC timestamps.

```sql
CREATE TABLE rle_rule_set (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  name            TEXT NOT NULL,
  description     TEXT,
  cache_epoch     BIGINT NOT NULL DEFAULT 1,
  created_at      TIMESTAMPTZ NOT NULL,
  UNIQUE (tenant_id, name)
);

CREATE TABLE rle_version (
  id                VARCHAR(26) PRIMARY KEY,
  tenant_id         TEXT NOT NULL,
  rule_set_id       VARCHAR(26) NOT NULL REFERENCES rle_rule_set(id),
  version_num       INT NOT NULL,
  status            TEXT NOT NULL, -- DRAFT | PENDING_APPROVAL | PUBLISHED | RETIRED
  hit_policy        TEXT NOT NULL, -- FIRST | UNIQUE | COLLECT
  effective_from    TIMESTAMPTZ,
  canonical_json    JSONB NOT NULL,
  canonical_hash    CHAR(64) NOT NULL,
  lock_version      BIGINT NOT NULL DEFAULT 0,
  requested_by      TEXT,
  requested_at      TIMESTAMPTZ,
  approved_by       TEXT,
  approved_at       TIMESTAMPTZ,
  approval_comment  TEXT,
  created_at        TIMESTAMPTZ NOT NULL,
  UNIQUE (tenant_id, rule_set_id, version_num)
);
CREATE INDEX idx_rle_version_effective
  ON rle_version (tenant_id, rule_set_id, status, effective_from DESC);

CREATE TABLE rle_rule (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  version_id      VARCHAR(26) NOT NULL REFERENCES rle_version(id),
  row_order       INT NOT NULL,
  rule_id         TEXT NOT NULL,
  when_expr_json  JSONB NOT NULL,
  then_expr_json  JSONB NOT NULL,
  UNIQUE (tenant_id, version_id, rule_id)
);

CREATE TABLE rle_decision (
  id               VARCHAR(26) NOT NULL,
  tenant_id        TEXT NOT NULL,
  rule_set_name    TEXT NOT NULL,
  version_num      INT NOT NULL,
  hit_policy       TEXT NOT NULL,
  outcome          TEXT NOT NULL, -- OK | UNIQUE_HIT_VIOLATION | EVALUATION_ERROR
  error_code       TEXT,
  masked_inputs    JSONB NOT NULL, -- inputs with mask=true stored as keyed HMAC-SHA256
  outputs          JSONB,
  matched_rule_ids JSONB NOT NULL,
  caller_id        TEXT,
  correlation_id   TEXT,
  eval_clock       TIMESTAMPTZ NOT NULL,
  duration_micros  BIGINT NOT NULL,
  log_mode         TEXT NOT NULL, -- TRANSACTIONAL | ASYNC
  created_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, created_at, id)
) PARTITION BY RANGE (created_at);
CREATE INDEX idx_rle_decision_lookup
  ON rle_decision (tenant_id, rule_set_name, created_at DESC, outcome);

CREATE TABLE rle_decision_outbox (
  id              VARCHAR(26) PRIMARY KEY,
  tenant_id       TEXT NOT NULL,
  payload         JSONB NOT NULL,
  status          TEXT NOT NULL, -- PENDING | CLAIMED | SENT
  locked_until    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_rle_outbox_poll
  ON rle_decision_outbox (tenant_id, status, created_at) WHERE status IN ('PENDING', 'CLAIMED');

CREATE TABLE rle_idempotency (
  tenant_id       TEXT NOT NULL,
  operation       TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_hash    CHAR(64) NOT NULL,
  status_code     INT NOT NULL,
  response_body   JSONB NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, operation, idempotency_key)
);
CREATE INDEX idx_rle_idempotency_expiry ON rle_idempotency (expires_at);
```

## 6. Runtime Mechanics & 10x Capacity Math

- **Multi-Replica Cache Invalidation:** On `approve` / `retire`, increment `rle_rule_set.cache_epoch` in the same TX and emit `pg_notify('horus_rle_cache', ...)`. A background listener invalidates local `Caffeine` cache immediately; as a PgBouncer-safe fallback, replicas verify `cache_epoch` periodically (default 5s TTL) or when an explicit version pin misses locally.
- **10x Latency & Pool Budget (`TR-PERF-01`, `TR-PERF-03`):**
  - Compiled AST evaluation of 200 rows: ~15–40 µs in-JVM (target `< 100 µs` p99), zero DB round-trips during evaluation once cached.
  - Transactional log write adds exactly `1 INSERT` into `rle_decision` (~0.5–1.5 ms inside host TX). At 2,000 decisions/sec per pod with 1.5 ms log insert, DB connection concurrency is ~3 active connections — comfortably within a standard 20-conn HikariCP pool.
  - `rle_decision` is range-partitioned monthly (`TR-OPS-04`) so retention purges are instant `DROP TABLE` metadata operations without vacuum bloat.

## 7. Open Questions
- **Maven `groupId`:** Confirm `com.horus.bpm` vs `io.github.dhluong90.bpm` before Sonatype Central Portal registration (Owner: Product Owner, Gate: before M1 publish).
- **Default async outbox flush cadence:** Tune batch size (100 vs 500) during M1 JMH + Testcontainers load benchmarks (Owner: `backend-engineer`, Gate: M1 exit).
