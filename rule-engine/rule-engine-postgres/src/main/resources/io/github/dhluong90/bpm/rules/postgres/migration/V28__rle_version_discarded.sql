-- Discard a draft version (rule-engine/docs/DESIGN.md section 3.3, RLE-098, #418).
--
-- A draft its author no longer wants moves to the terminal status DISCARDED and one DISCARDED row is appended to
-- rle_version_history. Nothing is deleted: the row, its rules and its history stay, so the audit trail keeps what was
-- discarded and by whom, and the version number is never used again. This script only widens three CHECK lists
-- (rle_version.status, rle_version_history.from_status and to_status) and the action list of the history.
--
-- Additive and forward-only: no column is added and no row changes; every existing row satisfies the wider lists.
-- Locks: each ALTER TABLE takes ACCESS EXCLUSIVE until the transaction commits, and the VALIDATE scans run under it;
-- the tables hold a few rows per rule set version, so the scans take milliseconds, and lock_timeout makes the
-- migration fail fast instead of queueing behind a long transaction. The append-only triggers of V4 fire on row
-- UPDATE, DELETE and TRUNCATE only, so this DDL is unaffected.
--
-- Rolling upgrade: finish the rollout before anyone discards a draft. A replica still on the release before this one
-- cannot read a DISCARDED row (an unknown status). Nothing writes the status until a caller sends :discard to a
-- replica on this release.
--
-- Rollback, a new forward migration. It must first decide what to do with discarded drafts, because the append-only
-- triggers refuse to delete their history rows; the usual answer is to keep the wider lists, which does no harm.
-- To narrow them anyway, once no row has the value:
--   ALTER TABLE rle_version DROP CONSTRAINT rle_version_status_check,
--     ADD CONSTRAINT rle_version_status_check CHECK (status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED'));
-- and the same for from_status, to_status and the action list of V24 without 'DISCARDED'.

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version
  DROP CONSTRAINT rle_version_status_check,
  ADD CONSTRAINT rle_version_status_check
    CHECK (status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED','DISCARDED')) NOT VALID;
ALTER TABLE rle_version VALIDATE CONSTRAINT rle_version_status_check;

ALTER TABLE rle_version_history
  DROP CONSTRAINT rle_version_history_from_status_check,
  ADD CONSTRAINT rle_version_history_from_status_check
    CHECK (from_status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED','DISCARDED')) NOT VALID,
  DROP CONSTRAINT rle_version_history_to_status_check,
  ADD CONSTRAINT rle_version_history_to_status_check
    CHECK (to_status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED','DISCARDED')) NOT VALID,
  DROP CONSTRAINT rle_version_history_action_check,
  ADD CONSTRAINT rle_version_history_action_check CHECK (action IN ('CREATED','REQUESTED','APPROVED','REJECTED',
    'WITHDRAWN','DRAFT_UPDATED','RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED','EVIDENCE_EXPORTED','DISCARDED'))
    NOT VALID;
ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_from_status_check;
ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_to_status_check;
ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_action_check;
