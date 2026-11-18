# Proposal NNNN: Title

- State: draft | review | accepted | rejected | implemented | withdrawn
- Author: name or handle
- Created: YYYY-MM-DD
- Modules: rule-engine | process-engine | cockpit | project
- Pull request: link
- Resulting ADRs: none yet
- Resulting spec statements: none yet

## Summary

Two or three sentences: what changes for a user or host developer.

## Motivation

The problem, who has it, and what they do today instead. Link issues or discussions. State what happens if nothing is done.

## Goals and non-goals

- Goals: outcomes this proposal must achieve, each testable.
- Non-goals: related things it deliberately leaves out.

## User-facing design

What an author, operator or host developer sees: definition syntax (YAML or JSON example), Java API, REST endpoints, cockpit screens. Include one worked example end to end.

## Technical design

### Boundaries and ownership
Which module owns the new state and behaviour. New tables (with prefix) and who may read them. Confirm no cross-module database access.

### Contracts
Java API and SPI signatures. REST endpoints with method, path, permission, idempotency and `If-Match` rules, and error codes. Event types (`<module>.<aggregate>.<pastTense>.v1`) and payloads. Definition schema changes and the `schemaVersion` impact.

### Data and migration
DDL sketch with `tenant_id` first in every key and index, retention and partitioning. Forward-only Flyway steps, expand then contract.

### Transactions and concurrency
Which work is sync in-transaction and which is Async Wait-State. Locks taken, for how long, and on which rows. Behaviour on retry and on a crash at each step.

### Security and tenancy
Permissions, tenant resolution, masking of personal data, maker-checker or four-eyes rules, new threats and their controls.

### Scale
Numbers for the new hot path: rate, database time per operation, connections busy, lock hold time, memory, at baseline and at 10x.

### Observability
Metrics, history rows, events and audit fields added.

## Alternatives considered

At least two options, including "do nothing". For each: pros, cons, cost, and why it was not chosen.

| Option | Pros | Cons | Cost |
|---|---|---|---|
| A. Chosen option | | | |
| B. | | | |
| C. Do nothing | | | |

## Compatibility

Effect on existing definitions, stored data, REST clients, event consumers and hosts. Deprecations and the release in which they end.

## Verification

Draft specification statements (Given/When/Then, milestone, test id) and the verification gates they extend: ArchUnit, Testcontainers, property tests, JMH.

## Rollout

Milestone, feature flag or configuration default, documentation pages to update.

## Open questions

Each with an owner and the state it must be answered by.

## Decision

Filled by maintainers when the proposal leaves `review`: accepted or rejected, date, reason, and links to the resulting ADRs and spec statements.
