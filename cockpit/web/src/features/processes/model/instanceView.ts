import type { ActivityEntry, CompensationScope, InstanceActivities, InstanceTask, NodeActivity, ProcessInstance } from '../../../api/processes/schemas';
import type { MessageKey } from '../../../i18n/en';

/*
 * How one instance reads on its diagram (CKP-017). Every fact comes from the engine: tokens and tasks from
 * getInstance, per-step activity from getInstanceActivities. This module only decides which word and icon show a fact;
 * it never computes a duration, a count or a status.
 */

/** Token statuses that mean "the case is here now". */
export const LIVE_TOKEN_STATUSES = ['ACTIVE', 'WAITING_USER', 'WAITING_ASYNC', 'WAITING_TIMER', 'WAITING_EVENT'] as const;
type LiveStatus = (typeof LIVE_TOKEN_STATUSES)[number];

function isLive(status: string): status is LiveStatus {
  return (LIVE_TOKEN_STATUSES as readonly string[]).includes(status);
}

/** What the diagram marks on one step, in order of what the operator must see first. */
export type NodeMark =
  | { kind: 'incident'; code: string }
  | { kind: 'token'; status: LiveStatus; count: number }
  | { kind: 'undone' }
  | { kind: 'done' };

/** The marks of every step: an open incident first, then where tokens wait, then undone steps, then completed ones. */
export function nodeMarks(instance: ProcessInstance, activities: InstanceActivities | undefined): Map<string, NodeMark> {
  const marks = new Map<string, NodeMark>();
  for (const node of activities?.nodes ?? []) {
    if (node.incident !== null) marks.set(node.nodeId, { kind: 'incident', code: node.incident.code });
  }
  for (const token of instance.tokens ?? []) {
    if (token.kind === 'SCOPE' || !isLive(token.status) || marks.get(token.nodeId)?.kind === 'incident') continue;
    const current = marks.get(token.nodeId);
    marks.set(token.nodeId, current?.kind === 'token' ? { ...current, count: current.count + 1 } : { kind: 'token', status: token.status, count: 1 });
  }
  for (const entry of activities?.entries ?? []) {
    if (entry.type === 'COMPENSATION_STEP_COMPLETED' && entry.compensates !== undefined && !marks.has(entry.compensates)) {
      marks.set(entry.compensates, { kind: 'undone' });
    }
  }
  for (const node of activities?.nodes ?? []) {
    if (!marks.has(node.nodeId) && node.state === 'COMPLETED') marks.set(node.nodeId, { kind: 'done' });
  }
  return marks;
}

/** The step to open first: where the case is stuck (an incident), else where it waits, else nothing. */
export function firstStep(marks: ReadonlyMap<string, NodeMark>, order: readonly string[]): string | undefined {
  const byKind = (kind: NodeMark['kind']) => order.find((id) => marks.get(id)?.kind === kind);
  return byKind('incident') ?? byKind('token');
}

export const TOKEN_KEY: Record<LiveStatus, MessageKey> = {
  ACTIVE: 'instance.token.ACTIVE',
  WAITING_USER: 'instance.token.WAITING_USER',
  WAITING_ASYNC: 'instance.token.WAITING_ASYNC',
  WAITING_TIMER: 'instance.token.WAITING_TIMER',
  WAITING_EVENT: 'instance.token.WAITING_EVENT',
};

export const TOKEN_ICON: Record<LiveStatus, string> = {
  ACTIVE: '▶',
  WAITING_USER: '◷',
  WAITING_ASYNC: '◷',
  WAITING_TIMER: '◷',
  WAITING_EVENT: '◷',
};

const STATUS_KEYS = ['RUNNING', 'COMPLETED', 'CANCELLED', 'COMPENSATING', 'FAILED'] as const;
type KnownStatus = (typeof STATUS_KEYS)[number];

/** An instance status in words, its icon and its tone; a status this cockpit does not know reads as "Other". */
export function instanceStatusLook(status: string): { key: MessageKey; glyph: string; tone: 'accent' | 'ok' | 'muted' | 'bad' | 'warn' | 'neutral' } {
  const known = (STATUS_KEYS as readonly string[]).includes(status) ? (status as KnownStatus) : undefined;
  switch (known) {
    case 'RUNNING': return { key: 'instanceStatus.RUNNING', glyph: '▶', tone: 'accent' };
    case 'COMPLETED': return { key: 'instanceStatus.COMPLETED', glyph: '✓', tone: 'ok' };
    case 'CANCELLED': return { key: 'instanceStatus.CANCELLED', glyph: '■', tone: 'muted' };
    case 'COMPENSATING': return { key: 'instanceStatus.COMPENSATING', glyph: '↺', tone: 'warn' };
    case 'FAILED': return { key: 'instanceStatus.FAILED', glyph: '✕', tone: 'bad' };
    default: return { key: 'instanceStatus.other', glyph: '•', tone: 'neutral' };
  }
}

const ENTRY_TYPES = [
  'INSTANCE_STARTED', 'NODE_ENTERED', 'NODE_COMPLETED', 'TASK_CLAIMED', 'TASK_UNCLAIMED', 'TASK_COMPLETED',
  'ASYNC_STEP_SCHEDULED', 'ASYNC_ATTEMPT_FAILED', 'ASYNC_RESULT_ACCEPTED', 'ASYNC_RESULT_REFUSED', 'STEP_ROUTED_TO_REVIEW',
  'HUMAN_OVERRIDE', 'DECISION_EVALUATED', 'TIMER_SCHEDULED', 'TIMER_FIRED', 'TOKEN_JOINED', 'TOKEN_CANCELLED',
  'INCIDENT_RAISED', 'INSTANCE_ENDED',
] as const;
type EntryType = (typeof ENTRY_TYPES)[number];

/** A history entry in words; an entry type this cockpit does not know reads "Recorded by the engine", never its code. */
export function entryKey(type: string): MessageKey {
  return (ENTRY_TYPES as readonly string[]).includes(type) ? (`instance.entry.${type as EntryType}` as MessageKey) : 'instance.entry.other';
}

/** Whether an entry is a problem, shown with the error icon and word. */
export function isProblemEntry(entry: ActivityEntry): boolean {
  return entry.type === 'INCIDENT_RAISED' || entry.type === 'ASYNC_ATTEMPT_FAILED' || entry.type === 'ASYNC_RESULT_REFUSED';
}

const UNDO_ENTRY_TYPES = ['COMPENSATION_STARTED', 'COMPENSATION_STEP_SCHEDULED', 'COMPENSATION_STEP_COMPLETED', 'COMPENSATION_STEP_FAILED', 'COMPENSATION_STEP_MANUAL', 'COMPENSATED'] as const;
export type UndoEntryType = (typeof UNDO_ENTRY_TYPES)[number];

/** An entry of an undo, as the engine marks it with `kind`; a type this cockpit does not know falls back to its plain entry words. */
export function undoEntryType(entry: ActivityEntry): UndoEntryType | undefined {
  return entry.kind === 'COMPENSATION' ? UNDO_ENTRY_TYPES.find((type) => type === entry.type) : undefined;
}

/** An id or handler name in words: `closeAccount` and `loans.close-account` both read "Close account". Display only. */
export function wordsOf(id: string): string {
  const last = id.slice(id.lastIndexOf('.') + 1);
  const words = last.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The scope a state line speaks of: the engine's state, never worked out from the entries. */
export function scopeState(scope: CompensationScope): 'UNDONE' | 'NOT_FULLY_UNDONE' | 'IN_PROGRESS' {
  return scope.state === 'UNDONE' || scope.state === 'NOT_FULLY_UNDONE' ? scope.state : 'IN_PROGRESS';
}

const INCIDENT_CODES = ['AGENT_TIMEOUT', 'ADAPTER_TIMEOUT', 'AGENT_SCHEMA_INVALID', 'MAX_ATTEMPTS_REACHED', 'UNHANDLED_BUSINESS_ERROR', 'GUARDRAIL_EVALUATION_ERROR', 'COMPENSATION_FAILED'] as const;
type IncidentCode = (typeof INCIDENT_CODES)[number];

/** An incident's code in words; the code itself stays one click away, as for every error (styleguide 9). */
export function incidentKey(code: string): MessageKey {
  return (INCIDENT_CODES as readonly string[]).includes(code) ? (`instance.incident.${code as IncidentCode}` as MessageKey) : 'instance.incident.other';
}

/**
 * A duration the engine measured, in whole units: "3 min 10 s", "2 h 5 min", "4 d 3 h". Integer division of the
 * engine's milliseconds only; nothing is measured here.
 */
export function durationParts(ms: number): { unit: 'd' | 'h' | 'min' | 's'; value: number }[] {
  const seconds = Math.floor(ms / 1000);
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  if (days > 0) return hours > 0 ? [{ unit: 'd', value: days }, { unit: 'h', value: hours }] : [{ unit: 'd', value: days }];
  if (hours > 0) return minutes > 0 ? [{ unit: 'h', value: hours }, { unit: 'min', value: minutes }] : [{ unit: 'h', value: hours }];
  if (minutes > 0) return rest > 0 ? [{ unit: 'min', value: minutes }, { unit: 's', value: rest }] : [{ unit: 'min', value: minutes }];
  return [{ unit: 's', value: rest }];
}

/**
 * Who a task waits for, in words: `role:COMPLIANCE_REVIEWER` reads "Compliance reviewer", `user:lan` reads "lan".
 * The API carries no role titles yet, so the words are the role's own.
 */
export function assigneeWords(assignee: string): string {
  const match = /^(role|group|user):(.+)$/.exec(assignee);
  if (!match?.[2]) return assignee;
  if (match[1] === 'user') return match[2];
  const words = match[2].replace(/[_-]+/g, ' ').trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The open task waiting on a step, if any. */
export function openTaskAt(instance: ProcessInstance, nodeId: string): InstanceTask | undefined {
  return (instance.tasks ?? []).find((task) => task.nodeId === nodeId && (task.status === 'OPEN' || task.status === 'CLAIMED'));
}

export function activityOf(activities: InstanceActivities | undefined, nodeId: string): NodeActivity | undefined {
  return activities?.nodes.find((node) => node.nodeId === nodeId);
}

/** An engine replica (`engine:loans-7f9c`) or no actor reads as the engine. */
export function actorReplica(actor: string | null): { engine: true; replica: string | undefined } | { engine: false; login: string } {
  if (actor === null) return { engine: true, replica: undefined };
  const replica = /^engine:(.+)$/.exec(actor);
  return replica ? { engine: true, replica: replica[1] } : { engine: false, login: actor };
}
