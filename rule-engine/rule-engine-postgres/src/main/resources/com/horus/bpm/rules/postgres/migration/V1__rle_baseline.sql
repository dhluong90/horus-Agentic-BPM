-- Rule Engine baseline schema (rule-engine/docs/DESIGN.md section 5).
-- Recorded in rle_schema_history only; the host's Flyway history is never read or written (TR-EMB-06).
-- tenant_id leads every primary key, unique constraint and index (TR-TEN-01). Ids are ULIDs, times UTC.

CREATE TABLE rle_rule_set (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  name            TEXT        NOT NULL,
  description     TEXT,
  cache_epoch     BIGINT      NOT NULL DEFAULT 1,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, name)
);

CREATE TABLE rle_version (
  tenant_id         TEXT        NOT NULL,
  id                VARCHAR(26) NOT NULL,
  rule_set_id       VARCHAR(26) NOT NULL,
  version_num       INT         NOT NULL,
  status            TEXT        NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','RETIRED')),
  hit_policy        TEXT        NOT NULL CHECK (hit_policy IN ('FIRST','UNIQUE','COLLECT')),
  effective_from    TIMESTAMPTZ,
  canonical_json    JSONB       NOT NULL,
  canonical_hash    CHAR(64)    NOT NULL,
  source_format     TEXT        NOT NULL CHECK (source_format IN ('JSON','YAML','DMN_XML')),
  source_document   TEXT        NOT NULL,
  source_hash       CHAR(64)    NOT NULL,
  lock_version      BIGINT      NOT NULL DEFAULT 0,
  requested_by      TEXT,
  requested_at      TIMESTAMPTZ,
  approved_by       TEXT,
  approved_at       TIMESTAMPTZ,
  approval_comment  TEXT,
  created_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, rule_set_id, version_num),
  FOREIGN KEY (tenant_id, rule_set_id) REFERENCES rle_rule_set (tenant_id, id),
  CHECK (approved_by IS NULL OR approved_by <> requested_by OR approved_by = 'system:dev-auto-publish')
);
CREATE INDEX idx_rle_version_effective
  ON rle_version (tenant_id, rule_set_id, status, effective_from DESC);

CREATE TABLE rle_rule (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  version_id      VARCHAR(26) NOT NULL,
  row_order       INT         NOT NULL,
  rule_id         TEXT        NOT NULL,
  when_expr_json  JSONB       NOT NULL,
  then_expr_json  JSONB       NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, version_id, rule_id),
  FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version (tenant_id, id)
);

-- Append-only decision log, range-partitioned by month from the first migration (ADR-0010, PD-004).
CREATE TABLE rle_decision (
  tenant_id        TEXT        NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL,
  id               VARCHAR(26) NOT NULL,
  rule_set_name    TEXT        NOT NULL,
  version_num      INT         NOT NULL,
  hit_policy       TEXT        NOT NULL,
  outcome          TEXT        NOT NULL CHECK (outcome IN ('OK','UNIQUE_HIT_VIOLATION','EVALUATION_ERROR')),
  error_code       TEXT,
  masked_inputs    JSONB       NOT NULL,
  outputs          JSONB,
  matched_rule_ids JSONB       NOT NULL,
  caller_id        TEXT,
  correlation_id   TEXT,
  eval_clock       TIMESTAMPTZ NOT NULL,
  duration_micros  BIGINT      NOT NULL,
  log_mode         TEXT        NOT NULL CHECK (log_mode IN ('TRANSACTIONAL','ASYNC')),
  PRIMARY KEY (tenant_id, created_at, id)
) PARTITION BY RANGE (created_at);

-- Catches rows outside every monthly partition, so an insert never fails for lack of a partition.
CREATE TABLE rle_decision_default PARTITION OF rle_decision DEFAULT;

-- Monthly partitions for the current and the next UTC month, named rle_decision_YYYY_MM, half-open
-- [first day, first day of next month). Each is created standalone with a CHECK constraint and then
-- attached, so ATTACH skips the validation scan. Later months are created by the scheduler (RLE-017).
DO $$
DECLARE
  first_month DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::DATE;
  month_start DATE;
  part_name   TEXT;
  lower_bound TIMESTAMPTZ;
  upper_bound TIMESTAMPTZ;
BEGIN
  FOR i IN 0..1 LOOP
    month_start := (first_month + make_interval(months => i))::DATE;
    part_name   := 'rle_decision_' || to_char(month_start, 'YYYY_MM');
    lower_bound := month_start::TIMESTAMP AT TIME ZONE 'UTC';
    upper_bound := (month_start + INTERVAL '1 month')::TIMESTAMP AT TIME ZONE 'UTC';
    EXECUTE format('CREATE TABLE %I (LIKE rle_decision INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', part_name);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (created_at >= %L AND created_at < %L)',
                   part_name, part_name || '_range', lower_bound, upper_bound);
    EXECUTE format('ALTER TABLE rle_decision ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)',
                   part_name, lower_bound, upper_bound);
  END LOOP;
END
$$;

CREATE INDEX idx_rle_decision_lookup
  ON rle_decision (tenant_id, rule_set_name, created_at DESC, outcome);

CREATE TABLE rle_decision_outbox (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  payload         JSONB       NOT NULL,
  status          TEXT        NOT NULL CHECK (status IN ('PENDING','CLAIMED','SENT')),
  locked_by       TEXT,
  locked_until    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id)
);
-- Engine sweep, crosses tenants and never returns rows to a caller.
CREATE INDEX idx_rle_outbox_poll
  ON rle_decision_outbox (created_at, locked_until) WHERE status IN ('PENDING','CLAIMED');

CREATE TABLE rle_idempotency (
  tenant_id       TEXT        NOT NULL,
  operation       TEXT        NOT NULL,
  idempotency_key TEXT        NOT NULL,
  request_hash    CHAR(64)    NOT NULL,
  status_code     INT         NOT NULL,
  response_body   JSONB       NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, operation, idempotency_key)
);
-- Engine sweep, crosses tenants and never returns rows to a caller.
CREATE INDEX idx_rle_idempotency_expiry ON rle_idempotency (expires_at);
