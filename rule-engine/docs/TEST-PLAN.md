# Rule Engine: Test Plan

Every statement in [`SPEC.md`](SPEC.md) has at least one automated test whose name starts with the statement's test id (`TR-QA-01`). A CI script lists the statement ids in `SPEC.md` and fails when a test with that id prefix is missing for a statement whose milestone has been reached. The script is `tools/spec-coverage.py`, run by the CI job "Spec-to-test coverage"; it reads the statement ids in `SPEC.md` and the requirement ids in the "Requirement-level tests" table below. Statements not delivered yet are listed with their milestone in [`spec-pending.txt`](spec-pending.txt). The gate fails on any entry whose milestone is at or before the current milestone in `tools/spec-coverage.ini`, and warns when an entry already has a test; the pull request that adds the test removes the line. Only running tests count: disabled, skipped, `todo` and commented-out tests do not.

**Naming rule.** A test name starts with the id it proves, in upper case with hyphens written as underscores: `RLE_013_...` for a `SPEC.md` statement, `TR_EMB_01_...` for a requirement in [`requirements.md`](../../docs/product/requirements.md). The spec-to-test gate matches this prefix. A test that proves no id (a helper check, a value-type check) carries no id prefix, so it never counts as coverage.

## Strategy
| Layer | Tool | Scope | Gate |
|---|---|---|---|
| Unit and property | JUnit 5, AssertJ, jqwik | Parser, type checker, evaluator, money, hit policies, determinism (1,000 tries per property in CI, 100,000 nightly) | Every pull request |
| Storage contract | Shared suite | In-memory and PostgreSQL adapters give the same results | Every pull request |
| Integration and fault | Testcontainers, Toxiproxy | PostgreSQL 14, 16, 17; PgBouncer in transaction mode; two replicas; partition maintenance | Every pull request (PostgreSQL 16), nightly (full matrix) |
| Contract | WebMvc tests, OpenAPI diff, JSON Schema | Every error code in the DESIGN error table; `/api/bpm/meta` and `/me` schemas | Every pull request |
| Architecture | ArchUnit | Core free of Spring, SQL, network, scripting, reflection; API signatures | Every build |
| Performance | JMH on the dedicated runner | `TR-PERF-01`, `TR-PERF-11`, `TR-PERF-12` | Release, and nightly trend |

## Specification coverage
| Spec id | Test | Level | Milestone | Traces to | Status |
|---|---|---|---|---|---|
| RLE-001 | `RLE_001_first_hit_policy_returns_top_matching_rule` | Unit, Testcontainers, REST | M1 | `TR-DET-01` | Implemented (#1): `RuleEngineServiceTest`, `RuleEnginePostgresIT`, `RuleLifecycleIT`; boundaries, input types and money exactness in `RuleLifecycleAcceptanceTest`, `MoneyPropertiesTest` (jqwik), `ExpressionPropertiesTest` (determinism, jqwik) and `RuleLifecycleFailurePathsIT` |
| RLE-002 | `RLE_002_unique_hit_policy_rejects_zero_or_multiple_matches` | Unit, WebMvc | M1 | `TR-DET-04` | Planned |
| RLE-003 | `RLE_003_collect_hit_policy_aggregates_matched_rules` | Unit | M1 | `TR-DET-01` | Planned |
| RLE-004 | `RLE_004_publish_rejects_unparsable_or_ill_typed_expressions` | Unit, property | M1 | `TR-DET-02`, `TR-SEC-01` | Planned |
| RLE-005 | `RLE_005_money_currency_mismatch_is_rejected` | Unit, REST | M1 | `TR-DET-03` | Implemented (#1): `RuleLifecycleFailurePathsIT` (422 `EVALUATION_ERROR`, no outputs, logged outcome); `MoneyPropertiesTest` for the evaluator |
| RLE-006 | `RLE_006_null_follows_feel_three_valued_logic` | Unit, replay | M1 | `TR-DET-01`, `TR-STD-02` | Partial (#1): truth tables and null operands in `ExpressionPropertiesTest` (jqwik). Stays in `spec-pending.txt` until the `collect` hit policy lands: the statement needs `collect`, the decision log and replay |
| RLE-007 | `RLE_007_regex_timeout_aborts_adversarial_input` | Unit, adversarial | M1 | `TR-SEC-02` | Planned |
| RLE-008 | `RLE_008_explicit_clock_resolves_effective_version_and_replays` | Unit, replay | M1 | `TR-DET-01`, `TR-DET-05` | Planned |
| RLE-009 | `RLE_009_maker_checker_blocks_self_approval` | Service, Testcontainers, REST | M1 | `TR-SEC-04` | Implemented (#1): `VersionServiceTest`, `RuleEnginePostgresIT` (also the database check constraint), `RuleLifecycleIT`; no event and no lock change on refusal in `RuleLifecycleAcceptanceTest` and `RuleLifecycleFailurePathsIT` |
| RLE-010 | `RLE_010_multi_replica_cache_invalidates_on_publish` | Two-replica Testcontainers, PgBouncer | M1 | `TR-DET-01`, `TR-OBS-04` | Planned |
| RLE-011 | `RLE_011_tenant_isolation_ignores_body_and_blocks_cross_tenant_read` | REST, two-tenant integration | M1 | `TR-SEC-03`, `TR-TEN-01`, `TR-TEN-02` | Partial (#1): `RuleLifecycleIT` covers evaluation and version reads; `GET /decisions/{id}` arrives with the decision log query slice. Header and body tenant fields ignored in `RuleLifecycleFailurePathsIT`; same name in two tenants in `RuleLifecycleAcceptanceTest` and `RuleLifecycleAcceptancePostgresIT` |
| RLE-012 | `RLE_012_masked_inputs_stored_as_keyed_hmac` | Integration, log capture | M1 | `TR-SEC-05`, `TR-OBS-01` | Planned |
| RLE-013 | `RLE_013_transactional_decision_log_rolls_back_with_host` | Testcontainers, SQL capture | M1 | `TR-EMB-04`, `TR-PERF-03`, `TR-OBS-01` | Implemented (#1): `RuleEnginePostgresIT`, `RuleLifecycleAcceptancePostgresIT` (one insert per committed evaluation, several evaluations rolled back) |
| RLE-014 | `RLE_014_idempotency_key_replays_identical_and_rejects_mismatch` | Service, two-thread Testcontainers, REST | M1 | `TR-IDEM-01` | Implemented (#1): `VersionServiceTest`, `RuleEngineServiceTest`, `RuleEnginePostgresIT` (in flight), `RuleLifecycleIT`; tenant-scoped keys, import-version and evaluation replay in `RuleLifecycleAcceptanceTest`, `RuleLifecycleAcceptancePostgresIT` and `RuleLifecycleFailurePathsIT` |
| RLE-015 | `RLE_015_simulation_leaves_decision_log_untouched` | Integration, SQL capture | M3 (replay against history moved to M3 by PD-042; simulation over sample inputs is M1, `RLE-016`) | `TR-OBS-01` | Planned |
| RLE-016 | `RLE_016_sample_simulation_leaves_decision_log_untouched` | Integration, SQL capture | M1 | `TR-OBS-01`, `TR-DET-01` | Planned |
| RLE-017 | `RLE_017_decision_log_monthly_partitions_and_retention_by_drop` | Testcontainers migration, SQL capture | M1 | `TR-OPS-04`, `TR-OBS-01` | Planned |
| RLE-018 | `RLE_018_tenant_header_accepted_only_within_allowed_tenants` | WebMvc, log capture | M2 | `TR-SEC-03`, `TR-TEN-01` | Planned |
| RLE-019 | `RLE_019_meta_and_me_describe_host_and_caller` | WebMvc, JSON Schema contract | M1 | `TR-UI-08`, `TR-SEC-08` | Planned |
| RLE-020 | `RLE_020_import_keeps_source_document_and_canonical_hash` | Unit, Testcontainers, REST | M1 | `TR-STD-01`, `TR-STD-03` | Implemented (#1): `VersionServiceTest`, `RuleEnginePostgresIT`, `RuleLifecycleIT`; refused documents (malformed YAML, unknown fields, wrong types, empty table) in `RuleSetReaderAcceptanceTest` and `RuleLifecycleFailurePathsIT`; BOM, CRLF and Vietnamese source in `RuleLifecycleAcceptancePostgresIT` |
| RLE-021 | `RLE_021_v1_expressions_match_their_feel_translation` | Property, differential against a reference FEEL in test scope | M1 | `TR-STD-02`, `TR-DET-01` | Planned |
| RLE-022 | `RLE_022_today_uses_rule_set_time_zone` | Unit, replay | M1 | `TR-DET-01`, `TR-DET-05` | Planned |
| RLE-023 | `RLE_023_engine_json_ignores_host_jackson_settings` | WebMvc in the Spring Boot 4.x and 3.5 legs, dependency-tree check | M1 | `TR-EMB-07`, `TR-API-06` | Planned |
| RLE-024 | `RLE_024_v1_rule_set_round_trips_through_dmn_model` | Unit over every example rule set, RFC 8785 vectors | M1 | `TR-STD-28`, `TR-STD-03` | Planned |
| RLE-025 | `RLE_025_reject_returns_pending_version_to_draft_with_reason` | Service, WebMvc, event capture | M1 | `TR-OBS-04`, `TR-IDEM-01` | Planned |
| RLE-026 | `RLE_026_manual_retire_stops_every_replica_serving_the_version` | Two-replica Testcontainers, event capture, replay | M1 | `TR-OBS-04`, `TR-ASY-04`, `TR-DET-05` | Planned |
| RLE-027 | `RLE_027_previous_version_retires_once_when_newer_becomes_effective` | Two-replica Testcontainers with `TestClock`, PgBouncer leg, event capture | M1 | `TR-OBS-04`, `TR-DET-01`, `TR-OPS-05` | Planned |
| RLE-028 | `RLE_028_version_diff_lists_added_changed_removed_and_moved_rows` | Unit (diff of models), WebMvc, two-tenant integration | M1 | `TR-UI-02`, `TR-STD-28`, `TR-STD-03` | Planned |
| RLE-029 | `RLE_029_decision_search_by_rule_set_and_period_is_bounded` | Testcontainers, query-plan check, two-tenant integration | M1 | `TR-OBS-01`, `TR-SEC-05`, `TR-TEN-01` | Planned |
| RLE-030 | `RLE_030_explanation_is_generated_from_log_entry_and_version` | Unit, WebMvc, SQL capture | M1 | `TR-OBS-05`, `TR-DET-05`; depends on PD-027 | Planned |
| RLE-031 | `RLE_031_explanation_masks_inputs_and_covers_no_match_null_and_failure` | Unit, integration, log capture | M1 | `TR-OBS-05`, `TR-SEC-05`, `TR-STD-02`; depends on PD-027 | Planned |
| RLE-032 | `RLE_032_export_round_trips_to_same_canonical_hash` | Integration, WebMvc, RFC 8785 vectors | M1 | `TR-STD-03`, `TR-STD-28`, `TR-UI-02` | Planned |
| RLE-033 | `RLE_033_import_as_new_version_creates_next_draft_or_refuses_whole` | WebMvc, integration | M1 | `TR-STD-01`, `TR-IDEM-01`, `TR-UI-02` | Planned |
| RLE-034 | `RLE_034_compiled_cache_is_bounded_and_heap_stays_flat` | Integration with heap measurement (median of three after full GC), metrics check | M1 | `TR-PERF-06` | Planned |
| RLE-035 | `RLE_035_masking_key_required_at_startup_and_never_leaked` | Context runner, example host start-up, log capture | M1 | `TR-SEC-10` | Planned |
| RLE-036 | `RLE_036_every_rest_error_is_problem_details_with_stable_code` | WebMvc over the error matrix, injected failure | M1 | `TR-SEC-08` | Planned |

## Requirement-level tests
Tests that prove a platform requirement directly, before any `SPEC.md` statement covers it. "Partial" means the test proves a precondition or part of the requirement; the named statement completes it.

| Requirement | Tests | Level | Coverage |
|---|---|---|---|
| `TR-EMB-01` | `RuleEngineOnlyHostIT.TR_EMB_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable`, `RuleEngineSchemaMigratorIT.TR_EMB_01_clean_database_gets_every_rle_table` | Testcontainers, example host | Full for the skeleton |
| `TR-EMB-03` | `CoreArchitectureTest.TR_EMB_03_core_has_no_framework_or_sql_imports`, plus the Maven Enforcer `bannedDependencies` rule in `rule-engine-core/pom.xml` | ArchUnit, build | Full |
| `TR-EMB-04` | `RuleEngineOnlyHostIT.TR_EMB_04_engine_uses_host_datasource_and_transaction_manager_and_no_pool_of_its_own`, `RuleLifecycleAcceptancePostgresIT.TR_EMB_04_*` (lifecycle writes roll back with the host; a refused evaluation the host handles does not doom its transaction), `RuleEngineAutoConfigurationTest.TR_EMB_04_*`, `PostgresArchitectureTest.TR_EMB_04_*`, `RuleEnginePostgresIT.RLE_013_*` | Bean graph, context runner, ArchUnit, Testcontainers | Full: the precondition tests plus the rollback test `RLE_013_transactional_decision_log_rolls_back_with_host` |
| `TR-EMB-05` | `CoreArchitectureTest.TR_EMB_05_core_has_no_network_or_file_io`, `PostgresArchitectureTest.TR_EMB_05_postgres_module_has_no_http_client` | ArchUnit | Full for the modules that exist |
| `TR-EMB-06` | `RuleEngineOnlyHostIT.TR_EMB_06_*`, `HostRestartIT.TR_EMB_06_*`, `RuleEngineSchemaMigratorIT.TR_EMB_06_*` | Testcontainers, example host | Full |
| `TR-OPS-04` | `RuleEngineSchemaMigratorIT.TR_OPS_04_*`, `RuleEngineOnlyHostIT.TR_OPS_04_*`, `RuleEngineAutoConfigurationTest.TR_OPS_04_*` | Testcontainers with fixed instants, context runner | **Partial.** Start-up creation of the current and next UTC month, UTC boundaries and the move of rows out of the default partition. Scheduled creation up to `partitions-ahead`, retention by drop and the default-rows gauge are `RLE-017` |
| `TR-API-06` | `CoreArchitectureTest.TR_API_06_jackson_3_only_inside_internal_and_never_jackson_2_databind` | ArchUnit | Full for the core |
| `TR-TEN-01` | `RuleEnginePostgresIT.TR_TEN_01_another_tenant_neither_sees_nor_evaluates_the_rule_set` | Testcontainers | Partial: storage and evaluation; the cross-tenant decision read arrives with `GET /decisions/{id}` |
| `TR-SEC-01` | `CoreArchitectureTest.TR_SEC_01_core_has_no_scripting_reflection_class_loading_or_deserialisation`; parser tests in `ExpressionSemanticsTest` (unknown functions and identifiers refused); `ExpressionPropertiesTest.TR_SEC_01_*` (jqwik: any text gives a tree or an `ExpressionException`, deep nesting refused without a stack overflow) | ArchUnit, unit | Partial: the boundary rule and the closed grammar. The tests of `RLE-004` and `RLE-007` complete it |

## Known gaps
| Gap | Plan |
|---|---|
| `RLE-014` in flight over REST: `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1` is proved at the service on PostgreSQL only; no HTTP test holds a first request open | A REST test with a host hook that blocks the first request, with the decision log query slice |
| Conditions that are not boolean (`tier: "'GOLD'"`) and output cells of the wrong type import and then never match or fail at evaluation | Publish-time type checking, `RLE-004` |
| Jackson 3 inside the core is conditional on the Spring Boot 3.5 coexistence test (ADR-0021) | The first parsing pull request (#1) runs on Spring Boot 4.x only; the 3.5 leg and `RLE-023` arrive with the platform compatibility slice, which must pass before 3.5 support is claimed. `RuleLifecycleIT` already runs the host with `SNAKE_CASE` and checks camelCase engine responses on Spring Boot 4 |
| The licence scope for JUnit, jqwik and JMH is pending PD-053 | Tests are written now. The CI licence check (`TR-LIC-01`) covers compile, runtime and provided scopes of the shipped modules; test scope joins it after the decision. Test-scope dependencies are already scanned for critical vulnerabilities |
