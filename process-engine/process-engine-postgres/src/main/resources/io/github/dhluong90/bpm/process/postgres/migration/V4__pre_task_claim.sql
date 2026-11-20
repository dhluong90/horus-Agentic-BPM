-- Task claims (process-engine/docs/DESIGN.md section 3.5): who holds a CLAIMED task and since when. Additive: two
-- nullable columns, and a check that a CLAIMED row names its claimant. No release before this one claims a task, so
-- no existing row is CLAIMED; the check is added NOT VALID and validated in a second statement, which takes only
-- SHARE UPDATE EXCLUSIVE on pre_task.
--
-- Forward-only. Rollback, if ever needed, is a new forward migration:
--   ALTER TABLE pre_task DROP CONSTRAINT ck_pre_task_claimant, DROP COLUMN claimed_by, DROP COLUMN claimed_at;
-- after which a CLAIMED task can no longer be completed by anyone until it is set back to OPEN.

SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_task
  ADD COLUMN claimed_by TEXT,
  ADD COLUMN claimed_at TIMESTAMPTZ;

ALTER TABLE pre_task
  ADD CONSTRAINT ck_pre_task_claimant CHECK (status <> 'CLAIMED' OR claimed_by IS NOT NULL) NOT VALID;

ALTER TABLE pre_task VALIDATE CONSTRAINT ck_pre_task_claimant;
