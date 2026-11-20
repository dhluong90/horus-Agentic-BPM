# Cockpit: Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated Vitest/Testing Library or Playwright test (`TR-QA-01`). Each statement carries the milestone that ships it, matching [`docs/product/roadmap.md`](../../docs/product/roadmap.md) (M1 = Cockpit 0.1, M2 = Cockpit 0.2, M3 = the 1.0 candidate); a statement whose milestone has not been reached is not a release gate yet.

---

## Configuration, Hosting & Auth

### CKP-001 Cockpit boots embedded or standalone from its host registry
- **Traces to:** `TR-UI-01`, `ADR-0005`, `ADR-0014`
- **Milestone:** M1
- **Given:** Case A: cockpit served by `cockpit-spring-boot-starter` at `/bpm/cockpit`, which generates `cockpit-hosts.json` with one host (`baseUrl` empty, `auth.mode: host-session`). Case B: standalone archive with `cockpit-hosts.json` listing host `loans` at `http://localhost:8080`, `auth.mode: oidc`.
- **When:** The user opens the cockpit and signs in.
- **Then:** The cockpit loads `cockpit-hosts.json`, calls `/api/bpm/meta` and `/api/bpm/me` on the host, lists rule sets through `/api/bpm/rules/v1/rule-sets`, and shows the host, the tenant and the user, without any backend of its own.
- **Example:** `Open /bpm/cockpit -> GET cockpit-hosts.json -> GET /api/bpm/meta, /me -> GET /api/bpm/rules/v1/rule-sets -> table rendered`.
- **Test ID:** `CKP_001_cockpit_boots_embedded_and_standalone_from_registry`

---

## Rule Governance & Audit (Cockpit 0.1)

### CKP-002 Rule set diff highlights added, modified, and removed rows side by side
- **Traces to:** `TR-UI-02`
- **Milestone:** M1
- **Given:** Rule set `card-fee` has `v1` (`PUBLISHED`) and `v2` (`PENDING_APPROVAL` with `R2` fee changed from `30000 VND` to `0 VND` and `R6` added).
- **When:** User opens Approval detail or Diff view comparing `v1` vs `v2`.
- **Then:** UI highlights `R2` (`changed`) and `R6` (`added`) before the approval action. Showing a simulation report attached to the request is M3 and is not part of this statement.
- **Example:** `Diff v1..v2 -> R2 marked modified (30000 -> 0 VND), R6 marked added`.
- **Test ID:** `CKP_002_version_diff_highlights_row_changes`

### CKP-003 Maker-checker disables Approve button for requester and surfaces API 403 if forced
- **Traces to:** `TR-SEC-04`, `TR-UI-02`
- **Milestone:** M1
- **Given:** Logged-in user `alice` is the `requestedBy` identity of `card-fee` v2 (`PENDING_APPROVAL`).
- **When:** `alice` views the Pending Approval screen; separately, `bob` (`rules:approve`) views the same screen.
- **Then:** For `alice`, the Approve button is disabled with tooltip *"Requester cannot approve own publication"*; for `bob`, Approve and Reject (requiring a comment) are enabled and transition status to `PUBLISHED` upon confirmation.
- **Example:** `User=alice -> Approve disabled; User=bob -> click Approve -> status PUBLISHED`.
- **Test ID:** `CKP_003_maker_checker_ui_blocks_requester_self_approval`

### CKP-004 Simulation screen runs sample inputs without mutating production logs
- **Traces to:** `TR-UI-02`
- **Milestone:** M1
- **Given:** `card-fee` v2 is in `DRAFT`.
- **When:** Analyst pastes 5 sample JSON/CSV rows and runs simulation.
- **Then:** UI displays matched rule IDs, output values, and changed-outcome counts (`2 / 5 changed`), while decision log count remains unchanged.
- **Example:** `5 samples -> shows 2 changed outcomes; Decision Log count unchanged`.
- **Test ID:** `CKP_004_simulation_displays_outcome_changes_safely`

### CKP-005 Decision log explorer renders masked PII and plain-language explanation
- **Traces to:** `TR-SEC-05`, `TR-OBS-05`
- **Milestone:** M1
- **Given:** Decision `01JD2X` logged with `nationalId` (`mask: true`) and matched rule `R2`.
- **When:** User with `rules:log:read` opens Decision `01JD2X` in the Decisions screen.
- **Then:** `nationalId` renders masked (`*** (hmac:9f4a...)`), never plaintext; matched row `R2` is highlighted in the rule table alongside the `/explain` text.
- **Example:** `Open decision 01JD2X -> nationalId masked, R2 highlighted`.
- **Test ID:** `CKP_005_decision_log_masks_pii_and_highlights_matched_row`

---

## Process Operations, `agentTask` & Grid Authoring (Cockpit 0.2)

### CKP-006 Interactive rule table editor validates expressions inline via `:validate`
- **Traces to:** `TR-DET-02`, `TR-UI-02`
- **Milestone:** M2
- **Given:** Analyst edits draft `card-fee` v3 in Cockpit 0.2 grid editor and types invalid condition `balance + 100 > 0`.
- **When:** User clicks **Validate** or **Request Publish**.
- **Then:** Server `:validate` error binds directly to row `R2` cell `balance` and blocks publish request submission until fixed.
- **Example:** `Invalid cell "balance + 100" -> inline error on row R2, Request Publish blocked`.
- **Test ID:** `CKP_006_grid_editor_shows_row_validation_errors`

### CKP-007 `agentTask` inspector displays bounded inputs, schema verdict, guardrail rules, and override diff
- **Traces to:** `TR-AGT-01`, `TR-AGT-02`
- **Milestone:** M2
- **Given:** Process instance `I9` ran `assess-documents` (`agentTask`) which triggered guardrail `R2` (`route-to-human`) creating approval task `T4`.
- **When:** Compliance reviewer opens `T4` / instance `I9` in Cockpit 0.2.
- **Then:** Screen shows masked agent inputs, JSON Schema check (`PASS`), structured agent output, triggered guardrail rule (`R2`), and lets the reviewer Approve or Override with required reason.
- **Example:** `Open T4 -> shows agent output + guardrail R2 badge + Approve/Override actions`.
- **Test ID:** `CKP_007_agent_task_inspector_shows_guardrail_and_override_controls`

### CKP-008 Incident queue surfaces root cause, retry count, and triggers `:retry` / `:resolve`
- **Traces to:** `TR-OPS-03`
- **Milestone:** M2
- **Given:** Instance `I12` has an `OPEN` incident (`AGENT_TIMEOUT`, `retryCount: 2`).
- **When:** Operator opens Incidents queue, inspects the cause/context, and clicks **Retry**.
- **Then:** Cockpit sends `POST /api/bpm/processes/v1/incidents/{id}:retry` with `Idempotency-Key` and `If-Match: "<lockVersion>"`, updating the incident list on `200 OK` (or showing conflict alert on `409`).
- **Example:** `Click Retry on incident -> POST :retry -> row transitions from OPEN`.
- **Test ID:** `CKP_008_incident_queue_retries_and_updates_status`

### CKP-011 Simulation screen replays logged decisions against a draft
- **Traces to:** `TR-UI-02`
- **Milestone:** M3 (replay simulation moved to M3 by PD-042)
- **Given:** `card-fee` v1 is published with 100 logged decisions and v2 is in `DRAFT`.
- **When:** Analyst clicks *"Replay last 100 decisions"* and runs simulation.
- **Then:** UI displays changed-outcome counts (`18 / 100 changed`) with the changed decisions listed, while decision log count remains unchanged.
- **Example:** `Replay 100 decisions -> shows 18 changed outcomes; Decision Log count unchanged`.
- **Test ID:** `CKP_011_replay_simulation_displays_outcome_changes_safely`

---

## Accessibility & Localization

### CKP-009 Keyboard navigation and WCAG 2.1 AA contrast pass in light and dark themes
- **Traces to:** `TR-UI-03`
- **Milestone:** M1 (Cockpit 0.1 screens); re-run at M2 over Cockpit 0.2 screens
- **Given:** Cockpit loaded in both light and dark CSS themes.
- **When:** Automated `axe-core` audit and keyboard-only navigation (`Tab` / `Enter` / `Esc`) run across Rule Sets, Approvals, and Incidents screens.
- **Then:** Zero critical/serious WCAG 2.1 AA contrast violations and all interactive actions are reachable with visible focus rings.
- **Example:** `Playwright + axe-core in light & dark modes -> 0 serious/critical violations`.
- **Test ID:** `CKP_009_wcag_aa_and_keyboard_navigation_pass`

### CKP-010 Complete English and Vietnamese UI localization without raw translation keys
- **Traces to:** `TR-UI-04`
- **Milestone:** M1 (Cockpit 0.1 screens); re-run at M2 over Cockpit 0.2 screens
- **Given:** Locale switched between `en` and `vi`.
- **When:** Render all Cockpit 0.1 and 0.2 views.
- **Then:** Every label, button, column header, and status badge renders translated text in both `en` and `vi` with zero untranslated i18n keys.
- **Example:** `Switch locale en -> vi -> all headings/statuses translated, 0 missing keys`.
- **Test ID:** `CKP_010_en_and_vi_i18n_coverage_complete`

---

## Host Registry, Sign-in and Version Skew (Cockpit 0.1, ADR-0014)

### CKP-012 An invalid host registry stops the cockpit before any host is called
- **Traces to:** `TR-UI-01`, `TR-SEC-09`
- **Milestone:** M1
- **Given:** Three `cockpit-hosts.json` files: (a) valid with hosts `loans` and `kyc`; (b) `hosts[1].baseUrl` is `http://kyc.bank.example`; (c) two hosts share `id: loans`.
- **When:** The cockpit starts with each file and network calls are recorded.
- **Then:** (a) lists both hosts. (b) shows a configuration error naming `/hosts/1/baseUrl` and the https rule. (c) shows an error naming the duplicate id. In (b) and (c) no request leaves the browser except the one for `cockpit-hosts.json`.
- **Example:** `baseUrl http://kyc.bank.example -> "Configuration error at /hosts/1/baseUrl", 0 host calls`.
- **Test ID:** `CKP_012_invalid_host_registry_blocks_all_host_calls`

### CKP-013 Sign-in uses PKCE once and requests one token per host, kept in memory
- **Traces to:** `TR-SEC-12`, `TR-UI-01`
- **Milestone:** M1
- **Given:** Registry with `tokenMode: per-host`, hosts `loans` (scope `loans-bpm`) and `kyc` (scope `kyc-bpm`); a test identity provider.
- **When:** The user signs in and opens both hosts; then reloads the page.
- **Then:** One authorization request with `code_challenge_method=S256`; one refresh grant per host scope; requests to `loans` carry the `loans-bpm` token and requests to `kyc` the `kyc-bpm` token; `localStorage`, `sessionStorage` and cookies of the cockpit origin hold no token. After reload the tokens are gone and sign-in runs again through the identity provider's session.
- **Example:** `loans request -> Authorization: Bearer <aud loans-bpm>; storage scan -> 0 tokens`.
- **Test ID:** `CKP_013_pkce_sign_in_with_per_host_tokens_in_memory`

### CKP-014 Screens follow `/meta` features and `/me` permissions; old hosts are marked unsupported
- **Traces to:** `TR-UI-08`
- **Milestone:** M1
- **Given:** Cockpit `0.3.0`. Host `loans` reports engine `0.3.1` with feature `rules.simulation`; host `cards` reports `0.2.4` without `rules.simulation`; host `kyc` reports `0.1.9`. User `lan` has `rules:read` but not `rules:approve` on `loans`.
- **When:** `lan` opens each host.
- **Then:** `loans` shows Rule sets and Simulation and hides Approvals; `cards` shows Rule sets without Simulation; `kyc` is `UNSUPPORTED_VERSION`, showing "engine 0.1.9, supported 0.2 and 0.3" and no screens.
- **Example:** `kyc 0.1.9 with cockpit 0.3.0 -> UNSUPPORTED_VERSION, screens off`.
- **Test ID:** `CKP_014_meta_features_and_version_skew_gate_screens`

### CKP-015 In Cockpit 0.1 the user switches between hosts, one at a time
- **Traces to:** `TR-UI-01`
- **Milestone:** M1
- **Given:** Registry with `loans` and `kyc`, both `READY`; `loans` has rule set `card-fee`, `kyc` has `kyc-risk`.
- **When:** The user selects `loans`, opens Rule sets, then switches to `kyc`.
- **Then:** The list shows only `card-fee`, then only `kyc-risk`; the URL changes from `/hosts/loans/rules` to `/hosts/kyc/rules`; no cached entry of `loans` is shown under `kyc`.
- **Example:** `switch loans -> kyc -> list replaced, query keys differ by hostId`.
- **Test ID:** `CKP_015_host_switch_shows_one_host_at_a_time`

---

## Diagrams (Cockpit 0.2, ADR-0013)

### CKP-016 A definition without stored layout is laid out automatically
- **Traces to:** `TR-UI-07`, `TR-DEF-02`
- **Milestone:** M2
- **Given:** `kyc-onboarding` v2 with 14 steps and no layout; `loan-origination` v5 with 200 steps and a stored layout that covers 190 of them.
- **When:** The user opens each definition diagram on the reference laptop profile.
- **Then:** `kyc-onboarding` renders left to right with no overlapping nodes; `loan-origination` keeps the 190 stored positions and places the other 10 automatically, and renders in under 1 s. Every node shows its step type icon and name.
- **Example:** `200 nodes, 10 unplaced -> stored 190 fixed, 10 placed, render 0.6 s`.
- **Test ID:** `CKP_016_definition_diagram_auto_layout_and_render_budget`

### CKP-017 The instance diagram shows tokens and per-node activity
- **Traces to:** `TR-UI-07`, `TR-UI-03`
- **Milestone:** M2
- **Given:** Instance `I7` of `kyc-onboarding` v2 completed `verify-docs` (2 attempts, 3 min 10 s) and waits at `approve` assigned to role `credit-officer`; `assess-documents` has an open incident.
- **When:** The operator opens `I7` and selects `verify-docs`.
- **Then:** `approve` shows a token badge with the text "Waiting for a person"; `assess-documents` shows an incident badge with its code; `verify-docs` shows "Completed, 2 attempts, 3 min 10 s, by svc-kyc"; status is given in text and icon, not colour alone. The timeline tab shows the same entries in time order.
- **Example:** `select verify-docs -> Completed, 2 attempts, 3 min 10 s`.
- **Test ID:** `CKP_017_instance_diagram_shows_tokens_and_activities`

### CKP-018 Live counts appear on each node of a definition diagram
- **Traces to:** `TR-PERF-09`
- **Milestone:** M2 (cut candidate)
- **Given:** `kyc-onboarding` v2 with 40 tokens waiting at `approve`, 7 at `assess-documents` and 2 open incidents there.
- **When:** The operator opens the definition diagram and waits 15 s.
- **Then:** `approve` shows "40 waiting"; `assess-documents` shows "7 running, 2 incidents"; the legend shows "as of" with the response's `asOf`; after 15 s the cockpit refreshes once with `If-None-Match`.
- **Example:** `approve 40 waiting; assess-documents 7 running, 2 incidents; asOf 10:02:15`.
- **Test ID:** `CKP_018_live_counts_overlay_on_definition_diagram`

---

## Federated Inbox, Incidents and Forms (Cockpit 0.2)

### CKP-019 The merged inbox is in exact due-date order and says when it is incomplete
- **Traces to:** `TR-UI-01`, `TR-PERF-07`
- **Milestone:** M2
- **Given:** Hosts `loans` (120 tasks due from day 1 to day 120), `kyc` (12 tasks due on days 5 to 16) and `cards` (stub that times out after 6 s); page size 50; `hostTimeoutSeconds` 5.
- **When:** The operator opens the inbox, then clicks Load more; 30 s later the refresh runs with no change on the hosts.
- **Then:** The first view shows tasks up to day 50 (the `loans` frontier) including all 12 `kyc` tasks, in due-date order, and a banner "Cards unreachable, list incomplete" after 5 s. Load more requests only `loans`' next page. The refresh sends `If-None-Match`, gets `304` from both hosts and the list is unchanged.
- **Example:** `first view = 50 loans + 12 kyc = 62 rows, exact order; Load more -> 1 request to loans`.
- **Test ID:** `CKP_019_merged_inbox_exact_order_partial_banner_and_304`

### CKP-020 The merged incident list spans hosts and acts on the right host
- **Traces to:** `TR-OPS-03`
- **Milestone:** M2
- **Given:** `loans` has incident `N3` (`AGENT_TIMEOUT`, created 10:00); `kyc` has `N9` (`SERVICE_ERROR`, created 10:05).
- **When:** The operator opens Incidents and retries `N3`.
- **Then:** The list shows `N9` then `N3` with host labels; the retry goes to `loans` with `Idempotency-Key` and `If-Match`; on `200` the row shows "Retry scheduled", and the next refresh shows the result.
- **Example:** `retry N3 -> POST https://loans.../incidents/N3:retry only`.
- **Test ID:** `CKP_020_merged_incident_list_routes_actions_to_host`

### CKP-021 A task is completed through its declared form, or by its link
- **Traces to:** `TR-UI-02`, `TR-UI-03`, `TR-UI-04`
- **Milestone:** M2
- **Given:** Task `T5` with a form requiring `decision` and `reason` when `decision = REJECT`, labels in `en` and `vi`; task `T6` with `link: /loans/review/{taskId}`.
- **When:** The operator submits `T5` with `decision: REJECT` and no reason, then with a reason; then opens `T6`.
- **Then:** The first submit shows the field error under `reason` (from the client check, and the API's `422 FORM_VALIDATION_FAILED` if the client check is bypassed); the second completes with `200` and the task leaves the inbox; `T6` opens `/loans/review/T6` with `rel="noopener noreferrer"` and no form.
- **Example:** `REJECT without reason -> error at /reason; with reason -> 200, removed from inbox`.
- **Test ID:** `CKP_021_task_form_completion_and_link`

### CKP-022 The tenant switcher offers only allowed tenants and shows a refusal
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** `/me` returns `tenant.current: bank-a`, `tenant.allowed: [bank-a, bank-a-fin]`; a test proxy rewrites one request to `X-Tenant-Id: bank-b`.
- **When:** The operator switches to `bank-a-fin`, then the rewritten request is sent.
- **Then:** Every open list reloads with `X-Tenant-Id: bank-a-fin` and shows only that tenant's data; the rewritten request gets `403 TENANT_NOT_ALLOWED`; the cockpit returns to `bank-a` and shows "Tenant bank-b is not allowed for your account" with the `correlationId`. No screen shows rows from two tenants.
- **Example:** `switch to bank-a-fin -> lists reload; forged bank-b -> 403 TENANT_NOT_ALLOWED`.
- **Test ID:** `CKP_022_tenant_switcher_allowed_tenants_only`

### CKP-023 When a host turns off cockpit drafts, authoring is hidden and review stays
- **Traces to:** `TR-DEF-04`
- **Milestone:** M2
- **Given:** `/meta` of `loans` reports `processes.authoring.cockpit-drafts: false`; `kyc` reports `true`; user `carol` holds `processes:author` on both.
- **When:** `carol` opens `kyc-onboarding` on each host.
- **Then:** On `loans` there is no New draft or Edit action and the notice "Changes to this environment arrive through your release pipeline" is shown; version, `definitionHash`, diff and approval remain; on `kyc` New draft is offered.
- **Example:** `loans cockpit-drafts false -> New draft hidden, hash shown`.
- **Test ID:** `CKP_023_cockpit_drafts_off_hides_authoring`

---

## Governed Process Change, Heatmap and Migration (M3)

### CKP-024 Process approval shows the graph diff and blocks the requester
- **Traces to:** `TR-SEC-04`, `TR-UI-02`
- **Milestone:** M3
- **Given:** `kyc-onboarding` v3 `PENDING_APPROVAL`, requested by `alice`, adding `fraud-check` and changing `approve.assignee.role`.
- **When:** `alice` and then `bob` (`processes:approve`) open the approval.
- **Then:** Both see `fraud-check` marked "added" and `approve` marked "changed: assignee role ops to credit-officer" on the diagram and in the list; for `alice` Approve is disabled with "Requester cannot approve own change"; `bob` approves with a comment and the status becomes `ACTIVE` with its effective date.
- **Example:** `alice -> Approve disabled; bob -> Approve -> ACTIVE from 2027-06-01`.
- **Test ID:** `CKP_024_process_approval_graph_diff_blocks_requester`

### CKP-025 The modeller offers only catalogue steps and saves a draft
- **Traces to:** `TR-SEC-01`, `TR-UI-02`
- **Milestone:** M3 (cut candidate, fallback 1.1)
- **Given:** Step catalogue with handler `kyc.verifyDocuments` and agent adapter `doc-assessor`; user `carol` with `processes:author`.
- **When:** `carol` opens the palette, adds `doc-assessor` as an `agentTask`, leaves `guardrails.decision` empty and saves; then fills it and saves.
- **Then:** The palette lists exactly the catalogue entries and no free-text handler; the first save shows the engine's validation problem on the new node and the definition stays unchanged; the second save creates or updates a `DRAFT`, never an active version.
- **Example:** `palette -> 1 handler, 1 agent; save without guardrail -> error on node`.
- **Test ID:** `CKP_025_modeller_offers_catalogue_steps_and_saves_draft`

### CKP-026 The heatmap shows p50 and p95 per node for a chosen window
- **Traces to:** `TR-PERF-10`
- **Milestone:** M3 (cut candidate)
- **Given:** Rollup for `kyc-onboarding` v2 over 7 days: `approve` p50 2 h, p95 9 h; `verify-docs` p50 40 s, p95 3 min.
- **When:** The operator selects "Last 7 days"; then tries "Last 60 days".
- **Then:** Each node shows its p50 and p95 in text, shaded by p95; the legend states the rollup lag; 60 days is not offered beyond the 31-day limit and the API's `400 QUERY_INVALID` is never reached from the UI.
- **Example:** `approve p50 2 h, p95 9 h`.
- **Test ID:** `CKP_026_heatmap_window_stats_per_node`

### CKP-027 A migration plan is shown as two diagrams with mapped steps
- **Traces to:** `TR-MIG-03`
- **Milestone:** M3
- **Given:** Plan from `kyc-onboarding` v2 to v3, where `legacy-review` is mapped to `manual-review` and 50 instances wait at `legacy-review`.
- **When:** The operator opens the plan.
- **Then:** v2 and v3 are side by side; `legacy-review` and `manual-review` are linked with "50 instances"; unmapped steps with waiting instances are listed; Execute is enabled only after a successful dry run.
- **Example:** `legacy-review -> manual-review, 50 instances`.
- **Test ID:** `CKP_027_migration_plan_side_by_side_diagrams`

---

## Load and Bundle

### CKP-028 The initial bundle stays within its budget and diagrams load on demand
- **Traces to:** `TR-UI-06`
- **Milestone:** M2
- **Given:** The production build of the cockpit.
- **When:** The CI bundle check measures gzipped sizes and the first screen is loaded at 10 Mbit/s.
- **Then:** The initial chunk is at most 500 KB gzipped; React Flow and dagre are in a separate chunk not requested until a diagram opens; the first screen is usable within 2 s.
- **Example:** `initial 270 KB gz; diagram chunk requested only on first diagram`.
- **Test ID:** `CKP_028_bundle_budget_and_lazy_diagram_chunk`

---

## Standards Foundations (ADR-0017)

### CKP-029 Diagram nodes are keyed by BPMN element type; unknown types render as generic nodes
- **Traces to:** `TR-UI-07`, `TR-UI-08`
- **Milestone:** M2
- **Given:** Host `loans` serves a definition with steps `start`, `userTask`, `agentTask` and `end`; host `cards` (a newer engine) serves a definition containing a node of BPMN type `boundaryEvent:timer` and reports `standards: { bpmnStage: S1 }` in `/api/bpm/meta`.
- **When:** The operator opens both definition diagrams in the same cockpit build.
- **Then:** `loans` renders node types `startEvent`, `userTask`, `serviceTask:agentTask` and `endEvent`; on `cards` the boundary node renders as a labelled generic node showing `boundaryEvent:timer` with no error and the rest of the diagram intact; the stage reported by each host is shown in the host details.
- **Example:** `unknown boundaryEvent:timer -> generic node with its type name, diagram rendered`.
- **Test ID:** `CKP_029_diagram_nodes_by_bpmn_type_unknown_rendered_generic`


---

## Rule Set Browser, Import and Export (Cockpit 0.1)

### CKP-030 An author lists rule sets, reads a version as a table, and imports or exports it
- **Traces to:** `TR-UI-02`, `TR-IDEM-01`
- **Milestone:** M1
- **Given:** Host `loans` with rule sets `card-fee` (v1 `PUBLISHED`, effective from `2026-11-01`; v2 `DRAFT`) and `kyc-risk` (v1 `PUBLISHED`). User `lan` holds `rules:read` and `rules:author`; user `dan` holds `rules:read` only. Files `card-fee-v3.yaml` (valid) and `card-fee-bad.yaml` (row `R4`, column `balance`, `50000000` without a currency). Viewports 1440x900 and 1440x1200.
- **When:** `lan` opens Rule sets, opens `card-fee` v1, exports it as JSON, then imports `card-fee-v3.yaml` and then `card-fee-bad.yaml` from the `card-fee` page. `dan` opens the same pages.
- **Then:** The list shows two rows with name, effective version, status and effective-from date, read from `GET /api/bpm/rules/v1/rule-sets`. The version view shows the rule table (row ids, conditions, outputs) and the hit policy; no YAML or JSON document is shown unless the user opens the source on purpose. Export calls `GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1/export?format=json` and downloads `card-fee-v1.json` whose bytes equal the response (`RLE-032`). The first import sends `POST /api/bpm/rules/v1/rule-sets/card-fee/versions` with an `Idempotency-Key` and, only after the `201`, shows "Draft version 3 created" and opens v3; a double click sends one key and creates one version. The bad file's `422 VALIDATION_FAILED` is shown as "Import refused. Nothing was saved." with "card-fee-bad.yaml, row R4, column Balance" and the reason in the user's language, and no version is listed as created. For `dan` the Import action is not shown and Export is. At 1440x900 the list and the Import and Export actions are above the fold.
- **Example:** `import card-fee-v3.yaml -> one POST with Idempotency-Key -> 201 -> "Draft version 3 created"; card-fee-bad.yaml -> "Import refused. Nothing was saved." and "card-fee-bad.yaml, row R4, column Balance", no draft`.
- **Test ID:** `CKP_030_rule_set_browser_lists_reads_imports_and_exports`

---

## Landing, Minimal Editing, Process Views and Diagram Accessibility (PD-013, PD-024, PD-048, PD-075, PD-078)

### CKP-031 The cockpit opens on Home, whose content follows the user's role, unless the host fixes the landing area
- **Traces to:** `TR-UI-08`, `TR-EXT-08`, PD-092
- **Milestone:** M1
- **Given:** Host `loans` runs only the Rule Engine. `/api/bpm/meta` reports no `cockpit.landing`, so the default `home` applies. Users: `bob` holds `rules:approve`; `lan` holds `rules:author`; `dan` holds `rules:log:read` only; `eve` holds `rules:read` only; `zoe` holds no `rules:*` permission. Second case: the host sets `cockpit.landing: approvals`. Third case: `bob` follows a link to `card-fee` v2. Fourth case: the host sets the retired value `by-role`.
- **When:** Each user signs in and the cockpit opens.
- **Then:**
  - Every user lands on Home. `bob`'s Home shows first the requests waiting for his approval, his own requests marked "Your request" with no Approve. `lan`'s Home shows first her own drafts and requests. `eve`'s Home shows the recently changed rule sets. `dan` cannot read rule sets, so his Home shows one line saying there is nothing for him here yet; Decisions is a Preview in 0.1.0 and is never the landing. `zoe`'s Home shows one line saying her permissions show nothing on this service, never a blank page, and the menu offers only Home.
  - Processes appears in the menu only when a Process Engine host is registered; here it does not.
  - In the second case every user who may open Approvals lands there; a user who may not lands on Home.
  - In the third case `bob` opens `card-fee` v2 directly, whatever the setting.
  - In the fourth case the cockpit treats the unknown value as `home`, and the host's start-up log names the retired value (PD-092).
- **Example:** `default -> Home for everyone, approver sees waiting requests first, user without rule permissions sees one line; landing: approvals -> Approvals; deep link wins; by-role -> home`.
- **Test ID:** `CKP_031_cockpit_opens_on_home_with_role_content_or_host_landing`

### CKP-032 An author edits a draft in the cockpit with cell text and row actions, and errors show on their cells
- **Traces to:** `TR-UI-02`, `TR-IDEM-01`
- **Milestone:** M1
- **Given:** Host `loans` with `card-fee` v1 `PUBLISHED` (rows `R1` to `R3`) and `authoring.cockpit-drafts` on; `lan` holds `rules:author`. Second case: the host turns `authoring.cockpit-drafts` off.
- **When:** `lan` creates a draft from v1, types `GOLD, PLATINUM` in `R1`'s `tier` cell, copies `R2` as a new row `R4` and moves it above `R3`, types `>= 50000000` without a currency in `R4`'s `balance` cell, and saves; then fixes the cell to `>= 50000000 VND` and saves again.
- **Then:** The first save sends the edited table through the existing draft endpoint (`PUT .../versions/2` with `If-Match`) and shows the engine's `422 VALIDATION_FAILED` on `R4`'s `balance` cell in plain words, with the other edits kept; the second save returns `200` and the draft shows rows `R1`, `R2`, `R4`, `R3` in that order. Inputs, outputs and the hit policy cannot be changed from this view. In the second case the edit actions are not shown and a direct `PUT` returns `403 DRAFT_AUTHORING_DISABLED`; import from a file still works.
- **Example:** `R4 balance ">= 50000000" -> error on R4/balance; ">= 50000000 VND" -> saved, order R1, R2, R4, R3`.
- **Test ID:** `CKP_032_minimal_draft_editing_shows_errors_on_cells`

### CKP-033 Compensation runs appear in the instance timeline
- **Traces to:** `TR-COMP-01`, `TR-OBS-01`
- **Milestone:** M2
- **Given:** Instance `I7` of `loan-booking` where `notify-partner` failed inside the `saga` `disburse` and the handlers `closeAccount` and `releaseFunds` ran, the second after one retry (`PRE-044`).
- **When:** An operator with `processes:read` opens `I7`.
- **Then:** The timeline lists, in time order and in text, the failure of `notify-partner`, then `closeAccount` and `releaseFunds` as compensation entries with their attempt ids, the retry of `releaseFunds`, and the scope `disburse` marked compensated; the diagram marks the compensated steps with an icon and a text label, not colour alone.
- **Example:** `I7 timeline -> notify-partner FAILED, closeAccount COMPENSATED, releaseFunds retry 1 then COMPENSATED`.
- **Test ID:** `CKP_033_compensation_runs_in_instance_timeline`

### CKP-034 SLA breaches are a filter of the task queue, measured against each task's own deadline
- **Traces to:** `TR-CAL-01`, `TR-PERF-07`
- **Milestone:** M2
- **Given:** Tasks `T1` (`sla: PT4H` on calendar `vn-banking-hours`, due 10:00), `T2` (`sla: P2D`, due tomorrow) and `T3` (no SLA) on host `loans`; the clock reads 11:00.
- **When:** An operator turns on the "SLA breached" filter of the task queue.
- **Then:** The cockpit sends the task query with `slaBreached=true` and shows only `T1`, with "overdue by 1 h" in text; `T3` never appears, because a task without a declared deadline cannot breach; no deadline is computed in the cockpit or from a product constant.
- **Example:** `filter SLA breached at 11:00 -> T1 only, overdue by 1 h`.
- **Test ID:** `CKP_034_sla_breach_filter_uses_task_deadlines`

### CKP-035 Every diagram has a keyboard-usable list of its steps and states in text, not colour alone
- **Traces to:** `TR-UI-03`
- **Milestone:** M2
- **Given:** The instance diagram of `I7` with tokens on two steps, one open incident and live counts per node.
- **When:** A keyboard-only user tabs to the diagram and opens its step list; axe-core runs on the view in light and dark themes.
- **Then:** The step list names every step in flow order with its type, token state, incident and count as text, and each entry moves focus to its node; token state, incidents and counts are also shown as text or icons with labels on the diagram (WCAG 1.4.1); axe-core reports no violation.
- **Example:** `step list -> "score-applicant, agent task, waiting, 1 incident, 12 instances"`.
- **Test ID:** `CKP_035_diagram_step_list_and_text_states`

### CKP-036 The modeller canvas states its accessibility exemption and names the equal path
- **Traces to:** `TR-UI-03`, `TR-DEF-04`
- **Milestone:** M3
- **Given:** The modeller canvas open on a draft definition.
- **When:** A user opens it with a screen reader running, then follows the named path.
- **Then:** The canvas announces and shows a notice that drawing is not keyboard accessible and that the definition can be edited as JSON or YAML and imported as a draft; the link opens the import path, which creates a draft that passes the same approval.
- **Example:** `canvas notice -> "Edit as JSON or YAML and import as a draft" -> import creates a draft`.
- **Test ID:** `CKP_036_canvas_exemption_names_import_path`

### CKP-037 The approval screen names undeclared personal-data inputs, needs their acceptance before Approve, and states masking changes
- **Traces to:** `TR-UI-02`, `TR-UI-03`, `TR-UI-04`, PD-099 (mitigations 1 and 3), PD-104, `RLE-049`, `RLE-051`
- **Milestone:** M1
- **Given:** Rule set `customer-screening` v1 `PUBLISHED` with inputs `nationalId` and `phoneNumber` (`mask: true`) and `segment`; v2 `PENDING_APPROVAL`, requested by `hoa`, where `nationalId` declares no `mask` and a new input `email` declares none. The engine answers v2 with two `UNMASKED_PERSONAL_DATA` warnings and its diff against v1 with `masking`; the host's `lifecycle.acknowledge-warnings` is the default. `minh` holds `rules:approve`. Second case: `card-fee` v2 with no warning. Third case: the engine answers the approval `409 WARNINGS_NOT_ACKNOWLEDGED`. Fourth case: a host whose `lifecycle.acknowledge-warnings` is empty, or a tenant whose `decision-log.mask-default` is on.
- **When:** `minh` opens v2 at 1440x900, in English and in Vietnamese, light and dark, ticks the acceptance and approves.
- **Then:** The docked decision lists one line per input, "National ID: no masking rule", with no category or error code, a closing sentence that follows the number of inputs, and one checkbox naming the inputs: "I accept that these inputs are logged without masking: National ID, Email". Approve is unavailable until the box is ticked and then sends `acknowledgedWarnings` as the exact pairs shown, `[{ code: UNMASKED_PERSONAL_DATA, input: nationalId }, { code: UNMASKED_PERSONAL_DATA, input: email }]` (`RLE-053`). The warning, the box and Approve are visible without scrolling, each control at least 44 px. Above the side-by-side table the change sentences read "National ID: mask removed (masked → not masked by default)" and "Condition column added: Email, not masked by default"; the summary line carries "Mask removed on 1 column"; the proposed side's National ID and Email headers carry an amber marker whose tooltip is the sentence; Phone number and Segment carry none. In the second case there is no warning, no box and no marker, and Approve sends no acknowledgement. In the third case the warning shows the set the refusal returns, the box is cleared and names that set, "Approval needs this acceptance" shows, and nothing is sent again until the approver ticks and approves. In the fourth case the warning shows without the box. A draft with the same warnings shows them to its author, without the box. axe-core finds no serious violation.
- **Example:** `nationalId MASKED -> NOT_MASKED_BY_DEFAULT -> "National ID: mask removed (masked → not masked by default)"; Approve disabled -> tick -> approve {acknowledgedWarnings: [{UNMASKED_PERSONAL_DATA, nationalId}, {UNMASKED_PERSONAL_DATA, email}]}`.
- **Test ID:** `CKP_037_the_warning_the_acceptance_and_approve_are_all_on_screen_at_1440x900`
