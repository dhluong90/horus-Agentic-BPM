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

The CI job "Spec-to-test coverage" runs `python3 tools/spec-coverage.py` (Python 3.9 or later, no build needed) and fails when an id in scope has no test whose name starts with it (`TR-QA-01`).

| Item | Rule |
|---|---|
| Ids in scope | Every `### RLE-*`, `PRE-*` or `CKP-*` statement in a module `docs/SPEC.md`, and every `TR-*` id in the "Requirement-level tests" table of the module `docs/TEST-PLAN.md` |
| Test name | The id in upper case with hyphens as underscores, then `_` and a description: `RLE_003_collect_hit_policy_aggregates_matched_rules`, `TR_EMB_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable`. Java test methods and ArchUnit rule fields count, and so do `it`, `test` and `describe` titles in `*.test.ts` and `*.spec.ts` files |
| Allow-list | `<module>/docs/spec-pending.txt` lists the statements not delivered yet, one `<ID> <milestone>` per line, the milestone as in `SPEC.md` |
| Fails on | An id with neither a test nor an allow-list line; an allow-list line for an id the module does not define; a milestone that differs from `SPEC.md` |
| Warns on | An allow-listed id that has a test. Remove its line in the same pull request |

To deliver a statement: add the test named by its id and delete the id's line from `spec-pending.txt`. To add a statement: add it to `SPEC.md` with its milestone and either its test or an allow-list line.

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

## Releases

Each module is versioned independently and tagged `<module>/vX.Y.Z`; see [[Technical Stack]]. What shipped is recorded in [[Release Notes]].
