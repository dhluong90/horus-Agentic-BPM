-- Engine-internal secrets, one row per purpose (rule-engine/docs/DESIGN.md section 4.2). The only purpose today is
-- 'cursor-hmac', the key that signs list cursors (RLE-039) so a cursor is accepted only from the engine that
-- issued it, for the same tenant and list. The engine creates the row on first use with 32 bytes from
-- SecureRandom (INSERT ... ON CONFLICT DO NOTHING, then SELECT), so every replica of a host uses the same key.
--
-- Not tenant data, like rle_schema_history: one key per database serves every tenant, and the MAC itself covers
-- the tenant. Never returned by any API, never logged. To rotate, delete the row; the next request creates a new
-- key and cursors issued before it answer 400 QUERY_INVALID, so clients start again from the first page.
--
-- Additive and forward-only. Rollback, if ever needed, is a new forward migration: DROP TABLE rle_engine_key;

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_engine_key (
  name        TEXT        NOT NULL PRIMARY KEY,
  key_bytes   BYTEA       NOT NULL CHECK (octet_length(key_bytes) >= 32),
  created_at  TIMESTAMPTZ NOT NULL
);
