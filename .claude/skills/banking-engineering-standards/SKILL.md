---
name: banking-engineering-standards
description: Non-negotiable engineering standards for HORUS banking services and libraries - money handling, idempotency, double-entry postings, audit events, multi-tenancy, secrets, contracts, migrations, embedded engine safety - and how to review code against them. Use when implementing or reviewing any service, library, migration or API in a banking accelerator.
---
# Banking engineering standards

## Money
- A `Money` value type (currency + minor units). Never float or double anywhere, including tests and UI formatting.
- Rounding rules are explicit per product parameter (half-even by default) and recorded in the rule card.

## Financial operations
- Every write endpoint takes an `Idempotency-Key`; a replay returns the stored result and performs no second effect. Test it under concurrency.
- Money movement is a double-entry posting; a posting whose debits do not equal credits is rejected before persistence.
- Every state change emits an audit event (who, what, when, tenant, before and after). Domain events go through an outbox in the same transaction.
- Reversals are new postings that reference the original; nothing is ever updated or deleted in the ledger.

## Multi-tenancy and security
- `tenant_id` in every table, every index that matters, every query. Taken from the authenticated token, never from the body.
- Maker-checker cannot be satisfied by the same identity, by API or by replaying an approval.
- Personal data is minimised, encrypted at rest, masked in logs and UIs.
- No secrets in code, config, images or logs. OWASP ASVS level 2 baseline.

## Contracts and data
- OpenAPI first; the generated client must build. Breaking changes mean a new major version of the API path.
- Flyway migrations forward-only; destructive changes need a tested rollback step. Prefixed schemas for embedded libraries (`pre_`, `rle_`).

## Embedded engines (Process Engine, Rule Engine)
- State in the host database under the prefixed schema; no separate server.
- Rule expressions use the safe expression language only: whitelisted functions, no reflection, no I/O.
- Process and rule definitions are versioned; running instances pin the version they started on.

## Review checklist
Money type · idempotency · balanced postings · audit event · tenant filter · secrets · contract match · migration rollback · tests named by rule id · docs updated.
