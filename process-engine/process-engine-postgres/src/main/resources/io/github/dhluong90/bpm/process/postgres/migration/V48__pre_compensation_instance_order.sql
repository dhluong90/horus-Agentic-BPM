-- Compensation of an enclosing scope walks the undo entries of the sub-processes completed in it (#488, ADR-0007,
-- ADR-0020). Entries are pushed under their own scope token, so the order the walk needs, newest first across scope
-- tokens, is not in seq (which counts within one scope token). instance_seq is the push order within the instance,
-- from 1, assigned in the push under the instance row lock, so two pushes never share a value. Existing rows get it
-- from pushed_at and id, which is the order they were pushed in.
-- Every key leads with tenant_id (TR-TEN-01).
--
-- Additive: one new column, filled for the rows that exist, then NOT NULL. Rollback, before any row is written
-- with it in use: ALTER TABLE pre_compensation_stack DROP COLUMN instance_seq; afterwards the column is the audit of
-- the order the undos were taken in and is kept.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_compensation_stack ADD COLUMN instance_seq INT;

UPDATE pre_compensation_stack s
   SET instance_seq = ranked.n
  FROM (SELECT tenant_id, id, row_number() OVER (PARTITION BY tenant_id, instance_id ORDER BY pushed_at, id) AS n
          FROM pre_compensation_stack) ranked
 WHERE s.tenant_id = ranked.tenant_id AND s.id = ranked.id;

ALTER TABLE pre_compensation_stack ALTER COLUMN instance_seq SET NOT NULL;
ALTER TABLE pre_compensation_stack ADD CONSTRAINT ck_pre_compensation_stack_instance_seq CHECK (instance_seq >= 1);
ALTER TABLE pre_compensation_stack
  ADD CONSTRAINT uq_pre_compensation_stack_instance_seq UNIQUE (tenant_id, instance_id, instance_seq);
