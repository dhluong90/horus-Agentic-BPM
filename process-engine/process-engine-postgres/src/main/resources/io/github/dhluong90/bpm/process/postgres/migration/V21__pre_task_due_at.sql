-- Task due times (#72, PRE-008): when a user task with horus:sla is due, in working time of its calendar, set once
-- when the task is created and never moved. Additive and metadata-only: one nullable column without a default, so
-- the ALTER takes ACCESS EXCLUSIVE on pre_task only for the catalogue change. Existing tasks keep a null due time:
-- they had no SLA. The indexes that order task queries by due time (#70) are built CONCURRENTLY in V22.
--
-- Forward-only. Rollback, if ever needed, is a new forward migration, after V22's indexes are dropped:
--   ALTER TABLE pre_task DROP COLUMN due_at;
-- after which tasks lose their due times and task queries order by id only.

SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_task ADD COLUMN due_at TIMESTAMPTZ;
