-- The migration dry run and runner ask which instances have a remote call in flight at a removed step (#106, PRE-208):
-- the claimed service or agent jobs of one definition version at given steps. CLAIMED rows are the few jobs being
-- called right now, so the index is partial and small however many jobs a tenant has: idx_pre_timer_inflight
-- (tenant_id, definition_id, node_id, instance_id) WHERE status = 'CLAIMED'. Additive only.
-- Rollback: DROP INDEX CONCURRENTLY idx_pre_timer_inflight.
--
-- pre_timer is written by every step that waits, so the index is built CONCURRENTLY (TR-OPS-01).
-- V41__pre_timer_inflight_index.sql.conf sets executeInTransaction=false, so each statement commits alone. A failed
-- build leaves an INVALID index under its name; the DO block renames such an index, it is dropped CONCURRENTLY and the
-- build starts again, as in V24.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_timer_inflight') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_timer_inflight RENAME TO idx_pre_timer_inflight_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_timer_inflight_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_timer_inflight
  ON pre_timer (tenant_id, definition_id, node_id, instance_id) WHERE status = 'CLAIMED';

RESET lock_timeout;
