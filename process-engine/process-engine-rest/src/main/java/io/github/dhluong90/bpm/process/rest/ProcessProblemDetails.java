package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.service.Views;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.node.ObjectNode;

/** RFC 9457 problem details for Process Engine endpoints, with the engine error code in {@code code}. */
@RestControllerAdvice(assignableTypes = {ProcessController.class, AgentReplayController.class,
        MigrationController.class, MigrationExecutionController.class, LayoutController.class,
        IncidentController.class, CalendarController.class, StepCatalogueController.class, SignalController.class})
public class ProcessProblemDetails {

    private static final System.Logger LOG = System.getLogger(ProcessProblemDetails.class.getName());
    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");
    private static final String TYPE_BASE = "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#";
    private static final Pattern CORRELATION = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);
    static final Map<String, HttpStatus> STATUS = Map.ofEntries(
            Map.entry("INPUT_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("QUERY_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("QUERY_TIMEOUT", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("ROLE_FAN_OUT_EXCEEDED", HttpStatus.BAD_REQUEST),
            Map.entry("CONTENT_HASH_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("CONTENT_CHANGED", HttpStatus.CONFLICT),
            Map.entry("CHANGE_SET_TRUNCATED", HttpStatus.CONFLICT),
            Map.entry("INCIDENT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("CALENDAR_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("OUTPUT_INVALID", UNPROCESSABLE),
            Map.entry("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED),
            Map.entry("PERMISSION_DENIED", HttpStatus.FORBIDDEN),
            Map.entry("TASK_NOT_ASSIGNED", HttpStatus.FORBIDDEN),
            Map.entry("SELF_APPROVAL_FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("TENANT_NOT_ALLOWED", HttpStatus.FORBIDDEN),
            Map.entry("PAYLOAD_TOO_LARGE", HttpStatus.valueOf(413)),
            Map.entry("START_ARRIVALS_FULL", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("PRECONDITION_REQUIRED", HttpStatus.valueOf(428)),
            Map.entry("IDEMPOTENCY_IN_FLIGHT", HttpStatus.CONFLICT),
            Map.entry("EVENT_LISTENER_FAILED", HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry("NO_EFFECTIVE_VERSION", HttpStatus.NOT_FOUND),
            Map.entry("DEFINITION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("INSTANCE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("ASYNC_STEP_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("MESSAGE_NOT_CORRELATED", UNPROCESSABLE),
            Map.entry("MESSAGE_CORRELATION_AMBIGUOUS", HttpStatus.CONFLICT),
            Map.entry("TASK_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("FORM_NOT_DECLARED", HttpStatus.NOT_FOUND),
            Map.entry("REPLAY_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("MIGRATION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("LAYOUT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("DRY_RUN_REQUIRED", HttpStatus.CONFLICT),
            Map.entry("LAYOUT_INVALID", UNPROCESSABLE),
            Map.entry("MIGRATION_PLAN_INVALID", UNPROCESSABLE),
            Map.entry("RULE_SET_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("REPLAY_TOO_LARGE", UNPROCESSABLE),
            Map.entry("VERSION_SUPERSEDED", HttpStatus.CONFLICT),
            Map.entry("LAST_VERSION_IN_EFFECT", HttpStatus.CONFLICT),
            Map.entry("INVALID_STATE", HttpStatus.CONFLICT),
            Map.entry("ASSIGNEE_NOT_ALLOWED", HttpStatus.CONFLICT),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("VALIDATION_FAILED", UNPROCESSABLE),
            Map.entry("GUARDRAIL_CONTRACT_INVALID", UNPROCESSABLE),
            Map.entry("FORM_VALIDATION_FAILED", UNPROCESSABLE),
            Map.entry("BPMN_UNSUPPORTED_ELEMENT", UNPROCESSABLE),
            Map.entry("CMMN_UNSUPPORTED", UNPROCESSABLE),
            Map.entry("BPMN_UNSAFE_XML", UNPROCESSABLE),
            Map.entry("DEFINITION_TOO_LARGE", UNPROCESSABLE),
            Map.entry("IDEMPOTENCY_KEY_REUSE_MISMATCH", UNPROCESSABLE),
            Map.entry("STEP_FAILED", UNPROCESSABLE));

    /**
     * @param e the engine exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(ProcessException.class)
    public ResponseEntity<byte[]> handle(ProcessException e, WebRequest request) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        ObjectNode body = EngineJson.NODES.objectNode()
                .put("type", TYPE_BASE + e.code().toLowerCase(Locale.ROOT))
                .put("title", status.getReasonPhrase())
                .put("status", status.value())
                .put("code", e.code())
                .put("detail", e.getMessage())
                .put("correlationId", correlationId(request));
        switch (e) {
            case BpmnUnsupportedElementException u -> {
                body.set("unsupportedElements", Views.unsupportedElements(u.problems()));
                if (u.truncated()) {
                    body.put("violationsTruncated", true);
                }
                if (!u.warnings().isEmpty()) {
                    body.set("warnings", Views.problems(u.warnings()));
                }
            }
            case io.github.dhluong90.bpm.process.api.CmmnUnsupportedException c ->
                    body.put("namespace", c.namespace()).put("line", c.line());
            case ValidationFailedException v -> body.set("problems", Views.problems(v.problems()));
            case io.github.dhluong90.bpm.process.api.GuardrailContractInvalidException g ->
                    body.set("problems", Views.problems(g.problems()));
            case StepFailedException s -> body.put("nodeId", s.nodeId()).put("reason", s.reason());
            case io.github.dhluong90.bpm.process.api.AssigneeNotAllowedException a -> {
                // #442: which rule refused, and the role or group the target lacks, for the client to name it
                if (a.reason() != null) {
                    body.put("reason", a.reason());
                }
                if (a.required() != null) {
                    body.put("required", a.required());
                }
            }
            case io.github.dhluong90.bpm.process.api.ContentChangedException c -> {
                body.put("currentDefinitionHash", c.currentDefinitionHash())
                        .put("currentLockVersion", c.currentLockVersion());
                if (c.currentChangeSetHash() != null) {
                    body.put("currentChangeSetHash", c.currentChangeSetHash());
                }
            }
            case io.github.dhluong90.bpm.process.api.OutputInvalidException o -> {
                tools.jackson.databind.node.ArrayNode paths = body.putArray("paths");
                o.paths().forEach(paths::add);
            }
            case io.github.dhluong90.bpm.process.api.LayoutInvalidException l -> {
                tools.jackson.databind.node.ArrayNode paths = body.putArray("paths");
                l.problems().forEach(paths::add);
            }
            case io.github.dhluong90.bpm.process.api.MigrationPlanInvalidException m -> {
                tools.jackson.databind.node.ArrayNode paths = body.putArray("paths");
                m.problems().forEach(paths::add);
            }
            case io.github.dhluong90.bpm.process.api.RoleFanOutExceededException r ->
                    body.put("cap", r.cap()).put("provided", r.provided());
            case io.github.dhluong90.bpm.process.api.ReplayTooLargeException r ->
                    body.put("cases", r.cases()).put("limit", r.limit());
            case io.github.dhluong90.bpm.process.api.FormValidationException f -> {
                tools.jackson.databind.node.ArrayNode violations = body.putArray("violations");
                f.violations().forEach(violations::add);
                tools.jackson.databind.node.ArrayNode errors = body.putArray("errors");
                for (int i = 0; i < f.violations().size(); i++) {
                    String violation = f.violations().get(i);
                    int colon = violation.indexOf(": ");
                    errors.addObject().put("pointer", f.pointers().get(i))
                            .put("rule", colon < 0 ? violation : violation.substring(colon + 2));
                }
            }
            case io.github.dhluong90.bpm.process.api.LastVersionInEffectException l -> {
                body.put("definitionKey", l.definitionKey()).put("version", l.version()).put("setting", l.setting());
                if (l.nextEffectiveFrom() == null) {
                    body.putNull("nextEffectiveFrom");
                } else {
                    body.put("nextEffectiveFrom", l.nextEffectiveFrom().toString());
                }
            }
            default -> { }
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(PROBLEM);
        if ("IDEMPOTENCY_IN_FLIGHT".equals(e.code())) {
            response.header("Retry-After", "1");
        }
        if (e instanceof io.github.dhluong90.bpm.process.api.StartArrivalsFullException full) {
            response.header("Retry-After", Long.toString(full.retryAfterSeconds()));
        }
        return response.body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A request the framework refused before the engine ran (an unreadable body, an unsupported media type, a missing
     * parameter) keeps its 4xx status as {@code INPUT_INVALID}, as on the Rule Engine endpoints; anything else is
     * {@code 500 INTERNAL} with a correlation id only, the cause staying in the log. Neither answer carries a stack
     * trace, a class name or SQL (TR-SEC-08). A refusal of the host contract ({@code EngineAccessDeniedException})
     * never reaches this handler: the host module answers it at the highest precedence.
     *
     * @param e the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> unexpected(Exception e, WebRequest request) {
        if (e instanceof ErrorResponse framework && framework.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            ObjectNode body = EngineJson.NODES.objectNode()
                    .put("type", TYPE_BASE + "input_invalid")
                    .put("title", status.getReasonPhrase())
                    .put("status", status.value())
                    .put("code", "INPUT_INVALID")
                    .put("detail", "The request could not be read: " + status.getReasonPhrase())
                    .put("correlationId", correlationId(request));
            return ResponseEntity.status(status).contentType(PROBLEM)
                    .body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        }
        String correlationId = Ulid.next(Instant.now());
        LOG.log(System.Logger.Level.ERROR, "process.rest.internal correlationId=" + correlationId, e);
        ObjectNode body = EngineJson.NODES.objectNode()
                .put("type", TYPE_BASE + "internal")
                .put("title", "Internal Server Error")
                .put("status", 500)
                .put("code", "INTERNAL")
                .put("correlationId", correlationId);
        return ResponseEntity.status(500).contentType(PROBLEM)
                .body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    private static String correlationId(WebRequest request) {
        String header = request.getHeader("X-Correlation-Id");
        return header != null && CORRELATION.matcher(header).matches() ? header : Ulid.next(Instant.now());
    }
}
