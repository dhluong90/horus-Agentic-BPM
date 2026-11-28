-- RLE-232: the decision log records the error mode each decision was evaluated in. Additive and forward-only:
-- a row written before this migration keeps NULL, which the API shows as "unknown" and never as a guessed "strict".
-- ALTER TABLE on the partitioned parent adds the column to every partition, the default one included.
ALTER TABLE rle_decision
    ADD COLUMN error_mode text,
    ADD CONSTRAINT rle_decision_error_mode_check CHECK (error_mode IS NULL OR error_mode IN ('strict', 'spec'));
