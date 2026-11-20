-- Offline replay (#98): a case whose candidate guardrail or schema needs the value of a masked field is not replayed
-- and carries the code MASKED_INPUT_REQUIRED (PRE-095). The run's summary counts those cases apart from the other
-- not-replayed ones. Additive: one NOT NULL column with a constant default, which PostgreSQL 11 and later adds without
-- rewriting the table; rows of runs already stored keep 0. The case table needs no change: the code is free text and
-- the outcome and change stay NOT_REPLAYED.
ALTER TABLE pre_agent_replay ADD COLUMN masked_input_required INT NOT NULL DEFAULT 0;
