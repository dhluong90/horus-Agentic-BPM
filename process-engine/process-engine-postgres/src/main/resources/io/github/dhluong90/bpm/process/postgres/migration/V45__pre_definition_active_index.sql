-- Index of the start-up summary of unclassified agent step fields (#438, PD-146, PRE-205): the active definition
-- versions of every tenant, read once at start-up in (tenant_id, key_name, version_num) order:
--   idx_pre_definition_active  ProcessListQueries.ACTIVE_DEFINITIONS, at most 1,000 rows, no sort
-- The index is partial, so it holds only the versions in effect and stays small however many versions are retired.
--
-- pre_definition is populated on every host, so the index is built CONCURRENTLY (TR-OPS-01). CONCURRENTLY cannot run
-- in a transaction: V45__pre_definition_active_index.sql.conf sets executeInTransaction=false, so each statement
-- commits alone. A failed build leaves an INVALID index under its name; the DO block renames such an index, which is
-- then dropped CONCURRENTLY and built again, as in V35.
-- Rollback: DROP INDEX CONCURRENTLY idx_pre_definition_active. No row changes.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_definition_active') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_definition_active RENAME TO idx_pre_definition_active_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_definition_active_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_definition_active
  ON pre_definition (tenant_id, key_name, version_num) WHERE status = 'ACTIVE';

RESET lock_timeout;
