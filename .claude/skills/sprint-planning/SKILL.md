---
name: sprint-planning
description: Drafting a 5-sprint plan of about 10 features per sprint for a HORUS accelerator program, turning it into labelled GitHub issues on the project board, and getting it approved by the product owner. Use when asked to plan sprints, build a backlog for a block, or create the issues for a sprint.
---
# Sprint planning

## Inputs
- Products selected by the product owner (`docs/roadmap.md`).
- Process maps (`PROCESSES.md`) and rule cards (`RULES.md`) of each product: features come from these.
- Design and ADRs: the architect orders features by technical dependency.
- Rejected features from the last sprint report: they go first.

## Output: `docs/sprints/PLAN-<n>.md`
Use the template in `docs/sprints/PLAN-template.md`. For each of the five sprints: a one-line goal and a table of about ten features with issue title, rule ids, roles and dependencies. Then cross-sprint dependencies, risks and assumptions, and decisions needed.

## Sizing
- A feature is one Slice issue reviewable in one sitting. Split anything larger into a parent issue with sub-issues; it still counts once.
- Sprint 1 of a block front-loads infrastructure the others need (schemas, contracts, seed data). Sprint 5 leaves slack for rejected features and packaging.
- Every sprint must end demoable: at least one visible journey, not only plumbing.

## From plan to issues
For every feature, once the plan is approved:
```
gh issue create --template slice.yml --title "<Acc>: <outcome>" \
  --label "acc:<acc>,type:slice,stage:2-build,role:<r>,priority:p1,status:plan-approved"
gh project item-add <project-number> --owner <owner> --url <issue-url>
gh project item-edit --project-id <id> --id <item-id> --field-id <sprint-field-id> --iteration-id <iteration-id>
```
Fill every section of the template body; an issue missing Goal, Context or Acceptance criteria is not created.

## Approval
The product owner approves the plan file (a comment or a commit marking it approved). That single approval is the authorisation for five sprints; individual issues do not wait again.
