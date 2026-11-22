package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.MigrationStepCancellation;
import io.github.dhluong90.bpm.process.api.MigrationStepMapping;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.Ids;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.service.Migrations.Step;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.Execution;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.InstanceBusyException;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.LiveToken;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.LockedInstance;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore.StoredPlan;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.StoredDefinition;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Moves the instances of an approved migration plan in bounded batches (#90, PRE-015, PRE-195 to PRE-198, DESIGN
 * section 4.3).
 *
 * <ul>
 *   <li><b>Only approved plans.</b> The claim reads plans in {@code APPROVED} or {@code RUNNING}, which only an
 *       approval writes, and the table refuses such a status without an approver; the runner checks it again.</li>
 *   <li><b>One writer per plan.</b> A runner holds a lease on the plan row ({@code FOR UPDATE SKIP LOCKED} at the
 *       claim). Every instance's transaction extends the lease and counts the instance only while this runner still
 *       holds it; a runner that lost its lease rolls back and stops.</li>
 *   <li><b>Idempotent per instance.</b> One transaction locks the instance row without waiting, checks that every
 *       live token waits at a step the target keeps with the same type, repoints the instance and everything that
 *       carries its definition, appends {@code MIGRATED} to its history, records the outcome and passes the cursor.
 *       The outcome row's primary key makes a repeat a no-op.</li>
 *   <li><b>Failure isolation.</b> An instance that cannot move is rolled back alone, recorded {@code FAILED} with an
 *       incident, and the batch goes on.</li>
 *   <li><b>Resumable.</b> The cursor, the counts and the lease are committed with each instance, so a crashed runner's
 *       plan is taken over when its lease ends and continues after the last committed instance.</li>
 * </ul>
 *
 * <p>The tenant of a plan is read from the plan row, never from a request: this is an engine sweep like the job claim.
 * Thread-safe; two runners on two replicas never work on one plan.
 */
public final class MigrationRunner {

    /** The history actor of the runner's own plan-level rows. */
    static final String ENGINE_ACTOR = "system:migration-runner";
    private static final System.Logger LOG = System.getLogger(MigrationRunner.class.getName());
    private static final String MOVED = "MOVED";
    private static final long COMPLETED_STEP = 1_000_000L;

    /** A hook after each instance has been dealt with, for tests that stop the runner as a crash would. */
    @FunctionalInterface
    public interface Checkpoint {
        /** No hook. */
        Checkpoint NONE = (migrationId, instanceId, dealt) -> { };

        /**
         * @param migrationId the plan
         * @param instanceId the instance just dealt with, its transaction committed
         * @param dealt instances dealt with by this call so far
         */
        void afterInstance(String migrationId, String instanceId, int dealt);
    }

    private final String owner;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessStore definitions;
    private final MigrationStore store;
    private final Function<StoredDefinition, Process> models;
    private final ProcessServices services;
    private final ProcessEventPublisher publisher;
    private final MigrationSettings settings;
    private final Checkpoint checkpoint;

    /**
     * @param owner this replica's id, the lease holder
     * @param clock engine clock
     * @param transactions transactions on the host's transaction manager
     * @param definitions the definition store
     * @param store migration storage
     * @param models the hash-checked model of a stored version
     * @param services the engine, which opens the incident of an instance that cannot move
     * @param publisher event publisher
     * @param settings migration settings
     * @param checkpoint a hook after each instance; {@link Checkpoint#NONE} in production
     */
    public MigrationRunner(String owner, Clock clock, TransactionRunner transactions, ProcessStore definitions,
            MigrationStore store, Function<StoredDefinition, Process> models, ProcessServices services,
            ProcessEventPublisher publisher, MigrationSettings settings, Checkpoint checkpoint) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.store = Objects.requireNonNull(store, "store");
        this.models = Objects.requireNonNull(models, "models");
        this.services = Objects.requireNonNull(services, "services");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    /**
     * Takes one runnable plan and works on it for at most {@code maxBatches} batches, then gives the lease back so the
     * next poll, here or on another replica, takes it on. A call that throws leaves the lease held, as a crash would.
     *
     * @param maxBatches batches of {@code migration.batch-size} instances to run in this call, at least 1
     * @return the instances dealt with, 0 when no plan was runnable
     */
    public int runDue(int maxBatches) {
        if (maxBatches < 1) {
            throw new IllegalArgumentException("maxBatches must be 1 or more");
        }
        Instant start = clock.instant();
        Optional<Execution> claimed = transactions.inTransaction(() -> store.claimRunnable(owner, start,
                start.plus(settings.lease())));
        if (claimed.isEmpty()) {
            return 0;
        }
        Execution run = claimed.get();
        String tenant = run.tenantId();
        if (run.approvedBy() == null || run.approvedAt() == null || run.proposedBy() == null
                || run.approval().needsSecondPerson() && run.approvedBy().equals(run.proposedBy())) {
            // Never run a plan that was not approved by the rules of its approval; leave it for an operator.
            transactions.inTransaction(() -> {
                store.release(tenant, run.migrationId(), owner);
                return null;
            });
            LOG.log(System.Logger.Level.ERROR, "process.migration.unapproved tenant={0} migration={1} refused",
                    tenant, run.migrationId());
            throw new IllegalStateException("Migration " + run.migrationId() + " is " + run.status()
                    + " without a valid approval and was not run");
        }
        Plan plan = plan(tenant, run);
        String cursor = run.cursor();
        int dealt = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            String after = cursor;
            List<String> ids = transactions.inTransaction(() -> store.nextBatch(tenant, plan.source.id(), after,
                    settings.batchSize()));
            if (ids.isEmpty()) {
                if (reconcile(run, plan)) {
                    complete(run, plan);
                }
                return dealt;
            }
            for (String id : ids) {
                if (!dealWith(run, plan, id)) {
                    return dealt;
                }
                cursor = id;
                dealt++;
                checkpoint.afterInstance(run.migrationId(), id, dealt);
            }
        }
        transactions.inTransaction(() -> {
            store.release(tenant, run.migrationId(), owner);
            return null;
        });
        return dealt;
    }

    /** The versions of a plan and the steps of each, read once per claim. */
    private record Plan(StoredPlan stored, StoredDefinition source, StoredDefinition target, Map<String, Step> from,
            Map<String, Step> to, Map<String, MigrationStepMapping> mapped,
            Map<String, MigrationStepCancellation> cancelled) {
    }

    private Plan plan(String tenant, Execution run) {
        return transactions.inTransaction(() -> {
            StoredPlan stored = store.findPlan(tenant, run.migrationId()).orElseThrow();
            StoredDefinition source = definitions.definition(tenant, stored.sourceDefinitionId()).orElseThrow();
            StoredDefinition target = definitions.definition(tenant, stored.targetDefinitionId()).orElseThrow();
            Map<String, MigrationStepMapping> mapped = new java.util.HashMap<>();
            stored.view().mappings().forEach(m -> mapped.put(m.from(), m));
            Map<String, MigrationStepCancellation> cancelled = new java.util.HashMap<>();
            stored.view().cancellations().forEach(c -> cancelled.put(c.step(), c));
            return new Plan(stored, source, target, Migrations.steps(models.apply(source)),
                    Migrations.steps(models.apply(target)), mapped, cancelled);
        });
    }

    /** A step the target does not keep, or keeps as another type: the instance cannot move. */
    private static final class CannotMove extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String nodeId;
        private final String tokenId;

        CannotMove(String code, String nodeId, String tokenId) {
            super(code + " at " + nodeId, null, false, false);
            this.code = code;
            this.nodeId = nodeId;
            this.tokenId = tokenId;
        }
    }

    /** This runner no longer holds the plan: the transaction is rolled back and the run stops. */
    private static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeaseLost() {
            super("The lease on the migration was lost", null, false, false);
        }
    }

    /** @return false when the lease was lost and the run must stop */
    private boolean dealWith(Execution run, Plan plan, String instanceId) {
        String tenant = run.tenantId();
        try {
            transactions.inTransaction(() -> {
                move(tenant, run, plan, instanceId);
                return null;
            });
            return true;
        } catch (LeaseLost e) {
            LOG.log(System.Logger.Level.WARNING, "process.migration.lease-lost tenant={0} migration={1} owner={2}",
                    tenant, run.migrationId(), owner);
            return false;
        } catch (CannotMove e) {
            return fail(tenant, run, plan, instanceId, e.code, e.nodeId, e.tokenId);
        } catch (InstanceBusyException e) {
            return fail(tenant, run, plan, instanceId, "LOCK_CONFLICT", null, null);
        } catch (io.github.dhluong90.bpm.process.api.ProcessException e) {
            // A step that cannot be entered on arrival (#106): this instance rolls back alone and stays on the source.
            return fail(tenant, run, plan, instanceId, e.code(), null, null);
        }
    }

    private void move(String tenant, Execution run, Plan plan, String instanceId) {
        Instant now = clock.instant();
        Instant lease = now.plus(settings.lease());
        Optional<LockedInstance> locked = store.lockInstance(tenant, instanceId);
        if (locked.isEmpty()) {
            skip(tenant, run, instanceId, "INSTANCE_GONE", "The instance no longer exists", now, lease);
            return;
        }
        if (store.hasOutcome(tenant, run.migrationId(), instanceId)) {
            // A repeat after a crash between the commit and the cursor cannot happen (one transaction), but a plan
            // read twice must still move nothing twice.
            advance(tenant, run, instanceId, now, lease, 0, 0, 0);
            return;
        }
        if (!"RUNNING".equals(locked.get().status())) {
            skip(tenant, run, instanceId, "NOT_RUNNING", "The instance is " + locked.get().status(), now, lease);
            return;
        }
        if (!plan.source.id().equals(locked.get().definitionId())) {
            skip(tenant, run, instanceId, "NOT_ON_SOURCE", "The instance no longer runs on the source version", now,
                    lease);
            return;
        }
        List<LiveToken> mappedTokens = new java.util.ArrayList<>();
        List<LiveToken> cancelledTokens = new java.util.ArrayList<>();
        boolean inFlight = false;
        for (LiveToken token : store.liveTokens(tenant, instanceId)) {
            MigrationStepMapping map = plan.mapped.get(token.nodeId());
            MigrationStepCancellation cancel = map == null ? plan.cancelled.get(token.nodeId()) : null;
            Step was = plan.from.get(token.nodeId());
            Step now2 = plan.to.get(map != null ? map.to() : cancel != null ? cancel.moveTo() : token.nodeId());
            if (now2 == null) {
                throw new CannotMove(MigrationDryRun.STEP_NOT_IN_TARGET, token.nodeId(), token.id());
            }
            if (cancel == null && was != null && !Objects.equals(was.type(), now2.type())) {
                throw new CannotMove(MigrationDryRun.STEP_TYPE_CHANGED, token.nodeId(), token.id());
            }
            if (map != null) {
                mappedTokens.add(token);
            } else if (cancel != null) {
                cancelledTokens.add(token);
                // PRE-208: a call in flight is never cut off; the instance is left on the source version.
                inFlight |= store.callInFlight(tenant, instanceId, token.id());
            }
        }
        if (inFlight) {
            skip(tenant, run, instanceId, MigrationDryRun.STEP_IN_FLIGHT, "A call is in flight at a removed step; the "
                    + "instance stays on v" + plan.source.version() + " for a later run", now, lease);
            return;
        }
        store.moveInstance(tenant, instanceId, plan.target.id(), plan.target.version());
        List<String> applied = new java.util.ArrayList<>();
        List<HistoryEntry> entries = new java.util.ArrayList<>();
        for (LiveToken token : mappedTokens) {
            MigrationStepMapping map = plan.mapped.get(token.nodeId());
            store.renameStep(tenant, instanceId, token.id(), token.nodeId(), map.to());
            applied.add(token.nodeId() + " -> " + map.to());
        }
        for (LiveToken token : cancelledTokens) {
            MigrationStepCancellation cancel = plan.cancelled.get(token.nodeId());
            MigrationStore.CancelledWork work = store.cancelOpenWork(tenant, instanceId, token.id(), now);
            Map<String, Object> cancelDetails = new LinkedHashMap<>();
            cancelDetails.put("migrationId", run.migrationId());
            cancelDetails.put("step", cancel.step());
            cancelDetails.put("moveTo", cancel.moveTo());
            // The reason is the plan's; the cancelled task's context is never copied here.
            cancelDetails.put("reason", cancel.reason());
            if (work.taskId() != null) {
                cancelDetails.put("taskId", work.taskId());
                entries.add(new HistoryEntry("TASK_CANCELLED", cancel.step(), token.id(), work.taskId(),
                        run.approvedBy(), now, cancelDetails));
            }
            for (String jobId : work.jobIds()) {
                Map<String, Object> jobDetails = new LinkedHashMap<>(cancelDetails);
                jobDetails.put("jobId", jobId);
                entries.add(new HistoryEntry("JOB_CANCELLED", cancel.step(), token.id(), jobId, run.approvedBy(), now,
                        jobDetails));
            }
            applied.add(cancel.step() + " -> " + cancel.moveTo() + " (cancelled)");
        }
        if (!entries.isEmpty()) {
            definitions.appendHistory(tenant, instanceId, plan.target.id(), entries);
        }
        for (LiveToken token : cancelledTokens) {
            // The token enters the named step as on arrival: a task opens, a job parks, a local step runs.
            services.enterStepAfterMigration(tenant, instanceId, token.id(), plan.cancelled.get(token.nodeId()).moveTo(),
                    ENGINE_ACTOR);
        }
        String detail = "v" + plan.source.version() + " -> v" + plan.target.version()
                + (applied.isEmpty() ? "" : "; " + String.join("; ", applied));
        if (detail.length() > 500) {
            detail = detail.substring(0, 500);
        }
        store.insertOutcome(tenant, run.migrationId(), new MigrationOutcome(instanceId, "MIGRATED", MOVED, detail, null,
                now));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("migrationId", run.migrationId());
        details.put("fromVersion", plan.source.version());
        details.put("toVersion", plan.target.version());
        details.put("fromDefinitionId", plan.source.id());
        details.put("proposedBy", run.proposedBy());
        details.put("approvedBy", run.approvedBy());
        details.put("reason", run.reason());
        if (!applied.isEmpty()) {
            details.put("steps", applied);
        }
        definitions.appendHistory(tenant, instanceId, plan.target.id(), List.of(new HistoryEntry("MIGRATED", null, null,
                null, run.approvedBy(), now, details)));
        advance(tenant, run, instanceId, now, lease, 1, 0, 0);
    }

    private void skip(String tenant, Execution run, String instanceId, String code, String detail, Instant now,
            Instant lease) {
        store.insertOutcome(tenant, run.migrationId(), new MigrationOutcome(instanceId, "SKIPPED", code, detail, null,
                now));
        advance(tenant, run, instanceId, now, lease, 0, 0, 1);
    }

    private void advance(String tenant, Execution run, String instanceId, Instant now, Instant lease, int migrated,
            int failed, int skipped) {
        if (!store.advance(tenant, run.migrationId(), owner, now, lease, instanceId, migrated, failed, skipped)) {
            throw new LeaseLost();
        }
    }

    /**
     * Records the failure of one instance in its own transaction; the others go on. A step the target lacks opens its
     * incident at once. A lock conflict records the failure and leaves the incident to {@link #reconcile}, because
     * opening one needs the very lock another transaction holds, and the runner must not wait for it here.
     */
    private boolean fail(String tenant, Execution run, Plan plan, String instanceId, String code, String nodeId,
            String tokenId) {
        try {
            transactions.inTransaction(() -> {
                Instant now = clock.instant();
                int failed = 0;
                if (!store.hasOutcome(tenant, run.migrationId(), instanceId)) {
                    String incidentId = nodeId == null ? null
                            : openIncident(tenant, run, plan, instanceId, code, nodeId, tokenId, now);
                    String summary = "Migration " + run.migrationId() + " could not move the instance to v"
                            + plan.target.version() + ": " + code + (nodeId == null ? "" : " at " + nodeId)
                            + "; it stays on v" + plan.source.version();
                    store.insertOutcome(tenant, run.migrationId(), new MigrationOutcome(instanceId, "FAILED", code,
                            summary, incidentId, now));
                    failed = 1;
                    LOG.log(System.Logger.Level.WARNING, "process.migration.instance-failed tenant={0} migration={1} "
                            + "instance={2} code={3}", tenant, run.migrationId(), instanceId, code);
                }
                advance(tenant, run, instanceId, now, now.plus(settings.lease()), 0, failed, 0);
                return null;
            });
            return true;
        } catch (LeaseLost e) {
            return false;
        }
    }

    private String openIncident(String tenant, Execution run, Plan plan, String instanceId, String code, String nodeId,
            String tokenId, Instant now) {
        String node = nodeId;
        String token = tokenId;
        if (node == null) {
            List<LiveToken> live = store.liveTokens(tenant, instanceId);
            node = live.isEmpty() ? plan.stored.view().definitionKey() : live.getFirst().nodeId();
            token = live.isEmpty() ? null : live.getFirst().id();
        }
        String summary = "Migration " + run.migrationId() + " could not move the instance to v" + plan.target.version()
                + ": " + code + " at " + node + "; it stays on v" + plan.source.version();
        return services.raiseMigrationIncident(tenant, instanceId, plan.source.id(), token, node, "MIGRATION_FAILED",
                summary, now);
    }

    /**
     * Opens the incidents of the failures that have none, before the plan is completed, so a completed plan has an
     * incident for every failure. Each waits at most {@code migration.lock-wait} for the instance row; a failure that
     * cannot get it stops this call, the plan stays {@code RUNNING} and the next poll tries again.
     *
     * @return false when this runner lost the lease
     */
    private boolean reconcile(Execution run, Plan plan) {
        String tenant = run.tenantId();
        String after = "";
        while (true) {
            String from = after;
            List<MigrationOutcome> pending = transactions.inTransaction(() -> store.failuresWithoutIncident(tenant,
                    run.migrationId(), from, settings.batchSize()));
            if (pending.isEmpty()) {
                return true;
            }
            for (MigrationOutcome failure : pending) {
                boolean held = transactions.inTransaction(() -> {
                    Instant now = clock.instant();
                    store.limitLockWait(settings.lockWait());
                    String incidentId = openIncident(tenant, run, plan, failure.instanceId(), failure.code(), null,
                            null, now);
                    if (incidentId != null) {
                        store.setIncident(tenant, run.migrationId(), failure.instanceId(), incidentId);
                    }
                    return store.renewLease(tenant, run.migrationId(), owner, now, now.plus(settings.lease()));
                });
                if (!held) {
                    return false;
                }
                after = failure.instanceId();
            }
        }
    }

    private void complete(Execution run, Plan plan) {
        String tenant = run.tenantId();
        transactions.inTransaction(() -> {
            Execution now = store.findExecution(tenant, run.migrationId()).orElseThrow();
            Instant at = clock.instant();
            String status = now.failed() == 0 ? "COMPLETED" : "COMPLETED_WITH_FAILURES";
            if (!store.finish(tenant, run.migrationId(), owner, status, at)) {
                return null;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", status);
            details.put("migrated", now.migrated());
            details.put("failed", now.failed());
            details.put("skipped", now.skipped());
            details.put("proposedBy", run.proposedBy());
            details.put("approvedBy", run.approvedBy());
            store.insertHistory(tenant, Ids.child(run.migrationId(), COMPLETED_STEP), run.migrationId(), "COMPLETED",
                    ENGINE_ACTOR, details, at);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("migrationId", run.migrationId());
            payload.put("definitionKey", plan.stored.view().definitionKey());
            payload.put("sourceVersion", plan.source.version());
            payload.put("targetVersion", plan.target.version());
            payload.putAll(details);
            try {
                publisher.publish(new ProcessEventPublisher.Event(Ids.child(run.migrationId(), COMPLETED_STEP + 1),
                        "processes.migration.completed.v1", tenant, at, payload));
            } catch (RuntimeException e) {
                throw new EventListenerFailedException("A listener of processes.migration.completed.v1 failed; the "
                        + "change was rolled back");
            }
            LOG.log(System.Logger.Level.INFO, "process.migration.completed tenant={0} migration={1} status={2} "
                    + "migrated={3} failed={4} skipped={5}", tenant, run.migrationId(), status, now.migrated(),
                    now.failed(), now.skipped());
            return null;
        });
    }
}
