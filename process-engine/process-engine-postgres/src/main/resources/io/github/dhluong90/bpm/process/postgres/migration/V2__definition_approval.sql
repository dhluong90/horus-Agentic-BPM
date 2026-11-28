-- The approved hashes of a definition version, kept apart from the version row (#172, ADR-0026 decision 4).
-- pre_definition holds the source and both hashes in one row, so whoever can update that row can rewrite all three.
-- This table is written once, when the version is approved, and never changed: the engine compares the hashes of
-- the row with these before it compiles the version.
-- Versions approved before this migration are recorded with the hashes their row holds now (trust on migration).

CREATE FUNCTION pre_definition_approval_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'pre_definition_approval is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TABLE pre_definition_approval (
    tenant_id text NOT NULL,
    definition_id character varying(26) NOT NULL,
    approved_by text NOT NULL,
    approved_at timestamp with time zone NOT NULL,
    source_hash character(64) NOT NULL,
    definition_hash character(64) NOT NULL,
    CONSTRAINT pre_definition_approval_pkey PRIMARY KEY (tenant_id, definition_id),
    CONSTRAINT pre_definition_approval_definition_fkey FOREIGN KEY (tenant_id, definition_id)
        REFERENCES pre_definition (tenant_id, id)
);

INSERT INTO pre_definition_approval (tenant_id, definition_id, approved_by, approved_at, source_hash, definition_hash)
SELECT tenant_id, id, approved_by, approved_at, source_hash, definition_hash
FROM pre_definition
WHERE approved_by IS NOT NULL AND approved_at IS NOT NULL;

CREATE TRIGGER pre_definition_approval_no_change BEFORE DELETE OR UPDATE ON pre_definition_approval
    FOR EACH ROW EXECUTE FUNCTION pre_definition_approval_append_only();

CREATE TRIGGER pre_definition_approval_no_truncate BEFORE TRUNCATE ON pre_definition_approval
    FOR EACH STATEMENT EXECUTE FUNCTION pre_definition_approval_append_only();
