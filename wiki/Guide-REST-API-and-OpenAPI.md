# Guide: the REST API and OpenAPI

Both engines expose REST under `/api/bpm`. The OpenAPI files are the contract, and the endpoints and their errors are listed in the reference pages.

| API | Base path | Contract file | Reference page |
|---|---|---|---|
| Rule Engine | `/api/bpm/rules/v1` | [`rule-engine/api/openapi.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/rule-engine/api/openapi.yaml) | [[Rule Engine Decision API]] |
| Process Engine | `/api/bpm/processes/v1` | [`process-engine/api/openapi.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/process-engine/api/openapi.yaml) | [[Process Engine API]] |
| Host metadata | `/api/bpm/me`, `/api/bpm/meta` | [`bpm-host-metadata/api/openapi.yaml`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/bpm-host-metadata/api/openapi.yaml) | [[Host metadata and permissions|Host-Metadata-and-Permissions]] |

Open a contract file in any OpenAPI viewer, or generate a client from it.

## Conventions

| Rule | Detail |
|---|---|
| Commands | A command is `POST .../{id}:verb`, for example `:approve`, `:claim`, `:complete`, `:retry` |
| `Idempotency-Key` | Header on every command that changes state; a replay returns the first answer |
| `If-Match` | The `lockVersion` or `ETag` you read. Missing: `428`. Stale: `409 VERSION_CONFLICT` |
| Lists | Keyset pages: `limit` and `cursor`, answer `{ "data": [...], "nextCursor": ..., "hasNext": ... }`. No total count |
| Errors | RFC 9457 `application/problem+json` with a stable `code` ([[Common Error Codes]]) |
| Tenant | From the signed-in user, never from a body |
| Money | `{"currency":"VND","amount":9000000}` |
| Permissions | Each endpoint names one. `GET /api/bpm/me` lists the caller's, so a screen shows only what the user can do |

Events for every state change leave through the host's outbox; their JSON schemas are in `process-engine/api/events` and `rule-engine/api/schema`.
