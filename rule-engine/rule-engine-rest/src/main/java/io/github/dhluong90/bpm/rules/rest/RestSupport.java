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
        body.set("allowedActions", actions(version.status(), version.createdBy(), version.requestedBy(),
                version.retirementRequest() == null ? null : version.retirementRequest().requestedBy(), callers));
        return builder.contentType(MediaType.APPLICATION_JSON)
                .body(EngineValues.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static ArrayNode actions(VersionStatus status, String createdBy, String requestedBy, CallerResolver callers) {
        return actions(status, createdBy, requestedBy, null, callers);
    }

    static ArrayNode actions(VersionStatus status, String createdBy, String requestedBy, String retireRequestedBy,
            CallerResolver callers) {
        ArrayNode actions = EngineJson.NODES.arrayNode();
        VersionActions.allowed(status, createdBy, requestedBy, retireRequestedBy, callers.currentCallerId(),
                callers.currentPermissions()).forEach(actions::add);
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
        node.set("retireRequestedBy", EngineValues.value(v.retireRequestedBy()));
        node.set("allowedActions", actions(v.status(), v.createdBy(), v.requestedBy(), v.retireRequestedBy(), callers));
        return node;
    }

    static ObjectNode historyEntry(VersionHistoryEntry entry) {
        return EngineValues.historyEntry(entry);
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

    /** The body of {@code :validate}; member order is fixed. */
    static ObjectNode validation(io.github.dhluong90.bpm.rules.api.DraftValidation result) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.put("valid", result.valid());
        ArrayNode problems = node.putArray("problems");
        for (io.github.dhluong90.bpm.rules.api.DraftValidation.Problem p : result.problems()) {
            ObjectNode item = problems.addObject();
            item.put("ruleId", p.ruleId());
            item.put("column", p.column());
            item.put("code", p.code());
            if (p.position() == null) {
                item.putNull("position");
            } else {
                item.put("position", p.position());
            }
            ObjectNode message = item.putObject("message");
            new java.util.TreeMap<>(p.message()).forEach(message::put);
        }
        node.put("problemsTruncated", result.problemsTruncated());
        ArrayNode readings = node.putArray("readings");
        for (io.github.dhluong90.bpm.rules.api.DraftValidation.Reading r : result.readings()) {
            ObjectNode item = readings.addObject();
            item.put("ruleId", r.ruleId());
            ObjectNode text = item.putObject("text");
            new java.util.TreeMap<>(r.text()).forEach(text::put);
        }
        node.put("readingsTruncated", result.readingsTruncated());
        if (result.readingsReason() == null) {
            node.putNull("readingsReason");
        } else {
            node.put("readingsReason", result.readingsReason());
        }
        return node;
    }

    private static ObjectNode reading(io.github.dhluong90.bpm.rules.api.RuleReadings.Reading reading) {
        return EngineJson.NODES.objectNode().put("full", reading.full()).put("short", reading.shortForm())
                .put("source", reading.source());
    }

    private static ObjectNode readings(java.util.Map<String, io.github.dhluong90.bpm.rules.api.RuleReadings.Reading> byLanguage) {
        ObjectNode node = EngineJson.NODES.objectNode();
        new java.util.TreeMap<>(byLanguage).forEach((language, reading) -> node.set(language, reading(reading)));
        return node;
    }

    private static ArrayNode strings(java.util.List<String> values) {
        ArrayNode array = EngineJson.NODES.arrayNode();
        values.forEach(array::add);
        return array;
    }

    /** Writes the readings of every row of a version (RLE-091). Member order is fixed, so the body is repeatable. */
    static ObjectNode ruleSetReadings(io.github.dhluong90.bpm.rules.api.RuleReadings.RuleSetReadings result) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.set("languages", strings(result.languages()));
        ArrayNode rows = node.putArray("rows");
        for (io.github.dhluong90.bpm.rules.api.RuleReadings.Row row : result.rows()) {
            ObjectNode item = rows.addObject();
            item.put("ruleId", row.ruleId());
            item.put("position", row.position());
            item.set("readings", readings(row.readings()));
        }
        return node;
    }

    /** Writes the readings of the rows of a diff, in both versions (RLE-092). */
    static ObjectNode readingsDiff(io.github.dhluong90.bpm.rules.api.RuleReadings.ReadingsDiff result) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.put("base", result.base());
        node.set("languages", strings(result.languages()));
        ArrayNode rows = node.putArray("rows");
        for (io.github.dhluong90.bpm.rules.api.RuleReadings.ChangedRow row : result.rows()) {
            ObjectNode item = rows.addObject();
            item.put("ruleId", row.ruleId());
            item.put("change", row.change().name());
            if (row.position() != null) {
                item.put("position", row.position());
            }
            if (row.basePosition() != null) {
                item.put("basePosition", row.basePosition());
            }
            item.put("readingChanged", row.readingChanged());
            item.set("previous", readings(row.previous()));
            item.set("current", readings(row.current()));
        }
        node.put("unchangedCount", result.unchangedCount());
        return node;
    }

    private static JsonNode outcomeReading(io.github.dhluong90.bpm.rules.api.RuleReadings.OutcomeReading outcome) {
        if (outcome == null) {
            return EngineJson.NODES.nullNode();
        }
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("kind", outcome.kind());
        node.set("ruleIds", strings(outcome.ruleIds()));
        node.set("readings", readings(outcome.readings()));
        return node;
    }

    /** Writes the readings of a simulation result (RLE-093); no sample input is part of it. */
    static ObjectNode simulationReadings(io.github.dhluong90.bpm.rules.api.RuleReadings.SimulationReadings result) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.set("baselineVersion", EngineValues.value(result.baselineVersion()));
        node.set("languages", strings(result.languages()));
        ArrayNode samples = node.putArray("results");
        for (io.github.dhluong90.bpm.rules.api.RuleReadings.SampleReading sample : result.samples()) {
            ObjectNode item = samples.addObject();
            item.put("index", sample.index());
            item.put("reference", sample.reference());
            item.set("label", EngineValues.value(sample.label()));
            item.put("changed", sample.changed());
            item.set("simulated", outcomeReading(sample.simulated()));
            item.set("baseline", outcomeReading(sample.baseline()));
        }
        return node;
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
