-- Diagram layout of a definition version, stored apart from the definition (#102, PRE-026, PRE-024, DESIGN section
-- 5.4 and 7, ADR-0013). Each save appends one revision; revisions are never changed or deleted, so the history of a
-- diagram is kept and a layout can never alter the stored definition or its hash. definition_hash records the
-- definition the layout was drawn for. A layout needs no approval, so no lifecycle state is stored here.
-- Additive only: one new table with its primary key and the append-only triggers; pre_definition is only referenced.
-- Rollback, before any revision is saved: DROP TABLE pre_definition_layout; DROP FUNCTION
-- pre_definition_layout_append_only(). Afterwards the rows are the audit of who moved which box, and are kept.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_definition_layout (
  tenant_id       TEXT        NOT NULL,
  definition_id   VARCHAR(26) NOT NULL,
  revision        INT         NOT NULL CHECK (revision >= 1),
  layout_json     JSONB       NOT NULL CHECK (jsonb_typeof(layout_json) = 'object'),
  layout_hash     CHAR(64)    NOT NULL,
  definition_hash CHAR(64)    NOT NULL,
  source          TEXT        NOT NULL CHECK (source IN ('IMPORT','BPMN','COCKPIT','API')),
  created_by      TEXT        NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, definition_id, revision),
  FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition (tenant_id, id)
);

-- Rows are appended and never changed, as pre_migration_history. A refusal raises SQLSTATE 55000 naming the table.
-- Hosts that separate the migration role from the runtime role should also REVOKE UPDATE, DELETE, TRUNCATE ON
-- pre_definition_layout FROM <runtime role>.
CREATE FUNCTION pre_definition_layout_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'pre_definition_layout is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER pre_definition_layout_no_change
  BEFORE UPDATE OR DELETE ON pre_definition_layout
  FOR EACH ROW EXECUTE FUNCTION pre_definition_layout_append_only();

CREATE TRIGGER pre_definition_layout_no_truncate
  BEFORE TRUNCATE ON pre_definition_layout
  FOR EACH STATEMENT EXECUTE FUNCTION pre_definition_layout_append_only();
