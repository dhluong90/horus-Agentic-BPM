# Documentation site

A static, searchable site generated from the [`wiki/`](../wiki) folder. The wiki stays the source; edit pages there.

## Build it

Needs Node 22.

```bash
cd site
npm ci
npm run build
```

Expected: `32 pages, 11 sidebar groups`, then `build complete`. Open the result with `npx vitepress preview .generated`, or serve `.generated/.vitepress/dist` with any static server.

`npm test` runs the generator's tests. `npm run check` checks the wiki without writing files.

## What the generator does

[`prepare.mjs`](prepare.mjs) copies each wiki page into `.generated/`, turns `[[Page]]` and `[[Text|Page]]` links into site links, and copies the images so that no page loads anything from another origin. The sidebar follows [`wiki/_Sidebar.md`](../wiki/_Sidebar.md). The build fails on an unknown link target, a page missing from the sidebar, or a page listed twice.

Search is built into the site and runs in the browser. There are no cookies and no analytics; the CI job fails if the built pages load a script, style or image from another origin.

Set `SITE_BASE` to build for a sub-path, for example `SITE_BASE=/1.0/ npm run build`.

## CI

The workflow [`docs-site.yml`](../.github/workflows/docs-site.yml) builds the site on every pull request that touches `wiki/` or `site/`. It also runs the commands of the Getting Started page in order (`tools/extract-guide-commands.py`). It publishes nothing.

## Licences

Direct packages: VitePress 1.6.4, vitepress-plugin-mermaid 2.0.17 and Mermaid 11.17, all MIT. Their transitive dependencies are not checked: the repository's licence gate (job "Dependency licences, SBOM and vulnerabilities") scans Maven dependencies only, and the site is a build tool that is not shipped in any jar. Wiring `site/` into a gate is a follow-up.

## Not done

Publishing under `/1.0/` with `latest`, and the version switcher, are not set up. They would publish the site publicly; see the pull request.
