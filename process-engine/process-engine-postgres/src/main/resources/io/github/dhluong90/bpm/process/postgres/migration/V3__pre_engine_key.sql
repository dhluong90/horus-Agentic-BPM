-- Engine-internal secrets, one row per purpose (process-engine/docs/DESIGN.md section 8.1). The only purpose today is
-- 'cursor-hmac', the key that signs list cursors (GET /definitions, GET /instances) so a cursor is accepted only from
-- the engine that issued it, for the same tenant and list. The engine creates the row at start-up, after the
-- migrations, with 32 bytes from SecureRandom (INSERT ... ON CONFLICT DO NOTHING, then SELECT), so replicas that
-- start together hold the same key. The runtime user therefore needs SELECT and INSERT on this table.
--
-- Not tenant data, like pre_schema_history: one key per database serves every tenant, and the MAC itself covers the
-- tenant. Never returned by any API, never logged.
--
-- Additive and forward-only. Rollback, if ever needed, is a new forward migration: DROP TABLE pre_engine_key;
-- outstanding cursors then fail with 400 QUERY_INVALID and clients restart from the first page.

SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_engine_key (
  name        TEXT        NOT NULL PRIMARY KEY,
  key_bytes   BYTEA       NOT NULL CHECK (octet_length(key_bytes) >= 32),
  created_at  TIMESTAMPTZ NOT NULL
);
