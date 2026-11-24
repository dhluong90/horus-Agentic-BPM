# Troubleshooting

Find your symptom, then follow the link for the full explanation. Every error is a JSON body with a `code`; look at `code` first, then `detail`. All codes are in [[Common Error Codes]].

| You see | Cause | What to do |
|---|---|---|
| `422 VALIDATION_FAILED`, problem `HANDLER_NOT_REGISTERED` | The BPMN names a handler no bean provides | Register a `ServiceTaskHandler` with that `name()` ([[Guide: task handlers and forms|Guide-Task-Handlers-and-Forms]]) |
| `422`, `CORRELATION_KEY_REQUIRED` | A message step has no `horus:correlationKey` | Add the attribute, naming an instance variable ([[Guide: messages, signals and timers|Guide-Messages-Signals-and-Timers]]) |
| `422 MESSAGE_NOT_CORRELATED` | No instance waits for that name and key | Check name and key; the message is not stored, so send it again once the instance waits |
| `403 SELF_APPROVAL_FORBIDDEN` | The person who requested a change tried to approve it | A different person approves ([[Concepts]], four-eyes) |
| `403 PERMISSION_DENIED` | The user's roles do not map to the permission the call needs | Add it under `horus.bpm.<engine>.security.role-permissions`; check `GET /api/bpm/me` |
| `400 CONTENT_HASH_REQUIRED` | An approval without the hash of what was reviewed | Read the version, send `expectedCanonicalHash` (rules) or `expectedDefinitionHash` (processes) |
| `428 PRECONDITION_REQUIRED` | A command without `If-Match` or a required key | Send `If-Match` with the `lockVersion` you read, and an `Idempotency-Key` |
| `409 VERSION_CONFLICT` | Someone changed it after you read it | Reload and decide again |
| `404 NO_EFFECTIVE_VERSION` | No version is active: the definition or rule set is a draft, pending, or retired, or it belongs to another tenant | Approve it, or check the tenant |
| `422 STEP_FAILED`, reason `RULE_SET_NOT_FOUND` | A rule task names a rule set that is not published | Publish it first ([[Getting Started: your own service|Getting-Started-Your-Own-Service]], step 6) |
| `422 STEP_FAILED`, reason `INPUT_VARIABLE_MISSING` | A `horus:input` reads a variable the instance does not have | Send it in `variables`, or fix the expression |
| `400 INPUT_INVALID` | A rule input has the wrong type, often money sent as a number | Send `{"currency":"VND","amount":9000000}` |
| `401` | No or wrong credentials | Check the user and the sign-in method |
| `405` on `.../tasks/$T:claim` in zsh | zsh reads `:c` as a variable modifier | Write `${T}:claim` |
| Nothing was imported at start-up | `definitions.startup-tenants` is empty | Set it, for example `[demo]` |
| Start-up refuses with a `dev`-only setting | `auto-approve` or `dev-tenant` outside the `dev` profile | Remove the setting, or run with `SPRING_PROFILES_ACTIVE=dev` locally only |
| Instance stuck, incident open | A step failed and no boundary caught it | `GET /incidents?status=OPEN`, then retry or resolve ([[Guide: errors and compensation|Guide-Errors-and-Compensation]]) |
| Flyway refuses a non-empty schema | The host added Flyway after an engine created its tables | See Install order in [[Guide: embedding in Spring Boot|Guide-Embedding-in-Spring-Boot]] |

If your case is not here, the `correlationId` in the error body finds the log line. The engine log names the step and the cause.
