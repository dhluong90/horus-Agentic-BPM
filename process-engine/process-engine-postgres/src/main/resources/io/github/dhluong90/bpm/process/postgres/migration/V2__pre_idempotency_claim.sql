-- An idempotency key is claimed before the work runs, so two concurrent requests with one key can never both do the
-- work: the second blocks on the primary key until the first commits, then replays its answer (or rolls back and
-- claims it). The answer is written when the work completes, in the same transaction.
SET LOCAL lock_timeout = '2s';
ALTER TABLE pre_idempotency
  ADD COLUMN status TEXT NOT NULL DEFAULT 'DONE' CHECK (status IN ('PENDING','DONE')),
  ALTER COLUMN response_body DROP NOT NULL;
