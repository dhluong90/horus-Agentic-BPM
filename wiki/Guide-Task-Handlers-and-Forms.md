# Guide: task handlers and forms

A service task calls your code through a handler. A user task shows a form, and the engine checks the answer before the task completes.

## Write a handler

Implement `ServiceTaskHandler` as a Spring bean. The engine finds it by `name()`, never by reflection.

```java
@Component
class BureauScoreHandler implements ServiceTaskHandler {

    @Override
    public String name() { return "bureau.score"; }

    @Override
    public HandlerResult handle(ServiceCall call) {
        Object customerId = call.inputs().get("customerId");
        if (customerId == null || customerId.toString().isBlank()) {
            return new HandlerResult.BusinessError("CUSTOMER_ID_MISSING", "The application has no customer id");
        }
        return HandlerResult.completed(Map.of("score", 720));
    }
}
```

`call.inputs()` holds the step's `horus:input` values and `call.tenantId()` the tenant. The result goes to the step's `resultVariable`. This handler is the one running in [[Getting Started: your own service|Getting-Started-Your-Own-Service]].

| Rule | Why |
|---|---|
| Keep it short and local | A handler runs in the caller's transaction and holds the instance lock. A call slower than `horus.bpm.processes.handlers.slow-call-warning` (200 ms) is logged as `process.step.slow` |
| Return `BusinessError` for expected failures | The process can route it with an error boundary ([[Guide: errors and compensation|Guide-Errors-and-Compensation]]). Throwing an exception rolls the whole call back |
| Call slow or remote systems through a `ServiceAdapter` | It answers `Accepted` and the remote side calls `POST /async-steps/{jobId}:complete` later |
| Make it idempotent | A retry from an incident runs it again |
| Name it before importing | A file that names an unregistered handler is refused with `HANDLER_NOT_REGISTERED` |

`GET /api/bpm/processes/v1/step-catalogue` lists what your host registered, for modellers.

## Forms

Put a JSON file in `src/main/resources/forms/credit-review.form.json` (the folder is `horus.bpm.processes.tasks.form-resource-root`) and name it in the BPMN with `horus:form="credit-review"`:

```json
{
  "schema": {
    "type": "object",
    "required": ["decision"],
    "properties": { "decision": { "enum": ["APPROVE", "REJECT"] }, "reason": { "type": "string" } }
  },
  "hints": { "order": ["decision", "reason"], "widgets": { "reason": "textarea" } }
}
```

`schema` is JSON Schema and decides what a completion may contain; a completion that does not match is refused. `hints` only guide a screen. `GET /tasks/{id}/form` returns the resolved form with the instance data it declares (a field marked `"mask": true` is `***`). Another source of forms (a database, a form service) is a bean of type `FormRegistry`. A missing form is `FORM_NOT_REGISTERED` at import.

### Showing values the way people read them

Three more hints change only what a screen shows, never the value that is stored or sent. They are part of the form, so a change is a new definition version, as with `labels`.

```json
"hints": {
  "options": {
    "decision": {
      "APPROVE": { "label": { "en": "Approve", "vi": "Phê duyệt" },
                   "consequence": { "en": "The loan is disbursed.", "vi": "Khoản vay được giải ngân." } },
      "REJECT":  { "label": { "en": "Reject", "vi": "Từ chối" } }
    }
  },
  "units":   { "termMonths": { "en": "months", "vi": "tháng" } },
  "formats": { "amount": { "currencyFrom": "currency", "grouping": true } }
}
```

- `options`: for an `enum` field, a label per language for each value (up to 200 characters) and an optional one-line `consequence` (up to 300). The completion still carries `APPROVE`.
- `units`: a suffix per language, up to 30 characters.
- `formats`: `grouping: true` asks for thousands separators in the viewer's language; `currency` is a fixed code such as `VND`, `currencyFrom` names a field that holds the code (use one, not both).

A value for an unknown field or enum value, or any other member, is `FORM_HINTS_INVALID` at import.

Complete a task with `POST /tasks/{id}:claim`, then `:complete`, each with `If-Match` and `Idempotency-Key`. Steps are in the walkthrough, and the example host's `credit-review-form` model is the form demo ([[Process Engine API]]).
