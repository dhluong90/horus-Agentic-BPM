-- Lifecycle history of rule set versions (rule-engine/docs/DESIGN.md sections 3.3.3 and 5): one row per
-- transition, appended in the transition's transaction. Append-only; the engine never updates or deletes a row.
-- tenant_id leads the primary key, the foreign key and the index (TR-TEN-01).
--
-- Additive and forward-only: a new table, its index and one backfilled row per existing version; no existing row
-- changes, so the locks are the brief one of CREATE TABLE and a read of rle_version. An old replica running
-- during a rolling upgrade is unaffected (it simply writes no history rows). Rollback, if ever needed, is a new
-- forward migration that drops the table; that loses the recorded history, so export it first:
--   DROP TABLE rle_version_history;
--
-- The action and approval_mode lists already hold the values of the withdraw (PD-089, RLE-046) and retirement
-- features (DESIGN section 3.3), so those features need no constraint change.

SET LOCAL lock_timeout = '5s';

CREATE TABLE rle_version_history (
  tenant_id         TEXT        NOT NULL,
  id                VARCHAR(26) NOT NULL,
  version_id        VARCHAR(26) NOT NULL,
  action            TEXT        NOT NULL CHECK (action IN ('CREATED','REQUESTED','APPROVED','REJECTED','WITHDRAWN',
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

-- Backfill: every version that exists now gets its CREATED row, so GET .../history starts at the creation for
-- versions written before this migration too. Their later transitions (request, approval) were not recorded and
-- are not invented; rle_version still holds requested_by, approved_by and their times. The actor is the author,
-- or 'system' (the default of lifecycle.system-actor) where V1 did not record one. The row id is the version id:
-- a ULID, unique per tenant, and no other history row exists yet. Bounded by the number of versions (tens per
-- rule set), so one statement in this transaction is enough.
INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor, reason,
                                 lock_version, occurred_at)
SELECT v.tenant_id, v.id, v.id, 'CREATED', NULL, 'DRAFT', COALESCE(v.created_by, 'system'), NULL, 0, v.created_at
FROM rle_version v;
