-- Restore an earlier version as the next draft (rule-engine/docs/DESIGN.md section 4.2, RLE-062, PD-121).
--
-- copied_from names the version a draft was restored from: set on the rle_version row of the restore and on its
-- CREATED history row, null everywhere else (an imported version, and every row written before this release). The
-- source of a restore is an earlier version of the same rule set, so the checks below hold it to 1 .. version_num - 1.
-- The history row's column is written once, in the transaction that creates the draft; the append-only triggers of V4
-- fire on UPDATE, DELETE and TRUNCATE only, so they stay in force.
--
-- Additive and forward-only: two nullable columns and two CHECK constraints; no existing row is changed, and every
-- existing row satisfies both checks because the column is null on it. A replica still on the release before this one
-- neither reads nor writes the columns. Locks: the ALTER TABLE statements take ACCESS EXCLUSIVE locks for the
-- metadata change only (ADD COLUMN without a default does not rewrite); the constraint scans cover rows that are all
-- null, and lock_timeout makes the migration fail fast instead of queueing behind a long transaction.
--
-- Rollback, a new forward migration; it loses which version each restore came from (the canonical hash of the draft
-- still shows what it holds), so export first
--   (SELECT tenant_id, id, copied_from FROM rle_version WHERE copied_from IS NOT NULL):
--   ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_copied_from_check,
--     DROP COLUMN copied_from;
--   ALTER TABLE rle_version DROP CONSTRAINT rle_version_copied_from_check, DROP COLUMN copied_from;

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version ADD COLUMN copied_from INTEGER;

ALTER TABLE rle_version ADD CONSTRAINT rle_version_copied_from_check
  CHECK (copied_from IS NULL OR (copied_from >= 1 AND copied_from < version_num));

ALTER TABLE rle_version_history ADD COLUMN copied_from INTEGER;

ALTER TABLE rle_version_history ADD CONSTRAINT rle_version_history_copied_from_check
  CHECK (copied_from IS NULL OR (action = 'CREATED' AND copied_from >= 1));
