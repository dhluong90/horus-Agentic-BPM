-- Index of the pending approvals list (#31, PRE-190, DESIGN section 8.1): the definition versions of a tenant that wait
-- for a second person, oldest request first, keyset on (requested_at, id):
--   idx_pre_definition_pending_page  GET /approvals, ordered by the index itself, no sort
-- The V7 index idx_pre_definition_pending (tenant_id, requested_at) has no tie-break on id, so a page that ends inside
-- a group of requests made at one instant could not resume from the index. It stays for replicas of the previous
-- release during a rolling upgrade and may be dropped in a later release. The new index is partial, so it holds only
-- the versions that wait and stays small however many versions a tenant has approved.
--
-- pre_definition is populated on every host, so the index is built CONCURRENTLY (TR-OPS-01). CONCURRENTLY cannot run
-- in a transaction: V35__pre_definition_pending_page_index.sql.conf sets executeInTransaction=false, so each statement
-- commits alone. A failed build leaves an INVALID index under its name; the DO block renames such an index, which is
-- then dropped CONCURRENTLY and built again, as in V30.
-- Rollback: DROP INDEX CONCURRENTLY idx_pre_definition_pending_page.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_definition_pending_page') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_definition_pending_page RENAME TO idx_pre_definition_pending_page_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_definition_pending_page_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_definition_pending_page
  ON pre_definition (tenant_id, requested_at, id) WHERE status = 'PENDING_APPROVAL';

RESET lock_timeout;
