# Decision API

Reference for the two Rule Engine surfaces: the in-process Java API and the optional REST API under `/api/bpm/rules/v1` that the cockpit and remote callers use. _Design preview: the contract is reviewed here before the code exists._

## Available in 0.1.0-SNAPSHOT

These operations are built and tested; their contract file is [`rule-engine/api/openapi.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/rule-engine/api/openapi.yaml). Everything else on this page is still a design preview.

| Method and path | Java | Permission | Notes |
|---|---|---|---|
| `POST /rule-sets` | `VersionService.importRuleSet(ImportRequest)` | `rules:author` | Body is the rule set document: YAML (`application/yaml`), JSON (`application/json`) or a DMN 1.2 to 1.5 file with one decision table (`application/xml`, `text/xml` or `application/dmn+xml`). The source is kept as received, and the content type sets `sourceFormat`. Creates the rule set named in the document (for DMN, the decision id) and its version 1 as `DRAFT`. `201`, `ETag`, `Location` |
| `POST /rule-sets/{name}/versions` | `VersionService.importVersion(name, ImportRequest)` | `rules:author` | The next draft version; the document's `ruleSet` must equal `{name}` |
| `GET /rule-sets` | `VersionService.listRuleSets(PageRequest)` | `rules:read` | The caller's rule sets by name: `ruleSet`, `latestVersion`, `latestStatus`, `publishedVersion` (in effect now, or null), `lastChangedAt`. Pages as below |
| `GET /rule-sets/{name}` | `VersionService.findRuleSet(name)` | `rules:read` | The same summary for one rule set |
| `GET /rule-sets/{name}/versions` | `VersionService.listVersions(name, PageRequest)` | `rules:read` | Versions newest first, with `createdBy`, `requestedBy`, `approvedBy`, their times and `allowedActions`; no definition |
| `GET /rule-sets/{name}/versions/{v}` | `VersionService.find(name, v, includeSource)` | `rules:read` | `?include=source` adds `sourceDocument`, the file as imported, comments included |
| `GET /rule-sets/{name}/versions/{v}/history` | `VersionService.history(name, v)` | `rules:read` | `{ "data": [...] }`, oldest first: `action` (`CREATED`, `REQUESTED`, `APPROVED`, `REJECTED`, `WITHDRAWN`, `DRAFT_UPDATED`), `DRAFT_UPDATED`), `fromStatus`, `toStatus`, `actor`, `reason`, `lockVersion`, `occurredAt` |
| `POST /rule-sets/{name}/versions/{v}:request-publish` | `VersionService.requestPublish(...)` | `rules:author` | `If-Match` optional |
| `POST /rule-sets/{name}/versions/{v}:approve` | `VersionService.approve(...)` | `rules:approve` | `If-Match` required: the `lockVersion` of the version reviewed (`428 PRECONDITION_REQUIRED` without it, `409 VERSION_CONFLICT` when the version changed since, for example sent back, replaced and requested again; #275). On `409 VERSION_CONFLICT` the client shows the current version again and never retries automatically with the new `ETag`. Optional body `{ "comment": "..." }`; the requester gets `403 SELF_APPROVAL_FORBIDDEN` |
| `POST /decisions/{ruleSet}:evaluate` | `RuleEngine.evaluate(...)` | `rules:evaluate` | Body `{ "inputs": { ... } }`; hit policies `first`, `unique` and `collect`; one `rle_decision` row in the request's transaction. `unique` with several matches is `422 UNIQUE_HIT_VIOLATION` with `decisionId` and `matchedRuleIds` and no outputs; no match is `200` with outcome `NO_MATCH` |
| `POST /rule-sets/{name}/versions/{v}:reject` | `VersionService.reject(...)` | `rules:approve` | `If-Match` required, as for `:approve`. Body `{ "reason": "..." }`, required, at most `lifecycle.reason-max-length` (2,000) characters. A `PENDING_APPROVAL` version goes back to `DRAFT` with the request cleared, a `REJECTED` history row and `rules.version.rejected.v1`. The requester gets `403 SELF_APPROVAL_FORBIDDEN` pointing to `:withdraw` (PD-089) |
| `POST /rule-sets/{name}/versions/{v}:withdraw` | `VersionService.withdraw(...)` | `rules:author`, and only the requester | `Idempotency-Key` and `If-Match` required (`400 INPUT_INVALID` without the key, `428 PRECONDITION_REQUIRED` without `If-Match`). Optional body `{ "reason": "..." }`, at most 2,000 characters. A `PENDING_APPROVAL` version goes back to `DRAFT` with the request cleared, a `WITHDRAWN` history row and `rules.version.withdrawn.v1`; nothing is published. Anyone but the requester, an approver included, gets `403 PERMISSION_DENIED` (PD-089, `RLE-046`) |
| `PUT /rule-sets/{name}/versions/{v}` | `VersionService.replaceDraft(...)` | `rules:author`, and only the draft's author | Replaces a `DRAFT`'s document in place with the import checks; same version number, new hashes, `lockVersion` + 1; one `DRAFT_UPDATED` history row with both canonical hashes and one `rules.version.draft-updated.v1` event. `Idempotency-Key` and `If-Match` required. `403 DRAFT_AUTHORING_DISABLED` when `authoring.cockpit-drafts` is `false` (`RLE-047`) |
| `GET /rule-sets/{name}/versions/{v}/diff?base={w}` | `VersionService.diff(...)` | `rules:read` | Row-level diff of two versions from their canonical models, with header and column changes and changed literals marked `nonAscii`; writes nothing (`RLE-028`, `RLE-048`) |

Every `POST` accepts `Idempotency-Key` with the semantics below, and every state change writes an outbox row (`rules.version.created.v1`, `rules.version.draft-updated.v1`, `rules.version.publish-requested.v1`, `rules.version.published.v1`, `rules.version.rejected.v1`, `rules.version.withdrawn.v1`) and a history row. Every version response carries `allowedActions`, the lifecycle commands the caller may send now (`request-publish`, `approve`, `reject`, `withdraw`), computed from the status, the caller's permissions and four eyes; it is a hint for screens, and the command checks again. A refused document answers `422 VALIDATION_FAILED` with `violations` placed on rule and column ([[Common Error Codes]]); a `money` output written without a currency is refused at import. Command bodies over 16,384 bytes and evaluation bodies over 1,048,576 bytes are `413 PAYLOAD_TOO_LARGE`. A caller without the permission gets `403 PERMISSION_DENIED`, through the Java API as well as REST. The approver must be neither the requester nor the author (`createdBy`) of the version. Evaluate refuses an undeclared input name and a number with more than 34 significant digits or a scale outside `evaluation.max-input-scale` with `400 INPUT_INVALID`; only declared inputs are written to the decision log. Imports refuse unknown properties, YAML anchors, aliases and tags, numbers that the canonical form would change, and conditions that do not produce true or false. In this build: YAML, JSON and DMN 1.2 to 1.5 decision table import, and DMN 1.5 export (`?format=dmn`); the hit policies `first`, `unique` and `collect` as DMN 1.5 defines them ([[Rule Engine Authoring Decision Tables]]); located violations on a refused document, as above; no `clock`, `copyOf` or version-pinned evaluation. A runnable walkthrough is in [`examples/rule-engine-only/README.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-engine-only/README.md).

![Rule evaluation flow](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/rule-evaluation-flow.svg)

## Java

```java
public interface RuleEngine {
  DecisionResult evaluate(String ruleSet, DecisionInput input);
  DecisionResult evaluate(String ruleSet, int version, DecisionInput input);   // pin a version
  DecisionResult evaluate(DecisionRequest request);                            // tenant, clock, correlation id, caller, idempotency key
  <T> T evaluateAs(String ruleSet, DecisionInput input, Class<T> outputType);  // map outputs onto a record
}
```
```java
DecisionResult r = rules.evaluate("card-fee", DecisionInput.of("tier", "GOLD", "balance", Money.ofMinor("VND", 60_000_000), "txCount", 3));
r.ruleSet();            // "card-fee"
r.version();            // 2
r.matchedRuleIds();     // ["R2"]
r.output("fee", Money.class);
r.decisionId();         // ULID of the decision log row
r.outcome();            // Matched (sealed: Matched, NoMatch, Failed)
```

| Rule | Detail |
|---|---|
| Tenant and caller | From the host's `TenantResolver` and `CallerResolver` (defaults read Spring Security). `DecisionRequest.tenantId` is for batch jobs that run without a security context and requires the host to grant it explicitly |
| Clock | `DecisionRequest.clock` selects the effective version and feeds `today()` and `now()`; default is the engine `Clock` bean |
| Errors | `UNIQUE_HIT_VIOLATION` and `EVALUATION_ERROR` come back as a `Failed` outcome with `code()`, because they are logged decisions; `evaluateAs` throws `DecisionFailedException` with the same code. Other failures throw an unchecked `HorusBpmException` subtype whose `code()` equals the REST error code (ADR-0016) |
| Idempotency | `DecisionRequest.idempotencyKey` returns the stored decision instead of logging a second one (ADR-0006) |

## REST conventions

| Convention | Rule |
|---|---|
| Base path | `/api/bpm/rules/v1` (`rest.base-path`, keep the version segment when you change it). Breaking changes go to `/v2`; additive fields stay in `/v1` |
| Actions | Non-CRUD transitions use a `:verb` suffix: `:evaluate`, `:request-publish`, `:approve` |
| Identifiers | ULID strings, for example `01JD2X5K9T1Q4N8V2M7B3C6D0E` |
| Timestamps | ISO 8601 UTC, `2026-12-01T00:00:00Z` |
| Money | `{ "currency": "VND", "amount": 60000000 }`: ISO 4217 code and an integer in minor units. Never a JSON float. In Java, `Money.ofMinor("VND", 60_000_000)` or `Money.ofMajor("USD", "12.50")` |
| Tenant | Resolved by the `TenantResolver` from the authenticated principal, session or token, never from a header ([[Multi-Tenancy and Security]]). A `tenantId` field in any request body is ignored (`TR-SEC-03`, `RLE-011`). With tenant switching on, `X-Tenant-Id` may name another tenant from the token's allowed list; any other value is `403 TENANT_NOT_ALLOWED` |
| Authentication | Every endpoint requires an authenticated principal from the host's Spring Security; see [[Multi Tenancy and Security]] |
| Errors | RFC 9457 `application/problem+json` with a stable `code`; see the error matrix and [[Common Error Codes]]. Clients branch on `code`, never on `type` |
| Idempotency | `Idempotency-Key` header on every `POST` and `PUT`; semantics below |
| Optimistic locking | Drafts carry `version` (entity lock version). `PUT` requires `If-Match: "<version>"` or the `version` field; responses carry `ETag` |
| Pagination | Cursor-based: `?cursor=<opaque>&limit=50` (max 200), response `{ "data": [], "nextCursor": "...", "hasNext": true }`. `limit` above 200 is `400 QUERY_INVALID` |
| OpenAPI | `GET /api/bpm/rules/v1/openapi.yaml` (OpenAPI 3.1) |
| Expressions in definitions | Plain function form only: `startsWith(name, 'Mr')`, `len(s)`, `matches(s, '^[A-Z]')`. There is no method-call syntax (`name.startsWith('Mr')`), which keeps the grammar closed (ADR-0003) |

## Endpoints

| Method and path | Purpose | Permission | Headers |
|---|---|---|---|
| `POST /decisions/{ruleSet}:evaluate` | Evaluate the effective version at `clock` (default now) | `rules:evaluate`; `rules:simulate` when `clock` is set | `Idempotency-Key` optional |
| `POST /decisions/{ruleSet}/versions/{v}:evaluate` | Evaluate one approved version. A draft is `409 INVALID_STATE` (use `:simulate`); a retired version is `409 INVALID_STATE` unless `evaluation.pin-retired` is `true` | `rules:pin`, plus `rules:simulate` for a custom `clock` | `Idempotency-Key` optional |
| `POST /decisions/{ruleSet}:evaluate-batch` | Up to 1 000 input sets; one decision row each; partial failures reported per item | `rules:evaluate` | `Idempotency-Key` optional |
| `GET /rule-sets` | List rule sets with effective version and pending approvals | `rules:read` | cursor pagination |
| `GET /rule-sets/{name}` | Rule set with its versions and states | `rules:read` | |
| `GET /rule-sets/{name}/versions/{v}` | One version, canonical JSON, validation report | `rules:read` | `ETag` |
| `GET /rule-sets/{name}/versions/{v}/diff?base={w}` | Added, removed, changed and moved rows, header and column changes | `rules:read` | |
| `POST /rule-sets` | Create a rule set with an initial draft | `rules:author` | `Idempotency-Key` |
| `POST /rule-sets/{name}/versions` | New draft, empty or copied from a version | `rules:author` | `Idempotency-Key` |
| `PUT /rule-sets/{name}/versions/{v}` | Replace a draft's content in place; the draft's author only | `rules:author` | `Idempotency-Key`, `If-Match` (both required) |
| `POST /rule-sets/{name}/versions/{v}:validate` | Parse and type-check without saving | `rules:author` | |
| `POST /rule-sets/{name}/versions/{v}:simulate` | Evaluate a draft over sample inputs or logged decisions; writes no decision rows | `rules:simulate` | `Idempotency-Key` |
| `POST /rule-sets/{name}/versions/{v}:request-publish` | Freeze the draft, record the requester | `rules:author` | `Idempotency-Key`, `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:approve` | Publish; approver must differ from requester | `rules:approve` | `Idempotency-Key`, `If-Match` (required) |
| `POST /rule-sets/{name}/versions/{v}:reject` | Back to draft with a reason | `rules:approve` | `Idempotency-Key`, `If-Match` (required) |
| `POST /rule-sets/{name}/versions/{v}:withdraw` | The requester takes their own request back; back to draft, reason optional | `rules:author` (requester only) | `Idempotency-Key`, `If-Match` (both required) |
| `POST /rule-sets/{name}/versions/{v}:retire` | Retire a published version, with a reason. For the last version in effect, add `"confirmNoVersionInEffect": true` or get `409 LAST_VERSION_IN_EFFECT` (PD-101). In `four-eyes` mode (M3) it records a retirement request and the version keeps serving | `rules:approve` | `Idempotency-Key`, `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:confirm-retire` | M3, `four-eyes` mode: retire the version a retirement request names; the requester gets `403 SELF_APPROVAL_FORBIDDEN` | `rules:approve` | `Idempotency-Key`, `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:cancel-retire` | M3: withdraw a pending retirement request, with a reason | `rules:approve` | `Idempotency-Key`, `If-Match` |
| `GET /rule-sets/{name}/versions/{v}/export` | YAML, JSON or DMN 1.5 export | `rules:read` | |
| `POST /rule-sets/{name}/versions:import` | Create a draft from YAML or JSON | `rules:author` | `Idempotency-Key` |
| `GET /decisions/{id}` | One decision; masked inputs stay hashed | `rules:log:read` | |
| `GET /decisions/{id}/explain` | Plain-language explanation | `rules:log:read` | |
| `GET /decisions?ruleSet=&from=&to=&outcome=&matchedRuleId=&cursor=&limit=` | Decision log, newest first. `ruleSet`, `from` and `to` are required; the range is at most 31 days | `rules:log:read` | cursor pagination |

Permission strings match [[Rule Engine Configuration]].

## Evaluate

Request:
```json
{ "inputs": { "tier": "GOLD", "balance": { "currency": "VND", "amount": 60000000 }, "txCount": 3 },
  "correlationId": "acct-open-7781", "clock": "2026-12-01T00:00:00Z" }
```
Response `200 OK`:
```json
{ "decisionId": "01JD2X5K9T1Q4N8V2M7B3C6D0E", "ruleSet": "card-fee", "version": 2, "hitPolicy": "first",
  "outputs": { "fee": { "currency": "VND", "amount": 0 }, "waived": true },
  "matchedRuleIds": ["R2"], "clock": "2026-12-01T00:00:00Z", "evaluatedAt": "2026-11-18T09:12:44Z", "durationMicros": 180 }
```
`clock` is the instant the decision reasoned about; `evaluatedAt` is when it ran. Both are logged so the decision replays (`TR-DET-05`).

Batch request and response wrap items in `{ "items": [ { "inputs": {}, "correlationId": "" } ] }` and `{ "results": [ { "index": 0, "decision": {} } | { "index": 1, "problem": {} } ] }`.

## Export, import and diff

| Call | Request | Response |
|---|---|---|
| `GET /rule-sets/{name}/versions/{v}/export?format=yaml` (default), `?format=json` or `?format=dmn` | none; another `format` is `400 INPUT_INVALID` | `200` with the canonical definition document, without `effectiveFrom`, tenant, status or approvals; `ETag: "<lockVersion>"`, `Content-Disposition: attachment; filename="card-fee-v2.yaml"` |
| `POST /rule-sets/{name}/versions` (next version) or `POST /rule-sets` (new rule set, version 1) | `Content-Type: application/yaml` or `application/json`; body is a definition document; `Idempotency-Key`. `POST /rule-sets/{name}/versions?copyOf=2` with no body copies version 2 | `201` with the new draft `{ "ruleSet": "card-fee", "version": 3, "status": "DRAFT", "lockVersion": 0, "validation": { "errors": [], "warnings": [] } }`. `400 INPUT_INVALID` when the document's `ruleSet` differs from `{name}`; `409 RULE_SET_EXISTS` on `POST /rule-sets` for an existing name; `422 VALIDATION_FAILED` naming row and column. A refused document stores nothing. An `effectiveFrom` in the document becomes the draft's proposed date and is not part of the hash |
| `PUT /rule-sets/{name}/versions/{v}` | `Content-Type: application/yaml` or a DMN XML type; the full definition document; `Idempotency-Key` and `If-Match: "<lockVersion>"` both required | `200` with the draft: same `version`, new `canonicalHash`, `sourceHash` and `ETag`. Only a `DRAFT`, only by its author (`createdBy`); another author gets `403 PERMISSION_DENIED` pointing to `POST .../versions?copyOf={v}`. The same checks and `violations` as import, and a refused document stores nothing. One `DRAFT_UPDATED` history entry (with `canonicalHash` and `previousCanonicalHash`) and one `rules.version.draft-updated.v1` event. `428 PRECONDITION_REQUIRED` without `If-Match`, `400 INPUT_INVALID` without the key, `403 PERMISSION_DENIED` for another author, `403 DRAFT_AUTHORING_DISABLED` when the host sets `authoring.cockpit-drafts: false`, `409 INVALID_STATE` on a non-draft, `409 VERSION_CONFLICT` on a stale lock (`RLE-047`) |
| `GET /rule-sets/{name}/versions/{v}/diff?base={w}` | none | `200` `{ "ruleSet": "card-fee", "version": 2, "base": 1, "canonicalHash": "...", "baseCanonicalHash": "...", "sourceHashChanged": true, "effectiveFrom": { "from": "2026-11-01T00:00:00Z", "to": null }, "header": [], "inputs": { "added": [], "removed": [], "changed": [], "reordered": false }, "outputs": { "added": [], "removed": [], "changed": [], "reordered": false }, "rows": [ { "ruleId": "R2", "change": "CHANGED", "position": 2, "basePosition": 2, "moved": false, "fields": [ { "path": "/then/fee", "from": {"currency":"VND","amount":30000}, "to": {"currency":"VND","amount":0}, "nonAscii": false } ] }, { "ruleId": "R6", "change": "ADDED", "position": 3 } ], "unchangedCount": 2 }`. Rows are matched by rule id; `change` is `ADDED`, `REMOVED`, `CHANGED` or `MOVED` (order changed relative to the other common rows; under `first` order is behaviour). `nonAscii: true` marks a changed value with a character outside printable ASCII or an escaped character, so a look-alike such as a Cyrillic `В` is visible. Writes nothing. `400 INPUT_INVALID` without `base` or with `base` equal to `{v}`, `404 VERSION_NOT_FOUND` for a missing version (`RLE-028`, `RLE-048`) |

Definition documents carry `schemaVersion`; old schema versions import forever. Simulation of a draft (`:simulate`) uses the same evaluator as `:evaluate` and writes no decision rows:

![Simulation versus evaluate](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/simulation-vs-evaluate.svg)

## Idempotency-Key semantics

The engine stores `(tenant_id, operation, idempotency_key, request_hash, status, body)` in `rle_idempotency` for 24 hours (`idempotency.retention`).

| Situation | Response |
|---|---|
| New key | Request executes; status and body stored |
| Same key, same request hash, finished | The original status and body, for example `201`; no second effect |
| Same key, different request hash | `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` |
| Same key, first request still in flight | `409 IDEMPOTENCY_IN_FLIGHT` with `Retry-After: 1` |
| Key older than the retention window | Treated as new |

On `:evaluate` the key is optional. Evaluation is pure, so a retry without a key is safe; it only logs a second decision. With a key, the stored decision is returned and no second row is written (`RLE-014`).

## Optimistic locking

Every draft response carries `version` in the body and `ETag: "<version>"`. `PUT` and lifecycle actions require `If-Match` (or `version` in the body). The update runs as `UPDATE ... WHERE lock_version = :expected`; zero rows updated gives `409 VERSION_CONFLICT` with the current version in `detail`. Approvals also carry the lock, so a second approval of the same version is rejected rather than replayed.

## Error shape

```json
{
  "type": "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#unique_hit_violation",
  "title": "Unique hit policy violated",
  "status": 422,
  "code": "UNIQUE_HIT_VIOLATION",
  "detail": "Rule set 'card-fee' v2 matched 2 rules (R2, R4); expected exactly 1.",
  "instance": "/api/bpm/rules/v1/decisions/card-fee:evaluate",
  "correlationId": "acct-open-7781",
  "violations": []
}
```
`type` points at the code's heading on [[Common Error Codes]] (ADR-0010). `violations` is present on `VALIDATION_FAILED` only and lists `{ "ruleId", "column", "message" }` entries, `column` being the input or output name. Responses never contain stack traces. `500` responses carry only `code: INTERNAL_ERROR` and the `correlationId` to find the server log.

## Error code matrix

| HTTP | `code` | When | Endpoints |
|---|---|---|---|
| 400 | `INPUT_INVALID` | Missing or mistyped input, malformed JSON | all |
| 400 | `QUERY_INVALID` | `limit` below 1 or above 200, a cursor the list did not issue, a decision log query without `ruleSet`, `from` and `to`, or a range longer than 31 days (ADR-0015) | `GET /decisions`, every list |
| 401 | `UNAUTHENTICATED` | No principal | all |
| 403 | `PERMISSION_DENIED` | Principal lacks the permission | all |
| 403 | `SELF_APPROVAL_FORBIDDEN` | Approver equals requester (`TR-SEC-04`) | `:approve` |
| 403 | `CLOCK_NOT_ALLOWED` | `clock` set without `rules:simulate` | `:evaluate` |
| 403 | `TENANT_NOT_ALLOWED` | `X-Tenant-Id` names a tenant outside the token's allowed list, or differs from the token tenant while switching is off; no data returned, security log entry written (ADR-0014, `RLE-018`) | all |
| 403 | `DRAFT_AUTHORING_DISABLED` | The host keeps rule changes in its release pipeline (`authoring.cockpit-drafts: false`); replacing a draft is refused | `PUT` |
| 404 | `RULE_SET_NOT_FOUND` | Unknown rule set in this tenant (also for other tenants' rule sets) | all with `{ruleSet}` or `{name}` |
| 404 | `VERSION_NOT_FOUND` | Unknown version number | versions |
| 404 | `NO_EFFECTIVE_VERSION` | Nothing published and effective at `clock` | `:evaluate`, `:evaluate-batch` |
| 404 | `DECISION_NOT_FOUND` | Unknown or other-tenant decision id | `/decisions/{id}` |
| 409 | `VERSION_CONFLICT` | `If-Match` or `version` is stale | `PUT`, lifecycle actions |
| 409 | `INVALID_STATE` | Action not allowed in the current state, for example `:approve` on a `DRAFT` | lifecycle actions |
| 409 | `IDEMPOTENCY_IN_FLIGHT` | Same key, first request still running; `Retry-After: 1` | all with `Idempotency-Key` |
| 409 | `RULE_SET_EXISTS` | Name already used in this tenant | `POST /rule-sets` |
| 413 | `PAYLOAD_TOO_LARGE` | Body over the endpoint's limit: 16,384 bytes for `:approve`, `:reject` and `:withdraw`, 1,048,576 bytes for `:evaluate` and imports | `:approve`, `:reject`, `:withdraw`, `:evaluate`, imports |
| 422 | `VALIDATION_FAILED` | Parse or type errors; `violations` lists every cell problem with `ruleId` and `column` (`TR-DET-02`) | `PUT`, `:validate`, `:request-publish`, `:import` |
| 422 | `UNIQUE_HIT_VIOLATION` | `unique` table matched several rows (`TR-DET-04`). An authoring defect, so retrying does not help (ADR-0010) | `:evaluate`, item error in `:evaluate-batch` |
| 422 | `EVALUATION_ERROR` | Currency mismatch, division by zero, regex timeout; `detail` names the reason (`CURRENCY_MISMATCH`, `DIVISION_BY_ZERO`, `REGEX_TIMEOUT`, `FUNCTION_ERROR`) | `:evaluate` |
| 422 | `IDEMPOTENCY_KEY_REUSE_MISMATCH` | Same key, different payload | all with `Idempotency-Key` |
| 422 | `BATCH_TOO_LARGE` | More than 1 000 items | `:evaluate-batch` |
| 428 | `PRECONDITION_REQUIRED` | A command that must be conditional was sent without `If-Match` | `PUT`, `:approve`, `:reject`, `:withdraw` |
| 429 | `RATE_LIMITED` | Host rate limiter, if configured; `Retry-After` | all |
| 500 | `INTERNAL_ERROR` | Unexpected failure; no details beyond `correlationId` | all |

Decision rows record `outcome` as `OK`, `UNIQUE_HIT_VIOLATION` or `EVALUATION_ERROR`, so errors are auditable too. Process Engine codes are listed on [[Common Error Codes]].

## Events

| `eventType` | When | Payload |
|---|---|---|
| `rules.version.draft-updated.v1` | `PUT` of a draft | `ruleSet`, `version`, `updatedBy`, `canonicalHash`, `previousCanonicalHash` |
| `rules.version.publish-requested.v1` | `:request-publish` | `ruleSet`, `version`, `canonicalHash`, `requestedBy`, `effectiveFrom` (the requested date, or null for at approval) |
| `rules.version.published.v1` | `:approve` | `ruleSet`, `version`, `canonicalHash`, `effectiveFrom`, `requestedBy`, `approvedBy` |
| `rules.version.rejected.v1` | `:reject` | `ruleSet`, `version`, `rejectedBy`, `reason` |
| `rules.version.withdrawn.v1` | `:withdraw` | `ruleSet`, `version`, `withdrawnBy`, `reason` (null when none was given) |
| `rules.version.retire-requested.v1` | `:retire` in `four-eyes` mode (M3) | `ruleSet`, `version`, `requestedBy`, `reason` |
| `rules.version.retired.v1` | `:retire` in `single` mode, `:confirm-retire`, or superseded | `ruleSet`, `version`, `retiredBy` (`system` when superseded; the confirmer in `four-eyes` mode), `reason` (`SUPERSEDED` when superseded), `supersededBy` (version, or null), `approvalMode` (`single`, `four-eyes` or `system`), `requestedBy` (the requester in `four-eyes` mode, else null), `retiredAt` |
| `rules.decision.evaluated.v1` | every decision, off by default (`events.decision-evaluated`) | `decisionId`, `ruleSet`, `version`, `outcome` |

A host that wants tamper evidence for approvals relays these events to a store outside the database administrator's control (an append-only log or a separate audit system) and reconciles the stored versions against it, because rows in the host database, outbox included, can be changed by whoever administers it.

Envelope: `eventId` (ULID), `eventType`, `occurredAt`, `tenantId`, `correlationId`, `causationId`, `producer`, `schemaVersion`, `payload`. Changes are additive within a `.v1` type. Events go through the host's `RuleEventPublisher` inside the same transaction as the state change.

## Performance targets (0.1)

In-process evaluation of a 200-row `first` table: p99 under 100 us after warm-up (`TR-PERF-01`). The transactional decision log adds exactly one `INSERT` (`TR-PERF-03`). REST `:evaluate` p99 target: 10 ms in the same data centre. Targets, not measurements, until the M1 benchmarks publish.
