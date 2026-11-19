# Rule Engine: Test Plan

Every statement in [`SPEC.md`](SPEC.md) has at least one automated test whose name starts with the statement's test id (`TR-QA-01`). A CI script lists the statement ids in `SPEC.md` and fails when a test with that id prefix is missing for a statement whose milestone has been reached.

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
| RLE-001 | `RLE_001_first_hit_policy_returns_top_matching_rule` | Unit | M1 | `TR-DET-01` | Planned |
| RLE-002 | `RLE_002_unique_hit_policy_rejects_zero_or_multiple_matches` | Unit, WebMvc | M1 | `TR-DET-04` | Planned |
| RLE-003 | `RLE_003_collect_hit_policy_aggregates_matched_rules` | Unit | M1 | `TR-DET-01` | Planned |
| RLE-004 | `RLE_004_publish_rejects_unparsable_or_ill_typed_expressions` | Unit, property | M1 | `TR-DET-02`, `TR-SEC-01` | Planned |
| RLE-005 | `RLE_005_money_currency_mismatch_is_rejected` | Unit | M1 | `TR-DET-03` | Planned |
| RLE-006 | `RLE_006_null_comparisons_are_false_unless_is_null` | Unit | M1 | `TR-DET-01` | Planned |
| RLE-007 | `RLE_007_regex_timeout_aborts_adversarial_input` | Unit, adversarial | M1 | `TR-SEC-02` | Planned |
| RLE-008 | `RLE_008_explicit_clock_resolves_effective_version_and_replays` | Unit, replay | M1 | `TR-DET-01`, `TR-DET-05` | Planned |
| RLE-009 | `RLE_009_maker_checker_blocks_self_approval` | Service, WebMvc | M1 | `TR-SEC-04` | Planned |
| RLE-010 | `RLE_010_multi_replica_cache_invalidates_on_publish` | Two-replica Testcontainers, PgBouncer | M1 | `TR-DET-01`, `TR-OBS-04` | Planned |
| RLE-011 | `RLE_011_tenant_isolation_ignores_body_and_blocks_cross_tenant_read` | WebMvc, two-tenant integration | M1 | `TR-SEC-03`, `TR-TEN-01`, `TR-TEN-02` | Planned |
| RLE-012 | `RLE_012_masked_inputs_stored_as_keyed_hmac` | Integration, log capture | M1 | `TR-SEC-05`, `TR-OBS-01` | Planned |
| RLE-013 | `RLE_013_transactional_decision_log_rolls_back_with_host` | Testcontainers, SQL capture | M1 | `TR-EMB-04`, `TR-PERF-03`, `TR-OBS-01` | Planned |
| RLE-014 | `RLE_014_idempotency_key_replays_identical_and_rejects_mismatch` | Two-thread integration | M1 | `TR-IDEM-01` | Planned |
| RLE-015 | `RLE_015_simulation_leaves_decision_log_untouched` | Integration, SQL capture | M3 (replay against history moved to M3 by PD-042; simulation over sample inputs is M1, `RLE-016`) | `TR-OBS-01` | Planned |
| RLE-016 | `RLE_016_sample_simulation_leaves_decision_log_untouched` | Integration, SQL capture | M1 | `TR-OBS-01`, `TR-DET-01` | Planned |
| RLE-017 | `RLE_017_decision_log_monthly_partitions_and_retention_by_drop` | Testcontainers migration, SQL capture | M1 | `TR-OPS-04`, `TR-OBS-01` | Planned |
| RLE-018 | `RLE_018_tenant_header_accepted_only_within_allowed_tenants` | WebMvc, log capture | M2 | `TR-SEC-03`, `TR-TEN-01` | Planned |
| RLE-019 | `RLE_019_meta_and_me_describe_host_and_caller` | WebMvc, JSON Schema contract | M1 | `TR-UI-08`, `TR-SEC-08` | Planned |
| RLE-020 | `RLE_020_import_keeps_source_document_and_canonical_hash` | Integration, WebMvc | M1 | `TR-STD-01`, `TR-STD-03` | Planned |
| RLE-021 | `RLE_021_v1_expressions_match_their_feel_translation` | Property, differential against a reference FEEL in test scope | M1 (proposed with ADR-0019) | `TR-STD-02`, `TR-DET-01` | Planned |
| RLE-022 | `RLE_022_today_uses_rule_set_time_zone` | Unit, replay | M1 | `TR-DET-01`, `TR-DET-05` | Planned |

## Requirement-level tests
Tests that prove a platform requirement directly, before any `SPEC.md` statement covers it. "Partial" means the test proves a precondition or part of the requirement; the named statement completes it.

| Requirement | Tests | Level | Coverage |
|---|---|---|---|
| `TR-EMB-01` | `RuleEngineOnlyHostIT.TR_EMB_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable`, `RuleEngineSchemaMigratorIT.TR_EMB_01_clean_database_gets_every_rle_table` | Testcontainers, example host | Full for the skeleton |
| `TR-EMB-03` | `CoreArchitectureTest.TR_EMB_03_core_has_no_framework_or_sql_imports`, plus the Maven Enforcer `bannedDependencies` rule in `rule-engine-core/pom.xml` | ArchUnit, build | Full |
| `TR-EMB-04` | `RuleEngineOnlyHostIT.TR_EMB_04_engine_uses_host_datasource_and_transaction_manager_and_no_pool_of_its_own`, `RuleEngineAutoConfigurationTest.TR_EMB_04_*`, `PostgresArchitectureTest.TR_EMB_04_*` | Bean graph, context runner, ArchUnit | **Partial.** These prove the precondition (one host `DataSource`, the transaction manager that manages it, no engine pool). The requirement's verification is the rollback test `RLE_013_transactional_decision_log_rolls_back_with_host`, which arrives with the decision log writer |
| `TR-EMB-05` | `CoreArchitectureTest.TR_EMB_05_core_has_no_network_or_file_io`, `PostgresArchitectureTest.TR_EMB_05_postgres_module_has_no_http_client` | ArchUnit | Full for the modules that exist |
| `TR-EMB-06` | `RuleEngineOnlyHostIT.TR_EMB_06_*`, `HostRestartIT.TR_EMB_06_*`, `RuleEngineSchemaMigratorIT.TR_EMB_06_*` | Testcontainers, example host | Full |
| `TR-OPS-04` | `RuleEngineSchemaMigratorIT.TR_OPS_04_*`, `RuleEngineOnlyHostIT.TR_OPS_04_*`, `RuleEngineAutoConfigurationTest.TR_OPS_04_*` | Testcontainers with fixed instants, context runner | **Partial.** Start-up creation of the current and next UTC month, UTC boundaries and the move of rows out of the default partition. Scheduled creation up to `partitions-ahead`, retention by drop and the default-rows gauge are `RLE-017` |
| `TR-SEC-01` | `CoreArchitectureTest.TR_SEC_01_core_has_no_scripting_reflection_class_loading_or_deserialisation` | ArchUnit | Partial: the boundary rule. The parser and evaluator tests of `RLE-004` and `RLE-007` complete it |

## Known gaps
| Gap | Plan |
|---|---|
| The Jackson major for `*-core` is undecided (DESIGN open question 7.1) | Decide at M1 start; the contract suite then runs on both Spring Boot lines |
| The licence scope for JUnit, jqwik and JMH is pending PD-053 | Tests are written now; the CI licence check is configured after the decision |
