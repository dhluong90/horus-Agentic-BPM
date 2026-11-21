-- The host function versions a decision used (rule-engine/docs/DESIGN.md section 5, RLE-058, PD-119).
-- A rule cell may call a function the host registered. The decision row keeps each one as name@version, comma
-- separated in name order, for example 'segmentOf@2027.2', so a replay can tell whether the function still has the
-- version the decision was made with. It is null for a decision whose rule set calls no host function, and for every
-- row written before this release.
--
-- Additive, nullable, no default: no row is rewritten and an old replica during a rolling upgrade inserts null. The
-- column is added on the partitioned parent and reaches every partition. Locks: ACCESS EXCLUSIVE for the metadata
-- change only; lock_timeout makes the migration fail fast instead of queueing behind a long transaction.
-- Forward-only. Rollback, if ever needed, is a new forward migration; it loses which function versions decisions
-- used, so export (SELECT tenant_id, id, function_versions FROM rle_decision WHERE function_versions IS NOT NULL)
-- first:
--   ALTER TABLE rle_decision DROP COLUMN function_versions;

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_decision ADD COLUMN function_versions TEXT
  CHECK (function_versions IS NULL OR length(function_versions) BETWEEN 3 AND 8192);
