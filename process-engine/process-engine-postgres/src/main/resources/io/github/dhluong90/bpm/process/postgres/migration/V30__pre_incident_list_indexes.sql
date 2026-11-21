-- Indexes of the incident list and of the unresolved incidents of one instance (#68, DESIGN section 8.1):
--   idx_pre_incident_status      GET /incidents?status=..., newest first, keyset on (created_at, id)
--   idx_pre_incident_unresolved  the OPEN and RESOLUTION_PENDING incidents of one instance: the instance read, the
--                                retry and resolution lookups by token, and the cancel that closes them
-- The V13 partial indexes on status = 'OPEN' stay for replicas of the previous release during a rolling upgrade and
-- may be dropped in a later release.
--
-- pre_incident is populated on every host that runs async steps, so both indexes are built CONCURRENTLY
-- (TR-OPS-01). CONCURRENTLY cannot run in a transaction: V30__pre_incident_list_indexes.sql.conf sets
-- executeInTransaction=false, so each statement commits alone. A failed build leaves an INVALID index under its
-- name; the DO blocks rename such an index, which is then dropped CONCURRENTLY and built again, as in V9.
-- Rollback: DROP INDEX CONCURRENTLY idx_pre_incident_status; DROP INDEX CONCURRENTLY idx_pre_incident_unresolved.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_incident_status') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_incident_status RENAME TO idx_pre_incident_status_invalid;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_incident_unresolved') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_incident_unresolved RENAME TO idx_pre_incident_unresolved_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_incident_status_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_incident_unresolved_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_incident_status
  ON pre_incident (tenant_id, status, created_at DESC, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_incident_unresolved
  ON pre_incident (tenant_id, instance_id, created_at, id) WHERE status IN ('OPEN','RESOLUTION_PENDING');

RESET lock_timeout;
