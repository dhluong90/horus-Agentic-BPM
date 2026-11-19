# ADR-0023: Rule Engine table names are fixed with the `rle_` prefix

- Status: accepted, 2026-11-19
- Date: 2026-11-19
- Deciders: maintainers; accepted by the product owner (PD-072 option 1)
- Module: rule-engine

## Context
The rule engine design documented a property `horus.bpm.rules.schema-prefix` to change the `rle_` table prefix. The first migration hard-codes `rle_` in every table name, and the Flyway history table is already fixed as `rle_schema_history` (`TR-EMB-06`). Flyway checks every applied migration against a checksum of its file: if the first migration ships with literal names and later gains a placeholder, every existing install fails validation at start-up.

## Decision
The property `horus.bpm.rules.schema-prefix` is removed. Rule Engine tables are always named `rle_*`, as Process Engine tables are always `pre_*`. If an adopter later needs isolation, the stated path is a dedicated PostgreSQL schema as an additive option, which leaves the migration files and their checksums unchanged.

## Options considered
1. Fixed `rle_` names (chosen). One code path, simpler SQL and tests, no checksum risk. A host that already has its own `rle_` tables cannot install the engine in the same schema.
2. Keep the property and put a Flyway placeholder in every table name of the first migration now. The documented promise holds; every migration and SQL statement carries the placeholder forever, the test matrix gains a prefix dimension, and one mistake in a later migration is a defect in the field.
3. Fixed names now, a dedicated schema later if an adopter asks. Same as option 1 for M1; recorded here as the path for isolation, built only on request.

## Consequences
- `TR-EMB-06` states that table names are fixed. The wiki Rule Engine Configuration page drops the `schema-prefix` row; rule engine DESIGN section 1 drops the property.
- Assumptions to test in the M1 pilot conversations: no pilot host uses `rle_` tables or needs two Rule Engine installs in one schema; a dedicated schema can be added later without changing checksums (a Testcontainers run of the first migration into a non-default schema).

## History
- 2026-11-19: raised in the architecture review of the first engine pull request and decided the same day by the product owner as PD-072 option 1, with option 3 as the stated path for isolation.
