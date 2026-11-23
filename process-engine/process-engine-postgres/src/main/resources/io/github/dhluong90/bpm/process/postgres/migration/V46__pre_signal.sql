-- Signal events (#492, PD-150). A signal is broadcast to every waiting subscription of one tenant and to the signal start
-- events of that tenant, and to no other tenant:
--   * pre_signal records each broadcast: its id is the idempotency key of the broadcast, so a second insert of the same
--     id writes nothing. Rows are small and are the audit of who threw what and when;
--   * pre_event_subscription holds the wait of a token on a signal, written in the transaction that parks the token and
--     deleted when it is woken or cancelled. Message waits will use the same table (the kind check already names them);
--   * pre_timer gains the job kind SIGNAL_DELIVERY, one job for each subscription a broadcast reaches, so the throwing
--     transaction writes rows and locks no waiting instance. The job id is the subscription id, which is why one
--     subscription is woken at most once.
-- Conditional events need no table: the token is the subscription and the instance re-evaluates it in the transaction
-- that changes its variables.
-- Additive only: two new tables and one widened check, added NOT VALID, which takes no long lock, and validated in a
-- second step that only reads. A replica of the previous release never writes a SIGNAL_DELIVERY job, so it is safe during
-- a rolling upgrade. Rollback, before any signal has been thrown: drop the two tables and restore the check without the
-- new value; afterwards the rows are the audit and are kept.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_signal (
  tenant_id   TEXT        NOT NULL,
  id          VARCHAR(26) NOT NULL,
  name        TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  thrown_by   TEXT        NOT NULL,
  thrown_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id)
);

CREATE TABLE pre_event_subscription (
  tenant_id     TEXT        NOT NULL,
  id            VARCHAR(26) NOT NULL,
  kind          TEXT        NOT NULL CHECK (kind IN ('MESSAGE','SIGNAL','CONDITIONAL')),
  event_name    TEXT        NOT NULL CHECK (length(event_name) BETWEEN 1 AND 100),
  definition_id VARCHAR(26) NOT NULL,
  instance_id   VARCHAR(26) NOT NULL,
  token_id      VARCHAR(26) NOT NULL,
  node_id       TEXT        NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition (tenant_id, id)
);
-- A broadcast reads the waiters of one name in one tenant, in id order.
CREATE INDEX idx_pre_event_sub_signal ON pre_event_subscription (tenant_id, event_name, id) WHERE kind = 'SIGNAL';
-- A cancelled or ended instance finds its own subscriptions.
CREATE INDEX idx_pre_event_sub_instance ON pre_event_subscription (tenant_id, instance_id);

ALTER TABLE pre_timer DROP CONSTRAINT ck_pre_timer_job_kind;
ALTER TABLE pre_timer ADD CONSTRAINT ck_pre_timer_job_kind CHECK (job_kind IN ('TIMER','SLA_ESCALATION','ASYNC_AGENT',
  'ASYNC_SERVICE','COMPENSATION','DEFINITION_EFFECTIVE','CALL_RESULT','SIGNAL_DELIVERY')) NOT VALID;
ALTER TABLE pre_timer VALIDATE CONSTRAINT ck_pre_timer_job_kind;
