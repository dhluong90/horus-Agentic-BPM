import type { HostError } from '../../../api/hostError';
import type { Incident, IncidentStatus } from '../../../api/processes/schemas';
import type { IncidentTab } from '../../../app/route';
import type { Locale } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { currencyDigits, toMinorUnits } from '../../rules/model/money';

/*
 * The incident queue's rules that are not the engine's (CKP-008, PD-126): which list a tab reads, how a cause reads
 * in words, why an action is unavailable, how the operator's result fields become the step's output, and what a
 * refusal means. The engine decides who may do what (`allowedActions`) and checks the output against the step's
 * schema; nothing here decides either.
 */

/** The permission that retries and resolves incidents; reading them needs `processes:read`. */
export const MANAGE_INCIDENTS = 'processes:incident:manage';

/** The status each tab lists: open incidents, and resolutions waiting for a second person. */
export function statusOf(tab: IncidentTab): IncidentStatus {
  return tab === 'pending' ? 'RESOLUTION_PENDING' : 'OPEN';
}

/** Error codes this cockpit can say in words; any other reads as a general failure, its code behind Details. */
const CAUSES = ['ADAPTER_TIMEOUT', 'AGENT_TIMEOUT', 'UNHANDLED_BUSINESS_ERROR', 'COMPENSATION_FAILED', 'COMPENSATION_MANUAL_REQUIRED', 'SERVICE_ERROR'] as const;
type Cause = (typeof CAUSES)[number];

export function causeKey(errorCode: string): MessageKey {
  const known = CAUSES.find((code): code is Cause => code === errorCode);
  return known !== undefined ? `incidents.cause.${known}` : 'incidents.cause.other';
}

/** An undo of a compensation walk: its resolution records how the step was undone by hand (PRE-074, PRE-075). */
export function isUndo(incident: Pick<Incident, 'errorCode'>): boolean {
  return incident.errorCode === 'COMPENSATION_FAILED' || incident.errorCode === 'COMPENSATION_MANUAL_REQUIRED';
}

/** Why Retry is not offered, in words; undefined when the engine offers it. */
export function retryBlock(incident: Incident, canManage: boolean): MessageKey | undefined {
  if (incident.allowedActions.includes('retry')) return undefined;
  if (!canManage) return 'incidents.why.permission';
  if (incident.retrying) return 'incidents.why.retrying';
  if (incident.errorCode === 'COMPENSATION_MANUAL_REQUIRED') return 'incidents.why.manual';
  if (incident.status === 'RESOLUTION_PENDING') return 'incidents.why.pending';
  return 'incidents.why.retryOther';
}

/** Why Approve is not offered on a pending resolution; undefined when the engine offers it. */
export function approveBlock(incident: Incident, userId: string, canManage: boolean): MessageKey | undefined {
  if (incident.allowedActions.includes('approve-resolution')) return undefined;
  if (!canManage) return 'incidents.why.permission';
  if (incident.pendingResolution?.proposedBy === userId) return 'incidents.why.self';
  return 'incidents.why.approveOther';
}

/** The host's `incidents.resolve-approval` setting (PD-126): four eyes unless the host says single (dev only). */
export function resolveMode(settings: Readonly<Record<string, unknown>> | undefined): 'four-eyes' | 'single' {
  return settings?.['incidents.resolve-approval'] === 'single' ? 'single' : 'four-eyes';
}

/** A field of the result the operator supplies: a name, the kind of value, and the value as typed. */
export interface OutputField {
  key: string;
  name: string;
  kind: 'text' | 'number' | 'amount' | 'yesno';
  text: string;
  currency: string;
  yes: boolean;
}

export type FieldProblem = 'name-missing' | 'name-format' | 'name-duplicate' | 'value-missing' | 'number' | 'amount' | 'currency';

export type BuiltOutput =
  | { ok: true; output: Record<string, unknown> }
  | { ok: false; problems: Readonly<Record<string, FieldProblem>>; empty: boolean };

/** A field name as the step's output schema names it: letters, digits and underscores, not starting with a digit. */
const NAME = /^[A-Za-z_][A-Za-z0-9_]{0,99}$/;

/**
 * The output object from the fields, with no float arithmetic: an amount becomes `{ amount, currency }` in integer
 * minor units, a number is read from its digits, yes or no is a boolean. Only the format is checked here; the engine
 * checks the output against the step's schema and answers `422 OUTPUT_INVALID` with the failing paths.
 */
export function buildOutput(fields: readonly OutputField[], locale: Locale): BuiltOutput {
  const problems: Record<string, FieldProblem> = {};
  const output: Record<string, unknown> = {};
  const seen = new Set<string>();
  for (const field of fields) {
    const name = field.name.trim();
    if (name === '') { problems[field.key] = 'name-missing'; continue; }
    if (!NAME.test(name)) { problems[field.key] = 'name-format'; continue; }
    if (seen.has(name)) { problems[field.key] = 'name-duplicate'; continue; }
    seen.add(name);
    const text = field.text.trim();
    switch (field.kind) {
      case 'yesno':
        output[name] = field.yes;
        break;
      case 'text':
        if (text === '') problems[field.key] = 'value-missing';
        else output[name] = text;
        break;
      case 'number': {
        const plain = locale === 'vi' ? text.split('.').join('').replace(',', '.') : text.split(',').join('');
        if (text === '') problems[field.key] = 'value-missing';
        else if (!/^-?\d+(\.\d+)?$/.test(plain)) problems[field.key] = 'number';
        else output[name] = Number(plain);
        break;
      }
      case 'amount': {
        const currency = field.currency.trim().toUpperCase();
        if (!/^[A-Z]{3}$/.test(currency)) { problems[field.key] = 'currency'; break; }
        if (text === '') { problems[field.key] = 'value-missing'; break; }
        const minor = toMinorUnits(text, currency, locale);
        if (!minor.ok) problems[field.key] = 'amount';
        else output[name] = { amount: minor.amount, currency };
        break;
      }
    }
  }
  if (fields.length === 0) return { ok: false, problems, empty: true };
  return Object.keys(problems).length > 0 ? { ok: false, problems, empty: false } : { ok: true, output };
}

/** The fraction digits an amount field accepts, for its hint: `VND` none, `USD` two. */
export function amountDigits(currency: string): number | undefined {
  return /^[A-Z]{3}$/.test(currency.trim().toUpperCase()) ? currencyDigits(currency.trim().toUpperCase()) : undefined;
}

/** The field names of a `422 OUTPUT_INVALID`: `paths` such as `/riskScore` or `$.riskScore`, never a value. */
export function failingFields(error: HostError): string[] {
  if (error.kind !== 'problem' || error.code !== 'OUTPUT_INVALID') return [];
  const paths = error.members?.['paths'];
  if (!Array.isArray(paths)) return [];
  return paths
    .filter((path): path is string => typeof path === 'string')
    .map((path) => path.replace(/^\$\.?/, '').replace(/^\//, '').split(/[/.]/)[0] ?? '')
    .filter((name) => name !== '');
}

/** What a refused command means for the user, read from the problem code; one plain sentence each. */
export type IncidentRefusal = 'changed' | 'moved' | 'self' | 'output' | 'forbidden' | 'not-found' | 'other';

export function refusalOf(error: HostError): IncidentRefusal {
  if (error.kind !== 'problem') return 'other';
  switch (error.code) {
    case 'VERSION_CONFLICT':
      return 'changed';
    case 'INVALID_STATE':
      return 'moved';
    case 'SELF_APPROVAL_FORBIDDEN':
      return 'self';
    case 'OUTPUT_INVALID':
      return 'output';
    case 'PERMISSION_DENIED':
      return 'forbidden';
    case 'INCIDENT_NOT_FOUND':
      return 'not-found';
    default:
      return 'other';
  }
}
