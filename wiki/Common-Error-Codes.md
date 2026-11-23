# Common Error Codes

Every error from the Rule Engine (`/api/bpm/rules/v1`), the Process Engine (`/api/bpm/processes/v1`) and the host metadata endpoints (`/api/bpm/meta`, `/api/bpm/me`, [[Host Metadata and Permissions]]) is an RFC 9457 `application/problem+json` body with a stable `code`. This page has one heading per code. The `type` URI of a problem points at that heading, for example `https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#unique_hit_violation` (ADR-0010). Branch on `code`, never on `type`. _Design preview: the codes are reviewed here before the code exists._

```json
{
  "type": "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#version_superseded",
  "title": "A newer version is already active",
  "status": 409,
  "code": "VERSION_SUPERSEDED",
  "detail": "kyc-onboarding v3 cannot be approved: v4 is ACTIVE.",
  "instance": "/api/bpm/processes/v1/definitions/kyc-onboarding/versions/3:approve",
  "correlationId": "01JD3A7Q2M5N8P1R4S6T9V0W2X"
}
```

`409` means a state conflict that a client resolves by reloading. `422` means the request was well formed but cannot succeed as it stands; retrying the same request never helps. Endpoints per code: [[Rule Engine Decision API]] for rules, `process-engine/docs/DESIGN.md` section 6.2 for processes.

## Index

| HTTP | `code` | Engine | From |
|---|---|---|---|
| 400 | [INPUT_INVALID](#input_invalid) | both | M1 |
| 400 | [QUERY_INVALID](#query_invalid) | both | M1 |
| 400 | [ROLE_FAN_OUT_EXCEEDED](#role_fan_out_exceeded) | processes | 0.3.0 |
| 400 | [CONTENT_HASH_REQUIRED](#content_hash_required) | rules | 0.3.0 |
| 401 | [UNAUTHENTICATED](#unauthenticated) | both | M1 |
| 403 | [PERMISSION_DENIED](#permission_denied) | both | M1 |
| 403 | [TENANT_NOT_ALLOWED](#tenant_not_allowed) | both | 0.3.0 |
| 403 | [SELF_APPROVAL_FORBIDDEN](#self_approval_forbidden) | both | M1 |
| 403 | [CLOCK_NOT_ALLOWED](#clock_not_allowed) | rules | M1 |
| 403 | [DRAFT_AUTHORING_DISABLED](#draft_authoring_disabled) | both | rules from 0.2.0 (`PUT` of a draft); processes M2 |
| 404 | [NO_EFFECTIVE_VERSION](#no_effective_version) | both | M1 |
| 404 | [RULE_SET_NOT_FOUND](#rule_set_not_found) | both | rules M1; processes M3 (candidate of an agent replay) |
| 404 | [VERSION_NOT_FOUND](#version_not_found) | rules | M1 |
| 404 | [DECISION_NOT_FOUND](#decision_not_found) | rules | M1 |
| 404 | [SIMULATION_REPORT_NOT_FOUND](#simulation_report_not_found) | rules | 0.3.0 |
| 404 | [DEFINITION_NOT_FOUND](#definition_not_found) | processes | M2 |
| 404 | [INSTANCE_NOT_FOUND](#instance_not_found) | processes | M2 |
| 404 | [TASK_NOT_FOUND](#task_not_found) | processes | M2 |
| 404 | [REPLAY_NOT_FOUND](#replay_not_found) | processes | M3 |
| 404 | [MIGRATION_NOT_FOUND](#migration_not_found) | processes | 0.3.0 |
| 404 | [LAYOUT_NOT_FOUND](#layout_not_found) | processes | 0.3.0 |
| 404 | [INCIDENT_NOT_FOUND](#incident_not_found) | processes | 0.3.0 |
| 404 | [CALENDAR_NOT_FOUND](#calendar_not_found) | processes | 0.3.0 |
| 400 | [CONTENT_HASH_REQUIRED](#content_hash_required) | processes | 0.3.0 |
| 409 | [CONTENT_CHANGED](#content_changed) | processes | 0.3.0 |
| 409 | [CHANGE_SET_TRUNCATED](#change_set_truncated) | processes | 0.3.0 |
| 422 | [OUTPUT_INVALID](#output_invalid) | processes | 0.3.0 |
| 422 | [LAYOUT_INVALID](#layout_invalid) | processes | 0.3.0 |
| 422 | [MIGRATION_PLAN_INVALID](#migration_plan_invalid) | processes | 0.3.0 |
| 409 | [DRY_RUN_REQUIRED](#dry_run_required) | processes | 0.3.0 |
| 409 | [VERSION_CONFLICT](#version_conflict) | both | M1 |
| 409 | [CONTENT_CHANGED](#content_changed) | rules | 0.3.0 |
| 409 | [INVALID_STATE](#invalid_state) | both | M1 |
| 409 | [ASSIGNEE_NOT_ALLOWED](#assignee_not_allowed) | processes | 0.3.0 |
| 409 | [IDEMPOTENCY_IN_FLIGHT](#idempotency_in_flight) | both | M1 |
| 409 | [RULE_SET_EXISTS](#rule_set_exists) | rules | M1 |
| 409 | [VERSION_SUPERSEDED](#version_superseded) | processes | M2 |
| 409 | [LAST_VERSION_IN_EFFECT](#last_version_in_effect) | both | 0.2.0 |
| 409 | [WARNINGS_NOT_ACKNOWLEDGED](#warnings_not_acknowledged) | rules | 0.2.0 |
| 409 | [AUTHOR_UNKNOWN](#author_unknown) | rules | 0.2.0 |
| 409 | [SIMULATION_REPORT_REQUIRED](#simulation_report_required) | rules | 0.3.0 |
| 409 | [SIMULATION_REPORT_OUT_OF_DATE](#simulation_report_out_of_date) | rules | 0.3.0 |
| 413 | [PAYLOAD_TOO_LARGE](#payload_too_large) | rules | M1 |
| 422 | [VALIDATION_FAILED](#validation_failed) | both | M1 |
| 422 | [IDEMPOTENCY_KEY_REUSE_MISMATCH](#idempotency_key_reuse_mismatch) | both | M1 |
| 422 | [UNIQUE_HIT_VIOLATION](#unique_hit_violation) | rules | M1 |
| 422 | [EVALUATION_ERROR](#evaluation_error) | rules | M1 |
| 422 | [REPLAY_NOT_POSSIBLE](#replay_not_possible) | rules | M1 (0.2.0) |
| 422 | [BATCH_TOO_LARGE](#batch_too_large) | rules | M1 |
| 422 | [HISTORY_TOO_LARGE](#history_too_large) | rules | 0.3.0 |
| 422 | [EVIDENCE_TOO_LARGE](#evidence_too_large) | rules | M3 |
| 422 | [READINGS_TOO_LARGE](#readings_too_large) | rules | M3 |
| 422 | [DEFINITION_TOO_LARGE](#definition_too_large) | processes | M2 |
| 422 | [GUARDRAIL_CONTRACT_INVALID](#guardrail_contract_invalid) | processes | M2 |
| 422 | [REPLAY_TOO_LARGE](#replay_too_large) | processes | M3 |
| 422 | [FORM_VALIDATION_FAILED](#form_validation_failed) | processes | M2 |
| 422 | [LAYOUT_INVALID](#layout_invalid) | processes | M3 |
| 422 | [CMMN_UNSUPPORTED](#cmmn_unsupported) | both | 0.2.0 |
| 422 | [BPMN_UNSUPPORTED_ELEMENT](#bpmn_unsupported_element) | processes | M3 |
| 422 | [DMN_UNSUPPORTED_ELEMENT](#dmn_unsupported_element) | rules | 0.2.0 |
| 422 | [EXPRESSION_LANGUAGE_UNSUPPORTED](#expression_language_unsupported) | rules | 0.2.0 |
| 422 | [BPMN_UNSAFE_XML](#bpmn_unsafe_xml) | processes | M3 |
| 428 | [PRECONDITION_REQUIRED](#precondition_required) | both | M1 |
| 429 | [RATE_LIMITED](#rate_limited) | both | M1 |
| 500 | [INTERNAL_ERROR](#internal_error) | both | M1 |
| 503 | [SCHEMA_NOT_READY](#schema_not_ready) | rules | M1 |

## Codes on every endpoint

### INPUT_INVALID
`400`. The body is malformed JSON, an input is missing or has the wrong type, or a money amount is a float, a string or outside plus or minus 2^53 - 1. Fix the request; `detail` names the field.

### QUERY_INVALID
`400`. A list query breaks a read limit (ADR-0015): `limit` below 1 or above `query.max-page-size` (200 by default), a `cursor` the list did not issue to this tenant (cursors are signed, so an edited, forged or replayed cursor is refused), a time range longer than 31 days (`GET /instances` with `startedFrom` and `startedTo`), a `status` that is not an instance status, a cursor sent with other filters than the page it came from, `GET /decisions` without `ruleSet`, `from` and `to`, or a `:simulate-history` request whose period is missing, reversed, longer than `query.max-range` or not ISO 8601 instants, or whose `limit` is outside 1 to `simulation.max-inputs`. Narrow the query and page with the cursor, passing `nextCursor` back unchanged.

### ROLE_FAN_OUT_EXCEEDED
`400`, Process Engine. `GET /tasks` would read more role and group queues than `query.max-roles` allows (100 by default): each role and group of the caller is a queue the list reads, so a very long role list is refused instead of fanning one request out. The problem carries `cap` and `provided`. Name one `role` or `group` to narrow the list to one queue, ask for `assignment=mine`, or have the host trim the caller's role list; an operator may raise the cap to at most 1,000.

### UNAUTHENTICATED
`401`. No principal, a token whose audience is another host, or a principal with no tenant the `TenantResolver` can read (for example no `tenant_id` attribute). Sign in again, request a token for this host, or give the user a tenant in your identity provider. Every `401` carries `WWW-Authenticate`; when the challenge says `error="invalid_token"`, the account has no tenant on this host, so sign in again at most once. The host returns this body from its security chain when the chain uses `BpmProblemAuthenticationEntryPoint` ([[Host Metadata and Permissions]]).

### PERMISSION_DENIED
`403`. The principal lacks the permission the endpoint checks. Map the permission to the caller's role; see [[Multi-Tenancy and Security]]. The host also answers this code, on `/api/bpm/me` and every engine endpoint, to a caller whose id starts with `system:`, the prefix the engines reserve for their own actions, and writes the security log entry `caller.reserved.refused`; give that user another id in your identity provider ([[Host Metadata and Permissions]]).

### TENANT_NOT_ALLOWED
`403`. `X-Tenant-Id` names a tenant outside the token's allowed-tenants claim, or differs from the resolved tenant while tenant switching is off; a host resolver returns a tenant other than the principal's tenant attribute; or the tenant is outside the allowed format. No data is returned and the host writes the security log entry `tenant.switch.refused` (ADR-0014). Ask your identity provider administrator to add the tenant to your token.

### IDEMPOTENCY_IN_FLIGHT
`409` with `Retry-After: 1`. A request with the same `Idempotency-Key` is still running, for example after a double click or a client retry that came too soon. Nothing was done for this request. Wait the `Retry-After` seconds and send the same request with the same key: once the first request has finished, the retry returns its original status and body, and nothing is created twice (ADR-0006).

### IDEMPOTENCY_KEY_REUSE_MISMATCH
`422`. The `Idempotency-Key` was used before with a different request body. This is a client defect, so retrying the same request never helps. Use a new key for a new request.

### CALENDAR_NOT_FOUND
`404`. Unknown calendar key or calendar change id, or one of another tenant. Nothing is written by a command that answers it. A task whose `horus:calendar` the tenant does not have fails its step instead (`422 STEP_FAILED`, reason `CALENDAR_NOT_FOUND`).

### CONTENT_HASH_REQUIRED
`400`. `:approve` or `:reject` did not send `expectedCanonicalHash`, the `canonicalHash` of the content the reviewer saw, and `horus.bpm.rules.approval.require-content-hash` is on (the default, `RLE-084`, PD-139). Send the hash the reviewer's screen showed when they opened the version. Nothing is written. A host on the `dev` profile can turn the requirement off; any other profile refuses to start with it off.

### CONTENT_CHANGED
`409`. The `expectedCanonicalHash` on `:approve` or `:reject` is not the hash of the content now stored: the version was withdrawn, replaced and requested again under the same number, even though `If-Match` may carry the current lock version. Nothing is written, and the problem carries `currentCanonicalHash` and `currentLockVersion`. Show the reviewer the current content and diff and let them decide again; never retry on your own with the new hash, because that would approve content nobody reviewed (`RLE-084`).

### VERSION_CONFLICT
`409`. `If-Match` no longer matches: someone changed the draft, task, incident or layout after you read it. Reload and apply your change again. A stale lock version on `:approve` or `:reject` is refused with this code too, and the problem names the version now stored in `currentCanonicalHash` and `currentLockVersion`. A changed content hash is a different code, [CONTENT_CHANGED](#content_changed), so a client that refreshes and retries on `VERSION_CONFLICT` cannot re-bind a decision to content nobody reviewed.

### INVALID_STATE
`409`. The action does not fit the current state, for example `:approve` on a `DRAFT`. Follow the lifecycle order.

### ASSIGNEE_NOT_ALLOWED
`409`. `:reassign` or `:delegate` named a user who may not work the task: they lack the task's role or group, they started the instance or completed an earlier task of it and the task is four-eyes, they already hold the task, or the name is reserved for the engine. Nothing is written. Pick another colleague. If every role or group task is refused, the host has not supplied an `AssigneeDirectory`. The body says which rule refused in `reason` (#442): `ALREADY_ASSIGNED`, `FOUR_EYES_PARTICIPANT`, `RESERVED_IDENTITY`, `MISSING_ROLE` or `MISSING_GROUP`; for the last two, `required` names the role or group the target lacks (`role:CARD_OFFICER`), whose readable name `GET /api/bpm/display-names` returns.

### RATE_LIMITED
`429` with `Retry-After`. The Rule Engine returns it for the masked-input search past `masking.search.max-per-minute` searches per caller per minute (default 30); the refusal is audited. Otherwise it is returned only when the host configures a rate limiter. Wait the given seconds and retry.

### INTERNAL_ERROR
`500`. Unexpected failure. The body carries only the fixed `detail` text and the `correlationId`, never a stack trace, class name, SQL or table name. Give the `correlationId` to the host's operators: the server log has the same id with the cause. Send `X-Correlation-Id` with your request to choose the id yourself.

### PRECONDITION_REQUIRED
`428`. A command that must be conditional was sent without the header it needs: `If-Match` on a Rule Engine command (`PUT` of a draft, `:approve`, `:reject`, `:withdraw` or `:retire`), `If-Match` and `Idempotency-Key` on a Process Engine task completion or incident command, or a business key or `Idempotency-Key` on a Process Engine instance start. An approval must name the lock version of the content the approver reviewed. Read the resource, then send its `ETag` as `If-Match: "<lockVersion>"` (and a new `Idempotency-Key` where asked), so the command cannot overwrite a change you have not seen or run twice. `:simulate` writes nothing and needs neither header.

## Versions and approvals

### WARNINGS_NOT_ACKNOWLEDGED
`409`. The version carries a warning the host requires the approver to accept, for example an input that looks like personal data and would be logged without masking (PD-104). The body lists the `warnings` (`code`, `input`, `matchedName`) and the codes the host requires (`setting`, from `lifecycle.acknowledge-warnings`). Nothing was published.
- **What to do:** read the warnings. To publish anyway, send the approval again with exactly the pairs in `warnings`, for example `"acknowledgedWarnings": [{ "code": "UNMASKED_PERSONAL_DATA", "input": "nationalId" }]`. The engine accepts only the exact current set; the acceptance is recorded on the approval history row and the published event.
- **What not to do:** a client never retries on its own with the codes added. Otherwise nobody would have accepted the risk.

### SIMULATION_REPORT_REQUIRED
`409`. The host lists the rule set in `lifecycle.require-simulation-report` and the `:request-publish` call carried no `simulationReportId`. Run `POST .../versions/{v}/simulation-reports` on the draft and send the report's id. The draft stays a draft; nothing was written.

### SIMULATION_REPORT_OUT_OF_DATE
`409`. The report was run on content the version no longer has, because the draft was replaced after the report. Run a new report on the draft as it is now and attach that. The draft stays a draft; the old report is kept and reads `status: OUT_OF_DATE`.

### SIMULATION_REPORT_NOT_FOUND
`404`. The version has no report with that id in your tenant. It is the same answer for a report of another tenant, another rule set or another version, so the code never says whether one exists.

### AUTHOR_UNKNOWN
`409`. The pending rule set version has no recorded author, so four eyes cannot tell whether the approver made it (PD-105). Only versions written before the author was recorded (schema V2) have none. Nobody can approve it, and the engine never assigns an author. Import the change again, which records you as its author, and have someone else approve that version; reject or withdraw the old one.

### SELF_APPROVAL_FORBIDDEN
`403`. The approver is the identity that requested the change, or a task's four-eyes rule forbids the caller. Enforced in the service layer whatever the caller's permissions (`TR-SEC-04`). Ask a different identity to approve. A requester who rejects their own rule set request gets this code too, with a detail pointing to `:withdraw`: take the request back with `POST .../versions/{v}:withdraw` instead (PD-089). The requester of a four-eyes retirement who confirms it gets this code too: another holder of `rules:approve` confirms (`:confirm-retire`), or the request is cancelled with `:cancel-retire`.

### NO_EFFECTIVE_VERSION
`404`. No approved version is effective at the clock: nothing is published or active yet, the effective date is in the future, the last version was retired, or the request names a tenant with no version. Publish or approve a version, or check the tenant.

### SCHEMA_NOT_READY
`503`. The engine's schema was not current at start-up and `horus.bpm.rules.migrate-on-start` is `false`, so the list cursor key was not loaded; `detail` names the schema version and the pending migrations. Run the engine migrations and restart the host. Only `GET /rule-sets` and `GET /rule-sets/{name}/versions` answer it.

### VALIDATION_FAILED
`422`. A rule set or process definition does not parse or type-check; `violations` lists each problem with its location. Fix the document and save again.

For rule sets every entry is `{ "ruleId", "column", "message", "code", "position" }`: `ruleId` is the rule (row), `column` the input or output name, and either is `null` when the problem is outside them; `message` carries no location prefix; `code` is stable and `position` is the zero-based character in the cell text as written, both `null` when they do not apply. Every cell problem of the document is listed at once, in document order, up to 100 entries (`violationsTruncated: true` when there were more), so an editor can mark each cell; a structural problem (an unknown property, a missing key) stops reading and is the only entry. Example: `[{ "ruleId": "R3", "column": "fee", "message": "output fee is money and needs a currency: write 30000 followed by its ISO 4217 currency code", "code": "TYPE_MISMATCH", "position": null }]`.

For process definitions, a request for approval and an approval also name each step whose binding the host cannot satisfy: `HANDLER_NOT_REGISTERED`, `ADAPTER_NOT_REGISTERED`, and `ESCALATION_ROLE_UNKNOWN` (an escalation `role:` the tenant's `RoleCatalogue` does not list; the problem names the step and the role, for example `review-application -> role:RISK_HEAD`). Nothing is written and the version keeps its status.

Codes of expression problems, in `when` and `then` cells. Each is refused at import, at draft replacement, by `VersionService.validate` and at `:request-publish`, which checks a stored draft again so a draft stored before a check existed stays a draft:

| `code` | Meaning | Example |
|---|---|---|
| `UNKNOWN_INPUT` | A name that is not a declared input | `creditScoreX > 600`: `Unknown input 'creditScoreX' at position 0` |
| `TYPE_MISMATCH` | Operands an operator does not take, a condition that is not true or false, or a cell of the wrong type for its output | `balance + 100 > 0` with money `balance`: `+ is not defined for money and number: write the number with a currency, for example 100 VND at position 8` |
| `FORBIDDEN_CONSTRUCT` | A call of a function the language does not have, member access (`.`), a statement character (`;`, `{`, `}`, `#`, `@`, `$`), or a word such as `new` or `class`. Refused before anything inside it is read, bound or evaluated | `T(java.lang.Runtime).getRuntime()`: `'.' is not part of the expression language ... at position 6` |
| `SYNTAX_ERROR` | Not an expression: an unterminated string, a missing parenthesis, `=` for `==`, `&&` for `and` | `tier == 'GOLD`: `Unterminated string at position 8` |
| `INVALID_LITERAL` | A malformed date, datetime, money amount or `round` scale | `10.5 VND` (VND has no minor unit) |
| `INVALID_PATTERN` | A `matches()` pattern outside the dialect or not a regular expression | `matches(ref, '(?i)ft')` |
| `EXPRESSION_TOO_LONG`, `EXPRESSION_TOO_DEEP` | More than 4,096 characters, or a tree deeper than 64 levels, chains of `+`, `*`, `and` or `or` included | `balance + 1 VND + ... > 0 VND` with 64 additions |
| `EVALUATION_BUDGET_EXCEEDED` | The table's worst case is over the step budget, so some decisions on it could fail every time. On the first rule from which it is over; `column` is null | A 5,000-rule `unique` tariff over 8 inputs: `the table can take up to 155,001 evaluation steps under hit policy unique, more than the step budget of 100,000 ...` on `T3226` |
| `UNKNOWN_OUTPUT`, `MASKED_INPUT_IN_OUTPUT` | A `then` key that is not a declared output; an output cell that reads a masked input | |

New codes may be added; a client should show `message` for a code it does not know.

Under strict mode (`decision-log.require-explicit-mask: true`) an input that declares no `mask` is one entry with `ruleId: null` and `column` the input name, at import, draft replacement, publication request and approval: `[{ "ruleId": null, "column": "nationalId", "message": "declares no mask, and decision-log.require-explicit-mask requires every input to declare mask: true or mask: false" }]`. Add `mask: true`, or `mask: false` if the input holds no personal data.

### PAYLOAD_TOO_LARGE
`413`. The request body is larger than the endpoint reads: 16,384 bytes for `:approve`, `:reject`, `:withdraw` and `:retire`, 1,048,576 bytes for `:evaluate` and for imports. A declared `Content-Length` over the limit is refused before the body is read. Shorten the comment or reason, or split the inputs.

### LAST_VERSION_IN_EFFECT
`409`. The retirement would leave no version of the rule set or definition key in effect. After it, evaluations or new starts would fail until a new version is approved (PD-101).
- **Body.** Besides `detail`, the body carries the rule set or definition key, `version`, `setting` and `nextEffectiveFrom`. `setting` is the value of `lifecycle.retire-last-version`; `nextEffectiveFrom` is the start of a later approved version, or null.
- **What to do under `confirm`, the default.** If stopping it is intended, send the retire again with `"confirmNoVersionInEffect": true`.
- **What to do under `refuse`.** Approve a replacement first, then retire.

### VERSION_SUPERSEDED
`409`. Processes only. An older pending version cannot be approved because a version with a higher number is already `ACTIVE` (ADR-0012). Create a new draft from the effective version and request approval again.

### DRAFT_AUTHORING_DISABLED
`403`. The host keeps definition changes in its release pipeline. Rules: `horus.bpm.rules.authoring.cockpit-drafts` is `false` (the 0.1.0 default, PD-094), so `PUT /rule-sets/{name}/versions/{v}` is refused; importing a document as a new version, restoring an earlier version as a draft (`?copyOf=`), approvals and simulation still work. Processes: `horus.bpm.processes.authoring.cockpit-drafts: false` refuses creating or editing drafts through the `processes:author` endpoints; imports with `processes:deploy`, approvals and layout saves still work. Change the definition in Git and let the release pipeline import it.

## Rule Engine

### CLOCK_NOT_ALLOWED
`403`. `clock` is set on `:evaluate` or `/versions/{v}:evaluate` without `rules:simulate`. A chosen instant reaches versions and dates the caller would not get at the host clock. Drop `clock`, or use an identity with `rules:simulate`. Nothing is logged.

### RULE_SET_NOT_FOUND
`404`. No rule set of that name in the caller's tenant. Another tenant's rule set also answers `404`, so names do not leak.

### VERSION_NOT_FOUND
`404`. The rule set has no version with that number in the caller's tenant. On `:simulate` a version of another tenant answers the same, so versions do not leak.

### DECISION_NOT_FOUND
`404`. Unknown decision id, or a decision of another tenant, on `GET /decisions/{id}` or `POST /decisions/{id}:replay`. Ids of other tenants answer the same, so they do not leak.

### RULE_SET_EXISTS
`409`. `POST /rule-sets` with a name already used in this tenant. Add a version to the existing rule set instead.

### UNIQUE_HIT_VIOLATION
`422`. A `unique` table matched several rows for these inputs (`TR-DET-04`). Zero matches is not this error: it is no match, as with `first`. This is an authoring defect in the published rule set, so retrying never helps (ADR-0010). The decision is still logged with this outcome. Remove the overlap, or switch to `first` if order is intended. The problem body carries `decisionId` and `matchedRuleIds`, for example `"matchedRuleIds": ["R1", "R2"]`; it never carries partial outputs.

### REPLAY_NOT_POSSIBLE
`422`. `POST /decisions/{id}:replay` for a decision that used a masked input (`mask: true`) with a value, or that called a host function whose version label is not the one registered now. The decision log keeps a masked value only as `masked`, so the decision cannot be evaluated again; a function with another version could give another result. `detail` names the inputs, or the function and both version labels, never their values. Explain the decision from its log entry instead. Nothing is written.

### EVALUATION_ERROR
`422`. Evaluation failed: `detail` names `CURRENCY_MISMATCH`, `DIVISION_BY_ZERO`, `REGEX_TIMEOUT`, `EVALUATION_BUDGET_EXCEEDED`, `FUNCTION_FAILED` or `FUNCTION_CHANGED`. The decision is logged with this outcome. When the rule set masks an input and the tenant has no masking key, the decision is refused without being evaluated: the row has `error_code = MASKING_KEY_MISSING` and no inputs, and `detail` names `horus.bpm.rules.masking.hmac-key`. When an output reads an input that the tenant's masking default masks, the decision is refused the same way with `error_code = MASKED_INPUT_IN_OUTPUT`; an output that reads an input declared `mask: true` is refused at import instead.

- `REGEX_TIMEOUT`: a `matches()` read more than `evaluation.regex-max-reads` characters (default 1,000,000), the `matches()` calls of one decision together read more than `evaluation.regex-max-reads-per-decision` (default 5,000,000; `detail` names that setting), the decisions of one request of many items (a simulation) together read more than `evaluation.regex-max-reads-per-request` (default 50,000,000; `detail` names that setting, and the items after it are reported `REGEX_TIMEOUT: not evaluated`), or a `matches()` ran longer than `evaluation.regex-timeout-ms` (default 50 ms), and was stopped. `detail` starts with `REGEX_TIMEOUT:` and the decision row has `error_code = REGEX_TIMEOUT`. Retrying the same input fails the same way; simplify the pattern (avoid nested quantifiers such as `(a+?)+?`).
- `REGEX_INPUT_TOO_LONG`: the text given to `matches()` is longer than `evaluation.regex-max-input-length` (default 4,096); the match was not run. `detail` starts with `REGEX_INPUT_TOO_LONG:` and so does the row's `error_code`.
- `EVALUATION_BUDGET_EXCEEDED`: the decision visited more than 100,000 nodes of its expressions (the step budget), or ran longer than the 10-second backstop. The problem's `limit` says which: `STEPS` is counted, not timed, so the same input fails the same way on every machine and the rule set must change; `TIME` means the machine was slow. `detail` starts with `EVALUATION_BUDGET_EXCEEDED:` and so does the row's `error_code`. Import and `:request-publish` already refuse a table whose worst case is over the budget, so `STEPS` at evaluation means the budget was lowered after the version was approved. A step is one node visited; the exact count of an expression may change between minor releases. One budget covers one top-level evaluation and every decision it requires.
- `FUNCTION_FAILED`: a function the host registered threw an exception or returned a value of another type than it declared. The problem and the decision row name the function and never the exception. The server log has the exception and the correlation id. Fix the function; the decision fails closed with no outputs.
- `FUNCTION_CHANGED`: only when the host sets `evaluation.function-version-change` to `refuse`. The function's version label is not the one the rule set version was approved with. Approve a new version of the rule set, by a second person, to go on.
- `REGEX_TOO_DEEP`: the pattern's backtracking ran out of thread stack, typically an alternation under `*` or `+` such as `(a|b)*c` on a long text. Simplify the pattern or lower `evaluation.regex-max-input-length`.

### BATCH_TOO_LARGE
`422`. `:evaluate-batch` with more than `evaluation.max-batch` items (1,000 at most). Split the batch; nothing was evaluated or written.

### HISTORY_TOO_LARGE
`422`. `POST /rule-sets/{name}:compare` over a period that holds more logged decisions than `simulation.max-history-decisions` (default 50,000, at most 200,000). `found` says how many the period holds (with `atLeast: true` when the count stopped at 200,000) and `maxHistoryDecisions` the setting in force. The comparison never takes a sample, so totals are never partial; narrow the period. Nothing was evaluated or written.

### EVIDENCE_TOO_LARGE
`422`. `POST /rule-sets/{name}/evidence` would produce a file over 8,388,608 bytes, or a chosen version has more than 10,000 history events. Export fewer decisions or versions at a time; nothing was written and no export was recorded.

### READINGS_TOO_LARGE
`422`. A request for plain-language readings covers more than 500 rows (`GET /rule-sets/{name}/versions/{v}/readings`, `GET .../diff/readings`) or 500 samples (`POST .../:simulate-readings`). Nothing is cut short; read one rule set or fewer samples at a time. Nothing was written. `:validate` never answers it: a valid draft of any legal size is `200`, with `readingsTruncated: true` above 500 rows.

## Process Engine

### DEFINITION_NOT_FOUND
`404`. No definition key or version of that number in the caller's tenant.

### INSTANCE_NOT_FOUND
`404`. Unknown instance id, or an instance of another tenant.

### TASK_NOT_FOUND
`404`. Unknown task id, or a task of another tenant.

### INCIDENT_NOT_FOUND
`404`. Unknown incident id, or an incident of another tenant. Nothing is written by a command that answers it.

### CONTENT_HASH_REQUIRED
`400`. An approval of a process definition version did not name `expectedDefinitionHash`, the `definitionHash` of the content the approver reviewed, while `horus.bpm.processes.approval.require-content-hash` is on (the default, PD-139). Nothing is read or written. Send the hash shown with the version you reviewed. Only the `dev` profile may turn the setting off.

### CONTENT_CHANGED
`409`. The stored content of the definition version is not the content the approver reviewed: `expectedDefinitionHash` differs from its `definitionHash`. The body carries `currentDefinitionHash` and `currentLockVersion`, and `currentChangeSetHash` when the change set you read page by page is no longer the change set now. Nothing is written, published or recorded. Show the approver the current content and its changes again; never retry with the new hash on your own, because that would approve content nobody reviewed. A change list that was cut off at its limit has no hash to approve, see [CHANGE_SET_TRUNCATED](#change_set_truncated).

### CHANGE_SET_TRUNCATED
`409`. An approval of a process definition version named a hash, but the change list from the effective version to this one has more than 1,000 changed values and is cut off, so nobody can have reviewed it complete (PD-139). The diff of such a pair has `approvableHash: null` and no `definitionHash` on either side. Nothing is written. Read the diff page by page (`limit` and `cursor`) and approve with `expectedChangeSetHash`, the `changeSetHash` of its last page; the unpaged diff stays cut off at 1,000 values and has no approvable hash.

### OUTPUT_INVALID
`422`. The output an operator proposed to resolve an incident breaks the step's output schema. `paths` lists each failing path and rule, for example `$.riskScore: expected integer`, never a value. Nothing is written. Correct the output and propose again.

### REPLAY_NOT_FOUND
`404`. Unknown agent replay run, a run of another tenant, or a case the run does not have. Runs are deleted `replay.retention` (30 days) after they were created.

### MIGRATION_NOT_FOUND
`404`. Unknown migration plan id, or a plan of another tenant (`GET /migrations/{id}`, `POST /migrations/{id}:validate`, `:execute`, `:approve`, `:reject`, `/execution`, `/instances`). Nothing is written.

### DRY_RUN_REQUIRED
`409`. A migration is proposed or approved without a passing dry run. The engine runs the dry run itself: the plan has conflicts (the detail gives their number), or the dry run of the proposal is older than `migration.dry-run-validity` when the second person approves. Nothing is written. Resolve the conflicts, or propose the execution again, then approve.

### MIGRATION_PLAN_INVALID
`422`. The step instructions of a migration plan cannot be carried out: a mapping to a step of another type or to a step the target version lacks, a step mapped or cancelled twice, a cancelled step the target still has, a cancellation without a `reason` (1 to `lifecycle.reason-max-length` characters) or without a `moveTo` the target has, or more than 200 mappings or cancellations. `paths` lists each problem naming the step ids. Nothing is written. A removed step that no instruction handles is not this error: the dry run lists it as a conflict. Correct the plan and create it again.

### Step failures of a process call
A step that calls another process (0.3.0) raises an incident, not an HTTP error, when the call cannot run or the child does not complete: `NO_EFFECTIVE_VERSION` (the called definition has no effective version, or the pinned version is not active, in the tenant), `CALL_DEPTH_EXCEEDED` (the call would sit deeper than `calls.max-depth`, at most 5), `CHILD_FAILED`, `CHILD_CANCELLED` (the cause names the child's cancellation reason) and `OUTPUT_VARIABLE_MISSING` (the child did not set a declared output). Retry the incident to start a new child. A migration outcome `SKIPPED` with the code `STEP_IN_FLIGHT` means a call was in flight at a removed step; a later plan moves the instance.

### LAYOUT_NOT_FOUND
`404`. The version has no such layout revision (`GET .../layout?revision=n`). A version with no layout at all answers `204`, not an error.

### LAYOUT_INVALID
`422`. A layout document breaks the layout schema, or names a step the definition version does not have. `paths` lists each problem as a path and a reason, for example `$.nodes.ghost-step names a step that is not in the definition`, never a value. Nothing is written. Correct the document and save again over the same `If-Match`.

### REPLAY_TOO_LARGE
`422`. The period of an agent replay holds more recorded answers than `horus.bpm.processes.replay.max-cases` (50,000). The body carries `cases` and `limit`. Nothing is written. Choose a shorter period.

### DEFINITION_TOO_LARGE
`422`. The definition has more than 500 steps or is larger than 1 MB, or exceeds the lower limit the host set in `horus.bpm.processes.definitions.max-nodes` or `definitions.max-bytes` (`TR-DEF-05`). The body names the limit and the value, for example `{ "limit": "max-nodes", "value": 501 }`. Nothing is written. Split the process with `callProcess`.

### GUARDRAIL_CONTRACT_INVALID
`422`. An `agentTask` names a guardrail rule set that does not meet the contract: it exists in your tenant with a version in effect, has hit policy `first` or `unique`, declares the output `allow: boolean`, may declare `reasons: string`, and reads only inputs the step's `outputSchema` declares with a compatible type. Checked at import, at request-approval and at approval, against the version in effect then; nothing is written. `detail` and each entry of `problems` name the step id and the failed check, for example `step assess-documents: guardrail rule set agent-output-guardrails v1 declares no output allow: boolean (outputs decision: string)`. Publish a compliant version of the rule set, then deploy again. An agent replay checks its candidate version against the same contract before it creates a run (`POST /agent-replays`).

### FORM_VALIDATION_FAILED
`422`. A task completion does not match the task's form schema. The body carries a JSON Pointer to each failing field, for example `/reason`. Correct the field and complete again. Since 0.2.0 the review task of a refused agent result checks the reviewer's answer against the step's output schema; its body carries `violations`, each a path and a rule such as `$.riskScore: expected integer`, and the task stays claimed.

### LAYOUT_INVALID
`422`. A layout save names a node id that is not in the definition, or exceeds 256 KB. Remove the unknown node or reduce the layout. Layout saves need no approval.

### CMMN_UNSUPPORTED
`422`. The file is a CMMN case model: its root element is in a CMMN namespace such as `http://www.omg.org/spec/CMMN/20151109/MODEL`. CMMN is not supported and not planned. The body has `detail` "CMMN is not supported; model the case as a BPMN process", the `namespace` and the `line` of the root element. The reader stops at the root: nothing else in the file is checked and nothing is stored. Model the case as a BPMN process and import that. Both engines answer it, at the BPMN import of the Process Engine and at the rule set import of the Rule Engine (`Content-Type: application/xml`). A root element in another unknown namespace, such as XPDL or BPEL, is `VALIDATION_FAILED` naming the namespace.

### BPMN_UNSUPPORTED_ELEMENT
`422`. A BPMN import contains an element, an attribute that changes how a step runs, or an expression language outside what the release executes. `unsupportedElements` lists each one in document order as `{ element, elementId, line, stage, message }`, up to 100 (`violationsTruncated: true` when there were more), the same field and item shape as `DMN_UNSUPPORTED_ELEMENT` (see the example below). `message` is plain English and may change; the element and the stage are the contract. A condition in another expression language is listed there with the element `conditionExpression`. `stage` is the stage of the [standards coverage table](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/architecture/standards-compliance.md) that adds it: `S1`, `S2`, `S3`, or `not planned`; `S0` marks an element the table lists for the current stage that this release has not finished. A condition in a language other than `urn:horus-bpm:expr:v1` has the problem code `BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED` (XPath and the other languages are `not planned`). Vendor attributes and extension elements are not refused: `warnings` lists them as `FOREIGN_EXTENSION_IGNORED` with the namespace and line, in a refusal and in the `201` answer of a successful import, and the stored source keeps them. Ids are printed without control characters and cut at 200 characters. Remove or replace each element and import again. See [[Process Engine Importing BPMN|Process-Engine-Importing-BPMN]].

### DMN_UNSUPPORTED_ELEMENT
`422`. A DMN import contains elements outside the current stage, such as a business knowledge model, a decision service, a boxed expression other than a decision table, or a wire from one decision to another. `unsupportedElements` lists each one in document order as `{ element, elementId, line, stage, message }`, up to 100 (`violationsTruncated: true` when there were more); `stage` is `S1`, `S2`, `S3` or `not planned`, from the DMN coverage table. Nothing is stored. Remove the elements, or keep one decision table per file.

One shape in both engines (PD-141), so a client needs one parser:

```json
{"status":422,"code":"DMN_UNSUPPORTED_ELEMENT","unsupportedElements":[
  {"element":"businessKnowledgeModel","elementId":"bkm-1","line":55,"stage":"S2",
   "message":"<businessKnowledgeModel id=\"bkm-1\"> is not supported; it is added in stage S2 (line 55)"}],...}
```

A BPMN refusal is the same, with `"code":"BPMN_UNSUPPORTED_ELEMENT"` and a BPMN element such as `inclusiveGateway`.

### EXPRESSION_LANGUAGE_UNSUPPORTED
`422`. A DMN file names an `expressionLanguage` or `typeLanguage` other than FEEL, for example JUEL or Groovy. `unsupportedElements` holds the element that names it with its `line` and `stage` (`not planned`). Nothing is stored.

### BPMN_UNSAFE_XML
`422`. A BPMN import contains a `DOCTYPE`, or exceeds 1 MB, nesting depth 32 or 5,000 elements. The file is refused before any transaction opens. Export the file again without a document type declaration.
