-- Review context of a four-eyes review task (#52, DESIGN section 4.1): when an agent step's answer is refused, the
-- review task carries why (code, reasons, matched rule ids, the guardrail decision id), the inputs the agent saw and
-- the answer masked by the output schema's masking policy, so the reviewer decides on what the guardrail saw.
-- NULL for every other task. Additive only: one nullable column, no default, no rewrite, no new index.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_task
  ADD COLUMN review JSONB CHECK (review IS NULL OR jsonb_typeof(review) = 'object');
