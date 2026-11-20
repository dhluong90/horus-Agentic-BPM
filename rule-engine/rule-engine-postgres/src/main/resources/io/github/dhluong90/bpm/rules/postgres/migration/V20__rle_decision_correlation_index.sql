-- Find decisions by the host's case reference (RLE-070, #94): GET /decisions?correlationId=... reads one tenant's
-- rows with one correlation id inside a period of at most 31 days, newest first. tenant_id leads (TR-TEN-01), the
-- period is the partition key, so the plan reads at most two monthly partitions through this index.
--
-- Numbered V20: V18 and V19 are taken by migrations not yet on main, and main's last Rule Engine migration is V17.
--
-- Partial: a decision with no correlation id is never searched by it. Additive and nullable-safe: no row is
-- rewritten. On a partitioned table the index is created on every existing partition and on every partition created
-- later (DecisionLogPartitions), so a new month needs nothing more.
--
-- Forward-only. Rollback, if ever needed, is a new forward migration: DROP INDEX idx_rle_decision_correlation;
-- It loses nothing: the index holds no data of its own.

SET LOCAL lock_timeout = '5s';

CREATE INDEX idx_rle_decision_correlation
  ON rle_decision (tenant_id, correlation_id, created_at DESC, id DESC)
  WHERE correlation_id IS NOT NULL;
