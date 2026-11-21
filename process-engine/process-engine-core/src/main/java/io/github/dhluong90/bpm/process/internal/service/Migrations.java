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
        String requestHash = Hashes.sha256Parts("migration-plan", caller, key,
                Integer.toString(request.sourceVersion()), Integer.toString(request.targetVersion()));
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
            models.apply(source);
            models.apply(target);
            Instant now = clock.instant();
            String id = Ulid.next(now);
            MigrationPlan plan = new MigrationPlan(id, key, source.version(), target.version(), PLANNED,
                    planHash(source, target), settings.approval(), caller, now);
            store.insertPlan(new StoredPlan(tenant, plan, source.id(), target.id()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sourceVersion", plan.sourceVersion());
            details.put("targetVersion", plan.targetVersion());
            details.put("planHash", plan.planHash());
            details.put("approval", plan.approval().value());
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

    private MigrationDryRun dryRun(String tenant, StoredPlan stored) {
        MigrationPlan plan = stored.view();
        StoredDefinition source = definitions.definition(tenant, stored.sourceDefinitionId()).orElseThrow();
        StoredDefinition target = definitions.definition(tenant, stored.targetDefinitionId()).orElseThrow();
        Map<String, Step> from = steps(models.apply(source));
        Map<String, Step> to = steps(models.apply(target));
        int sample = settings.dryRunSampleSize();

        List<MigrationDryRun.NodeMapping> nodes = new ArrayList<>();
        List<MigrationDryRun.Conflict> conflicts = new ArrayList<>();
        List<String> blockedNodes = new ArrayList<>();
        new TreeMap<>(store.liveTokensByNode(tenant, source.id())).forEach((nodeId, tokens) -> {
            Step was = from.get(nodeId);
            Step now = to.get(nodeId);
            String mapping = now == null ? MigrationDryRun.STEP_NOT_IN_TARGET
                    : was != null && !Objects.equals(was.type(), now.type()) ? MigrationDryRun.STEP_TYPE_CHANGED
                    : SAME_ID;
            nodes.add(new MigrationDryRun.NodeMapping(nodeId, was == null ? null : was.name(),
                    was == null ? null : was.type(), tokens, mapping, now == null ? null : nodeId,
                    now == null ? null : now.type()));
            if (!SAME_ID.equals(mapping)) {
                blockedNodes.add(nodeId);
                long count = store.instancesAt(tenant, source.id(), List.of(nodeId));
                List<String> ids = store.instanceIdsAt(tenant, source.id(), nodeId, sample);
                conflicts.add(new MigrationDryRun.Conflict(mapping, nodeId, was == null ? null : was.name(),
                        new MigrationDryRun.InstanceSample(count, ids, count > ids.size())));
            }
        });
        long running = store.runningInstances(tenant, source.id());
        long blocked;
        MigrationDryRun.InstanceSample movable;
        if (!"ACTIVE".equals(target.status())) {
            // A retired target takes no instance at all.
            conflicts.add(new MigrationDryRun.Conflict(MigrationDryRun.TARGET_NOT_ACTIVE, null, null,
                    new MigrationDryRun.InstanceSample(running, List.of(), running > 0)));
            blocked = running;
            movable = new MigrationDryRun.InstanceSample(0, List.of(), false);
        } else {
            blocked = blockedNodes.isEmpty() ? 0 : Math.min(running, store.instancesAt(tenant, source.id(),
                    blockedNodes));
            long movableCount = running - blocked;
            List<String> ids = movableCount == 0 ? List.of()
                    : store.movableInstanceIds(tenant, source.id(), blockedNodes, sample);
            movable = new MigrationDryRun.InstanceSample(movableCount, ids, movableCount > ids.size());
        }
        conflicts.sort(Comparator.comparing(MigrationDryRun.Conflict::code)
                .thenComparing(c -> c.nodeId() == null ? "" : c.nodeId()));
        Instant checkedAt = clock.instant();
        return new MigrationDryRun(plan.id(), plan.planHash(), plan.definitionKey(), plan.sourceVersion(),
                plan.targetVersion(), conflicts.isEmpty(), checkedAt, checkedAt.plus(settings.dryRunValidity()),
                plan.approval(), new MigrationDryRun.Instances(running, running - blocked, blocked), nodes, conflicts,
                movable);
    }

    /** A step's readable name and canonical type. */
    private record Step(String name, String type) {
    }

    private static Map<String, Step> steps(Process process) {
        Map<String, String> names = new HashMap<>();
        for (FlowElement element : process.flowElements()) {
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

    /** The key and both definition rows with their hashes: what an execution is bound to (PD-114). */
    private static String planHash(StoredDefinition source, StoredDefinition target) {
        return Hashes.sha256Parts("migration-plan.v1", source.key(), source.id(), source.definitionHash(), target.id(),
                target.definitionHash());
    }

    private StoredDefinition version(String tenant, String key, int version) {
        return definitions.definitionVersion(tenant, key, version).orElseThrow(() ->
                new DefinitionNotFoundException("No definition " + key + " v" + version + " in this tenant"));
    }

    private StoredPlan stored(String tenant, String migrationId) {
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
        return m;
    }

    private static MigrationPlan fromMap(Map<String, Object> m) {
        return new MigrationPlan((String) m.get("id"), (String) m.get("definitionKey"),
                ((Number) m.get("sourceVersion")).intValue(), ((Number) m.get("targetVersion")).intValue(),
                (String) m.get("status"), (String) m.get("planHash"), MigrationPlan.Approval.of((String) m.get(
                        "approval")), (String) m.get("createdBy"), Instant.parse((String) m.get("createdAt")));
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
