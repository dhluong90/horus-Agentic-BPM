package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.process.api.DefinitionIntegrityException;
import io.github.dhluong90.bpm.process.api.TenantNotAllowedException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.VersionSupersededException;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.TaskNotAssignedException;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;
import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.InstanceState;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.ServiceKind;
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.json.Utf8;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.IdempotencyRecord;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.InstanceRow;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.StoredDefinition;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.TaskRow;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The engine services and the persistence shell around the {@link ExecutionKernel}: resolve tenant and caller,
 * check the permission, lock the instance row, apply the command, apply the effects in the same transaction, and
 * store state and history. {@code SYNC_LOCAL} invocations run in the transaction; {@code ASYNC} is not in this
 * release. Thread-safe.
 */
public final class ProcessServices implements DefinitionService, ProcessEngine, TaskService {

    /**
     * The result of a start: the instance, and whether this request created it ({@code false} when an instance with
     * the business key already existed, PRE-002).
     *
     * @param instance the instance
     * @param created whether the request created it
     */
    public record Started(ProcessInstance instance, boolean created) {
    }

    static final String PERMISSION_DEPLOY = "processes:deploy";
    static final String PERMISSION_START = "processes:start";
    static final String PERMISSION_READ = "processes:read";
    static final String PERMISSION_TASK_WORK = "processes:task:work";
    static final String PERMISSION_AUTHOR = "processes:author";
    static final String PERMISSION_APPROVE = "processes:approve";
    static final String DEV_APPROVER = "system:dev-auto-approve";
    /** Actor of a retirement made because a newer version took effect. */
    static final String EFFECTIVE_DATE_ACTOR = "system:effective-date";
    /** Version of the BPMN reader whose output an approval binds (DESIGN section 5.8). */
    static final String READER_VERSION = "bpmn-subset-1";
    private static final System.Logger LOG = System.getLogger(ProcessServices.class.getName());

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessStore store;
    private final Map<SourceFormat, Function<byte[], Process>> readers;
    private final LocalServiceInvoker invoker;
    private final ProcessEventPublisher publisher;
    private final EngineSettings settings;
    private final BehaviorRegistry behaviors;
    private final ExpressionEvaluator expressions;
    private final ProcessCompiler compiler;
    private final JobStore jobs;
    private final JobSettings jobSettings;
    private final Map<String, Compiled> kernels = new ConcurrentHashMap<>();

    private record Compiled(CompiledProcess process, ExecutionKernel kernel) {
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions) {
        this(tenants, callers, clock, transactions, store, readers, invoker, publisher, settings, behaviors, expressions,
                JobStore.NONE, JobSettings.defaults("unused"));
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings) {
        this.jobs = Objects.requireNonNull(jobs);
        this.jobSettings = Objects.requireNonNull(jobSettings);
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.readers = Map.copyOf(readers);
        this.invoker = Objects.requireNonNull(invoker);
        this.publisher = Objects.requireNonNull(publisher);
        this.settings = Objects.requireNonNull(settings);
        this.behaviors = Objects.requireNonNull(behaviors);
        this.expressions = Objects.requireNonNull(expressions);
        this.compiler = new ProcessCompiler(behaviors, settings.maxNodes());
    }

    // ---- DefinitionService --------------------------------------------------------------------------------

    @Override
    public DefinitionVersion importDefinition(ImportRequest request) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_DEPLOY);
        return importAs(tenant, caller, request, "IMPORT");
    }

    /**
     * Imports for a tenant without a security context: the start-up import of seed definitions. Engine-internal;
     * never reachable from a request.
     *
     * @param tenant the tenant
     * @param request the document
     * @return the stored version
     */
    public DefinitionVersion importAtStartup(String tenant, ImportRequest request) {
        return importAs(tenant, "system:startup", request, "STARTUP");
    }

    private DefinitionVersion importAs(String tenant, String caller, ImportRequest request, String source) {
        byte[] bytes = request.source();
        String text = Utf8.strict(bytes);
        Function<byte[], Process> reader = readers.get(request.format());
        if (reader == null) {
            throw new InputInvalidException("Source format " + request.format() + " is not supported");
        }
        // Parse, validate and hash before any transaction opens (DESIGN section 4).
        Process process = reader.apply(bytes);
        compiler.compile("validation", process);
        checkBindings(process);
        String canonical = CanonicalProcessWriter.canonicalText(process);
        String hash = Hashes.sha256(canonical);
        int nodeCount = (int) process.flowElements().stream().filter(FlowNode.class::isInstance).count();
        String requestHash = Hashes.sha256Parts("import", caller, request.format().name(), Hashes.sha256(bytes));
        return transactions.inTransaction(() -> idempotent(tenant, "import", request.idempotencyKey(), requestHash,
                () -> {
                    // Locks the key, so concurrent imports and lifecycle commands of one key serialise.
                    List<StoredDefinition> versions = store.lockDefinitions(tenant, process.id());
                    StoredDefinition latest = versions.isEmpty() ? null : versions.getLast();
                    if (latest != null && latest.definitionHash().equals(hash)) {
                        return view(latest);
                    }
                    Instant now = clock.instant();
                    boolean active = settings.definitionsAutoApprove();
                    StoredDefinition stored = new StoredDefinition(tenant, Ulid.next(now), process.id(),
                            latest == null ? 1 : latest.version() + 1, active ? "ACTIVE" : "DRAFT", process.name(),
                            canonical, hash, request.format().name(), text,
                            Hashes.sha256(text), nodeCount, source, active ? now : null, caller, now,
                            active ? DEV_APPROVER : null, active ? now : null);
                    store.insertDefinition(stored);
                    lifecycleHistory(stored, "IMPORTED", null, caller, null, Map.of("source", source), now);
                    // One event per stored version, so its id is derived from the version id.
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("definitionKey", stored.key());
                    payload.put("version", stored.version());
                    payload.put("definitionHash", hash);
                    if (active) {
                        payload.put("approvedBy", DEV_APPROVER);
                        payload.put("approvedAt", now.toString());
                        payload.put("effectiveFrom", now.toString());
                        payload.put("sourceHash", stored.sourceHash());
                        payload.put("readerVersion", READER_VERSION);
                        payload.put("source", source);
                    }
                    publishDefinition(stored, active ? "processes.definition.approved.v1"
                            : "processes.definition.imported.v1", payload, now);
                    if (active) {
                        // PRE-022 (c): every auto-approved import is named in the log, with tenant and key.
                        LOG.log(System.Logger.Level.WARNING, "process.definition.auto-approved tenant={0} key={1} "
                                + "version={2} (definitions.auto-approve, dev profile)", tenant, stored.key(),
                                stored.version());
                        retireSuperseded(versions, stored, now);
                    }
                    return view(stored);
                }, Views::definitionToJson, Views::definitionFromJson));
    }

    // ---- definition lifecycle (ADR-0012, DESIGN section 3.2) -------------------------------------------------

    @Override
    public DefinitionVersion requestApproval(DefinitionRef ref, long expectedLockVersion, Instant effectiveFrom,
            String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWithAny(PERMISSION_AUTHOR, PERMISSION_DEPLOY);
        String requestHash = Hashes.sha256Parts("request-approval", caller, ref.key(), Integer.toString(ref.version()),
                Long.toString(expectedLockVersion), String.valueOf(effectiveFrom));
        return transactions.inTransaction(() -> idempotent(tenant, "request-approval", idempotencyKey, requestHash, () -> {
            Locked locked = lockVersion(tenant, ref);
            StoredDefinition current = locked.version();
            requireStatus(current, "DRAFT", "request approval of");
            requireLock(current, expectedLockVersion);
            Instant now = clock.instant();
            if (effectiveFrom != null && effectiveFrom.isBefore(now)) {
                throw new InputInvalidException("effectiveFrom must not be in the past");
            }
            StoredDefinition updated = current.requested(caller, now, effectiveFrom);
            write(updated);
            lifecycleHistory(updated, "REQUESTED", current.status(), caller, null, Map.of(), now);
            Map<String, Object> payload = definitionPayload(updated);
            payload.put("requestedBy", caller);
            payload.put("effectiveFrom", effectiveFrom == null ? null : effectiveFrom.toString());
            payload.put("source", updated.source());
            publishDefinition(updated, "processes.definition.publish-requested.v1", payload, now);
            return view(updated);
        }, Views::definitionToJson, Views::definitionFromJson));
    }

    @Override
    public DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String comment, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_APPROVE);
        String note = boundedComment(comment, false);
        String requestHash = Hashes.sha256Parts("approve", caller, ref.key(), Integer.toString(ref.version()),
                Long.toString(expectedLockVersion), note);
        return transactions.inTransaction(() -> idempotent(tenant, "approve", idempotencyKey, requestHash, () -> {
            Locked locked = lockVersion(tenant, ref);
            StoredDefinition current = locked.version();
            requireStatus(current, "PENDING_APPROVAL", "approve");
            // Four eyes (PRE-019, PD-096): checked here, so the Java API and REST refuse alike, before anything is
            // written. The requester never approves; under the default rule neither does any other maker (the
            // importer or creator; editors join when draft editing exists).
            boolean self = caller.equals(current.requestedBy())
                    || (settings.approverRule() == EngineSettings.ApproverRule.MAKERS && caller.equals(current.createdBy()));
            if (self && settings.makerChecker()) {
                throw new SelfApprovalForbiddenException(caller.equals(current.requestedBy())
                        ? "The requester of " + ref.key() + " v" + ref.version() + " cannot approve it"
                        : "A maker of " + ref.key() + " v" + ref.version() + " (its importer) cannot approve it "
                                + "(lifecycle.approver-must-differ-from: makers)");
            }
            requireLock(current, expectedLockVersion);
            // An older version never overtakes a newer active one (PRE-021).
            for (StoredDefinition other : locked.all()) {
                if ("ACTIVE".equals(other.status()) && other.version() > current.version()) {
                    throw new VersionSupersededException(ref.key() + " v" + other.version() + " is active; v"
                            + ref.version() + " cannot be approved over it");
                }
            }
            Instant now = clock.instant();
            Instant effective = current.effectiveFrom() == null || current.effectiveFrom().isBefore(now) ? now
                    : current.effectiveFrom();
            // With maker-checker off (dev profile only) a self-approval is recorded as the system approver, so the
            // table's four-eyes CHECK still holds; the history row names the person.
            String approver = self ? DEV_APPROVER : caller;
            StoredDefinition updated = current.approved(approver, now, effective, note);
            write(updated);
            lifecycleHistory(updated, "APPROVED", current.status(), caller, note,
                    Map.of("sourceHash", updated.sourceHash(), "readerVersion", READER_VERSION,
                            "effectiveFrom", effective.toString(),
                            "approverMustDifferFrom", settings.approverRule().value()), now);
            Map<String, Object> payload = definitionPayload(updated);
            payload.put("requestedBy", updated.requestedBy());
            payload.put("effectiveFrom", effective.toString());
            payload.put("source", updated.source());
            payload.put("approvedBy", approver);
            payload.put("approvedAt", now.toString());
            payload.put("sourceHash", updated.sourceHash());
            payload.put("readerVersion", READER_VERSION);
            publishDefinition(updated, "processes.definition.approved.v1", payload, now);
            if (!effective.isAfter(now)) {
                retireSuperseded(locked.all(), updated, now);
            } else {
                // PRE-020: the older version stays effective until then; a replica-safe job retires it once.
                jobs.insertJob(JobRow.due(tenant, Ulid.next(now), null, updated.id(), null, JobStore.DEFINITION_EFFECTIVE,
                        null, effective, jobSettings.timerLease(), jobSettings.maxAttempts(),
                        Values.write(Map.of("definitionKey", updated.key(), "version", updated.version()))), now);
            }
            return view(updated);
        }, Views::definitionToJson, Views::definitionFromJson));
    }

    @Override
    public DefinitionVersion reject(DefinitionRef ref, long expectedLockVersion, String reason, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_APPROVE);
        String why = boundedComment(reason, true);
        String requestHash = Hashes.sha256Parts("reject", caller, ref.key(), Integer.toString(ref.version()),
                Long.toString(expectedLockVersion), why);
        return transactions.inTransaction(() -> idempotent(tenant, "reject", idempotencyKey, requestHash, () -> {
            StoredDefinition current = lockVersion(tenant, ref).version();
            requireStatus(current, "PENDING_APPROVAL", "reject");
            // PD-097: as in the Rule Engine, "an approver refused it" is never the requester taking it back.
            if (caller.equals(current.requestedBy())) {
                throw new SelfApprovalForbiddenException("The requester of " + ref.key() + " v" + ref.version()
                        + " cannot reject their own request; withdraw it instead (withdraw is planned; until then "
                        + "ask an approver to reject it)");
            }
            requireLock(current, expectedLockVersion);
            Instant now = clock.instant();
            StoredDefinition updated = current.rejected(why);
            write(updated);
            lifecycleHistory(updated, "REJECTED", current.status(), caller, why, Map.of(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("definitionKey", updated.key());
            payload.put("version", updated.version());
            payload.put("rejectedBy", caller);
            payload.put("reason", why);
            publishDefinition(updated, "processes.definition.rejected.v1", payload, now);
            return view(updated);
        }, Views::definitionToJson, Views::definitionFromJson));
    }

    @Override
    public DefinitionVersion retire(DefinitionRef ref, long expectedLockVersion, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWithAny(PERMISSION_APPROVE, PERMISSION_AUTHOR);
        String requestHash = Hashes.sha256Parts("retire", caller, ref.key(), Integer.toString(ref.version()),
                Long.toString(expectedLockVersion));
        return transactions.inTransaction(() -> idempotent(tenant, "retire", idempotencyKey, requestHash, () -> {
            StoredDefinition current = lockVersion(tenant, ref).version();
            String needed = switch (current.status()) {
                case "ACTIVE" -> PERMISSION_APPROVE;
                case "DRAFT" -> PERMISSION_AUTHOR;
                default -> throw new InvalidStateException(ref.key() + " v" + ref.version() + " is " + current.status()
                        + "; only an active version or a draft can be retired");
            };
            callerWith(needed);
            requireLock(current, expectedLockVersion);
            Instant now = clock.instant();
            StoredDefinition updated = current.retired(caller, now, null);
            write(updated);
            lifecycleHistory(updated, "RETIRED", current.status(), caller, null, Map.of(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("definitionKey", updated.key());
            payload.put("version", updated.version());
            payload.put("retiredBy", caller);
            publishDefinition(updated, "processes.definition.retired.v1", payload, now);
            return view(updated);
        }, Views::definitionToJson, Views::definitionFromJson));
    }

    @Override
    public java.util.Optional<DefinitionVersion> find(DefinitionRef ref) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        return transactions.inTransaction(() -> versionOf(tenant, ref).map(ProcessServices::view));
    }

    @Override
    public List<DefinitionHistoryEntry> history(DefinitionRef ref) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        return transactions.inTransaction(() -> {
            StoredDefinition version = versionOf(tenant, ref).orElseThrow(() -> notFound(ref));
            return store.definitionHistory(tenant, version.id()).stream()
                    .map(h -> new DefinitionHistoryEntry(h.action(), h.fromStatus(), h.toStatus(), h.actorId(),
                            h.comment(), h.occurredAt()))
                    .toList();
        });
    }

    /** The version and every version of its key, all locked. */
    private record Locked(StoredDefinition version, List<StoredDefinition> all) {
    }

    private Locked lockVersion(String tenant, DefinitionRef ref) {
        List<StoredDefinition> all = store.lockDefinitions(tenant, ref.key());
        StoredDefinition version = all.stream().filter(d -> d.version() == ref.version()).findFirst()
                .orElseThrow(() -> notFound(ref));
        return new Locked(version, all);
    }

    private java.util.Optional<StoredDefinition> versionOf(String tenant, DefinitionRef ref) {
        return store.definitionByVersion(tenant, ref.key(), ref.version());
    }

    private static DefinitionNotFoundException notFound(DefinitionRef ref) {
        return new DefinitionNotFoundException("No version " + ref.version() + " of " + ref.key());
    }

    private static void requireStatus(StoredDefinition d, String expected, String action) {
        if (!expected.equals(d.status())) {
            throw new InvalidStateException("Cannot " + action + " " + d.key() + " v" + d.version() + ": it is "
                    + d.status());
        }
    }

    private static void requireLock(StoredDefinition d, long expected) {
        if (d.lockVersion() != expected) {
            throw new VersionConflictException(d.key() + " v" + d.version() + " is at lock version " + d.lockVersion());
        }
    }

    private void write(StoredDefinition updated) {
        if (store.updateDefinition(updated) != 1) {
            throw new VersionConflictException(updated.key() + " v" + updated.version() + " changed concurrently");
        }
    }

    private String boundedComment(String text, boolean required) {
        if (text == null || text.isBlank()) {
            if (required) {
                throw new InputInvalidException("reason is required and must not be blank");
            }
            return null;
        }
        if (text.length() > settings.reasonMaxLength()) {
            throw new InputInvalidException((required ? "reason" : "comment") + " must be at most "
                    + settings.reasonMaxLength() + " characters, was " + text.length());
        }
        return text;
    }

    /**
     * Retires every active version of the key older than the one now effective, each with its own history row and
     * {@code retired.v1}. Runs under the key lock; a version already retired is left alone, so it happens once.
     */
    private void retireSuperseded(List<StoredDefinition> versions, StoredDefinition effective, Instant now) {
        for (StoredDefinition older : versions) {
            if ("ACTIVE".equals(older.status()) && older.version() < effective.version()) {
                StoredDefinition retired = older.retired(EFFECTIVE_DATE_ACTOR, now, effective.version());
                write(retired);
                lifecycleHistory(retired, "RETIRED", older.status(), EFFECTIVE_DATE_ACTOR, null,
                        Map.of("supersededBy", effective.version()), now);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("definitionKey", retired.key());
                payload.put("version", retired.version());
                payload.put("supersededBy", effective.version());
                publishDefinition(retired, "processes.definition.retired.v1", payload, now);
            }
        }
    }

    /**
     * The effective-date job (PRE-020): under the key lock, then the claim, retires the versions the approved one
     * supersedes. A version no longer active by then (retired, or superseded itself) is consumed without effect.
     */
    private boolean fireDefinitionEffective(JobRow job, java.util.function.BooleanSupplier claimHeld) {
        StoredDefinition target = store.definition(job.tenantId(), job.definitionId()).orElse(null);
        List<StoredDefinition> versions = target == null ? List.of() : store.lockDefinitions(job.tenantId(), target.key());
        if (!claimHeld.getAsBoolean()) {
            return false;
        }
        StoredDefinition current = versions.stream().filter(d -> d.id().equals(job.definitionId())).findFirst()
                .orElse(null);
        if (current != null && "ACTIVE".equals(current.status())) {
            retireSuperseded(versions, current, clock.instant());
        }
        return true;
    }

    private void lifecycleHistory(StoredDefinition d, String action, String from, String actor, String comment,
            Map<String, Object> details, Instant now) {
        store.appendDefinitionHistory(new ProcessStore.DefinitionHistoryRow(d.tenantId(), Ulid.next(now), d.id(), action,
                from, d.status(), actor, comment, Values.write(details), now));
    }

    private static Map<String, Object> definitionPayload(StoredDefinition d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("definitionKey", d.key());
        payload.put("version", d.version());
        payload.put("definitionHash", d.definitionHash());
        return payload;
    }

    /** The event id is derived from the version id and its lock version, so a replayed transaction mints no new id. */
    private void publishDefinition(StoredDefinition d, String type, Map<String, Object> payload, Instant now) {
        publish(new ProcessEventPublisher.Event(io.github.dhluong90.bpm.process.internal.exec.Ids.child(d.id(),
                d.lockVersion()), type, d.tenantId(), now, payload), null);
    }

    private String callerWithAny(String... permissions) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        java.util.Set<String> held = callers.currentPermissions();
        for (String p : permissions) {
            if (held.contains(p)) {
                return caller;
            }
        }
        throw new PermissionDeniedException("Needs " + String.join(" or ", permissions));
    }

    /** Every handler a definition names must be registered, and decisions need a Rule Engine. */
    private void checkBindings(Process process) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (FlowElement element : process.flowElements()) {
            if (element instanceof ServiceTask task) {
                String handler = task.extensions().first(HandlerRef.class).map(HandlerRef::handler).orElse(null);
                if (handler != null && !invoker.hasHandler(handler)) {
                    problems.add(DefinitionProblem.of("HANDLER_NOT_REGISTERED", "No ServiceTaskHandler named " + handler
                            + " is registered in this host", task.id()));
                }
            } else if (element instanceof BusinessRuleTask task && !invoker.hasRuleEngine()) {
                problems.add(DefinitionProblem.of("RULE_ENGINE_MISSING", "A businessRuleTask needs the Rule Engine",
                        task.id()));
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationFailedException(problems);
        }
    }

    // ---- ProcessEngine ------------------------------------------------------------------------------------

    @Override
    public ProcessInstance start(StartRequest request) {
        return startDetailed(request).instance();
    }

    /**
     * Starts an instance and reports whether this request created it; used by the REST layer for 201 or 200.
     *
     * @param request the request
     * @return the instance and whether it was created
     */
    public Started startDetailed(StartRequest request) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_START);
        if (request.businessKey() != null && (request.businessKey().isBlank()
                || request.businessKey().length() > EngineDefaults.MAX_BUSINESS_KEY_LENGTH)) {
            throw new InputInvalidException("businessKey must be 1 to " + EngineDefaults.MAX_BUSINESS_KEY_LENGTH
                    + " characters");
        }
        String variablesJson = boundedJson(request.variables(), "variables");
        String requestHash = Hashes.sha256Parts("start", caller, request.definitionKey(), request.businessKey(),
                variablesJson);
        return transactions.inTransaction(() -> idempotent(tenant, "start", request.idempotencyKey(), requestHash, () -> {
            if (request.businessKey() != null) {
                InstanceRow existing = store.findInstanceByBusinessKey(tenant, request.definitionKey(),
                        request.businessKey()).orElse(null);
                if (existing != null) {
                    return new Started(read(tenant, existing), false);
                }
            }
            Instant now = clock.instant();
            StoredDefinition definition = store.effectiveDefinition(tenant, request.definitionKey(), now)
                    .orElseThrow(() -> new NoEffectiveVersionException("No active version of " + request.definitionKey()));
            String instanceId = Ulid.next(now);
            InstanceRow row = new InstanceRow(tenant, instanceId, definition.id(), definition.key(), definition.version(),
                    request.businessKey(), "RUNNING", "{}", 0, 0, caller, now, null);
            if (!store.insertInstance(row)) {
                return new Started(read(tenant, store.findInstanceByBusinessKey(tenant, request.definitionKey(),
                        request.businessKey()).orElseThrow()), false);
            }
            InstanceState state = InstanceState.created(instanceId, tenant, definition.id(), request.businessKey());
            run(definition, row, state, new Command.Start(request.variables(), caller));
            return new Started(read(tenant, store.findInstance(tenant, instanceId).orElseThrow()), true);
        }, Views::startedToJson, Views::startedFromJson));
    }

    @Override
    public ProcessInstance instance(String instanceId) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        return transactions.inTransaction(() -> read(tenant, store.findInstance(tenant, instanceId)
                .orElseThrow(() -> new InstanceNotFoundException("No instance " + instanceId))));
    }

    // ---- jobs ---------------------------------------------------------------------------------------------

    /**
     * The job handlers of this engine, by job kind, for the {@link JobExecutor} of each replica.
     *
     * @return the handlers
     */
    public Map<String, JobHandler> jobHandlers() {
        return Map.of(JobStore.TIMER, this::fireTimer, JobStore.DEFINITION_EFFECTIVE, this::fireDefinitionEffective);
    }

    /**
     * Fires a timer inside the fire transaction: locks the instance row, then checks the claim, then delivers
     * {@link Command.JobDue} to the waiting token. There is no caller: the tenant is the job's, and the actor is
     * {@code system:timer}. A timer whose instance ended or whose token moved on is consumed without firing.
     */
    private boolean fireTimer(JobRow job, java.util.function.BooleanSupplier claimHeld) {
        String tenant = job.tenantId();
        InstanceRow row = store.lockInstance(tenant, job.instanceId()).orElse(null);
        if (!claimHeld.getAsBoolean()) {
            return false;
        }
        if (row == null || !"RUNNING".equals(row.status())) {
            return true;
        }
        List<io.github.dhluong90.bpm.process.internal.exec.Token> tokens = store.tokens(tenant, row.id());
        boolean waiting = tokens.stream().anyMatch(t -> t.id().equals(job.tokenId())
                && t.status() == io.github.dhluong90.bpm.process.internal.exec.TokenStatus.WAITING_TIMER
                && job.id().equals(t.activityInstanceId()));
        if (!waiting) {
            LOG.log(System.Logger.Level.DEBUG, "process.timer.obsolete tenant={0} job={1}", tenant, job.id());
            return true;
        }
        StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
        InstanceState state = new InstanceState(row.id(), tenant, row.definitionId(), row.businessKey(),
                InstanceStatus.valueOf(row.status()), tokens, Values.readMap(row.variablesJson()),
                participants(tenant, row), row.sequence());
        run(definition, row, state, new Command.JobDue(job.id(), job.tokenId(), job.jobKind()));
        return true;
    }

    // ---- TaskService --------------------------------------------------------------------------------------

    @Override
    public ProcessInstance complete(String taskId, Map<String, Object> result, long expectedLockVersion,
            String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_TASK_WORK);
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException("Completing a task needs an Idempotency-Key");
        }
        Map<String, Object> values = result == null ? Map.of() : result;
        String requestHash = Hashes.sha256Parts("complete", caller, taskId, boundedJson(values, "result"),
                String.valueOf(expectedLockVersion));
        return transactions.inTransaction(() -> idempotent(tenant, "complete", idempotencyKey, requestHash, () -> {
            TaskRow task = store.findTask(tenant, taskId).orElseThrow(() -> new TaskNotFoundException("No task " + taskId));
            InstanceRow row = store.lockInstance(tenant, task.instanceId()).orElseThrow();
            task = store.findTask(tenant, taskId).orElseThrow();
            if (expectedLockVersion != task.lockVersion()) {
                throw new VersionConflictException("Task " + taskId + " is at lock version " + task.lockVersion());
            }
            if (!"OPEN".equals(task.status()) && !"CLAIMED".equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is " + task.status());
            }
            if (!assigned(task.assignee(), caller)) {
                throw new TaskNotAssignedException("The caller is not an assignee of task " + taskId);
            }
            java.util.Set<String> participants = participants(tenant, row);
            if (task.fourEyes() && participants.contains(caller)) {
                throw new SelfApprovalForbiddenException("The identity that started this instance or completed an earlier"
                        + " task of it cannot complete this four-eyes task");
            }
            Instant now = clock.instant();
            if (store.completeTask(tenant, taskId, task.lockVersion(), caller, now) != 1) {
                throw new VersionConflictException("Task " + taskId + " changed concurrently");
            }
            StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
            InstanceState state = new InstanceState(row.id(), tenant, row.definitionId(), row.businessKey(),
                    InstanceStatus.valueOf(row.status()), store.tokens(tenant, row.id()), Values.readMap(row.variablesJson()),
                    participants, row.sequence());
            run(definition, row, state, new Command.CompleteTask(taskId, task.tokenId(), values, caller));
            return read(tenant, store.findInstance(tenant, row.id()).orElseThrow());
        }, Views::instanceToJson, Views::instanceFromJson));
    }

    /**
     * Hands an event to the publisher inside the transaction. A slow listener holds the instance lock and a
     * connection, so the call is timed and logged above {@code handlers.slow-call-warning} with the event type,
     * instance and duration only, never the payload. A listener exception rolls the transaction back and is reported
     * as {@code EVENT_LISTENER_FAILED}.
     */
    private void publish(ProcessEventPublisher.Event event, String instanceId) {
        long started = System.nanoTime();
        try {
            publisher.publish(event);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "process.event.listener.failed type={0} instance={1} exception={2}",
                    event.type(), instanceId, e.getClass().getName());
            throw new EventListenerFailedException("A listener of " + event.type() + " failed; the change was rolled back");
        } finally {
            long elapsed = System.nanoTime() - started;
            if (elapsed > settings.slowCallWarning().toNanos()) {
                LOG.log(System.Logger.Level.WARNING, "process.event.listener.slow type={0} instance={1} elapsedMs={2}",
                        event.type(), instanceId, elapsed / 1_000_000);
            }
        }
    }

    /** Four-eyes participants: the starter and everyone who completed a task of the instance (PRE-007). */
    private java.util.Set<String> participants(String tenant, InstanceRow row) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (row.startedBy() != null) {
            out.add(row.startedBy());
        }
        store.tasks(tenant, row.id()).stream().filter(t -> "COMPLETED".equals(t.status()) && t.completedBy() != null)
                .forEach(t -> out.add(t.completedBy()));
        return out;
    }

    /** Serialises request values, refusing values JSON cannot hold and anything above the size limit. */
    private static String boundedJson(Map<String, Object> values, String what) {
        String json;
        try {
            json = Values.write(values);
        } catch (RuntimeException e) {
            throw new InputInvalidException(what + " hold a value that cannot be stored");
        }
        if (json.length() > EngineDefaults.MAX_VARIABLES_CHARS) {
            throw new PayloadTooLargeException(what + " exceed " + EngineDefaults.MAX_VARIABLES_CHARS + " characters of JSON");
        }
        return json;
    }

    private boolean assigned(String assignee, String caller) {
        int colon = assignee.indexOf(':');
        String kind = assignee.substring(0, colon);
        String value = assignee.substring(colon + 1);
        return switch (kind) {
            case "user" -> value.equals(caller);
            case "role" -> callers.currentRoles().contains(value);
            case "group" -> callers.currentGroups().contains(value);
            default -> false;
        };
    }

    // ---- shell --------------------------------------------------------------------------------------------

    private void run(StoredDefinition definition, InstanceRow row, InstanceState initial, Command first) {
        Compiled compiled = compiled(definition);
        InstanceState state = initial;
        List<HistoryEntry> history = new ArrayList<>();
        List<TaskRow> newTasks = new ArrayList<>();
        List<String> cancelledTasks = new ArrayList<>();
        List<JobRow> newJobs = new ArrayList<>();
        List<String> cancelledJobs = new ArrayList<>();
        Deque<Command> queue = new ArrayDeque<>();
        queue.add(first);
        int applied = 0;
        try {
            while (!queue.isEmpty()) {
                // One budget for the whole call: a loop through a local service task cannot run forever.
                if (++applied > EngineDefaults.MAX_COMMANDS_PER_CALL) {
                    throw new ExecutionException("STEP_LIMIT", null, "More than " + EngineDefaults.MAX_COMMANDS_PER_CALL
                            + " steps in one call; the process loops without a wait state");
                }
                Transition t = compiled.kernel().apply(state, queue.poll(), clock);
                state = t.state();
                history.addAll(t.history());
                for (Effect effect : t.effects()) {
                    switch (effect) {
                        case Effect.CreateUserTask task -> newTasks.add(new TaskRow(state.tenantId(), task.taskId(),
                                state.instanceId(), task.tokenId(), definition.id(), task.nodeId(), task.name(),
                                task.assignee(), task.fourEyes(), "OPEN", 0, null, clock.instant(), null));
                        case Effect.CancelUserTask cancel -> cancelledTasks.add(cancel.taskId());
                        case Effect.ScheduleJob job -> newJobs.add(JobRow.due(state.tenantId(), job.jobId(),
                                state.instanceId(), definition.id(), job.tokenId(), job.jobKind(), job.nodeId(),
                                job.dueAt(), jobSettings.timerLease(), jobSettings.maxAttempts(),
                                Values.write(job.payload())));
                        case Effect.CancelJob cancel -> cancelledJobs.add(cancel.jobId());
                        case Effect.InvokeService call when call.mode() == ExecutionMode.SYNC_LOCAL -> {
                            if (call.kind() == ServiceKind.DECISION && !state.tenantId().equals(tenants.currentTenantId())) {
                                // The Rule Engine resolves its tenant from the caller; it must be the instance's tenant.
                                throw new TenantNotAllowedException("The caller's tenant is not the instance's tenant");
                            }
                            var result = invoker.invoke(state.tenantId(), state.instanceId(), state.businessKey(), call);
                            if (call.kind() == ServiceKind.DECISION
                                    && result instanceof io.github.dhluong90.bpm.process.internal.exec.StepResult.Completed c) {
                                history.add(new HistoryEntry("DECISION_EVALUATED", call.nodeId(), call.tokenId(),
                                        call.attemptId(), null, clock.instant(),
                                        Map.of("ruleSet", call.target(), "decisionId", String.valueOf(c.output().get("decisionId")))));
                            }
                            queue.add(new Command.AsyncResult(call.tokenId(), call.attemptId(), result));
                        }
                        case Effect.EmitEvent event -> {
                            Map<String, Object> payload = new LinkedHashMap<>(event.payload());
                            payload.putIfAbsent("instanceId", state.instanceId());
                            payload.put("definitionKey", definition.key());
                            payload.put("version", definition.version());
                            if (state.businessKey() != null) {
                                payload.put("businessKey", state.businessKey());
                            }
                            publish(new ProcessEventPublisher.Event(event.eventId(), event.type(), state.tenantId(),
                                    clock.instant(), payload), state.instanceId());
                        }
                        default -> throw new ExecutionException("EFFECT_NOT_SUPPORTED", null,
                                effect.getClass().getSimpleName() + " is not supported in this release");
                    }
                }
            }
        } catch (ExecutionException e) {
            if ("SELF_APPROVAL_FORBIDDEN".equals(e.code())) {
                throw new SelfApprovalForbiddenException(e.getMessage());
            }
            // Handler and decision texts may carry personal data: they go to the log, the caller gets the code.
            LOG.log(System.Logger.Level.DEBUG, "process.step.failed node={0} code={1} detail={2}", e.nodeId(), e.code(),
                    e.getMessage());
            throw new StepFailedException(e.nodeId() == null ? "-" : e.nodeId(), e.code(), "see the engine log");
        } catch (NotExecutableException e) {
            throw new StepFailedException(e.nodeId(), "BPMN_NOT_EXECUTABLE", "not executable in this release");
        }
        String variablesJson = Values.write(state.variables());
        if (variablesJson.length() > EngineDefaults.MAX_VARIABLES_CHARS) {
            throw new PayloadTooLargeException("The instance variables exceed " + EngineDefaults.MAX_VARIABLES_CHARS
                    + " characters of JSON");
        }
        Instant now = clock.instant();
        store.saveTokens(state.tenantId(), state.instanceId(), definition.id(), state.tokens(), now);
        store.updateInstance(new InstanceRow(row.tenantId(), row.id(), row.definitionId(), row.definitionKey(),
                row.definitionVersion(), row.businessKey(), state.status().name(), variablesJson,
                state.sequence(), row.lockVersion(), row.startedBy(), row.startedAt(),
                state.status() == InstanceStatus.RUNNING ? null : now));
        String tenant = state.tenantId();
        newTasks.forEach(store::insertTask);
        cancelledTasks.forEach(id -> store.cancelTask(tenant, id));
        newJobs.forEach(job -> jobs.insertJob(job, now));
        cancelledJobs.forEach(id -> jobs.cancelJob(tenant, id, now));
        store.appendHistory(tenant, state.instanceId(), definition.id(), history);
    }

    private Compiled compiled(StoredDefinition definition) {
        return kernels.computeIfAbsent(definition.tenantId() + "/" + definition.id(), k -> {
            Function<byte[], Process> reader = readers.get(SourceFormat.valueOf(definition.sourceFormat()));
            // Interim form of ADR-0026 decision 4: the version runs from its stored source, so the source must be the
            // one that was validated and hashed at import. Any difference refuses the version.
            byte[] source = definition.sourceDocument().getBytes(StandardCharsets.UTF_8);
            if (!Hashes.sha256(source).equals(definition.sourceHash())) {
                throw new DefinitionIntegrityException("Definition " + definition.key() + " v" + definition.version()
                        + ": the stored source does not match its source hash");
            }
            Process process = reader.apply(source);
            if (!Hashes.sha256(CanonicalProcessWriter.canonicalText(process)).equals(definition.definitionHash())) {
                throw new DefinitionIntegrityException("Definition " + definition.key() + " v" + definition.version()
                        + ": the stored source no longer gives the recorded definition hash");
            }
            CompiledProcess compiledProcess = compiler.compile(definition.id(), process);
            return new Compiled(compiledProcess, new DefaultExecutionKernel(compiledProcess, behaviors, expressions));
        });
    }

    private ProcessInstance read(String tenant, InstanceRow row) {
        List<ProcessInstance.Token> tokens = store.tokens(tenant, row.id()).stream()
                .map(t -> new ProcessInstance.Token(t.id(), t.kind().name(), t.scopeTokenId(), t.nodeId(), t.status().name()))
                .toList();
        List<ProcessInstance.Task> tasks = store.tasks(tenant, row.id()).stream()
                .map(t -> new ProcessInstance.Task(t.id(), t.nodeId(), t.name(), t.assignee(), t.status(), t.lockVersion(),
                        t.completedBy()))
                .toList();
        return new ProcessInstance(row.id(), row.definitionKey(), row.definitionVersion(), row.businessKey(), row.status(),
                Values.readMap(row.variablesJson()), tokens, tasks, row.lockVersion(), row.startedBy(), row.startedAt(),
                row.endedAt());
    }

    private static DefinitionVersion view(StoredDefinition d) {
        return new DefinitionVersion(d.id(), d.key(), d.version(), d.status(), d.name(), d.definitionHash(),
                SourceFormat.valueOf(d.sourceFormat()), d.sourceHash(), d.nodeCount(), d.approvedBy(), d.createdBy(),
                d.createdAt(), d.source(), d.lockVersion(), d.effectiveFrom(), d.requestedBy(), d.requestedAt(),
                d.approvedAt(), d.decisionComment(), d.retiredBy(), d.retiredAt(), d.supersededBy());
    }

    private <T> T idempotent(String tenant, String operation, String key, String requestHash,
            java.util.function.Supplier<T> action, Function<T, String> toJson, Function<String, T> fromJson) {
        if (key == null) {
            return action.get();
        }
        if (key.isBlank() || key.length() > EngineDefaults.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InputInvalidException("Idempotency-Key must be 1 to " + EngineDefaults.MAX_IDEMPOTENCY_KEY_LENGTH
                    + " characters");
        }
        Instant now = clock.instant();
        // Claim first: a concurrent request with the same key blocks here until this transaction ends, then replays.
        if (store.claimIdempotency(tenant, operation, key, requestHash, now.plus(settings.idempotencyRetention()), now)) {
            T result = action.get();
            store.completeIdempotency(tenant, operation, key, toJson.apply(result));
            return result;
        }
        IdempotencyRecord previous = store.idempotency(tenant, operation, key, now)
                .orElseThrow(() -> new IdempotencyInFlightException("A request with this Idempotency-Key is running"));
        if (!previous.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException("The Idempotency-Key was used with a different request");
        }
        if (previous.responseJson() == null) {
            throw new IdempotencyInFlightException("A request with this Idempotency-Key is running");
        }
        return fromJson.apply(previous.responseJson());
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private String callerWith(String permission) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException("Needs " + permission);
        }
        return caller;
    }
}
