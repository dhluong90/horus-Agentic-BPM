-- Drops the system:dev-auto-publish exemption from the four-eyes check (TR-SEC-04, RLE-009, issue #299).
-- V2 added rle_version_four_eyes: an approver must differ from the requester and from the author, unless the
-- approver is 'system:dev-auto-publish'. No code writes that id, and the service refuses an approver equal to the
-- requester or the author whatever the name, so the exemption only weakened the database's own check. From this
-- migration on, the constraint has no exemption. V2 is not edited: Flyway checksums each applied file.
--
-- Forward-only. Numbered above V11; V7, V8 and V9 are released unused and must never be taken.
--
-- Rollback, if ever needed, is a new forward migration that restores the V2 constraint (tested by
-- FourEyesConstraintPostgresIT, which runs the statements between the markers):
-- rollback-begin
--   ALTER TABLE rle_version DROP CONSTRAINT rle_version_four_eyes;
--   ALTER TABLE rle_version ADD CONSTRAINT rle_version_four_eyes CHECK (
--     approved_by IS NULL
--     OR approved_by = 'system:dev-auto-publish'
--     OR (approved_by IS DISTINCT FROM requested_by AND approved_by IS DISTINCT FROM created_by)
--   ) NOT VALID;
--   ALTER TABLE rle_version VALIDATE CONSTRAINT rle_version_four_eyes;
-- rollback-end

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version DROP CONSTRAINT rle_version_four_eyes;

-- NOT VALID then VALIDATE: adding the constraint checks new writes only and takes its lock briefly; the scan of
-- existing rows runs under SHARE UPDATE EXCLUSIVE and does not block writers.
ALTER TABLE rle_version ADD CONSTRAINT rle_version_four_eyes CHECK (
  approved_by IS NULL
  OR (approved_by IS DISTINCT FROM requested_by AND approved_by IS DISTINCT FROM created_by)
) NOT VALID;

ALTER TABLE rle_version VALIDATE CONSTRAINT rle_version_four_eyes;
