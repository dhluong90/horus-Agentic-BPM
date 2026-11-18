---
name: accelerator-structure
description: The HORUS repository and product-folder layout - every accelerator folder is a complete repository in waiting - with product kinds (service, library with cockpit, library), what goes where, naming, versioning and how to scaffold a new product. Use when creating a product folder, deciding where a file belongs, or preparing a product to be split into its own repository.
---
# Accelerator structure

## The rule
Every product folder under `platform/` or `accelerators/` contains everything about the product: README, CHANGELOG, VERSION, `docs/` (BRIEF, PROCESSES, RULES, DESIGN, THREAT-MODEL, TEST-PLAN, DEMO, RUNBOOK, adr/), `api/` (openapi.yaml, events/), `src/` with its own build file, `ui/`, `deploy/` (Dockerfile, helm/, compose.yaml, seed/), `tests/` (acceptance, contract, performance). No source dependency on any other product folder; shared code is a versioned package from `libs/`. Splitting out later: `git subtree split --prefix=<kind>/<name>`.

## Product kinds
| Kind | `src/` builds | `ui/` | `deploy/` |
|---|---|---|---|
| Service | a Spring Boot service image | screens for the admin shell | Dockerfile, Helm, compose |
| Library + cockpit | a Spring Boot starter package plus a small reference host for tests and demo | the cockpit dashboard | compose for the reference host; no Helm |
| Library | a package | none | none |

## Scaffold
```
tools/new-accelerator.sh <platform|accelerators> <folder> "<Display Name>" <CODE>
```
Then the domain expert and analyst fill BRIEF.md; nothing else is written until the brief is approved.

## Naming
- Folders lower-case hyphenated. Accelerator code 3–4 upper-case letters, recorded in BRIEF.md, used in rule ids and product ADRs.
- Java packages `com.horus.bap.<product>`; Maven groupId `com.horus.bap`.
- Versions semver per product; tags `<kind>/<name>/vX.Y.Z`.
- Program-wide documents stay in `docs/` and never hold product-specific detail.
