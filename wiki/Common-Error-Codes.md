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
| 401 | [UNAUTHENTICATED](#unauthenticated) | both | M1 |
| 403 | [PERMISSION_DENIED](#permission_denied) | both | M1 |
| 403 | [TENANT_NOT_ALLOWED](#tenant_not_allowed) | both | M1 on `/api/bpm/meta` and `/api/bpm/me` (switching off); M2 everywhere |
| 403 | [SELF_APPROVAL_FORBIDDEN](#self_approval_forbidden) | both | M1 |
| 403 | [CLOCK_NOT_ALLOWED](#clock_not_allowed) | rules | M1 |
| 403 | [DRAFT_AUTHORING_DISABLED](#draft_authoring_disabled) | processes | M2 |
| 404 | [NO_EFFECTIVE_VERSION](#no_effective_version) | both | M1 |
| 404 | [RULE_SET_NOT_FOUND](#rule_set_not_found) | rules | M1 |
| 404 | [VERSION_NOT_FOUND](#version_not_found) | rules | M1 |
| 404 | [DECISION_NOT_FOUND](#decision_not_found) | rules | M1 |
| 404 | [DEFINITION_NOT_FOUND](#definition_not_found) | processes | M2 |
| 404 | [INSTANCE_NOT_FOUND](#instance_not_found) | processes | M2 |
| 404 | [TASK_NOT_FOUND](#task_not_found) | processes | M2 |
| 409 | [VERSION_CONFLICT](#version_conflict) | both | M1 |
| 409 | [INVALID_STATE](#invalid_state) | both | M1 |
| 409 | [IDEMPOTENCY_IN_FLIGHT](#idempotency_in_flight) | both | M1 |
| 409 | [RULE_SET_EXISTS](#rule_set_exists) | rules | M1 |
| 409 | [VERSION_SUPERSEDED](#version_superseded) | processes | M2 |
| 413 | [PAYLOAD_TOO_LARGE](#payload_too_large) | rules | M1 |
| 422 | [VALIDATION_FAILED](#validation_failed) | both | M1 |
| 422 | [IDEMPOTENCY_KEY_REUSE_MISMATCH](#idempotency_key_reuse_mismatch) | both | M1 |
| 422 | [UNIQUE_HIT_VIOLATION](#unique_hit_violation) | rules | M1 |
| 422 | [EVALUATION_ERROR](#evaluation_error) | rules | M1 |
| 422 | [BATCH_TOO_LARGE](#batch_too_large) | rules | M1 |
| 422 | [DEFINITION_TOO_LARGE](#definition_too_large) | processes | M2 |
| 422 | [GUARDRAIL_CONTRACT_INVALID](#guardrail_contract_invalid) | processes | M2 |
| 422 | [FORM_VALIDATION_FAILED](#form_validation_failed) | processes | M2 |
| 422 | [LAYOUT_INVALID](#layout_invalid) | processes | M3 |
| 422 | [BPMN_UNSUPPORTED_ELEMENT](#bpmn_unsupported_element) | processes | M3 |
| 422 | [BPMN_UNSAFE_XML](#bpmn_unsafe_xml) | processes | M3 |
| 429 | [RATE_LIMITED](#rate_limited) | both | M1 |
| 500 | [INTERNAL_ERROR](#internal_error) | both | M1 |
| 503 | [SCHEMA_NOT_READY](#schema_not_ready) | rules | M1 |

## Codes on every endpoint

### INPUT_INVALID
`400`. The body is malformed JSON, an input is missing or has the wrong type, or a money amount is a float, a string or outside plus or minus 2^53 - 1. Fix the request; `detail` names the field.

### QUERY_INVALID
`400`. A list query breaks a read limit (ADR-0015): `limit` below 1 or above `query.max-page-size` (200 by default), a `cursor` the list did not issue to this tenant (cursors are signed, so an edited, forged or replayed cursor is refused), a time range longer than 31 days, or `GET /decisions` without `ruleSet`, `from` and `to`. Narrow the query and page with the cursor, passing `nextCursor` back unchanged.

### UNAUTHENTICATED
`401`. No principal, a token whose audience is another host, or a principal with no tenant the `TenantResolver` can read (for example no `tenant_id` attribute). Sign in again, request a token for this host, or give the user a tenant in your identity provider. Every `401` carries `WWW-Authenticate`; when the challenge says `error="invalid_token"`, the account has no tenant on this host, so sign in again at most once. The host returns this body from its security chain when the chain uses `BpmProblemAuthenticationEntryPoint` ([[Host Metadata and Permissions]]).

### PERMISSION_DENIED
`403`. The principal lacks the permission the endpoint checks. Map the permission to the caller's role; see [[Multi-Tenancy and Security]].

### TENANT_NOT_ALLOWED
`403`. `X-Tenant-Id` names a tenant outside the token's allowed-tenants claim, or differs from the resolved tenant while tenant switching is off; a host resolver returns a tenant other than the principal's tenant attribute; or the tenant is outside the allowed format. No data is returned and the host writes the security log entry `tenant.switch.refused` (ADR-0014). Ask your identity provider administrator to add the tenant to your token.

### IDEMPOTENCY_IN_FLIGHT
`409` with `Retry-After: 1`. A request with the same `Idempotency-Key` is still running. Retry after one second with the same key (ADR-0006).

### IDEMPOTENCY_KEY_REUSE_MISMATCH
`422`. The `Idempotency-Key` was used before with a different request body. Use a new key for a new request.

### VERSION_CONFLICT
`409`. `If-Match` no longer matches: someone changed the draft, task, incident or layout after you read it. Reload and apply your change again.

### INVALID_STATE
`409`. The action does not fit the current state, for example `:approve` on a `DRAFT`. Follow the lifecycle order.

### RATE_LIMITED
`429` with `Retry-After`. Returned only when the host configures a rate limiter. Wait and retry.

### INTERNAL_ERROR
`500`. Unexpected failure. The body carries only `code` and `correlationId`; give the `correlationId` to the host's operators to find the server log.

## Versions and approvals

### SELF_APPROVAL_FORBIDDEN
`403`. The approver is the identity that requested the change, or a task's four-eyes rule forbids the caller. Enforced in the service layer whatever the caller's permissions (`TR-SEC-04`). Ask a different identity to approve.

### NO_EFFECTIVE_VERSION
`404`. No approved version is effective at the clock: nothing is published or active yet, the effective date is in the future, the last version was retired, or the request names a tenant with no version. Publish or approve a version, or check the tenant.

### SCHEMA_NOT_READY
`503`. The engine's schema was not current at start-up and `horus.bpm.rules.migrate-on-start` is `false`, so the list cursor key was not loaded; `detail` names the schema version and the pending migrations. Run the engine migrations and restart the host. Only `GET /rule-sets` and `GET /rule-sets/{name}/versions` answer it.

### VALIDATION_FAILED
`422`. A rule set or process definition does not parse or type-check; `violations` lists each problem with its location. Fix the document and save again.

For rule sets every entry is `{ "ruleId", "column", "message" }`: `ruleId` is the rule (row), `column` the input or output name, and either is `null` when the problem is outside them; `message` carries no location prefix. Every cell problem of the document is listed at once, in document order, up to 100 entries (`violationsTruncated: true` when there were more), so an editor can mark each cell; a structural problem (an unknown property, a missing key) stops reading and is the only entry. Example: `[{ "ruleId": "R3", "column": "fee", "message": "output fee is money and needs a currency: write 30000 followed by its ISO 4217 currency code" }]`.

### PAYLOAD_TOO_LARGE
`413`. The request body is larger than the endpoint reads: 16,384 bytes for `:approve` and `:reject`, 1,048,576 bytes for `:evaluate` and for imports. A declared `Content-Length` over the limit is refused before the body is read. Shorten the comment or reason, or split the inputs.

### VERSION_SUPERSEDED
`409`. Processes only. An older pending version cannot be approved because a version with a higher number is already `ACTIVE` (ADR-0012). Create a new draft from the effective version and request approval again.

### DRAFT_AUTHORING_DISABLED
`403`. Processes only. The host sets `horus.bpm.processes.authoring.cockpit-drafts: false`, so creating or editing drafts through the `processes:author` endpoints is refused. Imports with `processes:deploy`, approvals and layout saves still work. Change the definition in Git and let the release pipeline import it.

## Rule Engine

### CLOCK_NOT_ALLOWED
`403`. `clock` is set on `:evaluate` without `rules:simulate`. Drop `clock`, or use an identity with `rules:simulate`.

### RULE_SET_NOT_FOUND
`404`. No rule set of that name in the caller's tenant. Another tenant's rule set also answers `404`, so names do not leak.

### VERSION_NOT_FOUND
`404`. The rule set has no version with that number.

### DECISION_NOT_FOUND
`404`. Unknown decision id, or a decision of another tenant.

### RULE_SET_EXISTS
`409`. `POST /rule-sets` with a name already used in this tenant. Add a version to the existing rule set instead.

### UNIQUE_HIT_VIOLATION
`422`. A `unique` table matched several rows for these inputs (`TR-DET-04`). Zero matches is not this error: it is no match, as with `first`. This is an authoring defect in the published rule set, so retrying never helps (ADR-0010). The decision is still logged with this outcome. Remove the overlap, or switch to `first` if order is intended. The problem body carries `decisionId` and `matchedRuleIds`, for example `"matchedRuleIds": ["R1", "R2"]`; it never carries partial outputs.

### EVALUATION_ERROR
`422`. Evaluation failed: `detail` names `CURRENCY_MISMATCH`, `DIVISION_BY_ZERO`, `REGEX_TIMEOUT` or `FUNCTION_ERROR`. The decision is logged with this outcome.

- `REGEX_TIMEOUT`: a `matches()` ran longer than `evaluation.regex-timeout-ms` (default 50 ms) and was stopped. `detail` starts with `REGEX_TIMEOUT:` and the decision row has `error_code = REGEX_TIMEOUT`. Retrying the same input fails the same way; simplify the pattern (avoid nested quantifiers such as `(a+?)+?`).
- `REGEX_INPUT_TOO_LONG`: the text given to `matches()` is longer than `evaluation.regex-max-input-length` (default 4,096); the match was not run. `detail` starts with `REGEX_INPUT_TOO_LONG:` and so does the row's `error_code`.

### BATCH_TOO_LARGE
`422`. `:evaluate-batch` with more than 1,000 items. Split the batch.

## Process Engine

### DEFINITION_NOT_FOUND
`404`. No definition key or version of that number in the caller's tenant.

### INSTANCE_NOT_FOUND
`404`. Unknown instance id, or an instance of another tenant.

### TASK_NOT_FOUND
`404`. Unknown task id, or a task of another tenant.

### DEFINITION_TOO_LARGE
`422`. The definition has more than 500 steps or is larger than 1 MB, or exceeds the lower limit the host set in `horus.bpm.processes.definitions.max-nodes` or `definitions.max-bytes` (`TR-DEF-05`). The body names the limit and the value, for example `{ "limit": "max-nodes", "value": 501 }`. Nothing is written. Split the process with `callProcess`.

### GUARDRAIL_CONTRACT_INVALID
`422`. An `agentTask` names a guardrail rule set that does not meet the contract: hit policy `first` or `unique`, output `allow: boolean`, optional `reasons: list<string>`. Checked at import, at request-approval and at approval. `detail` names the step id and the failed check.

### FORM_VALIDATION_FAILED
`422`. A task completion does not match the task's form schema. The body carries a JSON Pointer to each failing field, for example `/reason`. Correct the field and complete again.

### LAYOUT_INVALID
`422`. A layout save names a node id that is not in the definition, or exceeds 256 KB. Remove the unknown node or reduce the layout. Layout saves need no approval.

### BPMN_UNSUPPORTED_ELEMENT
`422`. A BPMN import contains an element outside the supported subset, such as a boundary event or an inclusive gateway. The body lists each element with its `id` and line, for example `boundaryEvent be-timeout line 42`. Remove or replace the element and import again.

### BPMN_UNSAFE_XML
`422`. A BPMN import contains a `DOCTYPE`, or exceeds 1 MB, nesting depth 32 or 5,000 elements. The file is refused before any transaction opens. Export the file again without a document type declaration.
