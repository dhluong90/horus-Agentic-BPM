-- Calling a child process and waiting for it (#92, PRE-211 to PRE-214, DESIGN section 4.2, PD-127, PD-144). A step of
-- type callProcess starts a child instance of another definition of the same tenant and waits for it:
--   * pre_token gains the status WAITING_CHILD for the parent's token;
--   * pre_timer gains the job kind CALL_RESULT, which resumes the parent in its own transaction once the child has
--     ended, so the child's end transaction never locks the parent row;
--   * pre_instance gains the link from a child to the parent token it was started for: parent_instance_id,
--     parent_token_id, call_attempt (0, then one more for each retry of a CHILD_FAILED incident) and call_depth
--     (0 for an instance nobody called). The unique index on (tenant_id, parent_token_id, call_attempt) is V43.
-- Additive only. The columns are nullable or carry a constant default, so no table is rewritten. The two widened
-- checks are added NOT VALID, which takes no long lock, and validated in a second step that only reads. A replica of the
-- previous release never writes a WAITING_CHILD token or a CALL_RESULT job, so it is safe during a rolling upgrade.
-- Rollback, before any callProcess step has run: drop the columns and restore the two checks without the new value.
-- Afterwards the rows are the audit of who called whom, and are kept.
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE c RECORD;
BEGIN
  FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
           WHERE contype = 'c' AND (
             (conrelid = 'pre_token'::regclass AND pg_get_constraintdef(oid) LIKE '%WAITING_TIMER%'
                AND pg_get_constraintdef(oid) NOT LIKE '%token_kind%')
             OR (conrelid = 'pre_timer'::regclass AND pg_get_constraintdef(oid) LIKE '%DEFINITION_EFFECTIVE%'
                AND pg_get_constraintdef(oid) LIKE '%job_kind%' AND pg_get_constraintdef(oid) NOT LIKE '%instance_id%'))
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
  END LOOP;
END
$$;

ALTER TABLE pre_token ADD CONSTRAINT ck_pre_token_status CHECK (status IN ('ACTIVE','WAITING_USER','WAITING_ASYNC',
  'WAITING_TIMER','WAITING_EVENT','WAITING_SCOPE','WAITING_CHILD','JOINED','COMPLETED','COMPENSATED','CANCELLED')) NOT VALID;
ALTER TABLE pre_timer ADD CONSTRAINT ck_pre_timer_job_kind CHECK (job_kind IN ('TIMER','SLA_ESCALATION','ASYNC_AGENT',
  'ASYNC_SERVICE','COMPENSATION','DEFINITION_EFFECTIVE','CALL_RESULT')) NOT VALID;

ALTER TABLE pre_instance
  ADD COLUMN parent_instance_id VARCHAR(26),
  ADD COLUMN parent_token_id    VARCHAR(26),
  ADD COLUMN call_attempt       INT NOT NULL DEFAULT 0 CHECK (call_attempt >= 0),
  ADD COLUMN call_depth         INT NOT NULL DEFAULT 0 CHECK (call_depth >= 0),
  ADD CONSTRAINT ck_pre_instance_parent CHECK ((parent_instance_id IS NULL) = (parent_token_id IS NULL)),
  ADD CONSTRAINT fk_pre_instance_parent FOREIGN KEY (tenant_id, parent_instance_id)
    REFERENCES pre_instance (tenant_id, id) NOT VALID;

ALTER TABLE pre_token VALIDATE CONSTRAINT ck_pre_token_status;
ALTER TABLE pre_timer VALIDATE CONSTRAINT ck_pre_timer_job_kind;
ALTER TABLE pre_instance VALIDATE CONSTRAINT fk_pre_instance_parent;
