# Contributing

Thank you for your interest. Until the first release the maintainers drive the roadmap; issues and small pull requests are welcome.

## Ground rules
- Discuss before building: open an issue from the templates (Feature request, Bug report, Design proposal) and wait for a maintainer to label it `status:accepted`.
- One pull request per issue, from a `feat/<module>/<issue>-<slug>` branch, with Conventional Commits (`feat(rule-engine): add collect hit policy (#12)`).
- Tests are required and are named by the specification id they prove (`docs/SPEC.md` in each module).
- No arbitrary code execution paths in rule expressions or definitions. Pull requests that add reflection, scripting engines or I/O to the expression language are declined.
- Third-party dependencies must be permissively licensed (Apache 2.0, MIT, BSD).
- Documentation changes (wiki/ and module docs) ship in the same pull request as the code.

## Contributor licence agreement
The project is public under a noncommercial licence and licensed commercially by HORUS, so contributions require the CLA in `CLA.md`. Add `I agree to the CLA in CLA.md` to your first pull request.

## Releases
Each module is versioned independently (semver) and tagged `<module>/vX.Y.Z`.
