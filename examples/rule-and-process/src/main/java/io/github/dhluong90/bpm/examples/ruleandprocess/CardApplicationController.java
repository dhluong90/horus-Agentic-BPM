package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    record Submit(String applicationId, String customerId) {
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
    ResponseEntity<Map<String, Object>> submit(@RequestBody Submit body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
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
