-- Jobs: timers now; SLA escalations, async step attempts, compensation attempts and definition effective-date jobs
-- use the same table later (DESIGN sections 3.1, 4 and 7). A replica claims due rows with FOR UPDATE SKIP LOCKED and
-- a lease (locked_until = claim time + lease_duration) in one short transaction, then fires each job in a second
-- transaction that marks it DONE only while this replica still holds the claim (same locked_by and attempt). A
-- replica that dies leaves its rows CLAIMED; they are claimed again once the lease has expired (PRE-006).
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_timer (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  instance_id     VARCHAR(26),                   -- null for DEFINITION_EFFECTIVE jobs
  definition_id   VARCHAR(26),
  token_id        VARCHAR(26),
  job_kind        TEXT        NOT NULL CHECK (job_kind IN ('TIMER','SLA_ESCALATION','ASYNC_AGENT','ASYNC_SERVICE',
                                'COMPENSATION','DEFINITION_EFFECTIVE')),
  node_id         TEXT,
  status          TEXT        NOT NULL CHECK (status IN ('DUE','CLAIMED','DONE','FAILED','CANCELLED')),
  next_fire_at    TIMESTAMPTZ NOT NULL,
  lease_duration  INTERVAL    NOT NULL CHECK (lease_duration > INTERVAL '0'),
  locked_by       TEXT,
  locked_until    TIMESTAMPTZ,
  attempt         INT         NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  max_attempts    INT         NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
  attempt_id      VARCHAR(26),
  payload         JSONB       NOT NULL DEFAULT '{}',
  created_at      TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id),
  FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition (tenant_id, id),
  CHECK (status <> 'CLAIMED' OR (locked_by IS NOT NULL AND locked_until IS NOT NULL)),
  CHECK (job_kind = 'DEFINITION_EFFECTIVE' OR (instance_id IS NOT NULL AND token_id IS NOT NULL))
);
-- The engine sweep is the one read across tenants; it never returns rows to a caller (DESIGN section 7).
CREATE INDEX idx_pre_timer_poll
  ON pre_timer (next_fire_at, locked_until) WHERE status IN ('DUE','CLAIMED');
CREATE INDEX idx_pre_timer_instance
  ON pre_timer (tenant_id, instance_id) WHERE status IN ('DUE','CLAIMED');
