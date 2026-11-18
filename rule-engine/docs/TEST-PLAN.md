# Rule Engine: Test Plan

Every statement in [`SPEC.md`](SPEC.md) has at least one automated test whose name starts with the statement's test id (`TR-QA-01`). A CI script lists the statement ids in `SPEC.md` and fails when a test with that id prefix is missing for a statement whose milestone has been reached.

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

## Known gaps
| Gap | Plan |
|---|---|
| The Jackson major for `*-core` is undecided (DESIGN open question 7.1) | Decide at M1 start; the contract suite then runs on both Spring Boot lines |
| The licence scope for JUnit, jqwik and JMH is pending PD-053 | Tests are written now; the CI licence check is configured after the decision |
