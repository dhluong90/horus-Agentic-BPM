-- Approval seal (RLE-223, #237): a hash over the tenant, rule set, version, canonical hash, effective date, approver
-- and approval time, written at approval and checked before a version is compiled or served. Null on a version
-- approved before this migration. History rows of a request and an approval also record the effective date.
ALTER TABLE rle_version ADD COLUMN approval_seal text;
ALTER TABLE rle_version ADD CONSTRAINT rle_version_approval_seal_check CHECK (
    approval_seal IS NULL OR (approved_at IS NOT NULL AND approval_seal ~ '^(sha256|hmac-sha256):[0-9a-f]{64}$'));
ALTER TABLE rle_version_history ADD COLUMN effective_from timestamp with time zone;
