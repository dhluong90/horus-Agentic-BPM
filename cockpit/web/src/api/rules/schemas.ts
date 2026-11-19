import { z } from 'zod';

/*
 * Response shapes of the Rule Engine REST API, written from rule-engine/api/openapi.yaml (0.1.0) and the
 * definition schema rule-engine/api/schema/rule-set.v1.schema.json. Unknown members are kept, never rejected,
 * so a newer engine that adds a field still works.
 */

export const money = z.object({ currency: z.string().regex(/^[A-Z]{3}$/), amount: z.number().int() });
export type Money = z.infer<typeof money>;

export const thenValue = z.union([z.string(), z.number(), z.boolean(), z.null()]);
export type ThenValue = z.infer<typeof thenValue>;

const inputClause = z
  .object({
    name: z.string(),
    type: z.string(),
    description: z.string().optional(),
    allowed: z.array(z.union([z.string(), z.number()])).optional(),
    mask: z.boolean().optional(),
  })
  .loose();
export type InputClause = z.infer<typeof inputClause>;

const outputClause = z
  .object({ name: z.string(), type: z.string(), description: z.string().optional() })
  .loose();
export type OutputClause = z.infer<typeof outputClause>;

/** A `when` value is a condition text, or a nested `any`/`all` group list that the minimal editor shows read only. */
const whenValue = z.union([z.string(), z.array(z.unknown())]);

const rule = z
  .object({
    id: z.string(),
    description: z.string().optional(),
    when: z.record(z.string(), whenValue),
    then: z.record(z.string(), thenValue),
  })
  .loose();
export type Rule = z.infer<typeof rule>;

export const ruleSetDefinition = z
  .object({
    schemaVersion: z.number().int(),
    ruleSet: z.string(),
    hitPolicy: z.string(),
    inputs: z.array(inputClause),
    outputs: z.array(outputClause),
    rules: z.array(rule),
    description: z.string().optional(),
  })
  .loose();
export type RuleSetDefinition = z.infer<typeof ruleSetDefinition>;

export const VERSION_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'RETIRED'] as const;
export type VersionStatus = (typeof VERSION_STATUSES)[number];

const nullableString = z.string().nullable().optional();

/** Lifecycle actions the engine says the caller may take now. A hint for screens; the engine checks again. */
export const ALLOWED_ACTIONS = ['request-publish', 'approve', 'reject', 'withdraw'] as const;
export type AllowedAction = (typeof ALLOWED_ACTIONS)[number];
/** Unknown actions from a newer engine are dropped, never rejected. */
const allowedActions = z.array(z.string()).transform((items) => items.filter((item): item is AllowedAction => (ALLOWED_ACTIONS as readonly string[]).includes(item)));

const lifecycle = {
  ruleSet: z.string(),
  version: z.number().int().min(1),
  status: z.enum(VERSION_STATUSES),
  hitPolicy: z.string(),
  effectiveFrom: nullableString,
  lockVersion: z.number().int(),
  canonicalHash: z.string(),
  requestedBy: nullableString,
  requestedAt: nullableString,
  approvedBy: nullableString,
  approvedAt: nullableString,
  createdBy: nullableString,
  createdAt: z.string(),
  allowedActions: allowedActions.default([]),
};

/** An item of GET /rule-sets/{name}/versions: the lifecycle fields without the definition. */
export const versionSummary = z.object(lifecycle).loose();
export type VersionSummary = z.infer<typeof versionSummary>;

/** An item of GET /rule-sets. */
export const ruleSetSummary = z
  .object({
    ruleSet: z.string(),
    latestVersion: z.number().int().min(1),
    latestStatus: z.enum(VERSION_STATUSES),
    publishedVersion: z.number().int().nullable(),
    lastChangedAt: z.string(),
  })
  .loose();
export type RuleSetSummary = z.infer<typeof ruleSetSummary>;

/** The page shape of every list endpoint: { data, nextCursor, hasNext }. */
export function page<T extends z.ZodType>(item: T) {
  return z.object({ data: z.array(item), nextCursor: z.string().nullable(), hasNext: z.boolean() }).loose();
}
export interface Page<T> {
  data: T[];
  nextCursor: string | null;
  hasNext: boolean;
}

export const HISTORY_ACTIONS = ['CREATED', 'REQUESTED', 'APPROVED', 'REJECTED'] as const;
export const historyEntry = z
  .object({
    action: z.string(),
    fromStatus: z.string().nullable().optional(),
    toStatus: z.string(),
    actor: z.string(),
    reason: nullableString,
    lockVersion: z.number().int(),
    occurredAt: z.string(),
  })
  .loose();
export type HistoryEntry = z.infer<typeof historyEntry>;
export const versionHistory = z.object({ data: z.array(historyEntry) }).loose();

export const ruleSetVersion = z
  .object({
    ...lifecycle,
    sourceFormat: z.string(),
    sourceHash: z.string(),
    sourceDocument: z.string().optional(),
    definition: ruleSetDefinition,
  })
  .loose();
export type RuleSetVersion = z.infer<typeof ruleSetVersion>;

export const decisionResult = z
  .object({
    decisionId: z.string(),
    ruleSet: z.string(),
    version: z.number().int(),
    evaluatedAt: z.string(),
    outcome: z.enum(['MATCHED', 'NO_MATCH']),
    matchedRuleIds: z.array(z.string()),
    outputs: z.record(z.string(), z.unknown()),
  })
  .loose();
export type DecisionResult = z.infer<typeof decisionResult>;
