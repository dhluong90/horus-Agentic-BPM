-- Test only (PRE-218): what a migration of the NEXT release does while a replica of this release still runs. It only
-- expands: a nullable column, a defaulted NOT NULL column, a new table, and a job kind added to the check.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_task ADD COLUMN next_release_note TEXT;
ALTER TABLE pre_instance ADD COLUMN next_release_flag BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE pre_next_release_thing (
  tenant_id TEXT        NOT NULL,
  id        VARCHAR(26) NOT NULL,
  PRIMARY KEY (tenant_id, id)
);
ALTER TABLE pre_timer DROP CONSTRAINT ck_pre_timer_job_kind;
ALTER TABLE pre_timer ADD CONSTRAINT ck_pre_timer_job_kind CHECK (job_kind IN ('TIMER','SLA_ESCALATION','ASYNC_AGENT',
  'ASYNC_SERVICE','COMPENSATION','DEFINITION_EFFECTIVE','CALL_RESULT','NEXT_RELEASE_KIND')) NOT VALID;
ALTER TABLE pre_timer VALIDATE CONSTRAINT ck_pre_timer_job_kind;
