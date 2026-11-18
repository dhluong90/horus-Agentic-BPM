# How to use the decision log

Find, explain and protect the record of every decision. The decision log is the audit trail that risk, compliance and customer service read.

Design preview: describes the 0.1 target.

## Before you start

- Reading the log requires `rules:log:read` ([[Rule Engine Configuration]]).
- The log is append-only; the engine has no update or delete path (TR-OBS-01).

## What one entry contains

| Field | Content |
|---|---|
| `decisionId` | ULID, returned to the caller of the evaluate call |
| `tenant`, `ruleSet`, `version`, `hitPolicy` | Which rules decided |
| `inputs` | As received after type validation; masked inputs appear as keyed hashes |
| `matchedRuleIds`, `outputs` | The explanation |
| `outcome` | `ok` or an error code such as `UNIQUE_HIT_VIOLATION` |
| `caller`, `correlationId` | From the security context and the request |
| `clock`, `evaluatedAt`, `durationMicros` | The instant the decision reasoned about, when it ran, and how long |
| async marker | Set when the entry was written in `async` mode (see below) |

## Find decisions

```bash
curl -s 'http://localhost:8080/api/bpm/rules/v1/decisions?ruleSet=card-fee&from=2026-12-01T00:00:00Z&to=2026-12-31T23:59:59Z&outcome=error'
```

Filters: `ruleSet`, `from`, `to`, `outcome`, matched rule id, and any output value. Results are paginated by cursor; exact parameter names are in [[Rule Engine Decision API]]. The Decisions screen in the [[Cockpit]] offers the same filters.

## Open one decision

`GET /api/bpm/rules/v1/decisions/01JD2X8K3N4P5Q6R7S8T9V0W1X` returns the entry above. The cockpit shows it with the rule table and the matched row highlighted.

## Explain a decision to a customer

`GET /api/bpm/rules/v1/decisions/01JD2X8K3N4P5Q6R7S8T9V0W1X/explain` returns a plain-language rendering: the inputs, the row that matched with its conditions, and the outputs. Example:

```
Card fee for December 2026: 0 VND (waived).
Rule R2 applied: tier is GOLD and balance 60,000,000 VND is at least 50,000,000 VND.
```

Templates per rule set can override the wording (a 1.0 target, see [[Roadmap]]).

## Mask personal data

Declare the input with `mask: true` in the table (see [[Rule Engine Authoring Decision Tables]]):

```yaml
inputs:
  - { name: nationalId, type: string, mask: true }
```

The log stores a keyed HMAC of the value instead of the value. The same national id always produces the same token, so an auditor can still search for all decisions about one person without reading the id. The cockpit and the REST API render it masked (TR-SEC-05). To mask every input of every rule set by default, set `decision-log.mask-default: true`.

## Choose transactional or async logging

![Decision log modes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/decision-log-modes.svg)

| Mode | Behaviour | Use it for |
|---|---|---|
| `transactional` (default) | One insert in the caller's transaction. A rolled-back business transaction leaves no decision | Everything where the decision has a business effect |
| `async` | The entry is written through an outbox after the call returns and is marked as async | High-volume read paths, for example a fee preview on every page load |

Set `horus.bpm.rules.decision-log.mode`. To turn logging off entirely, set `decision-log.enabled: false`; decisions then return no `decisionId`.

## Keep the log within retention

Retention is the host's decision. The table `rle_decision` can be partitioned by month by configuration (TR-OPS-04); drop old partitions with your database tooling. The engine never deletes.

## React to events

| Event | When |
|---|---|
| `rules.version.published` | A version was approved |
| `rules.version.retired` | A version was retired |
| `rules.decision.evaluated` | Every decision; off by default because of volume |

Events are delivered through the `RuleEventPublisher` SPI, which the host implements to forward them to its own audit or messaging system. The engine itself never calls the network (TR-EMB-05).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A decision you saw in the response is missing from the log | The business transaction rolled back after the evaluation (transactional mode) | Expected behaviour; the business effect did not happen either |
| Plain values appear where you expected a hash | `mask: true` was added after the decisions were logged | Masking applies from the version where it is declared; older entries are immutable |
| `GET /decisions?...` returns `403` | Caller lacks `rules:log:read` | Grant the permission |
| Query is slow over a long range | No partitioning, or a range spanning many months | Enable monthly partitioning; narrow `from` and `to` |
