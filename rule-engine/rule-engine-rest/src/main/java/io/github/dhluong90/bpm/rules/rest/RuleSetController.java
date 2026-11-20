package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Import, read and the publication lifecycle of rule set versions. The tenant comes from the
 * {@code TenantResolver} inside the services; nothing in a body names a tenant.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/rule-sets")
public class RuleSetController {

    private final VersionService versions;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param versions the version service
     * @param callers resolves the caller and its permissions
     */
    public RuleSetController(VersionService versions, CallerResolver callers) {
        this.versions = Objects.requireNonNull(versions, "versions must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /** {@code GET /rule-sets}: the caller's rule sets by name, keyset-paged. */
    @GetMapping
    public ResponseEntity<byte[]> listRuleSets(
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        RestSupport.require(callers, "rules:read");
        Page<RuleSetSummary> page = versions.listRuleSets(new PageRequest(RestSupport.limit(limit), cursor));
        return RestSupport.json(HttpStatus.OK, RestSupport.page(page, RestSupport::ruleSetSummary));
    }

    /** {@code GET /rule-sets/{name}}: the summary of one rule set. */
    @GetMapping("/{name}")
    public ResponseEntity<byte[]> getRuleSet(@PathVariable("name") String name) {
        RestSupport.require(callers, "rules:read");
        return versions.findRuleSet(name)
                .map(summary -> RestSupport.json(HttpStatus.OK, RestSupport.ruleSetSummary(summary)))
                .orElseThrow(() -> new RuleSetNotFoundException(name));
    }

    /** {@code GET /rule-sets/{name}/versions}: versions newest first, keyset-paged. */
    @GetMapping("/{name}/versions")
    public ResponseEntity<byte[]> listVersions(
            @PathVariable("name") String name,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        RestSupport.require(callers, "rules:read");
        Page<VersionSummary> page = versions.listVersions(name, new PageRequest(RestSupport.limit(limit), cursor));
        return RestSupport.json(HttpStatus.OK, RestSupport.page(page, v -> RestSupport.versionSummary(v, callers)));
    }

    /** {@code GET /rule-sets/{name}/versions/{v}/history}: lifecycle history, oldest first. */
    @GetMapping("/{name}/versions/{version:\\d+}/history")
    public ResponseEntity<byte[]> history(
            @PathVariable("name") String name,
            @PathVariable("version") int version) {
        RestSupport.require(callers, "rules:read");
        List<VersionHistoryEntry> entries = versions.history(name, version);
        ObjectNode body = EngineJson.NODES.objectNode();
        ArrayNode data = body.putArray("data");
        entries.forEach(entry -> data.add(RestSupport.historyEntry(entry)));
        return RestSupport.json(HttpStatus.OK, body);
    }

    /** {@code POST /rule-sets}: imports a document as version 1 of a new rule set. */
    @PostMapping
    public ResponseEntity<byte[]> importRuleSet(
            InputStream body,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        // Permission first, then a bounded read: a caller without rules:author never makes the host buffer a body.
        RestSupport.require(callers, "rules:author");
        ImportRequest request = new ImportRequest(format(contentType), RestSupport.boundedBody(body, contentLength),
                idempotencyKey, ProblemDetails.correlationId(correlationId));
        return RestSupport.version(HttpStatus.CREATED, versions.importRuleSet(request), callers);
    }

    /**
     * {@code POST /rule-sets/{name}/versions}: imports a document as the next draft version, or, with
     * {@code ?copyOf={v}} and no body, restores version {@code v} as the next draft (RLE-062).
     */
    @PostMapping("/{name}/versions")
    public ResponseEntity<byte[]> importVersion(
            @PathVariable("name") String name,
            @RequestParam(value = "copyOf", required = false) String copyOf,
            InputStream body,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        RestSupport.require(callers, "rules:author");
        if (copyOf != null) {
            if (RestSupport.boundedBody(body, contentLength).length > 0) {
                throw new InputInvalidException("a request with copyOf takes no body");
            }
            int source = copyOf.matches("[0-9]{1,9}") ? Integer.parseInt(copyOf) : 0;
            if (source < 1) {
                throw new InputInvalidException("copyOf must be a version number, 1 or more, was '" + copyOf + "'");
            }
            return RestSupport.version(HttpStatus.CREATED, versions.copyVersion(name, source, new WriteOptions(
                    idempotencyKey, null, null, ProblemDetails.correlationId(correlationId))), callers);
        }
        ImportRequest request = new ImportRequest(format(contentType), RestSupport.boundedBody(body, contentLength),
                idempotencyKey, ProblemDetails.correlationId(correlationId));
        return RestSupport.version(HttpStatus.CREATED, versions.importVersion(name, request), callers);
    }

    /** {@code GET /rule-sets/{name}/versions/{v}}; {@code ?include=source} adds the source document. */
    @GetMapping("/{name}/versions/{version:\\d+}")
    public ResponseEntity<byte[]> get(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            @RequestParam(value = "include", required = false) String include) {
        RestSupport.require(callers, "rules:read");
        boolean withSource = "source".equals(include);
        return versions.find(name, version, withSource)
                .map(v -> RestSupport.version(HttpStatus.OK, v, callers))
                .orElseThrow(() -> new VersionNotFoundException(name, version));
    }

    /**
     * {@code PUT /rule-sets/{name}/versions/{v}}: replaces a draft's document in place (RLE-047). The permission is
     * checked first, then the required headers, then the body is read within the import limit.
     */
    @PutMapping("/{name}/versions/{version:\\d+}")
    public ResponseEntity<byte[]> replaceDraft(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream body,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        RestSupport.require(callers, "rules:author");
        String key = RestSupport.requiredIdempotencyKey(idempotencyKey, "PUT of a draft");
        long lock = RestSupport.requiredIfMatch(ifMatch, "PUT of a draft");
        ImportRequest request = new ImportRequest(format(contentType), RestSupport.boundedBody(body, contentLength), key);
        return RestSupport.version(HttpStatus.OK,
                versions.replaceDraft(name, version, request,
                        new WriteOptions(key, lock, null, ProblemDetails.correlationId(correlationId))), callers);
    }

    /**
     * {@code GET /rule-sets/{name}/versions/{v}/diff?base={w}}: the engine's row-level diff of two versions (RLE-028).
     * Writes nothing.
     */
    @GetMapping("/{name}/versions/{version:\\d+}/diff")
    public ResponseEntity<byte[]> diff(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            @RequestParam(value = "base", required = false) String base) {
        RestSupport.require(callers, "rules:read");
        if (base == null || base.isBlank()) {
            throw new InputInvalidException("base is required: the version to compare against");
        }
        int against;
        try {
            against = Integer.parseInt(base.strip());
        } catch (NumberFormatException e) {
            throw new InputInvalidException("base must be a version number");
        }
        if (against < 1) {
            throw new InputInvalidException("base must be a version number");
        }
        return RestSupport.json(HttpStatus.OK, RestSupport.diff(versions.diff(name, version, against)));
    }

    /** {@code POST /rule-sets/{name}/versions/{v}:request-publish}. */
    @PostMapping("/{name}/versions/{version:\\d+}:request-publish")
    public ResponseEntity<byte[]> requestPublish(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) {
        RestSupport.require(callers, "rules:author");
        WriteOptions options = new WriteOptions(idempotencyKey, RestSupport.ifMatch(ifMatch), null,
                ProblemDetails.correlationId(correlationId));
        return RestSupport.version(HttpStatus.OK, versions.requestPublish(name, version, options), callers);
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:approve}, optional body
     * {@code {"comment": "...", "acknowledgedWarnings": [{"code": "UNMASKED_PERSONAL_DATA", "input": "nationalId"}]}}
     * (RLE-053).
     */
    @PostMapping("/{name}/versions/{version:\\d+}:approve")
    public ResponseEntity<byte[]> approve(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        RestSupport.require(callers, "rules:approve");
        long lock = RestSupport.requiredIfMatch(ifMatch, ":approve");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        String comment = null;
        String expectedHash = null;
        java.util.List<io.github.dhluong90.bpm.rules.api.AcknowledgedWarning> acknowledged = new java.util.ArrayList<>();
        if (body.length > 0) {
            JsonNode node = EngineValues.readRequest(body);
            expectedHash = expectedCanonicalHash(node);
            JsonNode value = node.get("comment");
            if (value != null && !value.isNull()) {
                if (!value.isString()) {
                    throw new InputInvalidException("comment must be a string");
                }
                comment = value.stringValue();
            }
            JsonNode pairs = node.get("acknowledgedWarnings");
            if (pairs != null && !pairs.isNull()) {
                String shape = "acknowledgedWarnings must be a list of { \"code\", \"input\" } objects";
                if (!pairs.isArray()) {
                    throw new InputInvalidException(shape);
                }
                for (JsonNode pair : pairs) {
                    JsonNode code = pair.get("code");
                    JsonNode input = pair.get("input");
                    if (!pair.isObject() || code == null || !code.isString() || input == null || !input.isString()
                            || pair.size() != 2) {
                        throw new InputInvalidException(shape);
                    }
                    acknowledged.add(new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning(code.stringValue(),
                            input.stringValue()));
                }
            }
        }
        WriteOptions options = new WriteOptions(idempotencyKey, lock, comment,
                ProblemDetails.correlationId(correlationId), acknowledged).expectingContent(expectedHash);
        return RestSupport.version(HttpStatus.OK, versions.approve(name, version, options), callers);
    }

    /** The optional {@code expectedCanonicalHash} of a decision body: a string, or absent (RLE-084). */
    private static String expectedCanonicalHash(JsonNode body) {
        JsonNode value = body.get("expectedCanonicalHash");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InputInvalidException("expectedCanonicalHash must be a string");
        }
        return value.stringValue();
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:reject}, body {@code {"reason": "...", "expectedCanonicalHash": "..."}},
     * the reason required (RLE-084).
     */
    @PostMapping("/{name}/versions/{version:\\d+}:reject")
    public ResponseEntity<byte[]> reject(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        RestSupport.require(callers, "rules:approve");
        long lock = RestSupport.requiredIfMatch(ifMatch, ":reject");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        String reason = null;
        String expectedHash = null;
        if (body.length > 0) {
            JsonNode node = EngineValues.readRequest(body);
            JsonNode value = node.get("reason");
            if (value != null && !value.isNull()) {
                if (!value.isString()) {
                    throw new InputInvalidException("reason must be a string");
                }
                reason = value.stringValue();
            }
            expectedHash = expectedCanonicalHash(node);
        }
        WriteOptions options = new WriteOptions(idempotencyKey, lock, reason,
                ProblemDetails.correlationId(correlationId)).expectingContent(expectedHash);
        return RestSupport.version(HttpStatus.OK, versions.reject(name, version, options), callers);
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:retire}, body {@code {"reason": "..."}} required: takes a published
     * version out of service (ADR-0025, RLE-026). {@code Idempotency-Key} and {@code If-Match} are required.
     */
    @PostMapping("/{name}/versions/{version:\\d+}:retire")
    public ResponseEntity<byte[]> retire(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        // Permission first, then the headers, then a bounded read: no body is buffered for a caller who may not send it.
        RestSupport.require(callers, "rules:approve");
        String key = RestSupport.requiredIdempotencyKey(idempotencyKey, ":retire");
        long lock = RestSupport.requiredIfMatch(ifMatch, ":retire");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        String reason = null;
        boolean confirm = false;
        if (body.length > 0) {
            JsonNode request = EngineValues.readRequest(body);
            JsonNode flag = request.get("confirmNoVersionInEffect");
            if (flag != null && !flag.isNull()) {
                if (!flag.isBoolean()) {
                    throw new InputInvalidException("confirmNoVersionInEffect must be true or false");
                }
                confirm = flag.booleanValue();
            }
            JsonNode value = request.get("reason");
            if (value != null && !value.isNull()) {
                if (!value.isString()) {
                    throw new InputInvalidException("reason must be a string");
                }
                reason = value.stringValue();
            }
        }
        WriteOptions options = new WriteOptions(key, lock, reason, ProblemDetails.correlationId(correlationId));
        return RestSupport.version(HttpStatus.OK, versions.retire(name, version, options, confirm), callers);
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:withdraw}, optional body {@code {"reason": "..."}}: the requester takes
     * their own publication request back (PD-089, RLE-046). {@code Idempotency-Key} and {@code If-Match} are required.
     */
    @PostMapping("/{name}/versions/{version:\\d+}:withdraw")
    public ResponseEntity<byte[]> withdraw(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) throws IOException {
        // Permission first, then the headers, then a bounded read: no body is buffered for a caller who may not send it.
        RestSupport.require(callers, "rules:author");
        String key = RestSupport.requiredIdempotencyKey(idempotencyKey, ":withdraw");
        long lock = RestSupport.requiredIfMatch(ifMatch, ":withdraw");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        String reason = null;
        if (body.length > 0) {
            JsonNode value = EngineValues.readRequest(body).get("reason");
            if (value != null && !value.isNull()) {
                if (!value.isString()) {
                    throw new InputInvalidException("reason must be a string");
                }
                reason = value.stringValue();
            }
        }
        WriteOptions options = new WriteOptions(key, lock, reason, ProblemDetails.correlationId(correlationId));
        return RestSupport.version(HttpStatus.OK, versions.withdraw(name, version, options), callers);
    }

    /**
     * {@code GET /rule-sets/{name}/versions/{v}/export?format=json|dmn}: the version as a file that imports back with
     * the same canonical hash. Writes nothing.
     */
    @GetMapping("/{name}/versions/{version:\\d+}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            @RequestParam(value = "format", required = false) String format) {
        RestSupport.require(callers, "rules:read");
        SourceFormat sourceFormat = switch (format == null ? "" : format) {
            case "json" -> SourceFormat.JSON;
            case "dmn" -> SourceFormat.DMN_XML;
            default -> throw new InputInvalidException("format must be json or dmn");
        };
        String document = versions.export(name, version, sourceFormat);
        String extension = sourceFormat == SourceFormat.DMN_XML ? "dmn" : sourceFormat.name().toLowerCase(Locale.ROOT);
        String mediaType = sourceFormat == SourceFormat.DMN_XML ? "application/xml" : "application/json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mediaType + ";charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-v" + version + "." + extension + "\"")
                .body(document.getBytes(StandardCharsets.UTF_8));
    }

    private static SourceFormat format(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("yaml")) {
            return SourceFormat.YAML;
        }
        if (type.contains("json")) {
            return SourceFormat.JSON;
        }
        // application/xml, text/xml and the DMN media types such as application/dmn+xml
        if (type.contains("xml") || type.contains("dmn")) {
            return SourceFormat.DMN_XML;
        }
        throw new InputInvalidException("Content-Type must be application/yaml or application/xml (a DMN file)");
    }
}
