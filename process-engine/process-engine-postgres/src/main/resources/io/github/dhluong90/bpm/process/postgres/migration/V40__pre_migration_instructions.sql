-- Step instructions of a migration plan (#106, PRE-207 to PRE-210, DESIGN section 4.3): the renamed steps a plan maps
-- and the removed steps it cancels, stored as one JSON object with the plan so a plan, its hash and its execution
-- always read the same instructions. A plan made before this column existed has none, which reads as the default.
-- Additive only: one column with a constant default, so no table rewrite, and a check that it is an object.
-- Rollback, before any plan with instructions is created: ALTER TABLE pre_migration DROP COLUMN instructions.
-- Afterwards the column is the record of what a plan did, and is kept.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_migration
  ADD COLUMN instructions JSONB NOT NULL DEFAULT '{"mappings":[],"cancellations":[]}'::jsonb,
  ADD CONSTRAINT ck_pre_migration_instructions CHECK (jsonb_typeof(instructions) = 'object');
