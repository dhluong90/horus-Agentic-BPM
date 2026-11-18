---
name: issue-protocol
description: How an agent works a GitHub issue in a HORUS program - read, plan, execute, report - with the exact Plan and Completion report formats, status labels and branch naming. Use whenever assigned an issue, asked to pick up a ticket, or when posting progress on one.
---
# Issue protocol

## Sequence
1. **Read** the issue, every linked document (brief, process map, rule ids, design, API operations, ADRs), `CLAUDE.md` and the product README.
2. **Plan**: post one comment headed `## Plan` (format below). If the issue carries `status:plan-approved` (every issue in an approved sprint plan does) and your plan raises no blocking risk, continue at once. Otherwise add `status:needs-plan-review` or `status:needs-decision` and stop.
3. **Execute** on branch `feat/<acc>/<issue>-<slug>` (or `fix/`, `docs/`, `chore/`). Conventional Commits with the product as scope and `(#NN)`.
4. **Pull request** from the repository template; request `program-architect`, and `security-reviewer` when money, identity, tenancy or personal data is touched.
5. **Report**: post `## Completion report` (format below) with real command output. Never close your own issue; the delivery lead merges and closes.

## Plan format
```
## Plan
**Understanding:** the goal and constraints restated in your own words.
**Approach:** numbered steps, each small enough to be a commit.
**Files to change:** list.
**Tests to add:** list, each mapped to an acceptance criterion or rule id.
**Risks and questions:** each marked "blocking" or "proceeding under assumption: …".
**Estimate:** small / medium / large.
```

## Completion report format
```
## Completion report
**Done:** each acceptance criterion → met / not met / changed (with reason).
**Evidence:** commands run and their actual output, screenshots, CI links.
**Not done / follow-ups:** each with a new issue number.
**Docs updated:** list.
**Decisions needed:** list, or "none".
```

## Commands
```
gh issue view NN --comments
gh issue comment NN --body-file plan.md
gh issue edit NN --add-label status:in-progress --remove-label status:plan-approved
gh pr create --fill --template .github/PULL_REQUEST_TEMPLATE.md
```

## Status labels
needs-refinement → needs-plan-review → plan-approved → in-progress → in-review; plus blocked and needs-decision at any point.
