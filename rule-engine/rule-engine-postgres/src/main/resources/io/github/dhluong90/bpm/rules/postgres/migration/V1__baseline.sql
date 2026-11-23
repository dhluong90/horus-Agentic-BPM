-- Rule Engine schema baseline (V1).
-- One migration that produces the whole schema. Generated from a fresh PostgreSQL after the former migration
-- chain, as the schema-only dump (history table excluded, schema qualifiers removed so it runs in the host's
-- schema). The next migration is V2. Until the 1.0 release this file may be regenerated; after it, never edited.


CREATE FUNCTION rle_append_only_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION rle_decision_log_drop_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'rle_decision_log_drop is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION rle_version_history_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'rle_version_history is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TABLE rle_decision (
    tenant_id text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    id character varying(26) NOT NULL,
    rule_set_name text NOT NULL,
    version_num integer NOT NULL,
    hit_policy text NOT NULL,
    outcome text NOT NULL,
    error_code text,
    masked_inputs jsonb NOT NULL,
    outputs jsonb,
    matched_rule_ids jsonb NOT NULL,
    caller_id text,
    correlation_id text,
    eval_clock timestamp with time zone NOT NULL,
    duration_micros bigint NOT NULL,
    log_mode text NOT NULL,
    pinned boolean DEFAULT false NOT NULL,
    on_behalf_of text,
    function_versions text,
    CONSTRAINT rle_decision_function_versions_check CHECK (((function_versions IS NULL) OR ((length(function_versions) >= 3) AND (length(function_versions) <= 8192)))),
    CONSTRAINT rle_decision_log_mode_check CHECK ((log_mode = ANY (ARRAY['TRANSACTIONAL'::text, 'ASYNC'::text]))),
    CONSTRAINT rle_decision_on_behalf_of_check CHECK (((on_behalf_of IS NULL) OR ((length(on_behalf_of) >= 1) AND (length(on_behalf_of) <= 200)))),
    CONSTRAINT rle_decision_outcome_check CHECK ((outcome = ANY (ARRAY['OK'::text, 'UNIQUE_HIT_VIOLATION'::text, 'EVALUATION_ERROR'::text])))
)
PARTITION BY RANGE (created_at);

CREATE TABLE rle_decision_default (
    tenant_id text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    id character varying(26) NOT NULL,
    rule_set_name text NOT NULL,
    version_num integer NOT NULL,
    hit_policy text NOT NULL,
    outcome text NOT NULL,
    error_code text,
    masked_inputs jsonb NOT NULL,
    outputs jsonb,
    matched_rule_ids jsonb NOT NULL,
    caller_id text,
    correlation_id text,
    eval_clock timestamp with time zone NOT NULL,
    duration_micros bigint NOT NULL,
    log_mode text NOT NULL,
    pinned boolean DEFAULT false NOT NULL,
    on_behalf_of text,
    function_versions text,
    CONSTRAINT rle_decision_function_versions_check CHECK (((function_versions IS NULL) OR ((length(function_versions) >= 3) AND (length(function_versions) <= 8192)))),
    CONSTRAINT rle_decision_log_mode_check CHECK ((log_mode = ANY (ARRAY['TRANSACTIONAL'::text, 'ASYNC'::text]))),
    CONSTRAINT rle_decision_on_behalf_of_check CHECK (((on_behalf_of IS NULL) OR ((length(on_behalf_of) >= 1) AND (length(on_behalf_of) <= 200)))),
    CONSTRAINT rle_decision_outcome_check CHECK ((outcome = ANY (ARRAY['OK'::text, 'UNIQUE_HIT_VIOLATION'::text, 'EVALUATION_ERROR'::text])))
);

CREATE TABLE rle_decision_log_drop (
    tenant_id text NOT NULL,
    partition_name text NOT NULL,
    range_from timestamp with time zone NOT NULL,
    range_to timestamp with time zone NOT NULL,
    decision_count bigint NOT NULL,
    retention text NOT NULL,
    cutoff timestamp with time zone NOT NULL,
    dropped_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_decision_log_drop_check CHECK (((range_from < range_to) AND (range_to <= cutoff))),
    CONSTRAINT rle_decision_log_drop_decision_count_check CHECK ((decision_count >= 0)),
    CONSTRAINT rle_decision_log_drop_partition_name_check CHECK ((partition_name ~ '^rle_decision_[0-9]{4}_[0-9]{2}$'::text)),
    CONSTRAINT rle_decision_log_drop_retention_check CHECK ((retention ~ '^P[0-9]+M$'::text)),
    CONSTRAINT rle_decision_log_drop_tenant_id_check CHECK ((tenant_id <> ''::text))
);

CREATE TABLE rle_decision_outbox (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    payload jsonb NOT NULL,
    status text NOT NULL,
    locked_by text,
    locked_until timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_decision_outbox_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'CLAIMED'::text, 'SENT'::text])))
);

CREATE TABLE rle_engine_key (
    name text NOT NULL,
    key_bytes bytea NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_engine_key_key_bytes_check CHECK ((octet_length(key_bytes) >= 32))
);

CREATE TABLE rle_idempotency (
    tenant_id text NOT NULL,
    operation text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash character(64) NOT NULL,
    status_code integer NOT NULL,
    response_body jsonb NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

CREATE TABLE rle_masking_key_check (
    tenant_id text NOT NULL,
    key_id text NOT NULL,
    check_value text NOT NULL,
    first_seen_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_masking_key_check_check_value_check CHECK ((check_value ~ '^[0-9a-f]{16}$'::text)),
    CONSTRAINT rle_masking_key_check_key_id_check CHECK ((key_id ~ '^[A-Za-z0-9_-]{1,32}$'::text)),
    CONSTRAINT rle_masking_key_check_tenant_id_check CHECK ((tenant_id <> ''::text))
);

CREATE TABLE rle_rule (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    version_id character varying(26) NOT NULL,
    row_order integer NOT NULL,
    rule_id text NOT NULL,
    when_expr_json jsonb NOT NULL,
    then_expr_json jsonb NOT NULL
);

CREATE TABLE rle_rule_set (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    name text NOT NULL,
    description text,
    cache_epoch bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE rle_setting_seen (
    tenant_id text NOT NULL,
    setting text NOT NULL,
    value text NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_setting_seen_setting_check CHECK (((length(setting) >= 1) AND (length(setting) <= 100))),
    CONSTRAINT rle_setting_seen_value_check CHECK ((length(value) <= 200))
);

CREATE TABLE rle_simulation_report (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    rule_set_name text NOT NULL,
    version_num integer NOT NULL,
    canonical_hash character(64) NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    source text NOT NULL,
    period_from timestamp with time zone,
    period_to timestamp with time zone,
    period_limit integer,
    truncated boolean NOT NULL,
    total_count integer NOT NULL,
    changed_count integer NOT NULL,
    unchanged_count integer NOT NULL,
    failed_count integer NOT NULL,
    not_replayable_count integer NOT NULL,
    languages text NOT NULL,
    report_rows jsonb NOT NULL,
    rows_truncated boolean NOT NULL,
    CONSTRAINT rle_simulation_report_changed_count_check CHECK ((changed_count >= 0)),
    CONSTRAINT rle_simulation_report_check CHECK ((total_count = (((changed_count + unchanged_count) + failed_count) + not_replayable_count))),
    CONSTRAINT rle_simulation_report_check1 CHECK (((source = 'REPLAY'::text) = ((period_from IS NOT NULL) AND (period_to IS NOT NULL)))),
    CONSTRAINT rle_simulation_report_check2 CHECK (((source = 'REPLAY'::text) OR ((period_limit IS NULL) AND (NOT truncated)))),
    CONSTRAINT rle_simulation_report_failed_count_check CHECK ((failed_count >= 0)),
    CONSTRAINT rle_simulation_report_languages_check CHECK (((length(languages) >= 2) AND (length(languages) <= 200))),
    CONSTRAINT rle_simulation_report_not_replayable_count_check CHECK ((not_replayable_count >= 0)),
    CONSTRAINT rle_simulation_report_period_limit_check CHECK (((period_limit IS NULL) OR ((period_limit >= 1) AND (period_limit <= 1000)))),
    CONSTRAINT rle_simulation_report_report_rows_check CHECK (((jsonb_typeof(report_rows) = 'array'::text) AND (jsonb_array_length(report_rows) <= 50))),
    CONSTRAINT rle_simulation_report_source_check CHECK ((source = ANY (ARRAY['SAMPLES'::text, 'REPLAY'::text]))),
    CONSTRAINT rle_simulation_report_total_count_check CHECK ((total_count >= 0)),
    CONSTRAINT rle_simulation_report_unchanged_count_check CHECK ((unchanged_count >= 0)),
    CONSTRAINT rle_simulation_report_version_num_check CHECK ((version_num >= 1))
);

CREATE TABLE rle_simulation_report_attachment (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    rule_set_name text NOT NULL,
    version_num integer NOT NULL,
    report_id character varying(26) NOT NULL,
    attached_by text NOT NULL,
    attached_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_simulation_report_attachment_version_num_check CHECK ((version_num >= 1))
);

CREATE TABLE rle_version (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    rule_set_id character varying(26) NOT NULL,
    version_num integer NOT NULL,
    status text NOT NULL,
    hit_policy text NOT NULL,
    effective_from timestamp with time zone,
    canonical_json jsonb NOT NULL,
    canonical_hash character(64) NOT NULL,
    source_format text NOT NULL,
    source_document text NOT NULL,
    source_hash character(64) NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    requested_by text,
    requested_at timestamp with time zone,
    approved_by text,
    approved_at timestamp with time zone,
    approval_comment text,
    created_at timestamp with time zone NOT NULL,
    created_by text,
    retired_at timestamp with time zone,
    retire_requested_by text,
    retire_requested_at timestamp with time zone,
    retire_reason text,
    retire_confirm_no_version boolean,
    copied_from integer,
    CONSTRAINT rle_version_copied_from_check CHECK (((copied_from IS NULL) OR ((copied_from >= 1) AND (copied_from < version_num)))),
    CONSTRAINT rle_version_four_eyes CHECK (((approved_by IS NULL) OR ((approved_by IS DISTINCT FROM requested_by) AND (approved_by IS DISTINCT FROM created_by)))),
    CONSTRAINT rle_version_hit_policy_check CHECK ((hit_policy = ANY (ARRAY['FIRST'::text, 'UNIQUE'::text, 'COLLECT'::text]))),
    CONSTRAINT rle_version_retire_request_complete CHECK ((((retire_requested_by IS NULL) AND (retire_requested_at IS NULL) AND (retire_reason IS NULL) AND (retire_confirm_no_version IS NULL)) OR ((retire_requested_by IS NOT NULL) AND (retire_requested_at IS NOT NULL) AND (retire_reason IS NOT NULL) AND (retire_confirm_no_version IS NOT NULL)))),
    CONSTRAINT rle_version_retire_request_published CHECK (((retire_requested_by IS NULL) OR (status = 'PUBLISHED'::text))),
    CONSTRAINT rle_version_retired_at_check CHECK (((status = 'RETIRED'::text) = (retired_at IS NOT NULL))),
    CONSTRAINT rle_version_source_format_check CHECK ((source_format = ANY (ARRAY['JSON'::text, 'YAML'::text, 'DMN_XML'::text]))),
    CONSTRAINT rle_version_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'PUBLISHED'::text, 'RETIRED'::text, 'DISCARDED'::text])))
);

CREATE TABLE rle_version_function (
    tenant_id text NOT NULL,
    version_id character varying(26) NOT NULL,
    function_name text NOT NULL,
    function_version text NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT rle_version_function_function_name_check CHECK ((function_name ~ '^[a-z][A-Za-z0-9]{0,63}$'::text)),
    CONSTRAINT rle_version_function_function_version_check CHECK (((length(function_version) >= 1) AND (length(function_version) <= 64)))
);

CREATE TABLE rle_version_history (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    version_id character varying(26),
    action text NOT NULL,
    from_status text,
    to_status text,
    actor text NOT NULL,
    reason text,
    superseded_by integer,
    approval_mode text,
    lock_version bigint,
    occurred_at timestamp with time zone NOT NULL,
    canonical_hash character(64),
    previous_canonical_hash character(64),
    no_version_in_effect boolean,
    acknowledged_warnings jsonb,
    masked_by_default_inputs jsonb,
    retire_requested_by text,
    copied_from integer,
    rule_set_id character varying(26),
    evidence jsonb,
    CONSTRAINT rle_version_history_ack_arrays CHECK ((((acknowledged_warnings IS NULL) OR (jsonb_typeof(acknowledged_warnings) = 'array'::text)) AND ((masked_by_default_inputs IS NULL) OR (jsonb_typeof(masked_by_default_inputs) = 'array'::text)))),
    CONSTRAINT rle_version_history_ack_on_approval CHECK (((action = 'APPROVED'::text) OR ((acknowledged_warnings IS NULL) AND (masked_by_default_inputs IS NULL)))),
    CONSTRAINT rle_version_history_action_check CHECK ((action = ANY (ARRAY['CREATED'::text, 'REQUESTED'::text, 'APPROVED'::text, 'REJECTED'::text, 'WITHDRAWN'::text, 'DRAFT_UPDATED'::text, 'RETIRE_REQUESTED'::text, 'RETIRE_CANCELLED'::text, 'RETIRED'::text, 'EVIDENCE_EXPORTED'::text, 'DISCARDED'::text]))),
    CONSTRAINT rle_version_history_approval_mode_check CHECK ((approval_mode = ANY (ARRAY['single'::text, 'four-eyes'::text, 'system'::text]))),
    CONSTRAINT rle_version_history_copied_from_check CHECK (((copied_from IS NULL) OR ((action = 'CREATED'::text) AND (copied_from >= 1)))),
    CONSTRAINT rle_version_history_draft_updated_hashes CHECK (((action = 'DRAFT_UPDATED'::text) = ((canonical_hash IS NOT NULL) AND (previous_canonical_hash IS NOT NULL)))),
    CONSTRAINT rle_version_history_from_status_check CHECK ((from_status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'PUBLISHED'::text, 'RETIRED'::text, 'DISCARDED'::text]))),
    CONSTRAINT rle_version_history_retire_four_eyes CHECK (((retire_requested_by IS NULL) OR ((action = 'RETIRED'::text) AND (approval_mode = 'four-eyes'::text) AND (retire_requested_by <> actor)))),
    CONSTRAINT rle_version_history_rule_set_event CHECK (((version_id IS NOT NULL) OR ((action = 'EVIDENCE_EXPORTED'::text) AND (rule_set_id IS NOT NULL) AND (evidence IS NOT NULL) AND (jsonb_typeof(evidence) = 'object'::text) AND (from_status IS NULL) AND (to_status IS NULL) AND (lock_version IS NULL)))),
    CONSTRAINT rle_version_history_to_status_check CHECK ((to_status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'PUBLISHED'::text, 'RETIRED'::text, 'DISCARDED'::text]))),
    CONSTRAINT rle_version_history_version_event CHECK (((version_id IS NULL) OR ((to_status IS NOT NULL) AND (lock_version IS NOT NULL) AND (evidence IS NULL) AND (action <> 'EVIDENCE_EXPORTED'::text))))
);

ALTER TABLE ONLY rle_decision ATTACH PARTITION rle_decision_default DEFAULT;

ALTER TABLE ONLY rle_decision
    ADD CONSTRAINT rle_decision_pkey PRIMARY KEY (tenant_id, created_at, id);

ALTER TABLE ONLY rle_decision_default
    ADD CONSTRAINT rle_decision_default_pkey PRIMARY KEY (tenant_id, created_at, id);

ALTER TABLE ONLY rle_decision_log_drop
    ADD CONSTRAINT rle_decision_log_drop_pkey PRIMARY KEY (tenant_id, partition_name);

ALTER TABLE ONLY rle_decision_outbox
    ADD CONSTRAINT rle_decision_outbox_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_engine_key
    ADD CONSTRAINT rle_engine_key_pkey PRIMARY KEY (name);

ALTER TABLE ONLY rle_idempotency
    ADD CONSTRAINT rle_idempotency_pkey PRIMARY KEY (tenant_id, operation, idempotency_key);

ALTER TABLE ONLY rle_masking_key_check
    ADD CONSTRAINT rle_masking_key_check_pkey PRIMARY KEY (tenant_id, key_id);

ALTER TABLE ONLY rle_rule
    ADD CONSTRAINT rle_rule_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_rule_set
    ADD CONSTRAINT rle_rule_set_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_rule_set
    ADD CONSTRAINT rle_rule_set_tenant_id_name_key UNIQUE (tenant_id, name);

ALTER TABLE ONLY rle_rule
    ADD CONSTRAINT rle_rule_tenant_id_version_id_rule_id_key UNIQUE (tenant_id, version_id, rule_id);

ALTER TABLE ONLY rle_setting_seen
    ADD CONSTRAINT rle_setting_seen_pkey PRIMARY KEY (tenant_id, setting);

ALTER TABLE ONLY rle_simulation_report_attachment
    ADD CONSTRAINT rle_simulation_report_attachment_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_simulation_report
    ADD CONSTRAINT rle_simulation_report_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_version_function
    ADD CONSTRAINT rle_version_function_pkey PRIMARY KEY (tenant_id, version_id, function_name);

ALTER TABLE ONLY rle_version_history
    ADD CONSTRAINT rle_version_history_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_version
    ADD CONSTRAINT rle_version_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY rle_version
    ADD CONSTRAINT rle_version_tenant_id_rule_set_id_version_num_key UNIQUE (tenant_id, rule_set_id, version_num);

CREATE INDEX idx_rle_decision_correlation ON ONLY rle_decision USING btree (tenant_id, correlation_id, created_at DESC, id DESC) WHERE (correlation_id IS NOT NULL);

CREATE INDEX idx_rle_decision_lookup ON ONLY rle_decision USING btree (tenant_id, rule_set_name, created_at DESC, outcome);

CREATE INDEX idx_rle_idempotency_expiry ON rle_idempotency USING btree (expires_at);

CREATE INDEX idx_rle_outbox_poll ON rle_decision_outbox USING btree (created_at, locked_until) WHERE (status = ANY (ARRAY['PENDING'::text, 'CLAIMED'::text]));

CREATE INDEX idx_rle_simulation_report_attachment_version ON rle_simulation_report_attachment USING btree (tenant_id, rule_set_name, version_num, id DESC);

CREATE INDEX idx_rle_simulation_report_version ON rle_simulation_report USING btree (tenant_id, rule_set_name, version_num, id DESC);

CREATE INDEX idx_rle_version_effective ON rle_version USING btree (tenant_id, rule_set_id, status, effective_from DESC);

CREATE INDEX idx_rle_version_history_rule_set ON rle_version_history USING btree (tenant_id, rule_set_id, occurred_at DESC, id DESC) WHERE (rule_set_id IS NOT NULL);

CREATE INDEX idx_rle_version_history_version ON rle_version_history USING btree (tenant_id, version_id, occurred_at);

CREATE INDEX idx_rle_version_pending ON rle_version USING btree (tenant_id, requested_at, id) WHERE (status = 'PENDING_APPROVAL'::text);

CREATE INDEX idx_rle_version_published ON rle_version USING btree (tenant_id, rule_set_id, version_num) WHERE (status = 'PUBLISHED'::text);

CREATE INDEX rle_decision_default_correlation_idx ON rle_decision_default USING btree (tenant_id, correlation_id, created_at DESC, id DESC) WHERE (correlation_id IS NOT NULL);

CREATE INDEX rle_decision_default_tenant_id_rule_set_name_created_at_out_idx ON rle_decision_default USING btree (tenant_id, rule_set_name, created_at DESC, outcome);

CREATE INDEX rle_version_function_by_name ON rle_version_function USING btree (function_name, tenant_id, version_id);

ALTER INDEX idx_rle_decision_correlation ATTACH PARTITION rle_decision_default_correlation_idx;

ALTER INDEX rle_decision_pkey ATTACH PARTITION rle_decision_default_pkey;

ALTER INDEX idx_rle_decision_lookup ATTACH PARTITION rle_decision_default_tenant_id_rule_set_name_created_at_out_idx;

CREATE TRIGGER rle_decision_log_drop_no_change BEFORE DELETE OR UPDATE ON rle_decision_log_drop FOR EACH ROW EXECUTE FUNCTION rle_decision_log_drop_append_only();

CREATE TRIGGER rle_decision_log_drop_no_truncate BEFORE TRUNCATE ON rle_decision_log_drop FOR EACH STATEMENT EXECUTE FUNCTION rle_decision_log_drop_append_only();

CREATE TRIGGER rle_simulation_report_attachment_no_change BEFORE DELETE OR UPDATE ON rle_simulation_report_attachment FOR EACH ROW EXECUTE FUNCTION rle_append_only_guard();

CREATE TRIGGER rle_simulation_report_attachment_no_truncate BEFORE TRUNCATE ON rle_simulation_report_attachment FOR EACH STATEMENT EXECUTE FUNCTION rle_append_only_guard();

CREATE TRIGGER rle_simulation_report_no_change BEFORE DELETE OR UPDATE ON rle_simulation_report FOR EACH ROW EXECUTE FUNCTION rle_append_only_guard();

CREATE TRIGGER rle_simulation_report_no_truncate BEFORE TRUNCATE ON rle_simulation_report FOR EACH STATEMENT EXECUTE FUNCTION rle_append_only_guard();

CREATE TRIGGER rle_version_history_no_change BEFORE DELETE OR UPDATE ON rle_version_history FOR EACH ROW EXECUTE FUNCTION rle_version_history_append_only();

CREATE TRIGGER rle_version_history_no_truncate BEFORE TRUNCATE ON rle_version_history FOR EACH STATEMENT EXECUTE FUNCTION rle_version_history_append_only();

ALTER TABLE ONLY rle_rule
    ADD CONSTRAINT rle_rule_tenant_id_version_id_fkey FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version(tenant_id, id);

ALTER TABLE ONLY rle_simulation_report_attachment
    ADD CONSTRAINT rle_simulation_report_attachment_tenant_id_report_id_fkey FOREIGN KEY (tenant_id, report_id) REFERENCES rle_simulation_report(tenant_id, id);

ALTER TABLE ONLY rle_version_function
    ADD CONSTRAINT rle_version_function_tenant_id_version_id_fkey FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version(tenant_id, id);

ALTER TABLE ONLY rle_version_history
    ADD CONSTRAINT rle_version_history_rule_set_fk FOREIGN KEY (tenant_id, rule_set_id) REFERENCES rle_rule_set(tenant_id, id);

ALTER TABLE ONLY rle_version_history
    ADD CONSTRAINT rle_version_history_tenant_id_version_id_fkey FOREIGN KEY (tenant_id, version_id) REFERENCES rle_version(tenant_id, id);

ALTER TABLE ONLY rle_version
    ADD CONSTRAINT rle_version_tenant_id_rule_set_id_fkey FOREIGN KEY (tenant_id, rule_set_id) REFERENCES rle_rule_set(tenant_id, id);

