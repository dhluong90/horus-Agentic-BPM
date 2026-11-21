package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.LayoutNotFoundException;
import io.github.dhluong90.bpm.process.api.LayoutRevision;
import io.github.dhluong90.bpm.process.api.LayoutService;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.Layouts;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The diagram layout of a definition version under {@code /api/bpm/processes/v1} (#102, PRE-026): read the latest or a
 * named revision, save a new one with {@code If-Match}, list the revisions. The tenant and the caller come from the
 * service; a tenant in a body is never read, and the body is the layout document itself.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class LayoutController {

    private final LayoutService layouts;

    /** @param layouts the layout service */
    public LayoutController(LayoutService layouts) {
        this.layouts = Objects.requireNonNull(layouts, "layouts must not be null");
    }

    /** Reads the latest revision, or {@code revision}; {@code 204} with {@code ETag: "0"} when none was saved. */
    @GetMapping("/definitions/{key}/versions/{version}/layout")
    public ResponseEntity<byte[]> read(@PathVariable("key") String key, @PathVariable("version") String version,
            @RequestParam(value = "revision", required = false) String revision) {
        DefinitionRef ref = DefinitionRef.of(key, number("version", version, 1));
        if (revision != null) {
            int n = queryNumber("revision", revision, 1);
            LayoutRevision found = layouts.layoutRevision(ref, n).orElseThrow(() ->
                    new LayoutNotFoundException("The version has no layout revision " + n));
            return json(HttpStatus.OK, revision(found), found.revision());
        }
        Optional<LayoutRevision> latest = layouts.latestLayout(ref);
        if (latest.isEmpty()) {
            return ResponseEntity.noContent().header(HttpHeaders.ETAG, "\"0\"").build();
        }
        return json(HttpStatus.OK, revision(latest.get()), latest.get().revision());
    }

    /** Saves a revision over the one named in {@code If-Match}; no approval, the definition is not touched. */
    @PutMapping(value = "/definitions/{key}/versions/{version}/layout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> save(@PathVariable("key") String key, @PathVariable("version") String version,
            HttpServletRequest http, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the latest layout revision, for example \"0\"");
        }
        byte[] body = ProcessController.boundedBody(http, Layouts.MAX_BYTES);
        LayoutRevision saved = layouts.saveLayout(DefinitionRef.of(key, number("version", version, 1)),
                expectedRevision(ifMatch), new String(body, StandardCharsets.UTF_8), idempotencyKey);
        return json(HttpStatus.OK, revision(saved), saved.revision());
    }

    /** Lists the revisions oldest first, without their documents. */
    @GetMapping("/definitions/{key}/versions/{version}/layout/revisions")
    public ResponseEntity<byte[]> revisions(@PathVariable("key") String key, @PathVariable("version") String version,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "limit", required = false) String limit) {
        int pageSize = limit == null ? 50 : queryNumber("limit", limit, 1);
        int from = after == null ? 0 : queryNumber("after", after, 0);
        List<LayoutRevision> found = layouts.layoutRevisions(DefinitionRef.of(key, number("version", version, 1)), from,
                pageSize + 1);
        boolean more = found.size() > pageSize;
        ObjectNode body = EngineJson.NODES.objectNode();
        ArrayNode items = body.putArray("items");
        found.stream().limit(pageSize).forEach(r -> items.add(revision(r)));
        if (more) {
            body.put("nextAfter", found.get(pageSize - 1).revision());
        } else {
            body.putNull("nextAfter");
        }
        return json(HttpStatus.OK, body, null);
    }

    static ObjectNode revision(LayoutRevision r) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("revision", r.revision());
        if (r.layoutJson() != null) {
            node.set("layout", EngineJson.JSON.readTree(r.layoutJson()));
        }
        node.put("layoutHash", r.layoutHash());
        node.put("definitionHash", r.definitionHash());
        node.put("source", r.source());
        node.put("createdBy", r.createdBy());
        node.put("createdAt", r.createdAt().toString());
        return node;
    }

    private static long expectedRevision(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new InputInvalidException("If-Match must be a quoted layout revision, for example \"3\"");
        }
    }

    private static int number(String name, String value, int min) {
        try {
            int n = Integer.parseInt(value.strip());
            if (n >= min) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new InputInvalidException(name + " must be a whole number of " + min + " or more");
    }

    private static int queryNumber(String name, String value, int min) {
        try {
            return number(name, value, min);
        } catch (InputInvalidException e) {
            throw new QueryInvalidException(e.getMessage());
        }
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body, Integer etag) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            builder.header(HttpHeaders.ETAG, "\"" + etag + "\"");
        }
        try {
            return builder.body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}
