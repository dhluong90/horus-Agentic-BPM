-- The value of a setting each tenant last started with (rule-engine/docs/DESIGN.md section 4.4, RLE-052, PD-099
-- mitigation 4). At start-up, after the migrations and the masking key check, the engine compares the
-- decision-log.mask-default in force for every tenant with the value recorded here, logs one line naming the tenant
-- and the old and new value when it changed, and records the new value. A changed masking default decides whether
-- undeclared inputs reach the append-only decision log in plain text, so it must never change unnoticed.
--
-- One row per tenant and setting; tenant_id leads the primary key (TR-TEN-01). Written only at start-up, outside any
-- request, with INSERT ... ON CONFLICT DO UPDATE, so replicas that start together neither fail nor block. The runtime
-- user therefore needs SELECT, INSERT and UPDATE on this table. Holds setting values only, never a key, a secret or
-- decision data, and is never returned by any API.
--
-- Additive and forward-only: one new, empty table, so no existing row is touched and the lock is on the new table
-- only. A replica still on the release before this one never reads it. Rollback, if ever needed, is a new forward
-- migration: DROP TABLE rle_setting_seen; the next start-up then records every tenant again as first seen.
--
-- Numbering: the next number above every Rule Engine migration on main or reserved when this was written (V7 to V9
-- are released and unused; V10 to V13 belong to other changes). It takes the next free number above main when it
-- merges, because Flyway runs with out-of-order off.

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_setting_seen (
  tenant_id    TEXT        NOT NULL,
  setting      TEXT        NOT NULL CHECK (length(setting) BETWEEN 1 AND 100),
  value        TEXT        NOT NULL CHECK (length(value) <= 200),
  recorded_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, setting)
);
