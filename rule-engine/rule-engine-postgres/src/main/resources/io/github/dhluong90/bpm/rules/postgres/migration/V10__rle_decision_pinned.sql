-- Pinned decisions are visible in the log (#22, security review of #261).
--
-- rle_decision.pinned is set by the engine, never from a request body: true when the caller named the version
-- (rules:pin) instead of letting the clock select the version in force. Together with eval_clock differing from
-- created_at (a chosen clock), it tells a reader of the log that a decision did not come from the normal path.
--
-- Additive and forward-only: one NOT NULL column with a constant default, which PostgreSQL adds as metadata only, on
-- the partitioned parent and every partition, without rewriting rows. Rows written before this migration are
-- decisions of the version in force, so false is their true value. An old replica during a rolling upgrade inserts
-- without the column and gets false; it cannot pin, because pinning arrives in the same release. Numbered 10, the
-- number reserved for this change; Flyway accepts the gap from 5.
--
-- Rollback, a new forward migration, tested in RuleEngineSchemaMigratorIT:
--   ALTER TABLE rle_decision DROP COLUMN pinned;
-- It loses which decisions were pinned; export (SELECT tenant_id, id FROM rle_decision WHERE pinned) first.

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_decision ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT false;
