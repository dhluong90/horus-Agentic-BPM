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

The workflow [`docs-site.yml`](../.github/workflows/docs-site.yml) runs on every pull request that touches `wiki/` or `site/`. It publishes nothing. Jobs:

- **Build the site from the wiki:** licence check, generator tests, build, and a check that no page loads anything from another origin.
- **Site accessibility (axe):** `cockpit/web/scripts/axe-site.mjs` opens two pages with a diagram and code blocks at 1440x900 and 1440x1200, light and dark. It fails if the Mermaid diagram is not drawn, a code block has no copy button, or axe-core reports a serious violation. Contrast fixes live in [`.vitepress/theme/contrast.css`](.vitepress/theme/contrast.css).
- **Getting Started guide runs:** the commands of the Getting Started page, in order (`tools/extract-guide-commands.py`).

## Licences

The cockpit's licence script runs over the whole tree of `site/` (`node ../cockpit/web/scripts/check-licences.mjs --include-dev --exceptions=licence-exceptions.json`), because every package ends up in the built pages. It allows Apache-2.0, MIT, BSD and ISC. Two packages are listed in [`licence-exceptions.json`](licence-exceptions.json): `khroma` (no licence field, MIT licence file) and `robust-predicates` (Unlicense, a public-domain dedication; awaiting the product owner's decision).

## Not done

Publishing under `/1.0/` with `latest`, and the version switcher, are deferred to #475 (after 1.0). Publishing publicly is the product owner's decision.
