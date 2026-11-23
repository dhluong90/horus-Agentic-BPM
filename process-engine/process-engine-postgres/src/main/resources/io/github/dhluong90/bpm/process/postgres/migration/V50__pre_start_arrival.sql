-- Parallel multiple process start (PD-149). A process whose start event waits for all of several message and signal
-- definitions has no instance yet when the first of them arrives, so each arrival is held here, one row for each
-- definition and correlation key, until the last one comes and the instance starts. A message carries its correlation
-- key (the instance's business key); a signal is a latch with the empty key. The start consumes the rows it used.
-- Additive only: a new table, no existing row is touched, and a replica of the previous release never reads it.
-- Rollback, before any such process has been deployed: drop the table. Afterwards the rows are the held arrivals of
-- processes that have not started, so dropping them loses those starts; deploy the previous release only when no
-- parallel multiple start waits.
CREATE TABLE pre_start_arrival (
  tenant_id       TEXT         NOT NULL,
  key_name        TEXT         NOT NULL,
  trigger_kind    TEXT         NOT NULL CHECK (trigger_kind IN ('MESSAGE','SIGNAL')),
  event_name      VARCHAR(100) NOT NULL,
  correlation_key VARCHAR(200) NOT NULL DEFAULT '',
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  arrived_at      TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (tenant_id, key_name, trigger_kind, event_name, correlation_key)
);
CREATE INDEX idx_pre_start_arrival_oldest
  ON pre_start_arrival (tenant_id, key_name, trigger_kind, event_name, arrived_at);
