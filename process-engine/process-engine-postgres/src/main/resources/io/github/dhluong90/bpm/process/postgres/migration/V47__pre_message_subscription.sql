-- Messages and correlation (#491, PRE-230 to PRE-235). A token waiting for a message (a message catch event or a
-- receive task) leaves one row here, in the same transaction that parks it; the host's correlate call finds the row by
-- tenant, message name and correlation key. The row is deleted when the token leaves the step, however it leaves
-- (delivery, cancel, compensation, migration), so a row always points at a token that still waits on it:
--   id        = the token's activity_instance_id while it waits
--   key       = the value of the variable named by horus:correlationKey, kept as the instance variables are
-- Additive only: a new table, no existing row is touched, and a replica of the previous release never reads it.
-- Rollback, before any message step has run: drop the table. Afterwards the rows are only the live subscriptions, so
-- dropping them loses the wait of every parked token; deploy the previous release only with no instance waiting.
CREATE TABLE pre_message_subscription (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  instance_id     VARCHAR(26) NOT NULL,
  token_id        VARCHAR(26) NOT NULL,
  node_id         TEXT        NOT NULL,
  message_name    VARCHAR(100) NOT NULL,
  correlation_key VARCHAR(200) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, token_id) REFERENCES pre_token (tenant_id, id)
);
CREATE INDEX idx_pre_message_subscription_lookup
  ON pre_message_subscription (tenant_id, message_name, correlation_key, id);
CREATE INDEX idx_pre_message_subscription_instance
  ON pre_message_subscription (tenant_id, instance_id);
