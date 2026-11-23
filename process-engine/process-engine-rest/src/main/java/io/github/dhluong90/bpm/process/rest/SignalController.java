package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.SignalBroadcast;
import io.github.dhluong90.bpm.process.api.SignalRequest;
import io.github.dhluong90.bpm.process.api.SignalService;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Broadcasts a signal under {@code /api/bpm/processes/v1/signals} (#492). The tenant is the authenticated one; a tenant
 * in the body is never read. The answer is {@code 202}: the waiters continue when their jobs fire.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class SignalController {

    private final SignalService signals;

    /** @param signals the signal service */
    public SignalController(SignalService signals) {
        this.signals = Objects.requireNonNull(signals, "signals must not be null");
    }

    /** Broadcasts a signal in the caller's tenant ({@code processes:signal:broadcast}); needs an Idempotency-Key. */
    @PostMapping(value = "/signals", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> broadcast(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        byte[] bytes = ProcessController.boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        JsonNode request;
        try {
            request = EngineJson.JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw new InputInvalidException("The body is not valid JSON");
        }
        if (request == null || !request.isObject()) {
            throw new InputInvalidException("The body must be a JSON object");
        }
        JsonNode name = request.get("name");
        if (name == null || !name.isString()) {
            throw new InputInvalidException("name is required");
        }
        SignalBroadcast sent = signals.broadcast(new SignalRequest(name.stringValue(), idempotencyKey));
        ObjectNode body = EngineJson.NODES.objectNode().put("signalId", sent.signalId()).put("name", sent.name())
                .put("waiters", sent.waiters());
        ArrayNode started = body.putArray("startedInstances");
        sent.startedInstances().forEach(started::add);
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
