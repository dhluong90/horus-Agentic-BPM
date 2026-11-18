# ADR-0002: Engine state lives in the host's database under prefixed schemas

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: project

## Context
A separate engine database or cluster is the main operational cost of existing BPM platforms and prevents transactions that span business data and engine state.

## Decision
Each engine creates and migrates its own tables in the host's PostgreSQL under a prefix (`rle_`, `pre_`), uses the host's `DataSource` and transactions, and never opens its own connections. Append-only tables support monthly partitioning.

## Options considered
1. Host database with prefixed schemas (chosen).
2. Dedicated engine database: isolation, but a second database to operate and no shared transactions.
3. Embedded key-value or log store: fast, but opaque to operators and hard to back up consistently with business data.

## Consequences
Operators have one database. Engine and business state commit together. The engine must be disciplined about index design and migration safety because it shares the host's database.
