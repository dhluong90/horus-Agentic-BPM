-- Asynchronous decision log (RLE-230, #45): with decision-log.mode: async the engine writes each decision to this queue
-- inside the caller's transaction, so it is durable when the caller's commit is acknowledged and rolls back with it;
-- a relay moves rows into rle_decision in batches (log_mode = 'ASYNC'). The default, transactional, never reads or
-- writes this table. A row leaves the queue in the same statement that writes it to rle_decision, so none is lost or
-- doubled; nothing is ever deleted any other way.
-- Rollback (forward-only otherwise; run by hand when no host runs mode async and the queue is empty):
--   DROP TABLE rle_decision_queue;
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
    queued_at         timestamp with time zone DEFAULT now() NOT NULL,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX idx_rle_decision_queue_order ON rle_decision_queue (queue_seq);
