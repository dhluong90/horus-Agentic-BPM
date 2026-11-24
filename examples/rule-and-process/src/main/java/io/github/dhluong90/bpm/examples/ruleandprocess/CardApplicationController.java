package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The host's own API: submit a card application, read it back. Not an engine endpoint. */
@RestController
@RequestMapping("/api/cards/applications")
class CardApplicationController {

    /** Who may submit: the teller role, or a token that carries the permission itself (see application.yml). */
    static final String SUBMIT_ROLE = "ROLE_TELLER";
    static final String SUBMIT_PERMISSION = "processes:start";

    private static final Log LOG = LogFactory.getLog(CardApplicationController.class);

    record Submit(@NotBlank @Size(max = 64) String applicationId, @NotBlank @Size(max = 64) String customerId) {
    }

    /** Thrown when the caller holds neither the teller role nor the start permission. */
    static final class SubmitNotAllowed extends RuntimeException {
        SubmitNotAllowed() {
            super("not allowed", null, false, false);
        }
    }

    static boolean maySubmit(Authentication caller) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : caller.getAuthorities()) {
            if (SUBMIT_ROLE.equals(authority.getAuthority()) || SUBMIT_PERMISSION.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private final CardApplicationService service;
    private final JdbcClient jdbc;
    private final TenantResolver tenants;

    CardApplicationController(CardApplicationService service, JdbcClient jdbc, TenantResolver tenants) {
        this.service = service;
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody Submit body, Authentication caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (!maySubmit(caller)) {
            throw new SubmitNotAllowed();
        }
        ProcessInstance instance = service.submit(body.applicationId(), body.customerId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("applicationId", body.applicationId(),
                "instanceId", instance.id(), "instanceStatus", instance.status()));
    }

    /** Engine refusals keep their code; a failed step is 422 with the step and its reason. */
    @ExceptionHandler(ProcessException.class)
    ResponseEntity<Map<String, Object>> refused(ProcessException e) {
        HttpStatus status = switch (e.code()) {
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "NO_EFFECTIVE_VERSION" -> HttpStatus.NOT_FOUND;
            case "INPUT_INVALID" -> HttpStatus.BAD_REQUEST;
            case "EVENT_LISTENER_FAILED", "DEFINITION_INTEGRITY_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.valueOf(422);
        };
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", e.code());
        body.put("detail", e.getMessage());
        if (e instanceof StepFailedException failed) {
            body.put("nodeId", failed.nodeId());
            body.put("reason", failed.reason());
        }
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(SubmitNotAllowed.class)
    ResponseEntity<Map<String, Object>> notAllowed() {
        return problem(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You may not submit card applications.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid() {
        return problem(HttpStatus.BAD_REQUEST, "INPUT_INVALID",
                "applicationId and customerId are required, at most 64 characters each.");
    }

    /** Anything else is logged with a correlation id; the caller gets the id, never the exception message. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        String correlationId = UUID.randomUUID().toString();
        LOG.error("Unexpected error, correlationId " + correlationId, e);
        ResponseEntity<Map<String, Object>> response = problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The request failed. Quote the correlation id to the operator.");
        response.getBody().put("correlationId", correlationId);
        return response;
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @GetMapping("/{id}")
    ResponseEntity<Map<String, Object>> get(@PathVariable("id") String id) {
        return jdbc.sql("SELECT application_id, customer_id, status, fee_currency, fee_minor FROM card_application "
                        + "WHERE tenant_id = ? AND application_id = ?")
                .params(tenants.currentTenantId(), id)
                .query((rs, row) -> {
                    Map<String, Object> out = new java.util.LinkedHashMap<>();
                    out.put("applicationId", rs.getString("application_id"));
                    out.put("customerId", rs.getString("customer_id"));
                    out.put("status", rs.getString("status"));
                    out.put("fee", rs.getString("fee_currency") == null ? null
                            : Map.of("currency", rs.getString("fee_currency"), "amount", rs.getLong("fee_minor")));
                    return out;
                })
                .optional().map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
