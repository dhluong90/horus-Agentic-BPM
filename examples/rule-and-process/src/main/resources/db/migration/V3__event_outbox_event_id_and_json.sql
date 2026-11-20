-- Outbox rows carry the engine's eventId, unique per tenant, so a relay or a consumer can deduplicate, and the
-- payload as JSON. Earlier demo rows keep their text payload as a JSON string and have no event id.
SET LOCAL lock_timeout = '2s';
ALTER TABLE event_outbox ADD COLUMN event_id TEXT;
ALTER TABLE event_outbox ALTER COLUMN payload TYPE JSONB USING to_jsonb(payload);
CREATE UNIQUE INDEX uq_event_outbox_event_id ON event_outbox (tenant_id, event_id);
