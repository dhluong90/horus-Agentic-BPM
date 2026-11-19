-- Records the author of every version and extends four eyes to the author (security review of #1).
-- Forward-only. created_by is nullable, so the ADD COLUMN is a metadata-only change and rows written by V1
-- keep working (NULL means "author not recorded").
--
-- The V1 table CHECK (approved_by <> requested_by) is replaced by a stricter one that also refuses the
-- author. Rollback, if ever needed, is a new forward migration:
--   ALTER TABLE rle_version DROP CONSTRAINT rle_version_four_eyes;
--   ALTER TABLE rle_version ADD CONSTRAINT rle_version_check
--     CHECK (approved_by IS NULL OR approved_by <> requested_by OR approved_by = 'system:dev-auto-publish');
-- The column itself is kept on rollback: dropping it would lose audit data.

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version ADD COLUMN created_by TEXT;

ALTER TABLE rle_version DROP CONSTRAINT rle_version_check;

-- NOT VALID then VALIDATE: the scan runs under SHARE UPDATE EXCLUSIVE and does not block writers.
ALTER TABLE rle_version ADD CONSTRAINT rle_version_four_eyes CHECK (
  approved_by IS NULL
  OR approved_by = 'system:dev-auto-publish'
  OR (approved_by IS DISTINCT FROM requested_by AND approved_by IS DISTINCT FROM created_by)
) NOT VALID;

ALTER TABLE rle_version VALIDATE CONSTRAINT rle_version_four_eyes;
