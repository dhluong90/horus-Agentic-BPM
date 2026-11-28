-- Grants for the application role (#700). Flyway runs this after every migration, as the schema owner.
-- It does nothing when no application role is configured (the single-role setup for development, which is not for
-- production). The role gets SELECT, INSERT, UPDATE and DELETE on the pre* tables and nothing else: no ownership,
-- no ALTER, no TRIGGER, no TRUNCATE, no CREATE in the schema. On a table guarded by an append-only trigger it also
-- loses UPDATE and DELETE, so the trigger is not the only thing that refuses an edit. The process-engine tables.
DO $horus$
DECLARE
    app text := '${horusAppRole}';
    sch text := current_schema();
    t record;
BEGIN
    IF app = '' THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        RAISE EXCEPTION 'the application role % does not exist; create it first (deploy/postgres/app-role-grants.sql), or unset horus.bpm.processes.database.app-role', app;
    END IF;
    IF app = current_user THEN
        RAISE EXCEPTION 'the application role % is the role that runs the migrations; use two roles', app;
    END IF;
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', sch, app);
    EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM %I', sch, app);
    FOR t IN SELECT c.relname FROM pg_class c
             WHERE c.relnamespace = sch::regnamespace AND c.relkind IN ('r', 'p') AND c.relname LIKE 'pre\_%'
    LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I.%I TO %I', sch, t.relname, app);
        EXECUTE format('REVOKE TRUNCATE, REFERENCES, TRIGGER ON %I.%I FROM %I', sch, t.relname, app);
    END LOOP;
    FOR t IN SELECT DISTINCT c.relname FROM pg_class c JOIN pg_trigger g ON g.tgrelid = c.oid
             WHERE c.relnamespace = sch::regnamespace AND c.relkind IN ('r', 'p') AND c.relname LIKE 'pre\_%'
               AND NOT g.tgisinternal AND g.tgname LIKE '%\_no\_change'
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON %I.%I FROM %I', sch, t.relname, app);
    END LOOP;
END
$horus$;
