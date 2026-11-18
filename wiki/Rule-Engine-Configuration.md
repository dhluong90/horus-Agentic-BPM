# Configuration

All properties under `horus.bpm.rules`. _Design preview._

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Register the engine |
| `schema-prefix` | `rle_` | Table prefix in the host database |
| `migrate-on-start` | `true` | Run the engine's Flyway migrations at start-up |
| `definitions` | unset | Resource pattern of YAML rule sets to load as drafts at start-up (and auto-publish when `definitions-auto-publish: true` for development only) |
| `rest.enabled` | `true` | Expose the REST API |
| `rest.base-path` | `/api/bpm/rules` | |
| `decision-log.enabled` | `true` | |
| `decision-log.mode` | `transactional` | `transactional` or `async` |
| `decision-log.mask-default` | `false` | Mask inputs unless the rule set says otherwise |
| `evaluation.max-rows` | `5000` | Guard against pathological tables |
| `evaluation.regex-timeout-ms` | `50` | |
| `maker-checker.enabled` | `true` | Setting `false` is refused unless `profile: dev` |
| `tenancy.resolver` | `security-context` | `security-context`, `header:X-Tenant-Id` (behind a gateway), or a bean name |
| `clock` | system | Override for tests |

Permissions used by the REST layer (map them to your roles): `rules:evaluate`, `rules:read`, `rules:author`, `rules:approve`, `rules:simulate`, `rules:log:read`.
