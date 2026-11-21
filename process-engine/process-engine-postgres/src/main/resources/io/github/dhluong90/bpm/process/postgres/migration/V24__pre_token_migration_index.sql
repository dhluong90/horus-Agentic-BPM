-- The migration dry run (#74, PRE-014) lists the running instances whose live tokens wait at a step the target version
-- does not have, in instance id order and at most migration.dry-run-sample-size of them. idx_pre_token_live
-- (tenant_id, definition_id, status, current_node_id) counts tokens per step but cannot return instance ids in order
-- without reading every token of the step. This index can: (tenant_id, definition_id, current_node_id, instance_id)
-- over the same live statuses. Additive only. Rollback: DROP INDEX CONCURRENTLY idx_pre_token_live_node_instance.
--
-- pre_token is written by every step transition, so the index is built CONCURRENTLY and never holds a lock that blocks
-- them (TR-OPS-01, DESIGN section 7). V24__pre_token_migration_index.sql.conf sets executeInTransaction=false, so each
-- statement commits alone. A build that failed or was interrupted leaves an INVALID index under this name; the DO block
-- renames such an index (SHARE UPDATE EXCLUSIVE, which does not block reads or writes), it is dropped CONCURRENTLY, and
-- the build starts again. A valid index under this name is kept.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_token_live_node_instance')
             AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_token_live_node_instance RENAME TO idx_pre_token_live_node_instance_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_token_live_node_instance_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_token_live_node_instance
  ON pre_token (tenant_id, definition_id, current_node_id, instance_id)
  WHERE status IN ('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT');

RESET lock_timeout;
