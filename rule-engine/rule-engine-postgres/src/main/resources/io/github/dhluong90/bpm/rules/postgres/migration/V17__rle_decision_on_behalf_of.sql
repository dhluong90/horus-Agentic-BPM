-- The human an embedded engine evaluated a decision for (ADR-0027, PRE-046).
-- A process step evaluates its decision as the engine identity system:process-engine; on_behalf_of names the user who
-- triggered the step, so the log stays attributable without granting rules:evaluate to task workers. It is null for a
-- decision made for its caller alone, and for engine-owned work such as a step after a timer. Set only through the
-- tenant-scoped entry point, never from a request body.
--
-- Additive, nullable, no default: no row is rewritten and an old replica during a rolling upgrade inserts null.
-- Forward-only. Rollback, if ever needed, is a new forward migration:
--   ALTER TABLE rle_decision DROP COLUMN on_behalf_of;
-- It loses who engine decisions were made for; export (SELECT tenant_id, id, on_behalf_of FROM rle_decision WHERE
-- on_behalf_of IS NOT NULL) first.

SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_decision ADD COLUMN on_behalf_of TEXT CHECK (on_behalf_of IS NULL OR length(on_behalf_of) BETWEEN 1 AND 200);
