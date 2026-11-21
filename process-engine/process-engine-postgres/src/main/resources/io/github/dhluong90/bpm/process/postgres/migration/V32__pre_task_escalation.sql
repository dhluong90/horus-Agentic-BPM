-- Escalation of overdue user tasks (#73, PRE-178 to PRE-180) and the calendar change list (#409, PRE-181).
--
-- pre_task gains the escalation a task declared, resolved once when the task is created: escalation_json holds the
-- levels with the absolute time each fires and the claim rule, escalation_level the number of levels already
-- applied, next_escalation_at the time of the next one (null when none is left). The engine's sweep reads the tasks
-- whose next_escalation_at has passed through idx_pre_task_escalation_due, a partial index over open and claimed
-- tasks that declared an escalation, so a database where almost no task escalates keeps an almost empty index.
-- The three ADD COLUMN statements are metadata-only: no default is written to existing rows. Existing tasks keep
-- no escalation, which is what they declared.
--
-- pre_calendar_change gains idx_pre_calendar_change_status for GET /calendar-changes?status=..., newest first.
--
-- Both indexes are built CONCURRENTLY (TR-OPS-01), so the file runs outside a transaction
-- (V32__pre_task_escalation.sql.conf). A failed build leaves an INVALID index under its name; the DO block renames
-- it, it is dropped CONCURRENTLY and built again, as in V30.
--
-- Forward-only. Rollback, if ever needed, is a new forward migration:
--   DROP INDEX CONCURRENTLY idx_pre_task_escalation_due; DROP INDEX CONCURRENTLY idx_pre_calendar_change_status;
--   ALTER TABLE pre_task DROP COLUMN escalation_json, DROP COLUMN escalation_level, DROP COLUMN next_escalation_at;
-- after which no task escalates and tasks already escalated keep the assignment they reached.
SET lock_timeout = '5s';

ALTER TABLE pre_task ADD COLUMN IF NOT EXISTS escalation_json JSONB;
ALTER TABLE pre_task ADD COLUMN IF NOT EXISTS escalation_level INTEGER;
ALTER TABLE pre_task ADD COLUMN IF NOT EXISTS next_escalation_at TIMESTAMPTZ;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_task_escalation_due') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_task_escalation_due RENAME TO idx_pre_task_escalation_due_invalid;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_calendar_change_status') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_calendar_change_status RENAME TO idx_pre_calendar_change_status_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_escalation_due_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_calendar_change_status_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_task_escalation_due
  ON pre_task (next_escalation_at, tenant_id, id)
  WHERE status IN ('OPEN','CLAIMED') AND next_escalation_at IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_calendar_change_status
  ON pre_calendar_change (tenant_id, status, proposed_at DESC, id DESC);

RESET lock_timeout;
