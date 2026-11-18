# ADR-0009: Compiled-version caches stay coherent through LISTEN/NOTIFY with a version-epoch bound

- Status: proposed
- Date: 2026-11-18
- Deciders: maintainers
- Module: project

## Context
Each replica compiles published rule set versions and process definitions once and caches them in memory. Publishing or retiring a rule set version on one replica must reach every other replica, and a decision served from a stale cache would log a version that is no longer effective. The project has no message broker (ADR-0004). Many hosts run behind PgBouncer in transaction mode, where `LISTEN` does not work.

## Decision
An approve (publish) or retire increments `rle_rule_set.cache_epoch` in the same transaction that emits `rules.version.published.v1` or `rules.version.retired.v1`, and calls `pg_notify('horus_rle_cache', ...)` after commit. Each replica keeps one session-mode connection listening when `cache.listen-notify` is `true` and evicts the entry on receipt. Independently, the scheduler thread sweeps `cache_epoch` for every cached tenant every `cache.epoch-check-interval` (default 5 s) and evicts entries whose epoch moved. The sweep is the staleness bound; NOTIFY only makes it faster. The cache is bounded by `cache.max-entries` with LRU eviction.

Process definitions need no invalidation channel. A deployed definition version is immutable, running instances pin their version, and the start transaction resolves the version to start from `pre_definition`. The compiled-definition cache is keyed by `(tenant, key, version)` and so never serves stale content; it is bounded by the same LRU rule.

## Options considered
1. NOTIFY plus epoch sweep (chosen): sub-second in the common case, 5 s bound everywhere, one small indexed read per tenant per interval, no new infrastructure.
2. NOTIFY only: fails silently behind transaction-mode poolers and after a dropped listener connection.
3. TTL only: no listener connection, but stale for the whole TTL; a short TTL recompiles large tables often.
4. Broker topic: reliable fan-out, but a new dependency for every host.
5. No cache, compile per call: simplest, but a 1 000-row table compiles in up to 500 ms (`TR-PERF-02`), far outside the 100 us evaluation budget.

## Consequences
Hosts behind transaction-mode poolers set `cache.listen-notify=false` and accept the 5 s bound. The sweep cost grows with tenants x replicas / interval; above about 1 000 reads/s the interval is raised or the sweep is limited to recently evaluated tenants. Decisions always log the version they used, so a stale window is auditable. Verified by `TR-ASY-04`, `TR-PERF-06` and `RLE-010`.

Aligned on 2026-11-18 with `rule-engine/docs/DESIGN.md` (event types, `cache_epoch` on approve and retire) and `process-engine/docs/DESIGN.md` (no definition epoch). Status stays proposed until the maintainers accept it.
