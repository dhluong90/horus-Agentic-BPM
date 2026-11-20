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
| 5. Review the diff | bob | `GET .../versions/3/diff?base=2`, or the Approvals screen in the [[Cockpit]] | Added, removed, changed and moved rows, changed columns, each input's masking status (`nationalId: MASKED to NOT_MASKED`) and any personal-data warning |
| 6. Approve | bob | `POST .../versions/3:approve` with `If-Match` (the `lockVersion` reviewed) and `{ "comment": "Fee change agreed with product" }`, plus `"acknowledgedWarnings": [{ "code": "UNMASKED_PERSONAL_DATA", "input": "nationalId" }]`, the exact pairs you were shown: the warnings the version marks `"requiresAcknowledgement": true`. The engine computes that flag with the same rule as this check, so send exactly the marked pairs and none if nothing is marked | `200`, `"status": "PUBLISHED"`; event `rules.version.published` emitted, with `acknowledgedWarnings` and `maskedByDefaultInputs`. A missing or extra pair is `409 WARNINGS_NOT_ACKNOWLEDGED` with the current set, for example after the personal-data names changed; read it, then resend with exactly those pairs. The history shows the acknowledgement with the approval |

Name the content you reviewed. A version can be sent back, replaced in place and requested again under the same number, and a client that refreshed the lock version could otherwise send a decision made on older content. Add `"expectedCanonicalHash"` (the `canonicalHash` you saw) to the body of `:approve` or `:reject`: when the stored content has another hash the answer is `409 VERSION_CONFLICT` even with the current `If-Match`, nothing is written, and the problem carries `currentCanonicalHash` and `currentLockVersion`. Show the approver the current content again and let them decide; never retry on your own with the new hash. The field is optional, so clients written before it keep working (`RLE-084`).

From step 6 the new version is effective immediately, unless it carries a future `effectiveFrom`. The previous version stays readable and still explains its past decisions.

To reject instead of approve: `POST .../versions/3:reject` with `{ "reason": "..." }`; the reason is required. The version returns to draft and the reason stays in its history (`GET .../versions/3/history`). The requester cannot reject their own request (`403 SELF_APPROVAL_FORBIDDEN`); they take it back with `:withdraw`, so the history keeps "the author took it back" apart from "an approver refused it" (PD-089).

To take back your own request: `POST .../versions/3:withdraw` with `Idempotency-Key`, `If-Match` set to the version's `ETag`, and optionally `{ "reason": "Wrong fee for GOLD" }`. Only the person who requested publication can withdraw, and they need `rules:author`; anyone else, an approver included, gets `403 PERMISSION_DENIED`. The version returns to draft, nothing is published, the version in effect does not change, and the history shows `WITHDRAWN` with who and why. Edit the draft and request publication again to start a new approval. In the cockpit the requester sees Withdraw on their own pending version and never Approve or Reject.

## Roll a change back

History is never rewritten: to undo a fee change, restore an earlier version as the next draft and publish it the normal way.

| Step | Who | Call | Expected |
|---|---|---|---|
| 1. Restore version 1 | alice | `POST /api/bpm/rules/v1/rule-sets/card-fee/versions?copyOf=1` with no body, and an `Idempotency-Key` | `201`, `"version": 3, "status": "DRAFT", "copiedFrom": 1`, the same canonical hash as version 1 |
| 2. Request publication | alice | `POST .../versions/3:request-publish` | `200`, `"status": "PENDING_APPROVAL"` |
| 3. Approve | bob | `GET .../versions/3/diff?base=2`, then `POST .../versions/3:approve` | `200`, `"status": "PUBLISHED"`; version 2 is retired as superseded. Alice cannot approve her own restore (`403 SELF_APPROVAL_FORBIDDEN`) |

The restore works on a version in any status and on a host with `authoring.cockpit-drafts: false`, because it brings in content the bank already approved. It carries no approval, effective date or acknowledgement, so the approver acknowledges again anything the version needs. It is checked as an import of the same document would be today: a version approved before `decision-log.require-explicit-mask` was switched on is refused with `422 VALIDATION_FAILED` naming the input. The history of version 3 shows its `CREATED` row with `"copiedFrom": 1`.

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
| 1. Evaluate at a chosen instant | pricing (`rules:evaluate` and `rules:simulate`) | `POST /api/bpm/rules/v1/decisions/card-fee:evaluate` with `{ "inputs": { "tier": "STANDARD", "txCount": 1 }, "clock": "2026-11-20T10:00:00Z" }` | `200`, `"version": 1`, `"fee": { "currency": "VND", "amount": 50000 }`, `"evalClock": "2026-11-20T10:00:00Z"`, `"clockChosen": true`, and `evaluatedAt` the time it ran |
| 2. The same at the change | pricing | the same with `"clock": "2026-12-01T00:00:00Z"` | `"version": 2`, fee 30,000 VND |
| 3. Replay the November decision after 5 Dec | an (`rules:log:read`) | `POST /api/bpm/rules/v1/decisions/{decisionId}:replay`, no body | `200` with the logged `decisionId`, `"version": 1`, fee 50,000 VND, the same `matchedRuleIds` and `evalClock`; nothing is written |
| 4. Or pin the version and the clock | a service identity with `rules:pin` | `POST /api/bpm/rules/v1/decisions/card-fee/versions/1:evaluate` with the inputs and `"clock": "2026-11-20T10:00:00Z"` | The same version, fee and `matchedRuleIds`, logged as a new decision with `"pinned": true` |

In Java: `engine.evaluate(DecisionRequest.of("card-fee", input).withClock(at))`, `.withVersion(1)` to pin, and `engine.replay(decisionId)`.

- A chosen `clock` needs `rules:simulate`; without it the call is `403 CLOCK_NOT_ALLOWED`. Leave `clock` out to use the host clock.
- Pinning needs `rules:pin`, which no role holds by default: a pin bypasses the version in force, so grant it only to a service identity. A pinned version must be published; a retired one is refused unless `evaluation.pin-retired` is on, and still replays. A draft is `409 INVALID_STATE`: simulate it instead, which writes no decision row.
- A decision at a chosen clock is marked back-dated: `"clockChosen": true` in the response, and an `eval_clock` different from `created_at` in the log.
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

How many people a retirement needs depends on `horus.bpm.lifecycle.retire-approval`, shared with the Process Engine ([[Rule Engine Configuration]]). `/api/bpm/meta` shows the mode in force.

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

From the instant of the retirement no replica selects version 3: every evaluation selects in the database. Callers then receive `404 NO_EFFECTIVE_VERSION`, unless an older version is still in force. Decisions already logged keep pointing at version 3 and replay. An evaluation with a clock before the retirement still selects version 3 (ADR-0025), and version 3 stays readable, with `retiredAt` in its response.

- **The last version in effect needs a confirmation.** If no other version would remain in effect, send `{ "reason": "Tet promotion ended", "confirmNoVersionInEffect": true }`; without it the answer is `409 LAST_VERSION_IN_EFFECT`, naming the rule set. The history row and the event record `noVersionInEffect: true`. A host may refuse it outright with `horus.bpm.lifecycle.retire-last-version: refuse` (PD-101).
- **No older version comes back.** Versions that version 3 had superseded are retired with it, as `SUPERSEDED`, in the same transaction.
- **A scheduled change can be cancelled.** Retiring a version whose `effectiveFrom` is still ahead leaves the version in force serving.
- **A superseded version retires on its own.** Retiring a version that a newer version in effect already supersedes is `409 INVALID_STATE`, naming the newer version; nothing is written.

`Idempotency-Key` and `If-Match` are required: without `If-Match` the answer is `428 PRECONDITION_REQUIRED`, and without the key `400 INPUT_INVALID`. A retired version never changes state again; to use its rules again, export it and import the file as a new version.

![Retirement by supersession on two replicas, and by hand](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/retire-and-supersede.svg)

### Automatic retirement of a superseded version

When a newer approved version comes into effect, the older one stops serving at that instant. Its status follows within one `lifecycle.sweep-interval` (1 minute by default), or at once when the newer version is in effect at its approval. The older version becomes `RETIRED` with one history row and one `rules.version.retired.v1` event: actor `system:supersession`, reason `SUPERSEDED`, `supersededBy` the version in force, `approvalMode: system`. Every replica runs the sweep in an ordinary transaction with no advisory lock, and each retirement is still recorded exactly once.

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
