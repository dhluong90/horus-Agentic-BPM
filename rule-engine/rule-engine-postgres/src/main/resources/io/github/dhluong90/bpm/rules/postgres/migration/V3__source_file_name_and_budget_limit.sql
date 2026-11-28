-- The file name a version was imported from (RLE-228, #357): display metadata only. It is outside canonical_hash and
-- source_hash and outside the approval seal, so exporting and importing again leaves every hash unchanged.
-- Rollback (forward-only otherwise; run by hand, tested in RuleEngineSchemaMigratorIT):
--   ALTER TABLE rle_version_history DROP COLUMN source_file_name;
--   ALTER TABLE rle_version DROP COLUMN source_file_name;
--   ALTER TABLE rle_decision DROP COLUMN budget_limit;
ALTER TABLE rle_version ADD COLUMN source_file_name text;
ALTER TABLE rle_version ADD CONSTRAINT rle_version_source_file_name_check CHECK (
    source_file_name IS NULL OR (length(source_file_name) BETWEEN 1 AND 255 AND source_file_name !~ '[\x01-\x1f\x7f/\\]'));

ALTER TABLE rle_version_history ADD COLUMN source_file_name text;
ALTER TABLE rle_version_history ADD CONSTRAINT rle_version_history_source_file_name_check CHECK (
    source_file_name IS NULL OR (length(source_file_name) BETWEEN 1 AND 255 AND source_file_name !~ '[\x01-\x1f\x7f/\\]'));

-- The evaluation limit a decision reached (RLE-229, #360): STEPS or TIME for EVALUATION_BUDGET_EXCEEDED, null
-- otherwise. A nullable column added to the partitioned parent is a metadata change; no partition is rewritten, and
-- the value is not constrained in the database so that a later limit kind needs no migration.
ALTER TABLE rle_decision ADD COLUMN budget_limit text;
