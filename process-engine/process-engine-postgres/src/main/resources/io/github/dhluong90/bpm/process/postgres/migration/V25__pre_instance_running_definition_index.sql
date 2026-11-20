-- The migration dry run (#74, PRE-014) counts the running instances of one definition version and lists those that
-- would move, in id order. pre_instance has no index on definition_id; idx_pre_instance_status reads every running
-- instance of the tenant. This partial index reads only the running instances of the version:
-- (tenant_id, definition_id, id) WHERE status = 'RUNNING'. Additive only. Rollback: DROP INDEX CONCURRENTLY
-- idx_pre_instance_running_definition.
--
-- pre_instance is written by every step transition, so the index is built CONCURRENTLY (TR-OPS-01, DESIGN section 7);
-- V25__pre_instance_running_definition_index.sql.conf sets executeInTransaction=false. A failed build is renamed,
-- dropped and built again, as in V24.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_instance_running_definition')
             AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_instance_running_definition RENAME TO idx_pre_instance_running_definition_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_instance_running_definition_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_instance_running_definition
  ON pre_instance (tenant_id, definition_id, id) WHERE status = 'RUNNING';

RESET lock_timeout;
