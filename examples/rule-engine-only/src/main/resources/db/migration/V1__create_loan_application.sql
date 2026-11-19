-- The host's own table, in the host's own Flyway history (flyway_schema_history).
CREATE TABLE loan_application (
  tenant_id     TEXT        NOT NULL,
  id            VARCHAR(26) NOT NULL,
  applicant_age INT         NOT NULL,
  income_minor  BIGINT      NOT NULL,
  currency      CHAR(3)     NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id)
);
