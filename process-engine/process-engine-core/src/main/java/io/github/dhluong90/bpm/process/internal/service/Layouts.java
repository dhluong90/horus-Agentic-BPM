package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.LayoutInvalidException;
import io.github.dhluong90.bpm.process.api.LayoutRevision;
import io.github.dhluong90.bpm.process.api.LayoutService;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.internal.exec.Ids;
import io.github.dhluong90.bpm.process.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.store.LayoutStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.StoredDefinition;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Diagram layout revisions (#102, PRE-026, PRE-024, DESIGN section 5.4). A save validates the document before any
 * transaction opens, then appends one revision and publishes {@code processes.definition.layout-saved.v1} in one
 * transaction. It never writes the definition table, so the status, the hash and the lock version of the version are
 * untouched and no approval is requested. The tenant and the caller come from the resolvers. Thread-safe.
 */
public final class Layouts implements LayoutService {

    /** The largest layout document, 256 KB. */
    public static final int MAX_BYTES = 262_144;
    /** Most problems one refusal lists. */
    static final int MAX_PROBLEMS = 20;
    static final String SOURCE = "API";

    private static final System.Logger LOG = System.getLogger(Layouts.class.getName());
    private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");
    private static final Pattern EDGE_KEY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)->([A-Za-z_][A-Za-z0-9_.-]*)$");
    private static final Pattern LANE_ID = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Set<String> ROOT = Set.of("schemaVersion", "direction", "nodes", "edges", "lanes");
    private static final double COORDINATE_LIMIT = 100_000;
    private static final double SIZE_LIMIT = 2_000;

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessStore definitions;
    private final LayoutStore store;
    private final Function<StoredDefinition, Process> models;
    private final ProcessEventPublisher publisher;
    private final IdempotentCalls idempotency;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transactions on the host's transaction manager
     * @param definitions the definition store, also for idempotency records
     * @param store layout storage
     * @param models the hash-checked model of a stored version
     * @param publisher event publisher
     * @param idempotencyRetention how long an idempotency record answers replays
     */
    public Layouts(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore definitions, LayoutStore store, Function<StoredDefinition, Process> models,
            ProcessEventPublisher publisher, Duration idempotencyRetention) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.store = Objects.requireNonNull(store, "store");
        this.models = Objects.requireNonNull(models, "models");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.idempotency = new IdempotentCalls(definitions, clock, Objects.requireNonNull(idempotencyRetention,
                "idempotencyRetention"));
    }

    @Override
    public LayoutRevision saveLayout(DefinitionRef ref, long expectedRevision, String layoutJson,
            String idempotencyKey) {
        String tenant = tenant();
        String caller = caller(ProcessServices.PERMISSION_AUTHOR);
        Objects.requireNonNull(ref, "ref");
        if (expectedRevision < 0 || expectedRevision > Integer.MAX_VALUE) {
            throw new InputInvalidException("The expected revision must be 0 or more");
        }
        JsonNode layout = parse(layoutJson);
        String canonical = CanonicalJson.write(layout);
        String requestHash = Hashes.sha256Parts("layout-save", caller, ref.key(), Integer.toString(ref.version()),
                Long.toString(expectedRevision), Hashes.sha256(canonical));
        return transactions.inTransaction(() -> idempotency.run(tenant, "layout-save", idempotencyKey, requestHash,
                () -> {
                    StoredDefinition definition = version(tenant, ref);
                    int latest = store.latestRevision(tenant, definition.id());
                    if (latest != expectedRevision) {
                        throw new VersionConflictException("The layout is at revision " + latest + " but the save "
                                + "was made over revision " + expectedRevision);
                    }
                    // The model is read through the stored-source integrity check, so a layout is never drawn for a
                    // definition that no longer gives its recorded hash.
                    check(layout, steps(models.apply(definition)));
                    Instant now = clock.instant();
                    LayoutRevision revision = new LayoutRevision(latest + 1, canonical, Hashes.sha256(canonical),
                            definition.definitionHash(), SOURCE, caller, now);
                    if (!store.insert(tenant, definition.id(), revision)) {
                        throw new VersionConflictException("Another save made revision " + revision.revision()
                                + " first; reload the layout");
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("definitionKey", definition.key());
                    payload.put("version", definition.version());
                    payload.put("revision", revision.revision());
                    payload.put("layoutHash", revision.layoutHash());
                    payload.put("definitionHash", definition.definitionHash());
                    payload.put("createdBy", caller);
                    publish(new ProcessEventPublisher.Event(Ids.child(definition.id(), revision.revision()),
                            "processes.definition.layout-saved.v1", tenant, now, payload));
                    LOG.log(System.Logger.Level.INFO, "process.layout.saved tenant={0} key={1} version={2} "
                            + "revision={3}", tenant, definition.key(), definition.version(), revision.revision());
                    return revision;
                }, Layouts::toJson, Layouts::fromJson));
    }

    @Override
    public Optional<LayoutRevision> latestLayout(DefinitionRef ref) {
        String tenant = tenant();
        caller(ProcessServices.PERMISSION_READ);
        return transactions.inTransaction(() -> {
            StoredDefinition definition = version(tenant, ref);
            int latest = store.latestRevision(tenant, definition.id());
            return latest == 0 ? Optional.<LayoutRevision>empty()
                    : store.find(tenant, definition.id(), latest).map(Layouts::canonical);
        });
    }

    @Override
    public Optional<LayoutRevision> layoutRevision(DefinitionRef ref, int revision) {
        String tenant = tenant();
        caller(ProcessServices.PERMISSION_READ);
        return transactions.inTransaction(() -> store.find(tenant, version(tenant, ref).id(), revision)
                .map(Layouts::canonical));
    }

    @Override
    public List<LayoutRevision> layoutRevisions(DefinitionRef ref, int afterRevision, int limit) {
        String tenant = tenant();
        caller(ProcessServices.PERMISSION_READ);
        if (afterRevision < 0) {
            throw new InputInvalidException("after must be 0 or more");
        }
        if (limit < 1 || limit > EngineDefaults.MAX_PAGE_SIZE) {
            throw new InputInvalidException("limit must be 1 to " + EngineDefaults.MAX_PAGE_SIZE);
        }
        return transactions.inTransaction(() -> store.list(tenant, version(tenant, ref).id(), afterRevision, limit));
    }

    // ---- validation ------------------------------------------------------------------------------------------

    private static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InputInvalidException("The layout document is required");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new PayloadTooLargeException("The layout is larger than " + MAX_BYTES + " bytes");
        }
        try {
            JsonNode node = EngineJson.JSON.readTree(text);
            if (node == null || !node.isObject()) {
                throw new InputInvalidException("The layout must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new InputInvalidException("The layout is not valid JSON");
        }
    }

    /** The ids of the steps of the definition. */
    private static Set<String> steps(Process process) {
        Set<String> ids = new HashSet<>();
        // The steps inside a sub-process or a saga block are steps too (#486).
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof FlowNode node) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    /** Checks the document against {@code definition-layout.v1} and against the steps of the version. */
    static void check(JsonNode layout, Set<String> steps) {
        List<String> problems = new ArrayList<>();
        for (String name : layout.propertyNames()) {
            if (!ROOT.contains(name)) {
                add(problems, "$." + printable(name), "is not a layout member");
            }
        }
        JsonNode version = layout.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asInt() != 1) {
            add(problems, "$.schemaVersion", "must be 1");
        }
        JsonNode direction = layout.get("direction");
        if (direction != null && !(direction.isString() && Set.of("LR", "TB").contains(direction.asString()))) {
            add(problems, "$.direction", "must be LR or TB");
        }
        nodes(layout.get("nodes"), steps, problems);
        edges(layout.get("edges"), steps, problems);
        lanes(layout.get("lanes"), steps, problems);
        if (!problems.isEmpty()) {
            throw new LayoutInvalidException("The layout is not valid for this version: " + problems.size()
                    + (problems.size() >= MAX_PROBLEMS ? " or more" : "") + " problem(s), first " + problems.getFirst(),
                    problems);
        }
    }

    private static void nodes(JsonNode nodes, Set<String> steps, List<String> problems) {
        if (nodes == null || !nodes.isObject()) {
            add(problems, "$.nodes", "is required and must be an object");
            return;
        }
        if (nodes.size() > 500) {
            add(problems, "$.nodes", "holds more than 500 nodes");
        }
        for (String id : nodes.propertyNames()) {
            String path = "$.nodes." + printable(id);
            if (id.length() > 100 || !NODE_ID.matcher(id).matches()) {
                add(problems, path, "is not a step id");
            } else if (!steps.contains(id)) {
                add(problems, path, "names a step that is not in the definition");
            }
            JsonNode box = nodes.get(id);
            if (!box.isObject()) {
                add(problems, path, "must be an object with x and y");
                continue;
            }
            for (String member : box.propertyNames()) {
                if (!Set.of("x", "y", "width", "height").contains(member)) {
                    add(problems, path + "." + printable(member), "is not a box member");
                }
            }
            coordinate(box.get("x"), path + ".x", true, problems);
            coordinate(box.get("y"), path + ".y", true, problems);
            size(box.get("width"), path + ".width", problems);
            size(box.get("height"), path + ".height", problems);
        }
    }

    private static void edges(JsonNode edges, Set<String> steps, List<String> problems) {
        if (edges == null) {
            return;
        }
        if (!edges.isObject()) {
            add(problems, "$.edges", "must be an object");
            return;
        }
        if (edges.size() > 2000) {
            add(problems, "$.edges", "holds more than 2000 edges");
        }
        for (String key : edges.propertyNames()) {
            String path = "$.edges." + printable(key);
            var match = EDGE_KEY.matcher(key);
            if (key.length() > 202 || !match.matches()) {
                add(problems, path, "is not a link key, from->to");
            } else if (!steps.contains(match.group(1)) || !steps.contains(match.group(2))) {
                add(problems, path, "names a step that is not in the definition");
            }
            JsonNode points = edges.get(key);
            if (!points.isArray() || points.size() > 50) {
                add(problems, path, "must be a list of at most 50 points");
                continue;
            }
            for (int i = 0; i < points.size(); i++) {
                JsonNode point = points.get(i);
                if (!point.isObject() || point.size() != 2) {
                    add(problems, path + "[" + i + "]", "must be a point with x and y only");
                    continue;
                }
                coordinate(point.get("x"), path + "[" + i + "].x", true, problems);
                coordinate(point.get("y"), path + "[" + i + "].y", true, problems);
            }
        }
    }

    private static void lanes(JsonNode lanes, Set<String> steps, List<String> problems) {
        if (lanes == null) {
            return;
        }
        if (!lanes.isArray() || lanes.size() > 50) {
            add(problems, "$.lanes", "must be a list of at most 50 lanes");
            return;
        }
        for (int i = 0; i < lanes.size(); i++) {
            JsonNode lane = lanes.get(i);
            String path = "$.lanes[" + i + "]";
            if (!lane.isObject()) {
                add(problems, path, "must be an object");
                continue;
            }
            for (String member : lane.propertyNames()) {
                if (!Set.of("id", "label", "nodes").contains(member)) {
                    add(problems, path + "." + printable(member), "is not a lane member");
                }
            }
            JsonNode id = lane.get("id");
            if (id == null || !id.isString() || !LANE_ID.matcher(id.asString()).matches()) {
                add(problems, path + ".id", "must be a lower-case kebab-case id");
            }
            JsonNode label = lane.get("label");
            if (label == null || !label.isString() || label.asString().length() > 200) {
                add(problems, path + ".label", "must be text of at most 200 characters");
            }
            JsonNode members = lane.get("nodes");
            if (members == null || !members.isArray()) {
                add(problems, path + ".nodes", "must be a list of step ids");
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (int n = 0; n < members.size(); n++) {
                JsonNode step = members.get(n);
                String where = path + ".nodes[" + n + "]";
                if (!step.isString()) {
                    add(problems, where, "must be a step id");
                } else if (!seen.add(step.asString())) {
                    add(problems, where, "repeats a step of the lane");
                } else if (!steps.contains(step.asString())) {
                    add(problems, where, "names a step that is not in the definition");
                }
            }
        }
    }

    private static void coordinate(JsonNode value, String path, boolean required, List<String> problems) {
        if (value == null) {
            if (required) {
                add(problems, path, "is required");
            }
            return;
        }
        if (!value.isNumber() || Math.abs(value.asDouble()) > COORDINATE_LIMIT) {
            add(problems, path, "must be a number within 100000");
        }
    }

    private static void size(JsonNode value, String path, List<String> problems) {
        if (value != null && (!value.isNumber() || value.asDouble() <= 0 || value.asDouble() > SIZE_LIMIT)) {
            add(problems, path, "must be a number above 0 and at most 2000");
        }
    }

    private static void add(List<String> problems, String path, String reason) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(path + " " + reason);
        }
    }

    /** A member name as it may appear in a refusal: cut at 64 characters, control characters dropped. */
    private static String printable(String name) {
        String clean = name.replaceAll("\\p{Cntrl}", "");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    // ---- plumbing --------------------------------------------------------------------------------------------

    private StoredDefinition version(String tenant, DefinitionRef ref) {
        return definitions.definitionVersion(tenant, ref.key(), ref.version()).orElseThrow(() ->
                new DefinitionNotFoundException("No definition " + ref.key() + " v" + ref.version()
                        + " in this tenant"));
    }

    private void publish(ProcessEventPublisher.Event event) {
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "process.event.listener.failed type={0} exception={1}",
                    event.type(), e.getClass().getName());
            throw new EventListenerFailedException("A listener of " + event.type() + " failed; the change was rolled "
                    + "back");
        }
    }

    /** The document as it was hashed: the database keeps JSON in its own spacing, the hash is over RFC 8785. */
    private static LayoutRevision canonical(LayoutRevision r) {
        return new LayoutRevision(r.revision(), CanonicalJson.write(EngineJson.JSON.readTree(r.layoutJson())),
                r.layoutHash(), r.definitionHash(), r.source(), r.createdBy(), r.createdAt());
    }

    private static String toJson(LayoutRevision r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("revision", r.revision());
        m.put("layoutJson", r.layoutJson());
        m.put("layoutHash", r.layoutHash());
        m.put("definitionHash", r.definitionHash());
        m.put("source", r.source());
        m.put("createdBy", r.createdBy());
        m.put("createdAt", r.createdAt().toString());
        return Values.write(m);
    }

    private static LayoutRevision fromJson(String json) {
        Map<String, Object> m = Values.readMap(json);
        return new LayoutRevision(((Number) m.get("revision")).intValue(), (String) m.get("layoutJson"),
                (String) m.get("layoutHash"), (String) m.get("definitionHash"), (String) m.get("source"),
                (String) m.get("createdBy"), Instant.parse((String) m.get("createdAt")));
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private String caller(String permission) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (caller.startsWith(ProcessServices.SYSTEM_PREFIX)) {
            throw new PermissionDeniedException("Identities starting with " + ProcessServices.SYSTEM_PREFIX
                    + " are reserved for the engine");
        }
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException("Needs " + permission);
        }
        return caller;
    }
}
