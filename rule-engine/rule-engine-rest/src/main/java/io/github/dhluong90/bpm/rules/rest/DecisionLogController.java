package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionSearchResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InputSearch;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.MaskedInputQuery;
import io.github.dhluong90.bpm.rules.api.PageRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DecisionLogQuery log;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param decisions the decision log reads
     * @param log the decision log searches
     * @param callers resolves the caller and its permissions
     */
    public DecisionLogController(DecisionLogService decisions, DecisionLogQuery log, CallerResolver callers) {
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /** {@code GET /decisions/{id}}: one decision as logged. */
    @GetMapping("/decisions/{id}")
    public ResponseEntity<byte[]> get(@PathVariable("id") String id) {
        RestSupport.require(callers, "rules:log:read");
        return RestSupport.json(HttpStatus.OK, decision(decisions.get(id)));
    }

    /**
     * {@code GET /decisions/{id}/explain}: the decision explained from its row and its stored version (RLE-030,
     * RLE-031). {@code lang}, then {@code Accept-Language}, choose the language of {@code text}.
     */
    @GetMapping("/decisions/{id}/explain")
    public ResponseEntity<byte[]> explain(
            @PathVariable("id") String id,
            @RequestParam(value = "lang", required = false) String lang,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        RestSupport.require(callers, "rules:log:read");
        return RestSupport.json(HttpStatus.OK, explanation(decisions.explain(id, languages(lang, acceptLanguage))));
    }

    /** The caller's languages, best first: {@code lang}, then the primary tags of {@code Accept-Language} by weight. */
    static java.util.List<String> languages(String lang, String acceptLanguage) {
        java.util.List<String> languages = new java.util.ArrayList<>();
        if (lang != null && !lang.isBlank()) {
            languages.add(lang.strip().toLowerCase(java.util.Locale.ROOT));
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank() && acceptLanguage.length() <= 200) {
            try {
                for (java.util.Locale.LanguageRange range : java.util.Locale.LanguageRange.parse(acceptLanguage)) {
                    String primary = range.getRange().split("-", 2)[0].toLowerCase(java.util.Locale.ROOT);
                    if (range.getWeight() > 0 && !"*".equals(primary) && !languages.contains(primary)) {
                        languages.add(primary);
                    }
                }
            } catch (IllegalArgumentException malformed) {
                // A malformed header chooses nothing; the default language applies.
            }
        }
        return languages;
    }

    static ObjectNode explanation(io.github.dhluong90.bpm.rules.api.DecisionExplanation e) {
        ObjectNode json = EngineJson.NODES.objectNode()
                .put("decisionId", e.decisionId())
                .put("ruleSet", e.ruleSet())
                .put("version", e.version())
                .put("hitPolicy", e.hitPolicy())
                .put("outcome", e.outcome());
        json.put("errorCode", e.errorCode());
        json.put("clock", e.clock().toString());
        ArrayNode matched = json.putArray("matched");
        for (io.github.dhluong90.bpm.rules.api.DecisionExplanation.MatchedRow row : e.matched()) {
            ObjectNode item = matched.addObject().put("ruleId", row.ruleId()).put("position", row.position());
            ArrayNode conditions = item.putArray("conditions");
            for (io.github.dhluong90.bpm.rules.api.DecisionExplanation.Condition c : row.conditions()) {
                ObjectNode condition = conditions.addObject()
                        .put("input", c.input())
                        .put("path", c.path())
                        .put("test", c.test());
                condition.set("value", EngineValues.read(c.valueJson()));
                condition.put("masked", c.masked()).put("missing", c.missing());
                condition.set("result", c.result() == null ? EngineJson.NODES.nullNode()
                        : EngineJson.NODES.booleanNode(c.result()));
            }
            item.set("outputs", EngineValues.read(row.outputsJson()));
        }
        json.set("outputs", e.outputsJson() == null ? EngineJson.NODES.nullNode() : EngineValues.read(e.outputsJson()));
        ArrayNode reasons = json.putArray("reasons");
        for (io.github.dhluong90.bpm.rules.api.DecisionExplanation.Reason r : e.reasons()) {
            ObjectNode params = EngineJson.NODES.objectNode();
            r.params().forEach((key, value) -> params.set(key, EngineValues.value(value)));
            reasons.addObject().put("code", r.code()).set("params", params);
        }
        json.put("language", e.language());
        json.put("text", e.text());
        json.put("textSource", e.textSource());
        return json;
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

    private static final Set<String> INPUT_SEARCH_KEYS =
            Set.of("ruleSet", "input", "value", "from", "to", "limit", "cursor");

    /**
     * {@code POST /decisions:find-by-input}: the decisions of one rule set in a period whose input, stored in plain
     * text, had exactly the value in the body, which never sits in a URL (RLE-071). A masked input is
     * {@code 400 QUERY_INVALID}. Needs {@code rules:log:read}; writes nothing.
     */
    @PostMapping("/decisions:find-by-input")
    public ResponseEntity<byte[]> findByInput(
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength)
            throws IOException {
        RestSupport.require(callers, "rules:log:read");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object");
        }
        for (String key : request.propertyNames()) {
            if (!INPUT_SEARCH_KEYS.contains(key)) {
                throw new QueryInvalidException("Unknown member " + key + "; expected ruleSet, input, value, from, to,"
                        + " limit and cursor");
            }
        }
        JsonNode valueNode = request.get("value");
        if (valueNode == null || valueNode.isNull() || valueNode.isArray()) {
            throw new QueryInvalidException("value is required and must be a single value");
        }
        ObjectNode wrapped = EngineJson.NODES.objectNode();
        wrapped.set("value", valueNode);
        Object value;
        try {
            value = EngineValues.inputsFromJson(wrapped).get("value");
        } catch (InputInvalidException e) {
            throw new QueryInvalidException("value must be a string, number, boolean or money");
        }
        JsonNode limit = request.get("limit");
        if (limit != null && !limit.isNull() && !limit.isIntegralNumber()) {
            throw new QueryInvalidException("limit must be an integer");
        }
        JsonNode cursor = request.get("cursor");
        if (cursor != null && !cursor.isNull() && !cursor.isString()) {
            throw new QueryInvalidException("cursor must be a string");
        }
        InputSearch search = new InputSearch(text(request, "ruleSet"), text(request, "input"), value,
                instant(request, "from"), instant(request, "to"));
        PageRequest page = new PageRequest(limit == null || limit.isNull() ? null : limit.asInt(),
                cursor == null || cursor.isNull() ? null : cursor.stringValue());
        return RestSupport.json(HttpStatus.OK, RestSupport.page(log.searchByInput(search, page),
                EngineValues::decisionLogEntry));
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
        return EngineValues.loggedDecision(d);
    }
}
