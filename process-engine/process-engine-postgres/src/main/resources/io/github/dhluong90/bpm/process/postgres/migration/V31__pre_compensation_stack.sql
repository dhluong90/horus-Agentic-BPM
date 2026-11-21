-- Saga compensation (#67, DESIGN sections 4.2 and 7, ADR-0007, ADR-0020). A completed step that declares an undo
-- (horus:compensate) pushes one row under its scope token: the saga block's scope token, or the root scope token
-- outside any saga. A failure with onFailure compensate, or a throwCompensation, walks the rows of that scope token
-- only, in descending seq, one undo at a time. Each undo is a COMPENSATION job in pre_timer keyed by its own job id
-- (job_id); a failed or manual undo stops the walk with an incident and the row keeps what was already undone.
--   seq                 push order within the scope token, from 1; the walk takes the highest PENDING row
--   forward_job_id      the job the forward step ran under (a remote or agent step), the key the remote side posted
--                       the original call under; null for a local step
--   forward_attempt_id  the accepted forward attempt, which the undo receives
--   handler             the registered handler or adapter that undoes the step, or 'manual'
--   mode                local (a ServiceTaskHandler in the undo's own transaction), remote (a ServiceAdapter outside
--                       any transaction) or manual (an incident for an operator)
--   input_snapshot      what the undo needs: the forward step's inputs and its result under forwardResult. History
--                       and incidents show masked inputs as keyed hashes only (TR-SEC-05)
--   status              PENDING, RUNNING (its job is due or claimed), DONE, FAILED (incident COMPENSATION_FAILED)
--                       or MANUAL (incident COMPENSATION_MANUAL_REQUIRED)
-- Every key and foreign key leads with tenant_id (TR-TEN-01).
--
-- Additive only: one new table, nothing existing is touched. Rollback, before any row is written:
-- DROP TABLE pre_compensation_stack; afterwards the rows are the audit of what was undone and are kept.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_compensation_stack (
  tenant_id          TEXT        NOT NULL,
  id                 VARCHAR(26) NOT NULL,
  instance_id        VARCHAR(26) NOT NULL,
  scope_token_id     VARCHAR(26) NOT NULL,
  seq                INT         NOT NULL CHECK (seq >= 1),
  step_id            TEXT        NOT NULL,
  forward_job_id     VARCHAR(26),
  forward_attempt_id VARCHAR(26) NOT NULL,
  handler            TEXT        NOT NULL,
  mode               TEXT        NOT NULL CHECK (mode IN ('local','remote','manual')),
  input_snapshot     JSONB       NOT NULL,
  status             TEXT        NOT NULL CHECK (status IN ('PENDING','RUNNING','DONE','FAILED','MANUAL')),
  job_id             VARCHAR(26),
  pushed_at          TIMESTAMPTZ NOT NULL,
  completed_at       TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uq_pre_compensation_stack_seq UNIQUE (tenant_id, instance_id, scope_token_id, seq),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, scope_token_id) REFERENCES pre_token (tenant_id, id),
  CHECK ((handler = 'manual') = (mode = 'manual')),
  CHECK (status <> 'RUNNING' OR job_id IS NOT NULL)
);
