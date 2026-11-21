-- The host function versions a rule set version was approved with (rule-engine/docs/DESIGN.md section 5, RLE-058,
-- PD-119). Written in the approval transaction, one row per function the version calls. It is the baseline against
-- which start-up notices a changed function (one WARN naming the function, both labels and the published versions
-- that call it) and, under evaluation.function-version-change = refuse, evaluation fails closed with
-- FUNCTION_CHANGED. A version approved before this release has no rows, so it has no baseline and is never refused.
--
-- tenant_id leads the primary key and the foreign key (TR-TEN-01). The function names are the host's, not the
-- tenant's; a row records only which tenant's version called one.
--
-- Additive: a new table. A replica still on the release before this one neither reads nor writes it. Forward-only.
-- Rollback, if ever needed, is a new forward migration; it loses the baselines, so a later change of a function
-- version is no longer noticed until the versions are approved again:
--   DROP TABLE rle_version_function;

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_version_function (
  tenant_id        TEXT         NOT NULL,
  version_id       VARCHAR(26)  NOT NULL,
  function_name    TEXT         NOT NULL CHECK (function_name ~ '^[a-z][A-Za-z0-9]{0,63}$'),
  function_version TEXT         NOT NULL CHECK (length(function_version) BETWEEN 1 AND 64),
  recorded_at      TIMESTAMPTZ  NOT NULL,
  PRIMARY KEY (tenant_id, version_id, function_name),
  FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version (tenant_id, id)
);

CREATE INDEX rle_version_function_by_name
  ON rle_version_function (function_name, tenant_id, version_id);
