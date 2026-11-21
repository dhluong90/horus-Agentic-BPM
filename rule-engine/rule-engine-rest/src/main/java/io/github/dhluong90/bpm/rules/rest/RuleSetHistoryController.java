package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.EvidenceExport;
import io.github.dhluong90.bpm.rules.api.EvidenceFormat;
import io.github.dhluong90.bpm.rules.api.EvidenceRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import io.github.dhluong90.bpm.rules.api.RuleSetHistoryService;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
 * A rule set's history across its versions and its evidence export (PD-116). The tenant comes from the
 * {@code TenantResolver} inside the service; nothing in a body names a tenant.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/rule-sets")
public class RuleSetHistoryController {

    /** Header carrying the evidence file's {@code contentSha256}. */
    static final String EVIDENCE_SHA256_HEADER = "X-Evidence-Sha256";

    /** RFC 9530 header carrying the SHA-256 of the response body. */
    static final String CONTENT_DIGEST_HEADER = "Content-Digest";

    private static final Set<String> EVIDENCE_KEYS = Set.of("format", "versions", "decisionIds");

    private final RuleSetHistoryService history;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param history the history and evidence service
     * @param callers resolves the caller and its permissions
     */
    public RuleSetHistoryController(RuleSetHistoryService history, CallerResolver callers) {
        this.history = Objects.requireNonNull(history, "history must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /** {@code GET /rule-sets/{name}/history}: every version, newest first, with its events. */
    @GetMapping("/{name}/history")
    public ResponseEntity<byte[]> history(
            @PathVariable("name") String name,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        RestSupport.require(callers, "rules:read");
        RuleSetHistory page = history.history(name, new PageRequest(RestSupport.limit(limit), cursor));
        ObjectNode body = EngineJson.NODES.objectNode();
        body.put("ruleSet", page.ruleSet());
        ArrayNode data = body.putArray("data");
        page.versions().forEach(v -> data.add(line(v)));
        ArrayNode events = body.putArray("ruleSetEvents");
        page.ruleSetEvents().forEach(e -> events.add(event(e)));
        body.put("ruleSetEventsTruncated", page.ruleSetEventsTruncated());
        body.set("nextCursor", EngineValues.value(page.nextCursor()));
        body.put("hasNext", page.nextCursor() != null);
        return RestSupport.json(HttpStatus.OK, body);
    }

    /**
     * {@code POST /rule-sets/{name}/evidence}: the evidence file of chosen versions and decisions, recorded in the
     * rule set's history and as an event.
     */
    @PostMapping("/{name}/evidence")
    public ResponseEntity<byte[]> evidence(
            @PathVariable("name") String name,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId)
            throws IOException {
        // The endpoint's permission first; the service checks rules:log:read when decisions are named.
        RestSupport.require(callers, "rules:read");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object");
        }
        for (String key : request.propertyNames()) {
            if (!EVIDENCE_KEYS.contains(key)) {
                throw new InputInvalidException("Unknown member " + key + "; expected format, versions and decisionIds");
            }
        }
        JsonNode format = request.get("format");
        if (format == null || !format.isString()) {
            throw new InputInvalidException("format is required: csv or json");
        }
        EvidenceExport export = history.exportEvidence(name, new EvidenceRequest(EvidenceFormat.of(format.stringValue()),
                integers(request.get("versions")), strings(request.get("decisionIds")), idempotencyKey,
                ProblemDetails.correlationId(correlationId)));
        byte[] file = export.bytes();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, export.format().contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"")
                .header(CONTENT_DIGEST_HEADER, contentDigest(file))
                .header(EVIDENCE_SHA256_HEADER, export.contentSha256())
                .body(file);
    }

    /** RFC 9530 {@code Content-Digest} of a body: {@code sha-256=:<base64>:}. */
    static String contentDigest(byte[] body) {
        try {
            return "sha-256=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
                    + ":";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static List<Integer> integers(JsonNode node) {
        List<Integer> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw new InputInvalidException("versions must be a list of version numbers");
        }
        if (node.size() > EvidenceRequest.MAX_VERSIONS) {
            throw new InputInvalidException("versions holds at most " + EvidenceRequest.MAX_VERSIONS + " entries");
        }
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || !item.canConvertToInt()) {
                throw new InputInvalidException("versions must be a list of version numbers");
            }
            values.add(item.intValue());
        }
        return values;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw new InputInvalidException("decisionIds must be a list of decision ids");
        }
        if (node.size() > EvidenceRequest.MAX_DECISIONS) {
            throw new InputInvalidException("Choose at most " + EvidenceRequest.MAX_DECISIONS + " decisions");
        }
        for (JsonNode item : node) {
            if (!item.isString()) {
                throw new InputInvalidException("decisionIds must be a list of decision ids");
            }
            values.add(item.stringValue());
        }
        return values;
    }

    private static ObjectNode line(RuleSetHistory.VersionLine v) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("version", v.version());
        node.put("status", v.status().name());
        node.set("effectiveFrom", EngineValues.value(v.effectiveFrom()));
        node.set("effectiveTo", EngineValues.value(v.effectiveTo()));
        node.put("canonicalHash", v.canonicalHash());
        node.set("createdBy", EngineValues.value(v.createdBy()));
        node.put("createdAt", v.createdAt().toString());
        node.set("requestedBy", EngineValues.value(v.requestedBy()));
        node.set("requestedAt", EngineValues.value(v.requestedAt()));
        node.set("approvedBy", EngineValues.value(v.approvedBy()));
        node.set("approvedAt", EngineValues.value(v.approvedAt()));
        node.set("approvalComment", EngineValues.value(v.approvalComment()));
        node.set("rejectedBy", EngineValues.value(v.rejectedBy()));
        node.set("rejectedAt", EngineValues.value(v.rejectedAt()));
        node.set("rejectionReason", EngineValues.value(v.rejectionReason()));
        ArrayNode events = node.putArray("events");
        v.events().forEach(e -> events.add(RestSupport.historyEntry(e)));
        node.put("eventsTruncated", v.eventsTruncated());
        return node;
    }

    private static ObjectNode event(RuleSetHistory.RuleSetEvent e) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("action", e.action().name());
        node.put("actor", e.actor());
        node.put("occurredAt", e.occurredAt().toString());
        ObjectNode evidence = node.putObject("evidence");
        evidence.put("format", e.evidence().format().extension());
        ArrayNode versions = evidence.putArray("versions");
        e.evidence().versions().forEach(versions::add);
        ArrayNode ids = evidence.putArray("decisionIds");
        e.evidence().decisionIds().forEach(ids::add);
        evidence.put("contentSha256", e.evidence().contentSha256());
        evidence.put("bytes", e.evidence().bytes());
        return node;
    }
}
