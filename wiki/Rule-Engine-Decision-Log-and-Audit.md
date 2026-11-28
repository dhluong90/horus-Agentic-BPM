# How to use the decision log

Find, explain and protect the record of every decision. The decision log is the audit trail that risk, compliance and customer service read.

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
| `limit` | `STEPS` or `TIME` on a decision that failed with `EVALUATION_BUDGET_EXCEEDED` (1.1, `RLE-233`); absent otherwise and on earlier rows. `STEPS` is the step budget, the same on every machine, so the rule set must change; `TIME` is the wall-clock backstop, so the machine was slow and a retry may pass |
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

`GET /api/bpm/rules/v1/decisions/01JD2X8K3N4P5Q6R7S8T9V0W1X/explain` returns a plain-language rendering: the row that matched with its conditions and the values the log recorded, and the outputs. It needs `rules:log:read`. Example:

```json
{ "decisionId": "01JD2X8K3N4P5Q6R7S8T9V0W1X", "ruleSet": "card-fee", "version": 2, "hitPolicy": "FIRST",
  "outcome": "OK", "errorCode": null, "clock": "2027-01-15T02:00:00Z",
  "matched": [ { "ruleId": "R2", "position": 2, "conditions": [
      { "input": "tier", "path": "/when/tier", "test": "== 'GOLD'", "value": "GOLD", "masked": false, "missing": false, "result": true },
      { "input": "balance", "path": "/when/balance", "test": ">= 50000000 VND", "value": { "currency": "VND", "amount": 60000000 }, "masked": false, "missing": false, "result": true } ],
      "outputs": { "fee": { "currency": "VND", "amount": 0 } } } ],
  "outputs": { "fee": { "currency": "VND", "amount": 0 } },
  "reasons": [ { "code": "RULE_MATCHED", "params": { "ruleId": "R2", "version": 2 } } ],
  "language": "en",
  "text": "Rule R2 of card-fee version 2 matched because tier is GOLD, which equals GOLD, and balance is 60000000 VND, which is at least 50000000 VND. Result: fee 0 VND." }
```

`matched`, `outputs` and `reasons` are the contract. The reason codes are `RULE_MATCHED`, `NO_RULE_MATCHED`, `UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR`, `VALUE_MISSING` and `VALUE_MASKED`, each with named `params`. `text` is rendered from them through a message catalogue per language, with locale-neutral numbers (no grouping, money in major units with its currency code). English and Vietnamese catalogues ship. Ask for a language with `?lang=vi` or `Accept-Language`; `language` says which one was used. Without a language the engine has, `text` uses `horus.bpm.rules.explain.default-language` (default `en`), and a missing entry falls back to English. The cockpit reads the same catalogues, so the plain-language readings and the explanations use one wording.

To change the wording or add a language, put `horus-bpm/rules/explain_<language>.properties` (UTF-8, one file per language) on the host's class path; its entries replace the engine's, and no code is needed. To replace the renderer entirely, declare a `DecisionExplainer` bean ([[Configuration and Extension Points]]). `reasons` never change.

The explanation is built from the log entry and the stored version only: nothing is evaluated again, a later version does not change it, and the same decision always gives the same answer. Rows that did not match are not explained. Conditions are listed in column order. A condition inside an `any` group has `result: null`, because the log does not say which alternative held. Masked inputs show as `hmac:<keyId>:<hex>` with `masked: true`, and missing inputs as "has no value". A decision with no match reads "No rule of card-fee version 1 matched. No output was produced." Templates per rule set can override the wording in M3 (see [[Roadmap]]).

## Read a rule set's history and export evidence

`GET /api/bpm/rules/v1/rule-sets/card-fee/history` (`rules:read`) lists every version, newest first, with its status, effective dates (`effectiveTo` is when it was retired), who created, requested, approved or last rejected it, the comments, and its events oldest first. The first page also lists the rule set's own events, such as evidence exports. Follow `nextCursor` for older versions.

An auditor exports evidence with one call:

```http
POST /api/bpm/rules/v1/rule-sets/card-fee/evidence
Idempotency-Key: 4d0f1c2e-ha-2027-01-20
Content-Type: application/json

{ "format": "csv", "versions": [1, 2], "decisionIds": ["01JD2X8K3N4P5Q6R7S8T9V0W1X", "01JD3A0B1C2D3E4F5G6H7J8K9M", "01JD3B0B1C2D3E4F5G6H7J8K9M"] }
```

- The engine writes the file, so it is reproducible. It starts with who exported it, when, for which tenant and `Content SHA-256`, the hash of its content. In CSV the content is everything from the column line on; in JSON it is the `content` member as written. The same records always give the same content hash.
- CSV is one file with a `record` column, `version event` or `decision`. A cell that would run as a spreadsheet formula is prefixed with `'`.
- Masked inputs stay `hmac:<keyId>:<hex>`.
- The response carries `X-Evidence-Sha256`, the content hash, and `Content-Digest`, the SHA-256 of the whole file, so you can check the saved file.
- Decisions need `rules:log:read` as well. At most 100 versions and 100 decisions, all of this rule set; a decision of another rule set is refused, naming it. A file over 8 MB is `422 EVIDENCE_TOO_LARGE`.
- The export itself is recorded: an `EVIDENCE_EXPORTED` entry in the rule set's history and the event `rules.evidence.exported.v1`. A repeat with the same `Idempotency-Key` returns the same file and records nothing.

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

## Find decisions by case reference or by a plain input

Complaints arrive with a case number, not a decision id. Send your own reference as `X-Correlation-Id` (or the `correlationId` of a batch item) when you evaluate, and an auditor holding `rules:log:read` finds every decision of that case across all rule sets of the tenant:

```bash
curl -s "$HOST/api/bpm/rules/v1/decisions?correlationId=LA-2027-000123&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z" \
  -H "Authorization: Bearer $AUDITOR_TOKEN"
```

Add `ruleSet=card-fee` to narrow it. The period is at most 31 days, newest first, 200 a page. A reference is 1 to 128 characters of `A-Z a-z 0-9 . _ : -`. A call without `X-Correlation-Id` gets a generated id, so a host that wants case search must send its reference. A correlation id may appear in a URL and in access logs: never use a national id as one.

To find decisions by the exact value of an input that is stored in plain text, post the value, which never sits in a URL:

```bash
curl -s -X POST "$HOST/api/bpm/rules/v1/decisions:find-by-input" \
  -H "Authorization: Bearer $AUDITOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"ruleSet": "loan-pricing", "input": "productCode", "value": "HOME-30Y",
       "from": "2027-01-01T00:00:00Z", "to": "2027-02-01T00:00:00Z"}'
```

Values compare by canonical form: money by currency and minor units, a number by exact value (`30000` equals `30000.00`), a string by code point, with no case folding or trimming. An input that is masked, by `mask: true` or by `decision-log.mask-default`, is refused with `400 QUERY_INVALID` and a pointer to the masked search above, never answered with no rows, so this search cannot be used to test a masked value. Neither search writes to the decision log.

## Choose transactional or async logging

![Decision log modes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/decision-log-modes.svg)

| Mode | Behaviour | Use it for |
|---|---|---|
| `transactional` (default) | One insert in the caller's transaction. A rolled-back business transaction leaves no decision | Everything where the decision has a business effect |
| `async` (1.1, opt-in) | The entry is written to a queue table in the caller's transaction, so it is durable at the caller's commit and rolls back with it, and a relay moves it into the log within about a second, marked `ASYNC` | High-volume read paths, for example a fee preview on every page load |

Set `horus.bpm.rules.decision-log.mode`. In `async` mode:

- **Nothing is lost.** The queue row commits with your business data; a rolled-back transaction leaves nothing. The relay deletes a batch from the queue and inserts it into the log in one statement, so a row is in exactly one table. The queue is append-only: the database refuses every update and truncate of it, and a delete unless the same decision is already in the log.
- **A bad row does not stall the others.** A row the log refuses is retried with a growing wait (`decision-log.async.retry-backoff`, doubled up to `max-retry-backoff`) up to `max-attempts` times, then dead-lettered. Rows behind it go on; the log needs no order among decisions. A dead letter is listed at `GET /decision-log/dead-letters`, counted in `horus.rle.decision.log.queue.dead`, and the alert rule in `rule-engine/docs/alerts/decision-log-queue.rules.yml` fires on it.
- **Retry or resolve needs two people.** `POST /decisions/{id}/dead-letter:request` (`rules:log:operate`) asks, with a reason; `:approve` or `:reject` (`rules:log:approve`, another person) decides. An approved retry puts the row back in the relay's queue; an approved resolve closes it. Every step is an audit event and an append-only row; nothing deletes the decision.
- **Nothing is dropped silently.** When more than `decision-log.async.max-backlog` decisions wait, the next evaluation is refused with `503 DECISION_LOG_BACKLOG` and `Retry-After: 1`; see [[Common Error Codes]]. Alert on `horus.rle.decision.log.queue.depth` (and `.queue.oldest.age`) well before the limit; `.refused` and `.relay.failed` count the refusals and failed runs.
- **Reading.** A caller that holds a decision id reads and replays it at once (the read finds it in the queue), with the same result as after the relay; replay is unchanged. A search by rule set, period, correlation id or input lists a decision only once it is relayed, so it lags by about `async.poll-interval`.
- **Operation.** The relay runs on every replica and drains the queue at a clean shutdown. The database user needs `INSERT`, `SELECT` and `DELETE` on `rle_decision_queue`. The `ruleEngine` health contributor reports the job `decisionLogRelay`.

To turn logging off entirely, set `decision-log.enabled: false`; decisions then return no `decisionId`.

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
- **Upgrade note.** A host that upgrades without setting retention starts and keeps every decision, as before, and now sees the WARN. Before 1.0 nothing refuses start-up. **From 1.0 the setting is required** (PD-050, PD-130): with `decision-log.retention` unset, start-up stops with a message that names the setting and offers `keep-forever` or whole months of at least 12, such as `P12M`. Only the `dev` profile on its own (optionally with `test`) is exempt. Choose before you move to 1.0; `keep-forever` is the answer when you must keep everything and costs nothing to say. The engine reads its version from `rule-engine.properties`, which the build writes next to its classes, never from the jar manifest (#450): repackaging the engine into a shaded jar or running it from a class directory does not skip the requirement, and a version that cannot be read is treated as 1.0 or later.
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
| `rules.evidence.exported` | Every evidence export: who exported which versions and decisions, the format and the content hash |

These are the events an audit consumer usually needs; the full list, the envelope (`eventId`, `correlationId`, `causationId`, `producer`, `schemaVersion`) and the schema files are in [[Rule Engine Decision API]].

Events are delivered through the `RuleEventPublisher` SPI, which the host implements to forward them to its own audit or messaging system. The engine itself never calls the network (TR-EMB-05).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| A decision you saw in the response is missing from the log | The business transaction rolled back after the evaluation (transactional mode) | Expected behaviour; the business effect did not happen either |
| Plain values appear where you expected a hash | `mask: true` was added after the decisions were logged | Masking applies from the version where it is declared; older entries are immutable |
| `GET /decisions?...` returns `403` | Caller lacks `rules:log:read` | Grant the permission |
| Query is slow | A range close to the 31-day limit with no other filter | Narrow `from` and `to` to one monthly partition, or add `outcome` or `matchedRuleId` |
