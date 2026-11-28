-- Asynchronous decision log (RLE-234, #45): with decision-log.mode: async the engine writes each decision to this queue
-- inside the caller's transaction, so it is durable when the caller's commit is acknowledged and rolls back with it;
-- a relay moves rows into rle_decision (log_mode = 'ASYNC'). The default, transactional, never reads or writes it.
--
-- The queue is append-only. A trigger refuses every UPDATE and TRUNCATE, and a DELETE unless the very same decision
-- (tenant, id, time, inputs, outcome) is already in rle_decision, so a decision is never lost and never altered
-- in the queue; it only leaves when its copy is in the log. What happens to a row is not stored on the row but as new
-- rows of rle_decision_queue_event, itself append-only (no UPDATE, DELETE or TRUNCATE at all): failed attempts with
-- their back-off, the dead letter, and the four-eyes retry or resolve of a dead letter, with who and when.
-- Rollback (forward-only otherwise; run by hand when no host runs mode async and the queue is empty):
--   DROP TABLE rle_decision_queue_event;
--   DROP TABLE rle_decision_queue;
--   DROP FUNCTION rle_decision_queue_guard();
--   DROP FUNCTION rle_decision_queue_event_guard();
CREATE TABLE rle_decision_queue (
    queue_seq         bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id         text NOT NULL,
    created_at        timestamp with time zone NOT NULL,
    id                character varying(26) NOT NULL,
    rule_set_name     text NOT NULL,
    version_num       integer NOT NULL,
    hit_policy        text NOT NULL,
    outcome           text NOT NULL,
    error_code        text,
    masked_inputs     jsonb NOT NULL,
    outputs           jsonb,
    matched_rule_ids  jsonb NOT NULL,
    caller_id         text,
    correlation_id    text,
    eval_clock        timestamp with time zone NOT NULL,
    duration_micros   bigint NOT NULL,
    pinned            boolean DEFAULT false NOT NULL,
    on_behalf_of      text,
    function_versions text,
    budget_limit      text,
    step_budget       integer,
    timeout_ms        integer,
    queued_at         timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (queue_seq)
);

-- What happened to a queue row, one new row per step; the state of a queue row is its latest event (none: waiting).
-- kind: ATTEMPT_FAILED (retried after next_attempt_at), DEAD_LETTERED (out of attempts: the relay skips it and later
-- rows go on), RETRY_REQUESTED or RESOLVE_REQUESTED (an operator asks, comment required), REQUEST_REJECTED (a second
-- person refuses: back to dead letter), RETRY_APPROVED (a second person approves: waiting again, attempts from 0),
-- RESOLVE_APPROVED (a second person accepts the row as handled outside the queue; it stays as evidence). Nothing
-- deletes a row, and no event row has a foreign key to the queue row, because a relayed queue row may go.
CREATE TABLE rle_decision_queue_event (
    event_seq       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue_seq       bigint NOT NULL,
    tenant_id       text NOT NULL,
    decision_id     character varying(26) NOT NULL,
    kind            text NOT NULL,
    attempt         integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    actor           text,
    comment         text,
    detail          text,
    occurred_at     timestamp with time zone NOT NULL,
    CONSTRAINT rle_decision_queue_event_kind_check CHECK (kind IN ('ATTEMPT_FAILED', 'DEAD_LETTERED',
        'RETRY_REQUESTED', 'RESOLVE_REQUESTED', 'REQUEST_REJECTED', 'RETRY_APPROVED', 'RESOLVE_APPROVED')),
    CONSTRAINT rle_decision_queue_event_actor_check CHECK (
        (kind IN ('ATTEMPT_FAILED', 'DEAD_LETTERED')) = (actor IS NULL)),
    CONSTRAINT rle_decision_queue_event_comment_check CHECK (
        kind IN ('ATTEMPT_FAILED', 'DEAD_LETTERED', 'RETRY_APPROVED', 'REQUEST_REJECTED', 'RESOLVE_APPROVED')
        OR (comment IS NOT NULL AND length(comment) BETWEEN 3 AND 500))
);
CREATE INDEX idx_rle_decision_queue_event_row ON rle_decision_queue_event (queue_seq, event_seq DESC);
CREATE INDEX idx_rle_decision_queue_event_decision ON rle_decision_queue_event (tenant_id, decision_id);
CREATE INDEX idx_rle_decision_queue_order ON rle_decision_queue (queue_seq);

CREATE FUNCTION rle_decision_queue_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM rle_decision d
                   WHERE d.tenant_id = OLD.tenant_id AND d.id = OLD.id AND d.created_at = OLD.created_at
                     AND d.outcome = OLD.outcome AND d.masked_inputs = OLD.masked_inputs) THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'rle_decision_queue is append-only: decision % of tenant % leaves the queue only once the same decision is in rle_decision',
            OLD.id, OLD.tenant_id USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'rle_decision_queue is append-only: % is refused', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;
CREATE TRIGGER rle_decision_queue_append_only BEFORE UPDATE OR DELETE ON rle_decision_queue
    FOR EACH ROW EXECUTE FUNCTION rle_decision_queue_guard();
CREATE TRIGGER rle_decision_queue_no_truncate BEFORE TRUNCATE ON rle_decision_queue
    FOR EACH STATEMENT EXECUTE FUNCTION rle_decision_queue_guard();

CREATE FUNCTION rle_decision_queue_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'rle_decision_queue_event is append-only: % is refused', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;
CREATE TRIGGER rle_decision_queue_event_append_only BEFORE UPDATE OR DELETE ON rle_decision_queue_event
    FOR EACH ROW EXECUTE FUNCTION rle_decision_queue_event_guard();
CREATE TRIGGER rle_decision_queue_event_no_truncate BEFORE TRUNCATE ON rle_decision_queue_event
    FOR EACH STATEMENT EXECUTE FUNCTION rle_decision_queue_event_guard();
