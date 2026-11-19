# ADR-0016: Java API conventions

- Status: accepted, 2026-11-19
- Date: 2026-11-18 (proposed), 2026-11-19 (accepted)
- Deciders: maintainers; accepted by the product owner
- Module: project

## Context
The Java API is the primary product surface: the host developer is the first user, and from 1.0 the API is frozen (X-F-18). Nothing today defines the API style, nullability, exceptions, immutability, packaging or the compatibility promise, so each module would choose its own. Fixing these before M1 code costs a review; fixing them after adopters compile against the API costs a major release.

## Decision
| Topic | Convention |
|---|---|
| Entry points | One interface per job: `RuleEngine`, `VersionService`, `DecisionLogQuery`, `ProcessEngine`, `DefinitionService`, `TaskService`, `IncidentService`, `ProcessQueryService`, `MigrationService`. Spring beans in a host; `RuleEngines.builder()` and `ProcessEngines.builder()` without Spring. Verbs for commands (`evaluate`, `start`, `complete`), nouns for results. No `Manager`, `Helper` or `Util` types |
| Values and requests | Records with validating canonical constructors for small values (`Money`, `TenantId`, `BusinessKey`, `IdempotencyKey`); static factories named by what they take: `of` for values with one natural representation (`TenantId.of(String)`, `BusinessKey.of(String)`, `IdempotencyKey.of(String)`); `Money` has no `of` and no public constructor without a unit in its name, only `Money.ofMinor(String currency, long minorUnits)` and `Money.ofMajor(String currency, String amount)`, where `amount` is a decimal string (never a `double`), more fraction digits than the ISO 4217 scale of the currency throw `IllegalArgumentException`, and construction never rounds (rule-engine `DESIGN.md` section 3.4); builders for requests with optional fields (`DecisionRequest`, `StartRequest`, `ImportRequest`, `TaskQuery`) |
| Immutability | Every value and result is a record or final class, deeply immutable (`List.copyOf` in constructors) |
| Results | Sealed result types where outcomes differ (`DecisionResult.outcome()`: `Matched`, `NoMatch`, `Failed`), so callers use exhaustive `switch` |
| Nullability | JSpecify 1.0 `@NullMarked` on every `api` and `spi` package; `@Nullable` where null is allowed; NullAway on Error Prone fails the build. Collections are empty, never null. `Optional` only as a return type of lookups, never as a field or parameter |
| Exceptions | Unchecked only, one root `HorusBpmException` with `code()` equal to the REST error code; one subtype per case (`SelfApprovalForbiddenException`, `VersionConflictException`, `IdempotencyKeyReuseException`, `ValidationFailedException` with the problem list). Expected decision outcomes (`UNIQUE_HIT_VIOLATION`, `EVALUATION_ERROR`) are returned in the result because they are logged decisions; `evaluateAs` throws instead |
| Transactions | Javadoc states for every method whether it joins the caller's transaction, which rows it writes, and whether it may call an adapter (never inside a transaction) |
| Thread safety | Entry points are safe for concurrent use and hold no per-call state; SPI implementations must be thread-safe, and the SPI Javadoc says so |
| No leaks | Public signatures use only JDK types, engine `api` and `spi` types and JSpecify annotations: no Jackson, Spring, JDBC or cache library types. Inputs go through `DecisionInput` and `Variables`, not a JSON tree type |
| Packages | `io.github.dhluong90.bpm.<engine>.api` (hosts call), `.spi` (hosts implement), `.internal` (no promise), under the package root of ADR-0022; `module-info` exports `api` and `spi` only; examples may not import `internal` |
| Compatibility | 0.x: a minor may break, with release notes. From 1.0: a patch is binary and source compatible; a minor is binary compatible and additive, and new SPI methods are `default` methods; breaking changes only in a major. Checked by japicmp (Apache 2.0) against the last release for `api` and `spi`; revapi (Apache 2.0) as the fallback |
| Deprecation | `@Deprecated(since, forRemoval)` and a `@deprecated` tag naming the replacement. The minimum length is pending PD-054 (proposed: one minor release and six months, removal only in a major) |
| Javadoc | Every public type and method in `api` and `spi`: one-sentence purpose, contract, `@param` with units and nullability, `@return`, `@throws` with the error code, thread safety, `@since`, and one `{@snippet}` example per entry point taken from compiled example sources; `-Xdoclint:all -Werror` on those packages |

## Options considered
1. A written convention enforced by build tools (chosen). One style across modules; NullAway, doclint, japicmp and ArchUnit catch violations; some build time and up-front review.
2. Conventions by code review only. No tooling; drift between modules and between contributors, discovered by adopters.
3. Checked exceptions and `Optional` parameters for explicit contracts. Familiar in older codebases; noisy at call sites and awkward with lambdas and streams.

## Consequences
- New build dependencies, build scope only: JSpecify (Apache 2.0, also a compile-time annotation dependency of `api`), Error Prone and NullAway (Apache 2.0 and MIT), japicmp (Apache 2.0). Each gets its one-paragraph justification in the module DESIGN before adoption.
- ArchUnit rules on `..api..` and `..spi..` signatures, and on `internal` imports from examples.
- The engine DESIGN documents' Java API sketches follow this ADR.
- Requirements: `TR-API-01` to `TR-API-08` and `TR-DOC-01` to `TR-DOC-04` are release gates from 2026-11-19; `TR-API-09` stays proposed until PD-054 is decided.

## History
- 2026-11-18: proposed by the maintainers.
- 2026-11-19: accepted by the product owner. The same day the package root changed from `com.horus.bpm` to `io.github.dhluong90.bpm` (PD-070, ADR-0022); the deprecation length stays pending PD-054.
