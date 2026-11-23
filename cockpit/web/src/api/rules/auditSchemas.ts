import { z } from 'zod';
import { historyEntry, HISTORY_STATUSES } from './schemas';

/*
 * Response shapes of the Rule Engine's audit reads (rule-engine/api/openapi.yaml): the decision log
 * (searchDecisions, getDecision), the plain-language explanation (explainDecision, RLE-030, RLE-031), and a rule
 * set's history across its versions with its evidence exports (getRuleSetHistory, PD-116). Unknown members are
 * kept, never rejected, so a newer engine that adds a field still works.
 */

const nullableString = z.string().nullable().optional();

/** The outcomes of a logged decision, as the Rule Engine API names them (DecisionOutcome in its OpenAPI contract). */
export const DECISION_OUTCOMES = ['MATCHED', 'NO_MATCH', 'UNIQUE_HIT_VIOLATION', 'EVALUATION_ERROR'] as const;
export type DecisionOutcome = (typeof DECISION_OUTCOMES)[number];

/** An item of GET /decisions and the body of GET /decisions/{id}. Masked inputs arrive as `hmac:<keyId>:<hex>`. */
export const loggedDecision = z
  .object({
    decisionId: z.string(),
    ruleSet: z.string(),
    version: z.number().int(),
    hitPolicy: z.string(),
    outcome: z.string(),
    errorCode: nullableString,
    inputs: z.record(z.string(), z.unknown()),
    outputs: z.record(z.string(), z.unknown()).nullable().optional(),
    matchedRuleIds: z.array(z.string()),
    callerId: nullableString,
    onBehalfOf: nullableString,
    correlationId: nullableString,
    evalClock: z.string(),
    evaluatedAt: z.string(),
    clockChosen: z.boolean(),
    pinned: z.boolean(),
  })
  .loose();
export type LoggedDecision = z.infer<typeof loggedDecision>;

export const decisionLogPage = z.object({ data: z.array(loggedDecision), nextCursor: z.string().nullable(), hasNext: z.boolean() }).loose();
export type DecisionLogPage = z.infer<typeof decisionLogPage>;

const explainedCondition = z
  .object({
    input: z.string(),
    path: z.string(),
    test: z.string(),
    value: z.unknown(),
    masked: z.boolean(),
    missing: z.boolean(),
    result: z.boolean().nullable(),
  })
  .loose();
export type ExplainedCondition = z.infer<typeof explainedCondition>;

const explainedRow = z
  .object({
    ruleId: z.string(),
    position: z.number().int().min(1),
    conditions: z.array(explainedCondition),
    outputs: z.record(z.string(), z.unknown()),
  })
  .loose();
export type ExplainedRow = z.infer<typeof explainedRow>;

/** The engine's explanation of one decision. `text` is shown as sent; the cockpit never parses or rewrites it (PD-027). */
export const decisionExplanation = z
  .object({
    decisionId: z.string(),
    ruleSet: z.string(),
    version: z.number().int(),
    hitPolicy: z.string(),
    outcome: z.string(),
    errorCode: nullableString,
    clock: z.string(),
    matched: z.array(explainedRow),
    outputs: z.record(z.string(), z.unknown()).nullable(),
    reasons: z.array(z.object({ code: z.string(), params: z.record(z.string(), z.unknown()) }).loose()),
    language: z.string(),
    text: z.string(),
  })
  .loose();
export type DecisionExplanation = z.infer<typeof decisionExplanation>;

export const evidenceSummary = z
  .object({
    format: z.enum(['csv', 'json']),
    versions: z.array(z.number().int()),
    decisionIds: z.array(z.string()),
    contentSha256: z.string().regex(/^[0-9a-f]{64}$/),
    bytes: z.number().int().min(0),
  })
  .loose();
export type EvidenceSummary = z.infer<typeof evidenceSummary>;

/** A rule set's own event. Only evidence exports exist today; an action from a newer engine is dropped by the reader. */
const ruleSetEvent = z
  .object({ action: z.string(), actor: z.string(), occurredAt: z.string(), evidence: evidenceSummary })
  .loose();
export type RuleSetEvent = z.infer<typeof ruleSetEvent>;

export const versionHistoryLine = z
  .object({
    version: z.number().int().min(1),
    status: z.enum(HISTORY_STATUSES),
    effectiveFrom: nullableString,
    effectiveTo: nullableString,
    canonicalHash: z.string(),
    createdBy: nullableString,
    createdAt: z.string(),
    requestedBy: nullableString,
    requestedAt: nullableString,
    approvedBy: nullableString,
    approvedAt: nullableString,
    approvalComment: nullableString,
    rejectedBy: nullableString,
    rejectedAt: nullableString,
    rejectionReason: nullableString,
    events: z.array(historyEntry),
    eventsTruncated: z.boolean(),
  })
  .loose();
export type VersionHistoryLine = z.infer<typeof versionHistoryLine>;

export const ruleSetHistory = z
  .object({
    ruleSet: z.string(),
    data: z.array(versionHistoryLine),
    ruleSetEvents: z
      .array(z.unknown())
      .default([])
      .transform((items) => items.flatMap((item): RuleSetEvent[] => {
        const parsed = ruleSetEvent.safeParse(item);
        return parsed.success && parsed.data.action === 'EVIDENCE_EXPORTED' ? [parsed.data] : [];
      })),
    ruleSetEventsTruncated: z.boolean().default(false),
    nextCursor: z.string().nullable(),
    hasNext: z.boolean(),
  })
  .loose();
export type RuleSetHistory = z.infer<typeof ruleSetHistory>;

/** The request body of exportEvidence. At most 100 versions and 100 decisions; the engine checks again. */
export interface EvidenceRequest {
  format: 'csv' | 'json';
  versions: readonly number[];
  decisionIds: readonly string[];
}

/** Most decisions one evidence file may hold (PD-116). The engine refuses more; the cockpit says so before sending. */
export const MAX_EVIDENCE_DECISIONS = 100;

/** A decision id as the engine writes it: a 26-character ULID (PD-108 refuses anything else before a request). */
export const DECISION_ID = /^[0-9A-HJKMNP-TV-Z]{26}$/;

/** A case reference the decision search accepts: 1 to 128 of A-Z a-z 0-9 . _ : - */
export const CASE_REFERENCE = /^[A-Za-z0-9._:-]{1,128}$/;
