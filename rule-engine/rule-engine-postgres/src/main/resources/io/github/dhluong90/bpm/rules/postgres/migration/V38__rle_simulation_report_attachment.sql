-- Which simulation report a publication request carries (rule-engine/docs/DESIGN.md section 4.2.3, PD-115, RLE-201).
--
-- :request-publish may name a simulationReportId. The request writes one row here in its own transaction, so the report
-- an approver is shown is the one the requester attached, and a later request (after a withdrawal) adds a newer row
-- instead of changing the old one. Rows are appended and never changed; V37's guard function refuses the rest. The
-- version's status and lock are not touched: the attachment is evidence, not part of the version.
--
-- Additive: a new table and an index. Nothing existing is altered; lock_timeout makes the migration fail fast.
-- Rolling upgrade: a replica on the release before this one never reads or writes the table.
-- Forward-only. Rollback, if ever needed, is a new forward migration after export (SELECT * FROM
-- rle_simulation_report_attachment):
--   DROP TABLE rle_simulation_report_attachment;

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_simulation_report_attachment (
  tenant_id     TEXT         NOT NULL,
  id            VARCHAR(26)  NOT NULL,
  rule_set_name TEXT         NOT NULL,
  version_num   INT          NOT NULL CHECK (version_num >= 1),
  report_id     VARCHAR(26)  NOT NULL,
  attached_by   TEXT         NOT NULL,
  attached_at   TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, report_id) REFERENCES rle_simulation_report (tenant_id, id)
);

-- The latest attachment of one version.
CREATE INDEX idx_rle_simulation_report_attachment_version
  ON rle_simulation_report_attachment (tenant_id, rule_set_name, version_num, id DESC);

CREATE TRIGGER rle_simulation_report_attachment_no_change
  BEFORE UPDATE OR DELETE ON rle_simulation_report_attachment
  FOR EACH ROW EXECUTE FUNCTION rle_append_only_guard();

CREATE TRIGGER rle_simulation_report_attachment_no_truncate
  BEFORE TRUNCATE ON rle_simulation_report_attachment
  FOR EACH STATEMENT EXECUTE FUNCTION rle_append_only_guard();
