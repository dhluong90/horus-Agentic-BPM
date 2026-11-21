-- Recorded results that agent steps read as memory (#101, DESIGN sections 4.1 and 7): the latest accepted answer, or
-- the reviewer's answer that replaced a refused one, of each step that a later agent step of the definition names in
-- <horus:memory>. Only those steps are written, so a definition without memory writes no row. A refused answer is
-- never written. Memory reads only the rows of its own instance in the instance's tenant.
-- Additive only: one new table with its primary key, nothing existing is touched. Rollback, before any row is
-- written: DROP TABLE pre_step_result; afterwards an agent step that declares memory needs it, so the table stays.
SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_step_result (
  tenant_id            TEXT        NOT NULL,
  instance_id          VARCHAR(26) NOT NULL,
  node_id              TEXT        NOT NULL,
  definition_id        VARCHAR(26) NOT NULL,
  result_json          JSONB       NOT NULL CHECK (jsonb_typeof(result_json) = 'object'),
  source               TEXT        NOT NULL CHECK (source IN ('ANSWER','REVIEW')),
  activity_instance_id VARCHAR(26) NOT NULL,
  recorded_at          TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, instance_id, node_id),
  FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance (tenant_id, id)
);
