package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.CalendarChange;
import io.github.dhluong90.bpm.process.api.CalendarService;
import io.github.dhluong90.bpm.process.api.CalendarSummary;
import io.github.dhluong90.bpm.process.api.CalendarView;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.json.CalendarJson;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpHeaders;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Business calendar endpoints under {@code /api/bpm/processes/v1} (#409, PRE-114, PRE-181, PRE-182): read the
 * calendars in force, propose a change, list the changes, and approve or reject one as a second person. Permissions,
 * tenant, caller, four eyes and the idempotency record are enforced in {@link CalendarService}, so the Java API and
 * REST behave the same; a tenant in a body is never read. A proposal needs {@code If-Match} with the version it is made
 * against, and every write needs an {@code Idempotency-Key} ({@code 428} without either).
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class CalendarController {

    private static final Set<String> PROPOSAL_FIELDS = Set.of("calendar", "reason");
    private static final Set<String> DECISION_FIELDS = Set.of("comment", "expectedChangeHash");

    private final CalendarService calendars;

    /** @param calendars the calendar service */
    public CalendarController(CalendarService calendars) {
        this.calendars = Objects.requireNonNull(calendars, "calendars must not be null");
    }

    /** Lists the tenant's calendars by key ({@code processes:read}, PRE-181). */
    @GetMapping("/calendars")
    public ResponseEntity<byte[]> list(@RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return json(HttpStatus.OK, Views.page(calendars.calendars(new PageRequest(limitOf(limit), cursor)),
                CalendarController::summary), null);
    }

    /** Reads one calendar; the {@code ETag} is its version ({@code processes:read}, PRE-181). */
    @GetMapping("/calendars/{key}")
    public ResponseEntity<byte[]> read(@PathVariable("key") String key) {
        CalendarView view = calendars.calendar(key);
        return json(HttpStatus.OK, calendar(view), view.version());
    }

    /**
     * Proposes a calendar or a change ({@code processes:calendar:manage}, PRE-114, PRE-182). Body: {@code calendar}
     * and {@code reason}, both required. {@code 201} with the change, {@code PENDING} under four eyes.
     */
    @PostMapping("/calendars/{key}/changes")
    public ResponseEntity<byte[]> propose(@PathVariable("key") String key, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long version = version(ifMatch);
        requireKey(idempotencyKey);
        JsonNode body = body(http, PROPOSAL_FIELDS);
        JsonNode declared = body.get("calendar");
        if (declared == null || !declared.isObject()) {
            throw new InputInvalidException("calendar is required: zone, week and holidays");
        }
        String reason = text(body, "reason");
        CalendarChange change = calendars.propose(key, workingCalendar(declared), version, reason, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.LOCATION, ProcessController.BASE + "/calendar-changes/" + change.id())
                .body(bytes(change(change)));
    }

    /** Lists the tenant's changes of one status, newest first ({@code processes:read}, PRE-181). */
    @GetMapping("/calendar-changes")
    public ResponseEntity<byte[]> changes(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return json(HttpStatus.OK, Views.page(calendars.changes(status == null ? "PENDING" : status.strip(),
                new PageRequest(limitOf(limit), cursor)), CalendarController::change), null);
    }

    /** Reads one change with its old and new value ({@code processes:read}, PRE-181). */
    @GetMapping("/calendar-changes/{id}")
    public ResponseEntity<byte[]> change(@PathVariable("id") String id) {
        return json(HttpStatus.OK, change(calendars.change(id)), null);
    }

    /** Approves a pending change as a second person ({@code processes:calendar:manage}). Body: {@code comment}. */
    @PostMapping("/calendar-changes/{id}:approve")
    public ResponseEntity<byte[]> approve(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        requireKey(idempotencyKey);
        JsonNode body = body(http, DECISION_FIELDS);
        String comment = text(body, "comment");
        // PD-139, #473: the change the approver reviewed; required unless approval.require-content-hash=false (dev)
        String expectedChangeHash = text(body, "expectedChangeHash");
        return json(HttpStatus.OK, change(calendars.approve(id, comment, idempotencyKey, expectedChangeHash)), null);
    }

    /** Rejects a pending change; the calendar stays as it is. Body: {@code comment}, required. */
    @PostMapping("/calendar-changes/{id}:reject")
    public ResponseEntity<byte[]> reject(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        requireKey(idempotencyKey);
        String comment = text(body(http, DECISION_FIELDS), "comment");
        return json(HttpStatus.OK, change(calendars.reject(id, comment, idempotencyKey)), null);
    }

    static ObjectNode summary(CalendarSummary s) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("key", s.key());
        node.put("version", s.version());
        node.put("zone", s.zone());
        node.put("updatedBy", s.updatedBy());
        node.put("approvedBy", s.approvedBy());
        node.put("updatedAt", s.updatedAt().toString());
        return node;
    }

    static ObjectNode calendar(CalendarView v) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("key", v.key());
        node.put("version", v.version());
        node.set("calendar", definition(v.calendar()));
        node.put("updatedBy", v.updatedBy());
        node.put("approvedBy", v.approvedBy());
        node.put("updatedAt", v.updatedAt().toString());
        return node;
    }

    static ObjectNode change(CalendarChange c) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("id", c.id());
        node.put("calendarKey", c.calendarKey());
        node.put("status", c.status());
        node.put("approval", c.approval());
        node.put("baseVersion", c.baseVersion());
        if (c.previous() == null) {
            node.putNull("previous");
        } else {
            node.set("previous", definition(c.previous()));
        }
        node.set("proposed", definition(c.proposed()));
        node.put("reason", c.reason());
        node.put("proposedBy", c.proposedBy());
        node.put("proposedAt", c.proposedAt().toString());
        putNullable(node, "decidedBy", c.decidedBy());
        putNullable(node, "decidedAt", c.decidedAt() == null ? null : c.decidedAt().toString());
        putNullable(node, "comment", c.comment());
        // PD-139, #473: what an approver names as expectedChangeHash; the same value as the item of GET /approvals
        node.put("contentHash", io.github.dhluong90.bpm.process.internal.service.Calendars.changeHash(c.calendarKey(),
                c.baseVersion(), c.proposed()));
        return node;
    }

    private static JsonNode definition(WorkingCalendar calendar) {
        return EngineJson.JSON.readTree(Values.write(CalendarJson.toMap(calendar)));
    }

    private static WorkingCalendar workingCalendar(JsonNode declared) {
        for (String name : declared.propertyNames()) {
            if (!Set.of("zone", "week", "holidays").contains(name)) {
                throw new InputInvalidException("calendar takes zone, week and holidays");
            }
        }
        try {
            Map<String, Object> map = Values.readMap(EngineJson.JSON.writeValueAsString(declared));
            return CalendarJson.fromMap(map);
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            String why = e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\p{Cntrl}", "?");
            throw new InputInvalidException("calendar is not valid" + (why.length() > 200 ? why.substring(0, 200) : why));
        }
    }

    private static long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the calendar's version (its ETag), \"0\" for a "
                    + "new calendar");
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new InputInvalidException("If-Match must be a quoted calendar version, for example \"1\"");
        }
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PreconditionRequiredException("Send an Idempotency-Key so a repeated request changes nothing");
        }
    }

    private static JsonNode body(HttpServletRequest http, Set<String> fields) throws IOException {
        byte[] bytes = ProcessController.boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        if (bytes.length == 0) {
            return EngineJson.NODES.objectNode();
        }
        try {
            JsonNode node = EngineJson.JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new InputInvalidException("The body must be a JSON object");
            }
            for (String name : node.propertyNames()) {
                if (!fields.contains(name)) {
                    throw new InputInvalidException("Unknown field " + (name.length() > 64 ? name.substring(0, 64)
                            : name) + "; this request takes " + String.join(", ", new java.util.TreeSet<>(fields)));
                }
            }
            return node;
        } catch (JacksonException e) {
            throw new InputInvalidException("The body is not valid JSON");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InputInvalidException(field + " must be a string");
        }
        return value.stringValue();
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

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body, Long etag) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            builder.header(HttpHeaders.ETAG, "\"" + etag + "\"");
        }
        return builder.body(bytes(body));
    }

    private static byte[] bytes(JsonNode body) {
        return EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
