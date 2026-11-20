-- Definition lifecycle with four eyes (ADR-0012, DESIGN sections 3.2 and 7): request, approve, reject and retire,
-- each with an append-only history row. Additive only: nullable columns, a new index on a small table, a new table.
-- The CHECK is the second line behind the service-layer refusal: the approver of a version is never its requester,
-- except the dev-profile system approver.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_definition
  ADD COLUMN requested_by      TEXT,
  ADD COLUMN requested_at      TIMESTAMPTZ,
  ADD COLUMN decision_comment  TEXT,
  ADD COLUMN retired_by        TEXT,
  ADD COLUMN retired_at        TIMESTAMPTZ,
  ADD COLUMN superseded_by     INT,
  ADD CONSTRAINT ck_pre_definition_four_eyes
    CHECK (approved_by IS NULL OR requested_by IS NULL OR approved_by <> requested_by
           OR approved_by = 'system:dev-auto-approve');

CREATE INDEX idx_pre_definition_pending
  ON pre_definition (tenant_id, requested_at) WHERE status = 'PENDING_APPROVAL';

CREATE TABLE pre_definition_history (
  tenant_id      TEXT        NOT NULL,
  id             VARCHAR(26) NOT NULL,
  definition_id  VARCHAR(26) NOT NULL,
  action         TEXT        NOT NULL CHECK (action IN ('IMPORTED','REQUESTED','APPROVED','REJECTED','RETIRED')),
  from_status    TEXT,
  to_status      TEXT        NOT NULL,
  actor_id       TEXT        NOT NULL,
  comment        TEXT,
  details        JSONB       NOT NULL DEFAULT '{}',
  occurred_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition (tenant_id, id)
);
CREATE INDEX idx_pre_definition_history ON pre_definition_history (tenant_id, definition_id, occurred_at, id);

-- pre_definition_history is an audit trail (TR-OBS-01): rows are appended and never changed. The engine never updates
-- or deletes a row; these triggers make the database refuse it too, as rle_version_history does in the Rule Engine.
-- A refusal raises SQLSTATE 55000 naming the table. Defence in depth, not a replacement for privileges: a role that
-- owns the table can drop the trigger, so hosts that separate the migration role from the runtime role should also
-- REVOKE UPDATE, DELETE, TRUNCATE ON pre_definition_history FROM <runtime role>.
-- Rollback, if ever needed, is a new forward migration that drops the two triggers and the function.
CREATE FUNCTION pre_definition_history_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'pre_definition_history is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER pre_definition_history_no_change
  BEFORE UPDATE OR DELETE ON pre_definition_history
  FOR EACH ROW EXECUTE FUNCTION pre_definition_history_append_only();

CREATE TRIGGER pre_definition_history_no_truncate
  BEFORE TRUNCATE ON pre_definition_history
  FOR EACH STATEMENT EXECUTE FUNCTION pre_definition_history_append_only();
