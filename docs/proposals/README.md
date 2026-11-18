# Design proposals

A design proposal is how a feature beyond the current roadmap enters the project. It is a numbered markdown document reviewed in a pull request before any code is written. Accepted proposals turn into ADRs and specification statements, and only those bind the implementation.

## When a proposal is needed

| Needs a proposal | Does not |
|---|---|
| A new step type, hit policy, expression function family or definition field | A bug fix that restores documented behaviour |
| A new public Java API, SPI, REST resource or event type | An additive optional field on an existing response or event, with a `SPEC.md` statement |
| A new module, storage backend or deployment shape | Documentation, tests, refactoring inside one module |
| A breaking change to any contract in [`docs/architecture/overview.md`](../architecture/overview.md) section 6.7 | A dependency version update within the same major version |
| A new runtime dependency, or a change to transaction, locking, tenancy or caching rules | A cockpit change that uses existing API endpoints only |

When unsure, open an issue first and ask.

## Lifecycle

| State | Meaning | Who moves it | Exit criteria |
|---|---|---|---|
| `draft` | Being written; may be incomplete | Author | Every template section filled; at least two options compared; open questions listed |
| `review` | Pull request open for comment | Author | At least 7 days in review; every comment answered or resolved; two maintainer approvals |
| `accepted` | The design is agreed | Maintainers | ADRs and `SPEC.md` statements merged (see below) |
| `rejected` | Not pursued; the reason is recorded in the proposal | Maintainers | Reason written under "Decision" |
| `implemented` | Code, tests and documentation shipped | Maintainers | Every spec statement from the proposal has a passing test with its id; release notes link the proposal |

A proposal may also be `withdrawn` by its author from `draft` or `review`. Rejected and withdrawn proposals stay in the directory so the reasoning is not lost. A proposal is never renumbered or deleted.

## Numbering and files

- File name: `NNNN-kebab-title.md` in this directory, starting from [`0000-template.md`](0000-template.md).
- Number: the highest existing number plus one, taken when the pull request opens. If two pull requests collide, the later one renumbers before merge.
- The proposal header carries its state; the pull request title starts with `Proposal NNNN:`.

## What acceptance produces

An accepted proposal is not itself a specification. Before it moves to `accepted`, the same or a follow-up pull request adds:

1. **ADRs** in [`docs/adr/`](../adr/) for every decision a future contributor would ask "why?" about, using the ADR template with at least two options considered. The proposal links to them; the ADRs link back.
2. **Specification statements** in the affected module `SPEC.md` (`RLE-*`, `PRE-*`, `CKP-*`), each Given/When/Then with a milestone and a test id (`TR-QA-01`).
3. **Design updates** in the affected module `DESIGN.md` (DDL, ports, REST rows, events) and, for cross-module changes, in [`docs/architecture/overview.md`](../architecture/overview.md).
4. **Requirement changes**, if any, in the requirements document that owns the `TR-*` ids.

After that, the ADRs, specs and designs are the source of truth. The proposal is a historical record of the reasoning and is not updated to track later changes.

## Review checklist

Reviewers check the proposal against the architecture rules before approving:

- Each module still reads and writes only its own tables; cross-engine access goes through a public API.
- No network, adapter or model call runs inside an open database transaction or while a row lock is held.
- Every mutating operation is idempotent and leaves an audit record; optimistic locking uses `lockVersion`.
- `tenant_id` is explicit in every new table, index and API, and a body `tenantId` is never trusted.
- Money uses the canonical contract (minor units in JSON, never a floating-point number).
- Contract changes are additive, or a new major version path or `.v2` event type is proposed with a migration path.
- Capacity math is given for any new hot path, lock or table, with the 10x case.
- New runtime dependencies have a permissive licence and a one-paragraph justification.
