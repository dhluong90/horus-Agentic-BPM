-- Runner per async job kind (#363, DESIGN section 4). The async executor claims each kind in its own statement
-- (job_kind = ?), up to the free slots of that kind's runner. This index serves that claim in due order, so a backlog
-- of one kind is never scanned by the claim of another. Additive only: one partial index on the open rows; the
-- existing idx_pre_timer_poll still serves the timer sweep. Rollback: DROP INDEX CONCURRENTLY idx_pre_timer_poll_kind.
--
-- pre_timer is populated on every host that runs timers or async steps, so the index is built CONCURRENTLY and never
-- holds a lock that blocks claims, parks or completions (TR-OPS-01, DESIGN section 11). CONCURRENTLY cannot run in a
-- transaction: V9__pre_timer_kind_index.sql.conf sets executeInTransaction=false, so each statement commits alone.
--
-- Re-run after a failed build: a CONCURRENTLY build that fails or is interrupted leaves an INVALID index under this
-- name, which IF NOT EXISTS alone would keep. The DO block renames such an index (SHARE UPDATE EXCLUSIVE, which does
-- not block reads or writes of pre_timer) and only when it is INVALID; the renamed index is dropped CONCURRENTLY,
-- then the build starts again. A valid index under this name is kept.
SET lock_timeout = '5s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('idx_pre_timer_poll_kind') AND NOT indisvalid) THEN
    ALTER INDEX idx_pre_timer_poll_kind RENAME TO idx_pre_timer_poll_kind_invalid;
  END IF;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_timer_poll_kind_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_timer_poll_kind
  ON pre_timer (job_kind, next_fire_at, id) WHERE status IN ('DUE','CLAIMED');

RESET lock_timeout;
