-- Retention of the hourly node statistics (#410, PRE-200, DESIGN sections 7 and 8.3). The rollup run deletes hours whose
-- start is older than stats.rollup-retention, a bounded batch at a time, by bucket_start alone across tenants. The primary
-- key leads with tenant_id, so without this index that delete would read the whole table.
--   idx_pre_node_stats_hourly_bucket  DELETE ... WHERE bucket_start < ? LIMIT n (engine sweep, never a caller's read)
-- It is a BRIN index: the rollup adds hours in time order, so each block range holds neighbouring hours and the index is
-- a few pages whatever the table holds, and a window read by (tenant, definition, hour range) keeps using the primary key.
-- Additive only. pre_node_stats_hourly is derived data that only the rollup writes, never a step transaction, and the
-- statement takes a SHARE lock for the build: the next rollup slice waits for it (or, past lock_timeout, fails alone and
-- repeats at the next run). The table holds one row per node and hour, so the build is short.
-- Rollback: DROP INDEX idx_pre_node_stats_hourly_bucket. It holds no data; retention then reads the table in full.
SET LOCAL lock_timeout = '5s';

CREATE INDEX IF NOT EXISTS idx_pre_node_stats_hourly_bucket ON pre_node_stats_hourly USING brin (bucket_start);
