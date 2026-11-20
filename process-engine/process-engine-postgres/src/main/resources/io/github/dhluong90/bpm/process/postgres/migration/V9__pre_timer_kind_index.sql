-- Runner per async job kind (#363, DESIGN section 4). The async executor claims each kind in its own statement
-- (job_kind = ?), up to the free slots of that kind's runner. This index serves that claim in due order, so a backlog
-- of one kind is never scanned by the claim of another. Additive only: one partial index on the open rows; the
-- existing idx_pre_timer_poll still serves the timer sweep. Rollback: DROP INDEX idx_pre_timer_poll_kind.
SET LOCAL lock_timeout = '5s';

CREATE INDEX idx_pre_timer_poll_kind
  ON pre_timer (job_kind, next_fire_at, id) WHERE status IN ('DUE','CLAIMED');
