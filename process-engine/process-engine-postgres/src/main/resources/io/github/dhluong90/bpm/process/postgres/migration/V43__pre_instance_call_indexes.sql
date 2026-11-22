-- Indexes of the child link of V42 (#92, PRE-212, PRE-213):
--   uq_pre_instance_call   one child per (tenant, parent token, call attempt): a parent step that runs twice, or two
--                          replicas that fire the same job, can never start a second child for the same attempt;
--   idx_pre_instance_parent the children of one parent, which a cancel of the parent reads to cancel them, and the
--                          cockpit reads to show them.
-- Both are partial over the instances that were called, so they stay empty for hosts that never call a child.
-- Additive only. Rollback: DROP INDEX CONCURRENTLY uq_pre_instance_call, idx_pre_instance_parent.
--
-- pre_instance is written on every start and step, so both indexes are built CONCURRENTLY (TR-OPS-01).
-- V43__pre_instance_call_indexes.sql.conf sets executeInTransaction=false, so each statement commits alone. A failed
-- build leaves an INVALID index under its name; each DO block renames such an index, it is dropped CONCURRENTLY and the
-- build starts again, as in V24.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('uq_pre_instance_call') AND NOT indisvalid) THEN
    ALTER INDEX uq_pre_instance_call RENAME TO uq_pre_instance_call_invalid;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_instance_parent') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_instance_parent RENAME TO idx_pre_instance_parent_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS uq_pre_instance_call_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_instance_parent_invalid;

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_pre_instance_call
  ON pre_instance (tenant_id, parent_token_id, call_attempt) WHERE parent_token_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_instance_parent
  ON pre_instance (tenant_id, parent_instance_id, id) WHERE parent_instance_id IS NOT NULL;

RESET lock_timeout;
