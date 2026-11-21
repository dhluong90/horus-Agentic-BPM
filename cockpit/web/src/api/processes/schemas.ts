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
    allowedActions: z.array(z.string()),
    review: reviewContext.optional(),
  })
  .loose();
export type Task = z.infer<typeof task>;

/** The part of an instance the review screen reads: who started it (four eyes). */
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
  })
  .loose();
export type ProcessInstance = z.infer<typeof processInstance>;
