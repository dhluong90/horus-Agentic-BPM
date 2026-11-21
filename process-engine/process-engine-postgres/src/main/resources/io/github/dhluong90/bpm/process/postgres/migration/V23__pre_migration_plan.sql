-- Migration plans of running instances (#74, PRE-014, PRE-174, PRE-175, DESIGN sections 4.3 and 7; open question
-- 11.5). A plan names one definition key, the version its instances run on and the version they would move to, the
-- hash of that pair, and the approval its execution must meet: four-eyes unless the host runs the dev profile alone
-- (PD-114 as amended by PD-131). Creating a plan moves nothing; the dry run only reads. Executing a plan follows in a
-- later release and widens the status CHECK then.
-- Additive only: two new tables, their indexes and the append-only triggers of the history. pre_definition is only
-- referenced. Rollback: DROP TABLE pre_migration_history; DROP TABLE pre_migration; DROP FUNCTION
-- pre_migration_history_append_only(); no instance refers to a plan yet.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_migration (
  tenant_id            TEXT        NOT NULL,
  id                   VARCHAR(26) NOT NULL,
  definition_key       TEXT        NOT NULL,
  source_definition_id VARCHAR(26) NOT NULL,
  source_version       INT         NOT NULL CHECK (source_version >= 1),
  target_definition_id VARCHAR(26) NOT NULL,
  target_version       INT         NOT NULL CHECK (target_version >= 1),
  plan_hash            CHAR(64)    NOT NULL,
  approval             TEXT        NOT NULL CHECK (approval IN ('four-eyes','single')),
  status               TEXT        NOT NULL CHECK (status IN ('PLANNED')),
  created_by           TEXT        NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  lock_version         BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, source_definition_id) REFERENCES pre_definition (tenant_id, id),
  FOREIGN KEY (tenant_id, target_definition_id) REFERENCES pre_definition (tenant_id, id),
  CHECK (source_definition_id <> target_definition_id)
);
CREATE INDEX idx_pre_migration_definition ON pre_migration (tenant_id, definition_key, created_at DESC, id);

CREATE TABLE pre_migration_history (
  tenant_id    TEXT        NOT NULL,
  id           VARCHAR(26) NOT NULL,
  migration_id VARCHAR(26) NOT NULL,
  action       TEXT        NOT NULL CHECK (action IN ('PLANNED')),
  actor_id     TEXT        NOT NULL,
  details      JSONB       NOT NULL DEFAULT '{}',
  occurred_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, migration_id) REFERENCES pre_migration (tenant_id, id)
);
CREATE INDEX idx_pre_migration_history ON pre_migration_history (tenant_id, migration_id, occurred_at, id);

-- An audit trail (TR-OBS-01), as pre_definition_history: rows are appended and never changed. A refusal raises
-- SQLSTATE 55000 naming the table. Hosts that separate the migration role from the runtime role should also
-- REVOKE UPDATE, DELETE, TRUNCATE ON pre_migration_history FROM <runtime role>.
CREATE FUNCTION pre_migration_history_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'pre_migration_history is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER pre_migration_history_no_change
  BEFORE UPDATE OR DELETE ON pre_migration_history
  FOR EACH ROW EXECUTE FUNCTION pre_migration_history_append_only();

CREATE TRIGGER pre_migration_history_no_truncate
  BEFORE TRUNCATE ON pre_migration_history
  FOR EACH STATEMENT EXECUTE FUNCTION pre_migration_history_append_only();
