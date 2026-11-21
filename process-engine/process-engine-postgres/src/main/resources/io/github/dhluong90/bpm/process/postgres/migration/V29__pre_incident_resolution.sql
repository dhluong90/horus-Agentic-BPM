-- Retry and resolve incidents by API (#68, DESIGN section 3.6, PD-126). Resolving an incident with an operator's output
-- is a proposal that a second person approves, so an incident gains the state RESOLUTION_PENDING between OPEN and
-- RESOLVED, and the row keeps the proposal and both identities:
--   resolution        why it was resolved: RETRY_SUCCEEDED, COMPLETED_MANUALLY or INSTANCE_CANCELLED
--   resolved_by       who resolved it: the approver, the canceller, or the engine for a retry that succeeded
--   approval_mode     four-eyes or single (single only with incidents.resolve-approval=single, dev profile)
--   proposed_by/at    who proposed the resolution, and when
--   proposal_reason   the proposer's reason, at most lifecycle.reason-max-length (2,000) characters
--   proposal_output   the output the step completes with on approval, checked against the step's output schema
--   retrying          a retry is in flight: the step's job is due or running again, so neither a second retry nor a
--                     resolution is offered until it is accepted or fails again
-- The four-eyes CHECK holds the proposer and the approver apart whenever the mode is four-eyes.
--
-- Additive: nullable columns and one boolean with a constant default, metadata only (PostgreSQL 11 and later). The status CHECK of V13 is replaced; the new one is added NOT VALID and
-- validated in a second statement, which takes SHARE UPDATE EXCLUSIVE and does not block reads or writes. Every
-- existing row is OPEN or RESOLVED, so it validates. Old replicas of the previous release never write the new state
-- and read RESOLUTION_PENDING rows as not OPEN, so they do not retry or list a pending incident as open.
--
-- Rollback, while no row is RESOLUTION_PENDING: ALTER TABLE pre_incident DROP CONSTRAINT ck_pre_incident_status,
-- DROP CONSTRAINT ck_pre_incident_resolution, DROP CONSTRAINT ck_pre_incident_four_eyes, DROP COLUMN resolution,
-- DROP COLUMN resolved_by, DROP COLUMN approval_mode, DROP COLUMN proposed_by, DROP COLUMN proposed_at,
-- DROP COLUMN proposal_reason, DROP COLUMN proposal_output, DROP COLUMN retrying, ADD CONSTRAINT pre_incident_status_check
-- CHECK (status IN ('OPEN','RESOLVED')). After resolutions were recorded the columns are audit data and are kept.
SET LOCAL lock_timeout = '5s';

ALTER TABLE pre_incident
  ADD COLUMN resolution      TEXT,
  ADD COLUMN resolved_by     TEXT,
  ADD COLUMN approval_mode   TEXT,
  ADD COLUMN proposed_by     TEXT,
  ADD COLUMN proposed_at     TIMESTAMPTZ,
  ADD COLUMN proposal_reason TEXT,
  ADD COLUMN proposal_output JSONB,
  ADD COLUMN retrying        BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE pre_incident DROP CONSTRAINT pre_incident_status_check;
ALTER TABLE pre_incident ADD CONSTRAINT ck_pre_incident_status
  CHECK (status IN ('OPEN','RESOLUTION_PENDING','RESOLVED')) NOT VALID;
ALTER TABLE pre_incident VALIDATE CONSTRAINT ck_pre_incident_status;

ALTER TABLE pre_incident ADD CONSTRAINT ck_pre_incident_resolution
  CHECK (resolution IS NULL OR resolution IN ('RETRY_SUCCEEDED','COMPLETED_MANUALLY','INSTANCE_CANCELLED')) NOT VALID;
ALTER TABLE pre_incident VALIDATE CONSTRAINT ck_pre_incident_resolution;

ALTER TABLE pre_incident ADD CONSTRAINT ck_pre_incident_four_eyes
  CHECK (approval_mode IS DISTINCT FROM 'four-eyes' OR resolved_by IS NULL OR resolved_by <> proposed_by) NOT VALID;
ALTER TABLE pre_incident VALIDATE CONSTRAINT ck_pre_incident_four_eyes;

ALTER TABLE pre_incident ADD CONSTRAINT ck_pre_incident_proposal
  CHECK (status <> 'RESOLUTION_PENDING' OR (proposed_by IS NOT NULL AND proposed_at IS NOT NULL
    AND proposal_reason IS NOT NULL)) NOT VALID;
ALTER TABLE pre_incident VALIDATE CONSTRAINT ck_pre_incident_proposal;
