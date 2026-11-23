import type { PendingDefinitionApproval } from '../../api/processes/schemas';
import { nameOf } from '../../app/names';
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

export interface ApprovalItem {
  engine: Engine;
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
export const itemKey = (item: Pick<ApprovalItem, 'engine' | 'key' | 'version'>) => `${item.engine}/${item.key}/${String(item.version)}`;

function denial(code: string | undefined, allowed: boolean): Denial | undefined {
  if (allowed) return undefined;
  return DENIAL_CODES.find((known) => known === code) ?? 'UNAVAILABLE';
}

export function fromRule(item: PendingRuleApproval, userId: string): ApprovalItem {
  const canApprove = item.allowedActions.includes('approve');
  return {
    engine: 'rules', key: item.ruleSet, title: nameOf('rule set', item.ruleSet, item), version: item.version, hash: item.canonicalHash, lockVersion: item.lockVersion,
    requestedBy: item.requestedBy, requesterName: suppliedName(item, 'requestedBy'), requestedAt: item.requestedAt, canApprove,
    approveDenied: denial(item.deniedActions['approve'], canApprove), mine: item.requestedBy === userId, canWithdraw: item.allowedActions.includes('withdraw'),
  };
}

export function fromProcess(item: PendingDefinitionApproval, userId: string): ApprovalItem {
  const canApprove = item.allowedActions.includes('approve');
  return {
    engine: 'processes', key: item.key, title: item.name ?? item.key, version: item.version, hash: item.definitionHash, lockVersion: item.lockVersion,
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

/** The existing review screen of the item, where the change is read and decided: the rule diff or the process diff. */
export function reviewRoute(item: Pick<ApprovalItem, 'engine' | 'key' | 'version'>): Route {
  return item.engine === 'rules'
    ? { name: 'rule-set', ruleSet: item.key, version: item.version, lens: 'changes' }
    : { name: 'process', key: item.key, version: item.version, lens: 'changes' };
}
