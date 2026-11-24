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

Complete a task with `POST /tasks/{id}:claim`, then `:complete`, each with `If-Match` and `Idempotency-Key`. Steps are in the walkthrough, and the example host's `credit-review-form` model is the form demo ([[Process Engine API]]).
