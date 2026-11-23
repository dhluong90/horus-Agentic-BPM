import type { PendingDefinitionApproval } from '../../api/processes/schemas';
import type { PendingRuleApproval } from '../../api/rules/schemas';
import { suppliedName } from '../../app/people';
import type { Route } from '../../app/route';
import type { MessageKey } from '../../i18n/en';

/*
 * The approvals queue across both engines (#31): one item type for a rule version and a process definition version
 * waiting for a second person, merged oldest request first. What a user may do is the engine's answer for this
 * caller (allowedActions, deniedActions); the queue never works it out from names or roles.
 */

export type Engine = 'rules' | 'processes';

/** The reasons the engines give for not offering Approve; any other code reads as "not available to you". */
export const DENIAL_CODES = ['PERMISSION_REQUIRED', 'SELF_APPROVAL_FORBIDDEN', 'AUTHOR_UNKNOWN'] as const;
export type Denial = (typeof DENIAL_CODES)[number] | 'UNAVAILABLE';

/** What waits for a second person: a rule version, a process definition version, a migration plan or a calendar change. */
export type ApprovalType = 'rule' | 'definition' | 'migration' | 'calendar';

export interface ApprovalItem {
  engine: Engine;
  /** What kind of change it is; the review screen and the wording follow it. */
  type: ApprovalType;
  /** The id of the migration plan or calendar change, which the review link names; undefined for a version. */
  id: string | undefined;
  /** For a migration plan: the version its cases run on now. */
  sourceVersion: number | undefined;
  /** The date the requester asked a rule version to take effect, when one was set (RLE-210). */
  effectiveFrom: string | undefined;
  /** What the proposer wrote for a migration plan or a calendar change. */
  reason: string | undefined;
  /** The rule set name or the definition key: what the review link names. */
  key: string;
  /** What a person reads: the rule set name, or the process's name (its key when it has none). */
  title: string;
  version: number;
  /** canonicalHash or definitionHash: the content the reviewer is asked to approve. */
  hash: string;
  lockVersion: number;
  requestedBy: string;
  /** The requester's display name, when the host sends one with the item. */
  requesterName: string | undefined;
  requestedAt: string;
  /** Approve is offered to this caller now. */
  canApprove: boolean;
  /** Why Approve is not offered, when it is not. */
  approveDenied: Denial | undefined;
  /** The caller asked for this change. */
  mine: boolean;
  /** The engine offers withdraw to this caller (rules, PD-089). */
  canWithdraw: boolean;
}

/** One stable identity per item across both engines. */
export const itemKey = (item: Pick<ApprovalItem, 'engine' | 'key' | 'version'> & Partial<Pick<ApprovalItem, 'type' | 'id'>>) =>
  (item.type === 'migration' || item.type === 'calendar') && item.id !== undefined ? `${item.engine}/${item.type}/${item.id}` : `${item.engine}/${item.key}/${String(item.version)}`;

function denial(code: string | undefined, allowed: boolean): Denial | undefined {
  if (allowed) return undefined;
  return DENIAL_CODES.find((known) => known === code) ?? 'UNAVAILABLE';
}

export function fromRule(item: PendingRuleApproval, userId: string): ApprovalItem {
  const canApprove = item.allowedActions.includes('approve');
  return {
    engine: 'rules', type: 'rule', id: undefined, sourceVersion: undefined, reason: undefined, effectiveFrom: item.effectiveFrom ?? undefined, key: item.ruleSet, title: item.ruleSet, version: item.version, hash: item.canonicalHash, lockVersion: item.lockVersion,
    requestedBy: item.requestedBy, requesterName: suppliedName(item, 'requestedBy'), requestedAt: item.requestedAt, canApprove,
    approveDenied: denial(item.deniedActions['approve'], canApprove), mine: item.requestedBy === userId, canWithdraw: item.allowedActions.includes('withdraw'),
  };
}

export function fromProcess(item: PendingDefinitionApproval, userId: string): ApprovalItem {
  const canApprove = item.allowedActions.includes('approve');
  const type: ApprovalType = item.kind === 'MIGRATION_PLAN' ? 'migration' : item.kind === 'CALENDAR_CHANGE' ? 'calendar' : 'definition';
  return {
    engine: 'processes', type, id: type === 'definition' ? undefined : item.id ?? undefined, sourceVersion: item.sourceVersion ?? undefined, reason: item.reason ?? undefined, effectiveFrom: undefined,
    key: item.key, title: item.name ?? item.key, version: item.version, hash: item.contentHash ?? item.definitionHash ?? '', lockVersion: item.lockVersion,
    requestedBy: item.requestedBy, requesterName: suppliedName(item, 'requestedBy'), requestedAt: item.requestedAt, canApprove,
    approveDenied: denial(item.deniedActions['approve'], canApprove), mine: item.requestedBy === userId, canWithdraw: false,
  };
}

/** Oldest request first, as both engines order their lists; ties by engine, key and version, so the order is total. */
export function byRequest(a: ApprovalItem, b: ApprovalItem): number {
  const time = Date.parse(a.requestedAt) - Date.parse(b.requestedAt);
  if (time !== 0) return time;
  return itemKey(a).localeCompare(itemKey(b));
}

/** The loaded pages of one engine's list: its items in the engine's order, and whether the engine has more. */
export interface SourcePages {
  items: readonly ApprovalItem[];
  hasMore: boolean;
}

/**
 * Merges the engines' lists into one exact order. An engine that has more pages hides nothing it has not sent: the
 * merged list stops at the earliest last-loaded request among engines with more, so an item never appears before an
 * older one that is still on a later page (the frontier rule of the merged inbox, cockpit DESIGN section 8).
 */
export function mergeQueue(sources: readonly SourcePages[]): { items: ApprovalItem[]; more: boolean } {
  const frontiers = sources.filter((source) => source.hasMore).map((source) => source.items.at(-1)).filter((item): item is ApprovalItem => item !== undefined);
  const frontier = frontiers.sort(byRequest)[0];
  const all = sources.flatMap((source) => source.items).sort(byRequest);
  const items = frontier === undefined ? all : all.filter((item) => byRequest(item, frontier) <= 0);
  return { items, more: sources.some((source) => source.hasMore) };
}

/** The two views of the queue: requests this user may decide on or must know are stuck, and their own requests. */
export type QueueView = 'review' | 'mine';

/**
 * Whether an item belongs to a view. Your requests: those you asked for, or that the engine says are yours (you
 * made the version). To review: everything else you hold the approve permission for, including a version that
 * nobody can approve, so an approver sees it is stuck. Someone else's request you may not approve is not shown.
 */
export function inView(item: ApprovalItem, view: QueueView): boolean {
  const yours = item.mine || item.approveDenied === 'SELF_APPROVAL_FORBIDDEN';
  if (view === 'mine') return yours;
  return !yours && item.approveDenied !== 'PERMISSION_REQUIRED';
}

/** The one line beside a disabled Approve, from the engine's reason code. */
export function denialMessage(denied: Denial): MessageKey {
  return `approvals.denied.${denied}`;
}

/**
 * The existing review screen of the item, where the change is read and decided: the rule diff, the process diff, the
 * migration plan, or the calendar change.
 */
export function reviewRoute(item: Pick<ApprovalItem, 'engine' | 'key' | 'version'> & Partial<Pick<ApprovalItem, 'type' | 'id'>>): Route {
  if (item.type === 'migration' && item.id !== undefined) return { name: 'migration', migrationId: item.id };
  if (item.type === 'calendar' && item.id !== undefined) return { name: 'calendar-change', changeId: item.id };
  return item.engine === 'rules'
    ? { name: 'rule-set', ruleSet: item.key, version: item.version, lens: 'changes' }
    : { name: 'process', key: item.key, version: item.version, lens: 'changes' };
}

/** What a person reads as the item's title: "card-fee, version 2", or the move or calendar it asks for. */
export function itemTitle(item: Pick<ApprovalItem, 'type' | 'title' | 'version' | 'sourceVersion'>, t: (key: MessageKey, params?: Record<string, string>) => string): string {
  if (item.type === 'migration') return t('approvals.migrationTitle', { name: item.title, source: String(item.sourceVersion ?? ''), target: String(item.version) });
  if (item.type === 'calendar') return t('approvals.calendarTitle', { name: item.title });
  return t('rules.nameVersion', { name: item.title, version: String(item.version) });
}

/** The label of what the item is: the engine's kind, or a migration plan or a calendar change under Process. */
export function kindLabel(item: Pick<ApprovalItem, 'type'>): MessageKey {
  switch (item.type) {
    case 'rule': return 'approvals.kind.rules';
    case 'migration': return 'approvals.kind.migration';
    case 'calendar': return 'approvals.kind.calendar';
    case 'definition': return 'approvals.kind.processes';
  }
}
