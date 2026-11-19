-- rle_version_history is an audit trail: rows are appended and never changed (rule-engine/docs/DESIGN.md
-- section 3.3.3). The engine never updates or deletes a row; these triggers make the database refuse it too, so
-- the application role, or SQL injected elsewhere in the host, cannot rewrite or remove a REJECTED or APPROVED
-- row after the fact. A refusal raises SQLSTATE 55000 (object_not_in_prerequisite_state) naming the table.
--
-- Defence in depth, not a replacement for privileges: a role that owns the table can drop the trigger. Hosts that
-- separate the migration role from the runtime role should also run
--   REVOKE UPDATE, DELETE, TRUNCATE ON rle_version_history FROM <runtime role>;
-- (see the example host README, "Protect the audit tables").
--
-- Forward-only. Rollback, if ever needed, is a new forward migration:
--   DROP TRIGGER rle_version_history_no_truncate ON rle_version_history;
--   DROP TRIGGER rle_version_history_no_change ON rle_version_history;
--   DROP FUNCTION rle_version_history_append_only();

SET LOCAL lock_timeout = '5s';

CREATE FUNCTION rle_version_history_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'rle_version_history is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER rle_version_history_no_change
  BEFORE UPDATE OR DELETE ON rle_version_history
  FOR EACH ROW EXECUTE FUNCTION rle_version_history_append_only();

CREATE TRIGGER rle_version_history_no_truncate
  BEFORE TRUNCATE ON rle_version_history
  FOR EACH STATEMENT EXECUTE FUNCTION rle_version_history_append_only();
