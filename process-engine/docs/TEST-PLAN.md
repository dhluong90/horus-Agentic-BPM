# Process Engine: Test Plan

Every statement in [`SPEC.md`](SPEC.md) has at least one automated test whose name starts with the statement's test id (`TR-QA-01`). A CI script lists the statement ids in `SPEC.md` and fails when a test with that id prefix is missing for a statement whose milestone has been reached. The script is `tools/spec-coverage.py`, run by the CI job "Spec-to-test coverage"; it reads the statement ids in `SPEC.md`. Statements not delivered yet are listed with their milestone in [`spec-pending.txt`](spec-pending.txt). The gate fails on any entry whose milestone is at or before the current milestone in `tools/spec-coverage.ini`, and warns when an entry already has a test; the pull request that adds the test removes the line. Only running tests count: disabled, skipped, `todo` and commented-out tests do not.

## Strategy
| Layer | Tool | Scope | Gate |
|---|---|---|---|
| Unit and property | JUnit 5, AssertJ, the project's seeded generator (PD-090) | Lifecycle guards, graph diff, RFC 8785 canonicaliser, calendar arithmetic, BPMN reader | Every pull request |
| Storage contract | Shared suite | In-memory and PostgreSQL adapters | Every pull request |
| Integration and fault | Testcontainers, Toxiproxy | Two replicas, paused claimants, 2-connection pool, failover, rollup, partitions | Every pull request (PostgreSQL 16), nightly (full matrix) |
| Query plans | `EXPLAIN (FORMAT JSON)` on a seeded database (1 million instances, 20 million history rows) | Every registered list query (`TR-PERF-07`) | Every pull request touching `process-engine-postgres` |
| Contract | WebMvc tests, OpenAPI diff, JSON Schema | Every error code in the DESIGN REST table; event schemas | Every pull request |
| Architecture | ArchUnit | Core boundaries; adapter ports never called inside a transaction | Every build |
| Load | Load harness on the dedicated runner | `TR-PERF-04`, `TR-PERF-08`, `TR-PERF-09` | M2 exit, then per release |

## Specification coverage
| Spec id | Test | Level | Milestone | Traces to | Status |
|---|---|---|---|---|---|
| PRE-001 | `PRE_001_running_instance_pins_definition_version` | Integration | M2 | `TR-DET-01` | Planned |
| PRE-002 | `PRE_002_business_key_and_idempotency_prevent_duplicate_starts` | Concurrent integration | M2 | `TR-IDEM-01`, `TR-IDEM-02` | Planned |
| PRE-003 | `PRE_003_stale_task_completion_returns_conflict` | Concurrency | M2 | `TR-IDEM-03` | Planned |
| PRE-004 | `PRE_004_exclusive_gateway_routes_single_matching_branch` | Unit | M2 | `TR-DET-01` | Planned |
| PRE-005 | `PRE_005_parallel_gateway_join_prevents_concurrent_lost_wakeup` | Two-replica Testcontainers, 1,000 iterations | M2 | `TR-PERF-05` | Planned |
| PRE-006 | `PRE_006_timer_lease_recovers_after_worker_crash` | Two-replica fault injection | M2 | `TR-PERF-05` | Planned |
| PRE-007 | `PRE_007_four_eyes_blocks_same_actor_approval` | Service, WebMvc | M2 | `TR-SEC-04` | Planned |
| PRE-008 | `PRE_008_business_calendar_skips_holidays_and_weekends` | Unit across holidays and DST | M2 | `TR-CAL-01`, `TR-CAL-02` | Planned |
| PRE-009 | `PRE_009_agent_task_executes_outside_db_transaction` | Testcontainers, 2-connection pool | M2 | `TR-AGT-03`, `TR-EMB-04` | Planned |
| PRE-010 | `PRE_010_agent_task_guardrail_violation_routes_to_human` | Integration, stub adapter | M2 | `TR-AGT-01` | Planned |
| PRE-011 | `PRE_011_agent_task_exhausted_retries_raises_incident` | Fault injection | M2 | `TR-AGT-01`, `TR-AGT-03`, `TR-OPS-03` | Planned |
| PRE-012 | `PRE_012_agent_task_audits_masked_io_and_human_override` | Integration, history capture | M2 | `TR-AGT-02`, `TR-OBS-01` | Planned |
| PRE-013 | `PRE_013_failure_executes_reverse_saga_compensation` | Fault injection | M2 | `TR-COMP-01`, `TR-COMP-02`, `TR-COMP-03`, `TR-IDEM-04` | Planned |
| PRE-014 | `PRE_014_migration_validate_reports_unmapped_steps_without_mutation` | Integration | M2 | `TR-MIG-01` | Planned |
| PRE-015 | `PRE_015_migration_execute_isolates_instance_failure` | Fault injection, 250 instances | M2 | `TR-MIG-02` | Planned |
| PRE-016 | `PRE_016_deploy_rejects_guardrail_rule_set_breaking_contract` | WebMvc | M2 | `TR-AGT-01` | Planned |
| PRE-017 | `PRE_017_async_lease_is_timeout_plus_margin` | Two-replica fault injection | M2 | `TR-AGT-03`, `TR-PERF-05` | Planned |
| PRE-018 | `PRE_018_import_creates_draft_not_active_version` | WebMvc, integration | M2 | `TR-DEF-03`, `TR-DEF-04` | Planned |
| PRE-019 | `PRE_019_definition_requester_cannot_approve` | Service, WebMvc | M2 | `TR-SEC-04`, `TR-DEF-03` | Planned |
| PRE-020 | `PRE_020_effective_date_switches_version_and_retires_previous` | Integration, TestClock | M2 | `TR-DEF-03`, `TR-DET-01` | Planned |
| PRE-021 | `PRE_021_reject_returns_draft_and_superseded_approval_refused` | WebMvc | M2 | `TR-DEF-03` | Planned |
| PRE-022 | `PRE_022_dev_only_relaxations_refused_outside_dev` | Starter boot tests | M2 | `TR-SEC-11` | Planned |
| PRE-023 | `PRE_023_cockpit_drafts_setting_blocks_authoring_only` | WebMvc | M2 | `TR-DEF-04` | Planned |
| PRE-024 | `PRE_024_content_hash_excludes_diagram_and_matches_git` | Unit, RFC 8785 vectors, CI hash script | M2 | `TR-DEF-04`, `TR-DEF-02` | Planned |
| PRE-025 | `PRE_025_graph_diff_reports_nodes_and_edges` | Unit | M2 | `TR-DEF-03` | Planned |
| PRE-026 | `PRE_026_layout_revision_append_only_without_approval` | Concurrency, WebMvc | M3 | `TR-DEF-02` | Planned |
| PRE-027 | `PRE_027_activities_bounded_by_instance_lifetime` | Testcontainers, plan capture | M2 | `TR-PERF-08`, `TR-OBS-01` | Planned |
| PRE-028 | `PRE_028_live_node_counts_from_token_index` | Testcontainers, SQL capture | M2 | `TR-PERF-09` | Planned |
| PRE-029 | `PRE_029_rollup_bounded_lag_and_no_step_tx_writes` | Two-replica Testcontainers, SQL capture | M3 | `TR-PERF-10` | Planned |
| PRE-030 | `PRE_030_task_query_filters_bounded_by_index` | Testcontainers, plan capture | M2 | `TR-PERF-07`, `TR-TEN-01` | Planned |
| PRE-031 | `PRE_031_task_form_resolved_and_completion_validated` | Service, WebMvc | M2 | `TR-UI-02`, `TR-SEC-05` | Planned |
| PRE-032 | `PRE_032_step_catalogue_lists_registered_steps_only` | WebMvc | M3 | `TR-SEC-01` | Planned |
| PRE-033 | `PRE_033_bpmn_subset_import_rejects_unsupported_by_name` | Unit, adversarial XML | M3 | `TR-DEF-01` | Planned |
| PRE-034 | `PRE_034_bpmn_export_round_trip_preserves_hash` | Round trip over examples, XSD validation | M3 (cut candidate) | `TR-DEF-01` | Planned |
| PRE-035 | `PRE_035_definition_size_limits_enforced` | WebMvc | M2 | `TR-DEF-05` | Planned |
| PRE-036 | `PRE_036_list_queries_pass_query_plan_check` | Query-plan check on a seeded database | M2 | `TR-PERF-07` | Planned |
| PRE-037 | `PRE_037_definition_events_published_in_transaction` | Integration, rollback | M2 | `TR-OBS-04`, `TR-OBS-06` | Planned |
| PRE-038 | `PRE_038_tenant_header_accepted_only_within_allowed_tenants` | WebMvc, log capture | M2 | `TR-SEC-03`, `TR-TEN-01` | Planned |
| PRE-039 | `PRE_039_import_keeps_source_document_and_fixed_hash` | Integration, WebMvc | M2 | `TR-STD-01`, `TR-STD-03` | Planned |
| PRE-040 | `PRE_040_business_error_result_opens_incident` | Integration | M2 | `TR-OPS-03`, `TR-IDEM-04` | Planned |
| PRE-041 | `PRE_041_cancel_during_adapter_call_discards_late_result` | Testcontainers fault injection, 2-connection pool | M2 | `TR-ASY-01`, `TR-ASY-02`, `TR-STD-12` | Planned |
| PRE-042 | `PRE_042_bpmn_style_step_ids_kept` | Unit, integration | M2 | `TR-STD-03`, `TR-DEF-01` | Planned |
| PRE-043 | `PRE_043_v1_definition_round_trips_through_bpmn_model` | Unit over every example definition, RFC 8785 vectors | M2 | `TR-STD-28`, `TR-STD-03`, `TR-DEF-04` | Planned |
| PRE-044 | `PRE_044_joins_and_compensation_keyed_by_scope_token` | Testcontainers integration, SQL capture of `pre_token` and `pre_compensation_stack` | M2 | `TR-STD-29`, `TR-ASY-03`, `TR-COMP-01` | Planned |
| PRE-045 | `PRE_045_engine_rest_bodies_ignore_host_json_settings` in `EngineJsonSettingsIT` | Testcontainers host over HTTP in the Spring Boot 4.x and 3.5 legs, dependency-tree check of `examples/rule-and-process` in the 3.5 legs | M2 | `TR-EMB-07`, `TR-API-06` | Done |
| PRE-046 | `PRE_046_decision_step_uses_the_instance_tenant_and_the_engine_identity` | Service with both engines, no-principal harness, Testcontainers two tenants | M2 | `TR-TEN-01`, `TR-IDEM-04` | Planned |
| PRE-047 | `PRE_047_decision_port_refuses_a_request_tenant_other_than_the_instance_tenant` | Service, security log capture | M2 | `TR-TEN-01` | Planned |
| PRE-048 | `PRE_048_kernel_runs_only_the_approved_source` | Testcontainers with a tampered row, event and audit capture | M2 | `TR-DEF-04`, `TR-STD-01` | Planned |
| PRE-049 | `PRE_049_*` in `ProcessRestApiIT` | Testcontainers host over HTTP, two tenants | M2 | `TR-TEN-01`, `TR-PERF-07` | Done (the query-plan check of `TR-PERF-07` is still planned) |
| PRE-050 | `PRE_050_*` in `ProcessRestApiIT` | Testcontainers host over HTTP, concurrent requests, event capture after commit | M2 | `TR-IDEM-01`, `TR-IDEM-03`, `TR-OBS-04` | Done |

The error matrix of the REST layer (`TR-SEC-08`) is `ProcessRestErrorMatrixTest` in `process-engine-rest`: MockMvc over the controller with in-memory services, every code of `ProcessProblemDetails.STATUS` answered as `application/problem+json` with `code`, `correlationId` and `type`, and an injected failure answered `500 INTERNAL` with no message, class name or SQL. Tenant isolation over HTTP (`TR-TEN-01`) is `ProcessRestApiIT.TR_TEN_01_*`: another tenant's read, list and cancel are not found and write nothing, and a tenant header for another tenant is `403 TENANT_NOT_ALLOWED`.

## Known gaps
| Gap | Plan |
|---|---|
| `TR-UI-05` load envelope depends on PD-046 | The 15-host stub test is designed now and gated after the decision |
| Migration plan storage has no DDL (DESIGN open question 11.5) | Statements `PRE-014` and `PRE-015` are tested against the service layer until the table exists |
| Approver distinct from every editor (DESIGN open question 11.2) | No statement until decided |
