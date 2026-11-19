-- Process Engine baseline: definitions, instances with their execution tree (ADR-0020), user tasks, history and
-- idempotency. Every key leads with tenant_id (TR-TEN-01); foreign keys include tenant_id, so a row can never
-- reference another tenant's row. Ids are ULIDs, times TIMESTAMPTZ. Jobs, incidents and compensation follow in
-- later migrations. Recorded in pre_schema_history, separate from the host's Flyway history.

CREATE TABLE pre_definition (
  tenant_id         TEXT        NOT NULL,
  id                VARCHAR(26) NOT NULL,
  key_name          TEXT        NOT NULL,
  version_num       INT         NOT NULL CHECK (version_num >= 1),
  status            TEXT        NOT NULL CHECK (status IN ('DRAFT','PENDING_APPROVAL','ACTIVE','RETIRED')),
  name              TEXT,
  schema_version    INT         NOT NULL DEFAULT 1,
  definition_json   JSONB       NOT NULL,
  definition_hash   CHAR(64)    NOT NULL,
  source_format     TEXT        NOT NULL CHECK (source_format IN ('JSON','YAML','BPMN_XML')),
  source_document   TEXT        NOT NULL,
  source_hash       CHAR(64)    NOT NULL,
  node_count        INT         NOT NULL CHECK (node_count <= 500),
  source            TEXT        NOT NULL CHECK (source IN ('IMPORT','STARTUP','AUTHORED','BPMN')),
  effective_from    TIMESTAMPTZ,
  lock_version      BIGINT      NOT NULL DEFAULT 0,
  created_by        TEXT        NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  approved_by       TEXT,
  approved_at       TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, key_name, version_num),
  CHECK (status <> 'ACTIVE' OR (approved_by IS NOT NULL AND effective_from IS NOT NULL))
);
CREATE INDEX idx_pre_definition_effective ON pre_definition (tenant_id, key_name, status, effective_from DESC);

CREATE TABLE pre_instance (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  definition_id   VARCHAR(26) NOT NULL,
  definition_key  TEXT        NOT NULL,
  definition_ver  INT         NOT NULL,
  business_key    TEXT,
  status          TEXT        NOT NULL CHECK (status IN ('RUNNING','COMPLETED','COMPENSATING','CANCELLED','FAILED')),
  variables       JSONB       NOT NULL,
  seq             BIGINT      NOT NULL DEFAULT 0,      -- kernel id counter (InstanceState.sequence)
  lock_version    BIGINT      NOT NULL DEFAULT 0,
  started_by      TEXT,
  started_at      TIMESTAMPTZ NOT NULL,
  ended_at        TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition (tenant_id, id)
);
CREATE UNIQUE INDEX uq_pre_instance_business_key
  ON pre_instance (tenant_id, definition_key, business_key) WHERE business_key IS NOT NULL;
CREATE INDEX idx_pre_instance_status ON pre_instance (tenant_id, status, started_at DESC);

CREATE TABLE pre_token (
  tenant_id            TEXT        NOT NULL,
  id                   VARCHAR(26) NOT NULL,
  instance_id          VARCHAR(26) NOT NULL,
  definition_id        VARCHAR(26) NOT NULL,
  token_kind           TEXT        NOT NULL CHECK (token_kind IN ('SCOPE','FLOW','MI_BODY')),
  scope_token_id       VARCHAR(26),
  current_node_id      TEXT        NOT NULL,
  status               TEXT        NOT NULL CHECK (status IN ('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER',
                                     'WAITING_EVENT','WAITING_SCOPE','JOINED','COMPLETED','COMPENSATED','CANCELLED')),
  activity_instance_id VARCHAR(26),
  locals               JSONB       NOT NULL DEFAULT '{}',
  entered_at           TIMESTAMPTZ NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, scope_token_id) REFERENCES pre_token (tenant_id, id),
  CHECK ((token_kind = 'SCOPE' AND status IN ('WAITING_SCOPE','COMPLETED','COMPENSATED','CANCELLED'))
      OR (token_kind = 'FLOW' AND status <> 'WAITING_SCOPE' AND scope_token_id IS NOT NULL)
      OR token_kind = 'MI_BODY')
);
CREATE INDEX idx_pre_token_instance ON pre_token (tenant_id, instance_id, status);
CREATE INDEX idx_pre_token_scope ON pre_token (tenant_id, scope_token_id, status);
CREATE UNIQUE INDEX uq_pre_token_root
  ON pre_token (tenant_id, instance_id) WHERE token_kind = 'SCOPE' AND scope_token_id IS NULL;
CREATE INDEX idx_pre_token_live
  ON pre_token (tenant_id, definition_id, status, current_node_id)
  WHERE status IN ('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT');

CREATE TABLE pre_task (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  instance_id     VARCHAR(26) NOT NULL,
  token_id        VARCHAR(26) NOT NULL,
  definition_id   VARCHAR(26) NOT NULL,
  node_id         TEXT        NOT NULL,
  name            TEXT,
  task_kind       TEXT        NOT NULL DEFAULT 'USER_TASK' CHECK (task_kind IN ('USER_TASK','APPROVAL_TASK')),
  assignee_user   TEXT,
  assignee_role   TEXT,
  assignee_group  TEXT,
  four_eyes       BOOLEAN     NOT NULL DEFAULT FALSE,
  status          TEXT        NOT NULL CHECK (status IN ('OPEN','CLAIMED','COMPLETED','CANCELLED')),
  lock_version    BIGINT      NOT NULL DEFAULT 0,
  completed_by    TEXT,
  created_at      TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, token_id) REFERENCES pre_token (tenant_id, id),
  CHECK (num_nonnulls(assignee_user, assignee_role, assignee_group) = 1)
);
CREATE INDEX idx_pre_task_instance ON pre_task (tenant_id, instance_id, created_at);
CREATE INDEX idx_pre_task_role ON pre_task (tenant_id, assignee_role, created_at, id)
  WHERE status = 'OPEN' AND assignee_role IS NOT NULL;

CREATE TABLE pre_history (
  tenant_id       TEXT        NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL,
  id              VARCHAR(26) NOT NULL,
  instance_id     VARCHAR(26) NOT NULL,
  definition_id   VARCHAR(26) NOT NULL,
  event_type      TEXT        NOT NULL,
  node_id         TEXT,
  token_id        VARCHAR(26),
  actor_id        TEXT,
  attempt_id      VARCHAR(26),
  details         JSONB       NOT NULL,
  PRIMARY KEY (tenant_id, occurred_at, id)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE pre_history_default PARTITION OF pre_history DEFAULT;
CREATE INDEX idx_pre_history_instance ON pre_history (tenant_id, instance_id, occurred_at, id);

CREATE TABLE pre_idempotency (
  tenant_id       TEXT        NOT NULL,
  operation       TEXT        NOT NULL,
  idempotency_key TEXT        NOT NULL,
  request_hash    CHAR(64)    NOT NULL,
  response_body   JSONB       NOT NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, operation, idempotency_key)
);
