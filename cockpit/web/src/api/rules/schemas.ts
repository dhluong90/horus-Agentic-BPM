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
    /** The name people read; optional in the document (the list then shows the rule set name made readable). */
    displayName: z.string().optional(),
  })
  .loose();
export type RuleSetDefinition = z.infer<typeof ruleSetDefinition>;

export const VERSION_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'PUBLISHED', 'RETIRED'] as const;
export type VersionStatus = (typeof VERSION_STATUSES)[number];
/** A version the history still shows after its author discarded it (RLE-098); no list or picker offers it. */
export const HISTORY_STATUSES = [...VERSION_STATUSES, 'DISCARDED'] as const;
export type HistoryStatus = (typeof HISTORY_STATUSES)[number];

const nullableString = z.string().nullable().optional();

/** Lifecycle actions the engine says the caller may take now. A hint for screens; the engine checks again. */
export const ALLOWED_ACTIONS = ['request-publish', 'approve', 'reject', 'withdraw', 'discard'] as const;
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

/**
 * The answer to `:discard` (RLE-098): the version is now DISCARDED, a status no screen lists, so only what the
 * screen needs is read from it.
 */
export const discardedVersion = z.object({ ruleSet: z.string(), version: z.number().int().min(1), status: z.string(), lockVersion: z.number().int() }).loose();
export type DiscardedVersion = z.infer<typeof discardedVersion>;

/** An item of GET /rule-sets/{name}/versions: the lifecycle fields without the definition. */
export const versionSummary = z.object(lifecycle).loose();
export type VersionSummary = z.infer<typeof versionSummary>;

/** An item of GET /rule-sets. */
export const ruleSetSummary = z
  .object({
    ruleSet: z.string(),
    /** The name people read, never empty; `ruleSet` stays the key behind it. */
    displayName: z.string().min(1),
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

/** Why the caller may not approve or reject a pending version (GET /approvals, RLE-190). */
export const PENDING_DENIALS = ['PERMISSION_REQUIRED', 'SELF_APPROVAL_FORBIDDEN', 'AUTHOR_UNKNOWN'] as const;
export type PendingDenial = (typeof PENDING_DENIALS)[number];

/**
 * An item of GET /approvals: a version waiting for a second person, with the hash a reviewer sends back and what
 * this caller may do. Never the definition. An unknown denial code from a newer engine is kept as text.
 */
export const pendingRuleApproval = z
  .object({
    ruleSet: z.string(),
    version: z.number().int().min(1),
    canonicalHash: z.string().regex(/^[0-9a-f]{64}$/),
    lockVersion: z.number().int(),
    requestedBy: z.string(),
    /** The requester as a screen shows them, in the caller's language; the id when the host knows no name (#442). */
    requestedByName: z.string().min(1).optional(),
    requestedAt: z.string(),
    allowedActions: z.array(z.string()),
    deniedActions: z.object({ approve: z.string().optional(), reject: z.string().optional() }).loose(),
  })
  .loose();
export type PendingRuleApproval = z.infer<typeof pendingRuleApproval>;

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

/** Warning codes this cockpit shows. A warning with another code, from a newer engine, is dropped. */
export const WARNING_CODES = ['UNMASKED_PERSONAL_DATA', 'UNCLASSIFIED_INPUT'] as const;
export type WarningCode = (typeof WARNING_CODES)[number];

const anyWarning = z.object({ code: z.string(), input: z.string(), matchedName: z.string().nullable().optional(), message: z.string(), requiresAcknowledgement: z.boolean().optional() }).loose();
/**
 * `requiresAcknowledgement` is the engine's answer to whether Approve must accept this pair (PD-104, RLE-053); the
 * cockpit never works it out itself. An engine that does not send it marks none, and its 409 still names the set.
 */
export interface VersionWarning { code: WarningCode; input: string; matchedName: string | null; message: string; requiresAcknowledgement: boolean }

/** One warning the approver accepts, as the exact pair shown on screen (PD-104, RLE-053). */
export interface AcknowledgedWarning { code: WarningCode; input: string }

/** The reminder that an input is not classified (PD-146): never blocks, never needs an acceptance. */
export const UNCLASSIFIED = 'UNCLASSIFIED_INPUT';
export const isUnclassified = (warning: { code: WarningCode }): boolean => warning.code === UNCLASSIFIED;

const pendingWarning = z.object({ code: z.string(), input: z.string() }).loose();

/**
 * The `warnings` of a 409 WARNINGS_NOT_ACKNOWLEDGED: the full set the engine now needs accepted, read entry by
 * entry; an entry with a code this cockpit cannot show is dropped. Undefined when the member is missing.
 */
export function readPendingWarnings(value: unknown): AcknowledgedWarning[] | undefined {
  if (!Array.isArray(value)) return undefined;
  return value.flatMap((item): AcknowledgedWarning[] => {
    const parsed = pendingWarning.safeParse(item);
    // A reminder (UNCLASSIFIED_INPUT) is never part of what an approver accepts.
    return parsed.success && parsed.data.code !== UNCLASSIFIED && (WARNING_CODES as readonly string[]).includes(parsed.data.code) ? [{ code: parsed.data.code as WarningCode, input: parsed.data.input }] : [];
  });
}

/**
 * What the author and the approver should see before publication (RLE-049). Engines before 0.2.0 send no
 * `warnings`: none. A warning this cockpit cannot read is dropped, never fatal.
 */
const versionWarnings = z
  .array(z.unknown())
  .default([])
  .transform((items) => items.flatMap((item): VersionWarning[] => {
    const parsed = anyWarning.safeParse(item);
    if (!parsed.success || !(WARNING_CODES as readonly string[]).includes(parsed.data.code)) return [];
    return [{ code: parsed.data.code as WarningCode, input: parsed.data.input, matchedName: parsed.data.matchedName ?? null, message: parsed.data.message, requiresAcknowledgement: parsed.data.requiresAcknowledgement ?? false }];
  }));

export const ruleSetVersion = z
  .object({
    ...lifecycle,
    sourceFormat: z.string(),
    sourceHash: z.string(),
    sourceDocument: z.string().optional(),
    definition: ruleSetDefinition,
    warnings: versionWarnings,
  })
  .loose();
export type RuleSetVersion = z.infer<typeof ruleSetVersion>;

/** An input's masking in one version (RLE-051): declared, or decided by the tenant's masking default. */
export const MASKING_STATUSES = ['MASKED', 'NOT_MASKED', 'MASKED_BY_DEFAULT', 'NOT_MASKED_BY_DEFAULT'] as const;
export type MaskingStatus = (typeof MASKING_STATUSES)[number];

export const inputMasking = z
  .object({
    input: z.string(),
    from: z.enum(MASKING_STATUSES).nullable(),
    to: z.enum(MASKING_STATUSES).nullable(),
    changed: z.boolean(),
    personalDataName: z.string().nullable().optional(),
  })
  .loose();
export type InputMasking = z.infer<typeof inputMasking>;

/**
 * The masking column, read entry by entry: an entry this cockpit cannot read (a status from a newer engine, a
 * missing member) is dropped, and the other inputs keep their marks.
 */
const maskingColumn = z
  .array(z.unknown())
  .default([])
  .transform((items) => items.flatMap((item): InputMasking[] => {
    const parsed = inputMasking.safeParse(item);
    return parsed.success ? [parsed.data] : [];
  }));


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

/*
 * The engine's row-level diff of two versions (operation diffVersions, RLE-028 and RLE-048, rule-engine DESIGN
 * section 4.5). A value is the canonical node: Money for a money literal in a `then` cell or an output default,
 * the canonical text for any other cell, null when the member is absent on that side.
 */
export const fieldChange = z
  .object({ path: z.string(), from: z.unknown(), to: z.unknown(), nonAscii: z.boolean() })
  .loose();
export type FieldChange = z.infer<typeof fieldChange>;

const columnChanges = z
  .object({
    added: z.array(z.string()),
    removed: z.array(z.string()),
    changed: z.array(z.object({ name: z.string(), fields: z.array(fieldChange) }).loose()),
    reordered: z.boolean(),
  })
  .loose();
export type ColumnChanges = z.infer<typeof columnChanges>;

export const ROW_CHANGES = ['ADDED', 'REMOVED', 'CHANGED', 'MOVED'] as const;
export type RowChangeKind = (typeof ROW_CHANGES)[number];

const rowChange = z
  .object({
    ruleId: z.string(),
    change: z.enum(ROW_CHANGES),
    position: z.number().int().min(1).optional(),
    basePosition: z.number().int().min(1).optional(),
    moved: z.boolean().optional(),
    fields: z.array(fieldChange).optional(),
  })
  .loose();
export type RowChange = z.infer<typeof rowChange>;

export const versionDiff = z
  .object({
    ruleSet: z.string(),
    version: z.number().int().min(1),
    base: z.number().int().min(1),
    canonicalHash: z.string(),
    baseCanonicalHash: z.string(),
    sourceHashChanged: z.boolean(),
    effectiveFrom: z.object({ from: z.string().nullable(), to: z.string().nullable() }).loose(),
    header: z.array(fieldChange),
    inputs: columnChanges,
    outputs: columnChanges,
    rows: z.array(rowChange),
    unchangedCount: z.number().int().min(0),
    /** Each input's masking in both versions (RLE-051); engines before 0.2.0 send none. */
    masking: maskingColumn,
  })
  .loose();
export type VersionDiff = z.infer<typeof versionDiff>;

/**
 * One cell problem of a `:validate` answer (RLE-090): where it is, a stable code, and the engine's catalogue
 * message by language code. The message never repeats the cell's text, so a masked value is never echoed.
 */
const validationProblem = z
  .object({
    ruleId: z.string().nullable(),
    column: z.string().nullable(),
    code: z.string(),
    position: z.number().int().nullable().optional(),
    message: z.record(z.string(), z.string()),
  })
  .loose();
export type ValidationProblem = z.infer<typeof validationProblem>;

const ruleReading = z.object({ ruleId: z.string(), text: z.record(z.string(), z.string()) }).loose();

/** The answer of validateDraft: nothing was stored; `valid` says whether a save of the same body would be accepted. */
export const draftValidation = z
  .object({
    ruleSet: z.string(),
    version: z.number().int().min(1),
    valid: z.boolean(),
    problems: z.array(validationProblem),
    problemsTruncated: z.boolean(),
    readings: z.array(ruleReading),
    /** True when the draft is valid but too large for readings: `readings` is then empty and `readingsReason` says why. */
    readingsTruncated: z.boolean().optional(),
    readingsReason: z.string().nullable().optional(),
  })
  .loose();
export type DraftValidation = z.infer<typeof draftValidation>;

/*
 * Plain-language readings (readVersion, readDiff, simulateReadings; RLE-091 to RLE-093). The engine writes every
 * sentence in every language of its catalogue; the cockpit only picks the user's language and style (PD-027).
 */
export const readingText = z.object({ full: z.string(), short: z.string(), source: z.string().optional() }).loose();
export type ReadingText = z.infer<typeof readingText>;

export const readingsByLanguage = z.record(z.string(), readingText);
export type ReadingsByLanguage = z.infer<typeof readingsByLanguage>;

export const ruleSetReadings = z
  .object({
    ruleSet: z.string(),
    version: z.number().int(),
    languages: z.array(z.string()),
    rows: z.array(z.object({ ruleId: z.string(), position: z.number().int(), readings: readingsByLanguage }).loose()),
  })
  .loose();
export type RuleSetReadings = z.infer<typeof ruleSetReadings>;

export const readingsDiff = z
  .object({
    ruleSet: z.string(),
    version: z.number().int(),
    base: z.number().int(),
    languages: z.array(z.string()),
    rows: z.array(
      z
        .object({
          ruleId: z.string(),
          change: z.string(),
          position: z.number().int().optional(),
          basePosition: z.number().int().optional(),
          readingChanged: z.boolean(),
          previous: readingsByLanguage.nullable().optional(),
          current: readingsByLanguage.nullable().optional(),
        })
        .loose(),
    ),
    unchangedCount: z.number().int(),
  })
  .loose();
export type ReadingsDiff = z.infer<typeof readingsDiff>;
export type ReadingsDiffRow = ReadingsDiff['rows'][number];

const outcomeReading = z.object({ kind: z.string(), ruleIds: z.array(z.string()), readings: readingsByLanguage }).loose();
export type OutcomeReading = z.infer<typeof outcomeReading>;

export const simulationReadings = z
  .object({
    ruleSet: z.string(),
    version: z.number().int(),
    baselineVersion: z.number().int().nullable(),
    languages: z.array(z.string()),
    // `reference` (S1, S2, ...) always names the sample; `label` is the caller's own name for it, or null.
    results: z.array(z.object({ index: z.number().int(), reference: z.string(), label: z.string().nullable(), changed: z.boolean(), simulated: outcomeReading, baseline: outcomeReading.nullable() }).loose()),
  })
  .loose();
export type SimulationReadings = z.infer<typeof simulationReadings>;
export type SimulationReadingResult = SimulationReadings['results'][number];

/**
 * A replay of logged decisions in plain language (simulateHistoryReadings, RLE-202): each decision read in the
 * version that made it and in the draft. A decision that could not be replayed carries `REPLAY_NOT_POSSIBLE`, the names
 * of the masked inputs and no reading; no logged input, masked or not, is ever part of the answer.
 */
export const historyReadings = z
  .object({
    ruleSet: z.string(),
    version: z.number().int(),
    status: z.string(),
    from: z.string(),
    to: z.string(),
    decisionCount: z.number().int(),
    replayedCount: z.number().int(),
    changedCount: z.number().int(),
    notReplayableCount: z.number().int(),
    truncated: z.boolean(),
    languages: z.array(z.string()),
    results: z.array(
      z
        .object({
          decisionId: z.string(),
          loggedVersion: z.number().int(),
          evalClock: z.string(),
          changed: z.boolean(),
          code: z.string().nullable(),
          maskedInputs: z.array(z.string()),
          simulated: outcomeReading.nullable(),
          logged: outcomeReading.nullable(),
        })
        .loose(),
    ),
  })
  .loose();
export type HistoryReadings = z.infer<typeof historyReadings>;
export type HistoryReadingResult = HistoryReadings['results'][number];

/** The period of a replay: `from` inclusive, `to` exclusive (ISO 8601), and the most decisions to replay. */
export interface ReplayRequest {
  from: string;
  to: string;
  limit?: number | undefined;
}

/** One sample of a simulation request: its inputs by name, money as `Money`, and an optional name of 1 to 100 characters. */
export interface SimulationSample {
  label?: string | undefined;
  inputs: Readonly<Record<string, unknown>>;
}

/*
 * Rule set templates (listRuleSetTemplates, RLE-197) and simulation reports (RLE-199 to RLE-201). A template is the
 * host's; a report is stored once, bound to the content hash it was run on, and never holds an input value.
 */
const byLanguage = z.record(z.string(), z.string());

export const ruleSetTemplate = z
  .object({
    id: z.string().min(1),
    labels: byLanguage,
    descriptions: byLanguage,
    source: z.enum(['BUILT_IN', 'HOST']),
    hitPolicy: z.string(),
    inputs: z.array(z.object({ name: z.string(), type: z.string(), allowed: z.array(z.string()) }).loose()),
    outputs: z.array(z.object({ name: z.string(), type: z.string() }).loose()),
    rows: z.array(z.object({ id: z.string(), when: z.record(z.string(), z.string()), then: z.record(z.string(), thenValue) }).loose()),
  })
  .loose();
export type RuleSetTemplate = z.infer<typeof ruleSetTemplate>;
export const ruleSetTemplates = z.object({ templates: z.array(ruleSetTemplate) }).loose();

const reportOutcome = z.object({ kind: z.string(), ruleIds: z.array(z.string()), readings: readingsByLanguage }).loose();

export const REPORT_STATUSES = ['CURRENT', 'OUT_OF_DATE'] as const;
export const simulationReport = z
  .object({
    id: z.string().min(1),
    ruleSet: z.string(),
    version: z.number().int().min(1),
    canonicalHash: z.string(),
    status: z.enum(REPORT_STATUSES),
    attached: z.boolean(),
    createdBy: z.string(),
    createdAt: z.string(),
    scope: z.object({ source: z.enum(['SAMPLES', 'REPLAY']), from: z.string().nullable(), to: z.string().nullable(), limit: z.number().int().nullable(), truncated: z.boolean() }).loose(),
    counts: z.object({ total: z.number().int(), changed: z.number().int(), unchanged: z.number().int(), failed: z.number().int(), notReplayable: z.number().int() }).loose(),
    languages: z.array(z.string()),
    rows: z.array(
      z.object({ reference: z.string().nullable(), decisionId: z.string().nullable(), category: z.enum(['CHANGED', 'FAILED']), before: reportOutcome.nullable(), after: reportOutcome.nullable() }).loose(),
    ),
    rowsTruncated: z.boolean(),
  })
  .loose();
export type SimulationReport = z.infer<typeof simulationReport>;
export type SimulationReportRow = SimulationReport['rows'][number];
export const simulationReportList = z.object({ reports: z.array(simulationReport) }).loose();

/** What a new report is run over: the sample inputs, or the decisions logged in a period (createSimulationReport). */
export type ReportRequest =
  | { source: 'SAMPLES'; samples: readonly { inputs: Readonly<Record<string, unknown>> }[] }
  | { source: 'REPLAY'; from: string; to: string; limit: number };
