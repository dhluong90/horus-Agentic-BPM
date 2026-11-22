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
- **Polish (#280):** the summary line leaves out zero counts; the version row stays in view while a long diff scrolls; a cell whose value differs from what the approver had on screen says what it was, once; a look-alike character is named by its script before its code point; after `409 VERSION_CONFLICT` on Approve or Reject the version is read again, reads as changed while open, and the decision is never sent again on its own.
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
- **When:** Analyst types or pastes 5 sample rows from a spreadsheet or CSV (an optional name, then one column per input; no JSON is accepted, under the no-raw-JSON rule) and runs simulation.
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
- **Traces to:** `TR-DET-02`, `TR-UI-02`, PD-113, `RLE-090`
- **Milestone:** M2
- **Given:** `card-fee` v1 `PUBLISHED` and v2 `DRAFT` written by `lan` (`lockVersion 0`); `lan` holds `rules:author`, `minh` holds `rules:approve`; `authoring.cockpit-drafts` on.
- **When:** `lan` opens the grid of v2 (Edit draft, route `#/rules/card-fee/2/edit`), empties the amount of `R2`'s Balance condition and presses Enter; then fixes it and saves. Separately: `R3`'s fee is changed while someone else saves v2 (`lockVersion` 1), and `lan` saves; `lan` opens the grid of v1 or of a draft another author wrote; `minh` opens v2 after it is requested.
- **Then:**
  - A committed cell (Enter, leaving the cell, a row action) sends the table to `POST .../versions/2:validate`, with no `Idempotency-Key` and no `If-Match`. The problem shows on row `R2`, cell Balance, in the screen's language. Because the amount field is empty, the cell first says what to type, with the cell's saved amount as the example (`Enter an amount, for example 50,000,000 VND.` / `Nhập số tiền, ví dụ 50.000.000 VND.`), and the engine's catalogue message follows as the detail (`The cell is not a valid expression.` / `Ô này không phải là biểu thức hợp lệ.`); any other problem shows the engine's message alone. No code, expression, JSON or YAML is shown. An answer for an older table is never shown on a newer one (the check is keyed by the table text). A problem on a row or column the table no longer shows moves to its row or to the table.
  - A valid table of more than 500 rows (a draft may have up to 5,000) is still `200` with `valid: true` from `:validate`; the engine leaves only its readings out and says so with `readingsTruncated: true`. The grid never reads those readings, so it shows no error and no note, and Changes in this draft refreshes as for any other table.
  - Changes in this draft lists only committed cells the engine accepted: a cell being typed, or one the engine refused, never shows there (no `≥ 50,000,000 VND → ≥`). Column names use the rule set's own `label` in the screen's language when it has one, and otherwise the readable name, in the grid, this card and the review table.
  - The save dock sits at the bottom of the window, aligned to the cards. With nothing changed it says so once, beside the disabled Save draft.
  - Save draft stays disabled while a checked cell has a problem, with one line saying why (`Fix the marked cells to save.`), and while nothing changed (`Nothing to save yet.`). Once fixed, Save draft sends `PUT .../versions/2` with `If-Match: "0"` and an `Idempotency-Key`, and the draft reads as a table again with Request publish as a separate step.
  - On `409 VERSION_CONFLICT` the edits stay on screen and the grid does not move: the save dock says someone else saved the draft and names the cells they changed (`They changed R5 Fee.`), Save draft is described by that line, and the cockpit never retries with the newer lock version on its own. Load the latest version replaces the edits with the stored draft and its new lock version.
  - Arrow keys move between cells (Up and Down by column, Left and Right by control, leaving text only at its start or end); Enter commits a cell; Escape puts back the value the cell had when it took the focus.
  - An approved version is never edited: its grid route shows the table read only with `Approved versions never change. Start a new draft.`; New draft first creates a draft copy (`POST .../versions?copyOf=1`) and opens the grid on it. A draft another author wrote shows Edit draft disabled with `Only hoa can change this draft.` A reviewer of a pending version sees it read only, with Edit draft disabled and `Waiting for approval, so it cannot change.` beside it. After a save the confirmation stays until it is closed and the focus moves to the title.
- **Example:** `R2 Balance "≥" with no amount -> Enter -> :validate -> "Enter an amount, for example 50,000,000 VND." with "The cell is not a valid expression." on R2/Balance, Save draft disabled; "≥ 40,000,000 VND" -> PUT v2 If-Match "0" -> saved`.
- **Test ID:** `CKP_006_grid_editor_shows_row_validation_errors`

### CKP-007 `agentTask` inspector displays bounded inputs, schema verdict, guardrail rules, and override diff
- **Traces to:** `TR-AGT-01`, `TR-AGT-02`
- **Milestone:** M2
- **Given:** Process instance `I9` ran `assess-documents` (`agentTask`) which triggered guardrail `R2` (`route-to-human`) creating approval task `T4`.
- **When:** Compliance reviewer opens `T4` / instance `I9` in Cockpit 0.2.
- **Then:** Screen shows masked agent inputs, JSON Schema check (`PASS`), structured agent output, triggered guardrail rule (`R2`), and lets the reviewer Approve or Override with required reason. Field names read as words (`declaredMonthlyIncome` reads `Declared monthly income`); a masked value is one `Masked` chip whose keyed fingerprint is never shown, only copied by a Copy reference action; a long reference shows its last characters (`…R2X7`) with the whole value in its tooltip and a copy action. The starter of the case and the reporter of the answer each see one line saying why they cannot decide (`You started this case, so someone else decides.`) beside the disabled action. An answer holding a masked value opens on Override, with Approve disabled and one line saying why (`Part of the answer is masked, so only an override is possible.`).
- **Example:** `Open T4 -> shows agent output + guardrail R2 badge + Approve/Override actions`.
- **Test ID:** `CKP_007_agent_task_inspector_shows_guardrail_and_override_controls`, `CKP_007_the_starter_of_the_case_cannot_decide_its_review`, `CKP_007_the_reporter_of_the_refused_answer_cannot_decide_its_review`, `CKP_007_a_masked_answer_cannot_be_approved_as_is_and_the_override_needs_the_masked_value`, `CKP_007_the_starter_is_told_in_one_line_why_they_cannot_decide`, `CKP_007_the_reporter_is_told_in_one_line_why_they_cannot_decide`, `CKP_007_a_masked_answer_opens_on_the_override_and_says_why_in_one_line`, `CKP_007_field_names_read_as_words_and_references_are_short`

### CKP-008 Incident queue surfaces root cause, retry count, and triggers `:retry` / `:resolve`
- **Traces to:** `TR-OPS-03`, PD-126
- **Milestone:** M2
- **Given:** Host `loans` runs the Process Engine with four `OPEN` incidents, among them `assess-documents` of case `APP-1045` (`AGENT_TIMEOUT`, `retryCount: 2`) and `book-loan` of a booked case (`COMPENSATION_MANUAL_REQUIRED`), and one `RESOLUTION_PENDING`. Tuan holds `processes:read` and `processes:incident:manage`; Hoa holds `processes:read` only.
- **When:** Tuan opens Incidents, selects `assess-documents` and chooses Retry with the reason "Agent service back after maintenance"; then selects `book-loan`; Hoa opens Incidents.
- **Then:** Incidents shows in the menu where a Process Engine host is registered and `/me` lists `processes:read`. The Open tab sends `GET /incidents?status=OPEN&limit=50` and lists the incidents newest first with the step by its name in the process (its id in words until the diagram is read), the cause in words ("The AI agent did not answer in time"), the retry count and when it was raised; the error code and the engine's cause summary are behind Details, never in the list. The selected incident shows its case (a link to the case), its process by name, the cause and the retry count, and the actions of its `allowedActions`. Retry asks for a reason (unavailable, with "Enter a reason to continue." beside it, until one is typed) and sends `POST /incidents/{id}:retry` with `{ reason }`, `If-Match: "<lockVersion>"` and an `Idempotency-Key`; only after the `202` does the row read "Retrying" and the toast say "Retry scheduled for Assess documents. The result shows on the next refresh."; the next read no longer lists the incident once the step was accepted. A `409 VERSION_CONFLICT` reads "Someone changed this incident. It has been read again; check it, then try again." inside the dialog, with the code behind Details. For `book-loan` Retry is unavailable with "Nothing to run again: undo the step by hand, then resolve." beside it and Resolve is offered. Hoa sees the list and the incident and one line saying fixing them needs the incident permission; no action is shown. At 1440x900 the list, the selected incident and its primary action are on screen without scrolling; checked again at 1440x1200, in English and Vietnamese, light and dark, by keyboard alone, and axe-core reports no serious violation.
- **Example:** `Retry assess-documents -> POST :retry {reason} 202 -> row Retrying -> next read: Open (3)`.
- **Test ID:** `CKP_008_incident_queue_retries_and_updates_status`, `CKP_008_a_stale_incident_reads_as_a_plain_sentence_and_is_read_again`, `CKP_008_a_manual_undo_offers_resolve_only_and_says_why_retry_is_unavailable`, `CKP_008_a_reader_sees_the_queue_and_no_action`, `CKP_008_demo_an_incident_retried_from_the_queue_leaves_it_once_the_step_succeeds`, `CKP_008_retry_is_reached_and_sent_by_keyboard_alone_with_a_visible_focus`

### CKP-080 Resolving an incident is a proposal a second person approves
- **Traces to:** `TR-OPS-03`, `TR-SEC-11`, PD-126
- **Milestone:** M2
- **Given:** The incidents of `CKP-008`; `credit-decision` of case `APP-1042` is `OPEN` and its output schema requires `approvedLimit`; `verify-docs` of case `KYC-2207` is `RESOLUTION_PENDING` with a result Linh proposed. Tuan and Linh hold `processes:incident:manage`; the host's `/meta` setting `incidents.resolve-approval` is `four-eyes` (the default). Second case: a `dev` host with `single`.
- **When:** Tuan resolves `credit-decision` with the field `limit` as an amount of 30,000,000 VND and a reason, then renames the field `approvedLimit`; Tuan opens `verify-docs` and approves it; Linh opens `verify-docs`; in the second case Tuan resolves `book-loan`.
- **Then:** Resolve opens a dialog that says a colleague approves the result before the case moves on (or that it applies at once under `single`). The result is entered as fields in words, never raw JSON: a name, a kind (text, number, amount, yes or no) and a value; an amount shows separators as it is typed (`30,000,000`, `30.000.000` in Vietnamese) with its currency inside the field, and is sent as `{ amount, currency }` in integer minor units; a reason is required. The cockpit checks only the format; the engine's `422 OUTPUT_INVALID` names the failing paths, read as "The result does not match what the step expects: check Approved limit." and marked on the field, and nothing moves. After the `200` the incident is in Waiting for approval with "Proposed by Trần Minh Tuấn", and the proposal lists each field in words with money formatted. Approve result is unavailable to the proposer with "You proposed this result, so a colleague approves it." beside it, and Reject is not offered to them. Tuan approves Linh's proposal with `POST /incidents/{id}:approve-resolution` (`If-Match`, `Idempotency-Key`, optional comment); after the `200` the list is read again and says no result is waiting. Reject needs a reason and opens the incident again. In the second case the engine answers `RESOLVED` and the toast says the case moves on.
- **Example:** `resolve {limit: 30,000,000 VND} -> 422 paths [/approvedLimit] -> resolve {approvedLimit} -> RESOLUTION_PENDING; proposer: Approve unavailable; second person: approve -> RESOLVED`.
- **Test ID:** `CKP_080_a_proposed_result_is_built_from_fields_and_waits_for_a_colleague`, `CKP_080_a_second_person_approves_the_proposal_and_the_proposer_cannot`, `CKP_080_the_proposer_sees_approve_unavailable_with_the_reason`, `CKP_080_a_rejected_proposal_opens_the_incident_again_with_the_reason`, `CKP_080_under_the_single_setting_of_a_dev_host_a_resolution_applies_at_once`, `CKP_080_the_proposer_is_told_a_colleague_approves`, `CKP_080_the_resolve_mode_follows_the_host_setting_and_is_four_eyes_unless_single`

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

### CKP-046 An operator claims a task from Available to me, and a task claimed first by a colleague says who
- **Traces to:** `TR-UI-02`, `TR-UI-03`, `TR-UI-04`, `TR-IDEM-01`, PD-110, PD-111
- **Milestone:** M2
- **Given:** Host `loans` runs the Process Engine. Task `T1` (Credit review, case `LN-2026-0142`) is open for role `credit-officer` and due 21 Nov 2026 08:00 in Hanoi, before the engine clock; `T2` is open for the same role and due later; `T3` is open for group `loans-operations`. An holds `processes:task:work`, role `credit-officer` and group `loans-operations`. Second case: Hoa claims `T1` a moment before An.
- **When:** An opens Tasks, chooses Available to me, and claims `T1`.
- **Then:** Tasks shows in the menu only where a Process Engine host is registered and `/me` lists `processes:task:work` or `processes:task:manage`. Available to me sends `GET /tasks?assignment=candidate&status=OPEN&limit=50` and lists `T1`, `T2`, `T3` in the engine's due-time order with the count in the tab ("Available to me (3)", "50+" when the engine has more). Each row shows the step name, the case, who it is for in words ("Role: Credit officer", "Group: Loans operations", or the person), the due time and the status in words with an icon. Which tasks are overdue comes from the engine (`slaBreached=true`, or `slaState` when the list carries it); the browser computes no deadline. An overdue task shows an amber "Overdue" pill with an icon, the list header counts them ("1 overdue"), and a task without a deadline reads "No deadline". The first task is selected and shown beside the list with the actions of `GET /tasks/{id}` `allowedActions`. Claim sends `:claim` with `If-Match` (the task's `lockVersion`) and an `Idempotency-Key`; only after the `200` does `T1` leave Available to me and appear in My tasks, with "You claimed Credit review. It is in My tasks.". In the second case the `409` reads "hoa claimed this task first." above the list, no code or body is shown, and the list is read again without `T1`. An action the user may not take is shown unavailable, without the primary fill, with the reason in words beside it ("You worked on this case, so someone else does this task."). At 1440x900 the list, the selected task and its primary action are on screen without scrolling; checked again at 1440x1200, in English and Vietnamese, light and dark, by keyboard alone, and axe-core reports no serious violation.
- **Example:** `Available to me (3) -> T1 Overdue, T2, T3 -> Claim T1 -> 200 -> My tasks (3), Available to me (2)`.
- **Test ID:** `CKP_046_available_to_me_lists_open_tasks_in_due_order_and_claim_moves_one_after_the_200`, `CKP_046_a_task_claimed_first_by_a_colleague_names_them_and_leaves_the_list`, `CKP_046_a_task_the_user_may_not_claim_shows_claim_unavailable_with_the_reason`, `CKP_046_each_tab_maps_one_to_one_to_a_task_query`, `CKP_046_roles_and_groups_read_in_words_and_users_by_id`, `CKP_046_overdue_comes_from_the_engine_never_from_the_browser_clock`, `CKP_046_the_queue_route_keeps_the_tab_and_task_and_ignores_anything_else`, `CKP_046_claim_is_reached_and_sent_by_keyboard_alone_with_a_visible_focus`

### CKP-047 An operator releases a claimed task back to Available to me
- **Traces to:** `TR-UI-02`, `TR-IDEM-01`, PD-110
- **Milestone:** M2
- **Given:** An claimed `T4` (Credit review, case `LN-2026-0139`).
- **When:** An opens My tasks, selects `T4` and chooses Release.
- **Then:** The selected task offers Open task (the task's screen, `#/tasks/{id}`) as its primary action and Release beside it. Release sends `:unclaim` with `If-Match` and an `Idempotency-Key`; after the `200` the task is back in Available to me, with "Credit review is back in Available to me.". Completing a task through its form is CKP-021.
- **Example:** `My tasks: T4 -> Release -> 200 -> Available to me (4)`.
- **Test ID:** `CKP_047_release_hands_my_task_back_to_available_after_the_200`

### CKP-048 A supervisor reassigns a task to a colleague with a required reason
- **Traces to:** `TR-UI-02`, `TR-UI-04`, `TR-IDEM-01`, `TR-SEC-05`, PD-110
- **Milestone:** M2
- **Given:** Sau holds `processes:task:work` and `processes:task:manage`. `T2` is open and `T4` is claimed by An. `T5` (Approve credit limit) is a four-eyes task of a case Hoa started. Second case: An holds only `processes:task:work`. Third case: a review task carries masked values.
- **When:** Sau opens All tasks and reassigns `T2` and `T4` to `hoa` with the reason "Covering leave"; then reassigns `T5` to `hoa`; An opens a link to All tasks; Sau selects the review task.
- **Then:** All tasks sends `GET /tasks?assignment=any&limit=50`. Reassign opens a dialog with the colleague's user id and a reason (1 to 500 characters, with a count); its Reassign button stays unavailable, with "Enter a user id and a reason to reassign." beside it, until both are entered, and sends nothing before. `T2` is sent with `:reassign` and `T4` with `:delegate`, each with `{ assignee: hoa, reason: Covering leave }`, `If-Match` and an `Idempotency-Key`; after each `200` the dialog closes with "Credit review is now with hoa." and the lists are read again. For `T5` the `409 ASSIGNEE_NOT_ALLOWED` reads "hoa cannot take this task. They may lack its role, or have worked on this case." inside the dialog, which keeps the reason, and the task does not move. In the second case All tasks and Reassign are not shown and no `assignment=any` request is sent. In the third case the task says "Some values of this task are masked." and no masked value or fingerprint is shown. Escape closes the dialog and returns the focus to Reassign.
- **Example:** `T2 OPEN -> :reassign {hoa, Covering leave}; T4 CLAIMED -> :delegate {hoa, Covering leave}; T5 -> 409 ASSIGNEE_NOT_ALLOWED -> plain sentence`.
- **Test ID:** `CKP_048_reassign_sends_reassign_for_an_open_task_and_delegate_for_a_claimed_one_with_the_reason`, `CKP_048_a_target_the_engine_refuses_reads_as_a_plain_sentence_and_the_task_stays`, `CKP_048_without_the_manage_permission_all_tasks_and_reassign_are_not_shown`, `CKP_048_masked_values_of_a_task_stay_masked_and_are_never_shown`, `CKP_048_tasks_show_only_with_a_task_permission_and_all_tasks_only_with_manage`, `CKP_048_an_open_task_is_reassigned_and_a_claimed_one_delegated_when_the_engine_allows`, `CKP_048_refusals_map_from_the_problem_code`, `CKP_048_reassign_is_done_by_keyboard_and_escape_closes_the_dialog_without_a_request`

### CKP-049 An undo step that fails reads calmly, names the step and the attempts, and never says the case was put right
- **Traces to:** `TR-COMP-02`, `TR-UI-03`, `PRE-203`, #80
- **Milestone:** M2
- **Given:** Instance `I9` where `releaseFunds`, the undo of `reserve-funds`, fails on every attempt and the engine opens an incident; `compensations[]` reads `NOT_FULLY_UNDONE`, `blockedStep` `reserve-funds`, with an `incidentId`.
- **When:** An operator with `processes:read` opens `I9` and its Timeline.
- **Then:** The timeline shows "Undo failed: Release funds" with "Failed after 3 attempts", in plain words with the symbol `!` and no red; above it the scope reads "Disburse: not fully undone" with "Open the incident", a link that opens that incident selected in the incident queue (`CKP-008`, `#/incidents?view=open&incident={incidentId}`). Nothing on the page says the case was put right. At 1440x900 the failure and the undo entries are visible without scrolling; checked again at 1440x1200, in English and Vietnamese, light and dark, with axe-core.
- **Example:** `releaseFunds fails 3 times -> "Undo failed: Release funds, Failed after 3 attempts"; scope "Disburse: not fully undone"`.
- **Test ID:** `CKP_049_a_failed_undo_reads_calmly_and_never_says_the_case_was_put_right`

### CKP-022 The tenant switcher offers only allowed tenants and shows a refusal
- **Traces to:** `TR-SEC-03`, `TR-TEN-01`
- **Milestone:** M2
- **Given:** `/me` returns `tenant.current: bank-a`, `tenant.allowed: [bank-a, bank-a-fin]`; a test proxy rewrites one request to `X-Tenant-Id: bank-b`.
- **When:** The operator switches to `bank-a-fin`, then the rewritten request is sent.
- **Then:** Every open list reloads with `X-Tenant-Id: bank-a-fin` and shows only that tenant's data; the rewritten request gets `403 TENANT_NOT_ALLOWED`; the cockpit returns to `bank-a` and shows "The service did not accept that tenant for your account" with the `correlationId` behind Details. The sentence names no tenant, because the problem does not say which tenant was refused and the proxy changed the one the cockpit sent. The switcher is hidden when `/meta` reports `tenancy.switching: false` or `/me` allows one tenant. No screen shows rows from two tenants.
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
- **Traces to:** `TR-COMP-01`, `TR-COMP-02`, `TR-OBS-01`, `PRE-203`, #80
- **Milestone:** M2
- **Given:** Instance `I7` of `loan-booking` where `reserve-funds` then `open-account` completed inside the saga `disburse` and `notify-partner` failed; the undo steps `closeAccount` and `releaseFunds` ran, the second after one retry (`PRE-044`). The engine reports the scope `disburse` as `UNDONE` with two steps undone.
- **When:** An operator with `processes:read` opens `I7` and its Timeline.
- **Then:** The timeline lists, in time order and in text, the failure of "Notify partner", then "Undo of Open account: Close account", then "Undo of Reserve funds: Release funds" with its retry, and the scope "Disburse: undone". Each undo entry carries the symbol ↺ and the word "Undo" besides the warning-hue left edge, so colour is never the only mark. The state of the scope is the engine's `compensations[].state`; the page never works it out from the entries. The diagram marks each undone step with ↺ and the label "Undone". No variable or input value appears; no code or id is shown in the page body.
- **Example:** `I7 timeline -> Notify partner failed, Undo of Open account: Close account, Undo of Reserve funds: Release funds (attempt 2), Disburse: undone`.
- **Test ID:** `CKP_033_compensation_runs_in_instance_timeline`

### CKP-034 SLA breaches are a filter of the task queue, measured against each task's own deadline
- **Traces to:** `TR-CAL-01`, `TR-PERF-07`
- **Milestone:** M2
- **Given:** Tasks `T1` (`sla: PT4H` on calendar `vn-banking-hours`, due 10:00), `T2` (`sla: P2D`, due tomorrow) and `T3` (no SLA) on host `loans`; the clock reads 11:00.
- **When:** An operator turns on the "SLA breached" filter of the task queue.
- **Then:** The cockpit sends the task query with `slaBreached=true` and shows only `T1`, with "overdue by 1 h" in text; `T3` never appears, because a task without a declared deadline cannot breach; no deadline is computed in the cockpit or from a product constant.
- **Example:** `filter SLA breached at 11:00 -> T1 only, overdue by 1 h`.
- **Test ID:** `CKP_034_sla_breach_filter_uses_task_deadlines`

### CKP-050 Overdue count, order and wording, kept in the link
- **Traces to:** `TR-CAL-01`, PD-024, PD-111
- **Milestone:** M2
- **Given:** On host `loans` at the engine clock 09:30 in Hanoi, `Collateral valuation` (claimed by Hoa) was due yesterday 16:00 and `Credit review` today 08:00; other tasks are due later or have no deadline. Sau holds `processes:task:manage`; An holds only `processes:task:work`, and none of her own tasks is overdue.
- **When:** Sau turns on Overdue in All tasks and shares the link; An turns on Overdue in My tasks.
- **Then:** The filter sits beside the tabs and reads "Overdue 2", the count of the tab's `slaBreached=true` list. Turned on, it is pressed, the query is `assignment=any&slaBreached=true&limit=50`, the header reads "2 overdue, oldest first", and the two tasks are listed in the engine's due-time order, oldest deadline first; a task without a deadline never appears. The link is `#/tasks?view=all&overdue=1`, so a shared link opens the same view. Each row shows "Overdue" with an icon, and "Overdue by 26 h" when the engine's task item carries `overdueBy` (PD-111); the browser computes no duration. For An the query is `assignment=mine&slaBreached=true&limit=50` and one line reads "No overdue tasks." with a link to every task. At 1440x900 the filter, the count and the first rows are on screen without scrolling; checked again at 1440x1200, in English and Vietnamese, light and dark; overdue is shown in words and an icon, not colour alone; the keyboard path and axe-core pass.
- **Example:** `Overdue 2 -> GET /tasks?assignment=any&slaBreached=true -> Collateral valuation, Credit review`.
- **Test ID:** `CKP_050_combined_with_my_tasks_and_none_overdue_says_so_in_one_line`, `CKP_050_overdue_by_reads_the_engine_duration_when_the_list_carries_it`, `CKP_050_the_overdue_filter_is_kept_in_the_link_and_anything_else_is_ignored`, `CKP_050_demo_the_queue_filtered_to_two_overdue_tasks_and_the_filter_kept_in_the_link`

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
- **Given:** Rule set `customer-screening` v1 `PUBLISHED` with inputs `nationalId` and `phoneNumber` (`mask: true`) and `segment`; v2 `PENDING_APPROVAL`, requested by `hoa`, where `nationalId` declares no `mask` and a new input `email` declares none. The engine answers v2 with two `UNMASKED_PERSONAL_DATA` warnings, both `requiresAcknowledgement: true`, and its diff against v1 with `masking`. `minh` holds `rules:approve`. Second case: `card-fee` v2 with no warning. Third case: the engine answers the approval `409 WARNINGS_NOT_ACKNOWLEDGED`. Fourth case: the engine marks no warning (a host whose `lifecycle.acknowledge-warnings` is empty, or a tenant whose `decision-log.mask-default` is on). Fifth case: the engine marks `nationalId` only, while `/api/bpm/meta` says nothing needs accepting.
- **When:** `minh` opens v2 at 1440x900, in English and in Vietnamese, light and dark, ticks the acceptance and approves.
- **Then:** The docked decision lists one line per input, "National ID: no masking rule", with no category or error code, a closing sentence that follows the number of inputs, and one checkbox naming the inputs: "I accept that these inputs are logged without masking: National ID, Email". Approve is unavailable until the box is ticked and then sends `acknowledgedWarnings` as the exact pairs shown, `[{ code: UNMASKED_PERSONAL_DATA, input: nationalId }, { code: UNMASKED_PERSONAL_DATA, input: email }]` (`RLE-053`). The warning, the box and Approve are visible without scrolling, each control at least 44 px. Above the side-by-side table the change sentences read "National ID: mask removed (masked → not masked by default)" and "Condition column added: Email, not masked by default"; the summary line carries "Mask removed on 1 column"; the proposed side's National ID and Email headers carry an amber marker whose tooltip is the sentence; Phone number and Segment carry none. In the second case there is no warning, no box and no marker, and Approve sends no acknowledgement. In the third case the warning shows the set the refusal returns, the box is cleared and names that set, "Approval needs this acceptance" shows, and nothing is sent again until the approver ticks and approves. In the fourth case the warning shows without the box. In the fifth case both lines show, and one checkbox names National ID only; Approve sends that one pair. The cockpit takes the marks from the engine, never from `/api/bpm/meta`. A draft with the same warnings shows them to its author, without the box. axe-core finds no serious violation.
- **Example:** `nationalId MASKED -> NOT_MASKED_BY_DEFAULT -> "National ID: mask removed (masked → not masked by default)"; Approve disabled -> tick -> approve {acknowledgedWarnings: [{UNMASKED_PERSONAL_DATA, nationalId}, {UNMASKED_PERSONAL_DATA, email}]}`.
- **Test ID:** `CKP_037_the_warning_the_acceptance_and_approve_are_all_on_screen_at_1440x900`

### CKP-038 A version exports as a spreadsheet file the engine writes
- **Traces to:** `TR-DET-02`, `TR-UI-02`, PD-107, PD-142, `RLE-191`
- **Milestone:** M1
- **Given:** Rule set `card-fee` v1 `PUBLISHED` with inputs `tier`, `balance` (money) and `txCount`, outputs `fee` and `waived`, five rows, and a user holding `rules:read`; in the second case a version whose row R2 uses a grouped condition.
- **When:** The user opens Export and chooses "As spreadsheet (CSV)".
- **Then:** One `GET .../versions/1/export?format=csv` is sent and `card-fee-v1.csv` is saved with the response bytes, unchanged: UTF-8 with a byte order mark, header `Rule,tier,balance,txCount,fee,waived,Description`, one line per rule, cells in shorthand. For the grouped version no file is saved and the screen says "This version cannot be a spreadsheet." with the row and the reason.
- **Example:** `export card-fee v1 as CSV -> card-fee-v1.csv; R2 grouped -> "This version cannot be a spreadsheet." Row R2`.
- **Test ID:** `CKP_038_a_version_exports_as_a_csv_file_the_engine_wrote`, `CKP_038_a_version_with_no_table_form_saves_nothing_and_names_the_rule`

### CKP-039 A spreadsheet is imported as the next draft only after the user confirms, and a refusal stores nothing
- **Traces to:** `TR-DET-02`, `TR-UI-02`, `TR-UI-04`, PD-107, PD-142, `RLE-192`, `RLE-193`, `RLE-194`
- **Milestone:** M1
- **Given:** A user holding `rules:author` on `card-fee` v1; files that change R3's fee, that write R2's balance without a currency, and (a reader, `rules:read` only) no import.
- **When:** The user picks a `.csv` file with Import new version.
- **Then:** A dialog says "Based on version 1", names the file and says it creates a new draft; nothing is sent before "Import as draft". One `POST .../versions?base=1` with `Content-Type: text/csv` and one `Idempotency-Key` follows, however often the button is pressed; after the `201` the screen says "Draft version 3 created" and opens the draft. A `422` stores nothing and lists one line per problem, "row R2, column Balance" and a plain reason in the user's language, never the cell's value. A `409` keeps the file and the dialog and offers "Try again", which sends a new key. A reader is offered Export and not Import.
- **Example:** `R3 fee 32000 VND, base 1 -> confirm -> 201, "Draft version 3 created"; R2 ">= 51234567" -> "row R2, column Balance: the amount has no currency"`.
- **Test ID:** `CKP_039_a_valid_file_is_sent_once_after_confirming_and_opens_the_new_draft`, `CKP_039_a_refused_cell_is_listed_by_row_and_column_without_its_value_and_nothing_is_saved`, `CKP_039_a_refusal_is_said_in_vietnamese_on_a_vietnamese_screen`, `CKP_039_a_conflict_keeps_the_file_and_the_dialog_and_a_retry_sends_a_new_key`, `CKP_039_cancelling_sends_nothing`, `CKP_039_a_reader_is_offered_export_and_not_import`, `CKP_039_keyboard_path_confirm_with_the_keyboard_and_escape_sends_nothing`, `CKP_039_visual_confirm_1440x900_dark_en`

### CKP-040 Columns cannot change through a file, and damage from Excel is refused cell by cell
- **Traces to:** `TR-DET-02`, `TR-UI-04`, PD-107, `RLE-192`, `RLE-193`
- **Milestone:** M1
- **Given:** A file with an added column `segment` and no `waived`; a file with `5E+07 VND` and `50,000,000 VND` in balance cells; a semicolon-separated file without a byte order mark.
- **When:** Each is imported.
- **Then:** The first is refused with "column segment" and "column waived" named and a reason that inputs and outputs cannot change through a file; the second with one line per damaged cell; nothing is saved. The semicolon file is accepted. No damaged value is read as another value.
- **Example:** `header with segment -> "column segment: this column is not in the base version"`.
- **Test ID:** `CKP_040_an_added_or_missing_column_is_refused_by_name`, `CKP_040_excel_damage_is_refused_cell_by_cell_and_a_semicolon_file_is_read`

---

## Definition Diagram, First Slice (Cockpit 0.2)

These two statements are the part of `CKP-016` and `CKP-029` that ships before stored layouts (slice 8.8) and the host details page: every definition is laid out by the cockpit, and the graph is read from the version's BPMN export (`exportDefinition`, `format=bpmn`), whose generated BPMNDI is not a stored layout and is not read.

### CKP-069 A definition without a stored layout is drawn left to right with no overlapping nodes, each with its type and name
- **Traces to:** `TR-UI-07`, `TR-DEF-02`, `TR-UI-03`, `TR-UI-06`
- **Milestone:** M2
- **Given:** Host `loans` runs the Process Engine with `loan-origination` v3 `ACTIVE` (13 steps: a start event, a person task, an agent task, a decision, an exclusive gateway with the default flow `Declined`, service tasks, three end events and a boundary timer) and no stored layout; `card-dispute` v1 `DRAFT` with no version in effect. User `hoa` holds `processes:read`. Second case: a synthetic definition of 200 steps. Third case: user `hoa` holds `processes:task:work` only.
- **When:** `hoa` opens Processes, opens `loan-origination`, then the Steps tab; the 200-step definition is laid out; in the third case `hoa` opens Processes.
- **Then:** The list shows one row per key from `GET /api/bpm/processes/v1/definitions`, each opening the version in effect (`#/processes/loan-origination/3`) or, with none in effect, the latest (`#/processes/card-dispute/1`). The diagram is read from `GET .../definitions/loan-origination/versions/3/export?format=bpmn` and laid out by dagre left to right (node separation 28, rank separation 24, edge labels laid out with the graph so they keep clear of the nodes): every flow ends to the right of where it starts, no two node boxes intersect, each arrow ends on its target's outline, and the default flow carries the BPMN default mark and its name. Every node shows its type icon, its whole name and its type in words (`Assess documents, Agent task`), wrapped and never truncated; agent tasks use the violet of agent steps only. The breadcrumb names the process by its display name, not its key. At the default Fit every text in the diagram is at least 12 px: Fit scales the drawing to the canvas, up as well as down, but not below 100 %; a wider drawing scrolls inside the canvas. The Steps tab lists the 13 steps in flow order from the start event with their type in words, as the keyboard path through the diagram. The 200-step definition is laid out in under 1 s. The diagram code and dagre load in their own chunk only when a diagram opens. In the third case the page says in one line that the user's permissions show no process definitions, and no list is requested. At 1440x900 the title and the diagram are on screen without scrolling, in English and Vietnamese, light and dark, and axe-core reports no serious violation.
- **Example:** `open loan-origination v3 -> export?format=bpmn -> 13 nodes left to right, 0 overlaps; 200 steps laid out in < 1 s`.
- **Test ID:** `CKP_069_loan_origination_is_laid_out_left_to_right_without_overlapping_nodes`, `CKP_069_every_flow_ends_on_its_target_and_the_default_flow_is_marked`, `CKP_069_a_200_step_definition_is_laid_out_within_the_one_second_budget`, `CKP_069_the_step_list_follows_the_flow_from_the_start`, `CKP_069_processes_list_opens_the_version_in_effect_as_a_diagram_with_its_steps`, `CKP_069_a_user_without_processes_read_is_told_and_no_list_is_requested`, `CKP_069_the_diagram_chunk_loads_only_when_a_diagram_opens`, `CKP_069_diagram_text_is_at_least_12px_at_fit_and_fit_uses_the_canvas`

### CKP-070 Diagram nodes are keyed by BPMN element type and HORUS subtype; an unknown type is drawn as a generic node naming its type
- **Traces to:** `TR-UI-07`, `TR-UI-08`, ADR-0017 (DN-11)
- **Milestone:** M2
- **Given:** The export of `loan-origination` v3 holds `startEvent`, `userTask`, `serviceTask` with `horus:type="agentTask"`, `businessRuleTask`, `exclusiveGateway`, `serviceTask` and `endEvent`, and a `boundaryEvent` with a `timerEventDefinition`, which this cockpit does not draw; `kyc-onboarding` v2 holds a `parallelGateway` and an `intermediateCatchEvent` with a timer. Second case: a document with a `DOCTYPE`, one with no process, and one that is not well-formed.
- **When:** The operator opens both diagrams, in English and in Vietnamese.
- **Then:** Nodes are keyed `startEvent`, `userTask`, `serviceTask:agentTask`, `businessRuleTask`, `exclusiveGateway`, `serviceTask`, `endEvent`, `parallelGateway` and `intermediateCatchEvent:timer`, each drawn with its own symbol and named in the user's language (`Wait one day, Hẹn giờ`). The boundary timer is kept with the key `boundaryEvent:timer`, drawn as a dashed generic box with its outgoing flow, showing its name and its kind in words from its event definition, with the timer icon (`Timer`, `Hẹn giờ`); a type with no known event definition reads `Other step` (`Bước khác`). The key is never shown as text. The rest of the diagram is intact and no error is shown. In the second case the diagram shows an error with Try again instead of a guessed drawing.
- **Example:** `boundaryEvent + timerEventDefinition -> dashed node "No documents in 2 days", "Timer" with the clock icon, flow to remind kept`.
- **Test ID:** `CKP_070_steps_are_keyed_by_bpmn_type_with_the_horus_subtype`, `CKP_070_an_unknown_type_stays_in_the_graph_with_its_type_name_and_the_rest_is_intact`, `CKP_070_a_document_with_a_doctype_or_no_process_is_refused_not_guessed`, `CKP_070_an_unknown_node_type_renders_as_a_generic_node_naming_its_type`, `CKP_070_vietnamese_names_every_known_type_in_words`

## Decision Explorer, Version History and Evidence Export (PD-027, PD-108, PD-116)

The engine decides and the cockpit shows: the explanation sentence, the masked values, the history and the evidence file with its SHA-256 all come from the Rule Engine (`GET /decisions`, `GET /decisions/{id}`, `GET /decisions/{id}/explain`, `GET /rule-sets/{name}/history`, `POST /rule-sets/{name}/evidence`). The cockpit computes none of them.

### CKP-041 A link from a host screen opens one decision after sign-in
- **Traces to:** `TR-SEC-05`, `TR-QA-01`, PD-108
- **Milestone:** M1
- **Given:** Decision `01JD2X…` logged on host `loans` for tenant `bank-a`, with `nationalId` masked and rule R2 matched. Thu holds `rules:log:read` and is not signed in. Second case: the registry lists `loans` and `cards`.
- **When:** Thu opens `<cockpit>/#/decisions/01JD2X…`; in the second case `#/decisions/01JD2X…?host=loans`, then the same link without `host`.
- **Then:** After sign-in the cockpit sends `GET /api/bpm/rules/v1/decisions/01JD2X…` once and shows the decision page with R2 highlighted, its reading and `nationalId` masked; signed in already, the page opens with no sign-in step. With `host=loans` the decision is read from `loans` only and the address becomes `#/hosts/loans/decisions/01JD2X…`. Without `host` the page asks "Which service logged this decision?" with both hosts and requests nothing until one is chosen. Nothing else in the link is read.
- **Example:** `#/decisions/01JD2X…?host=loans -> sign-in -> #/hosts/loans/decisions/01JD2X…, one decision request to loans`.
- **Test ID:** `CKP_041_link_lands_on_the_decision_after_sign_in`

### CKP-042 A decision link refuses what the user may not see and what is not a decision id
- **Traces to:** `TR-SEC-05`, `TR-QA-01`, PD-108
- **Milestone:** M1
- **Given:** Eve holds `rules:read` but not `rules:log:read`. `01JD9Z…` does not exist and `01JD7K…` belongs to tenant `bank-b`. A third link's id is longer than 26 characters or holds markup.
- **When:** Eve opens a decision link; Thu opens the missing and the other tenant's link; anyone opens the third link.
- **Then:** Eve reads "You do not have permission to open decisions." and no decision request is sent; a forced request gets `403`. Both of Thu's links read "Decision not found for tenant bank-a." with the same wording. The third link reads "This link is not valid.", sends no request and renders nothing of the link as markup. No personal data is read from or written to the address.
- **Example:** `#/decisions/<img…> -> "This link is not valid.", 0 requests`.
- **Test ID:** `CKP_042_decision_link_refusals`

### CKP-071 An auditor finds a rule set's or a case's decisions in a month and opens one
- **Traces to:** `TR-OBS-05`, `TR-UI-02`, `TR-UI-04`, `RLE-029`, PD-108
- **Milestone:** M1
- **Given:** `card-fee` has 124 decisions logged in November 2026 and case reference `LN-2026-0042` has one `card-fee` and one `kyc-risk` decision. Ha holds `rules:read` and `rules:log:read`. Second case: Lan holds no `rules:log:read`.
- **When:** Ha opens Decisions, chooses `card-fee` and November 2026 and shows the decisions; then searches by case reference `LN-2026-0042`; then types `LN 42`; then opens a decision from the list.
- **Then:** One `GET /decisions?ruleSet=card-fee&from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z&limit=50` is sent; the list shows the first 50, newest first, with when, the rule set and version, the outcome in words with an icon ("Rule R2 matched", "No rule matched", "Failed"), the case reference and a short decision id, and "Show more decisions" reads the next page with the cursor. The search lives in the URL (`#/decisions?ruleSet=card-fee&month=2026-11`). The case search sends `correlationId=LN-2026-0042` and lists both decisions. `LN 42` is refused on the field before any request. A row opens `#/decisions/{id}`. Nothing is searched until the user asks. In the second case Decisions is not in the menu (an area is hidden when it holds data the user may not read), a link to it reads "Not available", and no decision endpoint is called.
- **Example:** `card-fee, November 2026 -> "card-fee, November 2026", "First 50 decisions", newest 20 Nov 2026 10:00 "Rule R2 matched" LN-2026-0042 01KF2Q…P7T9`.
- **Test ID:** `CKP_071_decisions_of_a_rule_set_or_a_case_in_a_month_newest_first`

### CKP-072 A decision reads as the engine's sentence, its conditions and its inputs, with masked inputs masked
- **Traces to:** `TR-SEC-05`, `TR-OBS-05`, `TR-UI-04`, `RLE-030`, `RLE-031`, PD-027, PD-108
- **Milestone:** M1
- **Given:** Decision `01KF2Q8Z0RQ6V2Y8M1C4N5P7T9` of `card-fee` v1 matched `R2` with `tier` `GOLD` and `balance` 60,000,000 VND, and logged `nationalId` as `hmac:k1:9f4a6c…`. A second decision failed with `EVALUATION_ERROR`. Ha holds `rules:log:read` and `rules:read`.
- **When:** Ha opens the first decision in English and in Vietnamese, opens Inputs and Rule table; then the failed decision; then an id of another tenant; then `#/decisions/not-a-decision`.
- **Then:** One `GET /decisions/{id}` and one `GET /decisions/{id}/explain?lang=en` (`lang=vi` in Vietnamese) are sent; the engine's `text` shows unchanged as the focal sentence, with the outcome as an icon and words. Conditions lists each condition of `R2` with the input's readable name ("Tier", "Balance"), the rule's condition as in the table ("Gold", "≥ 50,000,000 VND"), the recorded value with grouped amounts ("60,000,000 VND") and "Held". Inputs lists every logged input; `nationalId` reads "Masked" with a padlock and the reference `9f4a6c`, never a plain value and never as an error. Rule table shows the version with `R2` marked. The decision id shows in full with "Copy decision id", which copies exactly what is shown. The failed decision reads "The rule set could not be evaluated, so no result was produced." with the error edge, not the accent; the error code and the engine's sentence sit behind Details; Conditions says nothing was produced. Another tenant's decision reads "No decision with this id in tenant bank-a." A string that is not a decision id reads "This link does not name a decision." and no request is sent. No raw JSON is shown.
- **Example:** `open 01KF2Q…P7T9 -> engine sentence; Tier Gold Held; Balance ≥ 50,000,000 VND 60,000,000 VND Held; National id Masked 9f4a6c`.
- **Test ID:** `CKP_072_decision_shows_engine_sentence_conditions_and_masked_inputs`

### CKP-073 The history lists every version, newest first, with its people, comments and events
- **Traces to:** `TR-OBS-01`, `TR-UI-02`, `TR-UI-04`, PD-116
- **Milestone:** M3
- **Given:** `card-fee` v1 (created by Lan, approved by Minh with "Matches the tariff", retired when v2 took effect), v2 (rejected by Minh with "R2 waiver needs pricing committee minutes", sent again and approved with "Committee minutes attached") and v3 draft. Ha holds `rules:read`.
- **When:** Ha opens `card-fee`, then History.
- **Then:** One `GET /rule-sets/card-fee/history` is sent. One line per version, newest first, shows the status in words with an icon, "In effect from …" or "In effect … to …" or "Not in effect", who created, sent, approved and rejected it with when, and the approval comment and rejection reason. Each line opens to its events, oldest first, in words ("Rejected by Trần Văn Minh"), with the reason, which then replace that summary so nothing shows twice; an event the engine sends twice shows once; the version open on screen starts open. Evidence exports are listed below with who, what and when. Export evidence is beside the history; both are visible without scrolling at 1440x900.
- **Example:** `History -> "Version 3 · Draft · Not in effect", "Version 2 · Published · In effect from 19 Nov 2026 03:00", "Version 1 · Retired · In effect 01 Nov 2026 03:00 to 19 Nov 2026 03:00"`.
- **Test ID:** `CKP_073_history_lists_every_version_newest_first_with_people_and_events`

### CKP-074 Evidence of chosen versions and decisions is exported by the engine and its SHA-256 is shown
- **Traces to:** `TR-SEC-05`, `TR-OBS-01`, `TR-IDEM-01`, PD-116
- **Milestone:** M3
- **Given:** The history of `CKP-073`. Ha also holds `rules:log:read`.
- **When:** Ha ticks versions 1 and 2, picks November 2026 and ticks decisions `01KF2Q8Z0RQ6V2Y8M1C4N5P7T9`, `01KF2Q8Z0RQ6V2Y8M1C4N5P7TA` and `01KF2Q8Z0RQ6V2Y8M1C4N5P7TB`, keeps CSV and exports; then clicks Export again quickly on a second export.
- **Then:** One `POST /rule-sets/card-fee/evidence` with an `Idempotency-Key` and `{ format: csv, versions: [1, 2], decisionIds: [three ids] }` is sent; the saved file is the response bytes under the engine's file name; the card reads "Evidence exported", "Versions 1, 2 · 3 decisions", the file name, format and size, and "SHA-256" with all 64 hex digits of `X-Evidence-Sha256` and "Copy SHA-256", which copies exactly what is shown. A refusal shows just above Export and is scrolled into view beside it. The history then lists "Evidence exported by Phạm Thu Hà: 2 versions, 3 decisions". The file shows masked inputs as their keyed hash. A repeated click while the export runs sends nothing more.
- **Example:** `versions 1, 2 + 3 decisions, CSV -> "Evidence exported", "SHA-256" with 64 hex digits, Copy SHA-256 -> the same 64 hex digits`.
- **Test ID:** `CKP_074_export_sends_one_request_saves_the_bytes_and_shows_the_sha256`

### CKP-075 Export says why it is unavailable, and shows each refusal in words
- **Traces to:** `TR-SEC-05`, `TR-UI-02`, `TR-UI-04`, PD-116
- **Milestone:** M3
- **Given:** The history of `CKP-073`. First case: Ha holds `rules:read` only. Second case: Ha holds `rules:log:read` and the 124 decisions of November 2026 are listed. Third case: the engine answers `422 EVIDENCE_TOO_LARGE`. Fourth case: it answers `403 PERMISSION_DENIED`. Fifth case: it answers `400 INPUT_INVALID` naming a chosen decision as one of another rule set. Sixth case: the history answers `404 RULE_SET_NOT_FOUND`.
- **When:** Ha opens Export evidence and chooses as described.
- **Then:** With nothing chosen, Export is unavailable with "Choose at least one version or decision." In the first case the decision picker is not shown, one line says adding decisions needs permission to read the decision log, and a versions-only export sends no `decisionIds`. In the second case the picker says "One file holds at most 100 decisions." and offers "Choose first 100" instead of choosing all; it ticks the first 100, the count reads "100 chosen", the other boxes are unavailable until one is unticked, and Export stays available. The third case reads "The file would be larger than 8 MB. Choose fewer decisions.", the fourth "You may not export this evidence on this service.", the fifth "Decision 01KF2Q…P7TA belongs to another rule set and cannot be exported here.", the sixth "This rule set was not found on this service."; each keeps the choices, and the code and reference sit behind Details with Copy.
- **Example:** `124 listed -> "Choose first 100" -> "100 chosen", the rest unavailable; 422 EVIDENCE_TOO_LARGE -> "The file would be larger than 8 MB. Choose fewer decisions."`.
- **Test ID:** `CKP_075_export_limits_and_refusals_read_in_words`

### CKP-076 Each row of a version reads as the engine's sentence, in the user's language and the viewer's style
- **Traces to:** `TR-UI-02`, `TR-UI-04`, `RLE-091`, PD-027, PD-077
- **Milestone:** M2
- **Given:** `card-fee` v1 `PUBLISHED` with rows `R1` to `R5`. Lan holds `rules:read`. The host's `rules.readings.style` is unset (sentence).
- **When:** Lan opens the Readings tab of v1 in English, then in Vietnamese, then chooses Short and reloads.
- **Then:** One `GET .../versions/1/readings` is sent. Each row shows its id and the engine's `full` text in the user's language, unchanged: `R2` reads "Rule R2 applies when tier is GOLD and balance is at least 50,000,000 VND. Then fee is 0 VND; waived is Yes." in English and "Quy tắc R2 áp dụng khi tier là GOLD và balance ít nhất là 50.000.000 VND. Khi đó fee là 0 VND; waived là Có." in Vietnamese, with no English on the Vietnamese screen. Sentence / Short switches every reading on screen to the `short` text without a new request; the choice is stored in the browser as a display preference (no user data) and survives a reload. Until the viewer chooses, the host's setting decides (`shorthand` gives Short). The cockpit never builds or edits a sentence.
- **Example:** `R2 -> "Rule R2 applies when tier is GOLD and balance is at least 50,000,000 VND. ..."; Short -> "tier = GOLD, balance >= 50,000,000 VND -> fee = 0 VND; waived = Yes"`.
- **Test ID:** `CKP_076_each_row_shows_the_engine_sentence_in_the_chosen_language`

### CKP-077 A change reads row by row with the old reading struck and the new one added
- **Traces to:** `TR-UI-02`, `TR-UI-04`, `RLE-092`, PD-027
- **Milestone:** M2
- **Given:** `card-fee` v2 `PENDING_APPROVAL` against v1: `R3` fee raised from 30,000 to 35,000 VND, `R6` added, `R4` removed. Minh holds `rules:approve`.
- **When:** Minh opens Changes and chooses Readings.
- **Then:** One `GET .../versions/2/diff/readings?base=1` is sent and the side-by-side table is replaced, one view at a time. `R3` shows the v1 reading struck and the v2 reading added; `R6` reads "Added" with its reading only; `R4` reads "Removed" with its reading struck; a row whose `readingChanged` is false shows its reading once and says the wording did not change. "3 rows read the same." closes the list. Approve and Reject stay docked and visible at 1440x900.
- **Example:** `R3 -> struck "... fee is 30,000 VND ...", added "... fee is 35,000 VND ..."`.
- **Test ID:** `CKP_077_the_old_reading_is_struck_and_the_new_one_added_for_each_changed_row`

### CKP-078 A simulation reads both outcomes and says whether the result changes
- **Traces to:** `TR-UI-02`, `TR-UI-04`, `RLE-093`, PD-027
- **Milestone:** M2
- **Given:** `card-fee` v2 and v1 in effect. Minh holds `rules:simulate` and `rules:read`; Lan does not hold `rules:simulate`.
- **When:** Minh opens Simulate in the inspector, chooses Tier Gold and presses Simulate; Lan opens Simulate.
- **Then:** One `POST .../versions/2:simulate-readings` is sent with `{ "samples": [{ "inputs": { "tier": "GOLD" } }] }` and no `Idempotency-Key`; no decision is evaluated or logged. The result replaces the form, so it shows above the fold at 1440x900: "The result changes", the v2 reading and the v1 reading under "In effect: version 1", in the chosen style. "Change the sample" brings the form back with the sample kept. For Lan, Simulate is shown disabled with "You need permission to simulate rules." beside it.
- **Example:** `Gold -> "The result changes"; Version 2 "... fee is 35,000 VND ..."; In effect: version 1 "... fee is 30,000 VND ..."`.
- **Test ID:** `CKP_078_a_simulation_reads_both_sides_and_says_whether_the_result_changes`

### CKP-079 Readings show loading, empty, refused, too large, failed and masked states in words
- **Traces to:** `TR-SEC-05`, `TR-UI-02`, `TR-UI-04`, `RLE-091`, `RLE-092`, `RLE-093`
- **Milestone:** M2
- **Given:** `new-fees` v1 with no rows; `branch-fees` v1 with 501 rows; `kyc-routing` v1 whose `nationalId` is masked and tested by `K1`. The engine may answer `403 PERMISSION_DENIED`, `422 READINGS_TOO_LARGE` or `500`.
- **When:** The readings, the change readings and the simulation are opened in each case.
- **Then:** While loading, skeleton rows show at the height of real rows. No rows reads "This version has no rows yet." A 403 reads "You cannot read rows in plain words on this service." with no retry. `READINGS_TOO_LARGE` is a calm information note, not an error, without a number: "Too many rows to read at once", with "Open the table" (version) or "Compare in the table" (change), or "Run fewer samples." (simulation). Any other failure reads "The service could not answer. Try again." with Try again, the code and reference behind Details. A row that tests a masked input carries "Masked data is named here, never shown."; the masked value appears in no reading.
- **Example:** `branch-fees -> "Too many rows to read at once" + Open the table; kyc-routing K1 -> "National ID is masked; its test is not shown"`.
- **Test ID:** `CKP_079_too_many_rows_reads_calmly_and_offers_the_table`

### CKP-081 The author of a draft discards it after confirming, and the screen moves on
- **Traces to:** `TR-UI-02`, `TR-IDEM-01`, RLE-098, #418
- **Milestone:** M2
- **Given:** `card-fee` v1 is in effect and v2 is a draft Lan wrote. Lan holds `rules:author`; Minh holds `rules:approve`.
- **When:** Lan opens v2, chooses Discard draft, cancels, chooses it again and confirms with the reason "Started from the wrong version"; Minh opens v2.
- **Then:** Discard draft shows beside the draft's actions only when the engine lists `discard` in the version's `allowedActions`. It opens a dialog naming the version, with an optional reason; Cancel and Escape send nothing and return the focus to the button. Confirm sends `POST /rule-sets/card-fee/versions/2:discard` with `If-Match: "<lockVersion>"`, an `Idempotency-Key` and `{ reason }`; only after the `200` does the toast say "Version 2 draft discarded." and the screen return to the rule set, now on v1, with v2 gone from the version picker. A refusal (`409 VERSION_CONFLICT`, `409 INVALID_STATE`, `403 PERMISSION_DENIED`) reads as one plain sentence in the dialog with the code behind Details. The history shows a discarded version as "Discarded" with an icon. Minh sees no Discard draft. Checked at 1440x900 and 1440x1200, in English and Vietnamese, light and dark, by keyboard, and axe-core reports no serious violation.
- **Example:** `v2 DRAFT allowedActions [request-publish, discard] -> Discard draft -> confirm -> POST :discard 200 -> #/rules/card-fee (v1)`.
- **Test ID:** `CKP_081_the_author_discards_a_draft_after_confirming_and_returns_to_the_version_in_front`, `CKP_081_a_draft_without_discard_in_allowed_actions_shows_no_discard_button`, `CKP_081_discard_is_confirmed_by_keyboard_and_escape_closes_the_dialog_without_a_request`
