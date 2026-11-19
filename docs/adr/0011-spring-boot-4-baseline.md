# ADR-0011: Spring Boot 4.x as the primary baseline, 3.5.x tested until 1.0

- Status: accepted
- Date: 2026-11-18
- Deciders: maintainers
- Module: project
- Supersedes in part: ADR-0001 (the Spring Boot version only)

## Context
ADR-0001 set "Spring Boot 3.4+" as the integration baseline. Milestone 1 ships in January 2027. The open-source support calendar published by the Spring team (checked on 2026-11-18 at `api.spring.io/projects/spring-boot/generations`) shows:

| Line | First release | Open-source support ends | Commercial support ends |
|---|---|---|---|
| 3.4.x | 2024-11-30 | 2025-12-31 | 2026-12-31 |
| 3.5.x | 2025-05-31 | 2026-06-30 | 2032-06-30 |
| 4.0.x | 2025-11-30 | 2026-12-31 | 2027-12-31 |
| 4.1.x | 2026-06-30 (current GA 4.1.2) | 2027-07-31 | 2028-07-31 |
| 4.2.x | 2026-11-30, planned (4.2.0-RC2 at the time of writing) | 2027-12-31 | 2028-12-31 |

Every 3.x line is already outside open-source support. Many hosts still run 3.5 under commercial support or while they migrate.

## Decision
Spring Boot 4.x is the primary baseline: the starters build against the newest generally available 4.x minor, and CI tests every 4.x minor that is in open-source support. CI also tests Spring Boot 3.5.x (latest patch) until release 1.0; 3.5 support ends with 1.0. Java 21 stays the baseline with Java 25 in CI, and PostgreSQL 14+ is unchanged; everything else in ADR-0001 stands.

## Options considered
1. 4.x primary, 3.5.x in CI until 1.0 (chosen): the first releases sit on supported lines, and hosts that have not migrated yet can adopt during 0.x. Cost: a second starter test leg and care with the APIs that differ between the two lines.
2. 3.5.x primary: matches more current hosts, but open-source support ended on 2026-06-30, before M1, so security fixes would depend on commercial support.
3. 4.x only: one test leg and no compatibility code, but excludes hosts that are mid-migration during the period when early adopters choose the project.
4. Keep "3.4 and later 3.x": 3.4 left open-source support on 2025-12-31.

## Consequences
- Spring Boot 4 brings Spring Framework 7, Jakarta EE 11 and Jackson 3 as its default JSON library; 3.5 uses Jackson 2. `*-core` depends on Jackson, so the Jackson major used by `*-core`, and how the starters bridge the other one, must be fixed in `rule-engine/docs/DESIGN.md` and `process-engine/docs/DESIGN.md` before M1 code starts. Jackson types must not appear in the public Java API. Fixed on 2026-11-19 by ADR-0021: Jackson 3 inside the cores only.
- Spring Boot 4 splits auto-configuration into smaller modules (Flyway and JDBC support among them). The starters declare those modules explicitly.
- The CI matrix becomes Java 21/25 x Spring Boot 4.x minors in support plus 3.5.x x PostgreSQL 14/16/17, with one job through PgBouncer in transaction mode.
- Dropping 3.5 at 1.0 is announced in the release notes of every 0.x release.
- Requirement `TR-EMB-02` changes accordingly. This ADR is revisited when a new 4.x minor reaches GA or a 3.5 adopter needs support past 1.0.
