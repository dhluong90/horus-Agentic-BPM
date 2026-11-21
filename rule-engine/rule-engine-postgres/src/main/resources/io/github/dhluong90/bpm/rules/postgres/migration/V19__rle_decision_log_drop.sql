-- The record of every decision log partition dropped by retention (rule-engine/docs/DESIGN.md section 5, RLE-017,
-- TR-OPS-04, PD-050). The decision log is an audit trail: a month of it may leave the database only by the host's
-- explicit decision-log.retention, and never without a trace. In the transaction that drops rle_decision_YYYY_MM, the
-- engine writes one row per tenant that had decisions in it, with that tenant's count, and one row with tenant_id '*'
-- holding the total, so a month that held no decision is recorded too. The rows are written before the DROP, in the
-- same transaction: either the month is gone and recorded, or neither.
--
-- tenant_id leads the primary key (TR-TEN-01); '*' is the whole partition, as the host scope is in
-- rle_masking_key_check, and no tenant may be named '*'. retention is the setting in force, for
-- example P12M; cutoff is the host clock minus it, and every row of the partition was older. Holds counts only, never decision
-- data, and is never returned by any API.
--
-- Append-only, like rle_version_history (V4): the triggers refuse UPDATE, DELETE and TRUNCATE with SQLSTATE 55000
-- naming the table. A host that separates the migration role from the runtime role should also run
--   REVOKE UPDATE, DELETE, TRUNCATE ON rle_decision_log_drop FROM <runtime role>;
-- The runtime role needs SELECT and INSERT on this table, and the right to DROP the monthly partitions it created.
--
-- Additive and forward-only: one new, empty table, so no existing row is touched and the lock is on the new table
-- only. A replica still on the release before this one never reads it and drops nothing. Rollback, if ever needed,
-- is a new forward migration:
--   DROP TRIGGER rle_decision_log_drop_no_truncate ON rle_decision_log_drop;
--   DROP TRIGGER rle_decision_log_drop_no_change ON rle_decision_log_drop;
--   DROP TABLE rle_decision_log_drop;
--   DROP FUNCTION rle_decision_log_drop_append_only();
-- which loses the record of earlier drops, so export it first.
--
-- Numbering: main holds V17 and an open change on batch/s3 also takes V17, so this takes V19.

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_decision_log_drop (
  tenant_id       TEXT        NOT NULL CHECK (tenant_id <> ''),
  partition_name  TEXT        NOT NULL CHECK (partition_name ~ '^rle_decision_[0-9]{4}_[0-9]{2}$'),
  range_from      TIMESTAMPTZ NOT NULL,
  range_to        TIMESTAMPTZ NOT NULL,
  decision_count  BIGINT      NOT NULL CHECK (decision_count >= 0),
  retention       TEXT        NOT NULL CHECK (retention ~ '^P[0-9]+M$'),
  cutoff          TIMESTAMPTZ NOT NULL,
  dropped_at      TIMESTAMPTZ NOT NULL,
  CHECK (range_from < range_to AND range_to <= cutoff),
  PRIMARY KEY (tenant_id, partition_name)
);

CREATE FUNCTION rle_decision_log_drop_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'rle_decision_log_drop is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER rle_decision_log_drop_no_change
  BEFORE UPDATE OR DELETE ON rle_decision_log_drop
  FOR EACH ROW EXECUTE FUNCTION rle_decision_log_drop_append_only();

CREATE TRIGGER rle_decision_log_drop_no_truncate
  BEFORE TRUNCATE ON rle_decision_log_drop
  FOR EACH STATEMENT EXECUTE FUNCTION rle_decision_log_drop_append_only();
