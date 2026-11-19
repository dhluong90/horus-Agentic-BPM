# ADR-0021: The engine cores parse with Jackson 3 as a private dependency

- Status: accepted, 2026-11-19, on the condition below
- Date: 2026-11-19
- Deciders: maintainers; accepted by the product owner (PD-071 option 1)
- Module: rule-engine, process-engine

## Context
`rule-engine-core` and `process-engine-core` read definitions (JSON and YAML) and write canonical JSON. Spring Boot 4 uses Jackson 3 (`tools.jackson`) by default; Spring Boot 3.5, tested until 1.0 (ADR-0011), uses Jackson 2 (`com.fasterxml.jackson`). The two majors use different Java packages and can share one classpath. ADR-0016 keeps every JSON library type out of the public `api` and `spi`, so the choice is internal and can change in a later minor release. What must never change is the serialised form of definitions and decision log rows, because hashes and replay depend on it (`TR-STD-03`, `TR-DET-05`).

## Decision
The cores use Jackson 3 internally and declare it as their only compile dependency; the core boundary rules allow `tools.jackson..` explicitly. On a Spring Boot 3.5 host the engine brings Jackson 3 next to the host's Jackson 2. The engine REST modules read and write bodies with the engine's own mapper, not the host's message converters, so `spring.jackson.*` settings never change engine payloads.

Condition: the Spring Boot 3.5 test leg proves in Sprint 1, in the first pull request that parses definitions, that a 3.5 host resolves Jackson 3 next to its own Jackson 2 without overriding its own Jackson versions and parses a definition (`TR-EMB-07`). If that test fails, the maintainers return with option 3 before that pull request merges, and this ADR is superseded.

## Options considered
1. Jackson 3 inside the cores only (chosen). The cores sit on the line every supported Spring Boot release uses from now on; least work in M1. A 3.5 host carries both majors, and the engine REST endpoints do not follow the host's JSON settings, which the wiki states.
2. Jackson 2 inside the cores. Native on 3.5 hosts; on Spring Boot 4 hosts Jackson 2 is the deprecated line, so the engine adds a second major there instead, and a move to Jackson 3 is still due before or soon after 1.0, with a check that the serialised form does not change.
3. No JSON library in the cores: the cores read a neutral parsed tree through a small port, and each starter supplies an adapter on the host's Jackson major. Zero compile dependencies in the cores and the host's JSON settings followed; two adapters to build and test with number and money precision in both, about 3 to 5 extra days in M1 (assumption).

## Consequences
- Justification for the new compile dependency: Jackson (Apache 2.0) is already present in every Spring host, a streaming JSON and YAML parser is not product logic, and writing one would cost weeks for no gain; only the core, databind and YAML data format artifacts of the 3.x line are used, with `BigDecimal` for every number so no value passes through `double`.
- ArchUnit core rules: `tools.jackson..` allowed in `internal` only, forbidden in `api` and `spi` signatures (`TR-API-06`); `com.fasterxml.jackson.databind..` forbidden in the cores.
- The canonical serialiser (RFC 8785) and the hash do not depend on Jackson defaults: they are tested against the RFC 8785 vectors, so a later change of JSON library cannot change a hash.
- Requirement `TR-EMB-07`; statements `RLE-023` (M1) and `PRE-045` (M2). Rule engine DESIGN section 7.1 and process engine DESIGN section 11.6 are closed.
- Wiki: Getting Started states that the engine REST endpoints use the engine's own JSON settings.

## History
- 2026-11-19: raised in the architecture review of the first engine pull request and decided the same day by the product owner as PD-071 option 1, with the Spring Boot 3.5 coexistence test as the condition.
