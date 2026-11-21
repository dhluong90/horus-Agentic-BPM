-- The host's own table: loan applications waiting for their document review (#66), with the seed data of the demo.
-- The engines keep their state in their own pre_ and rle_ tables of the same database.
CREATE TABLE loan_application (
  tenant_id       TEXT        NOT NULL,
  id              TEXT        NOT NULL,
  applicant       TEXT        NOT NULL,
  amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
  currency        CHAR(3)     NOT NULL,
  document_count  INT         NOT NULL CHECK (document_count >= 0),
  instance_id     VARCHAR(26),
  PRIMARY KEY (tenant_id, id)
);

-- Seed data for tenant bank-a, amounts in VND minor units (VND has no minor unit, so 1 VND = 1).
--   LN-2026-0101: 250,000,000 VND, all 3 documents. The agent scores it 20 and approves; the guardrail allows it.
--   LN-2026-0102: 1,500,000,000 VND, all 3 documents. The agent scores it 85 and still approves; the guardrail refuses
--                 the answer (R2, HIGH_RISK_SCORE) and a compliance reviewer reviews it.
--   LN-2026-0103: 600,000,000 VND, 2 of 3 documents. The agent scores it 55 and refers it; the guardrail allows it.
INSERT INTO loan_application (tenant_id, id, applicant, amount_minor, currency, document_count) VALUES
  ('bank-a', 'LN-2026-0101', 'Nguyen Van An',  250000000, 'VND', 3),
  ('bank-a', 'LN-2026-0102', 'Tran Thi Binh', 1500000000, 'VND', 3),
  ('bank-a', 'LN-2026-0103', 'Le Van Cuong',   600000000, 'VND', 2);
