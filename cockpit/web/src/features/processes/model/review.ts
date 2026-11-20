import type { ProcessInstance, ReviewContext } from '../../../api/processes/schemas';
import type { Money } from '../../../api/rules/schemas';

/*
 * What the review screen of a refused agent answer shows and sends (CKP-007). The engine decides everything: why
 * the answer was refused, which values are masked, who may complete the review and whether the reviewer's answer
 * meets the output schema. This module only reads its answer and checks the format of what the reviewer typed.
 */

/** Why the engine refused the answer (ReviewContext.code). */
export type Refusal = 'guardrail' | 'guardrail-error' | 'schema' | 'other';

export function refusalOf(code: string): Refusal {
  switch (code) {
    case 'GUARDRAIL_VIOLATION':
      return 'guardrail';
    case 'GUARDRAIL_EVALUATION_ERROR':
      return 'guardrail-error';
    case 'AGENT_SCHEMA_INVALID':
      return 'schema';
    default:
      return 'other';
  }
}

/**
 * The output schema check of the refused answer. The engine checks the schema before the guardrail, so a refusal by
 * the guardrail means the answer passed the schema; `AGENT_SCHEMA_INVALID` means it failed. Any other code is unknown.
 */
export function schemaVerdict(code: string): 'passed' | 'failed' | 'unknown' {
  const refusal = refusalOf(code);
  if (refusal === 'schema') return 'failed';
  return refusal === 'other' ? 'unknown' : 'passed';
}

const MASK = '***';
const FINGERPRINT = /^hmac:([^:]+):([0-9a-f]+)$/i;

/** A value the engine masked: `***`, or the keyed fingerprint `hmac:<keyId>:<hex>` of a masked input. */
export function isMasked(value: unknown): boolean {
  return value === MASK || (typeof value === 'string' && FINGERPRINT.test(value));
}

/** The start of a keyed fingerprint, enough to compare two values by eye: `hmac:k2026:9f4a…`. Never the plain value. */
export function fingerprintHint(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const match = FINGERPRINT.exec(value);
  return match ? `hmac:${match[1] ?? ''}:${(match[2] ?? '').slice(0, 4)}…` : undefined;
}

export function containsMasked(value: unknown): boolean {
  if (isMasked(value)) return true;
  if (Array.isArray(value)) return value.some(containsMasked);
  if (typeof value === 'object' && value !== null) return Object.values(value).some(containsMasked);
  return false;
}

export function isMoney(value: unknown): value is Money {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  const keys = Object.keys(value);
  const record = value as Record<string, unknown>;
  return keys.length === 2 && typeof record['currency'] === 'string' && /^[A-Z]{3}$/.test(record['currency']) && Number.isInteger(record['amount']);
}

/** How the reviewer can change a field of the answer. Nested values and money stay as the agent answered. */
export type FieldKind = 'text' | 'number' | 'boolean' | 'masked' | 'fixed';

export function fieldKind(value: unknown): FieldKind {
  if (isMasked(value)) return 'masked';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'string' || value === null) return 'text';
  return 'fixed';
}

/** The text an editable field starts with: the agent's value, or empty for a masked one. */
export function initialText(value: unknown): string {
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return typeof value === 'string' && !isMasked(value) ? value : '';
}

export type FieldProblem = 'required' | 'number' | 'masked-fixed';

export interface BuiltAnswer {
  result: Record<string, unknown>;
  problems: Record<string, FieldProblem>;
}

const DECIMAL = /^-?\d+(\.\d+)?$/;

/**
 * The reviewer's answer from the agent's answer and the texts typed over it. Only format is checked here: a number
 * field needs a number, and a masked field needs a new value because the cockpit never holds the original. Whether
 * the answer meets the output schema is the engine's check (422 FORM_VALIDATION_FAILED).
 */
export function buildAnswer(output: Readonly<Record<string, unknown>>, texts: Readonly<Record<string, string>>): BuiltAnswer {
  const result: Record<string, unknown> = {};
  const problems: Record<string, FieldProblem> = {};
  for (const [field, original] of Object.entries(output)) {
    const kind = fieldKind(original);
    const text = (texts[field] ?? initialText(original)).trim();
    switch (kind) {
      case 'fixed':
        if (containsMasked(original)) problems[field] = 'masked-fixed';
        result[field] = original;
        break;
      case 'number':
        if (!DECIMAL.test(text)) problems[field] = 'number';
        else result[field] = Number(text);
        break;
      case 'boolean':
        result[field] = text === 'true';
        break;
      case 'masked':
        if (text === '') problems[field] = 'required';
        else result[field] = text;
        break;
      case 'text':
        result[field] = original === null && text === '' ? null : text;
        break;
    }
  }
  return { result, problems };
}

export interface FieldChange {
  field: string;
  from: unknown;
  to: unknown;
}

/** The fields the reviewer changed, in the answer's order, as the engine will record them (`changes`). */
export function changesOf(output: Readonly<Record<string, unknown>>, result: Readonly<Record<string, unknown>>): FieldChange[] {
  return Object.keys(output)
    .filter((field) => field in result && JSON.stringify(output[field]) !== JSON.stringify(result[field]))
    .map((field) => ({ field, from: output[field], to: result[field] }));
}

/**
 * Why this user may not answer the review, from four eyes: the identity that reported the refused answer and the
 * starter of the instance are never the reviewer (PRE-012). The engine refuses them anyway (403
 * SELF_APPROVAL_FORBIDDEN); the screen says so before they try.
 */
export function fourEyesBlock(userId: string, review: Pick<ReviewContext, 'reportedBy'>, instance: Pick<ProcessInstance, 'startedBy'> | undefined): 'reported' | 'started' | undefined {
  if (review.reportedBy !== undefined && review.reportedBy === userId) return 'reported';
  if (instance?.startedBy !== undefined && instance.startedBy !== null && instance.startedBy === userId) return 'started';
  return undefined;
}

/** The engine's FORM_VALIDATION_FAILED violations by top-level field: `$.riskScore: expected integer`. */
export function violationsByField(texts: readonly string[]): { byField: Record<string, string>; other: string[] } {
  const byField: Record<string, string> = {};
  const other: string[] = [];
  for (const text of texts) {
    const match = /^\$\.([A-Za-z0-9_-]+)[^:]*:\s*(.+)$/.exec(text);
    if (match?.[1] !== undefined && match[2] !== undefined) byField[match[1]] = match[2];
    else other.push(text);
  }
  return { byField, other };
}
