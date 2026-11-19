-- Draft replacement audit (rule-engine/docs/DESIGN.md sections 3.3 and 3.3.3, RLE-047, architecture decision on #234):
-- replacing a draft in place appends one DRAFT_UPDATED history row with the actor, the lock version after the
-- change and the canonical hash before and after it.
--
-- Additive and forward-only: two nullable columns and a wider CHECK on the action. V3 is never edited. The CHECK is
-- swapped in one transaction under a bounded lock_timeout; ADD CONSTRAINT ... NOT VALID takes the ACCESS EXCLUSIVE
-- lock only for the catalogue change, and VALIDATE CONSTRAINT then scans the existing rows under SHARE UPDATE
-- EXCLUSIVE, which does not block inserts. Every existing row already satisfies the wider list. The append-only
-- triggers of V4 fire on row UPDATE, DELETE and TRUNCATE only, so this DDL is unaffected and they stay in force.
--
-- A replica still on the release before this one has no PUT of a draft, so it never needs the new action, and it
-- does not read the new columns. Rollback, if ever needed, is a new forward migration; it must first decide what to
-- do with DRAFT_UPDATED rows, because the append-only triggers refuse to delete them:
--   ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_action_check,
--     ADD CONSTRAINT rle_version_history_action_check CHECK (action IN ('CREATED','REQUESTED','APPROVED',
--       'REJECTED','WITHDRAWN','RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED')) NOT VALID;
--   ALTER TABLE rle_version_history DROP COLUMN previous_canonical_hash, DROP COLUMN canonical_hash;

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version_history
  ADD COLUMN canonical_hash          CHAR(64),
  ADD COLUMN previous_canonical_hash CHAR(64);

-- V3 declared the CHECK inline on the column, so PostgreSQL named it rle_version_history_action_check. The DROP has no
-- IF EXISTS on purpose: a database where that name is missing fails here instead of keeping the narrower list.
ALTER TABLE rle_version_history
  DROP CONSTRAINT rle_version_history_action_check,
  ADD CONSTRAINT rle_version_history_action_check CHECK (action IN ('CREATED','REQUESTED','APPROVED','REJECTED',
    'WITHDRAWN','DRAFT_UPDATED','RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED')) NOT VALID;

ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_action_check;

-- Only DRAFT_UPDATED rows carry the hashes, and they carry both.
ALTER TABLE rle_version_history
  ADD CONSTRAINT rle_version_history_draft_updated_hashes CHECK (
    (action = 'DRAFT_UPDATED') = (canonical_hash IS NOT NULL AND previous_canonical_hash IS NOT NULL)) NOT VALID;

ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_draft_updated_hashes;
