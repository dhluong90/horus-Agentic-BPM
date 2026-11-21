# ADR-0025: Rule set version retirement: single approver with a reason, exactly-once supersession, time-aware selection

- Status: accepted, 2026-11-19; amended the same day by PD-080 (approval mode of manual retirement); decision 1 clarified 2026-11-20 (retiring a scheduled version)
- Date: 2026-11-19
- Deciders: maintainers; product manager under delegated authority (PD-080)
- Module: rule-engine

## Context
Three M1 statements need settled lifecycle rules: a hand retirement of a live version (`RLE-026`), automatic retirement when a newer version becomes effective (`RLE-027`), and evaluation with a past clock (`RLE-008`, `RLE-026`). Four-eyes protects publication (`TR-SEC-04`). The emergency single-approver path (PD-012) exists only when a bank turns it on, and it concerns publishing. The engines must work behind a transaction-mode pooler, with no session state and no advisory lock held across transactions (`TR-OPS-05`), and every replica runs the same scheduler. The single `approval_comment` column cannot hold a version that was rejected twice before its approval.

## Decision
1. Manual retirement of a `PUBLISHED` version follows the host setting `lifecycle.retire-approval` (PD-080), values `single` and `four-eyes`, default `single`:
   - `single` (M1): `:retire` by one identity with `rules:approve`, a non-blank reason and `If-Match` retires the version at once.
   - `four-eyes` (M3, last item on the M3 cut list): `:retire` records a pending retirement request with its reason and the version stays `PUBLISHED` and serving; `:confirm-retire` by a second identity with `rules:approve` retires it, and the requester's own confirmation is refused with `403 SELF_APPROVAL_FORBIDDEN`; `:cancel-retire` by any `rules:approve` holder, with a reason, withdraws the request.
   In both modes the retirement writes `rules.version.retired.v1` and a history row that record the mode in force (`approvalMode`), and, when the retired version is already in effect, retires in the same transaction every older `PUBLISHED` version it had superseded, so a retire never brings an older version back. A version retired before its effective date has superseded nothing: only that version is retired, and the version in effect today keeps serving (clarified 2026-11-20, review of #261 and #269). The mode is shown in the host's `/api/bpm/meta` settings. The emergency single-approver path of PD-012 governs publication only and does not apply to retirement; neither setting changes the other. `:reject` needs a reason; the requester withdraws their own request with `:withdraw` (PD-089); a self-reject is refused with `403 SELF_APPROVAL_FORBIDDEN`.
2. Superseded versions are retired by one conditional `UPDATE ... WHERE status = 'PUBLISHED' ... RETURNING`, run in the approve transaction when the new version is effective at once and by a sweep every `lifecycle.sweep-interval` (default `PT1M`). The history row, the event (actor `system`, reason `SUPERSEDED`, `supersededBy`) and the cache epoch are written only for returned rows, so they happen exactly once across replicas. Retirement is never written at evaluation time.
3. The effective version at instant `T` is the highest approved version with `effective_from <= T` that was not retired at `T` (`retired_at IS NULL OR retired_at > T`). The status column is not read.
4. Every lifecycle transition appends a row to `rle_version_history`.

Details: rule engine DESIGN sections 3.3 and 5.

## Options considered
Hand retirement:
1. One `rules:approve` holder with a mandatory reason (chosen as the default `single` mode). A retire only removes behaviour: the result is the previous behaviour or `404 NO_EFFECTIVE_VERSION`, never a new outcome, and the usual reason to retire is to stop a harmful rule quickly. The reason, the actor, the event and the history row make it auditable. Cost: one person can stop a rule set; the host's own process decides who holds `rules:approve`.
2. Four-eyes on retire (a pending retirement confirmed by a second identity). The strongest control; two calls and a second person on call to stop a bad rule, which slows exactly the case retire exists for. Kept as the `four-eyes` mode of `lifecycle.retire-approval` (PD-080), for banks whose policy treats taking a rule out of service as a change; not the default.
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
- `four-eyes` mode (PD-080, M3, built in #213 with migration V18): columns `retire_requested_by`, `retire_requested_at`, `retire_reason` and `retire_confirm_no_version` on `rle_version`, and `retire_requested_by` on the `RETIRED` history row; history actions `RETIRE_REQUESTED` and `RETIRE_CANCELLED`; event `rules.version.retire-requested.v1`; endpoints `:confirm-retire` and `:cancel-retire`; verified by `RLE-038`. If cut from M3 it arrives in 1.1, additively. If two of three compliance contacts in K6 require a second person, the default changes to `four-eyes` before 1.0 by a new decision.
- Verified by `RLE-025`, `RLE-026` (`single` mode), `RLE-027`, `RLE-038` (`four-eyes` mode).

## History
- 2026-11-19: decided by the maintainers while closing the design questions of `RLE-025` to `RLE-036`.
- 2026-11-19: amended by [PD-080](../product/decisions.md#pd-080-second-approver-for-retiring-a-live-rule-version-by-hand): the approval mode of manual retirement is the setting `lifecycle.retire-approval` (`single` default, `four-eyes` in M3); option 2 of "Hand retirement" became the `four-eyes` mode; PD-012 stated as not applying to retirement.
- 2026-11-20: decision 1 clarified. Retiring a version that is not yet in effect leaves the version in effect serving; only an already-effective version's retirement also retires the older versions it superseded.
- 2026-11-20: the `four-eyes` mode is built (#213, migration V18). The request records whether it accepted that no version may remain in effect, because the last-version rule (PD-101) is checked again at the confirmation. `:cancel-retire` writes a history row and no event, as the event list of DESIGN section 3.3 states.
