# How to contribute

This page owns the contribution process. `CONTRIBUTING.md` in the repository is the short form that GitHub shows when you open a pull request; when the two differ, this page wins and `CONTRIBUTING.md` is fixed.

Until the first release the maintainers drive the roadmap; issues and small pull requests are welcome.

## 1. Open an issue first

| You want to | Template | Label applied |
|---|---|---|
| Report behaviour that differs from this wiki or a module `SPEC.md` | Bug report | `type:bug`, `status:needs-triage` |
| Propose a capability | Feature request | `type:feature`, `status:needs-triage` |
| Change an API, a storage model or a principle | Design proposal | `type:proposal`, `status:needs-triage`; accepted proposals become ADRs in `docs/adr/` |

Wait for a maintainer to label the issue `status:accepted` before you write code. Security vulnerabilities go to the address in `SECURITY.md`, never to a public issue.

## 2. Make the change

| Rule | Detail |
|---|---|
| One pull request per issue | Branch `feat/<module>/<issue>-<slug>`, for example `feat/rule-engine/12-collect-hit-policy` |
| Conventional Commits | `feat(rule-engine): add collect hit policy (#12)`; scope is the module name |
| Tests named by specification id | Every statement in the module `SPEC.md` has a test whose name starts with its id in upper case with underscores, for example `RLE_003_collect_hit_policy_aggregates_matched_rules` (TR-QA-01). Tests that prove a requirement use the same form, for example `TR_EMB_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable`. A test that proves no id carries no id prefix |
| No code execution paths in definitions | Pull requests that add reflection, scripting engines or I/O to the expression language or to definitions are declined ([[Principles]] 4) |
| Permissive dependencies only | Apache 2.0, MIT or BSD; the build fails on anything else (TR-LIC-01) |
| Documentation in the same pull request | Any change to a REST path, configuration key, error code, metric name or architectural invariant updates the wiki page that owns it and the module docs in the same pull request (TR-QA-02) |

## 3. Open the pull request

Use the template. It asks for the linked issue, what changed, the commands you ran with their output, and the checklist above. On your first pull request add the line `I agree to the CLA in CLA.md` to the description; the CLA lets HORUS distribute your contribution under both the noncommercial and the commercial licence. Pull requests without it are not merged.

## Spec-to-test check

The CI job "Spec-to-test coverage" runs `python3 tools/spec-coverage.py` (Python 3.9 or later, no build needed) and fails when a `control` or `semantics` statement, or a requirement id, has no running test whose name starts with it (`TR-QA-01`). Its own tests run with `python3 tools/test_spec_coverage.py`.

| Item | Rule |
|---|---|
| Kind | Each statement has a `- **Kind:** control\|semantics\|other` line after its `Milestone` line. `control`: security, tenancy, audit, money, idempotency, approval, determinism. `semantics`: what an element or a rule does. `other`: the rest (screens, wording, lists, exports, reports). Only the first two need a test; an `other` statement without one is a warning. Requirement ids (`TR-*`) are enforced as before. No new statement needs a test id only to pass the gate |
| Ids in scope | Every `RLE-*`, `PRE-*` or `CKP-*` statement heading (`##` or deeper, three or more digits) in a module `docs/SPEC.md`, and every `TR-*` id in the "Requirement-level tests" table of the module `docs/TEST-PLAN.md` |
| Test name | The id in upper case with hyphens as underscores, then `_` and a description: `RLE_003_collect_hit_policy_aggregates_matched_rules`, `TR_EMB_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable` |
| What counts | Java under `src/test/java`: a method or ArchUnit field annotated `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`, `@Property`, `@Example` or `@ArchTest`, without `@Disabled...` on it or its class. TypeScript and JavaScript in `*.test.*` and `*.spec.*` files (`ts`, `tsx`, `js`, `jsx`, `mjs`, `cjs`, `mts`, `cts`) or a Playwright `testMatch`: `it` and `test` titles, `.each` included, and a `describe` title whose block holds a counted test. Comments, strings, `skip`, `fixme`, `todo`, `fail` and `x`-prefixed forms never count |
| Where tests are searched | The module folder, plus the folders listed for it under `[test-roots]` in `tools/spec-coverage.ini` (`examples/rule-engine-only` for the Rule Engine) |
| Allow-list | `<module>/docs/spec-pending.txt` lists the statements not delivered yet, one `<ID> <milestone>` per line, the milestone as in `SPEC.md`. Changes need a code-owner review (`.github/CODEOWNERS`) |
| Current milestone | `current-milestone` in `tools/spec-coverage.ini`. An allow-list entry at or before it fails, so closing a milestone means raising this one value |
| Fails on | An id with neither a test nor an allow-list line; an allow-list line that is unreadable, repeated, unknown, at or before the current milestone, or with a milestone that differs from `SPEC.md`; a duplicate statement, a statement without a `Milestone` or a valid `Kind` line, or a malformed id heading; a TEST-PLAN "Specification coverage" table whose ids differ from `SPEC.md` |
| Warns on | An allow-listed id that has a test (remove its line in the same pull request); an `other` statement without a test |

To deliver a statement: add the test named by its id and delete the id's line from `spec-pending.txt`. To add a statement: add it to `SPEC.md` and the TEST-PLAN table with its milestone, its `Kind`, and either its test or, for a `control` or `semantics` statement, an allow-list line.

## Database migrations

The engines run Flyway with out-of-order migrations off, so a migration numbered below one a host has already applied stops that host from starting. The CI job "Migration order" runs `python3 tools/check-migration-order.py` on every pull request and every push to `main`; its own tests run with `python3 tools/test_check_migration_order.py`.

| Rule | Detail |
|---|---|
| Which files | `V<n>__<description>.sql` under a `migration/` folder of `rule-engine/` or `process-engine/`, numbered per engine. Example host migrations under `examples/` belong to the host and are not checked |
| Choosing a number | The next number above the highest of that engine on `main` at the moment the pull request merges, skipping retired numbers. Never reserve a number ahead |
| Retired numbers | `tools/migration-retired-versions.txt` lists numbers nothing may use, with the reason: Rule Engine V7, V8 and V9, Process Engine V5 |
| Released migrations | A migration on `main` is never edited, deleted or renamed; moving it to another folder keeps its file name and bytes. Change the schema with a new migration. One exception until the 1.0 release (#479): each engine has one baseline, `V1__baseline.sql`, which may be edited in place, or replaced by a new squash of the engine's migrations; it is never deleted or renamed. The release commit closes the exception (`BASELINE_OPEN` in the check) |
| Fails on | An added migration at or below the highest of its engine on `main`; two migrations of one engine with the same number, on the branch or against `main`; a retired number; a changed migration that is on `main`. The message names the file, the highest number on `main` and the next free number |
| Before merging | The pull request run compares with `main` as it was when the run started. If another migration merged since, merge `main` into the branch, renumber if the check asks, and run the check again. The run on the push to `main` catches any that slip through |

## Documentation standard

This wiki is the `wiki/` folder of the repository; a workflow publishes it on merge to `main`. Pages follow four kinds (tutorial, how-to, reference, explanation) and one canonical owner per fact; see [[Home]] for the map.

| Rule | Detail |
|---|---|
| Opening | One sentence saying what the page is for, then a status note if the page describes behaviour not yet implemented, then short sections |
| Length | Prose under about 400 words; anything with three or more parallel items goes in a table |
| Diagrams | Mermaid sources in `wiki/diagrams/*.mmd`, rendered to `wiki/images/*.svg` with `tools/render-diagrams.sh` (theme in `wiki/diagrams/theme.json`). Pages embed the SVG by absolute URL, `![Alt](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/name.svg)`, because relative paths break between `/wiki` and `/wiki/Home`. Diagrams use the shared `classDef` names `client`, `host`, `engine`, `db`, `external` and `obs`, and dashed zone subgraphs for the Public zone, DMZ, Internal zone and Core and external. Never add an image without its source |
| Names | Terms as defined in [[Concepts]]. Requirement ids (`TR-*`), specification ids (`RLE-*`, `PRE-*`, `CKP-*`) and ADR numbers are quoted, never paraphrased |
| Links | `[[Page Name]]` form; file names use dashes |
| One owner per fact | Endpoints: [[Rule Engine Decision API]]. Properties: [[Rule Engine Configuration]]. Terms: [[Concepts]]. Dates and scope: [[Roadmap]]. Elsewhere, link; do not restate |
| Prose | Active voice, short sentences, concrete values with units and currency, no promotional adjectives, no emojis |

## Dependency checks

Every pull request, every push to `main` and a nightly run execute these checks (CI job "Dependency licences, SBOM and vulnerabilities"). Modules under `examples/` never ship: `examples/pom.xml` sets `maven.deploy.skip`, the one switch that also takes them out of the licence check and the shipped SBOM. No other POM may set it or any other gate setting.

| Check | Rule | Run it locally | When it fails |
|---|---|---|---|
| Licence (`TR-LIC-01`) | Compile, runtime and provided dependencies must be under a licence in `tools/licence-policy/allowed-licences.txt` (Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC). System scope is banned. The build names the library and its licence | `mvn -B verify` | Pick a permissively licensed alternative. If the licence is permissive but spelled differently in the library's POM, add the spelling to `tools/licence-policy/licence-merges.txt` in its own pull request |
| Test-scope FEEL oracle (`TR-LIC-01`, PD-098) | The reference FEEL engine `org.camunda.feel:feel-engine` and its runtime are allowed in test scope only, at exactly the versions in `tools/licence-policy/test-oracle-allow-list.txt` (one `groupId:artifactId:version \| licence \| reason` line each). The check fails on a version that differs, an artifact under `feel-engine` that the file does not name, or a listed artifact that is no longer resolved. The release build fails when `bom-release.json` contains any listed artifact | `mvn -B -ntp -q dependency:tree -Dscope=test -pl rule-engine/rule-engine-core -DoutputFile="$PWD/target/oracle-tree.txt"`, then `python3 tools/check-test-oracle.py tree target/oracle-tree.txt` (tests: `python3 tools/test_check_test_oracle.py`) | A version bump of the oracle, or a change the Spring Boot BOM makes to its runtime, is a pull request that updates the file to the resolved tree, checks the licence of every new or changed artifact against `allowed-licences.txt`, and says so in its description |
| Bills of materials (`TR-SEC-06`) | `target/bom.json` lists what ships; `target/bom-build.json` adds test scope and annotation processors. Both are attached to the CI run as `sbom-cyclonedx` | `mvn -B verify` | Not a gate; they are the input of the scans |
| Vulnerabilities (`TR-SEC-06`) | Shipped dependencies: no known high or critical vulnerability. Build and test classpath: no critical one. A canary SBOM with a known critical advisory must fail every run | `tools/scan-dependencies.sh`, `--build`, `--canary`; needs Grype or Docker | Upgrade to the fixed version, or override the transitive version in `dependencyManagement`. Only when no fix exists may a maintainer add an exception to `.grype.yaml`: one advisory, package and version, with a reason ending in `expires YYYY-MM-DD` |
| Test container images (PD-100) | Every image a test starts is pinned by digest and named once: `bpm.test.postgres.image` and `bpm.test.pgbouncer.image` in the root `pom.xml`, and `POSTGRES_<version>_IMAGE` in `.github/workflows/ci.yml` for the matrix legs. Tests read the property through `TestImages`, which refuses a value without a digest, and name no image. The Ryuk reaper is pinned in each module's `src/test/resources/testcontainers.properties`, which also registers `DigestPinnedImages`: Testcontainers refuses to start any container whose image has no digest. The check fails when a module's tests use Testcontainers and its `testcontainers.properties` does not set `image.substitutor`. Testcontainers 2 refuses a tag and a digest together, so the value is `<name>@sha256:<digest>` with the tag and version in a comment | `python3 tools/check-image-pins.py` (tests: `python3 tools/test_check_image_pins.py`); runs in the CI job "Spec-to-test coverage" | Resolve the digest of the tag from the registry, check it against a second source (the registry manifest and Docker Hub's tag page), and replace digest and comment together. An image tagged `:local` and built in the same job carries the comment `pin-exempt: <reason>`; the comment exempts nothing else |
| Licence notices (`TR-LIC-02`) | Every engine jar carries `META-INF/LICENSE`, `META-INF/COMMERCIAL-LICENSE.md` and the licence manifest entries | `python3 tools/check-dependency-policy.py jar-notices` after the build | Build from the repository root (the `.mvn/` directory marks it); a module must not replace the parent's `<resources>` |

Grype matches Java packages by GitHub advisory and package URL; NVD CPE matching is off. Test scope is not part of the licence check until PD-053 in the [decision log](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/decisions.md) sets the rule for test and build tools; CC0-1.0, ISC and 0BSD are decided with it. Common Spring starters bring in EPL, LGPL and CC0 libraries (`jakarta.annotation-api`, logback, `aspectjweaver`, HdrHistogram), so engine modules depend on Spring modules directly, never on `spring-boot-starter-*`. The gate files are listed in `.github/CODEOWNERS`.

## Releases

Every module shares one project version, released under one tag `vX.Y.Z` (PD-087); see [[Technical Stack]]. What shipped is recorded in [[Release Notes]].

## The documentation site

The site is built from `wiki/` and published at https://dhluong90.github.io/horus-Agentic-BPM/ by `.github/workflows/docs-site.yml`: a push to `main` that changes the wiki or `site/` deploys it to GitHub Pages after the build, origin check and axe pass; pull requests only build.
