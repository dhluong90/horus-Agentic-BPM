package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanInvalidException;
import io.github.dhluong90.bpm.process.api.MigrationStepCancellation;
import io.github.dhluong90.bpm.process.api.MigrationStepMapping;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.MigrationService;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.exec.Ids;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.StoredPlan;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.IdempotencyRecord;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.StoredDefinition;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Migration plans and their dry run (#74, PRE-014, PRE-174, PRE-175, DESIGN section 4.3). A plan is created in one
 * transaction with its history row and {@code processes.migration.planned.v1}, and records the approval its
 * execution needs (PD-131). The dry run maps every step that holds live tokens on the source version onto the target
 * by identical id and reports what blocks the plan; it writes nothing at all. The tenant and the caller come from
 * the resolvers. Thread-safe.
 */
public final class Migrations implements MigrationService {

    /** Permission to plan and dry-run migrations (ADR-0010). */
    public static final String PERMISSION_MANAGE = "processes:migration:manage";
    static final String PLANNED = "PLANNED";
    static final String SAME_ID = "SAME_ID";

    private static final System.Logger LOG = System.getLogger(Migrations.class.getName());
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Pattern ID = Pattern.compile("^[0-9A-Za-z]{1,26}$");
    private static final Pattern STEP_ID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$");
    /** Most mappings, and most cancellations, in one plan. */
    static final int MAX_INSTRUCTIONS = 200;
    private static final Set<String> MIGRATABLE_SOURCE = Set.of("ACTIVE", "RETIRED");

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessStore definitions;
    private final MigrationStore store;
    private final Function<StoredDefinition, Process> models;
    private final ProcessEventPublisher publisher;
    private final MigrationSettings settings;
    private final Duration idempotencyRetention;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transactions on the host's transaction manager
     * @param definitions the definition store, also for idempotency records
     * @param store migration storage
     * @param models the hash-checked model of a stored version
     * @param publisher event publisher
     * @param settings migration settings
     * @param idempotencyRetention how long an idempotency record answers replays
     */
    public Migrations(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore definitions, MigrationStore store, Function<StoredDefinition, Process> models,
            ProcessEventPublisher publisher, MigrationSettings settings, Duration idempotencyRetention) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.store = Objects.requireNonNull(store, "store");
        this.models = Objects.requireNonNull(models, "models");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.idempotencyRetention = Objects.requireNonNull(idempotencyRetention, "idempotencyRetention");
    }

    @Override
    public MigrationPlan plan(MigrationPlanRequest request) {
        String tenant = tenant();
        String caller = caller(PERMISSION_MANAGE);
        Objects.requireNonNull(request, "request must not be null");
        String key = request.definitionKey();
        if (key == null || !KEY.matcher(key).matches() || key.length() > 200) {
            throw new InputInvalidException("definitionKey must be a definition key");
        }
        if (request.sourceVersion() < 1 || request.targetVersion() < 1) {
            throw new InputInvalidException("sourceVersion and targetVersion must be 1 or more");
        }
        if (request.sourceVersion() == request.targetVersion()) {
            throw new InputInvalidException("sourceVersion and targetVersion must differ");
        }
        String instructions = instructionsText(request.mappings(), request.cancellations());
        // The instructions join the request hash only when there are some, so a plan stored before they existed still
        // replays under its key.
        String requestHash = instructions.isEmpty()
                ? Hashes.sha256Parts("migration-plan", caller, key, Integer.toString(request.sourceVersion()),
                        Integer.toString(request.targetVersion()))
                : Hashes.sha256Parts("migration-plan", caller, key, Integer.toString(request.sourceVersion()),
                        Integer.toString(request.targetVersion()), instructions);
        return transactions.inTransaction(() -> idempotent(tenant, request.idempotencyKey(), requestHash, () -> {
            StoredDefinition source = version(tenant, key, request.sourceVersion());
            StoredDefinition target = version(tenant, key, request.targetVersion());
            if (!"ACTIVE".equals(target.status())) {
                throw new InvalidStateException("Definition " + key + " v" + target.version() + " is "
                        + target.status() + "; instances move only to an active version");
            }
            if (!MIGRATABLE_SOURCE.contains(source.status())) {
                throw new InvalidStateException("Definition " + key + " v" + source.version() + " is "
                        + source.status() + " and has no running instances to move");
            }
            // Both models are read through the integrity check, so a plan is never made over a tampered source.
            Map<String, Step> from = steps(models.apply(source));
            Map<String, Step> to = steps(models.apply(target));
            checkInstructions(request.mappings(), request.cancellations(), from, to);
            Instant now = clock.instant();
            String id = Ulid.next(now);
            MigrationPlan plan = new MigrationPlan(id, key, source.version(), target.version(), PLANNED,
                    planHash(source, target, instructions), settings.approval(), caller, now, request.mappings(),
                    request.cancellations());
            store.insertPlan(new StoredPlan(tenant, plan, source.id(), target.id()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceVersion", plan.sourceVersion());
            details.put("targetVersion", plan.targetVersion());
            details.put("planHash", plan.planHash());
            details.put("approval", plan.approval().value());
            if (!plan.mappings().isEmpty()) {
                details.put("mappings", plan.mappings().stream().map(m -> m.from() + " -> " + m.to()).toList());
            }
            if (!plan.cancellations().isEmpty()) {
                // The reasons are in the plan row and in each instance's history; the plan's history names the steps.
                details.put("cancellations", plan.cancellations().stream().map(c -> c.step() + " -> " + c.moveTo())
                        .toList());
            }
            store.insertHistory(tenant, Ids.child(id, 0), id, PLANNED, caller, details, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("migrationId", id);
            payload.put("definitionKey", key);
            payload.putAll(details);
            payload.put("createdBy", caller);
            publish(new ProcessEventPublisher.Event(Ids.child(id, 0), "processes.migration.planned.v1", tenant, now,
                    payload));
            LOG.log(System.Logger.Level.INFO, "process.migration.planned tenant={0} migration={1} key={2} v{3}->v{4} "
                    + "approval={5}", tenant, id, key, plan.sourceVersion(), plan.targetVersion(),
                    plan.approval().value());
            return plan;
        }));
    }

    @Override
    public MigrationPlan find(String migrationId) {
        String tenant = tenant();
        caller(PERMISSION_MANAGE, ProcessServices.PERMISSION_READ);
        return transactions.inTransaction(() -> stored(tenant, migrationId).view());
    }

    @Override
    public MigrationDryRun validate(String migrationId) {
        String tenant = tenant();
        caller(PERMISSION_MANAGE);
        // Read-only (PRE-014): no row of any table is written, so a dry run needs no approval and no idempotency key.
        MigrationDryRun report = transactions.inTransaction(() -> dryRun(tenant, stored(tenant, migrationId)));
        LOG.log(System.Logger.Level.INFO, "process.migration.dry-run tenant={0} migration={1} valid={2} running={3} "
                + "blocked={4}", tenant, report.migrationId(), report.valid(), report.instances().running(),
                report.instances().blocked());
        return report;
    }

    /** The dry run of a stored plan, read-only; also what an execution repeats at its proposal and approval. */
    MigrationDryRun dryRun(String tenant, StoredPlan stored) {
        MigrationPlan plan = stored.view();
        StoredDefinition source = definitions.definition(tenant, stored.sourceDefinitionId()).orElseThrow();
        StoredDefinition target = definitions.definition(tenant, stored.targetDefinitionId()).orElseThrow();
        Map<String, Step> from = steps(models.apply(source));
        Map<String, Step> to = steps(models.apply(target));
        int sample = settings.dryRunSampleSize();
        Map<String, MigrationStepMapping> mapped = new HashMap<>();
        plan.mappings().forEach(m -> mapped.put(m.from(), m));
        Map<String, MigrationStepCancellation> cancelled = new HashMap<>();
        plan.cancellations().forEach(c -> cancelled.put(c.step(), c));

        List<MigrationDryRun.NodeMapping> nodes = new ArrayList<>();
        List<MigrationDryRun.Conflict> conflicts = new ArrayList<>();
        List<String> blockedNodes = new ArrayList<>();
        List<String> mappedNodes = new ArrayList<>();
        List<String> cancelNodes = new ArrayList<>();
        new TreeMap<>(store.liveTokensByNode(tenant, source.id())).forEach((nodeId, tokens) -> {
            Step was = from.get(nodeId);
            // The step the tokens go to: the plan's mapping or cancellation, otherwise the step with the same id.
            MigrationStepMapping map = mapped.get(nodeId);
            MigrationStepCancellation cancel = map == null ? cancelled.get(nodeId) : null;
            String targetId = map != null ? map.to() : cancel != null ? cancel.moveTo() : nodeId;
            Step now = to.get(targetId);
            String mapping = now == null ? MigrationDryRun.STEP_NOT_IN_TARGET
                    : map != null ? (was != null && !Objects.equals(was.type(), now.type())
                            ? MigrationDryRun.STEP_TYPE_CHANGED : MigrationDryRun.MAPPED)
                    : cancel != null ? MigrationDryRun.CANCELLED
                    : was != null && !Objects.equals(was.type(), now.type()) ? MigrationDryRun.STEP_TYPE_CHANGED
                    : SAME_ID;
            nodes.add(new MigrationDryRun.NodeMapping(nodeId, was == null ? null : was.name(),
                    was == null ? null : was.type(), tokens, mapping, now == null ? null : targetId,
                    now == null ? null : now.type()));
            if (MigrationDryRun.MAPPED.equals(mapping)) {
                mappedNodes.add(nodeId);
            } else if (MigrationDryRun.CANCELLED.equals(mapping)) {
                cancelNodes.add(nodeId);
            } else if (!SAME_ID.equals(mapping)) {
                blockedNodes.add(nodeId);
                long count = store.instancesAt(tenant, source.id(), List.of(nodeId));
                List<String> ids = store.instanceIdsAt(tenant, source.id(), nodeId, sample);
                conflicts.add(new MigrationDryRun.Conflict(mapping, nodeId, was == null ? null : was.name(),
                        new MigrationDryRun.InstanceSample(count, ids, count > ids.size())));
            }
        });
        // PRE-208: a removed step whose call is in flight is never cut off. It is reported, and its instance is left on
        // the source version for a later run; it does not make the plan invalid.
        long inFlight = 0;
        java.util.Set<String> inFlightIds = new HashSet<>();
        for (String nodeId : cancelNodes) {
            long count = store.inFlightInstances(tenant, source.id(), List.of(nodeId));
            if (count > 0) {
                List<String> ids = store.inFlightInstanceIds(tenant, source.id(), List.of(nodeId), sample);
                inFlight += count;
                inFlightIds.addAll(ids);
                Step was = from.get(nodeId);
                conflicts.add(new MigrationDryRun.Conflict(MigrationDryRun.STEP_IN_FLIGHT, nodeId,
                        was == null ? null : was.name(), new MigrationDryRun.InstanceSample(count, ids,
                                count > ids.size())));
            }
        }
        long running = store.runningInstances(tenant, source.id());
        long blocked;
        MigrationDryRun.InstanceSample movable;
        long unmapped = 0;
        boolean targetActive = "ACTIVE".equals(target.status());
        if (!targetActive) {
            // A retired target takes no instance at all.
            conflicts.add(new MigrationDryRun.Conflict(MigrationDryRun.TARGET_NOT_ACTIVE, null, null,
                    new MigrationDryRun.InstanceSample(running, List.of(), running > 0)));
            blocked = running;
            unmapped = running;
            movable = new MigrationDryRun.InstanceSample(0, List.of(), false);
        } else {
            unmapped = blockedNodes.isEmpty() ? 0 : Math.min(running, store.instancesAt(tenant, source.id(),
                    blockedNodes));
            blocked = Math.min(running, unmapped + inFlight);
            long movableCount = running - blocked;
            List<String> ids = movableCount == 0 ? List.of()
                    : store.movableInstanceIds(tenant, source.id(), blockedNodes, sample + inFlightIds.size()).stream()
                            .filter(id -> !inFlightIds.contains(id)).limit(sample).toList();
            movable = new MigrationDryRun.InstanceSample(movableCount, ids, movableCount > ids.size());
        }
        conflicts.sort(Comparator.comparing(MigrationDryRun.Conflict::code)
                .thenComparing(c -> c.nodeId() == null ? "" : c.nodeId()));
        long mappedInstances = mappedNodes.isEmpty() ? 0 : store.instancesAt(tenant, source.id(), mappedNodes);
        long cancelledInstances = cancelNodes.isEmpty() ? 0
                : Math.max(0, store.instancesAt(tenant, source.id(), cancelNodes) - inFlight);
        boolean valid = conflicts.stream().allMatch(c -> MigrationDryRun.STEP_IN_FLIGHT.equals(c.code()));
        Instant checkedAt = clock.instant();
        return new MigrationDryRun(plan.id(), plan.planHash(), plan.definitionKey(), plan.sourceVersion(),
                plan.targetVersion(), valid, checkedAt, checkedAt.plus(settings.dryRunValidity()),
                plan.approval(), new MigrationDryRun.Instances(running, running - blocked, blocked), nodes, conflicts,
                movable, new MigrationDryRun.Impact(mappedInstances, cancelledInstances, inFlight, unmapped));
    }

    /** A step's readable name and canonical type. */
    record Step(String name, String type) {
    }

    static Map<String, Step> steps(Process process) {
        Map<String, String> names = new HashMap<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof FlowNode node) {
                names.put(node.id(), node.name());
            }
        }
        Map<String, Step> out = new HashMap<>();
        for (JsonNode step : CanonicalProcessWriter.write(process).get("steps")) {
            String id = step.get("id").asString();
            out.put(id, new Step(names.get(id), step.get("type").asString()));
        }
        return out;
    }

    /**
     * The key, both definition rows with their hashes and, when the plan has any, its step instructions: what an
     * execution is bound to (PD-114). A plan without instructions keeps the hash it had before #106.
     */
    private static String planHash(StoredDefinition source, StoredDefinition target, String instructions) {
        return instructions.isEmpty()
                ? Hashes.sha256Parts("migration-plan.v1", source.key(), source.id(), source.definitionHash(),
                        target.id(), target.definitionHash())
                : Hashes.sha256Parts("migration-plan.v2", source.key(), source.id(), source.definitionHash(),
                        target.id(), target.definitionHash(), instructions);
    }

    /** The instructions as one text in a fixed order, empty for none: what the hashes are taken over. */
    static String instructionsText(List<MigrationStepMapping> mappings, List<MigrationStepCancellation> cancellations) {
        if (mappings.isEmpty() && cancellations.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        mappings.stream().sorted(Comparator.comparing(MigrationStepMapping::from))
                .forEach(m -> out.append("map ").append(m.from()).append(' ').append(m.to()).append('\n'));
        cancellations.stream().sorted(Comparator.comparing(MigrationStepCancellation::step))
                .forEach(c -> out.append("cancel ").append(c.step()).append(' ').append(c.moveTo()).append(' ')
                        .append(c.reason().length()).append(' ').append(c.reason()).append('\n'));
        return out.toString();
    }

    /**
     * Refuses instructions that cannot be carried out, before anything is written (PRE-207, PRE-209): a mapping or a
     * cancellation of a step the source does not have, a mapping to a step of another type or one the target lacks, a
     * cancellation of a step the target still has, a step handled twice, and a cancellation without a reason or a step
     * to continue at.
     */
    private void checkInstructions(List<MigrationStepMapping> mappings, List<MigrationStepCancellation> cancellations,
            Map<String, Step> from, Map<String, Step> to) {
        if (mappings.size() > MAX_INSTRUCTIONS || cancellations.size() > MAX_INSTRUCTIONS) {
            throw new InputInvalidException("A plan has at most " + MAX_INSTRUCTIONS + " mappings and "
                    + MAX_INSTRUCTIONS + " cancellations");
        }
        List<String> problems = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        for (MigrationStepMapping m : mappings) {
            if (!STEP_ID.matcher(m.from()).matches() || !STEP_ID.matcher(m.to()).matches()) {
                problems.add("a mapping names a step id that is not valid");
                continue;
            }
            Step was = from.get(m.from());
            Step now = to.get(m.to());
            if (!handled.add(m.from())) {
                problems.add("step " + m.from() + " is handled twice");
            } else if (was == null) {
                problems.add("mapping " + m.from() + " -> " + m.to() + ": " + m.from() + " is not a step of the source"
                        + " version");
            } else if (now == null) {
                problems.add("mapping " + m.from() + " -> " + m.to() + ": " + m.to() + " is not a step of the target"
                        + " version");
            } else if (!Objects.equals(was.type(), now.type())) {
                problems.add("mapping " + m.from() + " -> " + m.to() + ": a " + was.type() + " step cannot become a "
                        + now.type() + " step");
            }
        }
        for (MigrationStepCancellation c : cancellations) {
            if (!STEP_ID.matcher(c.step()).matches() || c.moveTo().isBlank() || !STEP_ID.matcher(c.moveTo())
                    .matches()) {
                problems.add("cancellation of " + printable(c.step()) + " needs a step to continue at (moveTo)");
                continue;
            }
            if (!handled.add(c.step())) {
                problems.add("step " + c.step() + " is handled twice");
            } else if (!from.containsKey(c.step())) {
                problems.add("cancellation of " + c.step() + ": it is not a step of the source version");
            } else if (to.containsKey(c.step())) {
                problems.add("cancellation of " + c.step() + ": the target version still has it; map it or leave it");
            }
            if (!to.containsKey(c.moveTo())) {
                problems.add("cancellation of " + c.step() + ": moveTo " + c.moveTo() + " is not a step of the target"
                        + " version");
            }
            if (c.reason().isBlank() || c.reason().length() > settings.reasonMaxLength()) {
                problems.add("cancellation of " + c.step() + " needs a reason of 1 to " + settings.reasonMaxLength()
                        + " characters (lifecycle.reason-max-length)");
            }
        }
        if (!problems.isEmpty()) {
            throw new MigrationPlanInvalidException(problems.getFirst(), problems.stream().limit(20).toList());
        }
    }

    private static String printable(String name) {
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private StoredDefinition version(String tenant, String key, int version) {
        return definitions.definitionVersion(tenant, key, version).orElseThrow(() ->
                new DefinitionNotFoundException("No definition " + key + " v" + version + " in this tenant"));
    }

    StoredPlan stored(String tenant, String migrationId) {
        if (migrationId == null || !ID.matcher(migrationId).matches()) {
            throw new MigrationNotFoundException("No migration plan with that id in this tenant");
        }
        return store.findPlan(tenant, migrationId).orElseThrow(() ->
                new MigrationNotFoundException("No migration plan " + migrationId + " in this tenant"));
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

    private MigrationPlan idempotent(String tenant, String key, String requestHash, Supplier<MigrationPlan> action) {
        if (key == null) {
            return action.get();
        }
        if (key.isBlank() || key.length() > EngineDefaults.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InputInvalidException("Idempotency-Key must be 1 to " + EngineDefaults.MAX_IDEMPOTENCY_KEY_LENGTH
                    + " characters");
        }
        Instant now = clock.instant();
        // Claim first: a concurrent request with the same key blocks here until this transaction ends, then replays.
        if (definitions.claimIdempotency(tenant, "migration-plan", key, requestHash, now.plus(idempotencyRetention),
                now)) {
            MigrationPlan result = action.get();
            definitions.completeIdempotency(tenant, "migration-plan", key, Values.write(toMap(result)));
            return result;
        }
        IdempotencyRecord previous = definitions.idempotency(tenant, "migration-plan", key, now)
                .orElseThrow(() -> new IdempotencyInFlightException("A request with this Idempotency-Key is running"));
        if (!previous.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException("The Idempotency-Key was used with a different request");
        }
        if (previous.responseJson() == null) {
            throw new IdempotencyInFlightException("A request with this Idempotency-Key is running");
        }
        return fromMap(Values.readMap(previous.responseJson()));
    }

    private static Map<String, Object> toMap(MigrationPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("definitionKey", p.definitionKey());
        m.put("sourceVersion", p.sourceVersion());
        m.put("targetVersion", p.targetVersion());
        m.put("status", p.status());
        m.put("planHash", p.planHash());
        m.put("approval", p.approval().value());
        m.put("createdBy", p.createdBy());
        m.put("createdAt", p.createdAt().toString());
        m.put("mappings", p.mappings().stream().map(x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("from", x.from());
            o.put("to", x.to());
            return o;
        }).toList());
        m.put("cancellations", p.cancellations().stream().map(x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("step", x.step());
            o.put("moveTo", x.moveTo());
            o.put("reason", x.reason());
            return o;
        }).toList());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static MigrationPlan fromMap(Map<String, Object> m) {
        List<Map<String, Object>> maps = (List<Map<String, Object>>) m.getOrDefault("mappings", List.of());
        List<Map<String, Object>> cancels = (List<Map<String, Object>>) m.getOrDefault("cancellations", List.of());
        return new MigrationPlan((String) m.get("id"), (String) m.get("definitionKey"),
                ((Number) m.get("sourceVersion")).intValue(), ((Number) m.get("targetVersion")).intValue(),
                (String) m.get("status"), (String) m.get("planHash"), MigrationPlan.Approval.of((String) m.get(
                        "approval")), (String) m.get("createdBy"), Instant.parse((String) m.get("createdAt")),
                maps.stream().map(x -> new MigrationStepMapping((String) x.get("from"), (String) x.get("to")))
                        .toList(),
                cancels.stream().map(x -> new MigrationStepCancellation((String) x.get("step"),
                        (String) x.get("moveTo"), (String) x.get("reason"))).toList());
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    /** The caller, who holds at least one of the permissions; an engine identity never acts here. */
    private String caller(String... permissions) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (caller.startsWith(ProcessServices.SYSTEM_PREFIX)) {
            throw new PermissionDeniedException("Identities starting with " + ProcessServices.SYSTEM_PREFIX
                    + " are reserved for the engine");
        }
        Set<String> held = callers.currentPermissions();
        for (String p : permissions) {
            if (held.contains(p)) {
                return caller;
            }
        }
        throw new PermissionDeniedException("Needs " + String.join(" or ", permissions));
    }
}
