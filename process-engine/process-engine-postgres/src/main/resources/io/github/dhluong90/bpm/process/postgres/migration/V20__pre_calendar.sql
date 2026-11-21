-- Business calendars (#72, PRE-008; PD-118 as amended by PD-131). A user task with horus:sla gets its due time from
-- working time in the tenant's calendar named by horus:calendar: time zone, working hours per weekday, holidays. The
-- due time is computed once when the task is created and stored on the task (V21); nothing here is read again for an
-- existing task, so a later change never moves a deadline.
--
-- A calendar is created or changed only through a row of pre_calendar_change: a proposal that a second holder of
-- processes:calendar:manage approves (FOUR_EYES), or, in the dev profile only, that its proposer applies (SINGLE).
-- ck_pre_calendar_change_four_eyes refuses an approved FOUR_EYES row whose approver is its proposer, so the rule holds
-- for any writer of this table. The change rows are the record: proposer, approver, old and new value, reason.
--
-- New, empty tables: no lock on an existing table. Forward-only. Rollback, if ever needed, is a new forward migration:
--   DROP TABLE pre_calendar_change; DROP TABLE pre_holiday; DROP TABLE pre_calendar;
-- after which a user task naming a calendar fails its step (CALENDAR_NOT_FOUND) until the host supplies its own
-- BusinessCalendar; tasks already created keep their due time.

SET LOCAL lock_timeout = '5s';

CREATE TABLE pre_calendar (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  calendar_key    TEXT        NOT NULL CHECK (length(calendar_key) BETWEEN 1 AND 100),
  timezone        TEXT        NOT NULL,
  weekly_hours    JSONB       NOT NULL CHECK (jsonb_typeof(weekly_hours) = 'object'),
  lock_version    BIGINT      NOT NULL CHECK (lock_version >= 1),
  updated_by      TEXT        NOT NULL,
  approved_by     TEXT        NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uq_pre_calendar_key UNIQUE (tenant_id, calendar_key)
);

CREATE TABLE pre_holiday (
  tenant_id       TEXT        NOT NULL,
  id              VARCHAR(26) NOT NULL,
  calendar_id     VARCHAR(26) NOT NULL,
  holiday_date    DATE        NOT NULL,
  description     TEXT        NOT NULL DEFAULT '' CHECK (length(description) <= 200),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT uq_pre_holiday_date UNIQUE (tenant_id, calendar_id, holiday_date),
  FOREIGN KEY (tenant_id, calendar_id) REFERENCES pre_calendar (tenant_id, id)
);

CREATE TABLE pre_calendar_change (
  tenant_id        TEXT        NOT NULL,
  id               VARCHAR(26) NOT NULL,
  calendar_key     TEXT        NOT NULL,
  status           TEXT        NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  approval         TEXT        NOT NULL CHECK (approval IN ('FOUR_EYES','SINGLE')),
  base_version     BIGINT      NOT NULL CHECK (base_version >= 0),
  previous_value   JSONB,
  proposed_value   JSONB       NOT NULL CHECK (jsonb_typeof(proposed_value) = 'object'),
  reason           TEXT        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  proposed_by      TEXT        NOT NULL,
  proposed_at      TIMESTAMPTZ NOT NULL,
  decided_by       TEXT,
  decided_at       TIMESTAMPTZ,
  decision_comment TEXT        CHECK (length(decision_comment) <= 500),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT ck_pre_calendar_change_decided CHECK ((status = 'PENDING') = (decided_by IS NULL AND decided_at IS NULL)),
  CONSTRAINT ck_pre_calendar_change_four_eyes CHECK (status <> 'APPROVED' OR approval = 'SINGLE'
    OR decided_by <> proposed_by)
);
CREATE INDEX idx_pre_calendar_change_key ON pre_calendar_change (tenant_id, calendar_key, proposed_at, id);
