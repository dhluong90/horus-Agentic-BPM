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
  })
  .loose();
export type DefinitionVersion = z.infer<typeof definitionVersion>;

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
    allowedActions: z.array(z.string()),
    review: reviewContext.optional(),
  })
  .loose();
export type Task = z.infer<typeof task>;

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
