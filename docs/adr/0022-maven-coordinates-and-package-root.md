# ADR-0022: Maven groupId and Java package root are `io.github.dhluong90.bpm`

- Status: accepted, 2026-11-19
- Date: 2026-11-19
- Deciders: maintainers; accepted by the product owner (PD-070 option 1)
- Module: project

## Context
Maven Central accepts a `groupId` only under a namespace the publisher has verified: a reversed domain proven by a DNS record, or `io.github.<account>` proven through that GitHub account. The first build used `com.horus.bpm` for the `groupId` and the package root. The domain `horus.com` belongs to an unrelated company, so `com.horus` cannot be verified by this project. A package root that names a domain the project does not control can be flagged by bank reviewers and dependency scanners, and changing it after the first publish breaks every adopter.

## Decision
The `groupId` of every published artifact and the root of every Java package is `io.github.dhluong90.bpm`; packages are `io.github.dhluong90.bpm.rules.*` and `io.github.dhluong90.bpm.process.*` (ADR-0016). The rename of the POMs, the BOM, the example host and the Java packages is one change in code before Sprint 1 code starts (7 Dec 2026); the namespace is registered before the release slice of Sprint 3.

## Options considered
1. `io.github.dhluong90.bpm` for both (chosen). Verifiable today at no cost; matches the repository's public address. The coordinates name a personal GitHub account; moving later needs a Maven relocation POM and a breaking package rename.
2. A domain the project registers, reversed, for both. The most durable coordinates; a domain registration, a DNS record and a trademark check before 4 Dec 2026.
3. Publish under `io.github.dhluong90.bpm` and keep the `com.horus.bpm` package root. No rename now; the package root claims a domain owned by another company.
4. Keep `com.horus.bpm` for both. Maven Central refuses the namespace; the M1 publish is blocked.

## Consequences
- Dependency coordinates in the wiki are `io.github.dhluong90.bpm:<artifact>`; `TR-API-07` names the package root.
- Rule engine DESIGN section 7.2 is closed.
- Assumption to test in the M1 pilot conversations: coordinates under a personal account are acceptable to bank procurement. If two of three pilots object, option 2 is applied before 1.0 by a new ADR, while the adopter count is small.

## History
- 2026-11-19: raised in the architecture review of the first engine pull request and decided the same day by the product owner as PD-070 option 1.
