-- Executing a migration plan (#90, PRE-015, PRE-195 to PRE-198, DESIGN section 4.3). A plan is proposed with a reason,
-- approved by a second holder of processes:migration:manage (PD-114 as amended by PD-131), and then moved in batches by
-- one runner at a time: the runner holds a lease on the plan row, and each moved instance is written, counted and
-- passed by the cursor in one transaction, so a crash resumes where it stopped and a repeat moves nothing twice.
--
-- Additive only. pre_migration is a small table created by V23: its status and action checks are widened, execution
-- columns are added with constant defaults or null, and the table refuses an approved four-eyes plan whose approver is
-- its proposer and any running plan that was never approved. pre_migration_instance is new. No hot table is touched
-- and no index is built on one: the batch reads the V25 index and the V24 and V1 token indexes.
-- Rollback, before any plan is proposed: DROP TABLE pre_migration_instance; the added pre_migration columns and
-- constraints may then be dropped and the two checks restored to ('PLANNED'). Afterwards the rows are the audit of
-- what was moved and by whom, and are kept.
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE c RECORD;
BEGIN
  FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
           WHERE contype = 'c' AND conrelid IN ('pre_migration'::regclass, 'pre_migration_history'::regclass)
             AND (pg_get_constraintdef(oid) LIKE '%status%' OR pg_get_constraintdef(oid) LIKE '%action%')
  LOOP
    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
  END LOOP;
END
$$;

ALTER TABLE pre_migration
  ADD COLUMN proposed_by         TEXT,
  ADD COLUMN proposed_at         TIMESTAMPTZ,
  ADD COLUMN reason              TEXT,
  ADD COLUMN dry_run_checked_at  TIMESTAMPTZ,
  ADD COLUMN approved_by         TEXT,
  ADD COLUMN approved_at         TIMESTAMPTZ,
  ADD COLUMN rejected_by         TEXT,
  ADD COLUMN rejected_at         TIMESTAMPTZ,
  ADD COLUMN rejection_reason    TEXT,
  ADD COLUMN total_count         BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN migrated_count      BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN failed_count        BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN skipped_count       BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN cursor_instance_id  VARCHAR(26) NOT NULL DEFAULT '',
  ADD COLUMN lease_owner         TEXT,
  ADD COLUMN lease_until         TIMESTAMPTZ,
  ADD COLUMN started_at          TIMESTAMPTZ,
  ADD COLUMN finished_at         TIMESTAMPTZ;

ALTER TABLE pre_migration
  ADD CONSTRAINT ck_pre_migration_status CHECK (status IN ('PLANNED','PROPOSED','REJECTED','APPROVED','RUNNING',
        'COMPLETED','COMPLETED_WITH_FAILURES')),
  ADD CONSTRAINT ck_pre_migration_counts CHECK (total_count >= 0 AND migrated_count >= 0 AND failed_count >= 0
        AND skipped_count >= 0),
  -- Only an approved plan runs, whoever writes the row.
  ADD CONSTRAINT ck_pre_migration_approved CHECK (status NOT IN ('APPROVED','RUNNING','COMPLETED',
        'COMPLETED_WITH_FAILURES') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND proposed_by IS NOT NULL
        AND reason IS NOT NULL AND dry_run_checked_at IS NOT NULL)),
  -- A four-eyes approval is never the proposer's own.
  ADD CONSTRAINT ck_pre_migration_four_eyes CHECK (approval <> 'four-eyes' OR approved_by IS NULL
        OR approved_by <> proposed_by);

ALTER TABLE pre_migration_history
  ADD CONSTRAINT ck_pre_migration_history_action CHECK (action IN ('PLANNED','PROPOSED','REJECTED','APPROVED',
        'COMPLETED'));

-- The runner's claim reads the plans that are approved or running and whose lease is free or expired: an engine sweep
-- over a table of a few rows per tenant.
CREATE INDEX idx_pre_migration_runnable ON pre_migration (lease_until NULLS FIRST, tenant_id, id)
  WHERE status IN ('APPROVED','RUNNING');

-- One row per instance the runner has dealt with. The primary key makes a repeat harmless: an instance is recorded once.
CREATE TABLE pre_migration_instance (
  tenant_id    TEXT        NOT NULL,
  migration_id VARCHAR(26) NOT NULL,
  instance_id  VARCHAR(26) NOT NULL,
  outcome      TEXT        NOT NULL CHECK (outcome IN ('MIGRATED','FAILED','SKIPPED')),
  code         TEXT        NOT NULL,
  detail       TEXT        NOT NULL CHECK (length(detail) <= 500),
  incident_id  VARCHAR(26),
  occurred_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, migration_id, instance_id),
  FOREIGN KEY (tenant_id, migration_id) REFERENCES pre_migration (tenant_id, id)
);
CREATE INDEX idx_pre_migration_instance_failed ON pre_migration_instance (tenant_id, migration_id, instance_id)
  WHERE outcome = 'FAILED';
