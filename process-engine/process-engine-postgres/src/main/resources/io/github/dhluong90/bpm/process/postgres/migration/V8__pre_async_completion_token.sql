-- Async step completion (ADR-0008, DESIGN section 6.3). Every claim of an async job issues a random completion token;
-- only its SHA-256 is stored here, and the completion API compares it in constant time. The attempt id stays the
-- correlation id and the history key. late_result_at bounds the LATE_RESULT_DISCARDED history of a cancelled job to
-- one row. Additive only: two nullable columns on pre_timer, no rewrite, no new index.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_timer
  ADD COLUMN completion_token_hash BYTEA CHECK (completion_token_hash IS NULL OR octet_length(completion_token_hash) = 32),
  ADD COLUMN late_result_at        TIMESTAMPTZ;
