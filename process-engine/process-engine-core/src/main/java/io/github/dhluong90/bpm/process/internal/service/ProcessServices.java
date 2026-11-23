package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.AsyncStepNotFoundException;
import io.github.dhluong90.bpm.process.api.AsyncStepService;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.GuardrailContractInvalidException;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.internal.exec.Ids;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.process.api.DefinitionIntegrityException;
import io.github.dhluong90.bpm.process.api.TenantNotAllowedException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.LastVersionInEffectException;
import io.github.dhluong90.bpm.process.api.VersionSupersededException;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.AssigneeNotAllowedException;
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
import io.github.dhluong90.bpm.process.api.TaskView;
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
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.internal.model.horus.OnFailure;
import io.github.dhluong90.bpm.process.internal.model.horus.RetryPolicy;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.IdempotencyRecord;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.InstanceRow;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.StoredDefinition;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore.TaskRow;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.BusinessCalendar;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
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
 * store state and history. {@code SYNC_LOCAL} invocations run in the transaction; an {@code ASYNC} invocation is
 * stored as a job and run by the {@link AsyncStepExecutor} outside any transaction, and its result is recorded by
 * {@link #recordAttempt} in a fresh one (ADR-0008). Thread-safe.
 */
public final class ProcessServices implements DefinitionService, ProcessEngine, TaskService, AsyncStepService,
        io.github.dhluong90.bpm.process.api.IncidentService {

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
    static final String PERMISSION_CANCEL = "processes:cancel";
    static final String PERMISSION_READ = "processes:read";
    static final String PERMISSION_TASK_WORK = "processes:task:work";
    static final String PERMISSION_TASK_MANAGE = "processes:task:manage";
    static final String PERMISSION_AUTHOR = "processes:author";
    static final String PERMISSION_APPROVE = "processes:approve";
    static final String PERMISSION_STEP_COMPLETE = "processes:step:complete";
    static final String PERMISSION_INCIDENT_MANAGE = "processes:incident:manage";
    /** History types of the incident commands (#68). */
    static final String INCIDENT_RETRIED = "INCIDENT_RETRIED";
    static final String INCIDENT_RESOLUTION_PROPOSED = "INCIDENT_RESOLUTION_PROPOSED";
    static final String INCIDENT_RESOLUTION_REJECTED = "INCIDENT_RESOLUTION_REJECTED";
    static final String INCIDENT_RESOLVED = "INCIDENT_RESOLVED";
    /** Resolutions of an incident (#68). */
    static final String RETRY_SUCCEEDED = "RETRY_SUCCEEDED";
    static final String COMPLETED_MANUALLY = "COMPLETED_MANUALLY";
    static final String INSTANCE_CANCELLED = "INSTANCE_CANCELLED";
    /** Who resolved an incident whose retry was accepted: the engine, never a person (PRE-077). */
    static final String RESOLVED_BY_ENGINE = "system:process-engine";
    static final List<String> INCIDENT_STATUSES = List.of("OPEN", "RESOLUTION_PENDING", "RESOLVED");
    /** An agent step whose memory failed before any call cannot run again: the facts it needs are missing (#101). */
    static final java.util.Set<String> NOT_RETRYABLE = java.util.Set.of("AGENT_MEMORY_TOO_LARGE", "AGENT_MEMORY_MISSING",
            "AGENT_MEMORY_INVALID", "AGENT_MEMORY_MASKED_FIELD");
    /** Actor of the result transaction of an async step. */
    static final String ASYNC_ACTOR = "system:async-step";
    static final String DEV_APPROVER = "system:dev-auto-approve";
    /** Prefix of the engine's own identities; refused for callers of lifecycle commands. */
    static final String SYSTEM_PREFIX = "system:";
    /** Actor of a retirement made because a newer version took effect. */
    static final String EFFECTIVE_DATE_ACTOR = "system:effective-date";
    /** Version of the BPMN reader whose output an approval binds (DESIGN section 5.8). */
    static final String READER_VERSION = "bpmn-subset-1";
    static final String CURSOR_KEY_NAME = "cursor-hmac";
    /** Job payload key of the audit copy of an agent step's inputs, masked inputs fingerprinted (#59). */
    static final String AUDIT_INPUTS = "auditInputs";
    /** Job payload key of the memory an agent step receives from earlier steps (#101); the adapter reads it. */
    static final String MEMORY = "memory";
    /** Job payload key of the audit copy of that memory, masked fields fingerprinted (#101); history reads it. */
    static final String AUDIT_MEMORY = "auditMemory";
    /** Job payload key of the step's own delay before each retry, in milliseconds (#58). */
    static final String RETRY_BACKOFF_MS = "retryBackoffMs";
    /** History type of a reviewer's answer that replaced a refused agent answer (#59). */
    static final String HUMAN_OVERRIDE = "HUMAN_OVERRIDE";
    /** History type of an incident opened on a step (#58). */
    static final String INCIDENT_RAISED = "INCIDENT_RAISED";
    /** Incident code of a business error no path of the process catches (DN-09). */
    static final String UNHANDLED_BUSINESS_ERROR = "UNHANDLED_BUSINESS_ERROR";
    static final List<String> INSTANCE_STATUSES = List.of("RUNNING", "COMPLETED", "CANCELLED", "COMPENSATING", "FAILED");
    private static final int MAX_USER_ID_LENGTH = 200;
    /** The longest reason of a reassign or delegate (PD-110, fixed). */
    static final int HAND_OVER_REASON_MAX = 500;
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final int MAX_KEY_LENGTH = 200;
    /** A definition hash as the engine shows it: lower-case hexadecimal SHA-256 (TR-DEF-04). */
    private static final java.util.regex.Pattern CONTENT_HASH = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    static final String TIMER_ACTOR = "system:timer";
    private static final System.Logger LOG = System.getLogger(ProcessServices.class.getName());

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final ProcessStore store;
    private final Map<SourceFormat, Function<byte[], Process>> readers;
    private final Map<SourceFormat, Function<Process, byte[]>> writers;
    private final LocalServiceInvoker invoker;
    private final ProcessEventPublisher publisher;
    private final EngineSettings settings;
    private final BehaviorRegistry behaviors;
    private final ExpressionEvaluator expressions;
    private final ProcessCompiler compiler;
    private final JobStore jobs;
    private final JobSettings jobSettings;
    private final AsyncSettings async;
    private final java.util.Set<String> adapters;
    private final java.util.Set<String> agents;
    /** The result check of each async job kind, run in the result transaction (#363); a kind without one has none. */
    private final Map<String, ResultGuard> guards;
    /** Checks the reviewer's answer to the review task of a refused result against the step's output schema. */
    private final io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas;
    /** Makes the audit copy of masked agent inputs (#59); never returns the plain value. */
    private final io.github.dhluong90.bpm.process.spi.InputMasker inputMasker;
    /** Bounds of agent answer labels (PD-014, #59). */
    private final AgentLabelLimits labelLimits;
    /** Bounds of the memory agent steps receive from earlier steps (#101). */
    private final AgentMemoryLimits memoryLimits;
    private final BusinessCalendar calendar;
    private final Map<String, Compiled> kernels = new ConcurrentHashMap<>();
    private volatile Cursors cursors;
    /** Operational measurements, called after commit only (PRE-084); the engine default does nothing. */
    private volatile io.github.dhluong90.bpm.process.spi.RoleCatalogue roleCatalogue =
            io.github.dhluong90.bpm.process.spi.RoleCatalogue.ACCEPT_ALL;
    /** {@code calls.max-depth} (#92). */
    private volatile int maxCallDepth = EngineDefaults.MAX_CALL_DEPTH;
    private volatile int maxLoopInstances = DefaultExecutionKernel.DEFAULT_MAX_INSTANCES;
    private volatile io.github.dhluong90.bpm.process.internal.store.LayoutStore importedLayouts;
    private volatile Map<SourceFormat, java.util.function.BiFunction<byte[], Process, String>> layoutExtractors =
            Map.of();
    private volatile io.github.dhluong90.bpm.process.spi.AssigneeDirectory directory =
            io.github.dhluong90.bpm.process.spi.AssigneeDirectory.NONE;
    private volatile io.github.dhluong90.bpm.process.spi.ProcessMetrics metrics =
            io.github.dhluong90.bpm.process.spi.ProcessMetrics.NONE;
    /** The kind of the async job the current thread's result transaction handles, for the attempt metric. */
    private static final ThreadLocal<String[]> ATTEMPT_KIND = new ThreadLocal<>();

    /**
     * @param memorySources the steps a later agent step reads as memory (#101); only their results are kept
     */
    private record Compiled(CompiledProcess process, ExecutionKernel kernel, java.util.Set<String> memorySources) {
    }

    /**
     * Who one run acts for. A method parameter, never a thread-local, so it cannot outlive the transaction or leak
     * to another call, and never built from request data.
     */
    private sealed interface RunAs {

        /** A caller's request: the tenant and the caller come from the host's security context. */
        RunAs REQUEST = new CallerRequest();

        /** The caller's request. */
        record CallerRequest() implements RunAs {
        }

        /**
         * Engine-owned work with no caller, built only by the job handlers of this class.
         *
         * @param tenantId the tenant of the job row this work fires
         * @param actor the engine actor, for example {@code system:timer}
         */
        record EngineWork(String tenantId, String actor) implements RunAs {
        }
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
        this(tenants, callers, clock, transactions, store, readers, Map.of(), invoker, publisher, settings, behaviors,
                expressions, JobStore.NONE, JobSettings.defaults("unused"));
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, JobStore.NONE, JobSettings.defaults("unused"));
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
        this(tenants, callers, clock, transactions, store, readers, Map.of(), invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings);
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings, AsyncSettings.DEFAULTS, java.util.Set.of());
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     * @param async lease margin and default timeout of remote steps (ADR-0008)
     * @param adapters names of the host's {@code ServiceAdapter}s; a remote step naming another is refused at import
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings, AsyncSettings async,
            java.util.Set<String> adapters) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings, async, adapters, new JsonSchemaSubsetValidator());
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     * @param async lease margin, default timeout and runner settings of async steps (ADR-0008, #363)
     * @param adapters names of the host's {@code ServiceAdapter}s; a remote step naming another is refused at import
     * @param outputSchemas checks async results against their output schema in the result transaction (#363)
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings, AsyncSettings async,
            java.util.Set<String> adapters, io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings, async, adapters, java.util.Set.of(), outputSchemas);
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     * @param async lease margin, default timeout and runner settings of async steps (ADR-0008, #363)
     * @param adapters names of the host's {@code ServiceAdapter}s; a remote step naming another is refused at import
     * @param agents names of the host's {@code AgentAdapter}s; an agent step naming another is refused at import (#52)
     * @param outputSchemas checks async results against their output schema in the result transaction (#363), and
     *     agent steps' schemas at import (#52)
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings, AsyncSettings async,
            java.util.Set<String> adapters, java.util.Set<String> agents,
            io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings, async, adapters, agents, outputSchemas,
                io.github.dhluong90.bpm.process.spi.InputMasker.REDACT, AgentLabelLimits.DEFAULTS);
    }

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     * @param async lease margin, default timeout and runner settings of async steps (ADR-0008, #363)
     * @param adapters names of the host's {@code ServiceAdapter}s; a remote step naming another is refused at import
     * @param agents names of the host's {@code AgentAdapter}s; an agent step naming another is refused at import (#52)
     * @param outputSchemas checks async results against their output schema in the result transaction (#363), and
     *     agent steps' schemas at import (#52)
     * @param inputMasker makes the audit copy of masked agent inputs (#59)
     * @param labelLimits bounds of agent answer labels ({@code agents.labels.*}, #59)
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings, AsyncSettings async,
            java.util.Set<String> adapters, java.util.Set<String> agents,
            io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas,
            io.github.dhluong90.bpm.process.spi.InputMasker inputMasker, AgentLabelLimits labelLimits) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings, async, adapters, agents, outputSchemas, inputMasker, labelLimits,
                AgentMemoryLimits.DEFAULTS);
    }

    /**
     * The full form; see the other constructors for the parameters they share.
     *
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     * @param async lease margin, default timeout and runner settings of async steps (ADR-0008, #363)
     * @param adapters names of the host's {@code ServiceAdapter}s
     * @param agents names of the host's {@code AgentAdapter}s
     * @param outputSchemas checks async results and agent steps' schemas
     * @param inputMasker makes the audit copy of masked agent inputs and memory (#59, #101)
     * @param labelLimits bounds of agent answer labels ({@code agents.labels.*}, #59)
     * @param memoryLimits bounds of agent step memory ({@code agents.memory.*}, #101)
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings, AsyncSettings async,
            java.util.Set<String> adapters, java.util.Set<String> agents,
            io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas,
            io.github.dhluong90.bpm.process.spi.InputMasker inputMasker, AgentLabelLimits labelLimits,
            AgentMemoryLimits memoryLimits) {
        this(tenants, callers, clock, transactions, store, readers, writers, invoker, publisher, settings, behaviors,
                expressions, jobs, jobSettings, async, adapters, agents, outputSchemas, inputMasker, labelLimits,
                memoryLimits, BusinessCalendar.ELAPSED);
    }

    /**
     * The full form with the business calendar that sets task due times (PRE-008); see the other constructors for
     * the parameters they share.
     *
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store storage
     * @param readers definition readers by source format
     * @param writers definition writers by export format (PRE-034)
     * @param invoker local handlers and decisions
     * @param publisher event publisher
     * @param settings validated settings
     * @param behaviors registered behaviours
     * @param expressions expression evaluator
     * @param jobs job storage ({@code pre_timer})
     * @param jobSettings lease and attempts of the jobs this engine schedules
     * @param async lease margin, default timeout and runner settings of async steps (ADR-0008, #363)
     * @param adapters names of the host's {@code ServiceAdapter}s
     * @param agents names of the host's {@code AgentAdapter}s
     * @param outputSchemas checks async results and agent steps' schemas
     * @param inputMasker makes the audit copy of masked agent inputs and memory (#59, #101)
     * @param labelLimits bounds of agent answer labels ({@code agents.labels.*}, #59)
     * @param memoryLimits bounds of agent step memory ({@code agents.memory.*}, #101)
     * @param calendar working time of the tenants' calendars, called once per task with an SLA
     */
    public ProcessServices(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            ProcessStore store, Map<SourceFormat, Function<byte[], Process>> readers,
            Map<SourceFormat, Function<Process, byte[]>> writers, LocalServiceInvoker invoker,
            ProcessEventPublisher publisher, EngineSettings settings, BehaviorRegistry behaviors,
            ExpressionEvaluator expressions, JobStore jobs, JobSettings jobSettings, AsyncSettings async,
            java.util.Set<String> adapters, java.util.Set<String> agents,
            io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas,
            io.github.dhluong90.bpm.process.spi.InputMasker inputMasker, AgentLabelLimits labelLimits,
            AgentMemoryLimits memoryLimits, BusinessCalendar calendar) {
        this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
        this.memoryLimits = Objects.requireNonNull(memoryLimits, "memoryLimits must not be null");
        this.inputMasker = Objects.requireNonNull(inputMasker, "inputMasker must not be null");
        this.labelLimits = Objects.requireNonNull(labelLimits, "labelLimits must not be null");
        this.async = Objects.requireNonNull(async);
        // Agent results are never accepted unchecked; remote service results keep their behaviour (#363).
        this.outputSchemas = Objects.requireNonNull(outputSchemas, "outputSchemas must not be null");
        this.guards = Map.of(JobStore.ASYNC_AGENT, new AgentResultGuard(outputSchemas, invoker.decisionPort(), clock));
        this.adapters = java.util.Set.copyOf(adapters);
        this.agents = java.util.Set.copyOf(agents);
        for (String adapter : async.completerRoles().keySet()) {
            if (!this.adapters.contains(adapter) && !this.agents.contains(adapter)) {
                throw new IllegalArgumentException("adapters.completer-roles names " + adapter
                        + ", which is no registered ServiceAdapter or AgentAdapter");
            }
        }
        this.writers = Map.copyOf(writers);
        this.jobs = Objects.requireNonNull(jobs);
        this.jobSettings = Objects.requireNonNull(jobSettings);
        this.tenants = Objects.requireNonNull(tenants);
        this.callers = Objects.requireNonNull(callers);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = TransactionRunner.tracked(Objects.requireNonNull(transactions));
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
        List<DefinitionProblem> warnings = List.of();
        Process process;
        if (reader instanceof DefinitionReader withWarnings) {
            DefinitionReader.Result read = withWarnings.readWithWarnings(bytes);
            process = read.process();
            warnings = read.warnings();
        } else {
            process = reader.apply(bytes);
        }
        final List<DefinitionProblem> importWarnings = warnings;
        compiler.compile("validation", process);
        checkBindings(process);
        checkGuardrailContracts(tenant, process);
        String canonical = CanonicalProcessWriter.canonicalText(process);
        String hash = Hashes.sha256(canonical);
        int nodeCount = (int) process.allFlowElements().stream().filter(FlowNode.class::isInstance).count();
        String requestHash = Hashes.sha256Parts("import", caller, request.format().name(), Hashes.sha256(bytes));
        String layout = importedLayout(request, process);
        return transactions.inTransaction(() -> idempotent(tenant, "import", request.idempotencyKey(), requestHash,
                () -> {
                    // Locks the key, so concurrent imports and lifecycle commands of one key serialise.
                    List<StoredDefinition> versions = store.lockDefinitions(tenant, process.id());
                    StoredDefinition latest = versions.isEmpty() ? null : versions.getLast();
                    if (latest != null && latest.definitionHash().equals(hash)) {
                        return view(latest).withWarnings(importWarnings);
                    }
                    Instant now = clock.instant();
                    boolean active = settings.definitionsAutoApprove();
                    StoredDefinition stored = new StoredDefinition(tenant, Ulid.next(now), process.id(),
                            latest == null ? 1 : latest.version() + 1, active ? "ACTIVE" : "DRAFT", process.name(),
                            canonical, hash, request.format().name(), text,
                            Hashes.sha256(text), nodeCount, source, active ? now : null, caller, now,
                            active ? DEV_APPROVER : null, active ? now : null);
                    store.insertDefinition(stored);
                    if (layout != null) {
                        // #445: revision 1, from the file's own diagram, in the transaction of the import.
                        importedLayouts.insert(tenant, stored.id(), new io.github.dhluong90.bpm.process.api.LayoutRevision(
                                1, layout, Hashes.sha256(layout), hash, "BPMN", caller, now));
                    }
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
                    return view(stored).withWarnings(importWarnings);
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
            LockedKey locked = lockVersion(tenant, ref);
            StoredDefinition current = locked.version();
            requireStatus(current, "DRAFT", "request approval of");
            requireLock(current, expectedLockVersion);
            // PRE-016: the guardrail rule set may have changed since import; checked again before anything is written.
            Process stored = storedProcess(current);
            checkServiceRegistrations(tenant, stored);
            checkGuardrailContracts(tenant, stored);
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
    public DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String expectedDefinitionHash,
            String expectedChangeSetHash, String comment, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_APPROVE);
        // PRE-177 (PD-139): an approval names the content it reviewed, refused before anything is read when missing.
        if (expectedDefinitionHash == null && settings.requireContentHash()) {
            throw new io.github.dhluong90.bpm.process.api.ContentHashRequiredException();
        }
        if (expectedDefinitionHash != null && !CONTENT_HASH.matcher(expectedDefinitionHash).matches()) {
            throw new InputInvalidException("expectedDefinitionHash must be 64 lower-case hexadecimal characters, the"
                    + " definitionHash of the version reviewed");
        }
        if (expectedChangeSetHash != null && !CONTENT_HASH.matcher(expectedChangeSetHash).matches()) {
            throw new InputInvalidException("expectedChangeSetHash must be 64 lower-case hexadecimal characters, the"
                    + " changeSetHash on the last page of the diff");
        }
        String note = boundedComment(comment, false);
        // The hashes join the request hash only when named, so a request stored before the fields existed still replays.
        String requestHash = expectedDefinitionHash == null
                ? Hashes.sha256Parts("approve", caller, ref.key(), Integer.toString(ref.version()),
                        Long.toString(expectedLockVersion), note)
                : expectedChangeSetHash == null
                        ? Hashes.sha256Parts("approve", caller, ref.key(), Integer.toString(ref.version()),
                                Long.toString(expectedLockVersion), note, expectedDefinitionHash)
                        : Hashes.sha256Parts("approve", caller, ref.key(), Integer.toString(ref.version()),
                                Long.toString(expectedLockVersion), note, expectedDefinitionHash,
                                expectedChangeSetHash);
        return transactions.inTransaction(() -> idempotent(tenant, "approve", idempotencyKey, requestHash, () -> {
            LockedKey locked = lockVersion(tenant, ref);
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
                                + "(horus.bpm.lifecycle.approver-must-differ-from: makers)");
            }
            requireLock(current, expectedLockVersion);
            // PRE-177: the content reviewed, after the lock version: a refreshed lock version can still carry a
            // decision made on older content, and a refusal names what is stored now.
            if (expectedDefinitionHash != null && !expectedDefinitionHash.equals(current.definitionHash())) {
                throw new io.github.dhluong90.bpm.process.api.ContentChangedException("The content of " + ref.key()
                        + " v" + ref.version() + " is not the content reviewed: expected definition hash "
                        + expectedDefinitionHash + " but it is " + current.definitionHash() + ", at lock version "
                        + current.lockVersion(), current.definitionHash(), current.lockVersion());
            }
            // PRE-177, PRE-199 (PD-139): an approval may not rest on a change list cut off at its limit, unless it
            // names the hash of the whole set read page by page. Pure computation on rows already read in this
            // transaction; no call leaves the process.
            if (expectedDefinitionHash != null || expectedChangeSetHash != null) {
                requireReviewedChangeSet(tenant, current, expectedChangeSetHash);
            }
            // PRE-016: and again at approval, against the rule set version in effect now; PRE-072: and the host's
            // handlers and adapters, which may have been removed since the request.
            Process stored = storedProcess(current);
            checkServiceRegistrations(tenant, stored);
            checkGuardrailContracts(tenant, stored);
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
            Map<String, Object> approvedDetails = new LinkedHashMap<>();
            approvedDetails.put("sourceHash", updated.sourceHash());
            approvedDetails.put("readerVersion", READER_VERSION);
            approvedDetails.put("effectiveFrom", effective.toString());
            approvedDetails.put("approverMustDifferFrom", settings.approverRule().value());
            if (expectedDefinitionHash != null) {
                approvedDetails.put("reviewedDefinitionHash", expectedDefinitionHash);
            }
            if (expectedChangeSetHash != null) {
                approvedDetails.put("reviewedChangeSetHash", expectedChangeSetHash);
            }
            lifecycleHistory(updated, "APPROVED", current.status(), caller, note, approvedDetails, now);
            Map<String, Object> payload = definitionPayload(updated);
            payload.put("requestedBy", updated.requestedBy());
            payload.put("effectiveFrom", effective.toString());
            payload.put("source", updated.source());
            payload.put("approvedBy", approver);
            payload.put("approvedAt", now.toString());
            payload.put("sourceHash", updated.sourceHash());
            payload.put("readerVersion", READER_VERSION);
            if (expectedDefinitionHash != null) {
                payload.put("reviewedDefinitionHash", expectedDefinitionHash);
            }
            if (expectedChangeSetHash != null) {
                payload.put("reviewedChangeSetHash", expectedChangeSetHash);
            }
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
    public DefinitionVersion retire(DefinitionRef ref, long expectedLockVersion, String reason,
            boolean confirmNoVersionInEffect, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWithAny(PERMISSION_APPROVE, PERMISSION_AUTHOR);
        String why = boundedComment(reason, false);
        String requestHash = Hashes.sha256Parts("retire", caller, ref.key(), Integer.toString(ref.version()),
                Long.toString(expectedLockVersion), why, Boolean.toString(confirmNoVersionInEffect));
        return transactions.inTransaction(() -> idempotent(tenant, "retire", idempotencyKey, requestHash, () -> {
            LockedKey locked = lockVersion(tenant, ref);
            StoredDefinition current = locked.version();
            boolean active = switch (current.status()) {
                case "ACTIVE" -> true;
                case "DRAFT" -> false;
                default -> throw new InvalidStateException(ref.key() + " v" + ref.version() + " is " + current.status()
                        + "; only an active version or a draft can be retired");
            };
            callerWith(active ? PERMISSION_APPROVE : PERMISSION_AUTHOR);
            if (active && why == null) {
                throw new InputInvalidException("reason is required to retire an active version");
            }
            requireLock(current, expectedLockVersion);
            Instant now = clock.instant();
            StoredDefinition selected = selected(locked.all(), now);
            // PD-102, as the Rule Engine (RLE-026): a version a newer one in effect already supersedes no longer takes
            // new starts and its effective-date job retires it; refused before anything is written, so a caller and a
            // reason are never recorded on that transition.
            if (active && selected != null && selected.version() > current.version()) {
                throw new InvalidStateException("retire is not allowed on " + ref.key() + " v" + ref.version() + ": v"
                        + selected.version() + " is in effect and supersedes it, so it no longer takes new starts and"
                        + " retires on its own");
            }
            // PD-101: a key left with no version in effect makes every new start fail until a new version is approved.
            // "In effect" is the selected version (DESIGN 3.2): retiring it leaves the key with none; older versions
            // it supersedes do not count.
            boolean last = active && selected != null && selected.id().equals(current.id());
            if (last) {
                Instant next = locked.all().stream().filter(d -> !d.id().equals(current.id())
                        && "ACTIVE".equals(d.status()) && d.effectiveFrom() != null && d.effectiveFrom().isAfter(now))
                        .map(StoredDefinition::effectiveFrom).min(Instant::compareTo).orElse(null);
                if (settings.retireLastVersion() == EngineSettings.RetireLastVersionRule.REFUSE) {
                    throw new LastVersionInEffectException(ref.key() + " v" + ref.version() + " is the last version "
                            + "in effect and horus.bpm.lifecycle.retire-last-version is refuse; approve a replacement "
                            + "first", ref.key(), ref.version(), "refuse", next);
                }
                if (!confirmNoVersionInEffect) {
                    throw new LastVersionInEffectException("No version of " + ref.key() + " will be in effect: new "
                            + "starts will fail with NO_EFFECTIVE_VERSION until a new version is approved. Running "
                            + "instances continue. Send confirmNoVersionInEffect: true to retire it.", ref.key(),
                            ref.version(), "confirm", next);
                }
            }
            // PRE-053, as the Rule Engine (#269): only after every check, in this transaction, retire the versions the
            // selected one supersedes (as its effective-date job would), then this one, so a refusal writes nothing and
            // a superseded version never takes new cases again.
            if (active && selected != null) {
                // This version itself is retired below, by the caller with the reason, never as superseded.
                retireSuperseded(locked.all().stream().filter(d -> !d.id().equals(current.id())).toList(), selected,
                        now);
            }
            StoredDefinition updated = current.retired(caller, now, null);
            write(updated);
            // PRE-053: a version retired before its start date never takes effect, so its effective-date job goes too.
            jobs.cancelDefinitionJobs(tenant, current.id(), now);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("approvalMode", EngineSettings.RETIRE_APPROVAL_MODE);
            details.put("noVersionInEffect", last);
            lifecycleHistory(updated, "RETIRED", current.status(), caller, why, details, now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("definitionKey", updated.key());
            payload.put("version", updated.version());
            payload.put("retiredBy", caller);
            payload.put("reason", why);
            payload.put("approvalMode", EngineSettings.RETIRE_APPROVAL_MODE);
            payload.put("noVersionInEffect", last);
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
    private record LockedKey(StoredDefinition version, List<StoredDefinition> all) {
    }

    private LockedKey lockVersion(String tenant, DefinitionRef ref) {
        List<StoredDefinition> all = store.lockDefinitions(tenant, ref.key());
        StoredDefinition version = all.stream().filter(d -> d.version() == ref.version()).findFirst()
                .orElseThrow(() -> notFound(ref));
        return new LockedKey(version, all);
    }

    private java.util.Optional<StoredDefinition> versionOf(String tenant, DefinitionRef ref) {
        return store.definitionVersion(tenant, ref.key(), ref.version());
    }

    /** The version new starts use: the highest-numbered ACTIVE version with {@code effective_from <= now}, or null. */
    private static StoredDefinition selected(List<StoredDefinition> versions, Instant now) {
        StoredDefinition best = null;
        for (StoredDefinition d : versions) {
            if ("ACTIVE".equals(d.status()) && d.effectiveFrom() != null && !d.effectiveFrom().isAfter(now)
                    && (best == null || d.version() > best.version())) {
                best = d;
            }
        }
        return best;
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
        requireNotSystemIdentity(caller);
        java.util.Set<String> held = callers.currentPermissions();
        for (String p : permissions) {
            if (held.contains(p)) {
                return caller;
            }
        }
        throw new PermissionDeniedException("Needs " + String.join(" or ", permissions));
    }

    /**
     * Identities starting with {@code system:} are the engine's own ({@code system:dev-auto-approve},
     * {@code system:effective-date}, {@code system:startup}, {@code system:timer}). No caller may act under such an
     * id at any entry point: the four-eyes CHECK exempts {@code system:dev-auto-approve}, and history and events would
     * credit the engine with the caller's work. Engine-internal paths (start-up import, job firing) never resolve a
     * caller.
     */
    private static void requireNotSystemIdentity(String caller) {
        if (caller.startsWith(SYSTEM_PREFIX)) {
            throw new PermissionDeniedException("Identities starting with " + SYSTEM_PREFIX + " are reserved for the "
                    + "engine");
        }
    }

    @Override
    public List<io.github.dhluong90.bpm.process.api.UnclassifiedField> unclassifiedFields(DefinitionRef ref) {
        String tenant = tenant();
        // Whoever may read the version or request its approval sees the reminder
        callerWithAny(PERMISSION_READ, PERMISSION_AUTHOR, PERMISSION_DEPLOY);
        if (!isKey(ref.key()) || ref.version() < 1) {
            throw new DefinitionNotFoundException("No definition " + safe(ref.key()) + " v" + ref.version());
        }
        StoredDefinition stored = transactions.inTransaction(() -> store.definitionVersion(tenant, ref.key(),
                ref.version()).orElseThrow(() -> new DefinitionNotFoundException("No definition " + ref.key() + " v"
                + ref.version())));
        return settings.warnUnclassified() ? UnclassifiedFields.of(storedProcess(stored)) : List.of();
    }

    /** Most active versions the start-up summary of unclassified fields reads. */
    static final int UNCLASSIFIED_SUMMARY_VERSIONS = 1_000;

    /** Most active versions the start-up summary names; the rest are counted. */
    static final int UNCLASSIFIED_SUMMARY_NAMED = 50;

    /**
     * The one start-up summary of PD-146 (#438): how many agent step fields of each active version declare no
     * {@code mask}. Names the tenant, key, version and count, never a field value. Runs outside any request.
     *
     * @return the WARN text, or empty when every field is classified or {@code classification.warn-unclassified} is off
     */
    public java.util.Optional<String> unclassifiedSummary() {
        if (!settings.warnUnclassified()) {
            return java.util.Optional.empty();
        }
        List<String> named = new ArrayList<>();
        int versions = 0;
        int total = 0;
        for (StoredDefinition definition : store.activeDefinitions(UNCLASSIFIED_SUMMARY_VERSIONS)) {
            int count;
            try {
                count = UnclassifiedFields.of(storedProcess(definition)).size();
            } catch (RuntimeException e) {
                // A version whose source no longer reads is reported where it is used, not by this reminder
                continue;
            }
            if (count == 0) {
                continue;
            }
            versions++;
            total += count;
            if (named.size() < UNCLASSIFIED_SUMMARY_NAMED) {
                named.add(definition.tenantId() + "/" + definition.key() + " v" + definition.version() + ": " + count);
            }
        }
        if (versions == 0) {
            return java.util.Optional.empty();
        }
        String more = versions > named.size() ? " and " + (versions - named.size()) + " more versions" : "";
        return java.util.Optional.of("Process Engine: " + total + " agent step fields of " + versions + " active "
                + "definition versions are not classified (they declare neither mask true nor mask false): "
                + String.join(", ", named) + more + ". Masking stays opt-in, so these fields are kept in plain text. "
                + "Declare each one, or set horus.bpm.processes.classification.warn-unclassified=false to silence this "
                + "reminder (PD-146).");
    }

    @Override
    public DefinitionVersion version(String key, int version) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        if (!isKey(key) || version < 1) {
            throw new DefinitionNotFoundException("No definition " + safe(key) + " v" + version);
        }
        return transactions.inTransaction(() -> store.definitionVersion(tenant, key, version).map(ProcessServices::view)
                .orElseThrow(() -> new DefinitionNotFoundException("No definition " + key + " v" + version)));
    }

    @Override
    public io.github.dhluong90.bpm.process.api.GraphDiff diff(DefinitionRef base, DefinitionRef target) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (!base.key().equals(target.key())) {
            throw new InputInvalidException("A diff compares two versions of one definition key");
        }
        // Read-only (PRE-176): no transaction is opened and nothing is written; every read filters by tenant, so
        // another tenant's version is not found.
        StoredDefinition from = storedVersion(tenant, base);
        StoredDefinition to = storedVersion(tenant, target);
        return GraphDiffs.diff(to.key(), side(from), storedProcess(from), side(to), storedProcess(to));
    }

    @Override
    public io.github.dhluong90.bpm.process.api.GraphDiff diffFromEffective(DefinitionRef target) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        Objects.requireNonNull(target, "target must not be null");
        StoredDefinition to = storedVersion(tenant, target);
        StoredDefinition from = store.effectiveDefinition(tenant, target.key(), clock.instant())
                .orElseThrow(() -> new NoEffectiveVersionException("Definition " + target.key()
                        + " has no effective version to compare with; pass base"));
        return GraphDiffs.diff(to.key(), side(from), storedProcess(from), side(to), storedProcess(to));
    }

    /**
     * The change set an approval rests on (PRE-177, PRE-199, PD-139): the complete one against the effective version.
     * Within {@link GraphDiff#MAX_CHANGES} changed values the definition hash alone suffices, and a change-set hash,
     * when named, must match. Beyond it a reviewer can only have read the set page by page, so the hash of the whole set
     * is required: missing is {@code CHANGE_SET_TRUNCATED}, different is {@code CONTENT_CHANGED}. Nothing is written
     * before this check. A first version, or one approved while no version is effective, has no change set.
     */
    private void requireReviewedChangeSet(String tenant, StoredDefinition current, String expectedChangeSetHash) {
        java.util.Optional<StoredDefinition> effective = store.effectiveDefinition(tenant, current.key(),
                clock.instant());
        if (effective.isEmpty() || effective.get().version() == current.version()) {
            if (expectedChangeSetHash != null) {
                throw new io.github.dhluong90.bpm.process.api.ContentChangedException("There is no change set against "
                        + "an effective version of " + current.key() + " to approve with a change-set hash",
                        current.definitionHash(), current.lockVersion());
            }
            return;
        }
        StoredDefinition from = effective.get();
        io.github.dhluong90.bpm.process.api.GraphDiff all = GraphDiffs.complete(current.key(), side(from),
                storedProcess(from), side(current), storedProcess(current));
        boolean oversize = all.nodesChanged().size() > io.github.dhluong90.bpm.process.api.GraphDiff.MAX_CHANGES;
        if (oversize && expectedChangeSetHash == null) {
            throw new io.github.dhluong90.bpm.process.api.ChangeSetTruncatedException("The change list from "
                    + current.key() + " v" + from.version() + " to v" + current.version() + " has more than "
                    + io.github.dhluong90.bpm.process.api.GraphDiff.MAX_CHANGES + " changed values and is cut off; "
                    + "read it page by page and approve with expectedChangeSetHash");
        }
        if (expectedChangeSetHash != null) {
            String now = GraphDiffPaging.changeSetHash(all);
            if (!expectedChangeSetHash.equals(now)) {
                throw new io.github.dhluong90.bpm.process.api.ContentChangedException("The change set of "
                        + current.key() + " v" + current.version() + " against v" + from.version()
                        + " is not the one reviewed: expected change-set hash " + expectedChangeSetHash
                        + " but it is " + now, current.definitionHash(), current.lockVersion(), now);
            }
        }
    }

    @Override
    public io.github.dhluong90.bpm.process.api.GraphDiffPage diffPage(DefinitionRef base, DefinitionRef target,
            Integer limit, String cursor) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(target, "target must not be null");
        if (!base.key().equals(target.key())) {
            throw new InputInvalidException("A diff compares two versions of one definition key");
        }
        StoredDefinition from = storedVersion(tenant, base);
        StoredDefinition to = storedVersion(tenant, target);
        return GraphDiffPaging.page(GraphDiffs.complete(to.key(), side(from), storedProcess(from), side(to),
                storedProcess(to)), limit, cursor);
    }

    @Override
    public io.github.dhluong90.bpm.process.api.GraphDiffPage diffPageFromEffective(DefinitionRef target,
            Integer limit, String cursor) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        Objects.requireNonNull(target, "target must not be null");
        StoredDefinition to = storedVersion(tenant, target);
        StoredDefinition from = store.effectiveDefinition(tenant, target.key(), clock.instant())
                .orElseThrow(() -> new NoEffectiveVersionException("Definition " + target.key()
                        + " has no effective version to compare with; pass base"));
        return GraphDiffPaging.page(GraphDiffs.complete(to.key(), side(from), storedProcess(from), side(to),
                storedProcess(to)), limit, cursor);
    }

    private StoredDefinition storedVersion(String tenant, DefinitionRef ref) {
        if (!isKey(ref.key())) {
            throw new DefinitionNotFoundException("No definition " + safe(ref.key()) + " v" + ref.version());
        }
        return store.definitionVersion(tenant, ref.key(), ref.version()).orElseThrow(() ->
                new DefinitionNotFoundException("No definition " + ref.key() + " v" + ref.version() + " in this tenant"));
    }

    private static io.github.dhluong90.bpm.process.api.GraphDiff.Side side(StoredDefinition d) {
        return new io.github.dhluong90.bpm.process.api.GraphDiff.Side(d.version(), d.status(), d.definitionHash());
    }

    @Override
    public DefinitionExport exportDefinition(String key, int version, SourceFormat format) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        Function<Process, byte[]> writer = format == null ? null : writers.get(format);
        if (writer == null) {
            throw new InputInvalidException("Export format " + format + " is not supported; supported: "
                    + writers.keySet().stream().map(Enum::name).sorted().toList());
        }
        // Read-only: no transaction is opened and nothing is written (TR-TEN-01: the query filters by tenant, so
        // another tenant's version is not found).
        StoredDefinition definition = key == null ? null : store.definitionVersion(tenant, key, version).orElse(null);
        if (definition == null) {
            throw new DefinitionNotFoundException("No definition " + key + " version " + version + " in this tenant");
        }
        Process process = storedProcess(definition);
        String document = Utf8.strict(writer.apply(process));
        return new DefinitionExport(definition.key(), definition.version(), definition.definitionHash(), format,
                document);
    }

    // ---- Lists, served as ProcessQueryService through ProcessQueries ---------------------------------------

    /** {@link io.github.dhluong90.bpm.process.api.ProcessQueryService#definitions(PageRequest)}. */
    public Page<DefinitionSummary> definitions(PageRequest page) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        String after = cursors().verify(tenant, "", "definitions", request.cursor());
        if (after != null && !isKey(after)) {
            throw Cursors.invalid();
        }
        List<DefinitionSummary> rows = transactions.inTransaction(() -> store.definitionKeys(tenant, after, limit + 1,
                clock.instant()));
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        List<DefinitionSummary> items = rows.subList(0, limit);
        return new Page<>(items, cursors().sign(tenant, "", "definitions", items.get(limit - 1).key()));
    }

    /** {@link io.github.dhluong90.bpm.process.api.ProcessQueryService#pendingApprovals(PageRequest)}. */
    public Page<io.github.dhluong90.bpm.process.api.PendingApproval> pendingApprovals(PageRequest page) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_READ);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        // Verified in memory before any transaction: a forged cursor never reaches the database.
        String after = cursors().verify(tenant, "", "pending-approvals", request.cursor());
        Instant afterRequestedAt = null;
        String afterId = null;
        if (after != null) {
            int bar = after.lastIndexOf('|');
            try {
                afterRequestedAt = Instant.parse(after.substring(0, bar));
                afterId = after.substring(bar + 1);
            } catch (RuntimeException e) {
                throw Cursors.invalid();
            }
            if (!afterId.matches("[0-9A-HJKMNP-TV-Z]{26}")) {
                throw Cursors.invalid();
            }
        }
        Instant lastAt = afterRequestedAt;
        String lastId = afterId;
        java.util.Set<String> held = callers.currentPermissions();
        List<ProcessStore.PendingApprovalRow> rows = transactions.inTransaction(() -> store.pendingApprovals(tenant,
                lastAt, lastId, limit + 1));
        List<io.github.dhluong90.bpm.process.api.PendingApproval> items = rows.stream().limit(limit)
                .map(r -> pendingApproval(r, caller, held)).toList();
        if (rows.size() <= limit) {
            return new Page<>(items, null);
        }
        ProcessStore.PendingApprovalRow last = rows.get(limit - 1);
        return new Page<>(items, cursors().sign(tenant, "", "pending-approvals", last.requestedAt() + "|" + last.id()));
    }

    /**
     * What the caller may do with a pending version now, as {@code approve} and {@code reject} do: the requester never
     * approves or rejects, and under the default {@code approver-must-differ-from: makers} the importer or creator does
     * not approve either; with maker-checker off (dev profile only) a requester may approve. A hint: the commands
     * check again.
     */
    private io.github.dhluong90.bpm.process.api.PendingApproval pendingApproval(ProcessStore.PendingApprovalRow row,
            String caller, java.util.Set<String> held) {
        List<String> allowed = new ArrayList<>(2);
        Map<String, String> denied = new LinkedHashMap<>(2);
        String approve = io.github.dhluong90.bpm.process.api.PendingApproval.APPROVE;
        String reject = io.github.dhluong90.bpm.process.api.PendingApproval.REJECT;
        if (!held.contains(PERMISSION_APPROVE)) {
            denied.put(approve, io.github.dhluong90.bpm.process.api.PendingApproval.PERMISSION_REQUIRED);
            denied.put(reject, io.github.dhluong90.bpm.process.api.PendingApproval.PERMISSION_REQUIRED);
        } else {
            boolean requester = caller.equals(row.requestedBy());
            boolean self = requester || (settings.approverRule() == EngineSettings.ApproverRule.MAKERS
                    && caller.equals(row.createdBy()));
            if (self && settings.makerChecker()) {
                denied.put(approve, io.github.dhluong90.bpm.process.api.PendingApproval.SELF_APPROVAL_FORBIDDEN);
            } else {
                allowed.add(approve);
            }
            if (requester) {
                denied.put(reject, io.github.dhluong90.bpm.process.api.PendingApproval.SELF_APPROVAL_FORBIDDEN);
            } else {
                allowed.add(reject);
            }
        }
        return new io.github.dhluong90.bpm.process.api.PendingApproval(row.key(), row.name(), row.version(),
                row.definitionHash(), row.lockVersion(), row.requestedBy(), row.requestedAt(), allowed, denied,
                displayName(row.requestedBy()));
    }

    /** A user's readable name from the host (#442); a lookup that fails leaves the id to show. */
    private String displayName(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            return callers.displayNameOf(userId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link io.github.dhluong90.bpm.process.api.ProcessQueryService#instances(InstanceQuery)}. */
    public Page<InstanceSummary> instances(InstanceQuery query) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        InstanceQuery q = query == null ? InstanceQuery.running() : query;
        int limit = limit(q.page());
        List<String> statuses = q.statuses().isEmpty() ? List.of("RUNNING") : q.statuses().stream().sorted().toList();
        for (String status : statuses) {
            if (!INSTANCE_STATUSES.contains(status)) {
                throw new QueryInvalidException("status must be one or more of " + String.join(", ", INSTANCE_STATUSES));
            }
        }
        if (q.definitionKey() != null && !isKey(q.definitionKey())) {
            throw new QueryInvalidException("definitionKey is not a definition key");
        }
        Instant from = q.startedFrom();
        Instant to = q.startedTo();
        if (from != null && to == null) {
            to = from.plus(settings.maxRange());
        } else if (from == null && to != null) {
            from = to.minus(settings.maxRange());
        }
        if (from != null) {
            if (!to.isAfter(from)) {
                throw new QueryInvalidException("startedTo must be after startedFrom");
            }
            if (java.time.Duration.between(from, to).compareTo(settings.maxRange()) > 0) {
                throw new QueryInvalidException("The start-time range is longer than " + settings.maxRange()
                        + " (query.max-range)");
            }
        }
        // The cursor is bound to the filters it was issued for, so it cannot be replayed on another list.
        String scope = String.join(",", statuses) + "|" + (q.definitionKey() == null ? "" : q.definitionKey()) + "|"
                + (from == null ? "" : from) + "|" + (to == null ? "" : to);
        String after = cursors().verify(tenant, scope, "instances", q.page().cursor());
        Instant afterStartedAt = null;
        String afterId = null;
        if (after != null) {
            int bar = after.lastIndexOf('|');
            try {
                afterStartedAt = Instant.parse(after.substring(0, bar));
                afterId = after.substring(bar + 1);
            } catch (RuntimeException e) {
                throw Cursors.invalid();
            }
        }
        Instant lower = from;
        Instant upper = to;
        Instant afterAt = afterStartedAt;
        String afterInstance = afterId;
        List<InstanceRow> rows = transactions.inTransaction(() -> store.instances(tenant, statuses, q.definitionKey(),
                lower, upper, afterAt, afterInstance, limit + 1));
        List<InstanceSummary> items = rows.stream().limit(limit).map(r -> new InstanceSummary(r.id(), r.definitionKey(),
                r.definitionVersion(), r.businessKey(), r.status(), r.lockVersion(), r.startedBy(), r.startedAt(),
                r.endedAt())).toList();
        if (rows.size() <= limit) {
            return new Page<>(items, null);
        }
        InstanceSummary last = items.get(limit - 1);
        return new Page<>(items, cursors().sign(tenant, scope, "instances", last.startedAt() + "|" + last.id()));
    }

    /**
     * Creates or reads the cursor key in its own short transaction. The starter calls it once at start-up, after the
     * migrations; a list call that finds no key loads it the same way.
     */
    public void loadCursorKey() {
        byte[] candidate = new byte[32];
        RANDOM.nextBytes(candidate);
        byte[] key;
        try {
            key = transactions.inTransaction(() -> store.engineKey(CURSOR_KEY_NAME, candidate, clock.instant()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The Process Engine could not create or read its cursor key in "
                    + "pre_engine_key; run the engine migrations, and give the database user SELECT and INSERT on it", e);
        }
        cursors = new Cursors(key);
    }

    /**
     * The task list over a query store, sharing this engine's resolvers, clock, transactions, page limit and cursor
     * key (PRE-030).
     */
    TaskQueries taskQueries(io.github.dhluong90.bpm.process.internal.store.ProcessQueryStore queryStore) {
        return new TaskQueries(tenants, callers, clock, transactions, queryStore, this::cursors, settings.maxPageSize(),
                settings.maxRoles(), settings.dueSoonWindow());
    }

    private Cursors cursors() {
        Cursors held = cursors;
        if (held == null) {
            synchronized (this) {
                if (cursors == null) {
                    loadCursorKey();
                }
                held = cursors;
            }
        }
        return held;
    }

    private int limit(PageRequest page) {
        Integer limit = page.limit();
        if (limit == null) {
            return Math.min(EngineDefaults.DEFAULT_PAGE_SIZE, settings.maxPageSize());
        }
        if (limit < 1 || limit > settings.maxPageSize()) {
            throw new QueryInvalidException("limit must be 1 to " + settings.maxPageSize() + " (query.max-page-size)");
        }
        return limit;
    }

    private static boolean isKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && KEY.matcher(key).matches();
    }

    /** A value safe to echo in a message: bounded, no control characters. */
    private static String safe(String value) {
        if (value == null) {
            return "null";
        }
        String bounded = value.length() > 80 ? value.substring(0, 80) : value;
        return bounded.replaceAll("\\p{Cntrl}", "?");
    }

    /**
     * What an import checks against this host: agent steps' adapters and guard specs, and that decisions have a Rule
     * Engine. The handlers and adapters of service steps are checked only when the version is about to become active
     * (PRE-072), so a pipeline can import a draft before the host registers them; an import that activates at once
     * (dev auto-approve) checks them too.
     */
    private void checkBindings(Process process) {
        List<DefinitionProblem> problems = new ArrayList<>();
        if (settings.definitionsAutoApprove()) {
            problems.addAll(serviceRegistrationProblems(process));
        }
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof ServiceTask task && task.extensions().first(AgentTaskSpec.class).isPresent()) {
                agentBindings(task, problems);
            } else if (element instanceof BusinessRuleTask task && !invoker.hasRuleEngine()) {
                // ADR-0027: refused at activation, naming the step, so nothing fails at run time.
                problems.add(new DefinitionProblem("BPMN_NOT_EXECUTABLE", "businessRuleTask " + task.id()
                        + " needs the Rule Engine, which this host does not have", "BusinessRuleTask", task.id(), 0));
            }
        }
        // #101: an agent step's memory names upstream agent steps and their declared fields only (PRE-101, PRE-102).
        problems.addAll(AgentMemory.check(process, memoryLimits));
        if (!problems.isEmpty()) {
            throw new ValidationFailedException(problems);
        }
    }

    /**
     * PRE-072: every handler and adapter a service step names is registered in this host. Checked at request-approval
     * and approval, before anything is written; one problem per step, naming {@code step -> name}.
     */
    private void checkServiceRegistrations(String tenant, Process process) {
        List<DefinitionProblem> problems = serviceRegistrationProblems(process);
        problems.addAll(escalationRoleProblems(tenant, process));
        if (!problems.isEmpty()) {
            throw new ValidationFailedException(problems);
        }
    }

    /**
     * PRE-180 (#449): every {@code role:} an escalation level hands an overdue task to is in the tenant's role list.
     * One problem per step and role, naming {@code step -> role:NAME}.
     */
    private List<DefinitionProblem> escalationRoleProblems(String tenant, Process process) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (FlowElement element : process.allFlowElements()) {
            if (!(element instanceof UserTask task)) {
                continue;
            }
            java.util.Set<String> reported = new java.util.LinkedHashSet<>();
            for (var spec : task.extensions().first(
                    io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec.class).stream().toList()) {
                for (var level : spec.levels()) {
                    String to = level.to();
                    if (to.startsWith("role:") && reported.add(to) && !roleCatalogue.exists(tenant,
                            to.substring("role:".length()))) {
                        problems.add(DefinitionProblem.of("ESCALATION_ROLE_UNKNOWN", task.id() + " -> " + safe(to)
                                + ": the escalation names a role that is not in this tenant's role list", task.id()));
                    }
                }
            }
        }
        return problems;
    }

    private List<DefinitionProblem> serviceRegistrationProblems(Process process) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof ServiceTask task) {
                undoRegistrationProblem(task).ifPresent(problems::add);
            }
            if (!(element instanceof ServiceTask task) || task.extensions().first(AgentTaskSpec.class).isPresent()) {
                continue;
            }
            HandlerRef ref = task.extensions().first(HandlerRef.class).orElse(null);
            String handler = ref == null ? null : ref.handler();
            if (handler == null) {
                continue;
            }
            if ("remote".equals(ref.mode())) {
                if (!adapters.contains(handler)) {
                    problems.add(DefinitionProblem.of("ADAPTER_NOT_REGISTERED", task.id() + " -> " + safe(handler)
                            + ": no ServiceAdapter of this name is registered in this host", task.id()));
                }
            } else if (!invoker.hasHandler(handler)) {
                problems.add(DefinitionProblem.of("HANDLER_NOT_REGISTERED", task.id() + " -> " + safe(handler)
                        + ": no ServiceTaskHandler of this name is registered in this host", task.id()));
            }
        }
        return problems;
    }

    /**
     * #67: the undo a step names with {@code horus:compensate} is registered like a forward binding: a local step's
     * undo is a {@code ServiceTaskHandler}, a remote or agent step's undo a {@code ServiceAdapter}; {@code manual}
     * names nothing.
     */
    private java.util.Optional<DefinitionProblem> undoRegistrationProblem(ServiceTask task) {
        String undo = task.extensions().first(io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef
                .class).map(io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef::handler)
                .orElse(null);
        if (undo == null || MANUAL.equals(undo)) {
            return java.util.Optional.empty();
        }
        boolean remote = task.extensions().first(AgentTaskSpec.class).isPresent() || task.extensions()
                .first(HandlerRef.class).map(h -> "remote".equals(h.mode())).orElse(false);
        if (remote && !adapters.contains(undo)) {
            return java.util.Optional.of(DefinitionProblem.of("ADAPTER_NOT_REGISTERED", task.id() + " -> "
                    + safe(undo) + " (compensate): no ServiceAdapter of this name is registered in this host",
                    task.id()));
        }
        if (!remote && !invoker.hasHandler(undo)) {
            return java.util.Optional.of(DefinitionProblem.of("HANDLER_NOT_REGISTERED", task.id() + " -> "
                    + safe(undo) + " (compensate): no ServiceTaskHandler of this name is registered in this host",
                    task.id()));
        }
        return java.util.Optional.empty();
    }

    /**
     * The host side of an agent step's guard spec (#52); its shape was checked by the reader. The adapter must be
     * registered, the output schema must be one the host's validator can evaluate (the engine default refuses any
     * keyword outside its subset), and the guardrail needs the Rule Engine. Problems name the step and the rule, never
     * a schema value.
     */
    private void agentBindings(ServiceTask task, List<DefinitionProblem> problems) {
        AgentTaskSpec agent = task.extensions().first(AgentTaskSpec.class).orElseThrow();
        if (agent.agent() != null && !agents.contains(agent.agent())) {
            problems.add(DefinitionProblem.of("AGENT_NOT_REGISTERED", "No AgentAdapter named " + agent.agent()
                    + " is registered in this host", task.id()));
        }
        if (agent.outputSchema() != null) {
            List<String> schemaProblems = outputSchemas.checkSchema(Values.readMap(agent.outputSchema()));
            for (String problem : schemaProblems == null ? List.<String>of() : schemaProblems) {
                problems.add(DefinitionProblem.of("GUARD_SPEC_INVALID", "horus:outputSchema " + problem, task.id()));
            }
        }
        if (!invoker.hasRuleEngine()) {
            problems.add(new DefinitionProblem("BPMN_NOT_EXECUTABLE", "agentTask " + task.id() + " needs the Rule "
                    + "Engine for its guardrail, which this host does not have", "ServiceTask", task.id(), 0));
        }
    }

    /**
     * PRE-016: every agent step's guardrail rule set meets the guardrail contract ({@link GuardrailContract}), read in
     * the caller's tenant at the engine clock. Runs after {@link #checkBindings}, so a host without a decision engine
     * has already been refused. Problems name the step and the failed check; nothing has been written.
     *
     * @param tenant the tenant resolved from the authenticated context, or the start-up tenant
     * @param process the definition
     * @throws GuardrailContractInvalidException when any guardrail breaks the contract
     */
    private void checkGuardrailContracts(String tenant, Process process) {
        java.util.Optional<DecisionPort> port = invoker.decisionPort();
        if (port.isEmpty()) {
            return;
        }
        List<DefinitionProblem> problems = new ArrayList<>();
        Instant now = clock.instant();
        for (FlowElement element : process.allFlowElements()) {
            if (!(element instanceof ServiceTask task)) {
                continue;
            }
            AgentTaskSpec agent = task.extensions().first(AgentTaskSpec.class).orElse(null);
            GuardrailSpec guardrail = task.extensions().first(GuardrailSpec.class).orElse(null);
            if (agent == null || guardrail == null || guardrail.decision() == null) {
                continue;
            }
            DecisionSignature signature;
            try {
                signature = port.get().signature(tenant, guardrail.decision(), now);
            } catch (RuntimeException e) {
                // The class only: fail closed, the definition is refused.
                signature = new DecisionSignature.Unavailable(e.getClass().getSimpleName(), "");
            }
            if (signature == null) {
                signature = new DecisionSignature.Unavailable("SIGNATURE_UNAVAILABLE", "");
            }
            Map<String, Object> schema = agent.outputSchema() == null ? Map.of() : Values.readMap(agent.outputSchema());
            for (String failure : GuardrailContract.check(guardrail.decision(), signature, schema)) {
                problems.add(new DefinitionProblem(GuardrailContractInvalidException.CODE, failure, "ServiceTask",
                        task.id(), 0));
            }
        }
        if (!problems.isEmpty()) {
            throw new GuardrailContractInvalidException(problems);
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
            run(definition, row, state, new Command.Start(request.variables(), caller), RunAs.REQUEST);
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
     * The decision port this engine evaluates through, for the offline replay of agent answers (#98), which must use
     * the same port: the starter's port claims the Rule Engine's tenant-scoped entry point once.
     *
     * @return the decision port, empty without a Rule Engine
     */
    public java.util.Optional<DecisionPort> decisionPort() {
        return invoker.decisionPort();
    }

    /**
     * The job handlers of this engine, by job kind, for the {@link JobExecutor} of each replica.
     *
     * @return the handlers
     */
    public Map<String, JobHandler> jobHandlers() {
        return Map.of(JobStore.TIMER, this::fireTimer, JobStore.DEFINITION_EFFECTIVE, this::fireDefinitionEffective,
                JobStore.CALL_RESULT, this::fireCallResult);
    }

    /**
     * Fires a timer inside the fire transaction: locks the instance row, then checks the claim, then delivers
     * {@link Command.JobDue} to the waiting token. There is no caller: the run acts for the job's tenant as
     * {@code system:timer}, and a decision step that follows is evaluated in the instance's tenant as the engine
     * (ADR-0027). A timer whose instance ended or whose token moved on is consumed without firing.
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
        // The instance's tenant is the stored row's; the job's tenant is what this firing is allowed to act for.
        InstanceState state = new InstanceState(row.id(), row.tenantId(), row.definitionId(), row.businessKey(),
                InstanceStatus.valueOf(row.status()), tokens, Values.readMap(row.variablesJson()),
                participants(tenant, row), row.sequence());
        try (io.github.dhluong90.bpm.process.spi.EventScope ignored = jobScope(job.id())) {
            run(definition, row, state, new Command.JobDue(job.id(), job.tokenId(), job.jobKind()),
                    new RunAs.EngineWork(tenant, TIMER_ACTOR));
        }
        Duration lag = job.nextFireAt() == null ? Duration.ZERO : Duration.between(job.nextFireAt(), clock.instant());
        Duration reported = lag.isNegative() ? Duration.ZERO : lag;
        transactions.afterCommit(() -> metrics.timerLag(reported));
        return true;
    }

    // ---- async steps (ADR-0008) ------------------------------------------------------------------------------

    /** Error code recorded when a job is claimed more often than {@code jobs.max-attempts} allows. */
    static final String MAX_ATTEMPTS_REACHED = "MAX_ATTEMPTS_REACHED";

    /**
     * The attempt id of one claim of an async job: derived from the job id and the attempt number, so every claim
     * has a new one (TR-ASY-02) and a result can be matched to its claim. A correlation id and the history key, never
     * a credential: a completion from outside must also present the attempt's completion token.
     *
     * @param jobId the job id
     * @param attempt the attempt number, from 1
     * @return the attempt id, 26 characters
     */
    public static String attemptId(String jobId, int attempt) {
        return Ids.child(jobId, attempt);
    }

    /**
     * Issues the completion token of a claim, inside the claim transaction: a new random token whose hash is stored on
     * the job row. The token is returned once, for the adapter call, and is never stored or logged.
     *
     * @param job a job this transaction just claimed
     * @return the token
     */
    String issueCompletionToken(JobRow job) {
        String token = CompletionTokens.issue();
        if (!jobs.issueCompletionToken(job.tenantId(), job.id(), job.attempt(), CompletionTokens.hash(token))) {
            throw new IllegalStateException("Job " + job.id() + " lost its claim inside its claim transaction");
        }
        return token;
    }

    @Override
    public StepCompletion complete(String jobId, String attemptId, String completionToken, AdapterResult result) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_STEP_COMPLETE);
        if (jobId == null || jobId.isBlank() || jobId.length() > 26 || attemptId == null || attemptId.isBlank()
                || attemptId.length() > 26) {
            throw new InputInvalidException("jobId and attemptId are required, at most 26 characters");
        }
        if (completionToken == null || completionToken.isBlank() || completionToken.length() > CompletionTokens.MAX_LENGTH) {
            throw new InputInvalidException("completionToken is required, at most " + CompletionTokens.MAX_LENGTH
                    + " characters");
        }
        Objects.requireNonNull(result, "result must not be null");
        StepResult step = switch (result) {
            case AdapterResult.Completed c -> {
                boundedJson(c.output(), "output");
                yield AnswerLabels.step(c);
            }
            case AdapterResult.BusinessError e -> new StepResult.BusinessError(e.code(), e.message());
            case AdapterResult.Failed f -> new StepResult.Failed(f.errorCode(), f.detail(), f.retryable());
            case AdapterResult.Accepted a -> throw new InputInvalidException("A completion carries a result, not Accepted");
        };
        // The adapter scope never changes for a job, so it is checked before any lock is taken.
        JobRow peek = jobs.findJob(tenant, jobId).filter(j -> JobStore.isAsync(j.jobKind()))
                .orElseThrow(() -> new AsyncStepNotFoundException("No async step " + jobId));
        String[] kindOut = ATTEMPT_KIND.get();
        if (kindOut != null) {
            kindOut[0] = peek.jobKind();
        }
        if (!async.mayComplete(target(peek), callers.currentRoles())) {
            throw new PermissionDeniedException("Not a completer of this step's adapter (adapters.completer-roles)");
        }
        return new StepCompletion(jobId, attemptId, recordAttempt(tenant, jobId, attemptId,
                new Presented(completionToken), step, caller));
    }

    /** The adapter name a job calls. */
    private static String target(JobRow job) {
        Object target = Values.readMap(job.payloadJson()).get("target");
        return target == null ? "" : String.valueOf(target);
    }

    /** The completion token a caller of the completion API presented; null for the engine's own executor. */
    private record Presented(String token) {
        @Override
        public String toString() {
            return "Presented[***]";
        }
    }

    /**
     * The result transaction (TX2) of an async step attempt, for the async executor. See
     * {@link #recordAttempt(String, String, String, Presented, StepResult, String)}.
     *
     * @param tenant the job's tenant
     * @param jobId the job
     * @param attemptId the attempt the result is for
     * @param result the result
     * @param reportedBy who reported the result, recorded in history
     * @return the outcome
     * @throws AsyncStepNotFoundException when no async job has this id in the tenant; nothing is written
     */
    public StepCompletion.Outcome recordAttempt(String tenant, String jobId, String attemptId, StepResult result,
            String reportedBy) {
        return recordAttempt(tenant, jobId, attemptId, null, result, reportedBy);
    }

    /**
     * The result transaction (TX2) of an async step attempt, for the async executor and for {@link #complete}. Locks
     * the instance row, then the job row (the order of every other writer). A caller of the completion API must
     * present the completion token of the job's latest claim when it quotes that claim's attempt id; a wrong token is
     * refused and writes nothing. The result is recorded only while the attempt is current: job {@code CLAIMED} under
     * that attempt with an unexpired lease. A completed result advances the token; a failed one gives the job back for
     * a retry with a new attempt id, or fails it. Otherwise nothing in the process changes: a repeat of a completed
     * attempt is {@code DUPLICATE}, a result after the lease ended or after a takeover is {@code DISCARDED} with
     * nothing written, and a result for a cancelled job is {@code DISCARDED} with one {@code LATE_RESULT_DISCARDED}
     * history entry per job, however often it is repeated (DN-10, PRE-041).
     */
    private StepCompletion.Outcome recordAttempt(String tenant, String jobId, String attemptId, Presented presented,
            StepResult result, String reportedBy) {
        String[] kind = new String[1];
        String[] outer = ATTEMPT_KIND.get() == null ? null : ATTEMPT_KIND.get();
        ATTEMPT_KIND.set(kind);
        StepCompletion.Outcome outcome;
        try (io.github.dhluong90.bpm.process.spi.EventScope ignored = jobScope(jobId)) {
            outcome = recordAttemptCore(tenant, jobId, attemptId, presented, result, reportedBy);
        } finally {
            if (outer == null) {
                ATTEMPT_KIND.remove();
            } else {
                ATTEMPT_KIND.set(outer);
            }
        }
        if (kind[0] != null) {
            // A failed or business-error result that the retry policy accepted is counted as FAILED (PRE-084).
            String label = outcome == StepCompletion.Outcome.ACCEPTED && !(result instanceof StepResult.Completed)
                    ? "FAILED" : outcome.name();
            String jobKind = kind[0];
            transactions.afterCommit(() -> metrics.asyncAttempt(jobKind, label));
        }
        return outcome;
    }

    private StepCompletion.Outcome recordAttemptCore(String tenant, String jobId, String attemptId,
            Presented presented, StepResult result, String reportedBy) {
        Objects.requireNonNull(tenant, "tenant must not be null");
        try {
            return transactions.inTransaction(() -> recordInTransaction(tenant, jobId, attemptId, presented, result,
                    reportedBy));
        } catch (RefusalNotRouted failed) {
            // The review task could not be created and everything rolled back, the refusal record with it. A refused
            // result is never retried as if nothing happened: record the refusal again and fail the job (#363).
            return transactions.inTransaction(() -> {
                JobRow job = jobs.lockJob(tenant, jobId).orElseThrow(() -> new AsyncStepNotFoundException(
                        "No async step " + jobId));
                if (!current(job, attemptId)) {
                    return StepCompletion.Outcome.DISCARDED;
                }
                Instant now = clock.instant();
                Map<String, Object> details = new LinkedHashMap<>(failed.details);
                details.put("route", "incident");
                details.put("routeError", failed.code);
                store.appendHistory(tenant, job.instanceId(), job.definitionId(), List.of(new HistoryEntry(
                        "ASYNC_RESULT_REFUSED", job.nodeId(), job.tokenId(), attemptId, reportedBy, now, details)));
                if (!jobs.release(tenant, job.id(), job.lockedBy(), job.attempt(), job.nextFireAt(), true, now)) {
                    throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
                }
                raiseIncident(job, String.valueOf(details.get("code")), "The answer was refused ("
                        + details.get("code") + ") and its review task could not be created (" + failed.code + ")", now);
                LOG.log(System.Logger.Level.WARNING, "process.async.result.refused tenant={0} job={1} attempt={2} code={3}"
                        + " route=incident routeError={4}", tenant, job.id(), job.attempt(), details.get("code"),
                        failed.code);
                return StepCompletion.Outcome.REFUSED;
            });
        } catch (AttemptRunFailed failed) {
            // The step's continuation failed and rolled back; give the attempt back as a failed one.
            String code = runFailureCode(failed);
            return transactions.inTransaction(() -> {
                jobs.findJob(tenant, jobId).filter(j -> j.instanceId() != null)
                        .ifPresent(j -> store.lockInstance(tenant, j.instanceId()));
                JobRow job = jobs.lockJob(tenant, jobId).orElseThrow(() -> new AsyncStepNotFoundException(
                        "No async step " + jobId));
                if (!current(job, attemptId)) {
                    return StepCompletion.Outcome.DISCARDED;
                }
                giveBack(job, attemptId, code, true, false, reportedBy);
                return StepCompletion.Outcome.ACCEPTED;
            });
        }
    }

    /** Wraps a failure of the run that a completed result starts, so the result transaction can give the job back. */
    private static class AttemptRunFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AttemptRunFailed(RuntimeException cause) {
            super(cause);
        }
    }

    /** The review task of a refused result could not be created; carries the refusal record to write again. */
    private static final class RefusalNotRouted extends AttemptRunFailed {
        private static final long serialVersionUID = 1L;
        private final transient Map<String, Object> details;
        private final String code;

        RefusalNotRouted(RuntimeException cause, Map<String, Object> details) {
            super(cause);
            this.details = Map.copyOf(details);
            this.code = runFailureCode(this);
        }
    }

    private static String runFailureCode(AttemptRunFailed failed) {
        RuntimeException cause = failed.getCause() instanceof RuntimeException r ? r : failed;
        return switch (cause) {
            case StepFailedException step -> step.reason();
            case io.github.dhluong90.bpm.process.api.ProcessException process -> process.code();
            default -> "STEP_RUN_FAILED";
        };
    }

    private StepCompletion.Outcome recordInTransaction(String tenant, String jobId, String attemptId, Presented presented,
            StepResult result, String reportedBy) {
        JobRow peek = jobs.findJob(tenant, jobId).filter(j -> JobStore.isAsync(j.jobKind()))
                .orElseThrow(() -> new AsyncStepNotFoundException("No async step " + jobId));
        InstanceRow row = store.lockInstance(tenant, peek.instanceId()).orElse(null);
        JobRow job = jobs.lockJob(tenant, jobId).orElseThrow(() -> new AsyncStepNotFoundException("No async step " + jobId));
        boolean sameAttempt = job.attempt() > 0 && attemptId.equals(attemptId(job.id(), job.attempt()));
        if (sameAttempt && presented != null
                && !CompletionTokens.matches(presented.token(), jobs.completionTokenHash(tenant, jobId).orElse(null))) {
            // The attempt id is derived from public ids; only the token proves the caller was handed this attempt.
            LOG.log(System.Logger.Level.WARNING, "process.async.token.refused tenant={0} job={1} attempt={2}", tenant,
                    job.id(), job.attempt());
            throw new PermissionDeniedException("The completion token does not match the attempt");
        }
        if ("DONE".equals(job.status()) && sameAttempt) {
            return StepCompletion.Outcome.DUPLICATE;
        }
        if ("CANCELLED".equals(job.status()) && row != null && sameAttempt) {
            if (jobs.markLateResult(tenant, job.id(), clock.instant())) {
                store.appendHistory(tenant, row.id(), job.definitionId(), List.of(new HistoryEntry(
                        "LATE_RESULT_DISCARDED", job.nodeId(), job.tokenId(), attemptId, reportedBy, clock.instant(),
                        Map.of("jobId", job.id(), "result", kind(result)))));
                LOG.log(System.Logger.Level.INFO, "process.async.late-result tenant={0} job={1} reason=cancelled", tenant,
                        job.id());
            } else {
                LOG.log(System.Logger.Level.DEBUG, "process.async.late-result tenant={0} job={1} reason=cancelled"
                        + " repeat", tenant, job.id());
            }
            return StepCompletion.Outcome.DISCARDED;
        }
        if (!current(job, attemptId)) {
            // Lease ended, another attempt owns the step, or the job is no longer open: no state change (TR-ASY-02).
            LOG.log(System.Logger.Level.INFO, "process.async.late-result tenant={0} job={1} status={2} attempt={3}"
                    + " sameAttempt={4}", tenant, job.id(), job.status(), job.attempt(), sameAttempt);
            return StepCompletion.Outcome.DISCARDED;
        }
        if (JobStore.COMPENSATION.equals(job.jobKind())) {
            return recordUndo(row, job, attemptId, result, reportedBy);
        }
        if (row == null || !"RUNNING".equals(row.status())) {
            jobs.cancelJob(tenant, job.id(), clock.instant());
            return StepCompletion.Outcome.DISCARDED;
        }
        List<io.github.dhluong90.bpm.process.internal.exec.Token> tokens = store.tokens(tenant, row.id());
        boolean waiting = tokens.stream().anyMatch(t -> t.id().equals(job.tokenId())
                && t.status() == TokenStatus.WAITING_ASYNC && job.id().equals(t.activityInstanceId()));
        if (!waiting) {
            jobs.cancelJob(tenant, job.id(), clock.instant());
            return StepCompletion.Outcome.DISCARDED;
        }
        switch (result) {
            case StepResult.Completed completed -> {
                ResultGuard kindGuard = guards.getOrDefault(job.jobKind(), ResultGuard.NONE);
                String labelsBroken = kindGuard == ResultGuard.NONE ? null : labelLimits.violation(completed.labels());
                if (labelsBroken != null) {
                    // Labels above the bounds (#59): a failed attempt, retried like any other; never recorded.
                    LOG.log(System.Logger.Level.WARNING, "process.async.labels.invalid tenant={0} job={1} attempt={2}"
                            + " reason={3}", tenant, job.id(), job.attempt(), labelsBroken);
                    giveBack(job, attemptId, AgentLabelLimits.LABELS_INVALID, true, false, reportedBy);
                    return StepCompletion.Outcome.ACCEPTED;
                }
                // The only authoritative check of a result, whichever path reported it (#363).
                ResultGuard.Verdict verdict = guard(row, job, attemptId, completed);
                if (verdict instanceof ResultGuard.Verdict.Invalid invalid) {
                    int allowed = async.runner(job.jobKind(), jobSettings).schemaRetries();
                    int used = schemaRetriesUsed(job);
                    if (used < allowed) {
                        // Not final: the step gets a fresh attempt, so a :complete caller reads ACCEPTED (#363).
                        retrySchema(job, attemptId, invalid, used + 1, allowed, reportedBy);
                        return StepCompletion.Outcome.ACCEPTED;
                    }
                    verdict = invalid.exhausted();
                }
                StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
                InstanceState state = new InstanceState(row.id(), row.tenantId(), row.definitionId(), row.businessKey(),
                        InstanceStatus.valueOf(row.status()), tokens, Values.readMap(row.variablesJson()),
                        participants(tenant, row), row.sequence());
                if (verdict instanceof ResultGuard.Verdict.Refused refused) {
                    refuse(definition, row, state, job, attemptId, refused, completed, reportedBy);
                    return StepCompletion.Outcome.REFUSED;
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("jobId", job.id());
                details.put("attempt", job.attempt());
                if (verdict instanceof ResultGuard.Verdict.Allow allow && allow.decisionId() != null) {
                    details.put("guardrailDecisionId", allow.decisionId());
                    details.put("ruleIds", allow.ruleIds());
                }
                if (kindGuard != ResultGuard.NONE) {
                    // An accepted agent answer is history too: masked by the same policy as a refused one (#52), with
                    // the masked inputs, the latency and the labels (#59).
                    Map<String, Object> output = completed.output() == null ? Map.of() : completed.output();
                    details.put("output", kindGuard.auditOutput(Values.readMap(job.payloadJson()), output));
                    details.put("outputFields", output.size());
                    answerFacts(job, completed, details);
                }
                try {
                    store.appendHistory(tenant, row.id(), job.definitionId(), List.of(new HistoryEntry(
                            "ASYNC_RESULT_ACCEPTED", job.nodeId(), job.tokenId(), attemptId, reportedBy, clock.instant(),
                            details)));
                    run(definition, row, state, new Command.AsyncResult(job.tokenId(), job.id(), completed),
                            new RunAs.EngineWork(tenant, ASYNC_ACTOR));
                } catch (RuntimeException e) {
                    throw new AttemptRunFailed(e);
                }
                if (!jobs.markDone(tenant, job.id(), job.lockedBy(), job.attempt(), clock.instant())) {
                    throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
                }
                closeRetried(tenant, row.id(), job.tokenId(), clock.instant());
            }
            case StepResult.BusinessError error -> giveBack(job, attemptId, error.code(), false, true, reportedBy);
            case StepResult.Failed failed -> giveBack(job, attemptId, failed.errorCode(), failed.retryable(), false,
                    reportedBy);
        }
        return StepCompletion.Outcome.ACCEPTED;
    }

    /**
     * Runs the result check of the job's kind, in the result transaction, under the runner's {@code guard-budget}.
     * The tenant is the locked instance row's. Anything the guard throws refuses the result: the check fails closed,
     * and the step's job fails because no reviewer can be read from a guard that broke.
     */
    private ResultGuard.Verdict guard(InstanceRow row, JobRow job, String attemptId, StepResult.Completed completed) {
        ResultGuard guard = guards.getOrDefault(job.jobKind(), ResultGuard.NONE);
        if (guard == ResultGuard.NONE) {
            return new ResultGuard.Verdict.Allow(null, List.of());
        }
        try {
            ResultGuard.Verdict verdict = guard.check(new ResultGuard.Request(row.tenantId(), row.id(), job.nodeId(),
                    attemptId, job.jobKind(), Values.readMap(job.payloadJson()), completed.output(),
                    new DecisionCaller.Engine(ASYNC_ACTOR)),
                    async.runner(job.jobKind(), jobSettings).guardBudget());
            return verdict == null ? failClosed("GUARD_NULL_VERDICT") : verdict;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "process.async.guard.error tenant={0} job={1} exception={2}",
                    row.tenantId(), job.id(), e.getClass().getName());
            return failClosed(e.getClass().getSimpleName());
        }
    }

    private static ResultGuard.Verdict failClosed(String reason) {
        return new ResultGuard.Verdict.Refused(AgentResultGuard.EVALUATION_ERROR, List.of(reason), List.of(), null,
                ResultGuard.Route.FAIL, null);
    }

    /**
     * A refused result (#363): nothing of it is accepted. The refusal is recorded with the refused output, masked by
     * the guard's policy and bounded (DESIGN section 4.1, for the reviewer and for offline evaluation), then the step
     * goes to a four-eyes review task and the job is {@code DONE}, or the job is {@code FAILED} and the token stays on
     * the step for an incident (#68). When the review task cannot be created, {@link RefusalNotRouted} makes the
     * caller record the refusal again and fail the job: a refusal is never lost and never becomes a retry.
     */
    private void refuse(StoredDefinition definition, InstanceRow row, InstanceState state, JobRow job,
            String attemptId, ResultGuard.Verdict.Refused refused, StepResult.Completed completed, String reportedBy) {
        String tenant = row.tenantId();
        Instant now = clock.instant();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.id());
        details.put("attempt", job.attempt());
        details.put("code", refused.code());
        details.put("reasons", refused.reasons());
        details.put("ruleIds", refused.ruleIds());
        if (refused.decisionId() != null) {
            details.put("guardrailDecisionId", refused.decisionId());
        }
        details.put("route", refused.route() == ResultGuard.Route.REVIEW ? "review" : "incident");
        Map<String, Object> output = completed.output() == null ? Map.of() : completed.output();
        details.put("output", guards.getOrDefault(job.jobKind(), ResultGuard.NONE)
                .auditOutput(Values.readMap(job.payloadJson()), output));
        details.put("outputFields", output.size());
        answerFacts(job, completed, details);
        store.appendHistory(tenant, row.id(), job.definitionId(), List.of(new HistoryEntry("ASYNC_RESULT_REFUSED",
                job.nodeId(), job.tokenId(), attemptId, reportedBy, now, details)));
        LOG.log(System.Logger.Level.WARNING, "process.async.result.refused tenant={0} job={1} attempt={2} code={3}"
                + " route={4}", tenant, job.id(), job.attempt(), refused.code(), details.get("route"));
        if (refused.route() == ResultGuard.Route.REVIEW) {
            try {
                // Always four eyes: the reviewer is never the reporter of the result (participants) or the starter.
                run(definition, row, state, new Command.RouteToReview(job.tokenId(), job.id(), refused.reviewer(),
                        true, refused.code(), refused.reasons(), reviewContext(job, attemptId, reportedBy, details)),
                        new RunAs.EngineWork(tenant, ASYNC_ACTOR));
            } catch (RuntimeException e) {
                throw new RefusalNotRouted(e, details);
            }
            if (!jobs.markDone(tenant, job.id(), job.lockedBy(), job.attempt(), now)) {
                throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
            }
            closeRetried(tenant, row.id(), job.tokenId(), now);
            return;
        }
        if (!jobs.release(tenant, job.id(), job.lockedBy(), job.attempt(), job.nextFireAt(), true, now)) {
            throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
        }
        raiseIncident(job, refused.code(), "The answer was refused (" + refused.code() + ") and the step has no review",
                now);
    }

    /**
     * What the history of an agent answer keeps besides the masked output (#59): the inputs the agent saw, masked
     * inputs as keyed fingerprints; the adapter latency, from the claim to the result on the engine clock; and the
     * labels as the agent reported them, which the engine never acts on.
     */
    private void answerFacts(JobRow job, StepResult.Completed completed, Map<String, Object> details) {
        details.put("inputs", auditInputs(job));
        // #101: the memory the agent saw, masked fields fingerprinted, so the trace of the answer is complete.
        if (Values.readMap(job.payloadJson()).get(AUDIT_MEMORY) instanceof Map<?, ?> memory) {
            details.put(MEMORY, memory);
        }
        Instant claimedAt = job.lockedUntil() == null ? null : job.lockedUntil().minus(job.leaseDuration());
        if (claimedAt != null) {
            details.put("latencyMs", Math.max(0, Duration.between(claimedAt, clock.instant()).toMillis()));
        }
        if (!completed.labels().isEmpty()) {
            details.put("labels", completed.labels());
        }
    }

    /** The audit copy of a job's inputs; a job parked before #59 has none and declared no masks. */
    private static Object auditInputs(JobRow job) {
        Map<String, Object> payload = Values.readMap(job.payloadJson());
        Object audit = payload.get(AUDIT_INPUTS);
        if (audit instanceof Map<?, ?>) {
            return audit;
        }
        Object inputs = payload.get("inputs");
        return inputs instanceof Map<?, ?> ? inputs : Map.of();
    }

    /**
     * Opens an incident on the step of a job that has just failed for good (#58), in the transaction that failed it:
     * one {@code OPEN} row, an {@code INCIDENT_RAISED} history entry and {@code processes.incident.raised.v1}. The
     * token stays on the step. The summary is built from codes only, never from an adapter's detail.
     */
    private void raiseIncident(JobRow job, String errorCode, String summary, Instant now) {
        raiseIncident(job, errorCode, null, summary, now);
    }

    private void raiseIncident(JobRow job, String errorCode, String businessCode, String summary, Instant now) {
        if (job.instanceId() == null) {
            return;
        }
        String cause = businessCode != null ? businessCode : errorCode;
        if (JobStore.COMPENSATION.equals(job.jobKind())) {
            undoFailed(job, cause, now);
            return;
        }
        if (compensateInstead(job, cause)) {
            return;
        }
        raiseIncident(new IncidentSite(job.tenantId(), job.instanceId(), job.definitionId(), job.tokenId(),
                job.nodeId(), job.id(), attemptId(job.id(), Math.max(1, job.attempt())), Math.max(0, job.attempt() - 1),
                errorCode, businessCode, summary), now);
    }

    /**
     * Where an incident opens and why: a job that failed for good (#58), an agent step whose memory failed before any
     * call (#101), or a local step whose handler returned a business error with no path in the process (PRE-040),
     * which has no job.
     *
     * @param jobId the failed job, or null for a local step
     * @param attemptId the attempt of the step, the history key
     * @param retries attempts after the first
     * @param businessCode the business error's code, or null
     * @param summary built from codes and numbers only, never from a handler's or an adapter's detail
     */
    private record IncidentSite(String tenantId, String instanceId, String definitionId, String tokenId, String nodeId,
            String jobId, String attemptId, int retries, String errorCode, String businessCode, String summary) {
    }

    /**
     * Opens one {@code OPEN} incident in the current transaction, with {@code INCIDENT_RAISED} in history and
     * {@code processes.incident.raised.v1}. The token stays on the step.
     */
    private String raiseIncident(IncidentSite site, Instant now) {
        InstanceRow row = store.findInstance(site.tenantId(), site.instanceId()).orElse(null);
        if (row == null) {
            return null;
        }
        String code = site.errorCode() == null || site.errorCode().isBlank() ? "STEP_FAILED" : site.errorCode();
        String cause = site.summary().length() > 500 ? site.summary().substring(0, 500) : site.summary();
        // PRE-077: a retried step that fails again keeps its incident open with the new cause, never a second one.
        ProcessStore.IncidentRow again = site.tokenId() == null ? null : store.openIncidents(site.tenantId(),
                site.instanceId()).stream().filter(i -> site.tokenId().equals(i.tokenId())
                        && io.github.dhluong90.bpm.process.api.Incident.OPEN.equals(i.status())).findFirst()
                .orElse(null);
        String id;
        long eventSequence;
        int retries;
        if (again == null) {
            id = Ulid.next(now);
            retries = site.retries();
            eventSequence = 1;
            store.insertIncident(new ProcessStore.IncidentRow(site.tenantId(), id, site.instanceId(),
                    site.definitionId(), site.tokenId(), site.nodeId(), code, cause, retries, "OPEN", 0, now, null));
        } else {
            ProcessStore.IncidentRow failed = again.failedAgain(code, cause);
            writeIncident(failed);
            id = failed.id();
            retries = failed.retryCount();
            eventSequence = failed.lockVersion() + 1;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incidentId", id);
        if (again != null) {
            details.put("failedAgain", true);
        }
        if (site.jobId() != null) {
            details.put("jobId", site.jobId());
        }
        details.put("errorCode", code);
        if (site.businessCode() != null) {
            details.put("businessCode", site.businessCode());
        }
        details.put("retryCount", retries);
        store.appendHistory(site.tenantId(), site.instanceId(), site.definitionId(), List.of(new HistoryEntry(
                INCIDENT_RAISED, site.nodeId(), site.tokenId(), site.attemptId(),
                site.jobId() == null ? DecisionCaller.ENGINE_IDENTITY : ASYNC_ACTOR, now, details)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", id);
        payload.put("instanceId", row.id());
        payload.put("nodeId", site.nodeId());
        payload.put("errorCode", code);
        if (site.businessCode() != null) {
            payload.put("businessCode", site.businessCode());
        }
        payload.put("retryCount", retries);
        payload.put("definitionKey", row.definitionKey());
        payload.put("version", row.definitionVersion());
        if (row.businessKey() != null) {
            payload.put("businessKey", row.businessKey());
        }
        publish(new ProcessEventPublisher.Event(Ids.child(id, eventSequence),
                "processes.incident.raised.v1", site.tenantId(), now, payload), row.id());
        LOG.log(System.Logger.Level.WARNING, "process.incident.raised tenant={0} instance={1} node={2} code={3}"
                + " retryCount={4}", site.tenantId(), site.instanceId(), site.nodeId(), code, retries);
        return id;
    }

    /**
     * Opens an incident on an instance that a migration could not move (#90), in the current transaction: one
     * {@code OPEN} row, {@code INCIDENT_RAISED} in history and {@code processes.incident.raised.v1}. The instance stays
     * on its version and its token on its step. The summary is built from codes and step ids only.
     *
     * @return the incident id, or null when the instance no longer exists
     */
    String raiseMigrationIncident(String tenant, String instanceId, String definitionId, String tokenId, String nodeId,
            String errorCode, String summary, Instant now) {
        return raiseIncident(new IncidentSite(tenant, instanceId, definitionId, tokenId, nodeId, null, null, 0,
                errorCode, null, summary), now);
    }

    @Override
    public List<io.github.dhluong90.bpm.process.api.Incident> openIncidents(String instanceId) {
        String tenant = tenant();
        callerWith(PERMISSION_READ);
        return transactions.inTransaction(() -> {
            store.findInstance(tenant, instanceId).orElseThrow(() -> new InstanceNotFoundException("No instance "
                    + instanceId));
            String caller = callers.currentCallerId();
            java.util.Set<String> held = callers.currentPermissions();
            return store.openIncidents(tenant, instanceId).stream().map(i -> incidentView(i, null, caller, held))
                    .toList();
        });
    }

    // ---- IncidentService: retry and resolve (#68, PD-126, DESIGN section 3.6) -------------------------------

    @Override
    public Page<io.github.dhluong90.bpm.process.api.Incident> incidents(
            io.github.dhluong90.bpm.process.api.IncidentQuery query) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_READ);
        io.github.dhluong90.bpm.process.api.IncidentQuery q = query == null
                ? io.github.dhluong90.bpm.process.api.IncidentQuery.open() : query;
        if (!INCIDENT_STATUSES.contains(q.status())) {
            throw new QueryInvalidException("status must be one of " + String.join(", ", INCIDENT_STATUSES));
        }
        int limit = limit(q.page());
        // The cursor is bound to the tenant and the status it was issued for.
        String after = cursors().verify(tenant, q.status(), "incidents", q.page().cursor());
        Instant afterCreatedAt = null;
        String afterId = null;
        if (after != null) {
            int bar = after.lastIndexOf('|');
            try {
                afterCreatedAt = Instant.parse(after.substring(0, bar));
                afterId = after.substring(bar + 1);
            } catch (RuntimeException e) {
                throw Cursors.invalid();
            }
        }
        Instant at = afterCreatedAt;
        String id = afterId;
        List<ProcessStore.IncidentRow> rows = transactions.inTransaction(() -> store.incidents(tenant, q.status(), at,
                id, limit + 1));
        java.util.Set<String> held = callers.currentPermissions();
        List<io.github.dhluong90.bpm.process.api.Incident> items = rows.stream().limit(limit)
                .map(r -> incidentView(r, null, caller, held)).toList();
        if (rows.size() <= limit) {
            return new Page<>(items, null);
        }
        io.github.dhluong90.bpm.process.api.Incident last = items.get(limit - 1);
        return new Page<>(items, cursors().sign(tenant, q.status(), "incidents", last.createdAt() + "|" + last.id()));
    }

    @Override
    public io.github.dhluong90.bpm.process.api.Incident incident(String incidentId) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_READ);
        requireIncidentId(incidentId);
        return transactions.inTransaction(() -> {
            ProcessStore.IncidentRow row = store.findIncident(tenant, incidentId)
                    .orElseThrow(() -> incidentNotFound(incidentId));
            return incidentView(row, jobOf(tenant, row).orElse(null), caller, callers.currentPermissions());
        });
    }

    @Override
    public io.github.dhluong90.bpm.process.api.Incident retry(String incidentId, long expectedLockVersion,
            String reason, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_INCIDENT_MANAGE);
        requireIncidentId(incidentId);
        requireKey(idempotencyKey, "Retrying an incident");
        String why = requiredReason(reason);
        String requestHash = Hashes.sha256Parts("incident-retry", caller, incidentId,
                Long.toString(expectedLockVersion), why);
        io.github.dhluong90.bpm.process.api.Incident done = transactions.inTransaction(() -> idempotent(tenant,
                "incident-retry", idempotencyKey, requestHash, () -> {
                    LockedIncident locked = lockIncident(tenant, incidentId, expectedLockVersion, "retry");
                    ProcessStore.IncidentRow incident = locked.incident();
                    if (COMPENSATION_INCIDENTS.contains(incident.errorCode())) {
                        return retryUndo(locked, caller, why);
                    }
                    if (NOT_RETRYABLE.contains(incident.errorCode())) {
                        throw new InvalidStateException("Incident " + incidentId + " (" + incident.errorCode()
                                + ") failed before any call because the step's memory could not be built; running it"
                                + " again cannot help. Resolve it with an output instead");
                    }
                    io.github.dhluong90.bpm.process.internal.exec.Token token = waitingToken(tenant, locked);
                    Instant now = clock.instant();
                    java.util.Optional<JobRow> job = jobs.lockJob(tenant, token.activityInstanceId());
                    if (job.isPresent() && !"FAILED".equals(job.get().status())) {
                        throw new InvalidStateException("The job of incident " + incidentId + " is "
                                + job.get().status() + "; an attempt is due or running");
                    }
                    ProcessStore.IncidentRow retried = incident.next(incident.status(), incident.retryCount() + 1,
                            job.isPresent());
                    writeIncident(retried);
                    Map<String, Object> details = incidentDetails(retried);
                    details.put("reason", why);
                    job.ifPresent(j -> details.put("jobId", j.id()));
                    store.appendHistory(tenant, retried.instanceId(), retried.definitionId(), List.of(new HistoryEntry(
                            INCIDENT_RETRIED, retried.nodeId(), retried.tokenId(), token.activityInstanceId(), caller,
                            now, details)));
                    Map<String, Object> payload = incidentPayload(retried, locked.instance());
                    payload.put("retriedBy", caller);
                    payload.put("reason", why);
                    publishIncident(retried, "processes.incident.retried.v1", payload, locked.instance(), now);
                    LOG.log(System.Logger.Level.INFO, "process.incident.retried tenant={0} incident={1} retryCount={2}",
                            tenant, incidentId, retried.retryCount());
                    if (job.isPresent()) {
                        // PRE-077: the same job id, so the remote side deduplicates; the next claim is a new attempt.
                        if (!jobs.retryFailed(tenant, job.get().id(), now)) {
                            throw new IllegalStateException("Job " + job.get().id() + " left FAILED under its lock");
                        }
                        return incidentView(retried, null, caller, java.util.Set.of());
                    }
                    // A local step: its handler runs again in this transaction, under a new attempt id.
                    List<IncidentSite> raised = run(locked.definition(), locked.instance(),
                            stateOf(tenant, locked.instance()), new Command.RetryStep(token.id(),
                                    token.activityInstanceId(), caller), RunAs.REQUEST);
                    ProcessStore.IncidentRow after = store.lockIncident(tenant, incidentId).orElseThrow();
                    if (raised.stream().noneMatch(site -> java.util.Objects.equals(site.tokenId(), after.tokenId()))) {
                        ProcessStore.IncidentRow resolved = after.resolved(RETRY_SUCCEEDED, RESOLVED_BY_ENGINE, now);
                        writeIncident(resolved);
                        recordResolved(resolved, caller, null, Map.of(), now);
                        return incidentView(resolved, null, caller, java.util.Set.of());
                    }
                    return incidentView(after, null, caller, java.util.Set.of());
                }, Views::incidentToJson, Views::incidentFromJson));
        return withIncidentActions(done, caller);
    }

    @Override
    public io.github.dhluong90.bpm.process.api.Incident resolve(String incidentId, long expectedLockVersion,
            Map<String, Object> output, String reason, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_INCIDENT_MANAGE);
        requireIncidentId(incidentId);
        requireKey(idempotencyKey, "Resolving an incident");
        String why = requiredReason(reason);
        if (output == null) {
            throw new InputInvalidException("output is required: the result the waiting step completes with");
        }
        String outputJson = boundedJson(output, "The output");
        String requestHash = Hashes.sha256Parts("incident-resolve", caller, incidentId,
                Long.toString(expectedLockVersion), why, outputJson);
        io.github.dhluong90.bpm.process.api.Incident done = transactions.inTransaction(() -> idempotent(tenant,
                "incident-resolve", idempotencyKey, requestHash, () -> {
                    LockedIncident locked = lockIncident(tenant, incidentId, expectedLockVersion, "resolve");
                    boolean undo = COMPENSATION_INCIDENTS.contains(locked.incident().errorCode());
                    // #67: an undo's incident has no waiting step; its resolution marks the undo done by hand.
                    io.github.dhluong90.bpm.process.internal.exec.Token token = undo ? null : waitingToken(tenant, locked);
                    if (token != null && token.status() == TokenStatus.WAITING_CHILD) {
                        throw new InvalidStateException("The step of incident " + incidentId + " calls a process: retry"
                                + " it to start a new child, or cancel the instance");
                    }
                    JobRow job = undo ? null : jobs.findJob(tenant, token.activityInstanceId()).orElse(null);
                    // PRE-078: the step's output schema, as for any result; the guardrail is not run on a person's
                    // answer. A violation names the path and what is wrong there, never the value.
                    Map<String, Object> schema = job == null ? null
                            : AgentResultGuard.outputSchema(Values.readMap(job.payloadJson()));
                    if (schema != null) {
                        List<String> violations = outputSchemas.validate(schema, output);
                        if (violations == null || !violations.isEmpty()) {
                            throw new io.github.dhluong90.bpm.process.api.OutputInvalidException("The output breaks"
                                    + " the step's output schema", violations == null
                                            ? List.of("$: the schema check gave no answer") : violations);
                        }
                    }
                    Instant now = clock.instant();
                    String mode = settings.resolveApproval().value();
                    ProcessStore.IncidentRow proposed = locked.incident().proposed(caller, now, why, outputJson, mode);
                    writeIncident(proposed);
                    Map<String, Object> details = incidentDetails(proposed);
                    details.put("reason", why);
                    details.put("output", auditedOutput(job, output));
                    details.put("approvalMode", mode);
                    store.appendHistory(tenant, proposed.instanceId(), proposed.definitionId(), List.of(
                            new HistoryEntry(INCIDENT_RESOLUTION_PROPOSED, proposed.nodeId(), proposed.tokenId(),
                                    token == null ? null : token.activityInstanceId(), caller, now, details)));
                    Map<String, Object> payload = incidentPayload(proposed, locked.instance());
                    payload.put("proposedBy", caller);
                    payload.put("approvalMode", mode);
                    publishIncident(proposed, "processes.incident.resolution-proposed.v1", payload, locked.instance(),
                            now);
                    if (settings.resolveApproval() == EngineSettings.ResolveApproval.SINGLE) {
                        // dev profile only: the proposer's resolution applies at once, recorded as single.
                        ProcessStore.IncidentRow applied = undo ? applyUndoResolution(locked, proposed, caller, null,
                                now) : applyResolution(locked, proposed, token, job, caller, null, now);
                        return incidentView(applied, job, caller, java.util.Set.of());
                    }
                    return incidentView(proposed, job, caller, java.util.Set.of());
                }, Views::incidentToJson, Views::incidentFromJson));
        return withIncidentActions(done, caller);
    }

    @Override
    public io.github.dhluong90.bpm.process.api.Incident approveResolution(String incidentId, long expectedLockVersion,
            String comment, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_INCIDENT_MANAGE);
        requireIncidentId(incidentId);
        requireKey(idempotencyKey, "Approving a resolution");
        String note = boundedComment(comment, false);
        String requestHash = Hashes.sha256Parts("incident-approve", caller, incidentId,
                Long.toString(expectedLockVersion), note);
        io.github.dhluong90.bpm.process.api.Incident done = transactions.inTransaction(() -> idempotent(tenant,
                "incident-approve", idempotencyKey, requestHash, () -> {
                    LockedIncident locked = lockPending(tenant, incidentId, expectedLockVersion, caller, "approve");
                    if (COMPENSATION_INCIDENTS.contains(locked.incident().errorCode())) {
                        return incidentView(applyUndoResolution(locked, locked.incident(), caller, note,
                                clock.instant()), null, caller, java.util.Set.of());
                    }
                    io.github.dhluong90.bpm.process.internal.exec.Token token = waitingToken(tenant, locked);
                    JobRow job = jobs.findJob(tenant, token.activityInstanceId()).orElse(null);
                    ProcessStore.IncidentRow resolved = applyResolution(locked, locked.incident(), token, job, caller,
                            note, clock.instant());
                    return incidentView(resolved, job, caller, java.util.Set.of());
                }, Views::incidentToJson, Views::incidentFromJson));
        return withIncidentActions(done, caller);
    }

    @Override
    public io.github.dhluong90.bpm.process.api.Incident rejectResolution(String incidentId, long expectedLockVersion,
            String reason, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_INCIDENT_MANAGE);
        requireIncidentId(incidentId);
        requireKey(idempotencyKey, "Rejecting a resolution");
        String why = requiredReason(reason);
        String requestHash = Hashes.sha256Parts("incident-reject", caller, incidentId,
                Long.toString(expectedLockVersion), why);
        io.github.dhluong90.bpm.process.api.Incident done = transactions.inTransaction(() -> idempotent(tenant,
                "incident-reject", idempotencyKey, requestHash, () -> {
                    LockedIncident locked = lockPending(tenant, incidentId, expectedLockVersion, caller, "reject");
                    ProcessStore.IncidentRow pending = locked.incident();
                    ProcessStore.IncidentRow open = pending.proposalRejected();
                    writeIncident(open);
                    Instant now = clock.instant();
                    Map<String, Object> details = incidentDetails(open);
                    details.put("reason", why);
                    details.put("proposedBy", pending.proposedBy());
                    store.appendHistory(tenant, open.instanceId(), open.definitionId(), List.of(new HistoryEntry(
                            INCIDENT_RESOLUTION_REJECTED, open.nodeId(), open.tokenId(), null, caller, now, details)));
                    Map<String, Object> payload = incidentPayload(open, locked.instance());
                    payload.put("rejectedBy", caller);
                    payload.put("proposedBy", pending.proposedBy());
                    publishIncident(open, "processes.incident.resolution-rejected.v1", payload, locked.instance(), now);
                    return incidentView(open, null, caller, java.util.Set.of());
                }, Views::incidentToJson, Views::incidentFromJson));
        return withIncidentActions(done, caller);
    }

    /** An incident locked under its instance's row lock, with the instance and the version it runs. */
    private record LockedIncident(ProcessStore.IncidentRow incident, InstanceRow instance,
            StoredDefinition definition) {
    }

    /**
     * Locks the instance row, then the incident, in the order every instance transition takes, and checks, in this
     * order, that it is {@code OPEN}, that the lock version is current, that no retry is in flight and that the
     * instance runs (PRE-079). A refusal writes nothing.
     */
    private LockedIncident lockIncident(String tenant, String incidentId, long expectedLockVersion, String action) {
        LockedIncident locked = lockAny(tenant, incidentId);
        ProcessStore.IncidentRow incident = locked.incident();
        if (!io.github.dhluong90.bpm.process.api.Incident.OPEN.equals(incident.status())) {
            throw new InvalidStateException("Incident " + incidentId + " is " + incident.status() + "; only an OPEN"
                    + " incident can " + action);
        }
        requireIncidentLock(incident, expectedLockVersion);
        if (incident.retrying()) {
            throw new InvalidStateException("Incident " + incidentId + " is being retried; wait for the attempt to"
                    + " finish");
        }
        return locked;
    }

    /** As {@link #lockIncident}, for a pending resolution decided by a second person, never its proposer. */
    private LockedIncident lockPending(String tenant, String incidentId, long expectedLockVersion, String caller,
            String action) {
        LockedIncident locked = lockAny(tenant, incidentId);
        ProcessStore.IncidentRow incident = locked.incident();
        if (!io.github.dhluong90.bpm.process.api.Incident.RESOLUTION_PENDING.equals(incident.status())) {
            throw new InvalidStateException("Incident " + incidentId + " is " + incident.status() + "; no resolution"
                    + " waits for a decision");
        }
        // PD-126: four eyes, checked here so that the Java API and REST refuse alike, before anything is written.
        if (caller.equals(incident.proposedBy())) {
            throw new SelfApprovalForbiddenException("The proposer of the resolution of incident " + incidentId
                    + " cannot " + action + " it; a second holder of " + PERMISSION_INCIDENT_MANAGE + " decides");
        }
        requireIncidentLock(incident, expectedLockVersion);
        return locked;
    }

    private LockedIncident lockAny(String tenant, String incidentId) {
        ProcessStore.IncidentRow seen = store.findIncident(tenant, incidentId)
                .orElseThrow(() -> incidentNotFound(incidentId));
        InstanceRow instance = store.lockInstance(tenant, seen.instanceId())
                .orElseThrow(() -> incidentNotFound(incidentId));
        ProcessStore.IncidentRow incident = store.lockIncident(tenant, incidentId)
                .orElseThrow(() -> incidentNotFound(incidentId));
        boolean undoIncident = InstanceStatus.COMPENSATING.name().equals(instance.status())
                && COMPENSATION_INCIDENTS.contains(incident.errorCode());
        if (!"RUNNING".equals(instance.status()) && !undoIncident
                && !io.github.dhluong90.bpm.process.api.Incident.RESOLVED.equals(incident.status())) {
            throw new InvalidStateException("Instance " + instance.id() + " is " + instance.status());
        }
        StoredDefinition definition = store.definition(tenant, instance.definitionId()).orElseThrow();
        return new LockedIncident(incident, instance, definition);
    }

    private static void requireIncidentLock(ProcessStore.IncidentRow incident, long expected) {
        if (incident.lockVersion() != expected) {
            throw new VersionConflictException("Incident " + incident.id() + " is at lock version "
                    + incident.lockVersion() + ", not " + expected);
        }
    }

    /** The token the incident holds on its step, which must still wait there. */
    private io.github.dhluong90.bpm.process.internal.exec.Token waitingToken(String tenant, LockedIncident locked) {
        ProcessStore.IncidentRow incident = locked.incident();
        return store.tokens(tenant, incident.instanceId()).stream()
                .filter(t -> t.id().equals(incident.tokenId()) && (t.status() == TokenStatus.WAITING_ASYNC
                        || t.status() == TokenStatus.WAITING_CHILD) && t.nodeId().equals(incident.nodeId()))
                .findFirst()
                .orElseThrow(() -> new InvalidStateException("The token of incident " + incident.id()
                        + " no longer waits on " + incident.nodeId()));
    }

    /**
     * Completes the waiting step with the proposed output and resolves the incident {@code COMPLETED_MANUALLY}
     * (PRE-078), in the caller's transaction: history records the proposer, the approver, the reason and the output
     * as history may keep it, and {@code processes.incident.resolved.v1} carries both identities.
     */
    private ProcessStore.IncidentRow applyResolution(LockedIncident locked, ProcessStore.IncidentRow pending,
            io.github.dhluong90.bpm.process.internal.exec.Token token, JobRow job, String approver, String comment,
            Instant now) {
        Map<String, Object> output = Values.readMap(pending.proposalOutputJson());
        run(locked.definition(), locked.instance(), stateOf(locked.instance().tenantId(), locked.instance()),
                new Command.AsyncResult(token.id(), token.activityInstanceId(), new StepResult.Completed(output)),
                RunAs.REQUEST);
        ProcessStore.IncidentRow current = store.lockIncident(pending.tenantId(), pending.id()).orElseThrow();
        ProcessStore.IncidentRow resolved = current.resolved(COMPLETED_MANUALLY, approver, now);
        writeIncident(resolved);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("proposedBy", pending.proposedBy());
        extra.put("reason", pending.proposalReason());
        extra.put("output", auditedOutput(job, output));
        extra.put("approvalMode", pending.approvalMode());
        if (comment != null) {
            extra.put("comment", comment);
        }
        recordResolved(resolved, approver, token.activityInstanceId(), extra, now);
        return resolved;
    }

    /**
     * Closes the incident a retry left on a token once the step is accepted or routed to its reviewer (PRE-077):
     * {@code RESOLVED} with {@code RETRY_SUCCEEDED}, in the result transaction. Nothing happens without one.
     */
    private void closeRetried(String tenant, String instanceId, String tokenId, Instant now) {
        for (ProcessStore.IncidentRow open : store.openIncidents(tenant, instanceId)) {
            if (open.retrying() && java.util.Objects.equals(open.tokenId(), tokenId)) {
                ProcessStore.IncidentRow resolved = open.resolved(RETRY_SUCCEEDED, RESOLVED_BY_ENGINE, now);
                writeIncident(resolved);
                recordResolved(resolved, RESOLVED_BY_ENGINE, null, Map.of(), now);
            }
        }
    }

    /** PRE-079: a cancel resolves every unresolved incident of the instance in the cancel transaction. */
    private void closeOnCancel(String tenant, String instanceId, String caller, Instant now) {
        for (ProcessStore.IncidentRow open : store.openIncidents(tenant, instanceId)) {
            ProcessStore.IncidentRow resolved = open.resolved(INSTANCE_CANCELLED, caller, now);
            writeIncident(resolved);
            recordResolved(resolved, caller, null, Map.of(), now);
        }
    }

    private void recordResolved(ProcessStore.IncidentRow resolved, String actor, String attemptId,
            Map<String, Object> extra, Instant now) {
        InstanceRow instance = store.findInstance(resolved.tenantId(), resolved.instanceId()).orElseThrow();
        Map<String, Object> details = incidentDetails(resolved);
        details.put("resolution", resolved.resolution());
        details.put("resolvedBy", resolved.resolvedBy());
        details.putAll(extra);
        store.appendHistory(resolved.tenantId(), resolved.instanceId(), resolved.definitionId(), List.of(
                new HistoryEntry(INCIDENT_RESOLVED, resolved.nodeId(), resolved.tokenId(), attemptId, actor, now,
                        details)));
        Map<String, Object> payload = incidentPayload(resolved, instance);
        payload.put("resolution", resolved.resolution());
        payload.put("resolvedBy", resolved.resolvedBy());
        if (resolved.proposedBy() != null && COMPLETED_MANUALLY.equals(resolved.resolution())) {
            payload.put("proposedBy", resolved.proposedBy());
            payload.put("approvalMode", resolved.approvalMode());
        }
        publishIncident(resolved, "processes.incident.resolved.v1", payload, instance, now);
        LOG.log(System.Logger.Level.INFO, "process.incident.resolved tenant={0} incident={1} resolution={2}",
                resolved.tenantId(), resolved.id(), resolved.resolution());
    }

    private void writeIncident(ProcessStore.IncidentRow updated) {
        if (store.updateIncident(updated) != 1) {
            throw new VersionConflictException("Incident " + updated.id() + " changed meanwhile");
        }
    }

    private static Map<String, Object> incidentDetails(ProcessStore.IncidentRow incident) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incidentId", incident.id());
        details.put("errorCode", incident.errorCode());
        details.put("retryCount", incident.retryCount());
        details.put("status", incident.status());
        return details;
    }

    private static Map<String, Object> incidentPayload(ProcessStore.IncidentRow incident, InstanceRow instance) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incident.id());
        payload.put("instanceId", incident.instanceId());
        payload.put("nodeId", incident.nodeId());
        payload.put("errorCode", incident.errorCode());
        payload.put("retryCount", incident.retryCount());
        payload.put("status", incident.status());
        payload.put("definitionKey", instance.definitionKey());
        payload.put("version", instance.definitionVersion());
        if (instance.businessKey() != null) {
            payload.put("businessKey", instance.businessKey());
        }
        return payload;
    }

    /** The event id derives from the incident id and the lock version the change reaches, so a replay repeats it. */
    private void publishIncident(ProcessStore.IncidentRow incident, String type, Map<String, Object> payload,
            InstanceRow instance, Instant now) {
        publish(new ProcessEventPublisher.Event(Ids.child(incident.id(), incident.lockVersion() + 1), type,
                incident.tenantId(), now, payload), instance.id());
    }

    /**
     * The output as history and the incident view keep it: an agent step's output masked by its output schema's
     * policy, as its answers are (#52); another step's output as given, since it has no masking policy.
     */
    private Map<String, Object> auditedOutput(JobRow job, Map<String, Object> output) {
        if (job == null) {
            return output;
        }
        ResultGuard guard = guards.get(job.jobKind());
        return guard == null ? output : guard.auditOutput(Values.readMap(job.payloadJson()), output);
    }

    private java.util.Optional<JobRow> jobOf(String tenant, ProcessStore.IncidentRow incident) {
        if (incident.tokenId() == null) {
            return java.util.Optional.empty();
        }
        return store.tokens(tenant, incident.instanceId()).stream().filter(t -> t.id().equals(incident.tokenId()))
                .findFirst().flatMap(t -> t.activityInstanceId() == null ? java.util.Optional.empty()
                        : jobs.findJob(tenant, t.activityInstanceId()));
    }

    private io.github.dhluong90.bpm.process.api.Incident incidentView(ProcessStore.IncidentRow row, JobRow job,
            String caller, java.util.Set<String> held) {
        io.github.dhluong90.bpm.process.api.PendingResolution pending = row.proposedBy() == null ? null
                : new io.github.dhluong90.bpm.process.api.PendingResolution(row.proposedBy(), row.proposedAt(),
                        row.proposalReason(), auditedOutput(job, row.proposalOutputJson() == null ? Map.of()
                                : Values.readMap(row.proposalOutputJson())), row.approvalMode());
        return new io.github.dhluong90.bpm.process.api.Incident(row.id(), row.instanceId(), row.nodeId(), row.tokenId(),
                row.errorCode(), row.causeSummary(), row.retryCount(), row.status(), row.lockVersion(), row.createdAt(),
                row.resolvedAt(), row.resolution(), row.resolvedBy(), pending, row.retrying(),
                incidentActions(row.status(), row.retrying(), row.errorCode(), row.proposedBy(), caller, held));
    }

    private io.github.dhluong90.bpm.process.api.Incident withIncidentActions(
            io.github.dhluong90.bpm.process.api.Incident i, String caller) {
        return new io.github.dhluong90.bpm.process.api.Incident(i.id(), i.instanceId(), i.nodeId(), i.tokenId(),
                i.errorCode(), i.causeSummary(), i.retryCount(), i.status(), i.lockVersion(), i.createdAt(),
                i.resolvedAt(), i.resolution(), i.resolvedBy(), i.pendingResolution(), i.retrying(),
                incidentActions(i.status(), i.retrying(), i.errorCode(), i.pendingResolution() == null ? null
                        : i.pendingResolution().proposedBy(), caller, callers.currentPermissions()));
    }

    /** What the caller may do now (PRE-080): computed from the row, the caller and its permissions. */
    private static List<String> incidentActions(String status, boolean retrying, String errorCode, String proposedBy,
            String caller, java.util.Set<String> held) {
        if (!held.contains(PERMISSION_INCIDENT_MANAGE)) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        if (io.github.dhluong90.bpm.process.api.Incident.OPEN.equals(status) && !retrying) {
            if (!NOT_RETRYABLE.contains(errorCode) && !COMPENSATION_MANUAL_REQUIRED.equals(errorCode)) {
                actions.add("retry");
            }
            actions.add("resolve");
        }
        if (io.github.dhluong90.bpm.process.api.Incident.RESOLUTION_PENDING.equals(status)
                && !caller.equals(proposedBy)) {
            actions.add("approve-resolution");
            actions.add("reject-resolution");
        }
        return actions;
    }

    // ---- called child processes (#92, PRE-211 to PRE-214, DESIGN section 4.2) ------------------------------------

    private static final String CALL_ACTOR = "system:call";
    /** Sequence of a child's end job in the id space of the child's own ids, far above any kernel id. */
    private static final long CALL_RESULT_SEQUENCE = 2_000_000L;

    /**
     * Sets the most repetitions one looped activity may have ({@code multi-instance.max}, #496): a collection or a
     * loop maximum over it opens an incident and creates no task. Set before the first instance runs.
     *
     * @param instances 1 to {@link EngineDefaults#MAX_LOOP_INSTANCES}
     */
    public void useMaxLoopInstances(int instances) {
        if (instances < 1 || instances > EngineDefaults.MAX_LOOP_INSTANCES) {
            throw new IllegalArgumentException("multi-instance.max must be 1 to " + EngineDefaults.MAX_LOOP_INSTANCES);
        }
        this.maxLoopInstances = instances;
    }

    /**
     * Sets how deep one call may sit above another ({@code calls.max-depth}).
     *
     * @param depth 1 to {@link EngineDefaults#MAX_CALL_DEPTH}
     */
    public void useCallDepth(int depth) {
        if (depth < 1 || depth > EngineDefaults.MAX_CALL_DEPTH) {
            throw new IllegalArgumentException("calls.max-depth must be 1 to " + EngineDefaults.MAX_CALL_DEPTH);
        }
        this.maxCallDepth = depth;
    }

    /**
     * Starts the child of a {@code callProcess} step in the parent's transaction, after the parent's rows are saved
     * (PRE-211, PRE-212, PRE-214). The child is looked up and started in the parent's tenant, on the pinned version or
     * the one effective now, and is unique per parent token and call attempt. A call that cannot start opens an
     * incident on the parent's step and starts nothing.
     */
    private void startChild(StoredDefinition parentDefinition, InstanceRow parent, InstanceState parentState,
            Effect.StartChildInstance call, List<IncidentSite> incidents, Instant now) {
        String tenant = parentState.tenantId();
        io.github.dhluong90.bpm.process.internal.exec.Token token = parentState.tokens().stream()
                .filter(t -> t.id().equals(call.tokenId())).findFirst().orElseThrow();
        int attempt = store.callAttempts(tenant, token.id());
        int depth = store.parentLink(tenant, parent.id()).map(l -> l.callDepth()).orElse(0) + 1;
        String failure = null;
        StoredDefinition child = null;
        if (depth > maxCallDepth) {
            failure = "CALL_DEPTH_EXCEEDED";
        } else if (call.version() != null) {
            child = store.definitionVersion(tenant, call.calledElement(), call.version())
                    .filter(d -> "ACTIVE".equals(d.status())).orElse(null);
        } else {
            child = store.effectiveDefinition(tenant, call.calledElement(), now).orElse(null);
        }
        if (failure == null && child == null) {
            failure = "NO_EFFECTIVE_VERSION";
        }
        if (failure != null) {
            incidents.add(new IncidentSite(tenant, parent.id(), parentDefinition.id(), token.id(), token.nodeId(), null,
                    call.attemptId(), 0, failure, call.calledElement(), "The step could not start " + call.calledElement()
                            + " (" + failure + (call.version() == null ? "" : ", pinned version " + call.version())
                            + ")"));
            return;
        }
        String childId = Ulid.next(now);
        String base = parent.businessKey() == null ? null : parent.businessKey() + "/" + token.nodeId();
        String businessKey = base == null ? null : (attempt == 0 ? base : base + "#" + attempt);
        if (businessKey != null && businessKey.length() > EngineDefaults.MAX_BUSINESS_KEY_LENGTH) {
            businessKey = null;
        }
        InstanceRow childRow = new InstanceRow(tenant, childId, child.id(), child.key(), child.version(), businessKey,
                "RUNNING", "{}", 0, 0, CALL_ACTOR, now, null);
        if (!store.insertInstance(childRow)) {
            childRow = new InstanceRow(tenant, childId, child.id(), child.key(), child.version(), null, "RUNNING", "{}",
                    0, 0, CALL_ACTOR, now, null);
            store.insertInstance(childRow);
            businessKey = null;
        }
        store.linkChild(tenant, childId, parent.id(), token.id(), attempt, depth);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("childInstanceId", childId);
        details.put("calledElement", call.calledElement());
        details.put("version", child.version());
        details.put("callAttempt", attempt);
        store.appendHistory(tenant, parent.id(), parentDefinition.id(), List.of(new HistoryEntry("CHILD_STARTED",
                token.nodeId(), token.id(), call.attemptId(), CALL_ACTOR, now, details)));
        // The child runs as engine work started by the parent's step, in the parent's tenant, with no caller.
        run(child, childRow, InstanceState.created(childId, tenant, child.id(), businessKey),
                new Command.Start(call.variables(), CALL_ACTOR), new RunAs.EngineWork(tenant, CALL_ACTOR));
    }

    /**
     * Writes the job that resumes the parent of an instance that has just ended, in the transaction that ended it. The
     * parent row is not locked here; the job fires in a transaction of its own that locks it (PRE-213).
     */
    private void scheduleCallResult(String tenant, InstanceState child, ProcessStore.ParentLink link,
            List<HistoryEntry> history, Instant now) {
        InstanceRow parent = store.findInstance(tenant, link.parentInstanceId()).orElse(null);
        if (parent == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("childInstanceId", child.instanceId());
        payload.put("outcome", child.status().name());
        for (HistoryEntry e : history) {
            if ("INSTANCE_ENDED".equals(e.type()) && e.details().get("reason") != null) {
                payload.put("reason", String.valueOf(e.details().get("reason")));
            }
        }
        jobs.insertJob(JobRow.due(tenant, Ids.child(child.instanceId(), CALL_RESULT_SEQUENCE), parent.id(),
                parent.definitionId(), link.parentTokenId(), JobStore.CALL_RESULT, null, now, jobSettings.timerLease(),
                jobSettings.maxAttempts(), Values.write(payload)), now);
    }

    /**
     * Fires a {@code CALL_RESULT} job inside the fire transaction: locks the parent row (the child's end transaction
     * never did), checks the claim, then tells the waiting {@code callProcess} step how its child ended. The child's
     * declared outputs are read from the ended child, never from the job. A job for a parent that ended, a token that
     * moved on, or a child that a retry replaced is consumed without effect.
     */
    private boolean fireCallResult(JobRow job, java.util.function.BooleanSupplier claimHeld) {
        String tenant = job.tenantId();
        InstanceRow row = store.lockInstance(tenant, job.instanceId()).orElse(null);
        if (!claimHeld.getAsBoolean()) {
            return false;
        }
        if (row == null || !"RUNNING".equals(row.status())) {
            return true;
        }
        Map<String, Object> payload = Values.readMap(job.payloadJson());
        String childId = (String) payload.get("childInstanceId");
        List<io.github.dhluong90.bpm.process.internal.exec.Token> tokens = store.tokens(tenant, row.id());
        io.github.dhluong90.bpm.process.internal.exec.Token token = tokens.stream()
                .filter(t -> t.id().equals(job.tokenId()) && t.status() == TokenStatus.WAITING_CHILD).findFirst()
                .orElse(null);
        ProcessStore.ParentLink link = token == null ? null : store.parentLink(tenant, childId).orElse(null);
        if (link == null || !link.parentTokenId().equals(token.id())
                || link.callAttempt() != store.callAttempts(tenant, token.id()) - 1) {
            LOG.log(System.Logger.Level.DEBUG, "process.call-result.obsolete tenant={0} job={1}", tenant, job.id());
            return true;
        }
        InstanceRow child = store.findInstance(tenant, childId).orElse(null);
        if (child == null) {
            return true;
        }
        Command.ChildOutcome outcome = switch (child.status()) {
            case "COMPLETED" -> Command.ChildOutcome.COMPLETED;
            case "CANCELLED" -> Command.ChildOutcome.CANCELLED;
            default -> Command.ChildOutcome.FAILED;
        };
        StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
        Map<String, Object> output = new LinkedHashMap<>();
        if (outcome == Command.ChildOutcome.COMPLETED) {
            Map<String, Object> childVariables = Values.readMap(child.variablesJson());
            FlowNode node = compiled(definition).process().node(token.nodeId()).orElseThrow();
            for (io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping out : node.extensions()
                    .all(io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping.class)) {
                Object value = childVariables;
                for (String part : out.from().split("\\.")) {
                    value = value instanceof Map<?, ?> map ? map.get(part) : null;
                }
                if (value != null) {
                    output.put(out.name(), value);
                }
            }
        }
        InstanceState state = stateOf(tenant, row);
        try (io.github.dhluong90.bpm.process.spi.EventScope ignored = jobScope(job.id())) {
            run(definition, row, state, new Command.ChildEnded(token.id(), token.activityInstanceId(), childId, outcome,
                    output, (String) payload.get("reason")), new RunAs.EngineWork(tenant, CALL_ACTOR));
        }
        return true;
    }

    /**
     * Cancels the children of a parent that is being cancelled, newest lock order last: the caller holds the parent, and
     * each child is locked and cancelled in turn with the reason {@code parent cancelled} and the actor of the
     * cancellation, and so are its own children (PRE-213). A child that is compensating is left: an undo is never
     * abandoned half way.
     */
    private void cancelChildren(String tenant, String parentId, String actor) {
        cancelChildren(tenant, parentId, actor, null);
    }

    /**
     * As {@link #cancelChildren(String, String, String)}, for the children started by some of the parent's tokens only
     * (#496: a multi-instance loop ended while those children still ran); null means every child.
     */
    private void cancelChildren(String tenant, String parentId, String actor, java.util.Set<String> parentTokens) {
        for (String childId : store.activeChildren(tenant, parentId, EngineDefaults.MAX_PAGE_SIZE)) {
            if (parentTokens != null && store.parentLink(tenant, childId)
                    .map(link -> !parentTokens.contains(link.parentTokenId())).orElse(true)) {
                continue;
            }
            InstanceRow child = store.lockInstance(tenant, childId).orElse(null);
            if (child == null || !"RUNNING".equals(child.status())) {
                continue;
            }
            StoredDefinition definition = store.definition(tenant, child.definitionId()).orElseThrow();
            run(definition, child, stateOf(tenant, child), new Command.Cancel("parent cancelled", actor),
                    new RunAs.EngineWork(tenant, CALL_ACTOR));
            closeOnCancel(tenant, child.id(), actor, clock.instant());
            cancelChildren(tenant, child.id(), actor);
        }
    }

    // ---- migration of a step's work (#106, PRE-208) ---------------------------------------------------------------

    /**
     * After a migration has repointed an instance at the target version and cancelled the work of a removed step, the
     * token enters {@code nodeId} of the target as on arrival: a task opens, a job parks, a local step runs. It runs as
     * engine work in the instance's tenant, in the migration's transaction.
     *
     * @param tenant the tenant of the plan
     * @param instanceId the instance, already locked by the caller's transaction
     * @param tokenId the token to move
     * @param nodeId the step of the instance's current version to enter
     * @param actor the engine actor that moves it
     */
    void enterStepAfterMigration(String tenant, String instanceId, String tokenId, String nodeId, String actor) {
        InstanceRow row = store.lockInstance(tenant, instanceId).orElseThrow();
        StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
        run(definition, row, stateOf(tenant, row), new Command.MoveToken(tokenId, nodeId),
                new RunAs.EngineWork(tenant, actor));
    }

    private InstanceState stateOf(String tenant, InstanceRow row) {
        return new InstanceState(row.id(), tenant, row.definitionId(), row.businessKey(),
                InstanceStatus.valueOf(row.status()), store.tokens(tenant, row.id()), Values.readMap(row.variablesJson()),
                participants(tenant, row), row.sequence());
    }

    private String requiredReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > settings.reasonMaxLength()) {
            throw new InputInvalidException("reason is required, 1 to " + settings.reasonMaxLength()
                    + " characters (lifecycle.reason-max-length)");
        }
        return reason;
    }

    private static void requireKey(String idempotencyKey, String what) {
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException(what + " needs an Idempotency-Key");
        }
    }

    private static void requireIncidentId(String incidentId) {
        if (incidentId == null || incidentId.isBlank() || incidentId.length() > 26) {
            throw incidentNotFound(String.valueOf(incidentId));
        }
    }

    private static io.github.dhluong90.bpm.process.api.IncidentNotFoundException incidentNotFound(String id) {
        return new io.github.dhluong90.bpm.process.api.IncidentNotFoundException("No incident " + id
                + " in this tenant");
    }

    /**
     * What the reviewer of a refused result sees on the review task (#52, PRE-010): why it was refused, the inputs the
     * agent saw, and the answer as the refusal recorded it, masked by the output schema's masking policy.
     */
    private static Map<String, Object> reviewContext(JobRow job, String attemptId, String reportedBy,
            Map<String, Object> refusal) {
        Map<String, Object> context = new LinkedHashMap<>();
        for (String key : List.of("code", "reasons", "ruleIds", "guardrailDecisionId")) {
            if (refusal.get(key) != null) {
                context.put(key, refusal.get(key));
            }
        }
        // The refused attempt and who reported it, so the reviewer's answer is recorded against both (#59).
        context.put("attemptId", attemptId);
        context.put("reportedBy", reportedBy);
        // Masked inputs only as fingerprints (#59): the reviewer never sees what history may not keep.
        context.put("inputs", auditInputs(job));
        context.put("output", refusal.get("output"));
        context.put("outputFields", refusal.get("outputFields"));
        // #386: the step's English and Vietnamese (or any) titles per input and output field, as declared.
        Object guard = Values.readMap(job.payloadJson()).get("guard");
        if (guard instanceof Map<?, ?> g && g.get("fieldTitles") instanceof Map<?, ?> titles && !titles.isEmpty()) {
            context.put("fieldTitles", titles);
        }
        return context;
    }

    /**
     * Fails a job that was claimed more often than {@code jobs.max-attempts} allows (its attempts kept dying with
     * their replica), without calling the adapter, and records {@code ASYNC_ATTEMPT_FAILED} with
     * {@code MAX_ATTEMPTS_REACHED}. Nothing happens when the claim no longer holds.
     *
     * @param job the claimed job
     * @param replicaId the claiming replica
     * @return true when the job was failed
     */
    public boolean failExhausted(JobRow job, String replicaId) {
        return transactions.inTransaction(() -> {
            if (job.instanceId() != null) {
                store.lockInstance(job.tenantId(), job.instanceId());
            }
            return jobs.lockClaim(job.tenantId(), job.id(), replicaId, job.attempt(), clock.instant()).map(held -> {
                Instant now = clock.instant();
                if (!jobs.release(job.tenantId(), job.id(), replicaId, job.attempt(), job.nextFireAt(), true, now)) {
                    return false;
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("jobId", job.id());
                details.put("attempt", job.attempt());
                details.put("errorCode", MAX_ATTEMPTS_REACHED);
                details.put("retryable", false);
                details.put("final", true);
                store.appendHistory(job.tenantId(), job.instanceId(), job.definitionId(), List.of(new HistoryEntry(
                        "ASYNC_ATTEMPT_FAILED", job.nodeId(), job.tokenId(), attemptId(job.id(), job.attempt()),
                        "system:replica:" + replicaId, now, details)));
                raiseIncident(job, MAX_ATTEMPTS_REACHED, "The step was claimed " + job.attempt() + " times without a"
                        + " result (" + MAX_ATTEMPTS_REACHED + ")", now);
                return true;
            }).orElse(false);
        });
    }

    /** Whether the attempt is the job's current claim: {@code CLAIMED}, that attempt, lease not ended. */
    private boolean current(JobRow job, String attemptId) {
        return "CLAIMED".equals(job.status()) && job.attempt() > 0 && attemptId.equals(attemptId(job.id(), job.attempt()))
                && job.lockedUntil() != null && job.lockedUntil().isAfter(clock.instant());
    }

    private static String kind(StepResult result) {
        return switch (result) {
            case StepResult.Completed c -> "COMPLETED";
            case StepResult.BusinessError e -> "BUSINESS_ERROR";
            case StepResult.Failed f -> "FAILED";
        };
    }

    /**
     * A failed attempt: the job is due again after {@code jobs.retry-backoff} times the attempt, or {@code FAILED}
     * when the failure is not retryable or the attempts are used; the token stays on the step. The code, whether the
     * reporter called it retryable, and the reporter are recorded, never the detail, which may carry personal data.
     */
    private void giveBack(JobRow job, String attemptId, String code, boolean retryable, boolean business,
            String reportedBy) {
        Instant now = clock.instant();
        boolean last = !retryable || job.attempt() >= job.maxAttempts();
        Instant retryAt = retryAt(job, now);
        if (!jobs.release(job.tenantId(), job.id(), job.lockedBy(), job.attempt(), retryAt, last, now)) {
            throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.id());
        details.put("attempt", job.attempt());
        details.put("errorCode", code == null ? "-" : code);
        details.put("retryable", retryable);
        details.put("final", last);
        if (!last) {
            details.put("retryAt", retryAt.toString());
        }
        store.appendHistory(job.tenantId(), job.instanceId(), job.definitionId(), List.of(new HistoryEntry(
                "ASYNC_ATTEMPT_FAILED", job.nodeId(), job.tokenId(), attemptId, reportedBy, now, details)));
        LOG.log(System.Logger.Level.WARNING, "process.async.attempt.failed tenant={0} job={1} attempt={2} code={3}"
                + " retryable={4} final={5}", job.tenantId(), job.id(), job.attempt(), code, retryable, last);
        if (last) {
            String failure = code == null ? "-" : code;
            raiseIncident(job, business ? UNHANDLED_BUSINESS_ERROR : failure, business ? failure : null, business
                    ? "Business error " + failure + " has no path in the process"
                    : "Attempt " + job.attempt() + " of " + job.maxAttempts() + " failed with " + failure
                            + (retryable ? "; no retry is left" : "; not retryable"), now);
        }
    }

    /**
     * When a failed attempt is due again: after the step's own {@code retry.backoff} (#58), the same delay before each
     * retry, or else after the runner's {@code retry-backoff} times the attempt number.
     */
    private Instant retryAt(JobRow job, Instant now) {
        if (Values.readMap(job.payloadJson()).get(RETRY_BACKOFF_MS) instanceof Number ms && ms.longValue() > 0) {
            return now.plusMillis(ms.longValue());
        }
        Duration backoff = JobStore.isAsync(job.jobKind()) ? async.runner(job.jobKind(), jobSettings).retryBackoff()
                : jobSettings.retryBackoff();
        return now.plus(backoff.multipliedBy(Math.max(1, job.attempt())));
    }

    @Override
    public ProcessInstance cancel(String instanceId, long expectedLockVersion, String reason, String idempotencyKey) {
        String tenant = tenant();
        // PD-103: cancelling has its own permission; processes:start does not grant it.
        String caller = callerWith(PERMISSION_CANCEL);
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException("Cancelling an instance needs an Idempotency-Key");
        }
        if (reason == null || reason.isBlank() || reason.length() > settings.reasonMaxLength()) {
            throw new InputInvalidException("reason is required, 1 to " + settings.reasonMaxLength()
                    + " characters (lifecycle.reason-max-length)");
        }
        String requestHash = Hashes.sha256Parts("cancel", caller, instanceId, reason, String.valueOf(expectedLockVersion));
        return transactions.inTransaction(() -> idempotent(tenant, "cancel", idempotencyKey, requestHash, () -> {
            InstanceRow row = store.lockInstance(tenant, instanceId)
                    .orElseThrow(() -> new InstanceNotFoundException("No instance " + instanceId));
            if (row.lockVersion() != expectedLockVersion) {
                throw new VersionConflictException("Instance " + instanceId + " is at lock version " + row.lockVersion());
            }
            if (InstanceStatus.COMPENSATING.name().equals(row.status())) {
                // PRE-076: an undo is never abandoned half way; an operator resolves the incidents first.
                throw new InvalidStateException("Instance " + instanceId + " is COMPENSATING: a compensation is"
                        + " running, and an undo is never abandoned half way; resolve its incidents first");
            }
            if (!"RUNNING".equals(row.status())) {
                throw new InvalidStateException("Instance " + instanceId + " is " + row.status());
            }
            StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
            InstanceState state = new InstanceState(row.id(), tenant, row.definitionId(), row.businessKey(),
                    InstanceStatus.valueOf(row.status()), store.tokens(tenant, row.id()), Values.readMap(row.variablesJson()),
                    participants(tenant, row), row.sequence());
            run(definition, row, state, new Command.Cancel(reason, caller), RunAs.REQUEST);
            closeOnCancel(tenant, row.id(), caller, clock.instant());
            // #92, PRE-213: lock order is parent, then child. The children end in this transaction.
            cancelChildren(tenant, row.id(), caller);
            return read(tenant, store.findInstance(tenant, row.id()).orElseThrow());
        }, Views::instanceToJson, Views::instanceFromJson));
    }


    // ---- saga compensation (#67, ADR-0007, DESIGN section 4.2) ------------------------------------------------

    /** Payload key of an undo job: the job the forward step ran under. */
    static final String FORWARD_JOB_ID = "forwardJobId";
    /** Payload key of an undo job: the accepted forward attempt. */
    static final String FORWARD_ATTEMPT_ID = "forwardAttemptId";
    /** Payload key of an undo job: its stack entry. */
    static final String COMPENSATION_ENTRY = "entryId";
    /** Incident code of an undo that failed for good: not retryable, or its last attempt used (PRE-074). */
    static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";
    /** Incident code of an undo declared {@code compensate: manual} (PRE-075). */
    static final String COMPENSATION_MANUAL_REQUIRED = "COMPENSATION_MANUAL_REQUIRED";
    /** The incidents of a compensation walk: they hold the walk, not a step. */
    static final java.util.Set<String> COMPENSATION_INCIDENTS = java.util.Set.of(COMPENSATION_FAILED,
            COMPENSATION_MANUAL_REQUIRED);
    /** The engine actor of the walk. */
    static final String COMPENSATION_ACTOR = "system:compensation";
    private static final String MANUAL = "manual";

    /**
     * The stack entry of a completed step: its undo, the forward step's keys and what the undo needs. A remote or agent
     * step's attempt id is its job id, whose accepted attempt is the job's current one; a local step has no job.
     */
    private ProcessStore.CompensationEntry entryOf(Effect.PushCompensation push, String tenant, String instanceId,
            FlowNode node, Instant now) {
        JobRow forward = jobs.findJob(tenant, push.attemptId()).filter(j -> JobStore.isAsync(j.jobKind()))
                .orElse(null);
        String forwardJobId = forward == null ? null : forward.id();
        String forwardAttemptId = forward == null ? push.attemptId()
                : attemptId(forward.id(), Math.max(1, forward.attempt()));
        boolean remote = node.extensions().first(AgentTaskSpec.class).isPresent() || node.extensions()
                .first(HandlerRef.class).map(h -> "remote".equals(h.mode())).orElse(false);
        String mode = MANUAL.equals(push.handler()) ? MANUAL : remote ? "remote" : "local";
        return new ProcessStore.CompensationEntry(tenant, Ulid.next(now), instanceId, push.scopeTokenId(), 0,
                push.nodeId(), forwardJobId, forwardAttemptId, push.handler(), mode, Values.write(push.inputSnapshot()),
                ProcessStore.CompensationEntry.PENDING, null, now, null);
    }

    /** The entries of one scope token, newest first. */
    private List<ProcessStore.CompensationEntry> scopeStack(String tenant, String instanceId, String scopeTokenId) {
        return store.compensationEntries(tenant, instanceId).stream()
                .filter(e -> e.scopeTokenId().equals(scopeTokenId))
                .sorted(java.util.Comparator.comparingInt(ProcessStore.CompensationEntry::seq).reversed())
                .toList();
    }

    /**
     * The kernel started a compensation (PRE-073): {@code processes.compensation.started.v1} with the scope token and
     * the number of steps to undo, then the first undo. Under the instance row lock, in the transaction of the change.
     */
    private void startCompensation(StoredDefinition definition, String tenant, String instanceId,
            Effect.StartCompensation started) {
        long depth = scopeStack(tenant, instanceId, started.scopeTokenId()).stream()
                .filter(e -> ProcessStore.CompensationEntry.PENDING.equals(e.status())).count();
        InstanceRow row = store.findInstance(tenant, instanceId).orElseThrow();
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instanceId);
        payload.put("scopeId", started.scopeTokenId());
        payload.put("depth", depth);
        payload.put("nodeId", started.nodeId());
        if (started.cause() != null) {
            payload.put("cause", started.cause());
        }
        payload.put("definitionKey", row.definitionKey());
        payload.put("version", row.definitionVersion());
        if (row.businessKey() != null) {
            payload.put("businessKey", row.businessKey());
        }
        publish(new ProcessEventPublisher.Event(Ids.child(started.scopeTokenId() + "/compensation", 1),
                "processes.compensation.started.v1", tenant, now, payload), instanceId);
        LOG.log(System.Logger.Level.WARNING, "process.compensation.started tenant={0} instance={1} scope={2} node={3}"
                + " cause={4} depth={5}", tenant, instanceId, started.scopeTokenId(), started.nodeId(), started.cause(),
                depth);
        advanceWalk(definition, tenant, instanceId, started.scopeTokenId());
    }

    /**
     * One step of the walk, under the instance row lock: nothing while an undo is in flight or an incident holds the
     * walk; otherwise the newest {@code PENDING} entry gets its undo job, or, for {@code compensate: manual}, an
     * incident; with none left the scope is {@code COMPENSATED} and the instance {@code FAILED} (PRE-013).
     */
    private void advanceWalk(StoredDefinition definition, String tenant, String instanceId, String scopeTokenId) {
        List<ProcessStore.CompensationEntry> stack = scopeStack(tenant, instanceId, scopeTokenId);
        if (stack.stream().anyMatch(e -> !ProcessStore.CompensationEntry.PENDING.equals(e.status())
                && !ProcessStore.CompensationEntry.DONE.equals(e.status()))) {
            return;
        }
        ProcessStore.CompensationEntry next = stack.stream()
                .filter(e -> ProcessStore.CompensationEntry.PENDING.equals(e.status())).findFirst().orElse(null);
        Instant now = clock.instant();
        if (next == null) {
            InstanceRow row = store.findInstance(tenant, instanceId).orElseThrow();
            run(definition, row, stateOf(tenant, row), new Command.EndCompensation(scopeTokenId),
                    new RunAs.EngineWork(tenant, COMPENSATION_ACTOR));
            LOG.log(System.Logger.Level.WARNING, "process.compensation.done tenant={0} instance={1} scope={2}", tenant,
                    instanceId, scopeTokenId);
            return;
        }
        Map<String, Object> details = undoDetails(next);
        if (MANUAL.equals(next.mode())) {
            // PRE-075: never skipped silently; the walk waits for an operator, with the step's context.
            store.updateCompensation(next.with(ProcessStore.CompensationEntry.MANUAL, null, null));
            FlowNode node = compiled(definition).process().node(next.stepId()).orElse(null);
            Map<String, Object> snapshot = Values.readMap(next.inputSnapshotJson());
            details.put("inputSnapshot", node == null ? Map.of() : AgentAudit.maskInputs(tenant, node, snapshot,
                    inputMasker));
            store.appendHistory(tenant, instanceId, definition.id(), List.of(new HistoryEntry(
                    "COMPENSATION_STEP_MANUAL", next.stepId(), scopeTokenId, next.forwardAttemptId(),
                    COMPENSATION_ACTOR, now, details)));
            raiseIncident(new IncidentSite(tenant, instanceId, definition.id(), scopeTokenId, next.stepId(), null,
                    next.forwardAttemptId(), 0, COMPENSATION_MANUAL_REQUIRED, null, "Step " + next.stepId()
                            + " is undone by hand (compensate: manual), entry " + next.id() + ", forward job "
                            + (next.forwardJobId() == null ? "-" : next.forwardJobId()) + ", forward attempt "
                            + next.forwardAttemptId() + "; the compensation waits for its resolution"), now);
            return;
        }
        // Each undo is its own job, keyed by its own id on every retry, with the COMPENSATION runner's retry policy.
        String jobId = Ulid.next(now);
        RunnerSettings runner = async.runner(JobStore.COMPENSATION, jobSettings);
        Duration timeout = async.timeout(JobStore.COMPENSATION, null, jobSettings);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", next.handler());
        payload.put(ServiceStepRunner.MODE, next.mode());
        payload.put("timeoutMs", timeout.toMillis());
        InstanceRow row = store.findInstance(tenant, instanceId).orElseThrow();
        if (row.businessKey() != null) {
            payload.put("businessKey", row.businessKey());
        }
        payload.put("inputs", Values.readMap(next.inputSnapshotJson()));
        if (next.forwardJobId() != null) {
            payload.put(FORWARD_JOB_ID, next.forwardJobId());
        }
        payload.put(FORWARD_ATTEMPT_ID, next.forwardAttemptId());
        payload.put(COMPENSATION_ENTRY, next.id());
        Duration lease = timeout.plus(async.leaseMargin());
        jobs.insertJob(JobRow.due(tenant, jobId, instanceId, definition.id(), scopeTokenId, JobStore.COMPENSATION,
                next.stepId(), now, lease, runner.maxAttempts(), Values.write(payload)), now);
        store.updateCompensation(next.with(ProcessStore.CompensationEntry.RUNNING, jobId, null));
        details.put("jobId", jobId);
        details.put("leaseMs", lease.toMillis());
        store.appendHistory(tenant, instanceId, definition.id(), List.of(new HistoryEntry(
                "COMPENSATION_STEP_SCHEDULED", next.stepId(), scopeTokenId, next.forwardAttemptId(),
                COMPENSATION_ACTOR, now, details)));
    }

    /** What history keeps of an entry: ids, codes and the handler, never the snapshot. */
    private static Map<String, Object> undoDetails(ProcessStore.CompensationEntry entry) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("entryId", entry.id());
        details.put("seq", entry.seq());
        details.put("handler", entry.handler());
        details.put("mode", entry.mode());
        if (entry.forwardJobId() != null) {
            details.put(FORWARD_JOB_ID, entry.forwardJobId());
        }
        details.put(FORWARD_ATTEMPT_ID, entry.forwardAttemptId());
        return details;
    }

    private java.util.Optional<ProcessStore.CompensationEntry> entry(String tenant, String instanceId, Object id) {
        return id == null ? java.util.Optional.empty() : store.compensationEntries(tenant, instanceId).stream()
                .filter(e -> e.id().equals(String.valueOf(id))).findFirst();
    }

    /**
     * The result transaction of an undo attempt (PRE-073, PRE-074), under the instance and job locks, while the
     * attempt is current. A local undo runs its handler here, in this transaction. A completed undo is {@code DONE}
     * and the walk goes on; a failure is given back for a retry or fails the job, which pauses the walk with
     * {@code COMPENSATION_FAILED}.
     */
    private StepCompletion.Outcome recordUndo(InstanceRow row, JobRow job, String attemptId, StepResult result,
            String reportedBy) {
        String tenant = job.tenantId();
        Map<String, Object> payload = Values.readMap(job.payloadJson());
        ProcessStore.CompensationEntry entry = entry(tenant, job.instanceId(), payload.get(COMPENSATION_ENTRY))
                .orElse(null);
        if (row == null || !InstanceStatus.COMPENSATING.name().equals(row.status()) || entry == null
                || !ProcessStore.CompensationEntry.RUNNING.equals(entry.status()) || !job.id().equals(entry.jobId())) {
            jobs.cancelJob(tenant, job.id(), clock.instant());
            return StepCompletion.Outcome.DISCARDED;
        }
        StepResult outcome = result;
        if (result instanceof StepResult.Completed && ServiceStepRunner.LOCAL.equals(payload.get(ServiceStepRunner.MODE))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inputs = payload.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m
                    : Map.of();
            try {
                outcome = invoker.undo(tenant, row.id(), row.businessKey(), job.nodeId(), entry.handler(), attemptId,
                        entry.forwardAttemptId(), inputs);
            } catch (RuntimeException e) {
                // Rolls the undo back with its writes; the attempt is given back as a failed one.
                throw new AttemptRunFailed(e);
            }
        }
        switch (outcome) {
            case StepResult.Completed completed -> {
                Instant now = clock.instant();
                if (!jobs.markDone(tenant, job.id(), job.lockedBy(), job.attempt(), now)) {
                    throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
                }
                store.updateCompensation(entry.with(ProcessStore.CompensationEntry.DONE, job.id(), now));
                Map<String, Object> details = undoDetails(entry);
                details.put("jobId", job.id());
                details.put("attempt", job.attempt());
                store.appendHistory(tenant, row.id(), job.definitionId(), List.of(new HistoryEntry(
                        "COMPENSATION_STEP_COMPLETED", entry.stepId(), entry.scopeTokenId(), attemptId, reportedBy, now,
                        details)));
                closeRetried(tenant, row.id(), entry.scopeTokenId(), now);
                advanceWalk(store.definition(tenant, row.definitionId()).orElseThrow(), tenant, row.id(),
                        entry.scopeTokenId());
            }
            case StepResult.BusinessError error -> giveBack(job, attemptId, error.code(), false, true, reportedBy);
            case StepResult.Failed failed -> giveBack(job, attemptId, failed.errorCode(), failed.retryable(), false,
                    reportedBy);
        }
        return StepCompletion.Outcome.ACCEPTED;
    }

    /** PRE-074: an undo failed for good. Its entry is {@code FAILED}, an incident holds the walk, nothing else moves. */
    private void undoFailed(JobRow job, String cause, Instant now) {
        ProcessStore.CompensationEntry entry = entry(job.tenantId(), job.instanceId(),
                Values.readMap(job.payloadJson()).get(COMPENSATION_ENTRY)).orElse(null);
        if (entry == null) {
            return;
        }
        store.updateCompensation(entry.with(ProcessStore.CompensationEntry.FAILED, job.id(), null));
        Map<String, Object> details = undoDetails(entry);
        details.put("jobId", job.id());
        details.put("errorCode", cause);
        store.appendHistory(job.tenantId(), job.instanceId(), job.definitionId(), List.of(new HistoryEntry(
                "COMPENSATION_STEP_FAILED", entry.stepId(), entry.scopeTokenId(),
                attemptId(job.id(), Math.max(1, job.attempt())), COMPENSATION_ACTOR, now, details)));
        raiseIncident(new IncidentSite(job.tenantId(), job.instanceId(), job.definitionId(), entry.scopeTokenId(),
                entry.stepId(), job.id(), attemptId(job.id(), Math.max(1, job.attempt())), Math.max(0, job.attempt() - 1),
                COMPENSATION_FAILED, cause, "Undo " + entry.handler() + " of " + entry.stepId() + " (entry "
                        + entry.id() + ", forward job " + (entry.forwardJobId() == null ? "-" : entry.forwardJobId())
                        + ") failed with " + cause + "; the compensation is paused"), now);
    }

    /**
     * PRE-073: a forward step whose job failed for good, and which declares {@code onFailure: compensate}, starts the
     * compensation of its scope instead of opening an incident.
     *
     * @return whether the compensation started
     */
    private boolean compensateInstead(JobRow job, String cause) {
        if (!JobStore.ASYNC_SERVICE.equals(job.jobKind()) && !JobStore.ASYNC_AGENT.equals(job.jobKind())) {
            return false;
        }
        InstanceRow row = store.lockInstance(job.tenantId(), job.instanceId()).orElse(null);
        if (row == null || !"RUNNING".equals(row.status())) {
            return false;
        }
        StoredDefinition definition = store.definition(job.tenantId(), row.definitionId()).orElseThrow();
        boolean compensates = compiled(definition).process().node(job.nodeId())
                .flatMap(n -> n.extensions().first(OnFailure.class)).map(OnFailure::compensates).orElse(false);
        if (!compensates) {
            return false;
        }
        InstanceState state = stateOf(job.tenantId(), row);
        boolean waiting = state.tokens().stream().anyMatch(t -> t.id().equals(job.tokenId())
                && t.status() == TokenStatus.WAITING_ASYNC && job.id().equals(t.activityInstanceId()));
        if (!waiting) {
            return false;
        }
        run(definition, row, state, new Command.Compensate(job.tokenId(), job.id(), cause),
                new RunAs.EngineWork(job.tenantId(), ASYNC_ACTOR));
        return true;
    }

    /** The entry an undo incident holds: on its scope token and step, {@code FAILED} or {@code MANUAL}. */
    private ProcessStore.CompensationEntry heldEntry(ProcessStore.IncidentRow incident) {
        return store.compensationEntries(incident.tenantId(), incident.instanceId()).stream()
                .filter(e -> e.scopeTokenId().equals(incident.tokenId()) && e.stepId().equals(incident.nodeId())
                        && (ProcessStore.CompensationEntry.FAILED.equals(e.status())
                                || ProcessStore.CompensationEntry.MANUAL.equals(e.status())))
                .findFirst()
                .orElseThrow(() -> new InvalidStateException("Incident " + incident.id() + " holds no undo of "
                        + incident.nodeId() + " any more"));
    }

    /**
     * PRE-074: a retry of a failed undo runs its job again under the same job id, with one more attempt and a new
     * attempt id, so the remote side never applies the undo twice. A manual undo has nothing to run again.
     */
    private io.github.dhluong90.bpm.process.api.Incident retryUndo(LockedIncident locked, String caller, String why) {
        ProcessStore.IncidentRow incident = locked.incident();
        if (COMPENSATION_MANUAL_REQUIRED.equals(incident.errorCode())) {
            throw new InvalidStateException("Incident " + incident.id() + " is a manual undo: there is nothing to run"
                    + " again. Undo the step by hand, then resolve the incident");
        }
        String tenant = incident.tenantId();
        ProcessStore.CompensationEntry entry = heldEntry(incident);
        JobRow job = entry.jobId() == null ? null : jobs.lockJob(tenant, entry.jobId()).orElse(null);
        if (job == null || !"FAILED".equals(job.status())) {
            throw new InvalidStateException("The undo job of incident " + incident.id() + " is "
                    + (job == null ? "missing" : job.status()) + "; an attempt is due or running");
        }
        Instant now = clock.instant();
        ProcessStore.IncidentRow retried = incident.next(incident.status(), incident.retryCount() + 1, true);
        writeIncident(retried);
        Map<String, Object> details = incidentDetails(retried);
        details.put("reason", why);
        details.put("jobId", job.id());
        details.put("entryId", entry.id());
        store.appendHistory(tenant, retried.instanceId(), retried.definitionId(), List.of(new HistoryEntry(
                INCIDENT_RETRIED, retried.nodeId(), retried.tokenId(), null, caller, now, details)));
        Map<String, Object> payload = incidentPayload(retried, locked.instance());
        payload.put("retriedBy", caller);
        payload.put("reason", why);
        publishIncident(retried, "processes.incident.retried.v1", payload, locked.instance(), now);
        if (!jobs.retryFailed(tenant, job.id(), now)) {
            throw new IllegalStateException("Job " + job.id() + " left FAILED under its lock");
        }
        store.updateCompensation(entry.with(ProcessStore.CompensationEntry.RUNNING, job.id(), null));
        LOG.log(System.Logger.Level.INFO, "process.incident.retried tenant={0} incident={1} retryCount={2} undo=true",
                tenant, incident.id(), retried.retryCount());
        return incidentView(retried, null, caller, java.util.Set.of());
    }

    /**
     * PRE-074, PRE-075: an approved resolution of an undo incident marks the undo done by hand, resolves the incident
     * {@code COMPLETED_MANUALLY} with both people, and the walk goes on, in the caller's transaction.
     */
    private ProcessStore.IncidentRow applyUndoResolution(LockedIncident locked, ProcessStore.IncidentRow pending,
            String approver, String comment, Instant now) {
        ProcessStore.CompensationEntry entry = heldEntry(pending);
        String tenant = pending.tenantId();
        store.updateCompensation(entry.with(ProcessStore.CompensationEntry.DONE, entry.jobId(), now));
        Map<String, Object> details = undoDetails(entry);
        details.put("resolvedBy", approver);
        details.put("manual", true);
        store.appendHistory(tenant, pending.instanceId(), pending.definitionId(), List.of(new HistoryEntry(
                "COMPENSATION_STEP_COMPLETED", entry.stepId(), entry.scopeTokenId(), null, approver, now, details)));
        ProcessStore.IncidentRow current = store.lockIncident(tenant, pending.id()).orElseThrow();
        ProcessStore.IncidentRow resolved = current.resolved(COMPLETED_MANUALLY, approver, now);
        writeIncident(resolved);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("proposedBy", pending.proposedBy());
        extra.put("reason", pending.proposalReason());
        extra.put("output", pending.proposalOutputJson() == null ? Map.of()
                : Values.readMap(pending.proposalOutputJson()));
        extra.put("approvalMode", pending.approvalMode());
        if (comment != null) {
            extra.put("comment", comment);
        }
        recordResolved(resolved, approver, null, extra, now);
        advanceWalk(locked.definition(), tenant, pending.instanceId(), entry.scopeTokenId());
        return resolved;
    }

    // ---- TaskService --------------------------------------------------------------------------------------

    @Override
    public TaskView task(String taskId) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_TASK_WORK);
        return transactions.inTransaction(() -> {
            TaskRow task = store.findTask(tenant, taskId).orElseThrow(() -> new TaskNotFoundException("No task " + taskId));
            InstanceRow row = store.findInstance(tenant, task.instanceId()).orElseThrow();
            return withActions(taskView(task, row), caller, participants(tenant, row));
        });
    }

    @Override
    public TaskView claim(String taskId, long expectedLockVersion, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_TASK_WORK);
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException("Claiming a task needs an Idempotency-Key");
        }
        String requestHash = Hashes.sha256Parts("claim", caller, taskId, String.valueOf(expectedLockVersion));
        TaskView claimed = transactions.inTransaction(() -> idempotent(tenant, "claim", idempotencyKey, requestHash, () -> {
            Locked locked = lockTask(tenant, taskId, expectedLockVersion);
            TaskRow task = locked.task();
            if (!"OPEN".equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is " + task.status());
            }
            if (!assigned(task.assignee(), caller)) {
                throw new TaskNotAssignedException("The caller is not an assignee of task " + taskId);
            }
            if (task.fourEyes() && participants(tenant, locked.instance()).contains(caller)) {
                throw new SelfApprovalForbiddenException("The identity that started this instance or completed an earlier"
                        + " task of it cannot claim this four-eyes task");
            }
            Instant now = clock.instant();
            if (store.claimTask(tenant, taskId, task.lockVersion(), caller, now) != 1) {
                throw new VersionConflictException("Task " + taskId + " changed concurrently");
            }
            recordTaskChange(locked, "TASK_CLAIMED", "processes.task.claimed.v1", caller, now,
                    Map.of("claimedBy", caller));
            return taskView(store.findTask(tenant, taskId).orElseThrow(), locked.instance());
        }, Views::taskToJson, Views::taskFromJson));
        return withActions(claimed, caller, transactions.inTransaction(() -> participants(tenant,
                store.findInstance(tenant, claimed.instanceId()).orElseThrow())));
    }

    @Override
    public TaskView unclaim(String taskId, long expectedLockVersion, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_TASK_WORK);
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException("Unclaiming a task needs an Idempotency-Key");
        }
        String requestHash = Hashes.sha256Parts("unclaim", caller, taskId, String.valueOf(expectedLockVersion));
        TaskView open = transactions.inTransaction(() -> idempotent(tenant, "unclaim", idempotencyKey, requestHash, () -> {
            Locked locked = lockTask(tenant, taskId, expectedLockVersion);
            TaskRow task = locked.task();
            if (!"CLAIMED".equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is " + task.status());
            }
            if (!caller.equals(task.claimedBy())) {
                throw new TaskNotAssignedException("Only the claimant can hand task " + taskId + " back");
            }
            Instant now = clock.instant();
            if (store.unclaimTask(tenant, taskId, task.lockVersion(), caller) != 1) {
                throw new VersionConflictException("Task " + taskId + " changed concurrently");
            }
            recordTaskChange(locked, "TASK_UNCLAIMED", "processes.task.unclaimed.v1", caller, now,
                    Map.of("unclaimedBy", caller));
            return taskView(store.findTask(tenant, taskId).orElseThrow(), locked.instance());
        }, Views::taskToJson, Views::taskFromJson));
        return withActions(open, caller, transactions.inTransaction(() -> participants(tenant,
                store.findInstance(tenant, open.instanceId()).orElseThrow())));
    }

    @Override
    public TaskView reassign(String taskId, String assignee, String reason, long expectedLockVersion,
            String idempotencyKey) {
        return handOver("reassign", "OPEN", taskId, assignee, reason, expectedLockVersion, idempotencyKey);
    }

    @Override
    public TaskView delegate(String taskId, String assignee, String reason, long expectedLockVersion,
            String idempotencyKey) {
        return handOver("delegate", "CLAIMED", taskId, assignee, reason, expectedLockVersion, idempotencyKey);
    }

    /**
     * Reassigns an open task or delegates a claimed one (PD-110), under the instance row lock. Guards in order: the
     * lock version, the status (a completed or cancelled task is never moved), then the target: not the current
     * claimant, not a four-eyes participant, and holding the task's role or group in the host's directory. A refused
     * request writes nothing.
     */
    private TaskView handOver(String action, String fromStatus, String taskId, String assignee, String reason,
            long expectedLockVersion, String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_TASK_MANAGE);
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException("A " + action + " needs an Idempotency-Key");
        }
        if (assignee == null || assignee.isBlank() || assignee.length() > MAX_USER_ID_LENGTH) {
            throw new InputInvalidException("assignee is required, 1 to " + MAX_USER_ID_LENGTH + " characters");
        }
        String why = boundedComment(reason, true);
        if (why.length() > HAND_OVER_REASON_MAX) {
            // PD-110: the length is part of the contract, not a setting.
            throw new InputInvalidException("reason must be at most " + HAND_OVER_REASON_MAX + " characters, was "
                    + why.length());
        }
        String requestHash = Hashes.sha256Parts(action, caller, taskId, assignee, why,
                String.valueOf(expectedLockVersion));
        TaskView moved = transactions.inTransaction(() -> idempotent(tenant, action, idempotencyKey, requestHash, () -> {
            Locked locked = lockTask(tenant, taskId, expectedLockVersion);
            TaskRow task = locked.task();
            if (!fromStatus.equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is " + task.status() + "; only a "
                        + fromStatus + " task can be " + (fromStatus.equals("OPEN") ? "reassigned" : "delegated"));
            }
            requireAssigneeAllowed(tenant, locked, assignee);
            Instant now = clock.instant();
            if (store.handOverTask(tenant, taskId, task.lockVersion(), fromStatus, task.claimedBy(), assignee,
                    now) != 1) {
                throw new VersionConflictException("Task " + taskId + " changed concurrently");
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put(fromStatus.equals("OPEN") ? "reassignedBy" : "delegatedBy", caller);
            if (task.claimedBy() != null) {
                extra.put("previousClaimant", task.claimedBy());
            }
            extra.put("claimedBy", assignee);
            extra.put("reason", why);
            recordTaskChange(locked, fromStatus.equals("OPEN") ? "TASK_REASSIGNED" : "TASK_DELEGATED",
                    fromStatus.equals("OPEN") ? "processes.task.reassigned.v1" : "processes.task.delegated.v1", caller,
                    now, extra);
            return taskView(store.findTask(tenant, taskId).orElseThrow(), locked.instance());
        }, Views::taskToJson, Views::taskFromJson));
        return withActions(moved, caller, transactions.inTransaction(() -> participants(tenant,
                store.findInstance(tenant, moved.instanceId()).orElseThrow())));
    }

    /** The target rules of PD-110; each refusal is {@code ASSIGNEE_NOT_ALLOWED} and writes nothing. */
    private void requireAssigneeAllowed(String tenant, Locked locked, String assignee) {
        TaskRow task = locked.task();
        if (assignee.startsWith(SYSTEM_PREFIX)) {
            throw new AssigneeNotAllowedException(AssigneeNotAllowedException.RESERVED_IDENTITY, null,
                    "Identities starting with " + SYSTEM_PREFIX + " are reserved for the engine");
        }
        if (assignee.equals(task.claimedBy())) {
            throw new AssigneeNotAllowedException(AssigneeNotAllowedException.ALREADY_ASSIGNED, null,
                    assignee + " already holds task " + task.id());
        }
        if (task.fourEyes() && participants(tenant, locked.instance()).contains(assignee)) {
            throw new AssigneeNotAllowedException(AssigneeNotAllowedException.FOUR_EYES_PARTICIPANT, null,
                    assignee + " started this instance or completed an earlier task of it, so cannot work its"
                    + " four-eyes task");
        }
        int colon = task.assignee().indexOf(':');
        String kind = task.assignee().substring(0, colon);
        String value = task.assignee().substring(colon + 1);
        boolean holds = switch (kind) {
            case "role" -> directory.holdsRole(tenant, assignee, value);
            case "group" -> directory.inGroup(tenant, assignee, value);
            default -> true;
        };
        if (!holds) {
            throw new AssigneeNotAllowedException("group".equals(kind) ? AssigneeNotAllowedException.MISSING_GROUP
                    : AssigneeNotAllowedException.MISSING_ROLE, task.assignee(),
                    assignee + " does not hold the " + kind + " the task is assigned to");
        }
    }

    /**
     * Installs the host's role list, which approval asks about the target role of every escalation level (PRE-180).
     * Called once while the engine is wired; without it no role is checked.
     *
     * @param catalogue the catalogue, or null for the default that accepts every role
     */
    public void useRoleCatalogue(io.github.dhluong90.bpm.process.spi.RoleCatalogue catalogue) {
        this.roleCatalogue = catalogue == null ? io.github.dhluong90.bpm.process.spi.RoleCatalogue.ACCEPT_ALL
                : catalogue;
    }

    /**
     * Installs the host's directory of roles and groups, which {@code reassign} and {@code delegate} ask about the
     * target. Called once while the engine is wired; without it no role or group task can be handed to a named user.
     *
     * @param directory the directory, or null for none
     */
    public void useAssigneeDirectory(io.github.dhluong90.bpm.process.spi.AssigneeDirectory directory) {
        this.directory = directory == null ? io.github.dhluong90.bpm.process.spi.AssigneeDirectory.NONE : directory;
    }

    /**
     * Turns on layout revision 1 at import (#445): the diagram part of an imported document is stored as the first
     * layout revision of the version it creates, in the transaction of the import, apart from the definition. Without
     * this call, or for a format with no reader, an import stores no layout, as before.
     *
     * @param layouts the layout store
     * @param extractors per source format, reads the layout document (JSON) out of the imported bytes, or returns null
     */
    public void useImportedLayouts(io.github.dhluong90.bpm.process.internal.store.LayoutStore layouts,
            Map<SourceFormat, java.util.function.BiFunction<byte[], Process, String>> extractors) {
        this.importedLayouts = layouts;
        this.layoutExtractors = extractors == null ? Map.of() : Map.copyOf(extractors);
    }

    /** The layout document of an import, validated against the layout schema and the steps, or null when none. */
    private String importedLayout(ImportRequest request, Process process) {
        var extractor = layoutExtractors.get(request.format());
        if (importedLayouts == null || extractor == null) {
            return null;
        }
        try {
            String text = extractor.apply(request.source(), process);
            if (text == null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Layouts.MAX_BYTES) {
                return null;
            }
            var node = io.github.dhluong90.bpm.process.internal.json.EngineJson.JSON.readTree(text);
            java.util.Set<String> steps = new java.util.HashSet<>();
            for (var element : process.allFlowElements()) {
                if (element instanceof FlowNode n) {
                    steps.add(n.id());
                }
            }
            Layouts.check(node, steps);
            return io.github.dhluong90.bpm.process.internal.json.CanonicalJson.write(node);
        } catch (RuntimeException e) {
            // A diagram that does not fit the schema is not stored; the definition does not depend on it.
            LOG.log(System.Logger.Level.WARNING, "process.import.layout-skipped key={0} exception={1}", process.id(),
                    e.getClass().getName());
            return null;
        }
    }

    @Override
    public ProcessInstance complete(String taskId, Map<String, Object> result, long expectedLockVersion,
            String idempotencyKey) {
        return complete(taskId, result, null, expectedLockVersion, idempotencyKey);
    }

    @Override
    public ProcessInstance complete(String taskId, Map<String, Object> result, String reason, long expectedLockVersion,
            String idempotencyKey) {
        String tenant = tenant();
        String caller = callerWith(PERMISSION_TASK_WORK);
        if (idempotencyKey == null) {
            throw new PreconditionRequiredException("Completing a task needs an Idempotency-Key");
        }
        Map<String, Object> values = result == null ? Map.of() : result;
        String why = boundedComment(reason, false);
        // Without a reason the hash is the one before #59, so a replay across the upgrade still matches.
        String requestHash = why == null
                ? Hashes.sha256Parts("complete", caller, taskId, boundedJson(values, "result"),
                        String.valueOf(expectedLockVersion))
                : Hashes.sha256Parts("complete", caller, taskId, boundedJson(values, "result"),
                        String.valueOf(expectedLockVersion), "reason", why);
        return transactions.inTransaction(() -> idempotent(tenant, "complete", idempotencyKey, requestHash, () -> {
            Locked locked = lockTask(tenant, taskId, expectedLockVersion);
            TaskRow task = locked.task();
            InstanceRow row = locked.instance();
            if ("OPEN".equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is OPEN; claim it first");
            }
            if (!"CLAIMED".equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is " + task.status());
            }
            if (!caller.equals(task.claimedBy())) {
                throw new TaskNotAssignedException("Task " + taskId + " is claimed by another user");
            }
            // A claimant who kept the claim through an escalation (onClaimed: keep) still completes the task.
            if (!assigned(task.assignee(), caller) && store.escalation(tenant, taskId)
                    .map(e -> e.level() == 0).orElse(true)) {
                throw new TaskNotAssignedException("The caller is not an assignee of task " + taskId);
            }
            java.util.Set<String> participants = participants(tenant, row);
            if (task.fourEyes() && participants.contains(caller)) {
                throw new SelfApprovalForbiddenException("The identity that started this instance or completed an earlier"
                        + " task of it cannot complete this four-eyes task");
            }
            java.util.Optional<JobRow> reviewed = checkReviewResult(tenant, row, taskId, values);
            Instant now = clock.instant();
            if (store.completeTask(tenant, taskId, task.lockVersion(), caller, now) != 1) {
                throw new VersionConflictException("Task " + taskId + " changed concurrently");
            }
            reviewed.ifPresent(job -> recordOverride(task, row, job, values, why, caller, now));
            StoredDefinition definition = store.definition(tenant, row.definitionId()).orElseThrow();
            InstanceState state = new InstanceState(row.id(), tenant, row.definitionId(), row.businessKey(),
                    InstanceStatus.valueOf(row.status()), store.tokens(tenant, row.id()), Values.readMap(row.variablesJson()),
                    participants, row.sequence());
            run(definition, row, state, new Command.CompleteTask(taskId, task.tokenId(), values, caller), RunAs.REQUEST);
            return read(tenant, store.findInstance(tenant, row.id()).orElseThrow());
        }, Views::instanceToJson, Views::instanceFromJson));
    }

    /** A task and its instance, read under the instance row lock, with the expected lock version checked. */
    private record Locked(TaskRow task, InstanceRow instance) {
    }

    /**
     * Takes the instance row lock, so every change of one instance's tasks serialises with its completions and its
     * cancellation, then rereads the task and checks the lock version first: a stale request is
     * {@code VERSION_CONFLICT} whatever else changed (PRE-003).
     */
    private Locked lockTask(String tenant, String taskId, long expectedLockVersion) {
        TaskRow found = store.findTask(tenant, taskId).orElseThrow(() -> new TaskNotFoundException("No task " + taskId));
        InstanceRow row = store.lockInstance(tenant, found.instanceId()).orElseThrow();
        TaskRow task = store.findTask(tenant, taskId).orElseThrow();
        if (expectedLockVersion != task.lockVersion()) {
            throw new VersionConflictException("Task " + taskId + " is at lock version " + task.lockVersion());
        }
        return new Locked(task, row);
    }

    /** The audit entry and the event of a claim or an unclaim, in the transaction of the change. */
    private void recordTaskChange(Locked locked, String historyType, String eventType, String actor, Instant now,
            Map<String, Object> extra) {
        TaskRow task = locked.task();
        InstanceRow row = locked.instance();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", task.id());
        details.putAll(extra);
        store.appendHistory(task.tenantId(), row.id(), row.definitionId(), List.of(new HistoryEntry(historyType,
                task.nodeId(), task.tokenId(), null, actor, now, details)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id());
        payload.put("instanceId", row.id());
        payload.put("nodeId", task.nodeId());
        payload.put("assignee", task.assignee());
        payload.putAll(extra);
        payload.put("definitionKey", row.definitionKey());
        payload.put("version", row.definitionVersion());
        if (row.businessKey() != null) {
            payload.put("businessKey", row.businessKey());
        }
        // One event per task transition, so its id is derived from the task and the lock version it reaches.
        publish(new ProcessEventPublisher.Event(io.github.dhluong90.bpm.process.internal.exec.Ids.child(task.id(),
                task.lockVersion() + 1), eventType, task.tenantId(), now, payload), row.id());
    }

    private static TaskView taskView(TaskRow t, InstanceRow row) {
        return new TaskView(t.id(), t.instanceId(), row.definitionKey(), row.definitionVersion(), row.businessKey(),
                t.nodeId(), t.name(), t.assignee(), t.fourEyes(), t.status(), t.lockVersion(), t.claimedBy(),
                t.claimedAt(), t.createdAt(), t.completedBy(), t.completedAt(), java.util.Set.of(),
                t.reviewJson() == null ? null : Values.readMap(t.reviewJson()), t.dueAt());
    }

    /** What this caller may do now; computed per caller, never stored with an idempotent answer. */
    private TaskView withActions(TaskView task, String caller, java.util.Set<String> participants) {
        java.util.Set<String> actions = new java.util.LinkedHashSet<>();
        boolean refusedByFourEyes = task.fourEyes() && participants.contains(caller);
        if ("OPEN".equals(task.status()) && assigned(task.assignee(), caller) && !refusedByFourEyes) {
            actions.add("claim");
        } else if ("CLAIMED".equals(task.status()) && caller.equals(task.claimedBy())) {
            actions.add("unclaim");
            if (assigned(task.assignee(), caller) && !refusedByFourEyes) {
                actions.add("complete");
            }
        }
        if (callers.currentPermissions().contains(PERMISSION_TASK_MANAGE)) {
            if ("OPEN".equals(task.status())) {
                actions.add("reassign");
            } else if ("CLAIMED".equals(task.status())) {
                actions.add("delegate");
            }
        }
        return task.withAllowedActions(actions);
    }

    /**
     * Installs the sink of the engine's measurements. Called once while the engine is wired, before the first request;
     * the engine never calls the sink inside a transaction that can still roll back, except for a failed step.
     *
     * @param sink the sink, or null for none
     */
    public void useMetrics(io.github.dhluong90.bpm.process.spi.ProcessMetrics sink) {
        this.metrics = sink == null ? io.github.dhluong90.bpm.process.spi.ProcessMetrics.NONE : sink;
    }

    /** Counts instance and incident events once their transaction has committed (PRE-084). */
    private void countCommitted(ProcessEventPublisher.Event event) {
        io.github.dhluong90.bpm.process.spi.ProcessMetrics sink = metrics;
        if (sink == io.github.dhluong90.bpm.process.spi.ProcessMetrics.NONE) {
            return;
        }
        Object key = event.payload().get("definitionKey");
        String definitionKey = key == null ? null : key.toString();
        Runnable count = switch (event.type()) {
            case "processes.instance.started.v1" -> () -> sink.instanceStarted(definitionKey);
            case "processes.instance.completed.v1" -> () -> sink.instanceCompleted(definitionKey);
            case "processes.instance.failed.v1" -> () -> sink.instanceFailed(definitionKey);
            case "processes.incident.raised.v1" -> {
                Object code = event.payload().get("errorCode");
                yield () -> sink.incidentRaised(code == null ? null : code.toString());
            }
            default -> null;
        };
        if (count != null) {
            transactions.afterCommit(count);
        }
    }

    /**
     * Hands an event to the publisher inside the transaction. A slow listener holds the instance lock and a
     * connection, so the call is timed and logged above {@code handlers.slow-call-warning} with the event type,
     * instance and duration only, never the payload. A listener exception rolls the transaction back and is reported
     * as {@code EVENT_LISTENER_FAILED}.
     */
    private static final String ESCALATION_ACTOR = "system:timer";
    private static final String ESCALATION_TARGET_FORBIDDEN = "ESCALATION_TARGET_FORBIDDEN";

    private void publish(ProcessEventPublisher.Event event, String instanceId) {
        long started = System.nanoTime();
        try {
            publisher.publish(event.withContext(io.github.dhluong90.bpm.process.spi.EventScope.correlationId(),
                    io.github.dhluong90.bpm.process.spi.EventScope.causationId()));
            countCommitted(event);
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

    /**
     * Four-eyes participants: the starter, everyone who completed a task of the instance (PRE-007), and everyone who
     * reported a result the guardrail refused (#363). The reporter of a refused result is the maker of its review, so
     * an identity that holds both the completer role and the reviewer role cannot approve its own result.
     */
    private java.util.Set<String> participants(String tenant, InstanceRow row) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (row.startedBy() != null) {
            out.add(row.startedBy());
        }
        store.tasks(tenant, row.id()).stream().filter(t -> "COMPLETED".equals(t.status()) && t.completedBy() != null)
                .forEach(t -> out.add(t.completedBy()));
        out.addAll(store.historyActors(tenant, row.id(), "ASYNC_RESULT_REFUSED"));
        return out;
    }

    /** Schema retries this job has had, kept in its payload by {@link JobStore#retrySchema}. */
    private static int schemaRetriesUsed(JobRow job) {
        return Values.readMap(job.payloadJson()).get(JobStore.SCHEMA_RETRIES) instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Gives an attempt whose result broke its output schema back for a fresh attempt, apart from the technical-failure
     * attempts ({@code runners.<kind>.schema-retries}, #363), and records {@code ASYNC_ATTEMPT_FAILED}.
     */
    private void retrySchema(JobRow job, String attemptId, ResultGuard.Verdict.Invalid invalid, int retry, int allowed,
            String reportedBy) {
        Instant now = clock.instant();
        Instant retryAt = retryAt(job, now);
        if (!jobs.retrySchema(job.tenantId(), job.id(), job.lockedBy(), job.attempt(), retryAt)) {
            throw new IllegalStateException("Job " + job.id() + " lost its claim inside its result transaction");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", job.id());
        details.put("attempt", job.attempt());
        details.put("errorCode", invalid.code());
        details.put("retryable", true);
        details.put("final", false);
        details.put("retryAt", retryAt.toString());
        details.put("schemaRetry", retry);
        details.put("schemaRetries", allowed);
        details.put("violations", invalid.violations());
        store.appendHistory(job.tenantId(), job.instanceId(), job.definitionId(), List.of(new HistoryEntry(
                "ASYNC_ATTEMPT_FAILED", job.nodeId(), job.tokenId(), attemptId, reportedBy, now, details)));
        LOG.log(System.Logger.Level.WARNING, "process.async.attempt.failed tenant={0} job={1} attempt={2} code={3}"
                + " schemaRetry={4}/{5}", job.tenantId(), job.id(), job.attempt(), invalid.code(), retry, allowed);
    }

    /**
     * The reviewer's answer to the review task of a refused async result must meet the step's output schema, like
     * the answer it replaces (#363). Nothing is checked for an ordinary user task.
     */
    private java.util.Optional<JobRow> checkReviewResult(String tenant, InstanceRow row, String taskId,
            Map<String, Object> result) {
        java.util.Optional<JobRow> reviewed = store.reviewedJob(tenant, row.id(), taskId).flatMap(id -> jobs.findJob(tenant, id));
        if (reviewed.isEmpty()) {
            return reviewed;
        }
        Map<String, Object> schema = AgentResultGuard.outputSchema(Values.readMap(reviewed.get().payloadJson()));
        List<String> violations = schema == null ? List.of() : outputSchemas.validate(schema, result);
        if (violations == null || !violations.isEmpty()) {
            throw new io.github.dhluong90.bpm.process.api.FormValidationException("The review result breaks the step's"
                    + " output schema", violations == null ? List.of("$: the schema check gave no answer") : violations);
        }
        return reviewed;
    }

    /**
     * A reviewer's answer to the review task of a refused agent answer is a person's output that replaces the agent's
     * (#59): recorded as {@code HUMAN_OVERRIDE} with the reviewer (the actor), the identity that reported the refused
     * answer, the refused attempt, the refusal code and guardrail decision, the reason, the reviewer's answer and the
     * fields that changed, both answers masked by the step's output schema policy. Written in the completion's
     * transaction, after the schema check and the four-eyes check passed.
     */
    private void recordOverride(TaskRow task, InstanceRow row, JobRow job, Map<String, Object> values, String reason,
            String reviewer, Instant now) {
        Map<String, Object> review = task.reviewJson() == null ? Map.of() : Values.readMap(task.reviewJson());
        ResultGuard guard = guards.getOrDefault(job.jobKind(), ResultGuard.NONE);
        Map<String, Object> answer = guard.auditOutput(Values.readMap(job.payloadJson()), values);
        @SuppressWarnings("unchecked")
        Map<String, Object> refused = review.get("output") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", task.id());
        details.put("jobId", job.id());
        if (review.get("attemptId") != null) {
            details.put("refusedAttemptId", review.get("attemptId"));
        }
        if (review.get("reportedBy") != null) {
            details.put("refusedReportedBy", review.get("reportedBy"));
        }
        details.put("code", review.get("code"));
        if (review.get("guardrailDecisionId") != null) {
            details.put("guardrailDecisionId", review.get("guardrailDecisionId"));
        }
        details.put("reviewedBy", reviewer);
        if (reason != null) {
            details.put("reason", reason);
        }
        details.put("output", answer);
        details.put("outputFields", values.size());
        details.put("changes", AgentAudit.diff(refused, answer));
        store.appendHistory(task.tenantId(), row.id(), row.definitionId(), List.of(new HistoryEntry(HUMAN_OVERRIDE,
                task.nodeId(), task.tokenId(), null, reviewer, now, details)));
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

    /**
     * The event scope of work a job starts: the job is the cause, and the correlation is the caller's when the job was
     * handed to the engine inside a request, otherwise the job id, so every event of one job shares one chain.
     */
    private static io.github.dhluong90.bpm.process.spi.EventScope jobScope(String jobId) {
        String correlation = io.github.dhluong90.bpm.process.spi.EventScope.correlationId();
        return io.github.dhluong90.bpm.process.spi.EventScope.open(correlation == null ? jobId : correlation, jobId);
    }

    /** The engine command kinds, which are the {@code stepKind} of the step duration metric (a fixed set). */
    private static String stepKind(Command command) {
        return switch (command) {
            case Command.Start s -> "START";
            case Command.CompleteTask c -> "COMPLETE_TASK";
            case Command.JobDue j -> "JOB_DUE";
            case Command.AsyncResult a -> "ASYNC_RESULT";
            case Command.Cancel c -> "CANCEL";
            default -> "OTHER";
        };
    }

    /**
     * One {@code processes.step.completed.v1} per node completed by a transition, inside the transaction of the step
     * (PRE-085). {@code durationMs} runs from the token's entry at the node to now: from the entry recorded in the same
     * transition, or from the token row when the step waited in an earlier transaction. The payload holds ids and the
     * duration, never a variable or a step output (PRE-086).
     */
    private void publishStepsCompleted(InstanceRow row, InstanceState state, List<HistoryEntry> entries) {
        Map<String, Instant> entered = null;
        for (HistoryEntry e : entries) {
            if (!"NODE_COMPLETED".equals(e.type()) || e.nodeId() == null || e.tokenId() == null) {
                continue;
            }
            Instant since = null;
            for (HistoryEntry earlier : entries) {
                if ("NODE_ENTERED".equals(earlier.type()) && e.tokenId().equals(earlier.tokenId())
                        && e.nodeId().equals(earlier.nodeId())) {
                    since = earlier.occurredAt();
                }
            }
            if (since == null) {
                if (entered == null) {
                    entered = store.tokenEnteredAt(state.tenantId(), state.instanceId());
                }
                since = entered.get(e.tokenId());
            }
            long durationMs = since == null ? 0 : Math.max(0, Duration.between(since, e.occurredAt()).toMillis());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instanceId", state.instanceId());
            payload.put("nodeId", e.nodeId());
            payload.put("attemptId", e.attemptId());
            payload.put("durationMs", durationMs);
            payload.put("definitionKey", row.definitionKey());
            payload.put("version", row.definitionVersion());
            if (row.businessKey() != null) {
                payload.put("businessKey", row.businessKey());
            }
            publish(new ProcessEventPublisher.Event(Ids.child(e.tokenId() + "/" + e.nodeId() + "/" + e.attemptId(),
                    e.occurredAt().toEpochMilli()), "processes.step.completed.v1", state.tenantId(), e.occurredAt(),
                    payload), state.instanceId());
        }
    }

    private List<IncidentSite> run(StoredDefinition definition, InstanceRow row, InstanceState initial, Command first,
            RunAs as) {
        Compiled compiled = compiled(definition);
        InstanceState state = initial;
        List<HistoryEntry> history = new ArrayList<>();
        List<TaskRow> newTasks = new ArrayList<>();
        Map<String, EscalationPlan> escalations = new LinkedHashMap<>();
        List<String> cancelledTasks = new ArrayList<>();
        List<JobRow> newJobs = new ArrayList<>();
        List<String> cancelledJobs = new ArrayList<>();
        // Written once the instance row is saved, because both reference it.
        List<IncidentSite> newIncidents = new ArrayList<>();
        Map<String, ProcessStore.StepResultRow> recorded = new LinkedHashMap<>();
        // #67: undo entries in the order their steps completed, and a compensation the run started.
        List<Effect.PushCompensation> pushes = new ArrayList<>();
        Effect.StartCompensation[] compensation = new Effect.StartCompensation[1];
        // #92: children are started after this instance's own rows are saved, in the same transaction.
        List<Effect.StartChildInstance> childStarts = new ArrayList<>();
        // #496: call-activity tokens whose child is no longer wanted, because a multi-instance loop ended without it.
        java.util.Set<String> cancelledChildTokens = new java.util.LinkedHashSet<>();
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
                Command command = queue.poll();
                long appliedAt = System.nanoTime();
                Transition t;
                try {
                    t = compiled.kernel().apply(state, command, clock);
                } catch (RuntimeException e) {
                    metrics.stepApplied(stepKind(command), "FAILED", Duration.ofNanos(System.nanoTime() - appliedAt));
                    throw e;
                }
                Duration kernelTime = Duration.ofNanos(System.nanoTime() - appliedAt);
                String commandKind = stepKind(command);
                transactions.afterCommit(() -> metrics.stepApplied(commandKind, "OK", kernelTime));
                state = t.state();
                history.addAll(t.history());
                for (Effect effect : t.effects()) {
                    switch (effect) {
                        case Effect.CreateUserTask task -> {
                            Instant created = clock.instant();
                            Instant due = dueAt(state.tenantId(), task, created);
                            newTasks.add(new TaskRow(state.tenantId(), task.taskId(), state.instanceId(),
                                    task.tokenId(), definition.id(), task.nodeId(), task.name(), task.assignee(),
                                    task.fourEyes(), "OPEN", 0, null, null, null, created, null,
                                    task.context().isEmpty() ? null : Values.write(task.context()), due));
                            if (task.escalation() != null && due != null) {
                                escalations.put(task.taskId(), escalationPlan(state.tenantId(), task, due));
                            }
                        }
                        case Effect.CancelUserTask cancel -> cancelledTasks.add(cancel.taskId());
                        case Effect.ScheduleJob job -> newJobs.add(JobRow.due(state.tenantId(), job.jobId(),
                                state.instanceId(), definition.id(), job.tokenId(), job.jobKind(), job.nodeId(),
                                job.dueAt(), jobSettings.timerLease(), jobSettings.maxAttempts(),
                                Values.write(job.payload())));
                        case Effect.CancelJob cancel -> cancelledJobs.add(cancel.jobId());
                        case Effect.RaiseIncident incident -> {
                            // PRE-040: a local step's business error. The code is the business code; the handler's
                            // message stayed in the kernel and is never stored.
                            String attemptId = state.tokens().stream().filter(tk -> tk.id().equals(incident.tokenId()))
                                    .map(io.github.dhluong90.bpm.process.internal.exec.Token::activityInstanceId)
                                    .findFirst().orElse(null);
                            newIncidents.add(new IncidentSite(state.tenantId(), state.instanceId(), definition.id(),
                                    incident.tokenId(), incident.nodeId(), null, attemptId, 0, incident.errorCode(),
                                    incident.detail(), incident.errorCode().startsWith("CHILD_")
                                            ? "The called process ended without completing (" + incident.errorCode()
                                                    + "): " + incident.detail()
                                            : "Business error " + incident.detail() + " has no path in the process"));
                        }
                        case Effect.RecordResult result when compiled.memorySources().contains(result.nodeId()) ->
                                // #101: kept only for the steps a later agent step reads as memory.
                                recorded.put(result.nodeId(), new ProcessStore.StepResultRow(state.tenantId(),
                                        state.instanceId(), result.nodeId(), definition.id(),
                                        Values.write(result.output()), result.reviewed() ? "REVIEW" : "ANSWER",
                                        result.activityInstanceId(), clock.instant()));
                        case Effect.RecordResult result -> {
                            // No later step reads this step's result as memory: nothing more is kept.
                        }
                        case Effect.PushCompensation push -> pushes.add(push);
                        case Effect.StartCompensation started -> compensation[0] = started;
                        case Effect.StartChildInstance child -> childStarts.add(child);
                        case Effect.CancelChildInstance cancel -> cancelledChildTokens.add(cancel.tokenId());
                        case Effect.InvokeService call when call.mode() == ExecutionMode.SYNC_LOCAL -> {
                            DecisionCaller decisionCaller = switch (as) {
                                case RunAs.CallerRequest r -> new DecisionCaller.Request(tenants.currentTenantId(),
                                        callers.currentCallerId());
                                case RunAs.EngineWork w -> new DecisionCaller.Engine(w.actor());
                            };
                            if (call.kind() == ServiceKind.DECISION) {
                                // A request's tenant must be the instance's; the decision adapter checks it again.
                                // Engine work has no caller: the tenant it fires for must be the instance's. Defence
                                // in depth only: the row was locked with the job's tenant, so the two are equal; the
                                // real control is the instance tenant passed explicitly to the decision port.
                                String allowed = switch (as) {
                                    case RunAs.CallerRequest r -> tenants.currentTenantId();
                                    case RunAs.EngineWork w -> w.tenantId();
                                };
                                if (!state.tenantId().equals(allowed)) {
                                    throw new TenantNotAllowedException("The caller's tenant is not the instance's tenant");
                                }
                            }
                            long invokedAt = System.nanoTime();
                            io.github.dhluong90.bpm.process.internal.exec.StepResult result;
                            try {
                                result = invoker.invoke(state.tenantId(), state.instanceId(), state.businessKey(), call,
                                        decisionCaller, clock.instant());
                            } catch (RuntimeException e) {
                                // PRE-071 rolls the step back by propagating the handler's exception; PRE-084 still
                                // reports it, at once, as the one failed step of the result it would have carried.
                                metrics.stepApplied("ASYNC_RESULT", "FAILED",
                                        Duration.ofNanos(System.nanoTime() - invokedAt));
                                throw e;
                            }
                            if (call.kind() == ServiceKind.DECISION
                                    && result instanceof io.github.dhluong90.bpm.process.internal.exec.StepResult.Completed c) {
                                history.add(new HistoryEntry("DECISION_EVALUATED", call.nodeId(), call.tokenId(),
                                        call.attemptId(), null, clock.instant(),
                                        Map.of("ruleSet", call.target(), "decisionId", String.valueOf(c.output().get("decisionId")))));
                            }
                            queue.add(new Command.AsyncResult(call.tokenId(), call.attemptId(), result));
                        }
                        case Effect.InvokeService call when call.mode() == ExecutionMode.ASYNC
                                && call.kind() == ServiceKind.HANDLER -> {
                            // TX of the step: the token waits and a durable job is stored; nothing is called here.
                            // The job id is the kernel's attempt id, so cancelling the token cancels the job.
                            String kind = JobStore.ASYNC_SERVICE;
                            RunnerSettings runner = async.runner(kind, jobSettings);
                            Duration timeout = async.timeout(kind, call.timeout(), jobSettings);
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("target", call.target());
                            payload.put("timeoutMs", timeout.toMillis());
                            if (state.businessKey() != null) {
                                payload.put("businessKey", state.businessKey());
                            }
                            payload.put("inputs", call.inputs());
                            Duration lease = timeout.plus(async.leaseMargin());
                            newJobs.add(JobRow.due(state.tenantId(), call.attemptId(), state.instanceId(),
                                    definition.id(), call.tokenId(), kind, call.nodeId(),
                                    clock.instant(), lease, runner.maxAttempts(), Values.write(payload)));
                            history.add(new HistoryEntry("ASYNC_STEP_SCHEDULED", call.nodeId(), call.tokenId(),
                                    call.attemptId(), null, clock.instant(), Map.of("jobId", call.attemptId(),
                                    "target", call.target(), "leaseMs", lease.toMillis())));
                        }
                        case Effect.InvokeService call when call.mode() == ExecutionMode.ASYNC
                                && call.kind() == ServiceKind.AGENT -> {
                            // Agent step (#52): the same park as a remote step, as an agent job whose guard spec is
                            // read from the node of the version being run, never from the request or the caller.
                            String kind = JobStore.ASYNC_AGENT;
                            RunnerSettings runner = async.runner(kind, jobSettings);
                            Duration timeout = async.timeout(kind, call.timeout(), jobSettings);
                            FlowNode node = compiled.process().node(call.nodeId()).orElseThrow(() ->
                                    new ExecutionException("NODE_NOT_FOUND", call.nodeId(), "No such step"));
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("target", call.target());
                            payload.put("timeoutMs", timeout.toMillis());
                            if (state.businessKey() != null) {
                                payload.put("businessKey", state.businessKey());
                            }
                            payload.put("inputs", call.inputs());
                            // History and the review task read only this copy, with masked inputs fingerprinted (#59).
                            payload.put(AUDIT_INPUTS, AgentAudit.maskInputs(state.tenantId(), node, call.inputs(),
                                    inputMasker));
                            payload.put("guard", AgentGuardSpecs.payload(node));
                            // The step's own retry policy, from the approved definition, or the runner's (#58).
                            RetryPolicy retry = node.extensions().first(RetryPolicy.class).orElse(null);
                            int attempts = retry == null ? runner.maxAttempts() : retry.attempts();
                            if (retry != null && retry.backoff() != null) {
                                payload.put(RETRY_BACKOFF_MS, retry.backoff().toMillis());
                            }
                            Duration lease = timeout.plus(async.leaseMargin());
                            Map<String, Object> scheduled = new LinkedHashMap<>(Map.of("jobId", call.attemptId(),
                                    "target", call.target(), "leaseMs", lease.toMillis(), "jobKind", kind));
                            // #101: memory is resolved once, here, so every attempt sends the same facts.
                            AgentMemory.Resolution.Failed memoryFailed = null;
                            if (!node.extensions().all(MemoryEntry.class).isEmpty()) {
                                switch (resolveMemory(state, node, compiled, recorded)) {
                                    case AgentMemory.Resolution.Resolved m -> {
                                        payload.put(MEMORY, m.memory());
                                        payload.put(AUDIT_MEMORY, m.audit());
                                        scheduled.put(MEMORY, m.audit());
                                        scheduled.put("memoryBytes", m.bytes());
                                        if (!m.missing().isEmpty()) {
                                            scheduled.put("memoryMissing", m.missing());
                                        }
                                    }
                                    case AgentMemory.Resolution.Failed f -> memoryFailed = f;
                                }
                            }
                            history.add(new HistoryEntry("ASYNC_STEP_SCHEDULED", call.nodeId(), call.tokenId(),
                                    call.attemptId(), null, clock.instant(), scheduled));
                            if (memoryFailed == null) {
                                newJobs.add(JobRow.due(state.tenantId(), call.attemptId(), state.instanceId(),
                                        definition.id(), call.tokenId(), kind, call.nodeId(),
                                        clock.instant(), lease, attempts, Values.write(payload)));
                            } else {
                                // PRE-101: the step fails before any call, not retryable; the job is stored FAILED
                                // so it is never claimed, and an incident is opened with it.
                                newJobs.add(new JobRow(state.tenantId(), call.attemptId(), state.instanceId(),
                                        definition.id(), call.tokenId(), kind, call.nodeId(), "FAILED",
                                        clock.instant(), lease, null, null, 0, attempts, Values.write(payload)));
                                Map<String, Object> failed = new LinkedHashMap<>();
                                failed.put("jobId", call.attemptId());
                                failed.put("attempt", 0);
                                failed.put("errorCode", memoryFailed.code());
                                failed.put("retryable", false);
                                failed.put("final", true);
                                if (memoryFailed.entry() != null) {
                                    failed.put("memoryEntry", memoryFailed.entry());
                                }
                                history.add(new HistoryEntry("ASYNC_ATTEMPT_FAILED", call.nodeId(), call.tokenId(),
                                        call.attemptId(), null, clock.instant(), failed));
                                newIncidents.add(new IncidentSite(state.tenantId(), state.instanceId(),
                                        definition.id(), call.tokenId(), call.nodeId(), call.attemptId(),
                                        call.attemptId(), 0, memoryFailed.code(), null, memoryFailed.summary()));
                                LOG.log(System.Logger.Level.WARNING, "process.agent.memory.failed tenant={0} "
                                        + "instance={1} node={2} code={3}", state.tenantId(), state.instanceId(),
                                        call.nodeId(), memoryFailed.code());
                            }
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
                // After the transition's own events (started, task created), so a consumer reads them in order.
                publishStepsCompleted(row, state, t.history());
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
        newTasks.forEach(task -> {
            store.insertTask(task);
            EscalationPlan plan = escalations.get(task.id());
            if (plan != null) {
                store.scheduleEscalation(tenant, task.id(), plan.toJson(), plan.firstAt());
            }
        });
        cancelledTasks.forEach(id -> store.cancelTask(tenant, id));
        newJobs.forEach(job -> jobs.insertJob(job, now));
        cancelledJobs.forEach(id -> jobs.cancelJob(tenant, id, now));
        recorded.values().forEach(store::recordStepResult);
        store.appendHistory(tenant, state.instanceId(), definition.id(), history);
        // After the tokens: an entry references its scope token.
        for (Effect.PushCompensation push : pushes) {
            FlowNode node = compiled.process().node(push.nodeId()).orElseThrow();
            store.pushCompensation(entryOf(push, tenant, state.instanceId(), node, now));
        }
        if (!cancelledChildTokens.isEmpty()) {
            cancelChildren(tenant, state.instanceId(), switch (as) {
                case RunAs.CallerRequest r -> callers.currentCallerId();
                case RunAs.EngineWork w -> w.actor();
            }, cancelledChildTokens);
        }
        for (Effect.StartChildInstance call : childStarts) {
            startChild(definition, row, state, call, newIncidents, now);
        }
        newIncidents.forEach(site -> raiseIncident(site, now));
        if (compensation[0] != null) {
            startCompensation(definition, tenant, state.instanceId(), compensation[0]);
        }
        if (state.status() == InstanceStatus.COMPLETED || state.status() == InstanceStatus.CANCELLED
                || state.status() == InstanceStatus.FAILED) {
            // #92: the instance has ended; if it was called, its parent hears of it through a job, never a lock.
            InstanceState ended = state;
            store.parentLink(tenant, state.instanceId()).ifPresent(link -> scheduleCallResult(tenant, ended, link,
                    history, now));
        }
        return newIncidents;
    }

    /**
     * The due time of a new task (PRE-008): its SLA in working time of its calendar, from its creation on the engine
     * clock, computed once and stored; a later calendar change never moves it. The calendar is read for the tenant of
     * the instance row. An unknown calendar, or one without working time ahead, fails the step and nothing is written.
     */
    private Instant dueAt(String tenant, Effect.CreateUserTask task, Instant created) {
        if (task.sla() == null) {
            return null;
        }
        try {
            return calendar.add(tenant, task.sla().calendar(), created, task.sla().duration());
        } catch (io.github.dhluong90.bpm.process.api.CalendarNotFoundException e) {
            throw new StepFailedException(task.nodeId(), e.code(), "the task's calendar " + task.sla().calendar()
                    + " does not exist in this tenant");
        } catch (IllegalStateException e) {
            throw new StepFailedException(task.nodeId(), "CALENDAR_NO_WORKING_TIME", "the task's calendar "
                    + task.sla().calendar() + " has no working time ahead");
        }
    }

    /** The fire times of a new task's escalation levels (PRE-178), computed once like its due time. */
    private EscalationPlan escalationPlan(String tenant, Effect.CreateUserTask task, Instant dueAt) {
        try {
            return EscalationPlan.resolve(tenant, task.escalation(), task.sla(), dueAt, calendar);
        } catch (io.github.dhluong90.bpm.process.api.CalendarNotFoundException e) {
            throw new StepFailedException(task.nodeId(), e.code(), "the task's calendar " + task.sla().calendar()
                    + " does not exist in this tenant");
        } catch (IllegalStateException e) {
            throw new StepFailedException(task.nodeId(), "CALENDAR_NO_WORKING_TIME", "the task's calendar "
                    + task.sla().calendar() + " has no working time ahead");
        }
    }

    /**
     * Applies every escalation level that has come due on the engine clock (PRE-178 to PRE-180). The read is the
     * bounded sweep {@code escalations-due}, at most {@code jobs.batch-size} tasks across tenants; each task is then
     * escalated in a transaction of its own as {@code system:timer}, under its instance row lock and after a reread, so
     * two replicas that read the same task apply the level once and a task completed or cancelled meanwhile is left
     * alone. One task that fails does not hold back the others.
     *
     * @return the number of levels applied or refused by this call
     */
    public int escalateOverdue() {
        List<ProcessStore.EscalationRow> due = transactions.inTransaction(() -> store.dueEscalations(clock.instant(),
                jobSettings.batchSize()));
        int handled = 0;
        for (ProcessStore.EscalationRow candidate : due) {
            try {
                if (transactions.inTransaction(() -> escalate(candidate))) {
                    handled++;
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "process.task.escalation.failed tenant={0} task={1} "
                        + "exception={2}", candidate.tenantId(), candidate.taskId(), e.getClass().getName());
            }
        }
        return handled;
    }

    private boolean escalate(ProcessStore.EscalationRow candidate) {
        String tenant = candidate.tenantId();
        InstanceRow row = store.lockInstance(tenant, candidate.instanceId()).orElse(null);
        TaskRow task = store.findTask(tenant, candidate.taskId()).orElse(null);
        ProcessStore.EscalationRow state = store.escalation(tenant, candidate.taskId()).orElse(null);
        Instant now = clock.instant();
        if (row == null || task == null || state == null || state.nextAt() == null || state.nextAt().isAfter(now)
                || !"RUNNING".equals(row.status()) || !("OPEN".equals(task.status()) || "CLAIMED".equals(task.status()))) {
            return false;
        }
        EscalationPlan plan = EscalationPlan.fromJson(state.json());
        int level = state.level() + 1;
        if (level > plan.levels().size()) {
            return false;
        }
        String to = plan.levels().get(level - 1).to();
        Instant nextAt = level < plan.levels().size() ? plan.levels().get(level).at() : null;
        String from = task.assignee();
        boolean forbidden = task.fourEyes() && to.startsWith("user:") && participants(tenant, row)
                .contains(to.substring("user:".length()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", task.id());
        details.put("level", level);
        details.put("from", from);
        details.put("to", to);
        if (forbidden) {
            // PRE-180: four eyes holds after any number of levels, so the level is not applied. It is counted, so the
            // sweep does not retry it, and recorded, with an incident for someone to act on.
            if (store.escalateTask(tenant, task.id(), task.lockVersion(), null, false, level, nextAt) != 1) {
                throw new VersionConflictException("Task " + task.id() + " changed concurrently");
            }
            details.put("code", ESCALATION_TARGET_FORBIDDEN);
            store.appendHistory(tenant, row.id(), row.definitionId(), List.of(new HistoryEntry("ESCALATION_REFUSED",
                    task.nodeId(), task.tokenId(), null, ESCALATION_ACTOR, now, details)));
            raiseIncident(new IncidentSite(tenant, row.id(), row.definitionId(), task.tokenId(), task.nodeId(), null,
                    null, 0, ESCALATION_TARGET_FORBIDDEN, null, "Level " + level + " of the escalation of task "
                            + task.id() + " targets a user who made this case"), now);
            return true;
        }
        String releasedClaimant = plan.releaseClaim() && "CLAIMED".equals(task.status()) ? task.claimedBy() : null;
        if (store.escalateTask(tenant, task.id(), task.lockVersion(), to, plan.releaseClaim(), level, nextAt) != 1) {
            throw new VersionConflictException("Task " + task.id() + " changed concurrently");
        }
        if (task.dueAt() != null) {
            details.put("dueAt", task.dueAt().toString());
        }
        if (releasedClaimant != null) {
            details.put("releasedClaimant", releasedClaimant);
        }
        store.appendHistory(tenant, row.id(), row.definitionId(), List.of(new HistoryEntry("TASK_ESCALATED",
                task.nodeId(), task.tokenId(), null, ESCALATION_ACTOR, now, details)));
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("instanceId", row.id());
        payload.put("nodeId", task.nodeId());
        payload.put("definitionKey", row.definitionKey());
        payload.put("version", row.definitionVersion());
        if (row.businessKey() != null) {
            payload.put("businessKey", row.businessKey());
        }
        publish(new ProcessEventPublisher.Event(Ids.child(task.id(), task.lockVersion() + 1),
                "processes.task.escalated.v1", tenant, now, payload), row.id());
        return true;
    }

    /**
     * The memory of an agent step (#101): the recorded results of its source steps in this instance, read in the
     * instance's tenant, with the results recorded earlier in this same run taking precedence.
     */
    private AgentMemory.Resolution resolveMemory(InstanceState state, FlowNode node, Compiled compiled,
            Map<String, ProcessStore.StepResultRow> recordedInRun) {
        java.util.Set<String> sources = new java.util.LinkedHashSet<>();
        node.extensions().all(MemoryEntry.class).forEach(m -> sources.add(m.from()));
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        store.stepResults(state.tenantId(), state.instanceId(), sources)
                .forEach((nodeId, json) -> results.put(nodeId, Values.readMap(json)));
        recordedInRun.forEach((nodeId, row) -> {
            if (sources.contains(nodeId)) {
                results.put(nodeId, Values.readMap(row.resultJson()));
            }
        });
        return AgentMemory.resolve(state.tenantId(), node, id -> compiled.process().node(id), results, memoryLimits,
                inputMasker);
    }

    private Compiled compiled(StoredDefinition definition) {
        return kernels.computeIfAbsent(definition.tenantId() + "/" + definition.id(), k -> {
            Process process = storedProcess(definition);
            CompiledProcess compiledProcess = compiler.compile(definition.id(), process);
            return new Compiled(compiledProcess, new DefaultExecutionKernel(compiledProcess, behaviors, expressions, maxLoopInstances),
                    AgentMemory.sources(process));
        });
    }

    /**
     * Interim form of ADR-0026 decision 4: a version runs and exports from its stored source, so the source must be
     * the one that was validated and hashed at import. Any difference refuses the version.
     */
    /**
     * The model of a stored version, read through the same integrity check as a run or an export. Engine-internal:
     * the migration dry run reads both versions of a plan with it.
     *
     * @param definition the stored version
     * @return its model
     * @throws DefinitionIntegrityException when the stored source no longer gives its recorded hash
     */
    public Process storedModel(StoredDefinition definition) {
        return storedProcess(definition);
    }

    private Process storedProcess(StoredDefinition definition) {
        Function<byte[], Process> reader = readers.get(SourceFormat.valueOf(definition.sourceFormat()));
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
        return process;
    }

    private ProcessInstance read(String tenant, InstanceRow row) {
        List<ProcessInstance.Token> tokens = store.tokens(tenant, row.id()).stream()
                .map(t -> new ProcessInstance.Token(t.id(), t.kind().name(), t.scopeTokenId(), t.nodeId(), t.status().name()))
                .toList();
        List<ProcessInstance.Task> tasks = store.tasks(tenant, row.id()).stream()
                .map(t -> new ProcessInstance.Task(t.id(), t.nodeId(), t.name(), t.assignee(), t.status(), t.lockVersion(),
                        t.claimedBy(), t.claimedAt(), t.completedBy()))
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
        // A host identity can never act as the engine: history and events would credit the engine with its work.
        requireNotSystemIdentity(caller);
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException("Needs " + permission);
        }
        return caller;
    }
}
