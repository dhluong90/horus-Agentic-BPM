# Decision Log and Audit

Every evaluation is recorded. The log is the audit trail that risk, compliance and customer service read.

## What is recorded
| Field | Note |
|---|---|
| decisionId | ULID, returned to the caller |
| tenant, ruleSet, version, hitPolicy | which rules decided |
| inputs | as received, after type validation; configurable masking per input (`mask: true`) for personal data |
| matchedRuleIds, outputs | the explanation |
| outcome | `ok` or an error code |
| caller, correlationId | from the security context and the request |
| clock, evaluatedAt, durationMicros | the time the decision reasoned about, and when it ran |

## Guarantees
- Append-only tables; no update or delete paths in the engine. Retention is a host decision (partition by month and drop partitions).
- Written in the caller's transaction by default, so a rolled-back business transaction leaves no orphan decision; `async` mode is available for high-volume read paths and is marked as such in each entry.
- Masked inputs are hashed (keyed HMAC) so that the same value can still be searched without being readable.

## Querying
`GET /api/bpm/rules/decisions?ruleSet=card-fee&from=…&to=…&outcome=error&page=…`, filterable by matched rule id and by any output value. The cockpit shows a decision with its rule table highlighting the matched row.

## Explaining a decision to a customer
`GET /decisions/{id}/explain` returns a plain-language rendering: inputs, the row that matched with its conditions, the outputs. Templates per rule set can override the wording.

## Events
`rules.decision.evaluated` (optional, off by default), `rules.version.published`, `rules.version.retired`, published through the host's event mechanism via `RuleEventPublisher`.
