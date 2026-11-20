package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.UnauthenticatedException;
import io.github.dhluong90.bpm.rules.api.VersionActions;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Shared helpers of the engine controllers. */
final class RestSupport {

    /** Base path of every Rule Engine endpoint. */
    static final String BASE = "/api/bpm/rules/v1";

    private RestSupport() {
    }

    static void require(CallerResolver callers, String permission) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("Authentication required");
        }
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }

    static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(EngineValues.write(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a version with its {@code ETag} and the caller's {@code allowedActions}, computed now: an idempotent
     * replay stores the version, never the actions, so they always reflect the caller's current permissions.
     */
    static ResponseEntity<byte[]> version(HttpStatus status, RuleSetVersion version, CallerResolver callers) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header(HttpHeaders.ETAG, "\"" + version.lockVersion() + "\"");
        if (status == HttpStatus.CREATED) {
            builder.header(HttpHeaders.LOCATION, BASE + "/rule-sets/" + version.ruleSet() + "/versions/" + version.version());
        }
        ObjectNode body = EngineValues.version(version);
        body.set("allowedActions", actions(version.status(), version.createdBy(), version.requestedBy(), callers));
        return builder.contentType(MediaType.APPLICATION_JSON)
                .body(EngineValues.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static ArrayNode actions(VersionStatus status, String createdBy, String requestedBy, CallerResolver callers) {
        ArrayNode actions = EngineJson.NODES.arrayNode();
        VersionActions.allowed(status, createdBy, requestedBy, callers.currentCallerId(), callers.currentPermissions())
                .forEach(actions::add);
        return actions;
    }

    static ObjectNode ruleSetSummary(RuleSetSummary summary) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", summary.ruleSet());
        node.put("latestVersion", summary.latestVersion());
        node.put("latestStatus", summary.latestStatus().name());
        node.set("publishedVersion", EngineValues.value(summary.publishedVersion()));
        node.put("lastChangedAt", summary.lastChangedAt().toString());
        return node;
    }

    static ObjectNode versionSummary(VersionSummary v, CallerResolver callers) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", v.ruleSet());
        node.put("version", v.version());
        node.put("status", v.status().name());
        node.put("hitPolicy", v.hitPolicy());
        node.set("effectiveFrom", EngineValues.value(v.effectiveFrom()));
        node.put("lockVersion", v.lockVersion());
        node.put("canonicalHash", v.canonicalHash());
        node.set("createdBy", EngineValues.value(v.createdBy()));
        node.put("createdAt", v.createdAt().toString());
        node.set("requestedBy", EngineValues.value(v.requestedBy()));
        node.set("requestedAt", EngineValues.value(v.requestedAt()));
        node.set("approvedBy", EngineValues.value(v.approvedBy()));
        node.set("approvedAt", EngineValues.value(v.approvedAt()));
        node.set("allowedActions", actions(v.status(), v.createdBy(), v.requestedBy(), callers));
        return node;
    }

    static ObjectNode historyEntry(VersionHistoryEntry entry) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("action", entry.action().name());
        node.set("fromStatus", EngineValues.value(entry.fromStatus() == null ? null : entry.fromStatus().name()));
        node.put("toStatus", entry.toStatus().name());
        node.put("actor", entry.actor());
        node.set("reason", EngineValues.value(entry.reason()));
        if (entry.action() == io.github.dhluong90.bpm.rules.api.LifecycleAction.RETIRED) {
            // Only a retirement has these: by hand (approvalMode single) or superseded (system, supersededBy).
            node.set("supersededBy", entry.supersededBy() == null
                    ? EngineJson.NODES.nullNode() : EngineJson.NODES.numberNode(entry.supersededBy()));
            node.set("approvalMode", EngineValues.value(entry.approvalMode()));
            node.set("noVersionInEffect", entry.noVersionInEffect() == null
                    ? EngineJson.NODES.nullNode() : EngineJson.NODES.booleanNode(entry.noVersionInEffect()));
        }
        node.put("lockVersion", entry.lockVersion());
        node.put("occurredAt", entry.occurredAt().toString());
        if (entry.canonicalHash() != null) {
            node.put("canonicalHash", entry.canonicalHash());
            node.put("previousCanonicalHash", entry.previousCanonicalHash());
        }
        return node;
    }

    static <T> ObjectNode page(Page<T> page, java.util.function.Function<T, ObjectNode> item) {
        ObjectNode node = EngineJson.NODES.objectNode();
        ArrayNode data = node.putArray("data");
        page.items().forEach(i -> data.add(item.apply(i)));
        node.set("nextCursor", EngineValues.value(page.nextCursor()));
        node.put("hasNext", page.nextCursor() != null);
        return node;
    }

    /** Parses {@code ?limit=}; a value that is not a number is {@code QUERY_INVALID}, like one out of range. */
    static Integer limit(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            throw new QueryInvalidException("limit must be a whole number");
        }
    }

    /** Parses {@code If-Match: "3"} into 3; null when absent. */
    static Long ifMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new io.github.dhluong90.bpm.rules.api.InputInvalidException("If-Match must be a quoted lock version, for example \"3\"");
        }
    }

    /**
     * Parses a required {@code If-Match}; absent or blank is {@code 428 PRECONDITION_REQUIRED}.
     *
     * @param header the header value
     * @param operation the command, named in the problem detail
     * @return the lock version
     */
    static long requiredIfMatch(String header, String operation) {
        Long lock = ifMatch(header);
        if (lock == null) {
            throw new io.github.dhluong90.bpm.rules.api.PreconditionRequiredException(operation);
        }
        return lock;
    }

    /**
     * Returns a required {@code Idempotency-Key}; absent or blank is {@code 400 INPUT_INVALID} naming the header.
     *
     * @param key the header value
     * @param operation the command, named in the problem detail
     * @return the key
     */
    static String requiredIdempotencyKey(String key, String operation) {
        if (key == null || key.isBlank()) {
            throw new io.github.dhluong90.bpm.rules.api.InputInvalidException(operation + " requires an Idempotency-Key header");
        }
        return key;
    }

    /**
     * Writes a version diff (RLE-028, RLE-048). Field values are the canonical JSON the engine computed, embedded as
     * JSON; member order is fixed, so a repeated request returns a byte-identical body.
     */
    static ObjectNode diff(io.github.dhluong90.bpm.rules.api.VersionDiff diff) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", diff.ruleSet());
        node.put("version", diff.version());
        node.put("base", diff.base());
        node.put("canonicalHash", diff.canonicalHash());
        node.put("baseCanonicalHash", diff.baseCanonicalHash());
        node.put("sourceHashChanged", diff.sourceHashChanged());
        ObjectNode effective = node.putObject("effectiveFrom");
        effective.set("from", EngineValues.value(diff.baseEffectiveFrom()));
        effective.set("to", EngineValues.value(diff.effectiveFrom()));
        ArrayNode header = node.putArray("header");
        diff.header().forEach(f -> header.add(field(f)));
        node.set("inputs", columns(diff.inputs()));
        node.set("outputs", columns(diff.outputs()));
        ArrayNode rows = node.putArray("rows");
        for (io.github.dhluong90.bpm.rules.api.VersionDiff.RowChange row : diff.rows()) {
            ObjectNode item = rows.addObject();
            item.put("ruleId", row.ruleId());
            item.put("change", row.change().name());
            if (row.position() != null) {
                item.put("position", row.position());
            }
            if (row.basePosition() != null) {
                item.put("basePosition", row.basePosition());
            }
            if (row.change() == io.github.dhluong90.bpm.rules.api.VersionDiff.Change.CHANGED) {
                item.put("moved", row.moved());
                ArrayNode fields = item.putArray("fields");
                row.fields().forEach(f -> fields.add(field(f)));
            }
        }
        node.put("unchangedCount", diff.unchangedCount());
        ArrayNode masking = node.putArray("masking");
        for (io.github.dhluong90.bpm.rules.api.VersionDiff.InputMasking input : diff.masking()) {
            ObjectNode item = masking.addObject();
            item.put("input", input.input());
            item.set("from", EngineValues.value(input.from() == null ? null : input.from().name()));
            item.set("to", EngineValues.value(input.to() == null ? null : input.to().name()));
            item.put("changed", input.changed());
            item.set("personalDataName", EngineValues.value(input.personalDataName()));
        }
        return node;
    }

    private static ObjectNode columns(io.github.dhluong90.bpm.rules.api.VersionDiff.ColumnChanges changes) {
        ObjectNode node = EngineJson.NODES.objectNode();
        ArrayNode added = node.putArray("added");
        changes.added().forEach(added::add);
        ArrayNode removed = node.putArray("removed");
        changes.removed().forEach(removed::add);
        ArrayNode changed = node.putArray("changed");
        for (io.github.dhluong90.bpm.rules.api.VersionDiff.ColumnChange column : changes.changed()) {
            ObjectNode item = changed.addObject();
            item.put("name", column.name());
            ArrayNode fields = item.putArray("fields");
            column.fields().forEach(f -> fields.add(field(f)));
        }
        node.put("reordered", changes.reordered());
        return node;
    }

    private static ObjectNode field(io.github.dhluong90.bpm.rules.api.VersionDiff.FieldChange change) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("path", change.path());
        node.set("from", change.from() == null ? EngineJson.NODES.nullNode() : EngineValues.read(change.from()));
        node.set("to", change.to() == null ? EngineJson.NODES.nullNode() : EngineValues.read(change.to()));
        node.put("nonAscii", change.nonAscii());
        return node;
    }

    /**
     * Reads an import body of at most {@link RuleSetReader#MAX_SOURCE_BYTES} bytes. A larger declared
     * {@code Content-Length} is refused before anything is read; otherwise at most one byte more than the limit is
     * read, so an oversized body never sits in memory whole.
     *
     * @param body the request body stream
     * @param contentLength the declared length, or null
     * @return the body bytes
     * @throws PayloadTooLargeException when the body is larger than the limit
     * @throws IOException when the stream fails
     */
    static byte[] boundedBody(InputStream body, Long contentLength) throws IOException {
        return boundedBody(body, contentLength, RuleSetReader.MAX_SOURCE_BYTES);
    }

    /**
     * Reads a body of at most {@code limit} bytes, as {@link #boundedBody(InputStream, Long)} does for imports.
     *
     * @param body the request body stream
     * @param contentLength the declared length, or null
     * @param limit the largest accepted size, in bytes
     * @return the body bytes
     * @throws PayloadTooLargeException when the body is larger than the limit
     * @throws IOException when the stream fails
     */
    static byte[] boundedBody(InputStream body, Long contentLength, int limit) throws IOException {
        if (contentLength != null && contentLength > limit) {
            throw new PayloadTooLargeException(limit);
        }
        byte[] bytes = body.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new PayloadTooLargeException(limit);
        }
        return bytes;
    }
}
