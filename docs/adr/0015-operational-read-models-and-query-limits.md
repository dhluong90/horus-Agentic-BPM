# ADR-0015: Operational read models and query limits

- Status: accepted
- Date: 2026-11-18
- Deciders: product owner (PD-038), maintainers
- Module: process-engine, rule-engine, project

## Context
The cockpit direction adds about 15 read endpoints: instance activities, live counts per node, a historical heatmap, task queries and merged lists from many operators. The engines run in the host's database, so one careless query is the host's outage. `pre_history` grows by up to 43 GB a day at a sustained 1,000 transitions per second and is partitioned monthly; a lookup without a time bound probes every partition, and a `GROUP BY` over it on a request path would take the database down. Counters updated inside step transactions would put a hot row lock on the busiest nodes. The product owner chose in-host analytics only: live counts in M2, an hourly rollup in M3, no aggregator (PD-038).

## Decision
1. **Every list query** carries `tenant_id` and either an equality on a leading index column or a time range of at most 31 days; pages use keyset cursors, default 50 and at most 200 items, no `OFFSET`, no exact totals; each read runs in a read-only transaction with `statement_timeout` of 2 s. A CI query-plan check runs `EXPLAIN` for every registered query on a seeded database and fails the build on a sequential scan of a large table or on more partitions than the query's bound.
2. **Activities** of an instance are read from `pre_history` only between the instance's `started_at` and `ended_at` (or now), so partition pruning limits the read to the months the instance lived.
3. **Live counts** per node come from `pre_token`, which carries `definition_id`, through the partial index `idx_pre_token_live (tenant_id, definition_id, status, current_node_id)` on live statuses, plus open incidents per node; results are cached per replica for 5 s, 15 s above 100,000 tokens, and carry `asOf`.
4. **Historical statistics** come from `pre_node_stats_hourly`, filled by the existing scheduler thread from `pre_history` in one-minute slices behind a 5-minute safety lag, with a watermark row updated in the same transaction; counts plus a 64-bucket duration histogram per node and hour, so p50 and p95 merge over any window up to 31 days. The table is derived data, partitioned monthly, retained 13 months by default.
5. **Never** a `GROUP BY` over history on a request path, and never a counter updated inside a step transaction.

## Options considered
1. Bounded read models in the host database (chosen). No data leaves the host; predictable cost; two small tables and one index.
2. Compute statistics on request from history. No new table; cost grows with history and can saturate the host database.
3. Counters updated in each step transaction. Always current; a hot row per busy node, contended at hundreds of updates per second, slowing every instance on that node.
4. An external analytics store or aggregator (PD-035 option C). Unbounded analytics; a separate product with copied data, rejected for now.

Percentiles, within option 1:
- a. Store p50 and p95 per hour. Small, but percentiles of hours cannot be combined into a percentile of a week.
- b. Store a fixed log-scale histogram per hour (chosen). Mergeable over any window; values within 19 percent; 256 bytes per row.

## Consequences
- New column `definition_id` on `pre_token`, `pre_task`, `pre_incident` and `pre_history`, `duration_ms` on `pre_history`, a BRIN index on `pre_history.occurred_at`, tables `pre_node_stats_hourly` and `pre_engine_watermark` (process-engine DESIGN section 7). Migration updates `definition_id` on tokens with the instance.
- The rollup is an engine-internal sweep across tenants, like the job poller; it returns nothing to a caller. The tenancy wording for sweeps is the open M1 question in the architecture overview.
- The rollup is operational: rows from transactions open longer than the safety lag are not counted. The audit record remains `pre_history`.
- `GET /decisions` in the Rule Engine follows the same bounds (one rule set, 31 days).
- Capacity math: process-engine DESIGN section 8. Requirements: `TR-PERF-07` to `TR-PERF-10`. Verified by `PRE-027` to `PRE-030` and `PRE-036`.
