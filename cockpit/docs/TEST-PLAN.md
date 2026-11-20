# Cockpit: Test Plan

Every statement in [`SPEC.md`](SPEC.md) has at least one automated test whose name starts with the statement's test id (`TR-QA-01`). A CI script lists the statement ids in `SPEC.md` and fails when a test with that id prefix is missing for a statement whose milestone has been reached. The script is `tools/spec-coverage.py`, run by the CI job "Spec-to-test coverage"; it reads the statement ids in `SPEC.md`. Statements not delivered yet are listed with their milestone in [`spec-pending.txt`](spec-pending.txt). The gate fails on any entry whose milestone is at or before the current milestone in `tools/spec-coverage.ini`, and warns when an entry already has a test; the pull request that adds the test removes the line. Only running tests count: disabled, skipped, `todo` and commented-out tests do not.

## Strategy
| Layer | Tool | Scope | Gate |
|---|---|---|---|
| Unit and component | Vitest, Testing Library | Config validation, k-way merge, host state machine, overlays, forms | Every pull request |
| End to end | Playwright against the examples and stub hosts | Sign-in with a test identity provider, federation, tenant switching, task and incident flows | Every pull request (Chromium), nightly (Chromium, Firefox, WebKit) |
| Accessibility | axe-core in Playwright | Every screen in light and dark themes | Every pull request |
| Contract | `zod` schemas generated from the OpenAPI documents and the JSON schemas in `api/schema` | Responses of stub hosts and of the example hosts | Every pull request |
| Bundle and licence | Bundle size check; licence check of the shipped bundle | `TR-UI-06`; `TR-LIC-01` (rejects bpmn-js, elkjs, JointJS by name) | Every pull request |

## Specification coverage
| Spec id | Test | Level | Milestone | Traces to | Status |
|---|---|---|---|---|---|
| CKP-001 | `CKP_001_*` (case A: `cockpit/web/e2e/shell.spec.ts`, `cockpit/web/src/shell/CockpitShell.test.tsx`, `CockpitStarterTest`, `CockpitEmbeddedIT`) | Playwright, Vitest, MockMvc, Testcontainers | M1 | `TR-UI-01`, `ADR-0005`, `ADR-0014` | Pending. Case A (embedded boot) is automated; the statement stays pending until case B (standalone, #11) and the rule set list step (Sprint 2) are automated, or until the statement is split |
| CKP-002 | `CKP_002_*` (`ChangesView.test.tsx`, `versionDiff.test.ts`, `RuleScreens.test.tsx`, `e2e/version-diff.spec.ts`) | Vitest, Testing Library, Playwright, axe-core | M1 | `TR-UI-02` | Automated. The engine's diff (`RLE-028`) through the mock engine in the engine's response shape; side-by-side rows, effective dates, `nonAscii` marks, header and column notes, a refused diff, EN and VI, Approve above the fold at 1440x900 and 1440x1200 in both themes. Review demo data (`src/mocks/reviewDemo.ts`): a 20-row diff with the decision pinned and the last row above it, and a hit-policy change, an added column and a look-alike character. Stale-diff guard: Approve off when the diff is of other content (Vitest) and when the version changes under the reader, then enabled with the new lock version after review (Playwright) |
| CKP-003 | `CKP_003_maker_checker_ui_blocks_requester_self_approval` | Playwright | M1 | `TR-SEC-04`, `TR-UI-02` | Planned |
| CKP-004 | `CKP_004_simulation_displays_outcome_changes_safely` | Playwright | M1 | `TR-UI-02` | Planned |
| CKP-005 | `CKP_005_decision_log_masks_pii_and_highlights_matched_row` | Testing Library | M1 | `TR-SEC-05`, `TR-OBS-05` | Planned |
| CKP-006 | `CKP_006_grid_editor_shows_row_validation_errors` | Playwright | M2 | `TR-DET-02`, `TR-UI-02` | Planned |
| CKP-007 | `CKP_007_agent_task_inspector_shows_guardrail_and_override_controls` | Testing Library | M2 | `TR-AGT-01`, `TR-AGT-02` | Planned |
| CKP-008 | `CKP_008_incident_queue_retries_and_updates_status` | Playwright | M2 | `TR-OPS-03` | Planned |
| CKP-009 | `CKP_009_wcag_aa_and_keyboard_navigation_pass` | Playwright, axe-core | M1 (Cockpit 0.1 screens); re-run at M2 over Cockpit 0.2 screens | `TR-UI-03` | Planned |
| CKP-010 | `CKP_010_en_and_vi_i18n_coverage_complete` | Vitest, key parity | M1 (Cockpit 0.1 screens); re-run at M2 over Cockpit 0.2 screens | `TR-UI-04` | Planned |
| CKP-011 | `CKP_011_replay_simulation_displays_outcome_changes_safely` | Playwright | M3 (replay simulation moved to M3 by PD-042) | `TR-UI-02` | Planned |
| CKP-012 | `CKP_012_*` (`registry.test.ts`, `CockpitShell.test.tsx`, `e2e/shell.spec.ts`) | Vitest, Playwright network capture | M1 | `TR-UI-01`, `TR-SEC-09` | Pending. Parts (b) http `baseUrl` and (c) duplicate id, each with no host call, are automated; part (a), listing two hosts, needs the host list (#11) |
| CKP-013 | `CKP_013_pkce_sign_in_with_per_host_tokens_in_memory` | Playwright, test identity provider | M1 | `TR-SEC-12`, `TR-UI-01` | Planned |
| CKP-014 | `CKP_014_meta_features_and_version_skew_gate_screens` | Testing Library, mocked hosts | M1 | `TR-UI-08` | Planned |
| CKP-015 | `CKP_015_host_switch_shows_one_host_at_a_time` | Playwright | M1 | `TR-UI-01` | Planned |
| CKP-016 | `CKP_016_definition_diagram_auto_layout_and_render_budget` | Playwright, render timing | M2 | `TR-UI-07`, `TR-DEF-02` | Planned |
| CKP-017 | `CKP_017_instance_diagram_shows_tokens_and_activities` | Testing Library | M2 | `TR-UI-07`, `TR-UI-03` | Planned |
| CKP-018 | `CKP_018_live_counts_overlay_on_definition_diagram` | Testing Library, fake timers | M2 (cut candidate) | `TR-PERF-09` | Planned |
| CKP-019 | `CKP_019_merged_inbox_exact_order_partial_banner_and_304` | Playwright, stub hosts | M2 | `TR-UI-01`, `TR-PERF-07` | Planned |
| CKP-020 | `CKP_020_merged_incident_list_routes_actions_to_host` | Playwright, stub hosts | M2 | `TR-OPS-03` | Planned |
| CKP-021 | `CKP_021_task_form_completion_and_link` | Playwright | M2 | `TR-UI-02`, `TR-UI-03`, `TR-UI-04` | Planned |
| CKP-022 | `CKP_022_tenant_switcher_allowed_tenants_only` | Playwright, proxy | M2 | `TR-SEC-03`, `TR-TEN-01` | Planned |
| CKP-023 | `CKP_023_cockpit_drafts_off_hides_authoring` | Testing Library | M2 | `TR-DEF-04` | Planned |
| CKP-024 | `CKP_024_process_approval_graph_diff_blocks_requester` | Playwright | M3 | `TR-SEC-04`, `TR-UI-02` | Planned |
| CKP-025 | `CKP_025_modeller_offers_catalogue_steps_and_saves_draft` | Playwright | M3 (cut candidate, fallback 1.1) | `TR-SEC-01`, `TR-UI-02` | Planned |
| CKP-026 | `CKP_026_heatmap_window_stats_per_node` | Testing Library | M3 (cut candidate) | `TR-PERF-10` | Planned |
| CKP-027 | `CKP_027_migration_plan_side_by_side_diagrams` | Playwright | M3 | `TR-MIG-03` | Planned |
| CKP-028 | `CKP_028_bundle_budget_and_lazy_diagram_chunk` | CI bundle check, Playwright throttled | M2 | `TR-UI-06` | Planned |
| CKP-029 | `CKP_029_diagram_nodes_by_bpmn_type_unknown_rendered_generic` | Component | M2 | `TR-UI-07`, `TR-UI-08` | Planned |
| CKP-030 | `CKP_030_rule_set_browser_lists_reads_imports_and_exports` | Playwright at 1440x900 and 1440x1200, network capture | M1 | `TR-UI-02`, `TR-IDEM-01` | Planned |
| CKP-031 | `CKP_031_cockpit_opens_on_home_with_role_content_or_host_landing` | Playwright with stubbed `/meta` and `/me`, one case per role including a user with no rule permission | M1 | `TR-UI-08`, `TR-EXT-08` | Planned |
| CKP-032 | `CKP_032_minimal_draft_editing_shows_errors_on_cells` | Playwright at 1440x900, network capture | M1 | `TR-UI-02`, `TR-IDEM-01` | Planned |
| CKP-033 | `CKP_033_compensation_runs_in_instance_timeline` | Playwright against the process example host | M2 | `TR-COMP-01`, `TR-OBS-01` | Planned |
| CKP-034 | `CKP_034_sla_breach_filter_uses_task_deadlines` | Playwright, network capture | M2 | `TR-CAL-01`, `TR-PERF-07` | Planned |
| CKP-035 | `CKP_035_diagram_step_list_and_text_states` | Playwright keyboard and axe-core, both themes | M2 | `TR-UI-03` | Planned |
| CKP-036 | `CKP_036_canvas_exemption_names_import_path` | Playwright with screen-reader assertions | M3 | `TR-UI-03`, `TR-DEF-04` | Planned |
| CKP-037 | `CKP_037_*` (`cockpit/web/e2e/personal-data.spec.ts`, `cockpit/web/src/features/rules/PersonalData.test.tsx`, `cockpit/web/src/features/rules/model/masking.test.ts`) | Playwright at 1440x900 and 1440x1200, light and dark, English and Vietnamese, axe-core; Vitest with the fake engine | M1 | `TR-UI-02`, `TR-UI-03`, `TR-UI-04` | Automated |

## Known gaps
| Gap | Plan |
|---|---|
| Keyboard node list and canvas exemption notice are pending PD-048 | Statements follow the decision |
| Federation envelope (`TR-UI-05`) is pending PD-046 | Stub-host load test designed with the process engine load harness |
| Identity providers without refresh tokens for browser clients (DESIGN open question 15.2) | A second sign-in test profile once the fallback is decided |
