package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DryRunRequiredException;
import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationExecution;
import io.github.dhluong90.bpm.process.api.MigrationExecutionService;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.internal.exec.Ids;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.Execution;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.StoredPlan;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The commands that let a migration plan run (#90, PRE-015, PRE-190 to PRE-193): propose with a reason, approve or
 * reject as a second person (PD-114 as amended by PD-131). A proposal and an approval each run the dry run again on the
 * server, so the check is the engine's and not a client's claim. Each command writes the plan, one history row and
 * its event in one transaction. Only an approval makes a plan runnable; the {@link MigrationRunner} reads no other
 * state. The tenant and the caller come from the resolvers. Thread-safe.
 */
public final class MigrationExecutions implements MigrationExecutionService {

    private static final System.Logger LOG = System.getLogger(MigrationExecutions.class.getName());
    private static final Pattern ID = Pattern.compile("^[0-9A-Za-z]{1,26}$");
    private static final Set<String> OUTCOMES = Set.of("MIGRATED", "FAILED", "SKIPPED");

    static final String PROPOSED = "PROPOSED";
    static final String APPROVED = "APPROVED";
    static final String REJECTED = "REJECTED";

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final MigrationStore store;
    private final Migrations migrations;
    private final ProcessEventPublisher publisher;
    private final MigrationSettings settings;
    private final IdempotentCalls idempotency;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transactions on the host's transaction manager
     * @param definitions the definition store, also for idempotency records
     * @param store migration storage
     * @param migrations the plans, whose dry run a proposal and an approval repeat
     * @param publisher event publisher
     * @param settings migration settings
     * @param idempotencyRetention how long an idempotency record answers replays
     */
    public MigrationExecutions(TenantResolver tenants, CallerResolver callers, Clock clock,
            TransactionRunner transactions, ProcessStore definitions, MigrationStore store, Migrations migrations,
            ProcessEventPublisher publisher, MigrationSettings settings, Duration idempotencyRetention) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.store = Objects.requireNonNull(store, "store");
        this.migrations = Objects.requireNonNull(migrations, "migrations");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.idempotency = new IdempotentCalls(definitions, clock, Objects.requireNonNull(idempotencyRetention,
                "idempotencyRetention"));
    }

    @Override
    public MigrationExecution propose(String migrationId, String reason, String idempotencyKey) {
        String tenant = tenant();
        String caller = caller(Migrations.PERMISSION_MANAGE);
        String why = reason(reason);
        String requestHash = Hashes.sha256Parts("migration-propose", caller, String.valueOf(migrationId),
                Hashes.sha256(why));
        return transactions.inTransaction(() -> idempotency.run(tenant, "migration-propose", idempotencyKey,
                requestHash, () -> {
                    Execution e = locked(tenant, migrationId);
                    Instant now = clock.instant();
                    boolean refresh = PROPOSED.equals(e.status()) && !fresh(e.dryRunCheckedAt(), now);
                    if (!"PLANNED".equals(e.status()) && !REJECTED.equals(e.status()) && !refresh) {
                        throw new InvalidStateException("Migration " + migrationId + " is " + e.status()
                                + "; only a planned or rejected plan, or a proposal whose dry run has expired, is "
                                + "proposed");
                    }
                    StoredPlan plan = store.findPlan(tenant, migrationId).orElseThrow();
                    MigrationDryRun report = dryRun(tenant, plan);
                    boolean single = !e.approval().needsSecondPerson();
                    Execution proposed = new Execution(tenant, migrationId, single ? APPROVED : PROPOSED,
                            e.approval(), e.lockVersion(), caller, now, reason(reason), now, single ? caller : null,
                            single ? now : null, null, null, null, report.instances().running(), 0, 0, 0, e.cursor(),
                            null, null, null, null);
                    store.updateExecution(proposed);
                    long step = (e.lockVersion() + 1) * 10;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("reason", proposed.reason());
                    details.put("planHash", plan.view().planHash());
                    details.put("running", report.instances().running());
                    details.put("movable", report.instances().movable());
                    details.put("approval", e.approval().value());
                    store.insertHistory(tenant, Ids.child(migrationId, step), migrationId, PROPOSED, caller, details,
                            now);
                    publish(event(tenant, plan, "processes.migration.proposed.v1", Ids.child(migrationId, step + 1),
                            now, "proposedBy", caller, details));
                    if (single) {
                        // The dev profile only (PD-131): the proposer's approval is the proposal itself, recorded as such.
                        Map<String, Object> approved = new LinkedHashMap<>(details);
                        approved.put("proposedBy", caller);
                        approved.put("mode", "single");
                        store.insertHistory(tenant, Ids.child(migrationId, step + 2), migrationId, APPROVED, caller,
                                approved, now);
                        publish(event(tenant, plan, "processes.migration.approved.v1", Ids.child(migrationId, step + 3),
                                now, "approvedBy", caller, approved));
                    }
                    LOG.log(System.Logger.Level.INFO, "process.migration.proposed tenant={0} migration={1} by={2} "
                            + "running={3} mode={4}", tenant, migrationId, caller, report.instances().running(),
                            e.approval().value());
                    return view(store.findExecution(tenant, migrationId).orElseThrow());
                }, MigrationExecutions::toJson, MigrationExecutions::fromJson));
    }

    @Override
    public MigrationExecution approve(String migrationId, long expectedLockVersion, String idempotencyKey) {
        String tenant = tenant();
        String caller = caller(Migrations.PERMISSION_MANAGE);
        String requestHash = Hashes.sha256Parts("migration-approve", caller, String.valueOf(migrationId),
                Long.toString(expectedLockVersion));
        return transactions.inTransaction(() -> idempotency.run(tenant, "migration-approve", idempotencyKey,
                requestHash, () -> {
                    Execution e = locked(tenant, migrationId);
                    requireProposed(e);
                    secondPerson(e, caller);
                    requireLock(e, expectedLockVersion);
                    Instant now = clock.instant();
                    if (!fresh(e.dryRunCheckedAt(), now)) {
                        throw new DryRunRequiredException("The dry run of this proposal is older than "
                                + settings.dryRunValidity() + "; propose the execution again");
                    }
                    StoredPlan plan = store.findPlan(tenant, migrationId).orElseThrow();
                    MigrationDryRun report = dryRun(tenant, plan);
                    Execution approved = new Execution(tenant, migrationId, APPROVED, e.approval(), e.lockVersion(),
                            e.proposedBy(), e.proposedAt(), e.reason(), e.dryRunCheckedAt(), caller, now, null, null,
                            null, report.instances().running(), 0, 0, 0, e.cursor(), null, null, null, null);
                    store.updateExecution(approved);
                    long step = (e.lockVersion() + 1) * 10;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("proposedBy", e.proposedBy());
                    details.put("reason", e.reason());
                    details.put("planHash", plan.view().planHash());
                    details.put("running", report.instances().running());
                    details.put("mode", e.approval().value());
                    store.insertHistory(tenant, Ids.child(migrationId, step), migrationId, APPROVED, caller, details,
                            now);
                    publish(event(tenant, plan, "processes.migration.approved.v1", Ids.child(migrationId, step + 1),
                            now, "approvedBy", caller, details));
                    LOG.log(System.Logger.Level.INFO, "process.migration.approved tenant={0} migration={1} by={2} "
                            + "proposedBy={3} running={4}", tenant, migrationId, caller, e.proposedBy(),
                            report.instances().running());
                    return view(store.findExecution(tenant, migrationId).orElseThrow());
                }, MigrationExecutions::toJson, MigrationExecutions::fromJson));
    }

    @Override
    public MigrationExecution reject(String migrationId, long expectedLockVersion, String reason,
            String idempotencyKey) {
        String tenant = tenant();
        String caller = caller(Migrations.PERMISSION_MANAGE);
        String why = reason(reason);
        String requestHash = Hashes.sha256Parts("migration-reject", caller, String.valueOf(migrationId),
                Long.toString(expectedLockVersion), Hashes.sha256(why));
        return transactions.inTransaction(() -> idempotency.run(tenant, "migration-reject", idempotencyKey,
                requestHash, () -> {
                    Execution e = locked(tenant, migrationId);
                    requireProposed(e);
                    secondPerson(e, caller);
                    requireLock(e, expectedLockVersion);
                    Instant now = clock.instant();
                    StoredPlan plan = store.findPlan(tenant, migrationId).orElseThrow();
                    store.updateExecution(new Execution(tenant, migrationId, REJECTED, e.approval(), e.lockVersion(),
                            e.proposedBy(), e.proposedAt(), e.reason(), e.dryRunCheckedAt(), null, null, caller, now,
                            why, e.total(), 0, 0, 0, e.cursor(), null, null, null, null));
                    long step = (e.lockVersion() + 1) * 10;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("proposedBy", e.proposedBy());
                    details.put("proposalReason", e.reason());
                    details.put("reason", why);
                    store.insertHistory(tenant, Ids.child(migrationId, step), migrationId, REJECTED, caller, details,
                            now);
                    publish(event(tenant, plan, "processes.migration.rejected.v1", Ids.child(migrationId, step + 1),
                            now, "rejectedBy", caller, details));
                    return view(store.findExecution(tenant, migrationId).orElseThrow());
                }, MigrationExecutions::toJson, MigrationExecutions::fromJson));
    }

    @Override
    public MigrationExecution execution(String migrationId) {
        String tenant = tenant();
        caller(Migrations.PERMISSION_MANAGE, ProcessServices.PERMISSION_READ);
        return transactions.inTransaction(() -> view(found(tenant, migrationId)));
    }

    @Override
    public List<MigrationOutcome> outcomes(String migrationId, String outcome, String afterInstanceId, int limit) {
        String tenant = tenant();
        caller(Migrations.PERMISSION_MANAGE, ProcessServices.PERMISSION_READ);
        if (outcome != null && !OUTCOMES.contains(outcome)) {
            throw new InputInvalidException("outcome must be MIGRATED, FAILED or SKIPPED");
        }
        if (limit < 1 || limit > EngineDefaults.MAX_PAGE_SIZE) {
            throw new InputInvalidException("limit must be 1 to " + EngineDefaults.MAX_PAGE_SIZE);
        }
        if (afterInstanceId != null && !ID.matcher(afterInstanceId).matches()) {
            throw new InputInvalidException("after must be an instance id");
        }
        return transactions.inTransaction(() -> {
            found(tenant, migrationId);
            return store.outcomes(tenant, migrationId, outcome, afterInstanceId == null ? "" : afterInstanceId, limit);
        });
    }

    // ---- rules -----------------------------------------------------------------------------------------------

    /** The engine's own dry run of the plan, which must have no conflict (PD-114). */
    private MigrationDryRun dryRun(String tenant, StoredPlan plan) {
        MigrationDryRun report = migrations.dryRun(tenant, plan);
        if (!report.valid()) {
            throw new DryRunRequiredException("The dry run of this plan reports " + report.conflicts().size()
                    + " conflict(s), affecting " + report.instances().blocked() + " running instance(s); resolve "
                    + "them and run the dry run again");
        }
        return report;
    }

    private boolean fresh(Instant checkedAt, Instant now) {
        return checkedAt != null && !now.isAfter(checkedAt.plus(settings.dryRunValidity()));
    }

    private static void requireProposed(Execution e) {
        if (!PROPOSED.equals(e.status())) {
            throw new InvalidStateException("Migration " + e.migrationId() + " is " + e.status()
                    + "; only a proposed execution is approved or rejected");
        }
    }

    /** A four-eyes proposal is answered by another person (PD-131). */
    private static void secondPerson(Execution e, String caller) {
        if (e.approval().needsSecondPerson() && caller.equals(e.proposedBy())) {
            throw new SelfApprovalForbiddenException("The proposer of an execution cannot approve or reject it");
        }
    }

    private static void requireLock(Execution e, long expected) {
        if (e.lockVersion() != expected) {
            throw new VersionConflictException("The proposal is at lock version " + e.lockVersion()
                    + " but the request names " + expected);
        }
    }

    private String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InputInvalidException("A reason is required");
        }
        if (reason.length() > EngineDefaults.MAX_REASON_LENGTH) {
            throw new InputInvalidException("The reason is longer than " + EngineDefaults.MAX_REASON_LENGTH
                    + " characters");
        }
        return reason.strip();
    }

    // ---- plumbing --------------------------------------------------------------------------------------------

    private Execution locked(String tenant, String migrationId) {
        validId(migrationId);
        return store.lockExecution(tenant, migrationId).orElseThrow(() ->
                new MigrationNotFoundException("No migration plan " + migrationId + " in this tenant"));
    }

    private Execution found(String tenant, String migrationId) {
        validId(migrationId);
        return store.findExecution(tenant, migrationId).orElseThrow(() ->
                new MigrationNotFoundException("No migration plan " + migrationId + " in this tenant"));
    }

    private static void validId(String migrationId) {
        if (migrationId == null || !ID.matcher(migrationId).matches()) {
            throw new MigrationNotFoundException("No migration plan with that id in this tenant");
        }
    }

    private static ProcessEventPublisher.Event event(String tenant, StoredPlan plan, String type, String id,
            Instant at, String actorKey, String actor, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("migrationId", plan.view().id());
        payload.put("definitionKey", plan.view().definitionKey());
        payload.put("sourceVersion", plan.view().sourceVersion());
        payload.put("targetVersion", plan.view().targetVersion());
        payload.put(actorKey, actor);
        payload.putAll(details);
        return new ProcessEventPublisher.Event(id, type, tenant, at, payload);
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

    static MigrationExecution view(Execution e) {
        return new MigrationExecution(e.migrationId(), e.status(), e.approval(), e.lockVersion(), e.proposedBy(),
                e.proposedAt(), e.reason(), e.approvedBy(), e.approvedAt(), e.rejectedBy(), e.rejectedAt(),
                e.rejectionReason(), e.total(), e.migrated(), e.failed(), e.skipped(), e.startedAt(), e.finishedAt());
    }

    private static String toJson(MigrationExecution x) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("migrationId", x.migrationId());
        m.put("status", x.status());
        m.put("approval", x.approval().value());
        m.put("lockVersion", x.lockVersion());
        put(m, "proposedBy", x.proposedBy());
        put(m, "proposedAt", x.proposedAt());
        put(m, "reason", x.reason());
        put(m, "approvedBy", x.approvedBy());
        put(m, "approvedAt", x.approvedAt());
        put(m, "rejectedBy", x.rejectedBy());
        put(m, "rejectedAt", x.rejectedAt());
        put(m, "rejectionReason", x.rejectionReason());
        m.put("total", x.total());
        m.put("migrated", x.migrated());
        m.put("failed", x.failed());
        m.put("skipped", x.skipped());
        put(m, "startedAt", x.startedAt());
        put(m, "finishedAt", x.finishedAt());
        return Values.write(m);
    }

    private static void put(Map<String, Object> m, String name, Object value) {
        m.put(name, value == null ? null : value.toString());
    }

    private static MigrationExecution fromJson(String json) {
        Map<String, Object> m = Values.readMap(json);
        return new MigrationExecution((String) m.get("migrationId"), (String) m.get("status"),
                MigrationPlan.Approval.of((String) m.get("approval")), ((Number) m.get("lockVersion")).longValue(),
                (String) m.get("proposedBy"), instant(m, "proposedAt"), (String) m.get("reason"),
                (String) m.get("approvedBy"), instant(m, "approvedAt"), (String) m.get("rejectedBy"),
                instant(m, "rejectedAt"), (String) m.get("rejectionReason"), ((Number) m.get("total")).longValue(),
                ((Number) m.get("migrated")).longValue(), ((Number) m.get("failed")).longValue(),
                ((Number) m.get("skipped")).longValue(), instant(m, "startedAt"), instant(m, "finishedAt"));
    }

    private static Instant instant(Map<String, Object> m, String name) {
        Object v = m.get(name);
        return v == null ? null : Instant.parse((String) v);
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
