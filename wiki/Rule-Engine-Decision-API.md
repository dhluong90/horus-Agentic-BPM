# Decision API

Reference for the two Rule Engine surfaces: the in-process Java API and the optional REST API under `/api/bpm/rules/v1` that the cockpit and remote callers use. _Design preview: the contract is reviewed here before the code exists, except for the operations listed under "Available in 0.1.0-SNAPSHOT"._

## Available in 0.1.0-SNAPSHOT

These operations are built and tested; their contract file is [`rule-engine/api/openapi.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/rule-engine/api/openapi.yaml). Everything else on this page is still a design preview.

| Method and path | Java | Permission | Notes |
|---|---|---|---|
| `POST /rule-sets` | `VersionService.importRuleSet(ImportRequest)` | `rules:author` | Body is the YAML document (`Content-Type: application/yaml`); creates the rule set named in the document and its version 1 as `DRAFT`. `201`, `ETag`, `Location` |
| `POST /rule-sets/{name}/versions` | `VersionService.importVersion(name, ImportRequest)` | `rules:author` | The next draft version; the document's `ruleSet` must equal `{name}` |
| `GET /rule-sets/{name}/versions/{v}` | `VersionService.find(name, v, includeSource)` | `rules:read` | `?include=source` adds `sourceDocument`, the file as imported, comments included |
| `POST /rule-sets/{name}/versions/{v}:request-publish` | `VersionService.requestPublish(...)` | `rules:author` | `If-Match` optional |
| `POST /rule-sets/{name}/versions/{v}:approve` | `VersionService.approve(...)` | `rules:approve` | Optional body `{ "comment": "..." }`; the requester gets `403 SELF_APPROVAL_FORBIDDEN` |
| `POST /decisions/{ruleSet}:evaluate` | `RuleEngine.evaluate(...)` | `rules:evaluate` | Body `{ "inputs": { ... } }`; `first` hit policy only; one `rle_decision` row in the request's transaction |

All of them accept `Idempotency-Key` with the semantics below, and every state change writes an outbox row (`rules.version.created.v1`, `rules.version.publish-requested.v1`, `rules.version.published.v1`). A caller without the permission gets `403 PERMISSION_DENIED`. In this build: YAML import only, no `clock` or version-pinned evaluation, no validation report in the response, and the `unique` and `collect` hit policies are refused at import.

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
| Tenant | Resolved from the security context or a trusted gateway header. A `tenantId` field in any request body is ignored (`TR-SEC-03`, `RLE-011`). With tenant switching on, `X-Tenant-Id` may name another tenant from the token's allowed list; any other value is `403 TENANT_NOT_ALLOWED` |
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
| `POST /decisions/{ruleSet}/versions/{v}:evaluate` | Evaluate a specific version; drafts allowed for simulation | `rules:evaluate` for published versions; `rules:simulate` for drafts or a custom `clock` | `Idempotency-Key` optional |
| `POST /decisions/{ruleSet}:evaluate-batch` | Up to 1 000 input sets; one decision row each; partial failures reported per item | `rules:evaluate` | `Idempotency-Key` optional |
| `GET /rule-sets` | List rule sets with effective version and pending approvals | `rules:read` | cursor pagination |
| `GET /rule-sets/{name}` | Rule set with its versions and states | `rules:read` | |
| `GET /rule-sets/{name}/versions/{v}` | One version, canonical JSON, validation report | `rules:read` | `ETag` |
| `GET /rule-sets/{name}/versions/{a}/diff/{b}` | Added, removed and changed rows and columns | `rules:read` | |
| `POST /rule-sets` | Create a rule set with an initial draft from a YAML or JSON document | `rules:author` | `Idempotency-Key` |
| `POST /rule-sets/{name}/versions` | New draft from a YAML or JSON document | `rules:author` | `Idempotency-Key` |
| `PUT /rule-sets/{name}/versions/{v}` | Replace a draft's content | `rules:author` | `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:validate` | Parse and type-check without saving | `rules:author` | |
| `POST /rule-sets/{name}/versions/{v}:simulate` | Evaluate a draft over sample inputs or logged decisions; writes no decision rows | `rules:simulate` | `Idempotency-Key` |
| `POST /rule-sets/{name}/versions/{v}:request-publish` | Freeze the draft, record the requester | `rules:author` | `Idempotency-Key`, `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:approve` | Publish; approver must differ from requester | `rules:approve` | `Idempotency-Key`, `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:reject` | Back to draft with a reason | `rules:approve` | `Idempotency-Key`, `If-Match` |
| `POST /rule-sets/{name}/versions/{v}:retire` | Retire a published version | `rules:approve` | `Idempotency-Key`, `If-Match` |
| `GET /rule-sets/{name}/versions/{v}/export` | YAML export | `rules:read` | |
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
| `GET /rule-sets/{name}/versions/{v}/export` | `Accept: application/yaml` (default) or `application/json` | `200` with the canonical definition document, `ETag: "<version>"`, `Content-Disposition: attachment; filename="card-fee-v2.yaml"` |
| `POST /rule-sets` (new rule set) or `POST /rule-sets/{name}/versions` (next version) | `Content-Type: application/yaml` (JSON from a later release); body is a definition document; `Idempotency-Key` | `201` with the new draft `{ "ruleSet": "card-fee", "version": 3, "status": "DRAFT", "lockVersion": 0, "sourceFormat": "YAML", "sourceHash": "...", "canonicalHash": "...", "definition": { ... } }`; `422 VALIDATION_FAILED` when the document does not parse; `409 RULE_SET_EXISTS` on `POST /rule-sets` for a name already used |
| `GET /rule-sets/{name}/versions/{a}/diff/{b}` | none | `200` `{ "from": 1, "to": 2, "hitPolicyChanged": false, "columns": { "added": [], "removed": [], "changed": [] }, "rows": { "added": ["R5"], "removed": [], "changed": [ { "ruleId": "R2", "cells": [ { "column": "fee", "from": {"currency":"VND","amount":10000}, "to": {"currency":"VND","amount":0} } ] } ] } }` |

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
`type` points at the code's heading on [[Common Error Codes]] (ADR-0010). `violations` lists `{ "field", "ruleId", "message" }` entries for validation errors. Responses never contain stack traces. `500` responses carry only `code: INTERNAL_ERROR` and the `correlationId` to find the server log.

## Error code matrix

| HTTP | `code` | When | Endpoints |
|---|---|---|---|
| 400 | `INPUT_INVALID` | Missing or mistyped input, malformed JSON | all |
| 400 | `QUERY_INVALID` | `limit` above 200, a decision log query without `ruleSet`, `from` and `to`, or a range longer than 31 days (ADR-0015) | `GET /decisions`, every list |
| 401 | `UNAUTHENTICATED` | No principal | all |
| 403 | `PERMISSION_DENIED` | Principal lacks the permission | all |
| 403 | `SELF_APPROVAL_FORBIDDEN` | Approver equals requester (`TR-SEC-04`) | `:approve` |
| 403 | `CLOCK_NOT_ALLOWED` | `clock` set without `rules:simulate` | `:evaluate` |
| 403 | `TENANT_NOT_ALLOWED` | `X-Tenant-Id` names a tenant outside the token's allowed list, or differs from the token tenant while switching is off; no data returned, security log entry written (ADR-0014, `RLE-018`) | all |
| 404 | `RULE_SET_NOT_FOUND` | Unknown rule set in this tenant (also for other tenants' rule sets) | all with `{ruleSet}` or `{name}` |
| 404 | `VERSION_NOT_FOUND` | Unknown version number | versions |
| 404 | `NO_EFFECTIVE_VERSION` | Nothing published and effective at `clock` | `:evaluate`, `:evaluate-batch` |
| 404 | `DECISION_NOT_FOUND` | Unknown or other-tenant decision id | `/decisions/{id}` |
| 409 | `VERSION_CONFLICT` | `If-Match` or `version` is stale | `PUT`, lifecycle actions |
| 409 | `INVALID_STATE` | Action not allowed in the current state, for example `:approve` on a `DRAFT` | lifecycle actions |
| 409 | `IDEMPOTENCY_IN_FLIGHT` | Same key, first request still running; `Retry-After: 1` | all with `Idempotency-Key` |
| 409 | `RULE_SET_EXISTS` | Name already used in this tenant | `POST /rule-sets` |
| 422 | `VALIDATION_FAILED` | Parse or type errors; `violations` lists rows (`TR-DET-02`) | `PUT`, `:validate`, `:request-publish`, `:import` |
| 422 | `UNIQUE_HIT_VIOLATION` | `unique` table matched zero or several rows (`TR-DET-04`). An authoring defect, so retrying does not help (ADR-0010) | `:evaluate`, item error in `:evaluate-batch` |
| 422 | `EVALUATION_ERROR` | Currency mismatch, division by zero, regex timeout; `detail` names the reason (`CURRENCY_MISMATCH`, `DIVISION_BY_ZERO`, `REGEX_TIMEOUT`, `FUNCTION_ERROR`) | `:evaluate` |
| 422 | `IDEMPOTENCY_KEY_REUSE_MISMATCH` | Same key, different payload | all with `Idempotency-Key` |
| 422 | `BATCH_TOO_LARGE` | More than 1 000 items | `:evaluate-batch` |
| 429 | `RATE_LIMITED` | Host rate limiter, if configured; `Retry-After` | all |
| 500 | `INTERNAL_ERROR` | Unexpected failure; no details beyond `correlationId` | all |

Decision rows record `outcome` as `OK`, `UNIQUE_HIT_VIOLATION` or `EVALUATION_ERROR`, so errors are auditable too. Process Engine codes are listed on [[Common Error Codes]].

## Events

| `eventType` | When | Payload |
|---|---|---|
| `rules.version.publish-requested.v1` | `:request-publish` | `ruleSet`, `version`, `requestedBy` |
| `rules.version.published.v1` | `:approve` | `ruleSet`, `version`, `effectiveFrom`, `approvedBy`, `requestedBy` |
| `rules.version.rejected.v1` | `:reject` | `ruleSet`, `version`, `reason` |
| `rules.version.retired.v1` | `:retire` or superseded | `ruleSet`, `version` |
| `rules.decision.evaluated.v1` | every decision, off by default (`events.decision-evaluated`) | `decisionId`, `ruleSet`, `version`, `outcome` |

Envelope: `eventId` (ULID), `eventType`, `occurredAt`, `tenantId`, `correlationId`, `causationId`, `producer`, `schemaVersion`, `payload`. Changes are additive within a `.v1` type. Events go through the host's `RuleEventPublisher` inside the same transaction as the state change.

## Performance targets (0.1)

In-process evaluation of a 200-row `first` table: p99 under 100 us after warm-up (`TR-PERF-01`). The transactional decision log adds exactly one `INSERT` (`TR-PERF-03`). REST `:evaluate` p99 target: 10 ms in the same data centre. Targets, not measurements, until the M1 benchmarks publish.
