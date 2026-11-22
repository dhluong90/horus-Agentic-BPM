-- Simulation reports (rule-engine/docs/DESIGN.md section 4.2.3, PD-115, RLE-199, RLE-200).
--
-- A report is the evidence an approver reads before approving a version: the engine runs a simulation over sample
-- inputs or a replay period, reads the changed items in plain language and stores the counts and at most 50 rows,
-- bound to the version's canonical hash (a report whose hash no longer matches the draft is out of date). It holds no
-- input value, masked or not, and no caller-written label: only counts, rule ids, outcome readings and, for replayed
-- decisions, decision ids. It is written once; the triggers below make the database refuse an UPDATE, DELETE or
-- TRUNCATE, so a report cannot be rewritten after an approval relied on it.
--
-- Additive: a new table, an index and a new function. Nothing existing is altered, so no lock is taken on a table that
-- serves traffic; lock_timeout makes the migration fail fast instead of queueing behind a long transaction.
-- Rolling upgrade: a replica on the release before this one never reads or writes the table.
-- Forward-only. Rollback, if ever needed, is a new forward migration; it loses every stored report, which an approval
-- may have relied on, so export first (SELECT * FROM rle_simulation_report):
--   DROP TABLE rle_simulation_report;   -- after V38's table is dropped
--   DROP FUNCTION rle_append_only_guard();
-- The triggers must be dropped before the table can be emptied; a role that owns the table can do so, as with V4.

SET LOCAL lock_timeout = '5s';

CREATE FUNCTION rle_append_only_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TABLE rle_simulation_report (
  tenant_id            TEXT         NOT NULL,
  id                   VARCHAR(26)  NOT NULL,
  rule_set_name        TEXT         NOT NULL,
  version_num          INT          NOT NULL CHECK (version_num >= 1),
  canonical_hash       CHAR(64)     NOT NULL,
  created_by           TEXT         NOT NULL,
  created_at           TIMESTAMPTZ  NOT NULL,
  source               TEXT         NOT NULL CHECK (source IN ('SAMPLES','REPLAY')),
  period_from          TIMESTAMPTZ,
  period_to            TIMESTAMPTZ,
  period_limit         INT          CHECK (period_limit IS NULL OR period_limit BETWEEN 1 AND 1000),
  truncated            BOOLEAN      NOT NULL,
  total_count          INT          NOT NULL CHECK (total_count >= 0),
  changed_count        INT          NOT NULL CHECK (changed_count >= 0),
  unchanged_count      INT          NOT NULL CHECK (unchanged_count >= 0),
  failed_count         INT          NOT NULL CHECK (failed_count >= 0),
  not_replayable_count INT          NOT NULL CHECK (not_replayable_count >= 0),
  languages            TEXT         NOT NULL CHECK (length(languages) BETWEEN 2 AND 200),
  report_rows          JSONB        NOT NULL,
  rows_truncated       BOOLEAN      NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CHECK (total_count = changed_count + unchanged_count + failed_count + not_replayable_count),
  CHECK ((source = 'REPLAY') = (period_from IS NOT NULL AND period_to IS NOT NULL)),
  CHECK (source = 'REPLAY' OR (period_limit IS NULL AND NOT truncated)),
  CHECK (jsonb_typeof(report_rows) = 'array' AND jsonb_array_length(report_rows) <= 50)
);

-- The reports of one version, newest first (the list and the single read).
CREATE INDEX idx_rle_simulation_report_version
  ON rle_simulation_report (tenant_id, rule_set_name, version_num, id DESC);

CREATE TRIGGER rle_simulation_report_no_change
  BEFORE UPDATE OR DELETE ON rle_simulation_report
  FOR EACH ROW EXECUTE FUNCTION rle_append_only_guard();

CREATE TRIGGER rle_simulation_report_no_truncate
  BEFORE TRUNCATE ON rle_simulation_report
  FOR EACH STATEMENT EXECUTE FUNCTION rle_append_only_guard();
