# How to publish, schedule and roll back a version

Change rules without a deployment while the engine enforces maker-checker and keeps every version.

Design preview: describes the 0.1 target.

## Before you start

- States and the meaning of maker-checker: [[Concepts]].
- Two identities: one with `rules:author` (the maker, `alice` below) and a different one with `rules:approve` (the checker, `bob`). Permissions are listed in [[Rule Engine Configuration]].
- Every mutating call below accepts an `Idempotency-Key` header; replaying the same key returns the original response (ADR-0006).

![Version lifecycle](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/version-lifecycle.svg)

## Publish a change

![Publish sequence](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/publish-sequence.svg)

| Step | Who | Call | Expected |
|---|---|---|---|
| 1. Create a draft from the current version | alice | `POST /api/bpm/rules/v1/rule-sets/card-fee/versions?copyOf=2` (or post an edited file as the body) | `201`, body contains `"version": 3, "status": "DRAFT"` |
| 2. Edit the draft | alice | `PUT /api/bpm/rules/v1/rule-sets/card-fee/versions/3` with the full document | `200`; validation errors come back as `422 VALIDATION_FAILED` with row ids |
| 3. Check it | alice | Simulate as in [[Rule Engine Simulation and Testing]] | Report of outputs per sample row |
| 4. Request publication | alice | `POST .../versions/3:request-publish` | `200`, `"status": "PENDING_APPROVAL"`; the draft is now frozen |
| 5. Review the diff | bob | `GET .../versions/3/diff?base=2`, or the Approvals screen in the [[Cockpit]] | Added, removed, changed and moved rows, and changed columns |
| 6. Approve | bob | `POST .../versions/3:approve` with `{ "comment": "Fee change agreed with product" }` | `200`, `"status": "PUBLISHED"`; event `rules.version.published` emitted |

From step 6 the new version is effective immediately, unless it carries a future `effectiveFrom`. The previous version stays readable and still explains its past decisions.

To reject instead of approve: `POST .../versions/3:reject` with `{ "reason": "..." }`; the reason is required. The version returns to draft and the reason stays in its history (`GET .../versions/3/history`). The requester cannot reject their own request (`403 SELF_APPROVAL_FORBIDDEN`); they take it back with `:withdraw`, so the history keeps "the author took it back" apart from "an approver refused it" (PD-089).

## Schedule a change for a date

Set `effectiveFrom` in the document, or in the `:request-publish` body, before requesting publication:

```yaml
effectiveFrom: 2027-01-01T00:00:00+07:00
```

The date is stored with the version, not in its content: it is frozen at request-publish and shown to the approver, but it is not part of the content hash and is not exported, so the same rules scheduled for different dates in two environments keep one hash. After approval the version is published but not yet effective. Evaluation selects the highest approved version whose `effectiveFrom` is at or before the decision's clock, so the change takes effect at that instant on every replica without a wake-up. Selection does not read the status, and a version approved later with an earlier date wins from its own date (ADR-0025). The engine only records the change of status: a sweep every `lifecycle.sweep-interval` (1 minute by default) retires the superseded version once and emits `rules.version.retired` with reason `SUPERSEDED`. A decision made at 23:59 on 31 Dec 2026 logs version 2; one made at 00:00 on 1 Jan 2027 logs version 3.

## Prove a past decision: clock, pin and replay

Every decision row records the version it used and its clock, the instant it reasoned about. With them an auditor or a complaint handler can show months later what a customer was charged, after any number of newer versions.

Example: `card-fee` v1 charges 50,000 VND from 1 Nov 2026, and v2 charges 30,000 VND from 1 Dec 2026.

| Step | Who | Call | Expected |
|---|---|---|---|
| 1. Evaluate at a chosen instant | pricing (`rules:evaluate` and `rules:simulate`) | `POST /api/bpm/rules/v1/decisions/card-fee:evaluate` with `{ "inputs": { "tier": "STANDARD", "txCount": 1 }, "clock": "2026-11-20T10:00:00Z" }` | `200`, `"version": 1`, `"fee": { "currency": "VND", "amount": 50000 }`, `"clock": "2026-11-20T10:00:00Z"`, and `evaluatedAt` the time it ran |
| 2. The same at the change | pricing | the same with `"clock": "2026-12-01T00:00:00Z"` | `"version": 2`, fee 30,000 VND |
| 3. Replay the November decision after 5 Dec | an (`rules:log:read`) | `POST /api/bpm/rules/v1/decisions/{decisionId}:replay`, no body | `200` with the logged `decisionId`, `"version": 1`, fee 50,000 VND, the same `matchedRuleIds` and `clock`; nothing is written |
| 4. Or pin the version and the clock | pricing | `POST /api/bpm/rules/v1/decisions/card-fee/versions/1:evaluate` with the inputs and `"clock": "2026-11-20T10:00:00Z"` | The same version, fee and `matchedRuleIds`, logged as a new decision |

In Java: `engine.evaluate(DecisionRequest.of("card-fee", input).withClock(at))`, `.withVersion(1)` to pin, and `engine.replay(decisionId)`.

- A chosen `clock` needs `rules:simulate`; without it the call is `403 CLOCK_NOT_ALLOWED`. Leave `clock` out to use the host clock.
- A pinned version must have been approved. A retired version still evaluates and replays. A draft is `409 INVALID_STATE`: simulate it instead, which writes no decision row.
- Replay never selects a version: it loads the logged one, so a later change, supersession or retirement cannot alter the answer.
- A decision of another tenant, or an unknown id, is `404 DECISION_NOT_FOUND`.
- A decision whose inputs include a masked value (`mask: true`) is `422 REPLAY_NOT_POSSIBLE`, because the log keeps only `masked`. The response names the inputs and never their values.

![Replay a logged decision](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/replay-decision.svg)

## Cut-offs in the bank's time zone

`today()` and `now()` read the decision's clock, never the server time. `today()` is the clock's date in the rule set's `timeZone`, an IANA id such as `Asia/Ho_Chi_Minh`, or `UTC` when the document names none. The zone is part of the version and of its hash, so the logged version identifies it and a replay uses it again.

At clock `2027-01-15T18:30:00Z`, it is 01:30 on 16 January in Ho Chi Minh City. A `same-day-cutoff` rule `txDate == today()` with `timeZone: Asia/Ho_Chi_Minh` matches `txDate = 2027-01-16`. The same rule without a time zone does not match, because in UTC it is still 15 January.

## Roll back

History is never rewritten, so a rollback is a new version with old content.

1. Export the retired version (`GET .../versions/2/export`, or the export button in the cockpit editor) and import the file as a new draft.
2. Request publication and approve as above.

## Retire a rule set

How many people a retirement needs depends on `horus.bpm.rules.lifecycle.retire-approval` ([[Rule Engine Configuration]]). `/api/bpm/meta` shows the mode in force.

### Default mode: `single`

`POST .../versions/3:retire` with `{ "reason": "Tet promotion ended" }` by an identity with `rules:approve`. One identity is enough, because a retire only stops behaviour and nothing is deleted. The reason, the actor, the time and the mode `single` are recorded in the history row, and `rules.version.retired.v1` is emitted with `approvalMode: "single"`.

### Four-eyes mode (M3, May 2027)

With `lifecycle.retire-approval: four-eyes`:

| Step | Call | Who | Result |
|---|---|---|---|
| 1 | `POST .../versions/3:retire` with `{ "reason": "Tet promotion ended" }` | `rules:approve` | Version 3 stays published and keeps serving. History row `RETIRE_REQUESTED`; event `rules.version.retire-requested.v1` |
| 2a | `POST .../versions/3:confirm-retire` | `rules:approve`, not the requester | Version 3 is retired. History row `RETIRED` with the mode `four-eyes`; event `rules.version.retired.v1` with `approvalMode: "four-eyes"`. The requester's own confirmation gets `403 SELF_APPROVAL_FORBIDDEN` |
| 2b | `POST .../versions/3:cancel-retire` with `{ "reason": "..." }` | `rules:approve` | The request is withdrawn and version 3 stays published. History row `RETIRE_CANCELLED` |

A second retire request while one is pending gets `409 INVALID_STATE`. `:confirm-retire` in `single` mode, or with no pending request, also gets `409 INVALID_STATE`. Every call takes `Idempotency-Key` and `If-Match`.

The emergency single-approver path is not involved in either mode: it governs publication only, and neither setting changes the other.

### What a retirement does

Older published versions are retired with it, so none comes back. Callers then receive `404 NO_EFFECTIVE_VERSION`. Decisions already logged keep pointing at version 3 and replay; an evaluation with a clock before the retirement still selects version 3 (ADR-0025).

## Give one tenant different rules

A rule set is defined per tenant. Copy a shared default into a tenant by exporting it under one tenant and importing the file under the other, where it arrives as a new draft, and let it diverge. There is no inheritance at evaluation time; evaluation stays deterministic and explainable.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `403 SELF_APPROVAL_FORBIDDEN` | The approver is the identity that requested publication | A different identity with `rules:approve` must approve (TR-SEC-04). The check is in the engine, so the API refuses it even when the cockpit button is bypassed |
| `422 VALIDATION_FAILED` on `:request-publish` | The draft has errors | Fix the rows named in the response; warnings do not block |
| `409 INVALID_STATE` | The call does not fit the current state, for example `:approve` on a draft | Follow the order in the table above |
| `409 VERSION_CONFLICT` on `PUT` | Someone saved the draft after you loaded it | Reload the draft and apply your change again |
| New version not used after approval | `effectiveFrom` is in the future, the caller pins a version, or the caller sends its own `clock` | Check `effectiveFrom`; check for `withVersion(...)` or `/versions/{v}:evaluate` calls and for a `clock` in the request |
| `403 CLOCK_NOT_ALLOWED` | The request sets `clock` and the caller lacks `rules:simulate` | Drop `clock`, or call with an identity that holds `rules:simulate` |
| `422 REPLAY_NOT_POSSIBLE` | The decision used a masked input with a value | Masked values are never stored; explain the decision from its log entry instead |
| Replica still answers with the old version | Cache invalidation lag | The engine invalidates compiled caches on publication across replicas (see [[Architecture]]); if it persists, open a bug with both replicas' logs |
