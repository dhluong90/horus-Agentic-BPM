import type { TaskQuery } from '../../../api/processes/processesClient';
import type { SlaState, Task, TaskSummary } from '../../../api/processes/schemas';
import type { HostError } from '../../../api/hostError';
import { QUEUE_TABS, type QueueTab } from '../../../app/route';

/*
 * The task queue's rules that are not the engine's: which list a tab reads, how an assignee reads in words, which
 * command hands a task over, and what a refusal means for the user. The engine decides who may do what
 * (`allowedActions`) and which tasks are overdue (`slaBreached`, later `slaState`); nothing here computes either.
 */

/** The tabs of the queue: the user's own work, work they may claim, and every task of the tenant (supervisors). */
export const VIEWS = QUEUE_TABS;
export type QueueView = QueueTab;

/** The permission that opens All tasks and hands tasks to colleagues (PD-110). */
export const MANAGE = 'processes:task:manage';
/** The permission to see and work one's own and available tasks. */
export const WORK = 'processes:task:work';

/** The tabs this user sees: All tasks only with the manage permission; the others only with the work permission. */
export function viewsFor(can: (permission: string) => boolean): QueueView[] {
  return [...(can(WORK) ? (['mine', 'candidate'] as const) : []), ...(can(MANAGE) ? (['all'] as const) : [])];
}

/** The list query of each tab, one to one with `GET /tasks` parameters. */
export function queryOf(view: QueueView): Pick<TaskQuery, 'assignment' | 'status'> {
  switch (view) {
    case 'mine':
      return { assignment: ['mine'] };
    case 'candidate':
      return { assignment: ['candidate'], status: ['OPEN'] };
    case 'all':
      return { assignment: ['any'] };
  }
}

/** The assignment that covers every tab this user sees, for the one query that asks the engine which are overdue. */
export function overdueAssignment(views: readonly QueueView[]): TaskQuery['assignment'] {
  return views.includes('all') ? ['any'] : ['mine', 'candidate'];
}

export type Assignee =
  | { kind: 'user'; id: string }
  | { kind: 'role'; name: string }
  | { kind: 'group'; name: string };

/** `user:<id>`, `role:<role>` or `group:<group>`; anything else reads as a user id, never as raw text. */
export function parseAssignee(value: string): Assignee {
  const match = /^(user|role|group):(.+)$/.exec(value);
  if (match?.[2] === undefined) return { kind: 'user', id: value };
  const [, kind, rest] = match;
  if (kind === 'role') return { kind: 'role', name: rest };
  if (kind === 'group') return { kind: 'group', name: rest };
  return { kind: 'user', id: rest };
}

/**
 * A role or group name in words: `credit-officer` and `CREDIT_OFFICER` read "Credit officer". The engine and /me
 * carry no display name for a role, so the identifier is shown in sentence case, never translated or guessed.
 */
export function readableName(identifier: string): string {
  const words = identifier.replace(/^ROLE_/, '').split(/[-_.\s]+/).filter(Boolean).map((word) => word.toLowerCase());
  const text = words.join(' ');
  return text === '' ? identifier : text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * How the task stands against its deadline: the engine's `slaState` when it sends one, otherwise overdue when the
 * engine's `slaBreached=true` list holds the task, on time when it has a due time, and no deadline without one.
 */
export function slaOf(task: Pick<TaskSummary, 'id' | 'dueAt' | 'slaState'>, overdue: ReadonlySet<string> | undefined): SlaState {
  if (task.slaState !== undefined) return task.slaState;
  if (task.dueAt === null || task.dueAt === undefined) return 'NONE';
  return overdue?.has(task.id) === true ? 'BREACHED' : 'ON_TIME';
}

/** `PT2H30M` → hours and minutes for "Overdue by 2 h 30 min"; undefined for any other shape. */
export function overdueParts(duration: string | null | undefined): { days: number; hours: number; minutes: number } | undefined {
  if (duration === null || duration === undefined) return undefined;
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(duration);
  if (match === null || duration === 'P' || duration === 'PT') return undefined;
  return { days: Number(match[1] ?? 0), hours: Number(match[2] ?? 0), minutes: Number(match[3] ?? 0) };
}

/** The hand-over command for the task's state (PD-110): reassign an open task, delegate a claimed one. */
export function handOverCommand(task: Pick<Task, 'status' | 'allowedActions'>): 'reassign' | 'delegate' | undefined {
  const allowed = new Set(task.allowedActions);
  if (task.status === 'OPEN' && allowed.has('reassign')) return 'reassign';
  if (task.status === 'CLAIMED' && allowed.has('delegate')) return 'delegate';
  return undefined;
}

/** The engine's limits on a hand-over (TaskHandOver in the Process Engine contract). */
export const REASON_MAX = 500;
export const ASSIGNEE_MAX = 200;

export type HandOverProblem = 'assignee-missing' | 'reason-missing';

/** What still keeps a hand-over from being sent; the engine checks the rest. */
export function handOverProblems(assignee: string, reason: string): HandOverProblem[] {
  return [...(assignee.trim() === '' ? (['assignee-missing'] as const) : []), ...(reason.trim() === '' ? (['reason-missing'] as const) : [])];
}

/** What a refused command means for the user, read from the problem code; one plain sentence each. */
export type Refusal =
  | 'claimed-first'
  | 'assignee-not-allowed'
  | 'changed'
  | 'not-assigned'
  | 'four-eyes'
  | 'not-found'
  | 'forbidden'
  | 'other';

export function refusalOf(error: HostError): Refusal {
  if (error.kind !== 'problem') return 'other';
  switch (error.code) {
    case 'ASSIGNEE_NOT_ALLOWED':
      return 'assignee-not-allowed';
    case 'INVALID_STATE':
      return 'claimed-first';
    case 'VERSION_CONFLICT':
      return 'changed';
    case 'TASK_NOT_ASSIGNED':
      return 'not-assigned';
    case 'SELF_APPROVAL_FORBIDDEN':
      return 'four-eyes';
    case 'TASK_NOT_FOUND':
      return 'not-found';
    case 'PERMISSION_DENIED':
      return 'forbidden';
    default:
      return 'other';
  }
}

/** Why the engine refused a hand-over target (`reason` of 409 ASSIGNEE_NOT_ALLOWED, PRE-206); new reasons may come. */
export const ASSIGNEE_REASONS = ['ALREADY_ASSIGNED', 'FOUR_EYES_PARTICIPANT', 'RESERVED_IDENTITY', 'MISSING_ROLE', 'MISSING_GROUP'] as const;
export type AssigneeReason = (typeof ASSIGNEE_REASONS)[number];

export interface AssigneeRefusal {
  /** Undefined when the engine sent none, or one this cockpit does not know: the sentence stays general. */
  reason: AssigneeReason | undefined;
  /** The role or group the target lacks, from `required` (`role:<id>` or `group:<id>`). */
  required: { kind: 'role' | 'group'; id: string } | undefined;
}

/** Reads `reason` and `required` from the problem; anything unreadable is left out, never guessed. */
export function assigneeRefusalOf(error: HostError): AssigneeRefusal {
  const members = error.kind === 'problem' ? error.members : undefined;
  const reason = ASSIGNEE_REASONS.find((known) => known === members?.['reason']);
  const required = typeof members?.['required'] === 'string' ? /^(role|group):(.+)$/.exec(members['required']) : null;
  return { reason, required: required?.[2] !== undefined ? { kind: required[1] === 'role' ? 'role' : 'group', id: required[2] } : undefined };
}
