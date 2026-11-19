# Cockpit web application

The cockpit's browser application: React 18, TypeScript 5 and Vite, built to static files with no backend of its own. Embedded hosts serve it through `cockpit-spring-boot-starter`.

## Run it

Needs Node.js 22.12 or later.

```bash
cd cockpit/web
npm ci
npm run dev        # http://localhost:5173, with a mock host
```

The dev server answers `cockpit-hosts.json`, `/api/bpm/meta`, `/api/bpm/me` and the Rule Engine endpoints from a mock host (Mock Service Worker), so no engine needs to run. The mock Rule Engine (`src/mocks/ruleEngine.ts`) holds Sao Mai Bank's `card-fee` (v1 published, v2 waiting for approval) and `kyc-risk`. Pick another situation with a query parameter, for example `http://localhost:5173/?scenario=me-forbidden`. The situations are listed in `src/mocks/fixtures.ts`. The production build contains no mock and no registry file.

## Check it

| Command | What it does |
|---|---|
| `npm run lint` | ESLint, strict type-checked rules; inline styles and raw HTML are refused |
| `npm run typecheck` | TypeScript, strict |
| `npm test` | Vitest and Testing Library against the mock host |
| `npm run e2e` | Builds, serves the build with the same security headers as the starter, and runs Playwright on Chromium. Screenshots at 1440x900 and 1440x1200, light and dark, English and Vietnamese, go to `e2e/screenshots/`. Set `COCKPIT_E2E_PORT` to run beside another checkout (default 4173) |
| `npm run e2e:live` | The rule editing journey against a running example host with the real Rule Engine (`LIVE_HOST_URL`, `LIVE_PASSWORD`); see `examples/rule-engine-only/README.md`. Screenshots go to `e2e/screenshots/live/` |
| `npm run schemas` | Regenerates `src/api/generated/` from `cockpit/api/schema`; `npm run schemas:check` fails when they differ |
| `npm run licences` | Fails when a shipped package has a licence other than Apache 2.0, MIT or BSD |

Test names start with the specification id they prove, for example `CKP_001_...`.

## Layout

| Folder | Holds |
|---|---|
| `src/app/config` | Loading and validating `cockpit-hosts.json` |
| `src/app/people.tsx`, `src/app/links.ts` | Display names for people in lists; documentation links |
| `src/app/route.ts`, `src/app/navigation.ts` | Hash routes (`#/home`, `#/rules/...`, `#/approvals`, `#/decisions`, `#/processes`), the menu areas each user may use, and the landing area |
| `src/shell` | The top bar's breadcrumb, the left menu (`NavRail`) and the frame that routes to an area (`AppFrame`) |
| `src/features` | One folder per area: `home`, `rules`, `approvals`, `decisions`, `processes`, and `overview` for the lists that Home and Approvals share |
| `src/app/session` | The session state: registry, `/meta` and `/me` as one state |
| `src/api` | The host client, problem-details parsing and the generated schemas |
| `src/api/rules` | The Rule Engine client written from `rule-engine/api/openapi.yaml`, its response schemas, and paging over the list endpoints |
| `src/features/rules` | Rule screens: list, decision table, minimal draft editor, changes, approval dock, Try it; `model/` holds the pure parts (money, readings, diff, draft state) |
| `src/components` | Design tokens, the header and the state panels |
| `src/i18n` | English and Vietnamese dictionaries |
| `src/mocks` | The mock host and mock Rule Engine used by the dev server, Vitest and Playwright |
| `e2e-live` | Playwright against a running host, no mocks |
| `e2e` | Playwright tests |
