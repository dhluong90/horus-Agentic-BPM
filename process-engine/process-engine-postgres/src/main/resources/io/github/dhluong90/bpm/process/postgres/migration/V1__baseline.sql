-- Process Engine schema baseline (V1).
-- One migration that produces the whole schema. Generated from a fresh PostgreSQL after the former migration
-- chain, as the schema-only dump (history table excluded, schema qualifiers removed so it runs in the host's
-- schema). The next migration is V2. Until the 1.0 release this file may be regenerated; after it, never edited.


CREATE FUNCTION pre_definition_history_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'pre_definition_history is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION pre_definition_layout_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'pre_definition_layout is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION pre_migration_history_append_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  RAISE EXCEPTION 'pre_migration_history is append-only: % is not allowed', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TABLE pre_agent_replay (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    status text NOT NULL,
    definition_key text NOT NULL,
    node_id text NOT NULL,
    period_from date NOT NULL,
    period_to date NOT NULL,
    candidate_rule_set text NOT NULL,
    candidate_version integer NOT NULL,
    candidate_schema jsonb,
    idempotency_key text,
    request_hash character(64),
    cursor_at timestamp with time zone,
    cursor_id character varying(26),
    cases integer DEFAULT 0 NOT NULL,
    unchanged integer DEFAULT 0 NOT NULL,
    newly_allowed integer DEFAULT 0 NOT NULL,
    newly_refused integer DEFAULT 0 NOT NULL,
    newly_reviewed integer DEFAULT 0 NOT NULL,
    overridden integer DEFAULT 0 NOT NULL,
    not_replayed integer DEFAULT 0 NOT NULL,
    with_masked_fields integer DEFAULT 0 NOT NULL,
    failure_code text,
    created_by text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone NOT NULL,
    masked_input_required integer DEFAULT 0 NOT NULL,
    CONSTRAINT pre_agent_replay_candidate_version_check CHECK ((candidate_version >= 1)),
    CONSTRAINT pre_agent_replay_check CHECK ((period_to >= period_from)),
    CONSTRAINT pre_agent_replay_check1 CHECK (((idempotency_key IS NULL) = (request_hash IS NULL))),
    CONSTRAINT pre_agent_replay_status_check CHECK ((status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'COMPLETED'::text, 'FAILED'::text])))
);

CREATE TABLE pre_agent_replay_case (
    tenant_id text NOT NULL,
    replay_id character varying(26) NOT NULL,
    seq integer NOT NULL,
    attempt_id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    recorded_outcome text NOT NULL,
    candidate_outcome text NOT NULL,
    change text NOT NULL,
    recorded_rule_ids jsonb NOT NULL,
    candidate_rule_ids jsonb NOT NULL,
    code text,
    overridden boolean NOT NULL,
    masked_fields integer NOT NULL,
    CONSTRAINT pre_agent_replay_case_candidate_outcome_check CHECK ((candidate_outcome = ANY (ARRAY['ALLOWED'::text, 'REVIEW'::text, 'REFUSED'::text, 'NOT_REPLAYED'::text]))),
    CONSTRAINT pre_agent_replay_case_change_check CHECK ((change = ANY (ARRAY['UNCHANGED'::text, 'NEWLY_ALLOWED'::text, 'NEWLY_REFUSED'::text, 'NEWLY_ROUTED_TO_REVIEW'::text, 'NOT_REPLAYED'::text]))),
    CONSTRAINT pre_agent_replay_case_masked_fields_check CHECK ((masked_fields >= 0)),
    CONSTRAINT pre_agent_replay_case_recorded_outcome_check CHECK ((recorded_outcome = ANY (ARRAY['ALLOWED'::text, 'REVIEW'::text, 'REFUSED'::text]))),
    CONSTRAINT pre_agent_replay_case_seq_check CHECK ((seq >= 0))
);

CREATE TABLE pre_calendar (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    calendar_key text NOT NULL,
    timezone text NOT NULL,
    weekly_hours jsonb NOT NULL,
    lock_version bigint NOT NULL,
    updated_by text NOT NULL,
    approved_by text NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_calendar_calendar_key_check CHECK (((length(calendar_key) >= 1) AND (length(calendar_key) <= 100))),
    CONSTRAINT pre_calendar_lock_version_check CHECK ((lock_version >= 1)),
    CONSTRAINT pre_calendar_weekly_hours_check CHECK ((jsonb_typeof(weekly_hours) = 'object'::text))
);

CREATE TABLE pre_calendar_change (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    calendar_key text NOT NULL,
    status text NOT NULL,
    approval text NOT NULL,
    base_version bigint NOT NULL,
    previous_value jsonb,
    proposed_value jsonb NOT NULL,
    reason text NOT NULL,
    proposed_by text NOT NULL,
    proposed_at timestamp with time zone NOT NULL,
    decided_by text,
    decided_at timestamp with time zone,
    decision_comment text,
    CONSTRAINT ck_pre_calendar_change_decided CHECK (((status = 'PENDING'::text) = ((decided_by IS NULL) AND (decided_at IS NULL)))),
    CONSTRAINT ck_pre_calendar_change_four_eyes CHECK (((status <> 'APPROVED'::text) OR (approval = 'SINGLE'::text) OR (decided_by <> proposed_by))),
    CONSTRAINT pre_calendar_change_approval_check CHECK ((approval = ANY (ARRAY['FOUR_EYES'::text, 'SINGLE'::text]))),
    CONSTRAINT pre_calendar_change_base_version_check CHECK ((base_version >= 0)),
    CONSTRAINT pre_calendar_change_decision_comment_check CHECK ((length(decision_comment) <= 500)),
    CONSTRAINT pre_calendar_change_proposed_value_check CHECK ((jsonb_typeof(proposed_value) = 'object'::text)),
    CONSTRAINT pre_calendar_change_reason_check CHECK (((length(reason) >= 1) AND (length(reason) <= 500))),
    CONSTRAINT pre_calendar_change_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'APPROVED'::text, 'REJECTED'::text])))
);

CREATE TABLE pre_compensation_stack (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    scope_token_id character varying(26) NOT NULL,
    seq integer NOT NULL,
    step_id text NOT NULL,
    forward_job_id character varying(26),
    forward_attempt_id character varying(26) NOT NULL,
    handler text NOT NULL,
    mode text NOT NULL,
    input_snapshot jsonb NOT NULL,
    status text NOT NULL,
    job_id character varying(26),
    pushed_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    instance_seq integer NOT NULL,
    CONSTRAINT ck_pre_compensation_stack_instance_seq CHECK ((instance_seq >= 1)),
    CONSTRAINT pre_compensation_stack_check CHECK (((handler = 'manual'::text) = (mode = 'manual'::text))),
    CONSTRAINT pre_compensation_stack_check1 CHECK (((status <> 'RUNNING'::text) OR (job_id IS NOT NULL))),
    CONSTRAINT pre_compensation_stack_mode_check CHECK ((mode = ANY (ARRAY['local'::text, 'remote'::text, 'manual'::text]))),
    CONSTRAINT pre_compensation_stack_seq_check CHECK ((seq >= 1)),
    CONSTRAINT pre_compensation_stack_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'RUNNING'::text, 'DONE'::text, 'FAILED'::text, 'MANUAL'::text])))
);

CREATE TABLE pre_definition (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    key_name text NOT NULL,
    version_num integer NOT NULL,
    status text NOT NULL,
    name text,
    schema_version integer DEFAULT 1 NOT NULL,
    definition_json jsonb NOT NULL,
    definition_hash character(64) NOT NULL,
    source_format text NOT NULL,
    source_document text NOT NULL,
    source_hash character(64) NOT NULL,
    node_count integer NOT NULL,
    source text NOT NULL,
    effective_from timestamp with time zone,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    approved_by text,
    approved_at timestamp with time zone,
    requested_by text,
    requested_at timestamp with time zone,
    decision_comment text,
    retired_by text,
    retired_at timestamp with time zone,
    superseded_by integer,
    CONSTRAINT ck_pre_definition_four_eyes CHECK (((approved_by IS NULL) OR (requested_by IS NULL) OR (approved_by <> requested_by) OR (approved_by = 'system:dev-auto-approve'::text))),
    CONSTRAINT pre_definition_check CHECK (((status <> 'ACTIVE'::text) OR ((approved_by IS NOT NULL) AND (effective_from IS NOT NULL)))),
    CONSTRAINT pre_definition_node_count_check CHECK ((node_count <= 500)),
    CONSTRAINT pre_definition_source_check CHECK ((source = ANY (ARRAY['IMPORT'::text, 'STARTUP'::text, 'AUTHORED'::text, 'BPMN'::text]))),
    CONSTRAINT pre_definition_source_format_check CHECK ((source_format = ANY (ARRAY['JSON'::text, 'YAML'::text, 'BPMN_XML'::text]))),
    CONSTRAINT pre_definition_status_check CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'ACTIVE'::text, 'RETIRED'::text]))),
    CONSTRAINT pre_definition_version_num_check CHECK ((version_num >= 1))
);

CREATE TABLE pre_definition_history (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    action text NOT NULL,
    from_status text,
    to_status text NOT NULL,
    actor_id text NOT NULL,
    comment text,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_definition_history_action_check CHECK ((action = ANY (ARRAY['IMPORTED'::text, 'REQUESTED'::text, 'APPROVED'::text, 'REJECTED'::text, 'RETIRED'::text])))
);

CREATE TABLE pre_definition_layout (
    tenant_id text NOT NULL,
    definition_id character varying(26) NOT NULL,
    revision integer NOT NULL,
    layout_json jsonb NOT NULL,
    layout_hash character(64) NOT NULL,
    definition_hash character(64) NOT NULL,
    source text NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_definition_layout_layout_json_check CHECK ((jsonb_typeof(layout_json) = 'object'::text)),
    CONSTRAINT pre_definition_layout_revision_check CHECK ((revision >= 1)),
    CONSTRAINT pre_definition_layout_source_check CHECK ((source = ANY (ARRAY['IMPORT'::text, 'BPMN'::text, 'COCKPIT'::text, 'API'::text])))
);

CREATE TABLE pre_engine_key (
    name text NOT NULL,
    key_bytes bytea NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_engine_key_key_bytes_check CHECK ((octet_length(key_bytes) >= 32))
);

CREATE TABLE pre_engine_watermark (
    job_name text NOT NULL,
    watermark timestamp with time zone NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE TABLE pre_event_subscription (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    kind text NOT NULL,
    event_name text NOT NULL,
    definition_id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    token_id character varying(26) NOT NULL,
    node_id text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_event_subscription_event_name_check CHECK (((length(event_name) >= 1) AND (length(event_name) <= 100))),
    CONSTRAINT pre_event_subscription_kind_check CHECK ((kind = ANY (ARRAY['MESSAGE'::text, 'SIGNAL'::text, 'CONDITIONAL'::text])))
);

CREATE TABLE pre_history (
    tenant_id text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    event_type text NOT NULL,
    node_id text,
    token_id character varying(26),
    actor_id text,
    attempt_id character varying(26),
    details jsonb NOT NULL
)
PARTITION BY RANGE (occurred_at);

CREATE TABLE pre_history_default (
    tenant_id text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    event_type text NOT NULL,
    node_id text,
    token_id character varying(26),
    actor_id text,
    attempt_id character varying(26),
    details jsonb NOT NULL
);

CREATE TABLE pre_holiday (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    calendar_id character varying(26) NOT NULL,
    holiday_date date NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    CONSTRAINT pre_holiday_description_check CHECK ((length(description) <= 200))
);

CREATE TABLE pre_idempotency (
    tenant_id text NOT NULL,
    operation text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash character(64) NOT NULL,
    response_body jsonb,
    expires_at timestamp with time zone NOT NULL,
    status text DEFAULT 'DONE'::text NOT NULL,
    CONSTRAINT pre_idempotency_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'DONE'::text])))
);

CREATE TABLE pre_incident (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    token_id character varying(26),
    node_id text NOT NULL,
    error_code text NOT NULL,
    cause_summary text NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    status text NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    resolution text,
    resolved_by text,
    approval_mode text,
    proposed_by text,
    proposed_at timestamp with time zone,
    proposal_reason text,
    proposal_output jsonb,
    retrying boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_pre_incident_four_eyes CHECK (((approval_mode IS DISTINCT FROM 'four-eyes'::text) OR (resolved_by IS NULL) OR (resolved_by <> proposed_by))),
    CONSTRAINT ck_pre_incident_proposal CHECK (((status <> 'RESOLUTION_PENDING'::text) OR ((proposed_by IS NOT NULL) AND (proposed_at IS NOT NULL) AND (proposal_reason IS NOT NULL)))),
    CONSTRAINT ck_pre_incident_resolution CHECK (((resolution IS NULL) OR (resolution = ANY (ARRAY['RETRY_SUCCEEDED'::text, 'COMPLETED_MANUALLY'::text, 'INSTANCE_CANCELLED'::text])))),
    CONSTRAINT ck_pre_incident_status CHECK ((status = ANY (ARRAY['OPEN'::text, 'RESOLUTION_PENDING'::text, 'RESOLVED'::text]))),
    CONSTRAINT pre_incident_cause_summary_check CHECK ((length(cause_summary) <= 500)),
    CONSTRAINT pre_incident_retry_count_check CHECK ((retry_count >= 0))
);

CREATE TABLE pre_instance (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    definition_key text NOT NULL,
    definition_ver integer NOT NULL,
    business_key text,
    status text NOT NULL,
    variables jsonb NOT NULL,
    seq bigint DEFAULT 0 NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    started_by text,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    parent_instance_id character varying(26),
    parent_token_id character varying(26),
    call_attempt integer DEFAULT 0 NOT NULL,
    call_depth integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_pre_instance_parent CHECK (((parent_instance_id IS NULL) = (parent_token_id IS NULL))),
    CONSTRAINT pre_instance_call_attempt_check CHECK ((call_attempt >= 0)),
    CONSTRAINT pre_instance_call_depth_check CHECK ((call_depth >= 0)),
    CONSTRAINT pre_instance_status_check CHECK ((status = ANY (ARRAY['RUNNING'::text, 'COMPLETED'::text, 'COMPENSATING'::text, 'CANCELLED'::text, 'FAILED'::text])))
);

CREATE TABLE pre_message_subscription (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    token_id character varying(26) NOT NULL,
    node_id text NOT NULL,
    message_name character varying(100) NOT NULL,
    correlation_key character varying(200) NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE pre_migration (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    definition_key text NOT NULL,
    source_definition_id character varying(26) NOT NULL,
    source_version integer NOT NULL,
    target_definition_id character varying(26) NOT NULL,
    target_version integer NOT NULL,
    plan_hash character(64) NOT NULL,
    approval text NOT NULL,
    status text NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    proposed_by text,
    proposed_at timestamp with time zone,
    reason text,
    dry_run_checked_at timestamp with time zone,
    approved_by text,
    approved_at timestamp with time zone,
    rejected_by text,
    rejected_at timestamp with time zone,
    rejection_reason text,
    total_count bigint DEFAULT 0 NOT NULL,
    migrated_count bigint DEFAULT 0 NOT NULL,
    failed_count bigint DEFAULT 0 NOT NULL,
    skipped_count bigint DEFAULT 0 NOT NULL,
    cursor_instance_id character varying(26) DEFAULT ''::character varying NOT NULL,
    lease_owner text,
    lease_until timestamp with time zone,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    instructions jsonb DEFAULT '{"mappings": [], "cancellations": []}'::jsonb NOT NULL,
    CONSTRAINT ck_pre_migration_approved CHECK (((status <> ALL (ARRAY['APPROVED'::text, 'RUNNING'::text, 'COMPLETED'::text, 'COMPLETED_WITH_FAILURES'::text])) OR ((approved_by IS NOT NULL) AND (approved_at IS NOT NULL) AND (proposed_by IS NOT NULL) AND (reason IS NOT NULL) AND (dry_run_checked_at IS NOT NULL)))),
    CONSTRAINT ck_pre_migration_counts CHECK (((total_count >= 0) AND (migrated_count >= 0) AND (failed_count >= 0) AND (skipped_count >= 0))),
    CONSTRAINT ck_pre_migration_four_eyes CHECK (((approval <> 'four-eyes'::text) OR (approved_by IS NULL) OR (approved_by <> proposed_by))),
    CONSTRAINT ck_pre_migration_instructions CHECK ((jsonb_typeof(instructions) = 'object'::text)),
    CONSTRAINT ck_pre_migration_status CHECK ((status = ANY (ARRAY['PLANNED'::text, 'PROPOSED'::text, 'REJECTED'::text, 'APPROVED'::text, 'RUNNING'::text, 'COMPLETED'::text, 'COMPLETED_WITH_FAILURES'::text]))),
    CONSTRAINT pre_migration_approval_check CHECK ((approval = ANY (ARRAY['four-eyes'::text, 'single'::text]))),
    CONSTRAINT pre_migration_check CHECK (((source_definition_id)::text <> (target_definition_id)::text)),
    CONSTRAINT pre_migration_source_version_check CHECK ((source_version >= 1)),
    CONSTRAINT pre_migration_target_version_check CHECK ((target_version >= 1))
);

CREATE TABLE pre_migration_history (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    migration_id character varying(26) NOT NULL,
    action text NOT NULL,
    actor_id text NOT NULL,
    details jsonb DEFAULT '{}'::jsonb NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_pre_migration_history_action CHECK ((action = ANY (ARRAY['PLANNED'::text, 'PROPOSED'::text, 'REJECTED'::text, 'APPROVED'::text, 'COMPLETED'::text])))
);

CREATE TABLE pre_migration_instance (
    tenant_id text NOT NULL,
    migration_id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    outcome text NOT NULL,
    code text NOT NULL,
    detail text NOT NULL,
    incident_id character varying(26),
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_migration_instance_detail_check CHECK ((length(detail) <= 500)),
    CONSTRAINT pre_migration_instance_outcome_check CHECK ((outcome = ANY (ARRAY['MIGRATED'::text, 'FAILED'::text, 'SKIPPED'::text])))
);

CREATE TABLE pre_node_stats_hourly (
    tenant_id text NOT NULL,
    definition_id character varying(26) NOT NULL,
    bucket_start timestamp with time zone NOT NULL,
    node_id text NOT NULL,
    entered_count bigint DEFAULT 0 NOT NULL,
    completed_count bigint DEFAULT 0 NOT NULL,
    failed_count bigint DEFAULT 0 NOT NULL,
    incident_count bigint DEFAULT 0 NOT NULL,
    sla_breach_count bigint DEFAULT 0 NOT NULL,
    duration_sum_ms bigint DEFAULT 0 NOT NULL,
    duration_hist integer[] NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_node_stats_hourly_duration_hist_check CHECK ((cardinality(duration_hist) = 64))
)
PARTITION BY RANGE (bucket_start);

CREATE TABLE pre_node_stats_hourly_default (
    tenant_id text NOT NULL,
    definition_id character varying(26) NOT NULL,
    bucket_start timestamp with time zone NOT NULL,
    node_id text NOT NULL,
    entered_count bigint DEFAULT 0 NOT NULL,
    completed_count bigint DEFAULT 0 NOT NULL,
    failed_count bigint DEFAULT 0 NOT NULL,
    incident_count bigint DEFAULT 0 NOT NULL,
    sla_breach_count bigint DEFAULT 0 NOT NULL,
    duration_sum_ms bigint DEFAULT 0 NOT NULL,
    duration_hist integer[] NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_node_stats_hourly_duration_hist_check CHECK ((cardinality(duration_hist) = 64))
);

CREATE TABLE pre_signal (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    name text NOT NULL,
    thrown_by text NOT NULL,
    thrown_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_signal_name_check CHECK (((length(name) >= 1) AND (length(name) <= 100)))
);

CREATE TABLE pre_start_arrival (
    tenant_id text NOT NULL,
    key_name text NOT NULL,
    trigger_kind text NOT NULL,
    event_name character varying(100) NOT NULL,
    correlation_key character varying(200) DEFAULT ''::character varying NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    arrived_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_start_arrival_trigger_kind_check CHECK ((trigger_kind = ANY (ARRAY['MESSAGE'::text, 'SIGNAL'::text])))
);

CREATE TABLE pre_step_result (
    tenant_id text NOT NULL,
    instance_id character varying(26) NOT NULL,
    node_id text NOT NULL,
    definition_id character varying(26) NOT NULL,
    result_json jsonb NOT NULL,
    source text NOT NULL,
    activity_instance_id character varying(26) NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT pre_step_result_result_json_check CHECK ((jsonb_typeof(result_json) = 'object'::text)),
    CONSTRAINT pre_step_result_source_check CHECK ((source = ANY (ARRAY['ANSWER'::text, 'REVIEW'::text])))
);

CREATE TABLE pre_task (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    token_id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    node_id text NOT NULL,
    name text,
    task_kind text DEFAULT 'USER_TASK'::text NOT NULL,
    assignee_user text,
    assignee_role text,
    assignee_group text,
    four_eyes boolean DEFAULT false NOT NULL,
    status text NOT NULL,
    lock_version bigint DEFAULT 0 NOT NULL,
    completed_by text,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    claimed_by text,
    claimed_at timestamp with time zone,
    review jsonb,
    due_at timestamp with time zone,
    escalation_json jsonb,
    escalation_level integer,
    next_escalation_at timestamp with time zone,
    CONSTRAINT ck_pre_task_claimant CHECK (((status <> 'CLAIMED'::text) OR (claimed_by IS NOT NULL))),
    CONSTRAINT pre_task_check CHECK ((num_nonnulls(assignee_user, assignee_role, assignee_group) = 1)),
    CONSTRAINT pre_task_review_check CHECK (((review IS NULL) OR (jsonb_typeof(review) = 'object'::text))),
    CONSTRAINT pre_task_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'CLAIMED'::text, 'COMPLETED'::text, 'CANCELLED'::text]))),
    CONSTRAINT pre_task_task_kind_check CHECK ((task_kind = ANY (ARRAY['USER_TASK'::text, 'APPROVAL_TASK'::text])))
);

CREATE TABLE pre_timer (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26),
    definition_id character varying(26),
    token_id character varying(26),
    job_kind text NOT NULL,
    node_id text,
    status text NOT NULL,
    next_fire_at timestamp with time zone NOT NULL,
    lease_duration interval NOT NULL,
    locked_by text,
    locked_until timestamp with time zone,
    attempt integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    attempt_id character varying(26),
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    completion_token_hash bytea,
    late_result_at timestamp with time zone,
    CONSTRAINT ck_pre_timer_job_kind CHECK ((job_kind = ANY (ARRAY['TIMER'::text, 'SLA_ESCALATION'::text, 'ASYNC_AGENT'::text, 'ASYNC_SERVICE'::text, 'COMPENSATION'::text, 'DEFINITION_EFFECTIVE'::text, 'CALL_RESULT'::text, 'SIGNAL_DELIVERY'::text]))),
    CONSTRAINT pre_timer_attempt_check CHECK ((attempt >= 0)),
    CONSTRAINT pre_timer_check CHECK (((status <> 'CLAIMED'::text) OR ((locked_by IS NOT NULL) AND (locked_until IS NOT NULL)))),
    CONSTRAINT pre_timer_check1 CHECK (((job_kind = 'DEFINITION_EFFECTIVE'::text) OR ((instance_id IS NOT NULL) AND (token_id IS NOT NULL)))),
    CONSTRAINT pre_timer_completion_token_hash_check CHECK (((completion_token_hash IS NULL) OR (octet_length(completion_token_hash) = 32))),
    CONSTRAINT pre_timer_lease_duration_check CHECK ((lease_duration > '00:00:00'::interval)),
    CONSTRAINT pre_timer_max_attempts_check CHECK ((max_attempts >= 1)),
    CONSTRAINT pre_timer_status_check CHECK ((status = ANY (ARRAY['DUE'::text, 'CLAIMED'::text, 'DONE'::text, 'FAILED'::text, 'CANCELLED'::text])))
);

CREATE TABLE pre_token (
    tenant_id text NOT NULL,
    id character varying(26) NOT NULL,
    instance_id character varying(26) NOT NULL,
    definition_id character varying(26) NOT NULL,
    token_kind text NOT NULL,
    scope_token_id character varying(26),
    current_node_id text NOT NULL,
    status text NOT NULL,
    activity_instance_id character varying(26),
    locals jsonb DEFAULT '{}'::jsonb NOT NULL,
    entered_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_pre_token_status CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'WAITING_USER'::text, 'WAITING_ASYNC'::text, 'WAITING_TIMER'::text, 'WAITING_EVENT'::text, 'WAITING_SCOPE'::text, 'WAITING_CHILD'::text, 'JOINED'::text, 'COMPLETED'::text, 'COMPENSATED'::text, 'CANCELLED'::text]))),
    CONSTRAINT pre_token_check CHECK ((((token_kind = 'SCOPE'::text) AND (status = ANY (ARRAY['WAITING_SCOPE'::text, 'COMPLETED'::text, 'COMPENSATED'::text, 'CANCELLED'::text]))) OR ((token_kind = 'FLOW'::text) AND (status <> 'WAITING_SCOPE'::text) AND (scope_token_id IS NOT NULL)) OR (token_kind = 'MI_BODY'::text))),
    CONSTRAINT pre_token_token_kind_check CHECK ((token_kind = ANY (ARRAY['SCOPE'::text, 'FLOW'::text, 'MI_BODY'::text])))
);

ALTER TABLE ONLY pre_history ATTACH PARTITION pre_history_default DEFAULT;

ALTER TABLE ONLY pre_node_stats_hourly ATTACH PARTITION pre_node_stats_hourly_default DEFAULT;

ALTER TABLE ONLY pre_agent_replay_case
    ADD CONSTRAINT pre_agent_replay_case_pkey PRIMARY KEY (tenant_id, replay_id, seq);

ALTER TABLE ONLY pre_agent_replay
    ADD CONSTRAINT pre_agent_replay_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_calendar_change
    ADD CONSTRAINT pre_calendar_change_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_calendar
    ADD CONSTRAINT pre_calendar_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_compensation_stack
    ADD CONSTRAINT pre_compensation_stack_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_definition_history
    ADD CONSTRAINT pre_definition_history_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_definition_layout
    ADD CONSTRAINT pre_definition_layout_pkey PRIMARY KEY (tenant_id, definition_id, revision);

ALTER TABLE ONLY pre_definition
    ADD CONSTRAINT pre_definition_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_definition
    ADD CONSTRAINT pre_definition_tenant_id_key_name_version_num_key UNIQUE (tenant_id, key_name, version_num);

ALTER TABLE ONLY pre_engine_key
    ADD CONSTRAINT pre_engine_key_pkey PRIMARY KEY (name);

ALTER TABLE ONLY pre_engine_watermark
    ADD CONSTRAINT pre_engine_watermark_pkey PRIMARY KEY (job_name);

ALTER TABLE ONLY pre_event_subscription
    ADD CONSTRAINT pre_event_subscription_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_history
    ADD CONSTRAINT pre_history_pkey PRIMARY KEY (tenant_id, occurred_at, id);

ALTER TABLE ONLY pre_history_default
    ADD CONSTRAINT pre_history_default_pkey PRIMARY KEY (tenant_id, occurred_at, id);

ALTER TABLE ONLY pre_holiday
    ADD CONSTRAINT pre_holiday_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_idempotency
    ADD CONSTRAINT pre_idempotency_pkey PRIMARY KEY (tenant_id, operation, idempotency_key);

ALTER TABLE ONLY pre_incident
    ADD CONSTRAINT pre_incident_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_instance
    ADD CONSTRAINT pre_instance_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_message_subscription
    ADD CONSTRAINT pre_message_subscription_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_migration_history
    ADD CONSTRAINT pre_migration_history_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_migration_instance
    ADD CONSTRAINT pre_migration_instance_pkey PRIMARY KEY (tenant_id, migration_id, instance_id);

ALTER TABLE ONLY pre_migration
    ADD CONSTRAINT pre_migration_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_node_stats_hourly
    ADD CONSTRAINT pre_node_stats_hourly_pkey PRIMARY KEY (tenant_id, definition_id, bucket_start, node_id);

ALTER TABLE ONLY pre_node_stats_hourly_default
    ADD CONSTRAINT pre_node_stats_hourly_default_pkey PRIMARY KEY (tenant_id, definition_id, bucket_start, node_id);

ALTER TABLE ONLY pre_signal
    ADD CONSTRAINT pre_signal_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_start_arrival
    ADD CONSTRAINT pre_start_arrival_pkey PRIMARY KEY (tenant_id, key_name, trigger_kind, event_name, correlation_key);

ALTER TABLE ONLY pre_step_result
    ADD CONSTRAINT pre_step_result_pkey PRIMARY KEY (tenant_id, instance_id, node_id);

ALTER TABLE ONLY pre_task
    ADD CONSTRAINT pre_task_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_timer
    ADD CONSTRAINT pre_timer_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_token
    ADD CONSTRAINT pre_token_pkey PRIMARY KEY (tenant_id, id);

ALTER TABLE ONLY pre_calendar
    ADD CONSTRAINT uq_pre_calendar_key UNIQUE (tenant_id, calendar_key);

ALTER TABLE ONLY pre_compensation_stack
    ADD CONSTRAINT uq_pre_compensation_stack_instance_seq UNIQUE (tenant_id, instance_id, instance_seq);

ALTER TABLE ONLY pre_compensation_stack
    ADD CONSTRAINT uq_pre_compensation_stack_seq UNIQUE (tenant_id, instance_id, scope_token_id, seq);

ALTER TABLE ONLY pre_holiday
    ADD CONSTRAINT uq_pre_holiday_date UNIQUE (tenant_id, calendar_id, holiday_date);

CREATE INDEX idx_pre_agent_replay_case_attempt ON pre_agent_replay_case USING btree (tenant_id, replay_id, attempt_id);

CREATE INDEX idx_pre_agent_replay_expiry ON pre_agent_replay USING btree (expires_at);

CREATE INDEX idx_pre_agent_replay_open ON pre_agent_replay USING btree (created_at, id) WHERE (status = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text]));

CREATE INDEX idx_pre_calendar_change_key ON pre_calendar_change USING btree (tenant_id, calendar_key, proposed_at, id);

CREATE INDEX idx_pre_calendar_change_pending_page ON pre_calendar_change USING btree (tenant_id, proposed_at, id) WHERE (status = 'PENDING'::text);

CREATE INDEX idx_pre_calendar_change_status ON pre_calendar_change USING btree (tenant_id, status, proposed_at DESC, id DESC);

CREATE INDEX idx_pre_definition_active ON pre_definition USING btree (tenant_id, key_name, version_num) WHERE (status = 'ACTIVE'::text);

CREATE INDEX idx_pre_definition_effective ON pre_definition USING btree (tenant_id, key_name, status, effective_from DESC);

CREATE INDEX idx_pre_definition_history ON pre_definition_history USING btree (tenant_id, definition_id, occurred_at, id);

CREATE INDEX idx_pre_definition_pending ON pre_definition USING btree (tenant_id, requested_at) WHERE (status = 'PENDING_APPROVAL'::text);

CREATE INDEX idx_pre_definition_pending_page ON pre_definition USING btree (tenant_id, requested_at, id) WHERE (status = 'PENDING_APPROVAL'::text);

CREATE INDEX idx_pre_event_sub_instance ON pre_event_subscription USING btree (tenant_id, instance_id);

CREATE INDEX idx_pre_event_sub_signal ON pre_event_subscription USING btree (tenant_id, event_name, id) WHERE (kind = 'SIGNAL'::text);

CREATE INDEX idx_pre_history_instance ON ONLY pre_history USING btree (tenant_id, instance_id, occurred_at, id);

CREATE INDEX idx_pre_history_time_brin ON ONLY pre_history USING brin (occurred_at);

CREATE INDEX idx_pre_incident_instance ON pre_incident USING btree (tenant_id, instance_id, created_at, id) WHERE (status = 'OPEN'::text);

CREATE INDEX idx_pre_incident_node ON pre_incident USING btree (tenant_id, definition_id, node_id) WHERE (status = 'OPEN'::text);

CREATE INDEX idx_pre_incident_open ON pre_incident USING btree (tenant_id, created_at DESC, id) WHERE (status = 'OPEN'::text);

CREATE INDEX idx_pre_incident_status ON pre_incident USING btree (tenant_id, status, created_at DESC, id DESC);

CREATE INDEX idx_pre_incident_unresolved ON pre_incident USING btree (tenant_id, instance_id, created_at, id) WHERE (status = ANY (ARRAY['OPEN'::text, 'RESOLUTION_PENDING'::text]));

CREATE INDEX idx_pre_instance_parent ON pre_instance USING btree (tenant_id, parent_instance_id, id) WHERE (parent_instance_id IS NOT NULL);

CREATE INDEX idx_pre_instance_running_definition ON pre_instance USING btree (tenant_id, definition_id, id) WHERE (status = 'RUNNING'::text);

CREATE INDEX idx_pre_instance_status ON pre_instance USING btree (tenant_id, status, started_at DESC);

CREATE INDEX idx_pre_message_subscription_instance ON pre_message_subscription USING btree (tenant_id, instance_id);

CREATE INDEX idx_pre_message_subscription_lookup ON pre_message_subscription USING btree (tenant_id, message_name, correlation_key, id);

CREATE INDEX idx_pre_migration_definition ON pre_migration USING btree (tenant_id, definition_key, created_at DESC, id);

CREATE INDEX idx_pre_migration_history ON pre_migration_history USING btree (tenant_id, migration_id, occurred_at, id);

CREATE INDEX idx_pre_migration_instance_failed ON pre_migration_instance USING btree (tenant_id, migration_id, instance_id) WHERE (outcome = 'FAILED'::text);

CREATE INDEX idx_pre_migration_proposed_page ON pre_migration USING btree (tenant_id, proposed_at, id) WHERE (status = 'PROPOSED'::text);

CREATE INDEX idx_pre_migration_runnable ON pre_migration USING btree (lease_until NULLS FIRST, tenant_id, id) WHERE (status = ANY (ARRAY['APPROVED'::text, 'RUNNING'::text]));

CREATE INDEX idx_pre_node_stats_hourly_bucket ON ONLY pre_node_stats_hourly USING brin (bucket_start);

CREATE INDEX idx_pre_start_arrival_oldest ON pre_start_arrival USING btree (tenant_id, key_name, trigger_kind, event_name, arrived_at);

CREATE INDEX idx_pre_task_claimed ON pre_task USING btree (tenant_id, claimed_by, due_at, id) WHERE (status = 'CLAIMED'::text);

CREATE INDEX idx_pre_task_escalation_due ON pre_task USING btree (next_escalation_at, tenant_id, id) WHERE ((status = ANY (ARRAY['OPEN'::text, 'CLAIMED'::text])) AND (next_escalation_at IS NOT NULL));

CREATE INDEX idx_pre_task_group ON pre_task USING btree (tenant_id, assignee_group, due_at, id) WHERE ((status = 'OPEN'::text) AND (assignee_group IS NOT NULL));

CREATE INDEX idx_pre_task_instance ON pre_task USING btree (tenant_id, instance_id, created_at);

CREATE INDEX idx_pre_task_mine ON pre_task USING btree (tenant_id, assignee_user, due_at, id) WHERE ((status = ANY (ARRAY['OPEN'::text, 'CLAIMED'::text])) AND (assignee_user IS NOT NULL));

CREATE INDEX idx_pre_task_open_due ON pre_task USING btree (tenant_id, due_at, id) WHERE (status = ANY (ARRAY['OPEN'::text, 'CLAIMED'::text]));

CREATE INDEX idx_pre_task_role_due ON pre_task USING btree (tenant_id, assignee_role, due_at, id) WHERE ((status = 'OPEN'::text) AND (assignee_role IS NOT NULL));

CREATE INDEX idx_pre_timer_inflight ON pre_timer USING btree (tenant_id, definition_id, node_id, instance_id) WHERE (status = 'CLAIMED'::text);

CREATE INDEX idx_pre_timer_instance ON pre_timer USING btree (tenant_id, instance_id) WHERE (status = ANY (ARRAY['DUE'::text, 'CLAIMED'::text]));

CREATE INDEX idx_pre_timer_poll ON pre_timer USING btree (next_fire_at, locked_until) WHERE (status = ANY (ARRAY['DUE'::text, 'CLAIMED'::text]));

CREATE INDEX idx_pre_timer_poll_kind ON pre_timer USING btree (job_kind, next_fire_at, id) WHERE (status = ANY (ARRAY['DUE'::text, 'CLAIMED'::text]));

CREATE INDEX idx_pre_token_instance ON pre_token USING btree (tenant_id, instance_id, status);

CREATE INDEX idx_pre_token_live ON pre_token USING btree (tenant_id, definition_id, status, current_node_id) WHERE (status = ANY (ARRAY['ACTIVE'::text, 'WAITING_USER'::text, 'WAITING_ASYNC'::text, 'WAITING_TIMER'::text, 'WAITING_EVENT'::text, 'WAITING_CHILD'::text]));

CREATE INDEX idx_pre_token_live_node_instance ON pre_token USING btree (tenant_id, definition_id, current_node_id, instance_id) WHERE (status = ANY (ARRAY['ACTIVE'::text, 'WAITING_USER'::text, 'WAITING_ASYNC'::text, 'WAITING_TIMER'::text, 'WAITING_EVENT'::text, 'WAITING_CHILD'::text]));

CREATE INDEX idx_pre_token_scope ON pre_token USING btree (tenant_id, scope_token_id, status);

CREATE INDEX pre_history_default_tenant_id_instance_id_occurred_at_id_idx ON pre_history_default USING btree (tenant_id, instance_id, occurred_at, id);

CREATE INDEX pre_history_default_time_brin ON pre_history_default USING brin (occurred_at);

CREATE INDEX pre_node_stats_hourly_default_bucket_start_idx ON pre_node_stats_hourly_default USING brin (bucket_start);

CREATE UNIQUE INDEX uq_pre_agent_replay_idempotency ON pre_agent_replay USING btree (tenant_id, idempotency_key) WHERE (idempotency_key IS NOT NULL);

CREATE UNIQUE INDEX uq_pre_instance_business_key ON pre_instance USING btree (tenant_id, definition_key, business_key) WHERE (business_key IS NOT NULL);

CREATE UNIQUE INDEX uq_pre_instance_call ON pre_instance USING btree (tenant_id, parent_token_id, call_attempt) WHERE (parent_token_id IS NOT NULL);

CREATE UNIQUE INDEX uq_pre_token_root ON pre_token USING btree (tenant_id, instance_id) WHERE ((token_kind = 'SCOPE'::text) AND (scope_token_id IS NULL));

ALTER INDEX pre_history_pkey ATTACH PARTITION pre_history_default_pkey;

ALTER INDEX idx_pre_history_instance ATTACH PARTITION pre_history_default_tenant_id_instance_id_occurred_at_id_idx;

ALTER INDEX idx_pre_history_time_brin ATTACH PARTITION pre_history_default_time_brin;

ALTER INDEX idx_pre_node_stats_hourly_bucket ATTACH PARTITION pre_node_stats_hourly_default_bucket_start_idx;

ALTER INDEX pre_node_stats_hourly_pkey ATTACH PARTITION pre_node_stats_hourly_default_pkey;

CREATE TRIGGER pre_definition_history_no_change BEFORE DELETE OR UPDATE ON pre_definition_history FOR EACH ROW EXECUTE FUNCTION pre_definition_history_append_only();

CREATE TRIGGER pre_definition_history_no_truncate BEFORE TRUNCATE ON pre_definition_history FOR EACH STATEMENT EXECUTE FUNCTION pre_definition_history_append_only();

CREATE TRIGGER pre_definition_layout_no_change BEFORE DELETE OR UPDATE ON pre_definition_layout FOR EACH ROW EXECUTE FUNCTION pre_definition_layout_append_only();

CREATE TRIGGER pre_definition_layout_no_truncate BEFORE TRUNCATE ON pre_definition_layout FOR EACH STATEMENT EXECUTE FUNCTION pre_definition_layout_append_only();

CREATE TRIGGER pre_migration_history_no_change BEFORE DELETE OR UPDATE ON pre_migration_history FOR EACH ROW EXECUTE FUNCTION pre_migration_history_append_only();

CREATE TRIGGER pre_migration_history_no_truncate BEFORE TRUNCATE ON pre_migration_history FOR EACH STATEMENT EXECUTE FUNCTION pre_migration_history_append_only();

ALTER TABLE ONLY pre_instance
    ADD CONSTRAINT fk_pre_instance_parent FOREIGN KEY (tenant_id, parent_instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_agent_replay_case
    ADD CONSTRAINT pre_agent_replay_case_tenant_id_replay_id_fkey FOREIGN KEY (tenant_id, replay_id) REFERENCES pre_agent_replay(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY pre_compensation_stack
    ADD CONSTRAINT pre_compensation_stack_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_compensation_stack
    ADD CONSTRAINT pre_compensation_stack_tenant_id_scope_token_id_fkey FOREIGN KEY (tenant_id, scope_token_id) REFERENCES pre_token(tenant_id, id);

ALTER TABLE ONLY pre_definition_history
    ADD CONSTRAINT pre_definition_history_tenant_id_definition_id_fkey FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_definition_layout
    ADD CONSTRAINT pre_definition_layout_tenant_id_definition_id_fkey FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_event_subscription
    ADD CONSTRAINT pre_event_subscription_tenant_id_definition_id_fkey FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_event_subscription
    ADD CONSTRAINT pre_event_subscription_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_holiday
    ADD CONSTRAINT pre_holiday_tenant_id_calendar_id_fkey FOREIGN KEY (tenant_id, calendar_id) REFERENCES pre_calendar(tenant_id, id);

ALTER TABLE ONLY pre_incident
    ADD CONSTRAINT pre_incident_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_instance
    ADD CONSTRAINT pre_instance_tenant_id_definition_id_fkey FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_message_subscription
    ADD CONSTRAINT pre_message_subscription_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_message_subscription
    ADD CONSTRAINT pre_message_subscription_tenant_id_token_id_fkey FOREIGN KEY (tenant_id, token_id) REFERENCES pre_token(tenant_id, id);

ALTER TABLE ONLY pre_migration_history
    ADD CONSTRAINT pre_migration_history_tenant_id_migration_id_fkey FOREIGN KEY (tenant_id, migration_id) REFERENCES pre_migration(tenant_id, id);

ALTER TABLE ONLY pre_migration_instance
    ADD CONSTRAINT pre_migration_instance_tenant_id_migration_id_fkey FOREIGN KEY (tenant_id, migration_id) REFERENCES pre_migration(tenant_id, id);

ALTER TABLE ONLY pre_migration
    ADD CONSTRAINT pre_migration_tenant_id_source_definition_id_fkey FOREIGN KEY (tenant_id, source_definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_migration
    ADD CONSTRAINT pre_migration_tenant_id_target_definition_id_fkey FOREIGN KEY (tenant_id, target_definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_step_result
    ADD CONSTRAINT pre_step_result_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_task
    ADD CONSTRAINT pre_task_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_task
    ADD CONSTRAINT pre_task_tenant_id_token_id_fkey FOREIGN KEY (tenant_id, token_id) REFERENCES pre_token(tenant_id, id);

ALTER TABLE ONLY pre_timer
    ADD CONSTRAINT pre_timer_tenant_id_definition_id_fkey FOREIGN KEY (tenant_id, definition_id) REFERENCES pre_definition(tenant_id, id);

ALTER TABLE ONLY pre_timer
    ADD CONSTRAINT pre_timer_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_token
    ADD CONSTRAINT pre_token_tenant_id_instance_id_fkey FOREIGN KEY (tenant_id, instance_id) REFERENCES pre_instance(tenant_id, id);

ALTER TABLE ONLY pre_token
    ADD CONSTRAINT pre_token_tenant_id_scope_token_id_fkey FOREIGN KEY (tenant_id, scope_token_id) REFERENCES pre_token(tenant_id, id);

