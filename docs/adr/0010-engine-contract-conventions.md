# ADR-0010: Engine contract conventions

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: project

## Context
Milestone 1 freezes the first public contracts of the engines. Before that, five conventions were inconsistent or unsettled, and each is cheap to change now and expensive after hosts adopt it:

1. Permission strings mixed module-scoped names (`rules:approve`) with global words (`tasks:work`, `incidents:manage`). A later module with its own tasks or incidents would collide, and hosts hard-code these strings in token mappers.
2. `rle_decision` partitioning was described both as present in the DDL and as optional by configuration (`decision-log.partitioning`). Converting a populated table to a partitioned one later is a long, locking migration.
3. A string output in a decision table such as `UNDER_AGE` could be read as a literal or as an identifier, so the same cell meant different things depending on whether an input of that name existed.
4. Problem `type` URIs pointed at a host that does not resolve.
5. `UNIQUE_HIT_VIOLATION` returned `409`, the status the API uses for state conflicts that a client resolves by reloading. A `unique` table matching zero or several rows is an authoring defect; reloading never fixes it.

## Decision
1. **Permissions** are `<module>:<action>`, and `<module>:<resource>:<action>` for sub-resources. Modules are `rules` and `processes`, matching the REST path segment. The full set is: `rules:evaluate`, `rules:read`, `rules:author`, `rules:approve`, `rules:simulate`, `rules:log:read`; `processes:read`, `processes:deploy`, `processes:start`, `processes:task:work`, `processes:task:manage`, `processes:incident:manage`, `processes:migration:manage`, `processes:calendar:manage`.
2. **`rle_decision` is range-partitioned by month from the first migration**, with a default partition for rows outside any monthly partition. The scheduler thread creates partitions 7 days ahead. Configuration controls retention only: `decision-log.retention` (ISO 8601 period, unset by default, meaning keep everything). When set, the scheduler drops whole monthly partitions that lie entirely before `now() - retention`. The `decision-log.partitioning` property is removed. `pre_history` is already partitioned the same way in its first migration.
3. **String outputs keep inner quotes.** A string literal in a `then` cell or an output `default` carries expression-language quotes inside the YAML or JSON string (`"'UNDER_AGE'"`). A bare word (`UNDER_AGE`) is an identifier and fails validation unless an input has that name. There is no auto-quoting by output type, so one rule applies to every cell.
4. **Error `type` URIs** are `https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#<code>`, where `<code>` is the error code in lower case because that is the anchor GitHub generates for a heading (`#unique_hit_violation`). The `code` field is the contract; clients branch on `code` and never parse `type`.
5. **`UNIQUE_HIT_VIOLATION` returns `422`.** `409` is reserved for state conflicts: `VERSION_CONFLICT`, `INVALID_STATE`, `IDEMPOTENCY_IN_FLIGHT`, `RULE_SET_EXISTS`.

## Options considered

Permissions:
1. `<module>:<action>` with `<module>:<resource>:<action>` for sub-resources (chosen): short strings for the common top-level actions, no collisions between modules, existing `rules:*` strings unchanged.
2. `<module>:<resource>:<action>` everywhere (`rules:version:approve`): fully regular, but longer strings and every existing `rules:*` name changes.
3. Keep global words: no change, but collisions with the next module that has tasks or incidents.

Partitioning:
1. Partitioned from the first migration, retention by configuration (chosen): free on an empty table, one code path, retention without database tooling.
2. Optional partitioning by configuration: two schemas to test and migrate, and switching later rewrites a populated table.
3. Never partitioned, delete by date: row deletes on the hottest append-only table, vacuum load, and an update-or-delete path the audit rule forbids.

String outputs:
1. Inner quotes required, bare word is an identifier (chosen): the same grammar as conditions; the validator and the example lint name the fix.
2. Auto-quote cells of string-typed outputs: shorter YAML, but a cell that refers to an input of the same name changes meaning silently, and the rule differs between `then` cells and expressions elsewhere.

Error `type` URIs:
1. Wiki error-code page (chosen): resolves today on the project's own host; the anchor documents the code.
2. A future documentation site: a better long-term home, but no host exists yet and URIs would not resolve until it does. A later move is possible because clients do not depend on `type`.
3. `about:blank`: valid RFC 9457, but gives a reader nothing to follow.

`UNIQUE_HIT_VIOLATION` status:
1. `422` (chosen): the request was well-formed but the published rule set cannot produce a result for it; matches `EVALUATION_ERROR`.
2. `409`: suggests a retry after reload would help, which it never does, and mixes authoring defects into the conflict handling of clients.

## Consequences
- Hosts map the new `processes:*` strings. No release has shipped, so there is no migration for adopters.
- The engine owns one more scheduled task (partition drop). Dropping whole partitions under a configured retention is not an update or delete path for decision rows; with retention unset nothing is dropped.
- Authors write `"'GOLD'"` for string literals. `tools/validate-examples.sh` already reports a bare word that is not a declared input.
- The wiki must keep a `Common-Error-Codes` page with one heading per code; renaming that page breaks every `type` URI, so it is treated as part of the REST contract.
- Clients that treated `409` from `:evaluate` as retryable stop retrying an authoring defect.
- Verified by: `RLE-002` (`422`), schema lint and the example validator (string outputs), the migration test for `TR-OPS-04`, REST tests per error code (`TR-SEC-08`).
