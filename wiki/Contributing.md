# Contributing

See `CONTRIBUTING.md` in the repository for the full text. In short: open an issue from the templates, wait for `status:accepted`, one pull request per issue with Conventional Commits and tests named by specification id, documentation in the same pull request, and the CLA line (`I agree to the CLA in CLA.md`) in your first pull request. Changes to this wiki are made by pull request to the `wiki/` folder; a workflow publishes it.

Security issues go to the address in `SECURITY.md`, not to public issues.

## Documentation standard
- A page opens with one sentence saying what it is for, then a diagram when it describes a mechanism, then short sections. Prose stays under about 400 words; reference material goes in tables.
- Diagrams are Mermaid sources in `wiki/diagrams/*.mmd`, rendered to `wiki/images/*.svg` with `tools/render-diagrams.sh` (shared theme in `wiki/diagrams/theme.json`). Pages embed the SVG; never paste an image without its source.
- Names are consistent with [[Concepts]]. Requirement ids (`TR-…`), specification ids and ADR numbers are stable and quoted, never paraphrased.
- Every page states its status when it describes behaviour that is not yet implemented.
