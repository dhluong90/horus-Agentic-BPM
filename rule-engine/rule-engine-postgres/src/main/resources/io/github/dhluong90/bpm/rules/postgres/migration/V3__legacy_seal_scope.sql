-- Legacy seal marker scope (RLE-231, follow-up to #658 and #644). V2 wrote the marker 'legacy:pre-seal' on every version
-- approved before seals existed, but the engine accepted the marker by its value alone. From here the marker stands
-- only for a version stamped with legacy_backfilled_at, the time this migration found it carrying the marker. No row
-- created later can be stamped, so no row created later can carry the marker.
ALTER TABLE rle_version ADD COLUMN legacy_backfilled_at timestamp with time zone;

ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_action_check;
ALTER TABLE rle_version_history ADD CONSTRAINT rle_version_history_action_check CHECK (action = ANY (ARRAY[
    'CREATED', 'REQUESTED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'DRAFT_UPDATED', 'RETIRE_REQUESTED',
    'RETIRE_CANCELLED', 'RETIRED', 'EVIDENCE_EXPORTED', 'DISCARDED', 'RESEALED', 'LEGACY_SEALED']));

-- One immutable history row per backfilled version, written before the stamp so the list is exactly what is stamped.
INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor, reason,
                                 lock_version, occurred_at, rule_set_id)
SELECT v.tenant_id, upper(substr(md5(v.id || ':legacy-sealed'), 1, 26)), v.id, 'LEGACY_SEALED', v.status, v.status,
       'system:migration-v3',
       'approved before approval seals existed; migration V2 gave it the pre-seal marker, V3 scopes the marker to it',
       v.lock_version, now(), v.rule_set_id
FROM rle_version v
WHERE v.approval_seal = 'legacy:pre-seal' AND v.approved_at IS NOT NULL;

UPDATE rle_version SET legacy_backfilled_at = now()
WHERE approval_seal = 'legacy:pre-seal' AND approved_at IS NOT NULL;

DO $$
DECLARE
    n integer;
    ids text;
BEGIN
    SELECT count(*), coalesce(string_agg(id, ',' ORDER BY id), '') INTO n, ids
    FROM rle_version WHERE legacy_backfilled_at IS NOT NULL;
    RAISE NOTICE 'rle V3: pre-seal marker scoped to % version(s) approved before seals existed: %', n, ids;
END $$;

-- The marker needs the stamp.
ALTER TABLE rle_version ADD CONSTRAINT rle_version_legacy_seal_scope_check CHECK (
    approval_seal IS DISTINCT FROM 'legacy:pre-seal' OR legacy_backfilled_at IS NOT NULL);

-- Nothing sets or moves the stamp after this migration: an insert carrying it, or an update that sets or changes it,
-- is refused with the code LEGACY_SEAL_SCOPE. The marker itself stays refused by rle_version_legacy_seal_guard (V2).
CREATE FUNCTION rle_version_legacy_scope_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND (NEW.legacy_backfilled_at IS NOT NULL OR NEW.approval_seal = 'legacy:pre-seal') THEN
        RAISE EXCEPTION 'LEGACY_SEAL_SCOPE: rle_version_legacy_scope_guard: a version created after migration V3 cannot carry the legacy marker'
            USING ERRCODE = '23514';
    ELSIF TG_OP = 'UPDATE' AND OLD.legacy_backfilled_at IS DISTINCT FROM NEW.legacy_backfilled_at THEN
        RAISE EXCEPTION 'LEGACY_SEAL_SCOPE: rle_version_legacy_scope_guard: the legacy marker scope is written only by migration V3'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rle_version_legacy_scope_guard BEFORE INSERT OR UPDATE OF legacy_backfilled_at ON rle_version
    FOR EACH ROW EXECUTE FUNCTION rle_version_legacy_scope_guard();
