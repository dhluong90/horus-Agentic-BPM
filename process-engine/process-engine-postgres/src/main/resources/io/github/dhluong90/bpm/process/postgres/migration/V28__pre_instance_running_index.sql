-- Running-instance gauge (#86, PRE-084): horus.pre.instances.active is read by one count per gauge refresh on each
-- replica. A partial index over the RUNNING rows answers it with an index-only scan, so the count never reads the
-- table and does not depend on a tenant's leading index. Additive only: one partial index.
--
-- pre_instance is a hot table, so the index is built CONCURRENTLY outside a transaction
-- (V28__pre_instance_running_index.sql.conf), safe to run again after a failed build as V9 is. Rollback:
-- DROP INDEX CONCURRENTLY idx_pre_instance_running.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_instance_running') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_instance_running RENAME TO idx_pre_instance_running_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_instance_running_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_instance_running
  ON pre_instance (tenant_id) WHERE status = 'RUNNING';

RESET lock_timeout;
