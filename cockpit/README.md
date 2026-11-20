# Cockpit

Code: `CKP` · Version: 0.1.0 · Status: pre-alpha

Operations dashboard for both engines, embeddable in a host admin UI.

Documents: `docs/DESIGN.md`, `docs/SPEC.md`, `docs/TEST-PLAN.md`, `docs/adr/`. Public documentation is in the project wiki.

| Part | Where | Build and test |
|---|---|---|
| Web application (React 18, TypeScript 5, Vite) | [`web/`](web/README.md) | `npm ci`, `npm run lint`, `npm run typecheck`, `npm test`, `npm run e2e` in `cockpit/web` |
| Embedded starter (Spring Boot) | `cockpit-spring-boot-starter/` | `mvn -B -pl cockpit/cockpit-spring-boot-starter -am verify` from the repository root, after `npm run build` in `cockpit/web`. Without the bundle the build stops; `-Dcockpit.bundle.skip=true` builds without the cockpit on purpose and skips the example host's cockpit test |
| Host contract schemas | `api/schema/` | The web application generates its `zod` schemas from them (`npm run schemas`) |

