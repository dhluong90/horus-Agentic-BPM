import { z } from 'zod';
import { page } from '../rules/schemas';

/*
 * Response shapes of the Process Engine REST API, written from process-engine/api/openapi.yaml (0.2.0). Unknown
 * members are kept, never rejected, so a newer engine that adds a field still works.
 */

export const DEFINITION_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'RETIRED'] as const;
export type DefinitionStatus = (typeof DEFINITION_STATUSES)[number];

const nullableString = z.string().nullable().optional();

export const definitionSummary = z
  .object({
    key: z.string(),
    name: nullableString,
    latestVersion: z.number().int().min(1),
    latestStatus: z.enum(DEFINITION_STATUSES),
    effectiveVersion: z.number().int().nullable().optional(),
    lastChangedAt: z.string(),
  })
  .loose();
export type DefinitionSummary = z.infer<typeof definitionSummary>;
export const definitionSummaryPage = page(definitionSummary);

/** One agent step input or output that declares neither mask true nor mask false (PD-146, PRE-205). */
export interface UnclassifiedField { step: string; field: string; direction: 'INPUT' | 'OUTPUT' }

const unclassifiedEntry = z.object({ code: z.literal('UNCLASSIFIED_FIELD'), step: z.string(), field: z.string(), direction: z.enum(['INPUT', 'OUTPUT']) }).loose();

/** Read entry by entry: a warning this cockpit cannot read is dropped, never fatal; an engine without them sends none. */
const unclassifiedWarnings = z
  .array(z.unknown())
  .optional()
  .transform((items) => (items ?? []).flatMap((item): UnclassifiedField[] => {
    const parsed = unclassifiedEntry.safeParse(item);
    return parsed.success ? [{ step: parsed.data.step, field: parsed.data.field, direction: parsed.data.direction }] : [];
  }));

export const definitionVersion = z
  .object({
    id: z.string(),
    key: z.string(),
    version: z.number().int().min(1),
    status: z.enum(DEFINITION_STATUSES),
    name: nullableString,
    definitionHash: z.string(),
    nodeCount: z.number().int(),
    createdBy: z.string(),
    createdAt: z.string(),
    effectiveFrom: nullableString,
    /** Sent as If-Match on the lifecycle commands (approve, reject). */
    lockVersion: z.number().int().optional(),
    requestedBy: nullableString,
    requestedAt: nullableString,
    approvedBy: nullableString,
    approvedAt: nullableString,
    decisionComment: nullableString,
    /** Reminders that never block (PD-146): agent step fields with no classification. Names only, never a value. */
    warnings: unclassifiedWarnings.optional(),
  })
  .loose();
export type DefinitionVersion = z.infer<typeof definitionVersion>;

const HASH = /^[0-9a-f]{64}$/;

/** One side of a graph diff. The hash is omitted when the diff is truncated (PD-139). */
const graphDiffSide = z
  .object({ version: z.number().int().min(1), status: z.enum(DEFINITION_STATUSES), definitionHash: z.string().regex(HASH).optional() })
  .loose();

export const graphDiffNode = z.object({ id: z.string(), name: nullableString, type: z.string() }).loose();
export type GraphDiffNode = z.infer<typeof graphDiffNode>;

export const graphDiffChange = z
  .object({ id: z.string(), name: nullableString, type: nullableString, path: z.string(), from: z.unknown(), to: z.unknown() })
  .loose();
export type GraphDiffChange = z.infer<typeof graphDiffChange>;

export const graphDiffEdge = z
  .object({ id: nullableString, from: z.string(), fromName: nullableString, to: z.string(), toName: nullableString, kind: z.string() })
  .loose();
export type GraphDiffEdge = z.infer<typeof graphDiffEdge>;

/**
 * Steps and links added, removed and changed between two stored versions (getDefinitionDiff, PRE-025, PRE-176).
 * `approvableHash` is the target's definitionHash, the value an approval names after this review; it is null when
 * the change list was cut off at its limit (`truncated`), because a cut-off list is not a complete review (PD-139).
 */
export const graphDiff = z
  .object({
    definitionKey: z.string(),
    approvableHash: z.string().regex(HASH).nullable(),
    base: graphDiffSide,
    target: graphDiffSide,
    identical: z.boolean(),
    nodesAdded: z.array(graphDiffNode),
    nodesRemoved: z.array(graphDiffNode),
    nodesChanged: z.array(graphDiffChange),
    edgesAdded: z.array(graphDiffEdge),
    edgesRemoved: z.array(graphDiffEdge),
    truncated: z.boolean(),
  })
  .loose();
export type GraphDiff = z.infer<typeof graphDiff>;

export const TASK_STATUSES = ['OPEN', 'CLAIMED', 'COMPLETED', 'CANCELLED'] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

/** Present only on the four-eyes review task of an agent step whose answer was refused (#59). */
export const reviewContext = z
  .object({
    code: z.string(),
    reasons: z.array(z.string()),
    ruleIds: z.array(z.string()),
    guardrailDecisionId: z.string().optional(),
    inputs: z.record(z.string(), z.unknown()),
    output: z.record(z.string(), z.unknown()),
    outputFields: z.number().int(),
    attemptId: z.string().optional(),
    reportedBy: z.string().optional(),
  })
  .loose();
export type ReviewContext = z.infer<typeof reviewContext>;

export const task = z
  .object({
    id: z.string(),
    instanceId: z.string(),
    definitionKey: z.string(),
    definitionVersion: z.number().int(),
    businessKey: nullableString,
    nodeId: z.string(),
    name: nullableString,
    assignee: z.string(),
    fourEyes: z.boolean(),
    status: z.enum(TASK_STATUSES),
    lockVersion: z.number().int(),
    claimedBy: nullableString,
    claimedAt: nullableString,
    createdAt: z.string(),
    completedBy: nullableString,
    completedAt: nullableString,
    dueAt: nullableString,
    allowedActions: z.array(z.string()),
    review: reviewContext.optional(),
  })
  .loose();
export type Task = z.infer<typeof task>;

/**
 * The form a task is completed through, or the link to the host's own screen (`GET /tasks/{id}/form`, PRE-031). The
 * schema and hints stay open: the engine checks them at import, the cockpit only draws what it understands.
 */
export const taskForm = z
  .object({
    schemaVersion: z.number().int(),
    taskId: z.string(),
    lockVersion: z.number().int(),
    form: z
      .object({
        schema: z.record(z.string(), z.unknown()),
        hints: z.record(z.string(), z.unknown()),
        data: z.record(z.string(), z.unknown()),
      })
      .loose()
      .optional(),
    link: z.string().optional(),
  })
  .loose();
export type TaskForm = z.infer<typeof taskForm>;

/**
 * How a task stands against its deadline, when the engine says so (PD-111, #81). Until the list carries it, the
 * queue reads which tasks are overdue from the engine's own `slaBreached=true` query; the browser computes neither.
 */
export const SLA_STATES = ['NONE', 'ON_TIME', 'DUE_SOON', 'BREACHED'] as const;
export type SlaState = (typeof SLA_STATES)[number];

/** One item of `GET /tasks` (listTasks): no variables and no review context. */
export const taskSummary = z
  .object({
    id: z.string(),
    instanceId: z.string(),
    definitionKey: z.string(),
    definitionVersion: z.number().int(),
    businessKey: nullableString,
    nodeId: z.string(),
    name: nullableString,
    assignee: z.string(),
    status: z.enum(['OPEN', 'CLAIMED']),
    lockVersion: z.number().int(),
    claimedBy: nullableString,
    createdAt: z.string(),
    dueAt: nullableString,
    slaState: z.enum(SLA_STATES).optional().catch(undefined),
    overdueBy: nullableString,
  })
  .loose();
export type TaskSummary = z.infer<typeof taskSummary>;
export const taskSummaryPage = page(taskSummary);

export const INSTANCE_STATUSES = ['RUNNING', 'COMPLETED', 'CANCELLED', 'COMPENSATING', 'FAILED'] as const;
export type InstanceStatus = (typeof INSTANCE_STATUSES)[number];

/** A token of an instance: where its execution stands. A status this cockpit does not know is kept as text. */
export const instanceToken = z
  .object({ id: z.string(), kind: z.string(), scopeTokenId: nullableString, nodeId: z.string(), status: z.string() })
  .loose();
export type InstanceToken = z.infer<typeof instanceToken>;

export const instanceTask = z
  .object({ id: z.string(), nodeId: z.string(), name: nullableString, assignee: z.string(), status: z.string(), lockVersion: z.number().int(), claimedBy: nullableString })
  .loose();
export type InstanceTask = z.infer<typeof instanceTask>;

/** An instance with its tokens and tasks (getInstance). The review screen reads who started it (four eyes). */
export const processInstance = z
  .object({
    id: z.string(),
    definitionKey: z.string(),
    definitionVersion: z.number().int(),
    businessKey: nullableString,
    status: z.string(),
    lockVersion: z.number().int(),
    startedBy: nullableString,
    startedAt: z.string(),
    endedAt: nullableString,
    tokens: z.array(instanceToken).optional(),
    tasks: z.array(instanceTask).optional(),
  })
  .loose();
export type ProcessInstance = z.infer<typeof processInstance>;

/**
 * An item of GET /approvals (PRE-190): a definition version waiting for a second person, with the hash a reviewer
 * sends back as expectedDefinitionHash and what this caller may do. Never the definition or a variable.
 */
export const PENDING_KINDS = ['DEFINITION', 'MIGRATION_PLAN', 'CALENDAR_CHANGE'] as const;
export const pendingDefinitionApproval = z
  .object({
    /** What waits: a definition version, a migration plan or a calendar change (#473); an engine without kinds sends only definitions. */
    kind: z.enum(PENDING_KINDS).default('DEFINITION'),
    /** The id of the migration plan or calendar change; the version's own id for a definition. */
    id: nullableString,
    key: z.string(),
    name: nullableString,
    /** The version; for a migration plan the version it moves to. */
    version: z.number().int().min(1),
    /** For a migration plan, the version its cases run on now. */
    sourceVersion: z.number().int().nullable().optional(),
    definitionHash: z.string().regex(/^[0-9a-f]{64}$/).nullable().optional(),
    /** The hash of what the reviewer is shown, sent back with the approval (PD-139); the definition hash on an older engine. */
    contentHash: z.string().regex(/^[0-9a-f]{64}$/).optional(),
    /** What the proposer wrote (a migration plan or a calendar change). */
    reason: nullableString,
    lockVersion: z.number().int(),
    requestedBy: z.string(),
    /** The requester as a screen shows them, in the caller's language; the id when the host knows no name (#442). */
    requestedByName: z.string().min(1).optional(),
    requestedAt: z.string(),
    allowedActions: z.array(z.string()),
    deniedActions: z.object({ approve: z.string().optional(), reject: z.string().optional() }).loose(),
  })
  .loose();
/** `kind` may be absent: an engine from before #473 lists definition versions only. */
export type PendingDefinitionApproval = z.input<typeof pendingDefinitionApproval>;

/** A holiday, a weekly range or the zone of a business calendar, as the engine writes a proposed or previous value. */
const calendarValue = z.object({ zone: z.string(), week: z.record(z.string(), z.array(z.string())).optional(), holidays: z.record(z.string(), z.string()).optional() }).loose();

/** A change of a business calendar waiting for a second person, or decided (listCalendarChanges, getCalendarChange; PRE-181). */
export const calendarChange = z
  .object({
    id: z.string(),
    calendarKey: z.string(),
    status: z.enum(['PENDING', 'APPROVED', 'REJECTED']),
    /** `SINGLE` only when the dev profile let the proposer apply it alone. */
    approval: z.enum(['FOUR_EYES', 'SINGLE']).optional(),
    baseVersion: z.number().int(),
    previous: calendarValue.nullable().optional(),
    proposed: calendarValue,
    reason: z.string(),
    proposedBy: z.string(),
    proposedAt: z.string(),
    decidedBy: nullableString,
    decidedAt: nullableString,
    comment: nullableString,
    /** The hash an approval names (PD-139, #473). */
    contentHash: z.string().regex(/^[0-9a-f]{64}$/),
  })
  .loose();
export type CalendarChange = z.infer<typeof calendarChange>;

/** A calendar in force: its zone, working hours per weekday and holidays, with the version a change is made against (getCalendar; PRE-181). */
export const calendarInForce = z
  .object({ key: z.string(), version: z.number().int().min(1), calendar: calendarValue, updatedBy: z.string(), approvedBy: z.string(), updatedAt: z.string() })
  .loose();
export type CalendarInForce = z.infer<typeof calendarInForce>;
export type CalendarValue = z.infer<typeof calendarValue>;
export const calendarSummary = z
  .object({ key: z.string(), version: z.number().int().min(1), zone: z.string(), updatedBy: z.string(), approvedBy: z.string(), updatedAt: z.string() })
  .loose();
export type CalendarSummary = z.infer<typeof calendarSummary>;
export const calendarSummaryPage = page(calendarSummary);
export const calendarChangePage = page(calendarChange);
export const pendingDefinitionApprovalPage = page(pendingDefinitionApproval);

export const instanceSummary = z
  .object({
    id: z.string(),
    definitionKey: z.string(),
    definitionVersion: z.number().int(),
    businessKey: nullableString,
    status: z.string(),
    startedBy: nullableString,
    startedAt: z.string(),
    endedAt: nullableString,
  })
  .loose();
export type InstanceSummary = z.infer<typeof instanceSummary>;
export const instanceSummaryPage = page(instanceSummary);

/** `CANCELLED` and `TERMINATED` were added after the first three; `nodeActivity.state` stays a string so a newer value never fails a page. */
export const ACTIVITY_STATES = ['ACTIVE', 'COMPLETED', 'CANCELLED', 'TERMINATED', 'INCIDENT'] as const;

/** What happened at one step (getInstanceActivities, PRE-027): every figure is the engine's, never the browser's. */
export const nodeActivity = z
  .object({
    nodeId: z.string(),
    state: z.string(),
    enteredAt: z.string().nullable(),
    completedAt: z.string().nullable(),
    durationMs: z.number().int().min(0).nullable(),
    attempts: z.number().int().min(0),
    actor: z.string().nullable(),
    /** Why a cancelled or terminated step was cut off (the engine's cause code); absent on older engines and on any other step. */
    cause: z.string().nullable().optional(),
    incident: z.object({ code: z.string(), openedAt: z.string() }).loose().nullable(),
  })
  .loose();
export type NodeActivity = z.infer<typeof nodeActivity>;

const coordinate = z.number().min(-100000).max(100000);

/**
 * The stored layout of a version (`definition-layout.v1`, ADR-0013): the bounds of the steps and the waypoints of
 * the flows as a BPMN import's own diagram drew them. It may list a subset; the rest is laid out automatically.
 */
export const definitionLayout = z
  .object({
    schemaVersion: z.literal(1),
    nodes: z.record(z.string(), z.object({ x: coordinate, y: coordinate, width: z.number().positive().optional(), height: z.number().positive().optional() })),
    edges: z.record(z.string(), z.array(z.object({ x: coordinate, y: coordinate }))).optional(),
    /** Where the diagram puts the name of a flow (BPMNLabel bounds), by `source->target`; a flow not listed has its name placed clear of the steps. */
    labels: z.record(z.string(), z.object({ x: coordinate, y: coordinate, width: z.number().positive(), height: z.number().positive() })).optional(),
  })
  .loose();
export type DefinitionLayout = z.infer<typeof definitionLayout>;

/** `GET .../layout`: a revision with its layout document (`204`, no layout, is read as `undefined`). */
export const layoutRevision = z.object({ revision: z.number().int(), layout: definitionLayout.optional() }).loose();

/** One history entry. The undo fields (#80, PRE-203) are present on the entries of an undo only. */
export const activityEntry = z
  .object({
    id: z.string(),
    type: z.string(),
    nodeId: z.string().nullable(),
    actor: z.string().nullable(),
    occurredAt: z.string(),
    kind: z.string().optional(),
    compensates: z.string().optional(),
    attemptId: z.string().optional(),
    scopeTokenId: z.string().optional(),
    scope: z.string().optional(),
    attempt: z.number().int().min(1).optional(),
    errorCode: z.string().optional(),
    handler: z.string().optional(),
    cause: z.string().optional(),
    /** A `SIGNAL_THROWN` entry: how many waiting subscriptions of the tenant the broadcast woke (#492). */
    waiters: z.number().int().min(0).optional(),
    /** Message steps only (#491): the name, the key as the host masked it, and the delivery result. */
    message: z.object({ name: z.string(), correlationKey: z.string().optional(), result: z.string() }).loose().optional(),
    /** The counters of a loop or multi-instance step when the entry was written (#496); `nrOfInstances` is absent for a standard loop. */
    instances: z
      .object({
        loopCounter: z.number().int().min(1).optional(),
        nrOfInstances: z.number().int().min(0).optional(),
        nrOfActiveInstances: z.number().int().min(0).optional(),
        nrOfCompletedInstances: z.number().int().min(0).optional(),
      })
      .loose()
      .optional(),
    /** A gateway that chose its flows (#494): the ids of the flows it took, and why. */
    flows: z.array(z.string()).optional(),
    reason: z.string().optional(),
    /** An inclusive join that waits (#494): the steps a token can still arrive from. */
    waitingFor: z.array(z.string()).optional(),
    /** `BOUNDARY_FIRED` and `EVENT_GATEWAY_RESOLVED` entries only (#490, #495): what fired, or which catch won and which were cancelled. */
    event: z.object({
      trigger: z.string().optional(),
      name: z.string().optional(),
      interrupting: z.boolean().optional(),
      gateway: z.string().optional(),
      winner: z.string().optional(),
      cancelled: z.array(z.string()).optional(),
    }).loose().optional(),
    /** The `NODE_COMPLETED` of a script step: the variable it set, as recorded when it ran (#518). */
    resultVariable: z.string().optional(),
    /** The `TASK_COMPLETED` of a task with a form (#663): the values as stored; a masked field is `{"masked": true}`. */
    submission: z.record(z.string(), z.unknown()).optional(),
    /** Paths the submission held that the form does not declare; their values are not stored (#663). */
    unrecordedFields: z.array(z.string()).optional(),
  })
  .loose();
export type ActivityEntry = z.infer<typeof activityEntry>;

export const COMPENSATION_STATES = ['IN_PROGRESS', 'UNDONE', 'NOT_FULLY_UNDONE'] as const;

/** Where the undo of one saga scope stands, as the engine states it; the cockpit never infers it (PRE-203). */
export const compensationScope = z
  .object({
    scopeTokenId: z.string(),
    scope: z.string().nullable(),
    state: z.string(),
    startedAt: z.string(),
    endedAt: z.string().nullable(),
    cause: z.string().nullable(),
    failingStep: z.string().nullable(),
    stepsUndone: z.number().int().min(0),
    blockedStep: z.string().nullable(),
    incidentId: z.string().nullable(),
    incidentCode: z.string().nullable(),
  })
  .loose();
export type CompensationScope = z.infer<typeof compensationScope>;

export const instanceActivities = z
  .object({
    instanceId: z.string(),
    startedAt: z.string(),
    endedAt: z.string().nullable(),
    nodes: z.array(nodeActivity),
    entries: z.array(activityEntry),
    /** Absent from an engine older than PRE-203. */
    compensations: z.array(compensationScope).optional(),
    hasMore: z.boolean(),
  })
  .loose();
export type InstanceActivities = z.infer<typeof instanceActivities>;

/*
 * Migration plans (PRE-195, PRE-200, PD-114, PD-131): written from process-engine/api/openapi.yaml. A plan is made
 * once and never edited; the dry run, the proposal and the progress are read apart from it.
 */

export const MIGRATION_STATUSES = ['PLANNED', 'PROPOSED', 'REJECTED', 'APPROVED', 'RUNNING', 'COMPLETED', 'COMPLETED_WITH_FAILURES'] as const;
export type MigrationStatus = (typeof MIGRATION_STATUSES)[number];

export const migrationStepMapping = z.object({ from: z.string(), to: z.string() }).loose();
export type MigrationStepMapping = z.infer<typeof migrationStepMapping>;

export const migrationStepCancellation = z.object({ step: z.string(), moveTo: z.string(), reason: z.string() }).loose();
export type MigrationStepCancellation = z.infer<typeof migrationStepCancellation>;

export const migrationApproval = z.object({ mode: z.enum(['four-eyes', 'single']), secondPersonRequired: z.boolean() }).loose();

export const migrationPlan = z
  .object({
    id: z.string(),
    definitionKey: z.string(),
    sourceVersion: z.number().int().min(1),
    targetVersion: z.number().int().min(1),
    status: z.enum(MIGRATION_STATUSES),
    planHash: z.string(),
    approval: migrationApproval,
    createdBy: z.string(),
    createdAt: z.string(),
    mappings: z.array(migrationStepMapping),
    cancellations: z.array(migrationStepCancellation),
  })
  .loose();
export type MigrationPlan = z.infer<typeof migrationPlan>;

const instanceSample = z.object({ count: z.number().int().min(0), instanceIds: z.array(z.string()), truncated: z.boolean() }).loose();

export const MAPPING_KINDS = ['SAME_ID', 'MAPPED', 'CANCELLED', 'STEP_NOT_IN_TARGET', 'STEP_TYPE_CHANGED'] as const;
export type MappingKind = (typeof MAPPING_KINDS)[number];
export const CONFLICT_CODES = ['STEP_NOT_IN_TARGET', 'STEP_TYPE_CHANGED', 'TARGET_NOT_ACTIVE', 'STEP_IN_FLIGHT'] as const;
export type ConflictCode = (typeof CONFLICT_CODES)[number];

export const migrationDryRun = z
  .object({
    migrationId: z.string(),
    planHash: z.string(),
    definitionKey: z.string(),
    sourceVersion: z.number().int().min(1),
    targetVersion: z.number().int().min(1),
    valid: z.boolean(),
    checkedAt: z.string(),
    validUntil: z.string(),
    approval: migrationApproval,
    instances: z.object({ running: z.number().int(), movable: z.number().int(), blocked: z.number().int() }).loose(),
    nodes: z.array(
      z
        .object({
          nodeId: z.string(),
          name: nullableString,
          type: nullableString,
          liveTokens: z.number().int(),
          mapping: z.enum(MAPPING_KINDS),
          targetNodeId: nullableString,
          targetType: nullableString,
        })
        .loose(),
    ),
    conflicts: z.array(z.object({ code: z.enum(CONFLICT_CODES), nodeId: nullableString, name: nullableString, instances: instanceSample }).loose()),
    movable: instanceSample,
    impact: z.object({ mapped: z.number().int(), cancelled: z.number().int(), inFlight: z.number().int(), unmapped: z.number().int() }).loose(),
  })
  .loose();
export type MigrationDryRun = z.infer<typeof migrationDryRun>;

export const migrationExecution = z
  .object({
    migrationId: z.string(),
    status: z.enum(MIGRATION_STATUSES),
    approval: migrationApproval,
    lockVersion: z.number().int(),
    proposedBy: nullableString,
    proposedAt: nullableString,
    reason: nullableString,
    approvedBy: nullableString,
    approvedAt: nullableString,
    rejectedBy: nullableString,
    rejectedAt: nullableString,
    rejectionReason: nullableString,
    progress: z.object({ total: z.number().int(), migrated: z.number().int(), failed: z.number().int(), skipped: z.number().int() }).loose(),
    startedAt: nullableString,
    finishedAt: nullableString,
  })
  .loose();
export type MigrationExecution = z.infer<typeof migrationExecution>;

export const migrationOutcome = z
  .object({
    instanceId: z.string(),
    outcome: z.enum(['MIGRATED', 'FAILED', 'SKIPPED']),
    code: z.string(),
    detail: z.string(),
    occurredAt: z.string(),
    incidentId: nullableString,
  })
  .loose();
export type MigrationOutcome = z.infer<typeof migrationOutcome>;
export const migrationOutcomePage = z.object({ items: z.array(migrationOutcome), nextAfter: z.string().nullable().optional() }).loose();
export type MigrationOutcomePage = z.infer<typeof migrationOutcomePage>;
/** Incident statuses (PRE-077, PRE-078): open, a resolution waiting for a second person (PD-126), resolved. */
export const INCIDENT_STATUSES = ['OPEN', 'RESOLUTION_PENDING', 'RESOLVED'] as const;
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number];

/** What this caller may do with an incident now; computed per caller by the engine. Unknown actions are dropped. */
export const INCIDENT_ACTIONS = ['retry', 'resolve', 'approve-resolution', 'reject-resolution'] as const;
export type IncidentAction = (typeof INCIDENT_ACTIONS)[number];
const incidentActions = z.array(z.string()).transform((items) => items.filter((item): item is IncidentAction => (INCIDENT_ACTIONS as readonly string[]).includes(item)));

/** A proposed resolution: the output and reason one operator supplied, waiting for a second (PD-126). */
export const pendingResolution = z
  .object({
    proposedBy: z.string(),
    proposedAt: z.string(),
    reason: nullableString,
    output: z.record(z.string(), z.unknown()),
    approvalMode: z.string().nullable().optional(),
  })
  .loose();
export type PendingResolution = z.infer<typeof pendingResolution>;

/** One incident of `GET /incidents` and `GET /incidents/{id}`, with the actions this caller may take. */
export const incident = z
  .object({
    id: z.string(),
    instanceId: z.string(),
    nodeId: z.string(),
    tokenId: nullableString,
    errorCode: z.string(),
    causeSummary: z.string(),
    retryCount: z.number().int().min(0),
    status: z.enum(INCIDENT_STATUSES),
    lockVersion: z.number().int().min(0),
    retrying: z.boolean(),
    createdAt: z.string(),
    resolvedAt: nullableString,
    resolution: nullableString,
    resolvedBy: nullableString,
    pendingResolution: pendingResolution.nullable(),
    allowedActions: incidentActions,
  })
  .loose();
export type Incident = z.infer<typeof incident>;
export const incidentPage = page(incident);
