-- PD-088: a decision with no matching rule is logged with outcome NO_MATCH (under first, unique and collect),
-- so the log tells "nothing matched" apart from "a rule matched". Forward-only; the change only widens the
-- allowed set, so every existing row satisfies the new constraint.
--
-- The outcome CHECK of V1 exists on the partitioned parent and, because partitions are created with
-- LIKE ... INCLUDING CONSTRAINTS before ATTACH, also as a local copy on each partition. Both are replaced.
-- Rollback: this migration only widens the allowed set, so no data has to change and the safe rollback is to
-- leave it in place. Narrowing the constraint again would fail once NO_MATCH rows exist, and the log is
-- append-only, so those rows are never rewritten. A deployment that has written no NO_MATCH row yet can run,
-- as a new forward migration:
--   ALTER TABLE rle_decision DROP CONSTRAINT rle_decision_outcome_check;
--   ALTER TABLE rle_decision ADD CONSTRAINT rle_decision_outcome_check
--     CHECK (outcome IN ('OK','UNIQUE_HIT_VIOLATION','EVALUATION_ERROR'));

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_decision DROP CONSTRAINT IF EXISTS rle_decision_outcome_check;

DO $$
DECLARE
  partition_name text;
BEGIN
  FOR partition_name IN
    SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'rle_decision'::regclass
  LOOP
    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS rle_decision_outcome_check', partition_name);
  END LOOP;
END $$;

ALTER TABLE rle_decision ADD CONSTRAINT rle_decision_outcome_check
  CHECK (outcome IN ('OK', 'NO_MATCH', 'UNIQUE_HIT_VIOLATION', 'EVALUATION_ERROR'));
