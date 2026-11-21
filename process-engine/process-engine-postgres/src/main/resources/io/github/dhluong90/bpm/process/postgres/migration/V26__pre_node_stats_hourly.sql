-- Hourly node statistics (#110, PRE-029, DESIGN sections 7 and 8.3). pre_node_stats_hourly holds, per tenant,
-- definition version, hour and node, the counts and a 64-bucket duration histogram that the rollup adds from
-- pre_history; pre_engine_watermark holds the rollup's position, one row per sweep job, with no tenant data.
-- Neither table is written by a step transaction: only the rollup writes them, one slice per transaction, which also
-- moves the watermark, so a repeated slice cannot be counted twice.
-- Additive only: two new tables and a default partition, nothing existing is touched. Monthly partitions of
-- pre_node_stats_hourly are created by partition maintenance as for pre_history; until then rows go to the default
-- partition. Rollback, before any row is written: DROP TABLE pre_node_stats_hourly; DROP TABLE pre_engine_watermark;
-- afterwards the rows are the operational view of past hours and are kept (pre_history stays the audit record).
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_node_stats_hourly (
  tenant_id         TEXT        NOT NULL,
  definition_id     VARCHAR(26) NOT NULL,
  bucket_start      TIMESTAMPTZ NOT NULL,        -- hour, UTC
  node_id           TEXT        NOT NULL,
  entered_count     BIGINT      NOT NULL DEFAULT 0,
  completed_count   BIGINT      NOT NULL DEFAULT 0,
  failed_count      BIGINT      NOT NULL DEFAULT 0,
  incident_count    BIGINT      NOT NULL DEFAULT 0,
  sla_breach_count  BIGINT      NOT NULL DEFAULT 0,
  duration_sum_ms   BIGINT      NOT NULL DEFAULT 0,
  duration_hist     INT[]       NOT NULL,        -- 64 buckets, bucket k covers [10 ms * 2^(k/2), 10 ms * 2^((k+1)/2))
  updated_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, definition_id, bucket_start, node_id),
  CHECK (cardinality(duration_hist) = 64)
) PARTITION BY RANGE (bucket_start);
CREATE TABLE pre_node_stats_hourly_default PARTITION OF pre_node_stats_hourly DEFAULT;

CREATE TABLE pre_engine_watermark (
  job_name        TEXT        PRIMARY KEY,       -- 'node-stats'
  watermark       TIMESTAMPTZ NOT NULL,
  updated_by      TEXT        NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL
);
