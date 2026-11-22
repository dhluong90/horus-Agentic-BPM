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
export const pendingDefinitionApproval = z
  .object({
    key: z.string(),
    name: nullableString,
    version: z.number().int().min(1),
    definitionHash: z.string().regex(/^[0-9a-f]{64}$/),
    lockVersion: z.number().int(),
    requestedBy: z.string(),
    requestedAt: z.string(),
    allowedActions: z.array(z.string()),
    deniedActions: z.object({ approve: z.string().optional(), reject: z.string().optional() }).loose(),
  })
  .loose();
export type PendingDefinitionApproval = z.infer<typeof pendingDefinitionApproval>;
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

export const ACTIVITY_STATES = ['ACTIVE', 'COMPLETED', 'INCIDENT'] as const;

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
    incident: z.object({ code: z.string(), openedAt: z.string() }).loose().nullable(),
  })
  .loose();
export type NodeActivity = z.infer<typeof nodeActivity>;

export const activityEntry = z
  .object({ id: z.string(), type: z.string(), nodeId: z.string().nullable(), actor: z.string().nullable(), occurredAt: z.string() })
  .loose();
export type ActivityEntry = z.infer<typeof activityEntry>;

export const instanceActivities = z
  .object({
    instanceId: z.string(),
    startedAt: z.string(),
    endedAt: z.string().nullable(),
    nodes: z.array(nodeActivity),
    entries: z.array(activityEntry),
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
