package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.Page;
import com.horus.bpm.rules.api.PageRequest;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.RuleSetSummary;
import com.horus.bpm.rules.api.VersionHistoryEntry;
import com.horus.bpm.rules.api.VersionSummary;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.VersionNotFoundException;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.json.EngineJson;
import com.horus.bpm.rules.internal.service.EngineDefaults;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RestSupport.require(callers, "rules:author");
        ImportRequest request = new ImportRequest(format(contentType), body, idempotencyKey);
        return RestSupport.version(HttpStatus.CREATED, versions.importRuleSet(request), callers);
    }

    /** {@code POST /rule-sets/{name}/versions}: imports a document as the next draft version. */
    @PostMapping("/{name}/versions")
    public ResponseEntity<byte[]> importVersion(
            @PathVariable("name") String name,
            @RequestBody byte[] body,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RestSupport.require(callers, "rules:author");
        ImportRequest request = new ImportRequest(format(contentType), body, idempotencyKey);
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

    /** {@code POST /rule-sets/{name}/versions/{v}:request-publish}. */
    @PostMapping("/{name}/versions/{version:\\d+}:request-publish")
    public ResponseEntity<byte[]> requestPublish(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RestSupport.require(callers, "rules:author");
        WriteOptions options = new WriteOptions(idempotencyKey, RestSupport.ifMatch(ifMatch), null);
        return RestSupport.version(HttpStatus.OK, versions.requestPublish(name, version, options), callers);
    }

    /** {@code POST /rule-sets/{name}/versions/{v}:approve}, optional body {@code {"comment": "..."}}. */
    @PostMapping("/{name}/versions/{version:\\d+}:approve")
    public ResponseEntity<byte[]> approve(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        RestSupport.require(callers, "rules:approve");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        String comment = null;
        if (body.length > 0) {
            JsonNode node = EngineValues.readRequest(body);
            JsonNode value = node.get("comment");
            if (value != null && !value.isNull()) {
                if (!value.isString()) {
                    throw new InputInvalidException("comment must be a string");
                }
                comment = value.stringValue();
            }
        }
        WriteOptions options = new WriteOptions(idempotencyKey, RestSupport.ifMatch(ifMatch), comment);
        return RestSupport.version(HttpStatus.OK, versions.approve(name, version, options), callers);
    }

    /** {@code POST /rule-sets/{name}/versions/{v}:reject}, body {@code {"reason": "..."}} required. */
    @PostMapping("/{name}/versions/{version:\\d+}:reject")
    public ResponseEntity<byte[]> reject(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        RestSupport.require(callers, "rules:approve");
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
        WriteOptions options = new WriteOptions(idempotencyKey, RestSupport.ifMatch(ifMatch), reason);
        return RestSupport.version(HttpStatus.OK, versions.reject(name, version, options), callers);
    }

    private static SourceFormat format(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("yaml")) {
            return SourceFormat.YAML;
        }
        if (type.contains("json")) {
            return SourceFormat.JSON;
        }
        throw new InputInvalidException("Content-Type must be application/yaml");
    }
}
