-- The host's own outbox. The Process Engine hands every event to the host inside the transaction of the state change
-- (DESIGN 6.4), and ProcessEventOutbox writes it here in that transaction, so the event exists exactly when the state
-- change committed. A relay (not part of this sample) publishes rows after commit.
CREATE TABLE event_outbox (
  tenant_id     TEXT        NOT NULL,
  id            BIGSERIAL   NOT NULL,
  event_type    TEXT        NOT NULL,
  business_key  TEXT,
  payload       TEXT        NOT NULL,
  occurred_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id)
);
