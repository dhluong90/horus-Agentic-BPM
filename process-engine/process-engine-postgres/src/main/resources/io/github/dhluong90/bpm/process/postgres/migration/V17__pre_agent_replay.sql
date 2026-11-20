-- Offline replay of recorded agent answers (#98, PRE-092 to PRE-095, DESIGN section 4.1): one row per run in
-- pre_agent_replay and one report line per recorded answer in pre_agent_replay_case. A report holds ids, outcomes and
-- rule ids only, never an output value, and is not an audit record: runs are deleted replay.retention after they were
-- created, their lines with them. Nothing existing is touched.
-- Additive only: two new tables and their indexes. Rollback: DROP TABLE pre_agent_replay_case; DROP TABLE
-- pre_agent_replay; reports are test aids and need not be kept.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_agent_replay (
  tenant_id          TEXT        NOT NULL,
  id                 VARCHAR(26) NOT NULL,
  status             TEXT        NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
  definition_key     TEXT        NOT NULL,
  node_id            TEXT        NOT NULL,
  period_from        DATE        NOT NULL,
  period_to          DATE        NOT NULL CHECK (period_to >= period_from),
  candidate_rule_set TEXT        NOT NULL,
  candidate_version  INT         NOT NULL CHECK (candidate_version >= 1),
  candidate_schema   JSONB,
  idempotency_key    TEXT,
  request_hash       CHAR(64),
  cursor_at          TIMESTAMPTZ,
  cursor_id          VARCHAR(26),
  cases              INT         NOT NULL DEFAULT 0,
  unchanged          INT         NOT NULL DEFAULT 0,
  newly_allowed      INT         NOT NULL DEFAULT 0,
  newly_refused      INT         NOT NULL DEFAULT 0,
  newly_reviewed     INT         NOT NULL DEFAULT 0,
  overridden         INT         NOT NULL DEFAULT 0,
  not_replayed       INT         NOT NULL DEFAULT 0,
  with_masked_fields INT         NOT NULL DEFAULT 0,
  failure_code       TEXT,
  created_by         TEXT        NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL,
  completed_at       TIMESTAMPTZ,
  expires_at         TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CHECK ((idempotency_key IS NULL) = (request_hash IS NULL))
);
CREATE UNIQUE INDEX uq_pre_agent_replay_idempotency
  ON pre_agent_replay (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- The worker's claim across tenants, oldest first, and the retention purge.
CREATE INDEX idx_pre_agent_replay_open ON pre_agent_replay (created_at, id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX idx_pre_agent_replay_expiry ON pre_agent_replay (expires_at);

CREATE TABLE pre_agent_replay_case (
  tenant_id          TEXT        NOT NULL,
  replay_id          VARCHAR(26) NOT NULL,
  seq                INT         NOT NULL CHECK (seq >= 0),
  attempt_id         VARCHAR(26) NOT NULL,
  instance_id        VARCHAR(26) NOT NULL,
  recorded_at        TIMESTAMPTZ NOT NULL,
  recorded_outcome   TEXT        NOT NULL CHECK (recorded_outcome IN ('ALLOWED','REVIEW','REFUSED')),
  candidate_outcome  TEXT        NOT NULL CHECK (candidate_outcome IN ('ALLOWED','REVIEW','REFUSED','NOT_REPLAYED')),
  change             TEXT        NOT NULL CHECK (change IN ('UNCHANGED','NEWLY_ALLOWED','NEWLY_REFUSED',
                                   'NEWLY_ROUTED_TO_REVIEW','NOT_REPLAYED')),
  recorded_rule_ids  JSONB       NOT NULL,
  candidate_rule_ids JSONB       NOT NULL,
  code               TEXT,
  overridden         BOOLEAN     NOT NULL,
  masked_fields      INT         NOT NULL CHECK (masked_fields >= 0),
  PRIMARY KEY (tenant_id, replay_id, seq),
  FOREIGN KEY (tenant_id, replay_id) REFERENCES pre_agent_replay (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_pre_agent_replay_case_attempt ON pre_agent_replay_case (tenant_id, replay_id, attempt_id);
