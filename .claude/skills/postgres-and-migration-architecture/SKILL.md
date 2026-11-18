---
name: postgres-and-migration-architecture
description: "Design multi-tenant PostgreSQL 14+ schemas, tenant_id leading indexes, ULID primary keys, Zero-Downtime Expand-Contract Flyway migrations, SELECT ... FOR UPDATE SKIP LOCKED job/timer queues with leases, LISTEN/NOTIFY + version-epoch cache invalidation, and monthly table partitioning. Use when designing SQL DDL, Flyway migrations, high-concurrency queries, or multi-replica cache coherence. Adapted from affaan-m/ECC (postgres-patterns & database-migrations) and softaworks/agent-toolkit (database-schema-designer)."
---

# PostgreSQL & Zero-Downtime Migration Architecture

Design relational schemas and forward-only Flyway migrations that run safely inside a host service's PostgreSQL 14+ database across multiple application replicas without lock outages or cross-tenant leaks.

## When to Use
- Designing or reviewing `rle_*`, `pre_*`, or accelerator database schemas.
- Writing Flyway migrations (`V001__...sql`) or planning zero-downtime schema changes (`TR-OPS-01`, `TR-OPS-02`).
- Designing replica-safe timers, async job queues (`agentTask`), idempotency tables, or multi-pod cache invalidation.

---

## 1. Multi-Tenant Banking Schema Standards
1. **`tenant_id` Leading Indexes (`TR-TEN-01`):**
   - Every table (except global static lookup tables if any) MUST carry `tenant_id TEXT NOT NULL`.
   - Every unique constraint and query index MUST place `tenant_id` **first**:
     ```sql
     CREATE UNIQUE INDEX uq_rle_rule_set_tenant_name ON rle_rule_set (tenant_id, name);
     CREATE INDEX idx_pre_task_tenant_status_assignee ON pre_task (tenant_id, status, assignee_id);
     ```
2. **Primary Keys & Types:**
   - **IDs:** `VARCHAR(26)` / `TEXT` ULID (monotonically increasing per millisecond → B-tree append-friendly, no UUIDv4 page splits, replica-safe without DB sequences).
   - **Timestamps:** Always `TIMESTAMPTZ` (UTC), never `TIMESTAMP` without time zone.
   - **Money:** `BIGINT` minor units + `CHAR(3)` ISO currency code (or `NUMERIC(19, 4)` when sub-cent precision is required by product config). Never `REAL` or `DOUBLE PRECISION`.
   - **JSON Snapshots:** `JSONB` for canonical definitions, masked input hashes, and context variables (with explicit GIN index only where queried).
3. **Engine Isolation (`ADR-0002`):**
   - Embeddable engines prefix all tables (`rle_*`, `pre_*`) and use separate Flyway history tables (`rle_schema_history`, `pre_schema_history`) inside the host `DataSource`. Never open a separate connection pool.

---

## 2. Replica-Safe Queues, Timers & Locks

### Timer / Async `agentTask` Claiming (`SKIP LOCKED` + Lease)
Never poll rows without locking, and never hold a row lock during an HTTP/LLM call:
```sql
-- Step 1 (Short TX): Claim batch with lease
WITH claimed AS (
  SELECT id
  FROM pre_timer
  WHERE tenant_id = :tenantId
    AND status IN ('DUE', 'CLAIMED')
    AND next_fire_at <= :now
    AND (locked_until IS NULL OR locked_until < :now)
  ORDER BY next_fire_at ASC
  LIMIT :batchSize
  FOR UPDATE SKIP LOCKED
)
UPDATE pre_timer t
SET status = 'CLAIMED',
    locked_by = :replicaId,
    locked_until = :now + INTERVAL '60 seconds',
    attempt = attempt + 1
FROM claimed
WHERE t.id = claimed.id
RETURNING t.*;
```
- **Lease Expiration Recovery:** If a pod crashes mid-execution, another replica automatically reclaims expired leases (`locked_until < :now`) after lease expiry without manual intervention.
- **Keep `SKIP LOCKED` TX sub-5ms:** Commit the claim TX *before* executing any external `agentTask` / HTTP call.

### Parallel Gateway Join Safety
When concurrent branches on different pods finish at the same instant, lock the parent process instance row before evaluating join readiness:
```sql
SELECT id, version FROM pre_instance WHERE tenant_id = :tenantId AND id = :instanceId FOR UPDATE;
```

---

## 3. Multi-Replica Cache Invalidation (`LISTEN/NOTIFY` + Epoch Fallback)
Compiled rule versions and process definitions live in JVM memory per replica. Without a message broker:
1. On `publish` / `retire` commit, emit `SELECT pg_notify('horus_bpm_cache_invalidation', json_build_object('tenantId', :tenantId, 'ruleSet', :name, 'epoch', :epoch)::text)`.
2. A single daemon listener connection (optional or polling fallback) invalidates the local Caffeine/JVM cache entry immediately.
3. **PgBouncer / Connection-Pooler Resilience:** Because `LISTEN/NOTIFY` does not work across transaction-mode PgBouncer, combine it with a **lightweight `cache_epoch` table/row** checked on a short TTL (e.g., every 5 seconds or background refresh) so replicas never remain stale past the configured bound.

---

## 4. Zero-Downtime Migrations (Expand-Contract) & Partitioning

### Non-Negotiable Flyway Rules (`TR-OPS-01`, `TR-OPS-02`)
1. **Forward-only & Immutable:** Never modify a migration already deployed. Rollbacks are new forward migrations (or documented recovery SQL for destructive drops).
2. **One-Minor-Release Compatibility Window:** During rolling upgrades, old pods (`v1.0`) and new pods (`v1.1`) share the database simultaneously:
   - **Expand:** Add nullable columns or columns with non-volatile `DEFAULT` (PG 11+ metadata-only, fast, though still acquires brief `ACCESS EXCLUSIVE` — always set `SET LOCAL lock_timeout = '2s'`).
   - **Dual-Write & Backfill:** Deploy app that writes both old and new columns; backfill existing rows in bounded primary-key batches (`LIMIT 10000`) in separate transactions.
   - **Contract:** Drop legacy columns/constraints only in a *subsequent* release after old pods are drained.
3. **Non-Blocking Indexes:** Large populated tables must use `CREATE INDEX CONCURRENTLY` (configured as non-transactional Flyway migration `-- flyway:executeInTransaction=false`).
4. **Monthly Partitioning (`TR-OBS-01`, `TR-OPS-04`):** Append-only high-volume tables (`rle_decision`, `pre_history`, audit/journal tables) should support declarative `PARTITION BY RANGE (created_at)` (inclusion of partition key in PK/unique constraints required by Postgres).

## HORUS Program Integration
- Governs Flyway DDL design in `horus-Agentic-BPM` (`rle_*`, `pre_*`) and every Banking Accelerator (`platform/*`, `accelerators/*`).
- Every table added to `DESIGN.md` must explicitly list its primary key, `tenant_id`-leading indexes, lock mode / migration safety notes, and retention/partitioning strategy.
