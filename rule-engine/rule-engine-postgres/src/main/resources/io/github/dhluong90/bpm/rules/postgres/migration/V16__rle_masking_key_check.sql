-- Key check values of masking keys, one row per scope and key id (rule-engine/docs/DESIGN.md section 6, TR-SEC-10).
-- Every masked input is stored as hmac:<keyId>:<hex>, so a key id must name one key for good: a key id that comes
-- back with different key bytes would make earlier tokens unfindable and two keys indistinguishable. At start-up,
-- after the migrations and outside any request, the starter records the check value of every configured key,
-- current and previous (INSERT ... ON CONFLICT DO NOTHING, then SELECT, in one short transaction), and refuses to
-- start when a known key id comes back with a different check value, or when a key id recorded for the host comes
-- back for a tenant or the reverse. The runtime user therefore needs SELECT and INSERT on this table. Rows are never
-- updated or deleted by the engine: a retired key id stays reserved.
--
-- check_value is the first 8 bytes of HMAC-SHA256(HKDF-SHA256(key, info 'horus-bpm/masking/v1/kcv'), empty message)
-- in hex: a key derived for the check value alone, so it is never a token. It is not secret and reveals nothing about
-- the key or any token. tenant_id is the tenant of a tenant's own key, or '*' for a host key
-- shared by every tenant without its own key (a host key is recorded once, before any tenant derivation).
--
-- Additive and forward-only. Rollback, if ever needed, is a new forward migration: DROP TABLE rle_masking_key_check;
-- the next start records the configured keys again.

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_masking_key_check (
  tenant_id      TEXT        NOT NULL CHECK (tenant_id <> ''),
  key_id         TEXT        NOT NULL CHECK (key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
  check_value    TEXT        NOT NULL CHECK (check_value ~ '^[0-9a-f]{16}$'),
  first_seen_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, key_id)
);
