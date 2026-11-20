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
| `onBehalfOf` | Set only for a process decision step (0.2.0): the step is evaluated as `system:process-engine`, and `onBehalfOf` names the user who triggered it, or is null after a timer. The task worker needs no `rules:evaluate`. A retried step replays its decision instead of writing a second row |
| `evalClock`, `evaluatedAt`, `durationMicros` | The instant the decision reasoned about, when it ran, and how long |
| `clockChosen`, `pinned` | Whether the caller chose the clock (`rules:simulate`), so the decision is back-dated or forward-dated, and whether the caller named the version (`rules:pin`). The engine sets both; `GET /decisions/{id}` and the masked-input search return them |
| async marker | Set when the entry was written in `async` mode (see below) |

## Find decisions

```bash
curl -s 'http://localhost:8080/api/bpm/rules/v1/decisions?ruleSet=card-fee&from=2026-12-01T00:00:00Z&to=2027-01-01T00:00:00Z'
```

Filters: `ruleSet`, `from`, `to`, `outcome`, matched rule id, and any output value. Results are paginated by cursor; exact parameter names are in [[Rule Engine Decision API]]. The Decisions screen in the [[Cockpit]] offers the same filters.

## Open one decision

`GET /api/bpm/rules/v1/decisions/01JD2X8K3N4P5Q6R7S8T9V0W1X` returns the entry above. The cockpit shows it with the rule table and the matched row highlighted.

## Explain a decision to a customer

`GET /api/bpm/rules/v1/decisions/01JD2X8K3N4P5Q6R7S8T9V0W1X/explain` returns a plain-language rendering: the inputs, the row that matched with its conditions, and the outputs. Example:

```json
{ "ruleSet": "card-fee", "version": 2, "outcome": "OK",
  "matched": [ { "ruleId": "R2", "conditions": [
      { "input": "tier", "test": "== 'GOLD'", "value": "GOLD", "masked": false, "missing": false, "result": true },
      { "input": "balance", "test": ">= 50000000 VND", "value": { "currency": "VND", "amount": 60000000 }, "masked": false, "missing": false, "result": true } ] } ],
  "outputs": { "fee": { "currency": "VND", "amount": 0 } },
  "reasons": [ { "code": "RULE_MATCHED", "params": { "ruleId": "R2", "version": 2 } } ],
  "text": "Rule R2 of card-fee version 2 matched because tier is GOLD and balance is 60000000 VND, which is at least 50000000 VND. Result: fee 0 VND." }
```

`matched`, `outputs` and `reasons` are the contract; `text` is rendered from `reasons` through a message catalogue per language, with locale-neutral numbers (no grouping, money in major units with its currency code). English and Vietnamese catalogues ship. Ask for a language with `?lang=vi` or `Accept-Language`; without one, `text` uses `horus.bpm.rules.explain.default-language` (default `en`), and a missing entry falls back to English. The cockpit reads the same catalogues, so the plain-language readings and the explanations use one wording.

To change the wording or add a language, place your own catalogue resource on the host's classpath; no code is needed. To replace the renderer entirely, declare a `DecisionExplainer` bean ([[Configuration and Extension Points]]). `reasons` never change.

The explanation is built from the log entry and the stored version only: nothing is evaluated again, rows that did not match are not explained, masked inputs show as `hmac:<keyId>:<hex>` and missing inputs as "has no value". Templates per rule set can override the wording in M3 (see [[Roadmap]]).

## Mask personal data

Declare the input with `mask: true` in the table (see [[Rule Engine Authoring Decision Tables]]):

```yaml
inputs:
  - { name: nationalId, type: string, mask: true }
```

The log stores a keyed HMAC of the value instead of the value, `hmac:` followed by 64 hex digits. The same national id always produces the same token under one key, so an auditor can still find every decision about one person without the id being stored. The REST API returns it masked (TR-SEC-05); the key setting and the start-up check are described in [[Rule Engine Configuration]]. An input that declares no `mask` and looks like personal data is a warning at import and in the change the approver reviews, and a bank can make every input declare its masking; see the personal data guards in [[Rule Engine Configuration]].

To find the decisions about one person, a holder of `rules:masked:search` (a separate permission that `rules:log:read` does not imply) posts the plain value, at most 30 times a minute by default (`masking.search.max-per-minute`); the engine fingerprints it with your tenant's key before it searches:

```bash
curl -s -X POST "$HOST/api/bpm/rules/v1/decisions:find-by-masked-input" \
  -H "Authorization: Bearer $AUDITOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"ruleSet": "minor-review", "input": "nationalId", "value": "079090001234",
       "from": "2027-01-01T00:00:00Z", "to": "2027-02-01T00:00:00Z"}'
```

```json
{ "data": [ { "decisionId": "01KF2Q8Z0RQ6V2Y8M1C4N5P7T9", "ruleSet": "minor-review", "version": 1,
              "outcome": "OK", "inputs": { "nationalId": "hmac:k1:7d8c2f0a…", "age": 16 },
              "outputs": { "route": "MANUAL" }, "matchedRuleIds": ["R1"] } ],
  "truncated": false }
```

Every search writes one audit entry, the event `rules.decision-log.masked-input-searched.v1`, in the same transaction: who searched, which rule set and input, the period, how many decisions were found, the key ids used and the correlation id. It never holds the value or its fingerprint. Each token carries the id of the key that made it, `hmac:<keyId>:<hex>`, and the search tries every key your tenant holds.

Two banks on one host always store different tokens for the same id: a tenant with its own key uses it as it is, and every other tenant uses its own subkey derived from the host key (see [Masking keys](Rule-Engine-Configuration#masking-keys)), never the host key itself. Neither bank finds the other's decisions. To mask every input that declares nothing, set `decision-log.mask-default: true` for the host or `masking.tenants.<tenant>.mask-default: true` for one tenant; `mask: false` keeps an input in plain text.

## Choose transactional or async logging

![Decision log modes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/decision-log-modes.svg)

| Mode | Behaviour | Use it for |
|---|---|---|
| `transactional` (default) | One insert in the caller's transaction. A rolled-back business transaction leaves no decision | Everything where the decision has a business effect |
| `async` (from M2) | The entry is written through an outbox after the call returns and is marked as async | High-volume read paths, for example a fee preview on every page load |

Set `horus.bpm.rules.decision-log.mode`. To turn logging off entirely, set `decision-log.enabled: false`; decisions then return no `decisionId`.

## Keep the log within retention

Retention is the host's decision; no product default deletes audit data. The table `rle_decision` is partitioned by calendar month in every install (ADR-0010). Set `horus.bpm.rules.decision-log.retention` in whole months, at least `P12M`:

```yaml
horus:
  bpm:
    rules:
      decision-log:
        retention: P120M   # keep decisions for 10 years
```

- **Off by default.** Left out, every decision is kept, nothing is dropped and the host starts as before, but a WARN at every start-up says the setting is unset, that everything is kept, roughly how the log grows per row (1 to 3 KB a decision) and names the setting. Set `keep-forever` to say it on purpose; that logs INFO. Only a period of at least `P12M` turns drops on.
- **Upgrade note.** A host that upgrades without setting retention starts and keeps every decision, as before, and now sees the WARN. Before 1.0 nothing refuses start-up. At 1.0 the setting becomes required (PD-050, PD-130), so choose `keep-forever` or a period before then.
- **Whole months only.** The daily partition job drops each monthly partition that ends at or before the host clock minus the retention. With `P12M` on 3 February 2028, `rle_decision_2027_01` (up to 1 February 2027) is dropped and `rle_decision_2027_02` stays. No row is ever deleted.
- **Every drop is recorded.** In the transaction that drops a month, the engine writes to `rle_decision_log_drop` one row per tenant with its decision count, and one row with tenant `*` for the total. The rows also hold the partition, its range, the retention and the cutoff, and `dropped_at`. The table is append-only: the database refuses `UPDATE`, `DELETE` and `TRUNCATE` on it. Each drop is also logged at WARN.
- **A floor of your own.** `decision-log.retention-floor` (default `P12M`) is the shortest retention accepted. Raise it to your policy, for example `P84M`, so that a shorter value set by mistake stops start-up.

To see what was dropped:

```sql
SELECT partition_name, tenant_id, decision_count, retention, cutoff, dropped_at
FROM rle_decision_log_drop ORDER BY dropped_at, partition_name, tenant_id;
```

Details: [[Rule Engine Configuration]].

## React to events

| Event | When |
|---|---|
| `rules.version.published` | A version was approved |
| `rules.version.retired` | A version was retired |
| `rules.decision.evaluated` | Every decision; off by default because of volume |
| `rules.decision-log.masked-input-searched` | Every search by a masked input's plain value; the audit entry of that lookup, without the value |

These are the events an audit consumer usually needs; the full list, the envelope (`eventId`, `correlationId`, `causationId`, `producer`, `schemaVersion`) and the schema files are in [[Rule Engine Decision API]].

Events are delivered through the `RuleEventPublisher` SPI, which the host implements to forward them to its own audit or messaging system. The engine itself never calls the network (TR-EMB-05).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A decision you saw in the response is missing from the log | The business transaction rolled back after the evaluation (transactional mode) | Expected behaviour; the business effect did not happen either |
| Plain values appear where you expected a hash | `mask: true` was added after the decisions were logged | Masking applies from the version where it is declared; older entries are immutable |
| `GET /decisions?...` returns `403` | Caller lacks `rules:log:read` | Grant the permission |
| Query is slow | A range close to the 31-day limit with no other filter | Narrow `from` and `to` to one monthly partition, or add `outcome` or `matchedRuleId` |
