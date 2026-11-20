-- Task queries by assignee, role, group, due date and SLA breach (#70, PRE-030; DESIGN sections 7 and 8.2). Each
-- index serves one range of a task query ordered by (due_at, id), so a page reads at most limit + 1 index entries
-- per range however long the queue is: tasks declared to a user (open or claimed), tasks a user claimed, open tasks
-- of one role, open tasks of one group, and every open or claimed task of a tenant (assignment=any). Tasks without a
-- due time sort after the others in each index (NULLS LAST, the B-tree default) and are read by id in a second range.
--
-- pre_task is written on every user task, so every index is built CONCURRENTLY and never blocks task creation,
-- claims or completions (TR-OPS-01). CONCURRENTLY cannot run in a transaction: V22__pre_task_query_indexes.sql.conf
-- sets executeInTransaction=false, so each statement commits alone. A failed or interrupted build leaves an INVALID
-- index under its name, which IF NOT EXISTS alone would keep: the DO block renames such an index (SHARE UPDATE
-- EXCLUSIVE, which blocks no read or write), the renamed one is dropped CONCURRENTLY, and the build starts again.
--
-- idx_pre_task_role of V1 ordered open role tasks by created_at; no query reads it once idx_pre_task_role_due exists,
-- so it is dropped CONCURRENTLY last.
--
-- Forward-only. Rollback, if ever needed, is a new forward migration, one statement each:
--   CREATE INDEX CONCURRENTLY idx_pre_task_role ON pre_task (tenant_id, assignee_role, created_at, id)
--     WHERE status = 'OPEN' AND assignee_role IS NOT NULL;
--   DROP INDEX CONCURRENTLY idx_pre_task_mine; DROP INDEX CONCURRENTLY idx_pre_task_claimed;
--   DROP INDEX CONCURRENTLY idx_pre_task_role_due; DROP INDEX CONCURRENTLY idx_pre_task_group;
--   DROP INDEX CONCURRENTLY idx_pre_task_open_due;
-- after which task queries fall back to scans and the query-plan check (PRE-036) fails.
SET lock_timeout = '5s';

DO $$
DECLARE
  name TEXT;
BEGIN
  FOREACH name IN ARRAY ARRAY['idx_pre_task_mine', 'idx_pre_task_claimed', 'idx_pre_task_role_due',
                              'idx_pre_task_group', 'idx_pre_task_open_due'] LOOP
    IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass(name) AND NOT indisvalid) THEN
      EXECUTE format('ALTER INDEX %I RENAME TO %I', name, name || '_invalid');
    END IF;
  END LOOP;
END
$$;

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_mine_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_claimed_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_role_due_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_group_invalid;
DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_open_due_invalid;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_task_mine
  ON pre_task (tenant_id, assignee_user, due_at, id) WHERE status IN ('OPEN','CLAIMED') AND assignee_user IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_task_claimed
  ON pre_task (tenant_id, claimed_by, due_at, id) WHERE status = 'CLAIMED';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_task_role_due
  ON pre_task (tenant_id, assignee_role, due_at, id) WHERE status = 'OPEN' AND assignee_role IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_task_group
  ON pre_task (tenant_id, assignee_group, due_at, id) WHERE status = 'OPEN' AND assignee_group IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pre_task_open_due
  ON pre_task (tenant_id, due_at, id) WHERE status IN ('OPEN','CLAIMED');

DROP INDEX CONCURRENTLY IF EXISTS idx_pre_task_role;

RESET lock_timeout;
