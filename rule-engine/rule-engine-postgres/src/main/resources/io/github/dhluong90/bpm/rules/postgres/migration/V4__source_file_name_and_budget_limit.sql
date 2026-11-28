-- The file name a version was imported from (RLE-232, #357): display metadata only. It is outside canonical_hash and
-- source_hash and outside the approval seal, so exporting and importing again leaves every hash unchanged.
-- Rollback (forward-only otherwise; run by hand, tested in RuleEngineSchemaMigratorIT):
--   ALTER TABLE rle_version_history DROP COLUMN source_file_name;
--   ALTER TABLE rle_version DROP COLUMN source_file_name;
--   ALTER TABLE rle_decision DROP COLUMN timeout_ms;
--   ALTER TABLE rle_decision DROP COLUMN step_budget;
--   ALTER TABLE rle_decision DROP COLUMN budget_limit;
-- Each CHECK is added NOT VALID and then validated, so a populated table is never locked against writes for a scan:
-- ADD CONSTRAINT ... NOT VALID takes only a brief lock and checks new rows at once; VALIDATE CONSTRAINT scans under a
-- SHARE UPDATE EXCLUSIVE lock, which lets reads and writes go on. The column is new, so every existing row is null and
-- the validation cannot fail.
ALTER TABLE rle_version ADD COLUMN source_file_name text;
ALTER TABLE rle_version ADD CONSTRAINT rle_version_source_file_name_check CHECK (
    source_file_name IS NULL OR (length(source_file_name) BETWEEN 1 AND 255 AND source_file_name !~ '[\x01-\x1f\x7f/\\]')) NOT VALID;
ALTER TABLE rle_version VALIDATE CONSTRAINT rle_version_source_file_name_check;

ALTER TABLE rle_version_history ADD COLUMN source_file_name text;
ALTER TABLE rle_version_history ADD CONSTRAINT rle_version_history_source_file_name_check CHECK (
    source_file_name IS NULL OR (length(source_file_name) BETWEEN 1 AND 255 AND source_file_name !~ '[\x01-\x1f\x7f/\\]')) NOT VALID;
ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_source_file_name_check;

-- The evaluation limit a decision reached (RLE-233, #360): STEPS or TIME for EVALUATION_BUDGET_EXCEEDED, null
-- otherwise, and the two limits the decision ran with (step_budget, timeout_ms), so that a replay uses the stored
-- values and the stored outcome and never this machine's clock. A nullable column added to the partitioned parent is a metadata change; no partition is rewritten, and
-- the value is not constrained in the database so that a later limit kind needs no migration.
ALTER TABLE rle_decision ADD COLUMN budget_limit text;
ALTER TABLE rle_decision ADD COLUMN step_budget integer;
ALTER TABLE rle_decision ADD COLUMN timeout_ms integer;
