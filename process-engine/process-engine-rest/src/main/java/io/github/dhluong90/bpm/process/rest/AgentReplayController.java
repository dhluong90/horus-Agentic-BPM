package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.api.AgentReplayRequest;
import io.github.dhluong90.bpm.process.api.AgentReplayRun;
import io.github.dhluong90.bpm.process.api.AgentReplayService;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Offline replay of recorded agent answers (#98, PRE-092 to PRE-095) under {@code /api/bpm/processes/v1}. Every
 * endpoint needs {@code processes:agent:replay}, checked in the service with the tenant from the resolver; a tenant
 * in a body is never read. Reports carry ids, outcomes and rule ids, never output values.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class AgentReplayController {

    private final AgentReplayService replays;

    /** @param replays the replay service */
    public AgentReplayController(AgentReplayService replays) {
        this.replays = replays;
    }

    /** Starts a replay run: {@code 202} with the queued run; a repeat with the same Idempotency-Key returns it. */
    @PostMapping(value = "/agent-replays", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> start(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        JsonNode body = parse(bounded(http));
        JsonNode candidate = body.get("candidate");
        if (candidate == null || !candidate.isObject()) {
            throw new InputInvalidException("candidate with ruleSet and version is required");
        }
        JsonNode version = candidate.get("version");
        if (version == null || !version.isIntegralNumber()) {
            throw new InputInvalidException("candidate.version must be a whole number");
        }
        JsonNode schema = candidate.get("outputSchema");
        if (schema != null && !schema.isNull() && !schema.isObject()) {
            throw new InputInvalidException("candidate.outputSchema must be a JSON object");
        }
        AgentReplayRun run = replays.start(new AgentReplayRequest(text(body, "definitionKey"), text(body, "nodeId"),
                date("from", text(body, "from")), date("to", text(body, "to")), text(candidate, "ruleSet"),
                version.asInt(), schema == null || schema.isNull() ? null : Views.variables(schema), idempotencyKey));
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                .header("Location", ProcessController.BASE + "/agent-replays/" + run.id()).body(bytes(run(run)));
    }

    /** Reads a run and its summary. */
    @GetMapping("/agent-replays/{id}")
    public ResponseEntity<byte[]> run(@PathVariable("id") String id) {
        return ok(run(replays.run(id)));
    }

    /** Reads report lines in recorded order, {@code limit} 1 to 500 (default 50). */
    @GetMapping("/agent-replays/{id}/cases")
    public ResponseEntity<byte[]> cases(@PathVariable("id") String id,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return ok(Views.page(replays.cases(id, new PageRequest(limitOf(limit), cursor)), AgentReplayController::line));
    }

    /** Opens one case's recorded answer as history keeps it: masked fields stay masked. */
    @GetMapping("/agent-replays/{id}/cases/{attemptId}/answer")
    public ResponseEntity<byte[]> answer(@PathVariable("id") String id, @PathVariable("attemptId") String attemptId) {
        Map<String, Object> answer = replays.recordedAnswer(id, attemptId);
        return ok(Values.toJson(answer));
    }

    static ObjectNode run(AgentReplayRun r) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("id", r.id());
        node.put("status", r.status());
        node.put("definitionKey", r.definitionKey());
        node.put("nodeId", r.nodeId());
        node.put("from", r.from().toString());
        node.put("to", r.to().toString());
        ObjectNode candidate = node.putObject("candidate");
        candidate.put("ruleSet", r.candidateRuleSet());
        candidate.put("version", r.candidateVersion());
        candidate.put("outputSchema", r.candidateOutputSchema());
        AgentReplayRun.Summary s = r.summary();
        ObjectNode summary = node.putObject("summary");
        summary.put("cases", s.cases());
        summary.put("unchanged", s.unchanged());
        summary.put("newlyAllowed", s.newlyAllowed());
        summary.put("newlyRefused", s.newlyRefused());
        summary.put("newlyRoutedToReview", s.newlyRoutedToReview());
        summary.put("overriddenByReviewer", s.overriddenByReviewer());
        summary.put("notReplayed", s.notReplayed());
        summary.put("withMaskedFields", s.withMaskedFields());
        putNullable(node, "failureCode", r.failureCode());
        node.put("createdBy", r.createdBy());
        putTime(node, "createdAt", r.createdAt());
        putTime(node, "completedAt", r.completedAt());
        putTime(node, "expiresAt", r.expiresAt());
        return node;
    }

    static ObjectNode line(AgentReplayCase c) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("attemptId", c.attemptId());
        node.put("instanceId", c.instanceId());
        putTime(node, "recordedAt", c.recordedAt());
        node.put("recordedOutcome", c.recordedOutcome());
        node.put("candidateOutcome", c.candidateOutcome());
        node.put("change", c.change());
        strings(node.putArray("recordedRuleIds"), c.recordedRuleIds());
        strings(node.putArray("candidateRuleIds"), c.candidateRuleIds());
        putNullable(node, "code", c.code());
        if (c.overriddenByReviewer()) {
            node.putArray("flags").add("OVERRIDDEN_BY_REVIEWER");
        } else {
            node.putArray("flags");
        }
        node.put("maskedFields", c.maskedFields());
        return node;
    }

    private static void strings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void putTime(ObjectNode node, String name, Instant value) {
        putNullable(node, name, value == null ? null : value.toString());
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static LocalDate date(String name, String value) {
        if (value == null) {
            throw new QueryInvalidException(name + " is required, a date such as 2026-11-01");
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new QueryInvalidException(name + " must be a date such as 2026-11-01");
        }
    }

    private static Integer limitOf(String limit) {
        if (limit == null) {
            return null;
        }
        try {
            return Integer.valueOf(limit.strip());
        } catch (NumberFormatException e) {
            throw new QueryInvalidException("limit must be a whole number, for example 50");
        }
    }

    private static byte[] bounded(HttpServletRequest http) throws IOException {
        int limit = EngineDefaults.MAX_JSON_BODY_BYTES;
        byte[] bytes = http.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new PayloadTooLargeException("The body is larger than " + limit + " bytes");
        }
        return bytes;
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode node = EngineJson.JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new InputInvalidException("The body must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new InputInvalidException("The body is not valid JSON");
        }
    }

    private static ResponseEntity<byte[]> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes(body));
    }

    private static byte[] bytes(JsonNode body) {
        return EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
