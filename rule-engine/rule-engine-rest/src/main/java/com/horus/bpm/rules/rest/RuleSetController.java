package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.VersionNotFoundException;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** {@code POST /rule-sets}: imports a document as version 1 of a new rule set. */
    @PostMapping
    public ResponseEntity<byte[]> importRuleSet(
            InputStream body,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        RestSupport.require(callers, "rules:author");
        ImportRequest request = new ImportRequest(format(contentType), RestSupport.boundedBody(body, contentLength),
                idempotencyKey);
        return RestSupport.version(HttpStatus.CREATED, versions.importRuleSet(request));
    }

    /** {@code POST /rule-sets/{name}/versions}: imports a document as the next draft version. */
    @PostMapping("/{name}/versions")
    public ResponseEntity<byte[]> importVersion(
            @PathVariable("name") String name,
            InputStream body,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        RestSupport.require(callers, "rules:author");
        ImportRequest request = new ImportRequest(format(contentType), RestSupport.boundedBody(body, contentLength),
                idempotencyKey);
        return RestSupport.version(HttpStatus.CREATED, versions.importVersion(name, request));
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
                .map(v -> RestSupport.version(HttpStatus.OK, v))
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
        return RestSupport.version(HttpStatus.OK, versions.requestPublish(name, version, options));
    }

    /** {@code POST /rule-sets/{name}/versions/{v}:approve}, optional body {@code {"comment": "..."}}. */
    @PostMapping("/{name}/versions/{version:\\d+}:approve")
    public ResponseEntity<byte[]> approve(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        RestSupport.require(callers, "rules:approve");
        String comment = null;
        if (body != null && body.length > 0) {
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
        return RestSupport.version(HttpStatus.OK, versions.approve(name, version, options));
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
            case "yaml", "" -> SourceFormat.YAML;
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
