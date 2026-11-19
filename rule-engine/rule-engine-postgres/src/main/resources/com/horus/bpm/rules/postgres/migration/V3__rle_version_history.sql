-- Lifecycle history of rule set versions (rule-engine/docs/DESIGN.md sections 3.3.3 and 5): one row per
-- transition, appended in the transition's transaction. Append-only; the engine never updates or deletes a row.
-- tenant_id leads the primary key, the foreign key and the index (TR-TEN-01).
--
-- Additive and forward-only: a new empty table and its index, no change to existing rows, so the lock is only
-- the brief one of CREATE TABLE and an old replica running during a rolling upgrade is unaffected (it simply
-- writes no history rows). Versions written before this migration have no history rows for their earlier
-- transitions. Rollback, if ever needed, is a new forward migration that drops the table; that loses the
-- recorded history, so export it first:
--   DROP TABLE rle_version_history;
--
-- The action and approval_mode lists already hold the values of the retirement features (DESIGN section 3.3),
-- so those features need no constraint change.

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_version_history (
  tenant_id         TEXT        NOT NULL,
  id                VARCHAR(26) NOT NULL,
  version_id        VARCHAR(26) NOT NULL,
  action            TEXT        NOT NULL CHECK (action IN ('CREATED','REQUESTED','APPROVED','REJECTED',
                                                           'RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED')),
  from_status       TEXT        CHECK (from_status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED')),
  to_status         TEXT        NOT NULL CHECK (to_status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED')),
  actor             TEXT        NOT NULL,
  reason            TEXT,
  superseded_by     INT,
  approval_mode     TEXT        CHECK (approval_mode IN ('single','four-eyes','system')),
  lock_version      BIGINT      NOT NULL,
  occurred_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version (tenant_id, id)
);

-- History of one version, oldest first; also serves the rule set's latest change (max occurred_at).
CREATE INDEX idx_rle_version_history_version ON rle_version_history (tenant_id, version_id, occurred_at);
