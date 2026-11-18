# Cockpit — Behaviour Specification

Behaviour is specified as numbered, testable statements. Every statement maps 1:1 to an automated Vitest/Testing Library or Playwright test (`TR-QA-01`).

---

## Configuration, Hosting & Auth

### CKP-001 Cockpit boots standalone or embedded using one host base URL configuration
- **Traces to:** `TR-UI-01`, `ADR-0005`
- **Given:** Cockpit served either via `cockpit-spring-boot-starter` at `/bpm/cockpit` OR as standalone static archive configured with `apiBaseUrl = "http://localhost:8080"`.
- **When:** User opens `/bpm/cockpit`.
- **Then:** Cockpit fetches runtime config, loads Rule Sets list via `/api/bpm/rules/v1/rule-sets`, and displays active tenant and authenticated user without requiring any BFF backend.
- **Example:** `Open /bpm/cockpit -> GET /api/bpm/rules/v1/rule-sets -> renders rule-sets table`.
- **Test ID:** `CKP_001_cockpit_boots_embedded_and_standalone_from_base_url`

---

## Rule Governance & Audit (Cockpit 0.1)

### CKP-002 Rule set diff highlights added, modified, and removed rows side by side
- **Traces to:** `TR-UI-02`
- **Given:** Rule set `card-fee` has `v1` (`PUBLISHED`) and `v2` (`PENDING_APPROVAL` with `R2` fee changed from `30000 VND` to `0 VND` and `R6` added).
- **When:** User opens Approval detail or Diff view comparing `v1` vs `v2`.
- **Then:** UI highlights `R2` (`changed`), `R6` (`added`), and shows any attached simulation diff summary before the approval action.
- **Example:** `Diff v1..v2 -> R2 marked modified (30000 -> 0 VND), R6 marked added`.
- **Test ID:** `CKP_002_version_diff_highlights_row_changes`

### CKP-003 Maker-checker disables Approve button for requester and surfaces API 403 if forced
- **Traces to:** `TR-SEC-04`, `TR-UI-02`
- **Given:** Logged-in user `alice` is the `requestedBy` identity of `card-fee` v2 (`PENDING_APPROVAL`).
- **When:** `alice` views the Pending Approval screen; separately, `bob` (`rules:approve`) views the same screen.
- **Then:** For `alice`, the Approve button is disabled with tooltip *"Requester cannot approve own publication"*; for `bob`, Approve and Reject (requiring a comment) are enabled and transition status to `PUBLISHED` upon confirmation.
- **Example:** `User=alice -> Approve disabled; User=bob -> click Approve -> status PUBLISHED`.
- **Test ID:** `CKP_003_maker_checker_ui_blocks_requester_self_approval`

### CKP-004 Simulation screen runs sample and historical replay without mutating production logs
- **Traces to:** `TR-UI-02`
- **Given:** `card-fee` v2 is in `DRAFT`.
- **When:** Analyst pastes 5 sample JSON/CSV rows or clicks *"Replay last 100 decisions"* and runs simulation.
- **Then:** UI displays matched rule IDs, output values, and changed-outcome counts (`18 / 100 changed`), while decision log count remains unchanged.
- **Example:** `Replay 100 decisions -> shows 18 changed outcomes; Decision Log count unchanged`.
- **Test ID:** `CKP_004_simulation_displays_outcome_changes_safely`

### CKP-005 Decision log explorer renders masked PII and plain-language explanation
- **Traces to:** `TR-SEC-05`, `TR-OBS-05`
- **Given:** Decision `01JD2X` logged with `nationalId` (`mask: true`) and matched rule `R2`.
- **When:** User with `rules:log:read` opens Decision `01JD2X` in the Decisions screen.
- **Then:** `nationalId` renders masked (`*** (hmac:9f4a...)`), never plaintext; matched row `R2` is highlighted in the rule table alongside the `/explain` text.
- **Example:** `Open decision 01JD2X -> nationalId masked, R2 highlighted`.
- **Test ID:** `CKP_005_decision_log_masks_pii_and_highlights_matched_row`

---

## Process Operations, `agentTask` & Grid Authoring (Cockpit 0.2)

### CKP-006 Interactive rule table editor validates expressions inline via `:validate`
- **Traces to:** `TR-DET-02`, `TR-UI-02`
- **Given:** Analyst edits draft `card-fee` v3 in Cockpit 0.2 grid editor and types invalid condition `balance + 100 > 0`.
- **When:** User clicks **Validate** or **Request Publish**.
- **Then:** Server `:validate` error binds directly to row `R2` cell `balance` and blocks publish request submission until fixed.
- **Example:** `Invalid cell "balance + 100" -> inline error on row R2, Request Publish blocked`.
- **Test ID:** `CKP_006_grid_editor_shows_row_validation_errors`

### CKP-007 `agentTask` inspector displays bounded inputs, schema verdict, guardrail rules, and override diff
- **Traces to:** `TR-AGT-01`, `TR-AGT-02`
- **Given:** Process instance `I9` ran `assess-documents` (`agentTask`) which triggered guardrail `R2` (`route-to-human`) creating approval task `T4`.
- **When:** Compliance reviewer opens `T4` / instance `I9` in Cockpit 0.2.
- **Then:** Screen shows masked agent inputs, JSON Schema check (`PASS`), structured agent output, triggered guardrail rule (`R2`), and lets the reviewer Approve or Override with required reason.
- **Example:** `Open T4 -> shows agent output + guardrail R2 badge + Approve/Override actions`.
- **Test ID:** `CKP_007_agent_task_inspector_shows_guardrail_and_override_controls`

### CKP-008 Incident queue surfaces root cause, retry count, and triggers `:retry` / `:resolve`
- **Traces to:** `TR-OPS-03`
- **Given:** Instance `I12` has an `OPEN` incident (`AGENT_TIMEOUT`, `retryCount: 2`).
- **When:** Operator opens Incidents queue, inspects the cause/context, and clicks **Retry**.
- **Then:** Cockpit sends `POST /api/bpm/processes/v1/incidents/{id}:retry` with `Idempotency-Key` and optimistic `version`, updating the incident list on `200 OK` (or showing conflict alert on `409`).
- **Example:** `Click Retry on incident -> POST :retry -> row transitions from OPEN`.
- **Test ID:** `CKP_008_incident_queue_retries_and_updates_status`

---

## Accessibility & Localization

### CKP-009 Keyboard navigation and WCAG 2.1 AA contrast pass in light and dark themes
- **Traces to:** `TR-UI-03`
- **Given:** Cockpit loaded in both light and dark CSS themes.
- **When:** Automated `axe-core` audit and keyboard-only navigation (`Tab` / `Enter` / `Esc`) run across Rule Sets, Approvals, and Incidents screens.
- **Then:** Zero critical/serious WCAG 2.1 AA contrast violations and all interactive actions are reachable with visible focus rings.
- **Example:** `Playwright + axe-core in light & dark modes -> 0 serious/critical violations`.
- **Test ID:** `CKP_009_wcag_aa_and_keyboard_navigation_pass`

### CKP-010 Complete English and Vietnamese UI localization without raw translation keys
- **Traces to:** `TR-UI-04`
- **Given:** Locale switched between `en` and `vi`.
- **When:** Render all Cockpit 0.1 and 0.2 views.
- **Then:** Every label, button, column header, and status badge renders translated text in both `en` and `vi` with zero untranslated i18n keys.
- **Example:** `Switch locale en -> vi -> all headings/statuses translated, 0 missing keys`.
- **Test ID:** `CKP_010_en_and_vi_i18n_coverage_complete`
