# ADR-0025: Rule set version retirement: single approver with a reason, exactly-once supersession, time-aware selection

- Status: accepted, 2026-11-19
- Date: 2026-11-19
- Deciders: maintainers
- Module: rule-engine

## Context
Three M1 statements need settled lifecycle rules: a hand retirement of a live version (`RLE-026`), automatic retirement when a newer version becomes effective (`RLE-027`), and evaluation with a past clock (`RLE-008`, `RLE-026`). Four-eyes protects publication (`TR-SEC-04`). The emergency single-approver path (PD-012) exists only when a bank turns it on, and it concerns publishing. The engines must work behind a transaction-mode pooler, with no session state and no advisory lock held across transactions (`TR-OPS-05`), and every replica runs the same scheduler. The single `approval_comment` column cannot hold a version that was rejected twice before its approval.

## Decision
1. `:retire` of a `PUBLISHED` version needs one identity with `rules:approve`, a non-blank reason and `If-Match`. It writes a history row and `rules.version.retired.v1`, and retires every older `PUBLISHED` version already effective in the same transaction, so a retire never brings an older version back. `:reject` needs a reason; the requester may reject (withdraw) their own request.
2. Superseded versions are retired by one conditional `UPDATE ... WHERE status = 'PUBLISHED' ... RETURNING`, run in the approve transaction when the new version is effective at once and by a sweep every `lifecycle.sweep-interval` (default `PT1M`). The history row, the event (actor `system`, reason `SUPERSEDED`, `supersededBy`) and the cache epoch are written only for returned rows, so they happen exactly once across replicas. Retirement is never written at evaluation time.
3. The effective version at instant `T` is the highest approved version with `effective_from <= T` that was not retired at `T` (`retired_at IS NULL OR retired_at > T`). The status column is not read.
4. Every lifecycle transition appends a row to `rle_version_history`.

Details: rule engine DESIGN sections 3.3 and 5.

## Options considered
Hand retirement:
1. One `rules:approve` holder with a mandatory reason (chosen). A retire only removes behaviour: the result is the previous behaviour or `404 NO_EFFECTIVE_VERSION`, never a new outcome, and the usual reason to retire is to stop a harmful rule quickly. The reason, the actor, the event and the history row make it auditable. Cost: one person can stop a rule set; the host's own process decides who holds `rules:approve`.
2. Four-eyes on retire (a pending-retire state approved by a second identity). The strongest control; a new state, two calls and a second person on call to stop a bad rule, which slows exactly the case retire exists for.
3. No reason required, as before. Cheapest; an auditor cannot tell why a rule stopped.

Automatic retirement:
1. Conditional update with `RETURNING`, at approval and in a sweep (chosen). Exactly once through the row lock and the status predicate; works behind PgBouncer; at most one interval of status lag, which affects no decision.
2. A session advisory lock elects one replica to run the job. Simple to reason about; breaks behind transaction-mode poolers (`TR-OPS-05`).
3. Retire at evaluation time. No scheduler; a status write and a row lock on the hottest path in the host's transaction.

Selection:
1. Time-aware through `retired_at` (chosen). Past clocks see what was effective then; a hand retirement takes effect from its instant only.
2. Status-based (`PUBLISHED` only). Simple; a past-clock evaluation of a retired version gives `404`, and a superseded version disappears from simulation of its own period.

## Consequences
- New column `rle_version.retired_at`, new table `rle_version_history`, new partial index `idx_rle_version_published`, new property `lifecycle.sweep-interval`, new counter `horus.rle.lifecycle.retired`.
- `SELF_APPROVAL_FORBIDDEN` applies to `:approve` only.
- If pilots ask for four-eyes on retire, it is added as a host setting with today's behaviour as the default, by a new ADR.
- Verified by `RLE-025`, `RLE-026`, `RLE-027`.

## History
- 2026-11-19: decided by the maintainers while closing the design questions of `RLE-025` to `RLE-036`.
