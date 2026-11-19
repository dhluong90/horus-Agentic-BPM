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

The date is stored with the version, not in its content: it is frozen at request-publish and shown to the approver, but it is not part of the content hash and is not exported, so the same rules scheduled for different dates in two environments keep one hash. After approval the version is published but not yet effective. Evaluation always selects the version with the latest `effectiveFrom` at or before the decision's clock, so the change takes effect at that instant on every replica without a wake-up. The engine only records the change of status: a sweep every `lifecycle.sweep-interval` (1 minute by default) retires the superseded version once and emits `rules.version.retired` with reason `SUPERSEDED`. A decision made at 23:59 on 31 Dec 2026 logs version 2; one made at 00:00 on 1 Jan 2027 logs version 3.

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

When a newer approved version comes into effect, the older one stops serving at that instant. Its status follows within one `lifecycle.sweep-interval` (1 minute by default), or at once when the newer version is in effect at its approval. The older version becomes `RETIRED` with one history row and one `rules.version.retired.v1` event: actor `system`, reason `SUPERSEDED`, `supersededBy` the version in force, `approvalMode: system`. Every replica runs the sweep in an ordinary transaction with no advisory lock, and each retirement is still recorded exactly once.

## Give one tenant different rules

A rule set is defined per tenant. Copy a shared default into a tenant by exporting it under one tenant and importing the file under the other, where it arrives as a new draft, and let it diverge. There is no inheritance at evaluation time; evaluation stays deterministic and explainable.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `403 SELF_APPROVAL_FORBIDDEN` | The approver is the identity that requested publication | A different identity with `rules:approve` must approve (TR-SEC-04). The check is in the engine, so the API refuses it even when the cockpit button is bypassed |
| `422 VALIDATION_FAILED` on `:request-publish` | The draft has errors | Fix the rows named in the response; warnings do not block |
| `409 INVALID_STATE` | The call does not fit the current state, for example `:approve` on a draft | Follow the order in the table above |
| `409 VERSION_CONFLICT` on `PUT` | Someone saved the draft after you loaded it | Reload the draft and apply your change again |
| New version not used after approval | `effectiveFrom` is in the future, or the caller pins a version | Check `effectiveFrom`; check for `evaluate(ruleSet, version, input)` calls |
| Replica still answers with the old version | Cache invalidation lag | The engine invalidates compiled caches on publication across replicas (see [[Architecture]]); if it persists, open a bug with both replicas' logs |
