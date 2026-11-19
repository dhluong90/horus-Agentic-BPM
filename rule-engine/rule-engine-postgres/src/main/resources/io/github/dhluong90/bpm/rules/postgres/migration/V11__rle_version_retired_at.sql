-- Version retirement (rule-engine/docs/DESIGN.md sections 3.3.1 and 3.3.2, ADR-0025, RLE-026, RLE-027, PD-101).
--
-- retired_at is the instant from which evaluation no longer selects a version: selection reads it, never the status
-- (a clock before it still selects the version). It is set in the same UPDATE that sets status RETIRED, by hand or
-- when a newer approved version is in effect, so the two always agree; the check below holds them together.
-- idx_rle_version_published serves the lifecycle sweep, which crosses tenants (engine sweep, never returns rows to a
-- caller), and the supersession in the approve and retire transactions of one rule set.
-- rle_version_history.no_version_in_effect records, on a RETIRED row, whether the retirement left the rule set with
-- no version in effect (PD-101); it is null on every other row.
--
-- Additive and forward-only: two nullable columns, one partial index and one check constraint. No release before
-- this one writes status RETIRED, so no existing row needs a value and the constraint validates at once. An old
-- replica during a rolling upgrade ignores the columns and selects by status: it also stops serving a retired
-- version, but does not select it for a clock before its retirement. Numbered 11, the number reserved for this
-- change; versions 6 to 10 belong to other 0.2.0 changes, and Flyway accepts the gap.
--
-- Rollback, a new forward migration, tested in RuleEngineSchemaMigratorIT:
--   ALTER TABLE rle_version_history DROP COLUMN no_version_in_effect;
--   ALTER TABLE rle_version DROP CONSTRAINT rle_version_retired_at_check;
--   DROP INDEX idx_rle_version_published;
--   ALTER TABLE rle_version DROP COLUMN retired_at;
-- It loses when each retired version stopped serving, so past-clock evaluations would select it again; export
-- (SELECT tenant_id, id, retired_at FROM rle_version WHERE retired_at IS NOT NULL) first.

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version ADD COLUMN retired_at TIMESTAMPTZ;

ALTER TABLE rle_version ADD CONSTRAINT rle_version_retired_at_check
  CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL));

ALTER TABLE rle_version_history ADD COLUMN no_version_in_effect BOOLEAN;

-- Engine sweep, crosses tenants and never returns rows to a caller; also the per-rule-set supersession.
CREATE INDEX idx_rle_version_published
  ON rle_version (tenant_id, rule_set_id, version_num) WHERE status = 'PUBLISHED';
