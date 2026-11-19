-- Engine-internal secrets, one row per purpose (rule-engine/docs/DESIGN.md section 4.2). The only purpose today is
-- 'cursor-hmac', the key that signs list cursors (RLE-039) so a cursor is accepted only from the engine that
-- issued it, for the same tenant and list. The starter creates the row at start-up, after the migrations, with 32
-- bytes from SecureRandom (INSERT ... ON CONFLICT DO NOTHING, then SELECT) in one short transaction outside any
-- request, so replicas that start together hold the same key. The runtime user therefore needs SELECT and INSERT
-- on this table. Requests only read the key from memory; a scheduler thread re-reads it read-only every 5 minutes.
--
-- Not tenant data, like rle_schema_history: one key per database serves every tenant, and the MAC itself covers
-- the tenant. Never returned by any API, never logged. Rotation is not offered in this release; the in-memory
-- holder keeps a previous key so that a later rotation keeps recent cursors valid for one refresh period.
--
-- Additive and forward-only. Rollback, if ever needed, is a new forward migration: DROP TABLE rle_engine_key;

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_engine_key (
  name        TEXT        NOT NULL PRIMARY KEY,
  key_bytes   BYTEA       NOT NULL CHECK (octet_length(key_bytes) >= 32),
  created_at  TIMESTAMPTZ NOT NULL
);
