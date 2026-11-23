-- Indexes of the pending approvals list when it carries migration plans and calendar changes (#473, PRE-190,
-- DESIGN section 8.1): the proposals that wait for a second person, oldest first, keyset on (proposed_at, id), as
-- idx_pre_definition_pending_page does for definition versions (V35):
--   idx_pre_migration_proposed_page         plans in PROPOSED
--   idx_pre_calendar_change_pending_page    calendar changes in PENDING
-- Both are partial, so they hold only what waits and stay small. The tables are populated on every host, so each index
-- is built CONCURRENTLY (TR-OPS-01); V49__pre_pending_approvals_indexes.sql.conf sets executeInTransaction=false. A
-- failed build leaves an INVALID index under its name; the DO block renames it, it is dropped CONCURRENTLY and built
-- again, as in V35.
-- Rollback: DROP INDEX CONCURRENTLY idx_pre_migration_proposed_page; DROP INDEX CONCURRENTLY
-- idx_pre_calendar_change_pending_page.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_migration_proposed_page') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_migration_proposed_page RENAME TO idx_pre_migration_proposed_page_invalid;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_calendar_change_pending_page') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_calendar_change_pending_page RENAME TO idx_pre_calendar_change_pending_page_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_migration_proposed_page_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_calendar_change_pending_page_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_migration_proposed_page
  ON pre_migration (tenant_id, proposed_at, id) WHERE status = 'PROPOSED';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_calendar_change_pending_page
  ON pre_calendar_change (tenant_id, proposed_at, id) WHERE status = 'PENDING';

RESET lock_timeout;
