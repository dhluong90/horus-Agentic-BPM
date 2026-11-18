# Decision API

Two surfaces: an in-process Java API and an optional REST API that the cockpit and remote callers use. _Design preview._

## Java
```java
public interface RuleEngine {
  DecisionResult evaluate(String ruleSet, DecisionInput input);
  DecisionResult evaluate(String ruleSet, int version, DecisionInput input);   // pin a version
  DecisionResult evaluate(DecisionRequest request);                            // tenant, clock, correlation id, caller
  <T> T evaluateAs(String ruleSet, DecisionInput input, Class<T> outputType);  // map outputs onto a record
}
```
```java
DecisionResult r = rules.evaluate("card-fee", DecisionInput.of("tier", "GOLD", "balance", Money.of("VND", 60_000_000), "txCount", 3));
r.ruleSet();            // "card-fee"
r.version();            // 2
r.matchedRuleIds();     // ["R2"]
r.output("fee", Money.class);
r.decisionId();         // id in the decision log
r.isError();            // false
```
Tenant and caller come from the host's `SecurityContext` by default; `DecisionRequest` can override them for batch jobs.

## REST
Base path `/api/bpm/rules` (configurable). All endpoints require an authenticated principal; see [[Multi Tenancy and Security]].

| Method and path | Purpose |
|---|---|
| `POST /decisions/{ruleSet}:evaluate` | Evaluate the effective version |
| `POST /decisions/{ruleSet}/versions/{v}:evaluate` | Evaluate a specific version (also used for simulation of drafts, role-restricted) |
| `POST /decisions/{ruleSet}:evaluate-batch` | Up to 1 000 input sets in one call; one decision log entry each |
| `GET /rule-sets` · `GET /rule-sets/{name}` | List and describe rule sets with their versions and states |
| `POST /rule-sets` · `POST /rule-sets/{name}/versions` | Create a rule set or a new draft version |
| `PUT /rule-sets/{name}/versions/{v}` | Update a draft |
| `POST /rule-sets/{name}/versions/{v}:validate` | Validate without saving |
| `POST /rule-sets/{name}/versions/{v}:request-publish` · `:approve` · `:reject` · `:retire` | Lifecycle with maker-checker |
| `GET /decisions/{id}` · `GET /decisions?ruleSet=&from=&to=&outcome=` | Decision log |

Request and response schema are published as OpenAPI at `/api/bpm/rules/openapi.yaml`.

### Evaluate request
```json
{ "inputs": { "tier": "GOLD", "balance": { "currency": "VND", "amount": 60000000 }, "txCount": 3 },
  "correlationId": "acct-open-7781", "clock": "2026-12-01T00:00:00Z" }
```
`clock` is accepted only from callers with the `rules:simulate` permission.

All mutating endpoints (create, update, lifecycle) accept an `Idempotency-Key` header; a replay within 24 hours returns the original response (ADR-0006). Evaluation itself is pure and safe to retry; when the caller sends an `Idempotency-Key` on `:evaluate`, the engine returns the original decision instead of logging a second one.

### Evaluate response
```json
{ "decisionId": "01JD2X…", "ruleSet": "card-fee", "version": 2, "hitPolicy": "first",
  "outputs": { "fee": { "currency": "VND", "amount": 0 }, "waived": true },
  "matchedRuleIds": ["R2"], "evaluatedAt": "2026-12-01T00:00:00Z", "durationMicros": 180 }
```

### Errors
| HTTP | code | Meaning |
|---|---|---|
| 400 | `INPUT_INVALID` | Missing or mistyped input |
| 404 | `RULE_SET_NOT_FOUND` / `NO_EFFECTIVE_VERSION` | Unknown rule set or nothing published for this tenant |
| 409 | `UNIQUE_HIT_VIOLATION` | `unique` table matched zero or several rows |
| 422 | `EVALUATION_ERROR` | Currency mismatch, division by zero, regex timeout; details in the log |

## Performance targets (0.1)
In-process evaluation of a 200-row table under 100 µs p99 after warm-up; decision log write in the caller's transaction adds one insert.
