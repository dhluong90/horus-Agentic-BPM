-- The host's own tables, in the host's own Flyway history (flyway_schema_history). tenant_id leads every key.
CREATE TABLE card_customer (
  tenant_id    TEXT    NOT NULL,
  customer_id  TEXT    NOT NULL,
  full_name    TEXT    NOT NULL,
  tier         TEXT    NOT NULL,
  tx_count     INT     NOT NULL CHECK (tx_count >= 0),
  PRIMARY KEY (tenant_id, customer_id)
);

CREATE TABLE card_application (
  tenant_id       TEXT        NOT NULL,
  application_id  TEXT        NOT NULL,
  customer_id     TEXT        NOT NULL,
  status          TEXT        NOT NULL CHECK (status IN ('SUBMITTED','AUTO_APPROVED','APPROVED','REJECTED')),
  fee_currency    CHAR(3),
  fee_minor       BIGINT,
  submitted_at    TIMESTAMPTZ NOT NULL,
  decided_at      TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, application_id)
);
-- No foreign key to card_customer: the application keeps what was submitted, and the review process checks the
-- customer in its first step (cardProfiles.load), so an unknown customer rolls the submission back.

-- Seed customers for the scripted demo: one per row of the card-fee rule set, in two tenants.
INSERT INTO card_customer (tenant_id, customer_id, full_name, tier, tx_count) VALUES
  ('bank-a', 'C-001', 'Nguyen Van An',   'GOLD',     2),   -- R1: 0 VND, approved automatically
  ('bank-a', 'C-002', 'Tran Thi Binh',   'STANDARD', 5),   -- R2: 10000 VND, officer review
  ('bank-a', 'C-003', 'Le Van Cuong',    'STANDARD', 1),   -- R3: 50000 VND, officer review
  ('bank-b', 'C-001', 'Pham Thi Dung',   'STANDARD', 0);   -- same id in another tenant
