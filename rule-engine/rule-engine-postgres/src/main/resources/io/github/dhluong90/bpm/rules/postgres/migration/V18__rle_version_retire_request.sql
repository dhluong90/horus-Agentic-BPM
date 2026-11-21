-- Four-eyes retirement (rule-engine/docs/DESIGN.md section 3.3, ADR-0025, PD-080, PD-101, RLE-038, issue #213).
--
-- With horus.bpm.lifecycle.retire-approval: four-eyes (or retire-last-version: four-eyes for the last version in
-- effect), :retire records a request on the version row and the version stays PUBLISHED and serving; selection never
-- reads these columns. :confirm-retire by another rules:approve holder retires it; :cancel-retire clears the request.
--
--   rle_version.retire_requested_by        who asked; never the one who confirms
--   rle_version.retire_requested_at        when
--   rle_version.retire_reason              the reason, recorded again on the RETIRED history row
--   rle_version.retire_confirm_no_version  whether the requester accepted that no version may remain in effect
--                                          (PD-101), so the confirmation of the last version needs no flag of its own
--   rle_version_history.retire_requested_by  on a RETIRED row in four-eyes mode, the requester the actor confirmed
--
-- Checks: the four request columns are set together or not at all, and only on a PUBLISHED version (a retirement or
-- a supersession clears them in the same UPDATE). On the history, retire_requested_by appears only on a RETIRED row
-- with approval_mode four-eyes, and never equals the actor: four eyes on retirement in the database too (TR-SEC-04).
--
-- Additive and forward-only: five nullable columns and three CHECK constraints; no existing row changes. Every
-- existing row satisfies the checks because the new columns are null on it. V1, V3 and V11 are never edited. The
-- append-only triggers of V4 fire on row UPDATE, DELETE and TRUNCATE only, so this DDL is unaffected.
--
-- Locks: Flyway runs this script in one transaction. Each ALTER TABLE (ADD COLUMN, metadata only, no rewrite) takes
-- an ACCESS EXCLUSIVE lock held until commit, and the ADD CONSTRAINT scans run under it. Both tables hold a few rows
-- per rule set version, so the scans take milliseconds; lock_timeout makes the migration fail fast instead of
-- queueing behind a long transaction.
--
-- A replica still on the release before this one neither reads nor writes the columns. It refuses
-- retire-approval: four-eyes at start-up, so it never writes a request; its :retire in single mode and its sweep leave
-- a pending request on a row it retires, which the first check refuses: finish the rolling upgrade before turning
-- four-eyes on.
--
-- Rollback, if ever needed, is a new forward migration (tested in RuleEngineSchemaMigratorIT, which runs the
-- statements between the markers). It loses the pending requests and, because the append-only triggers refuse to
-- change a history row, it first needs a decision on the recorded requesters; export
-- (SELECT tenant_id, id, retire_requested_by FROM rle_version_history WHERE retire_requested_by IS NOT NULL) first.
-- rollback-begin
--   ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_retire_four_eyes,
--     DROP COLUMN retire_requested_by;
--   ALTER TABLE rle_version DROP CONSTRAINT rle_version_retire_request_published,
--     DROP CONSTRAINT rle_version_retire_request_complete,
--     DROP COLUMN retire_confirm_no_version, DROP COLUMN retire_reason, DROP COLUMN retire_requested_at,
--     DROP COLUMN retire_requested_by;
-- rollback-end
--
-- Numbering: the next free Rule Engine number above V16 (V13 is retired, tools/migration-retired-versions.txt).

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version
  ADD COLUMN retire_requested_by       TEXT,
  ADD COLUMN retire_requested_at       TIMESTAMPTZ,
  ADD COLUMN retire_reason             TEXT,
  ADD COLUMN retire_confirm_no_version BOOLEAN;

ALTER TABLE rle_version
  ADD CONSTRAINT rle_version_retire_request_complete CHECK (
    (retire_requested_by IS NULL AND retire_requested_at IS NULL AND retire_reason IS NULL
       AND retire_confirm_no_version IS NULL)
    OR (retire_requested_by IS NOT NULL AND retire_requested_at IS NOT NULL AND retire_reason IS NOT NULL
       AND retire_confirm_no_version IS NOT NULL)),
  ADD CONSTRAINT rle_version_retire_request_published CHECK (
    retire_requested_by IS NULL OR status = 'PUBLISHED');

ALTER TABLE rle_version_history ADD COLUMN retire_requested_by TEXT;

ALTER TABLE rle_version_history
  ADD CONSTRAINT rle_version_history_retire_four_eyes CHECK (
    retire_requested_by IS NULL
    OR (action = 'RETIRED' AND approval_mode = 'four-eyes' AND retire_requested_by <> actor));
