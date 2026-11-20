package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionSearchResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.MaskedInputQuery;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads of the decision log (RLE-012). Masked inputs are returned as their {@code hmac:<hex>} fingerprints. The
 * search by a masked input's plain value is a {@code POST}, so the value travels in the body and never appears in a
 * URL or an access log. Reading needs {@code rules:log:read}; the search needs {@code rules:masked:search}, which
 * {@code rules:log:read} does not imply and no role holds by default. The tenant comes from the host's resolver only.
 */
@RestController
@RequestMapping(RestSupport.BASE)
public class DecisionLogController {

    private static final Set<String> SEARCH_KEYS = Set.of("ruleSet", "input", "value", "from", "to", "limit");

    private final DecisionLogService decisions;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param decisions the decision log reads
     * @param callers resolves the caller and its permissions
     */
    public DecisionLogController(DecisionLogService decisions, CallerResolver callers) {
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /** {@code GET /decisions/{id}}: one decision as logged. */
    @GetMapping("/decisions/{id}")
    public ResponseEntity<byte[]> get(@PathVariable("id") String id) {
        RestSupport.require(callers, "rules:log:read");
        return RestSupport.json(HttpStatus.OK, decision(decisions.get(id)));
    }

    /**
     * {@code POST /decisions:find-by-masked-input}: the decisions of one rule set, in a period of at most 31 days,
     * whose masked input had the plain value in the body.
     */
    @PostMapping("/decisions:find-by-masked-input")
    public ResponseEntity<byte[]> findByMaskedInput(
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId)
            throws IOException {
        RestSupport.require(callers, "rules:masked:search");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object");
        }
        for (String key : request.propertyNames()) {
            if (!SEARCH_KEYS.contains(key)) {
                throw new QueryInvalidException("Unknown member " + key + "; expected ruleSet, input, value, from, to"
                        + " and limit");
            }
        }
        JsonNode valueNode = request.get("value");
        if (valueNode == null || valueNode.isNull() || valueNode.isArray()) {
            throw new QueryInvalidException("value is required and must be a single value");
        }
        ObjectNode wrapped = EngineJson.NODES.objectNode();
        wrapped.set("value", valueNode);
        Object value = EngineValues.inputsFromJson(wrapped).get("value");
        JsonNode limit = request.get("limit");
        if (limit != null && !limit.isIntegralNumber()) {
            throw new QueryInvalidException("limit must be an integer");
        }
        MaskedInputQuery query = new MaskedInputQuery(text(request, "ruleSet"), text(request, "input"), value,
                instant(request, "from"), instant(request, "to"),
                limit == null ? EngineDefaults.QUERY_DEFAULT_PAGE_SIZE : limit.asInt(),
                ProblemDetails.correlationId(correlationId));
        DecisionSearchResult result = decisions.findByMaskedInput(query);
        ObjectNode json = EngineJson.NODES.objectNode();
        ArrayNode data = json.putArray("data");
        result.items().forEach(item -> data.add(decision(item)));
        json.put("truncated", result.truncated());
        return RestSupport.json(HttpStatus.OK, json);
    }

    private static String text(JsonNode request, String key) {
        JsonNode node = request.get(key);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw new QueryInvalidException(key + " is required");
        }
        return node.stringValue();
    }

    private static Instant instant(JsonNode request, String key) {
        String text = text(request, key);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new QueryInvalidException(key + " must be an ISO 8601 instant such as 2027-01-01T00:00:00Z");
        }
    }

    static ObjectNode decision(LoggedDecision d) {
        ObjectNode json = EngineJson.NODES.objectNode()
                .put("decisionId", d.decisionId())
                .put("ruleSet", d.ruleSet())
                .put("version", d.version())
                .put("hitPolicy", d.hitPolicy())
                .put("outcome", d.outcome());
        json.put("errorCode", d.errorCode());
        json.set("inputs", EngineValues.read(d.inputsJson()));
        json.set("outputs", d.outputsJson() == null ? EngineJson.NODES.nullNode() : EngineValues.read(d.outputsJson()));
        json.set("matchedRuleIds", EngineValues.value(d.matchedRuleIds()));
        json.put("callerId", d.callerId());
        json.put("correlationId", d.correlationId());
        json.put("evalClock", d.evalClock().toString());
        json.put("evaluatedAt", d.evaluatedAt().toString());
        json.put("clockChosen", d.clockChosen());
        json.put("pinned", d.pinned());
        json.put("durationMicros", d.durationMicros());
        return json;
    }
}
