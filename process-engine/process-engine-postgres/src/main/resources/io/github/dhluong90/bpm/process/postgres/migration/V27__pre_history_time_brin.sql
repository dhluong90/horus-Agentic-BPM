-- Time index of pre_history for the rollup (#110, DESIGN section 7): the rollup reads history in slices of one minute
-- by occurred_at, and a BRIN index over the time column finds those blocks without scanning the table. Additive only.
--
-- pre_history is the hottest table, so the index is built CONCURRENTLY and never blocks a step's insert. PostgreSQL
-- cannot build an index CONCURRENTLY on a partitioned table, so the script builds it on the default partition, then
-- creates the parent index ON ONLY (no rows to read, instant) and attaches the partition index to it. A monthly
-- partition created later gets its own index from the parent. CONCURRENTLY cannot run in a transaction:
-- V27__pre_history_time_brin.sql.conf sets executeInTransaction=false, so each statement commits alone.
--
-- Re-run after a failed build: an interrupted CONCURRENTLY build leaves an INVALID index under its name, which
-- IF NOT EXISTS alone would keep. The DO block renames such an index and the rename is dropped CONCURRENTLY before the
-- build starts again. A valid index is kept. Rollback: DROP INDEX CONCURRENTLY idx_pre_history_time_brin_default;
-- DROP INDEX idx_pre_history_time_brin.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_history_time_brin_default') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_history_time_brin_default RENAME TO idx_pre_history_time_brin_default_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_history_time_brin_default_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_history_time_brin_default
  ON pre_history_default USING brin (occurred_at);

CREATE INDEX IF NOT EXISTS idx_pre_history_time_brin ON ONLY pre_history USING brin (occurred_at);

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid = to_regclass('idx_pre_history_time_brin_default')
                 AND inhparent = to_regclass('idx_pre_history_time_brin')) THEN
    ALTER INDEX idx_pre_history_time_brin ATTACH PARTITION idx_pre_history_time_brin_default;
  END IF;
END
$$;

RESET lock_timeout;
