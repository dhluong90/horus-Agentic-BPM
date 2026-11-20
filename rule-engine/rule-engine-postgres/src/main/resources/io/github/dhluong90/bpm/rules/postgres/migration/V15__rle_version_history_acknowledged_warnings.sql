-- Acknowledged warnings on approval (rule-engine/docs/DESIGN.md section 3.3.5, RLE-053, PD-104): the APPROVED
-- history row records the warnings the approver accepted on the record, and the inputs masked by the tenant's
-- decision-log.mask-default rather than by their declaration at approval.
--
--   acknowledged_warnings     JSON array of { "code", "input" }, for example
--                             [{"code": "UNMASKED_PERSONAL_DATA", "input": "nationalId"}]; [] when nothing needed an
--                             acknowledgement, so an auditor can tell "nothing to accept" from "accepted".
--   masked_by_default_inputs  JSON array of input names, for example ["nationalId"]; [] when none.
--
-- Both are written on every APPROVED row from this release, and are null on every other action and on APPROVED rows
-- written before it. The checks below refuse a value on another action and anything that is not a JSON array.
--
-- Additive and forward-only: two nullable columns and two CHECK constraints; no existing row is changed. V3, V4 and
-- V6 are never edited. The append-only triggers of V4 fire on row UPDATE, DELETE and TRUNCATE only, so this DDL is
-- unaffected and they stay in force: an acknowledgement, once written, can never be changed.
--
-- Locks: Flyway runs this script in one transaction. The first ALTER TABLE (ADD COLUMN, metadata only, no rewrite)
-- takes an ACCESS EXCLUSIVE lock on rle_version_history until the transaction commits, and the two ADD CONSTRAINT
-- scans run under it. The table holds a few rows per rule set version, so the scans take milliseconds; lock_timeout
-- makes the migration fail fast instead of queueing behind a long transaction. Every existing row satisfies both
-- checks, because both columns are null on it.
--
-- A replica still on the release before this one neither reads nor writes the columns, and its APPROVED rows leave
-- them null, which the checks allow. Rollback, if ever needed, is a new forward migration; it must first decide what
-- to do with the recorded acknowledgements, because the append-only triggers refuse to remove them:
--   ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_ack_on_approval,
--     DROP CONSTRAINT rle_version_history_ack_arrays,
--     DROP COLUMN acknowledged_warnings, DROP COLUMN masked_by_default_inputs;
--
-- Numbering: the next number above #308's V14. It takes the next free number above main when it merges, because
-- Flyway runs with out-of-order off.

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version_history
  ADD COLUMN acknowledged_warnings    JSONB,
  ADD COLUMN masked_by_default_inputs JSONB;

ALTER TABLE rle_version_history
  ADD CONSTRAINT rle_version_history_ack_on_approval
    CHECK (action = 'APPROVED' OR (acknowledged_warnings IS NULL AND masked_by_default_inputs IS NULL)),
  ADD CONSTRAINT rle_version_history_ack_arrays
    CHECK ((acknowledged_warnings IS NULL OR jsonb_typeof(acknowledged_warnings) = 'array')
       AND (masked_by_default_inputs IS NULL OR jsonb_typeof(masked_by_default_inputs) = 'array'));
