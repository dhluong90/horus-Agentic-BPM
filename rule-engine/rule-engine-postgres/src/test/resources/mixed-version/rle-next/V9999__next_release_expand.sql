-- Test only (RLE-197): what a migration of the NEXT release does while a replica of this release still runs. It only
-- expands: a nullable column, a defaulted NOT NULL column and a new table.
SET LOCAL lock_timeout = '5s';

ALTER TABLE rle_version ADD COLUMN next_release_note TEXT;
ALTER TABLE rle_rule_set ADD COLUMN next_release_flag BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE rle_next_release_thing (
  tenant_id TEXT NOT NULL,
  id        TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id)
);
