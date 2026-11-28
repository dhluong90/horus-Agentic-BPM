-- Approval seal (RLE-223, #237): a hash over the tenant, rule set, version, canonical hash, effective date, approver
-- and approval time, written at approval and checked before a version is compiled or served. History rows of a
-- request and an approval also record the effective date; RESEALED is the history action of a re-seal after a key
-- rotation.
ALTER TABLE rle_version ADD COLUMN approval_seal text;

-- A version approved before this migration has nothing to verify. It gets the explicit marker 'legacy:pre-seal' here,
-- once, so an approved version with a NULL seal is never a pre-seal version: the engine refuses it.
UPDATE rle_version SET approval_seal = 'legacy:pre-seal' WHERE approved_at IS NOT NULL;

ALTER TABLE rle_version ADD CONSTRAINT rle_version_approval_seal_check CHECK (
    (approved_at IS NULL AND approval_seal IS NULL)
    OR (approved_at IS NOT NULL AND approval_seal IS NOT NULL AND (approval_seal = 'legacy:pre-seal'
        OR approval_seal ~ '^(sha256:[0-9a-f]{64}|hmac-sha256:[A-Za-z0-9_.-]{1,32}:[0-9a-f]{64})$')));

-- Nothing writes the marker after this migration: an update that sets it on another seal is refused.
CREATE FUNCTION rle_version_legacy_seal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.approval_seal = 'legacy:pre-seal' AND OLD.approval_seal IS DISTINCT FROM NEW.approval_seal THEN
        RAISE EXCEPTION 'rle_version_legacy_seal_guard: the legacy seal marker is written only by migration V2'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rle_version_legacy_seal_guard BEFORE UPDATE OF approval_seal ON rle_version
    FOR EACH ROW EXECUTE FUNCTION rle_version_legacy_seal_guard();

ALTER TABLE rle_version_history ADD COLUMN effective_from timestamp with time zone;
ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_action_check;
ALTER TABLE rle_version_history ADD CONSTRAINT rle_version_history_action_check CHECK (action = ANY (ARRAY[
    'CREATED', 'REQUESTED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'DRAFT_UPDATED', 'RETIRE_REQUESTED',
    'RETIRE_CANCELLED', 'RETIRED', 'EVIDENCE_EXPORTED', 'DISCARDED', 'RESEALED']));
