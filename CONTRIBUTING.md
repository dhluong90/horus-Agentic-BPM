# Contributing

Thank you for your interest. Until the first release the maintainers drive the roadmap; issues and small pull requests are welcome. The full process is on the wiki page [Contributing](../../wiki/Contributing); this file is the short form.

## Before you write code
- Open an issue from the templates (Bug report, Feature request, Design proposal) and wait for a maintainer to label it `status:accepted`.
- Security vulnerabilities go to the address in `SECURITY.md`, never to a public issue.

## While you work
- One pull request per issue, from a `feat/<module>/<issue>-<slug>` branch, with Conventional Commits (`feat(rule-engine): add collect hit policy (#12)`).
- Tests are required and named by the specification id they prove (`<module>/docs/SPEC.md`), for example `RLE_003_collect_hit_policy_aggregates_matched_rules`. `python3 tools/spec-coverage.py` checks this; statements not delivered yet are listed in `<module>/docs/spec-pending.txt`.
- An engine database migration takes the next number above the highest of that engine on `main` when it merges; a migration on `main` is never edited. `python3 tools/check-migration-order.py` checks this. See "Database migrations" on the wiki page.
- No reflection, scripting engines or I/O in rule expressions or definitions. Such pull requests are declined.
- Third-party dependencies must be Apache 2.0, MIT or BSD licensed. `mvn -B verify` fails on any other licence; `tools/scan-dependencies.sh` fails on a high or critical vulnerability. See "Dependency checks" on the wiki page.

## Documentation ships with the code
- Any change to a REST path, configuration key, error code, metric name or architectural invariant updates the owning wiki page (`wiki/`) and the module docs in the same pull request.
- Diagram sources live in `wiki/diagrams/*.mmd`; render them to `wiki/images/*.svg` with `tools/render-diagrams.sh` and embed the SVG. Never add an image without its source.
- Each fact has one owning page; link to it instead of restating it.

## Contributor licence agreement
The project is public under a noncommercial licence and licensed commercially by HORUS, so contributions require the CLA in `CLA.md`. Add `I agree to the CLA in CLA.md` to your first pull request.

## Releases
Every module shares one project version (semver), released under one tag `vX.Y.Z` (PD-087). See `docs/RELEASING.md`.
