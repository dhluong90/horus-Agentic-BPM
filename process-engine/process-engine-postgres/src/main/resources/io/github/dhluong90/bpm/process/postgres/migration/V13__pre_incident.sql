-- Incidents (#58, DESIGN section 7): a step whose job failed for good (retries used, not retryable, an agent answer
-- refused with onViolation fail, or claimed too often) opens one OPEN row in the transaction that fails the job, and
-- the token stays on the step for an operator. Retry and resolve by API follow with #68.
-- Additive only: one new table and three partial indexes on it, nothing existing is touched. Rollback, before any row is
-- written: DROP TABLE pre_incident; afterwards the rows are the audit of failed steps and are kept.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_incident (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  instance_id     VARCHAR(26) NOT NULL,
  definition_id   VARCHAR(26) NOT NULL,
  token_id        VARCHAR(26),
  node_id         TEXT        NOT NULL,
  error_code      TEXT        NOT NULL,
  cause_summary   TEXT        NOT NULL CHECK (length(cause_summary) <= 500),
  retry_count     INT         NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
  status          TEXT        NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
  lock_version    BIGINT      NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL,
  resolved_at     TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id)
);
CREATE INDEX idx_pre_incident_open ON pre_incident (tenant_id, created_at DESC, id) WHERE status = 'OPEN';
CREATE INDEX idx_pre_incident_node ON pre_incident (tenant_id, definition_id, node_id) WHERE status = 'OPEN';
CREATE INDEX idx_pre_incident_instance ON pre_incident (tenant_id, instance_id, created_at, id) WHERE status = 'OPEN';
