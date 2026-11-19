import { useQueries, useQuery } from '@tanstack/react-query';
import { allVersions } from '../../api/rules/catalogue';
import type { RulesClient } from '../../api/rules/rulesClient';
import type { RuleSetSummary, VersionSummary } from '../../api/rules/schemas';
import { useRulesSession } from '../rules/api/rulesContext';

/*
 * Read-only views over the engine's list endpoints for Home and Approvals. Keys start with host and tenant
 * and sit under [host, tenant, 'rules'], so a confirmed change anywhere in the rule screens refreshes them.
 */

/** Async state as one variant at a time. */
export type Loadable<T> = { status: 'loading' } | { status: 'error'; error: unknown } | { status: 'ready'; data: T };

/** A guard against a host that keeps answering with a next page; never reached with a sound engine. */
const MAX_PAGES = 50;

async function everyRuleSet(client: RulesClient, limit: number, signal: AbortSignal): Promise<RuleSetSummary[]> {
  const items: RuleSetSummary[] = [];
  let cursor: string | undefined;
  for (let page = 0; page < MAX_PAGES; page += 1) {
    const answer = await client.listRuleSets({ cursor, limit, signal });
    items.push(...answer.data);
    if (!answer.hasNext || answer.nextCursor === null) break;
    cursor = answer.nextCursor;
  }
  return items;
}

/** Every rule set of the tenant, read with the host's largest page size. */
export function useEveryRuleSet(pageLimit: number, enabled = true): Loadable<RuleSetSummary[]> {
  const { client, hostId, tenant } = useRulesSession();
  const query = useQuery({
    queryKey: [hostId, tenant, 'rules', 'every-rule-set', pageLimit],
    queryFn: ({ signal }) => everyRuleSet(client, pageLimit, signal),
    staleTime: 10_000,
    enabled,
  });
  if (query.isError) return { status: 'error', error: query.error };
  if (query.data === undefined) return { status: 'loading' };
  return { status: 'ready', data: query.data };
}

/** Newest change first. Ties keep the engine's order (by name). */
export function recentFirst(items: readonly RuleSetSummary[]): RuleSetSummary[] {
  return [...items].sort((a, b) => Date.parse(b.lastChangedAt) - Date.parse(a.lastChangedAt));
}

/** One request waiting for a second person, as this user sees it. */
export interface PendingRequest {
  version: VersionSummary;
  /** The user asked for this publication, so four eyes forbids them to approve it. */
  mine: boolean;
  /** The engine lists approve for this user on this version. */
  canApprove: boolean;
  /** The engine lists withdraw: this user asked for the publication and may take it back (PD-089). */
  canWithdraw: boolean;
}

export function toPendingRequest(version: VersionSummary, userId: string): PendingRequest {
  const mine = version.requestedBy === userId;
  return {
    version,
    mine,
    canApprove: !mine && version.allowedActions.includes('approve'),
    canWithdraw: mine && version.allowedActions.includes('withdraw'),
  };
}

/**
 * The requests this user should see: those they may approve and their own. A request by someone else that
 * this user may not approve is not theirs to act on, so it is left out.
 */
export function relevantRequests(versions: readonly VersionSummary[], userId: string): PendingRequest[] {
  return versions
    .filter((version) => version.status === 'PENDING_APPROVAL')
    .map((version) => toPendingRequest(version, userId))
    .filter((request) => request.canApprove || request.mine)
    .sort((a, b) => Date.parse(b.version.requestedAt ?? b.version.createdAt) - Date.parse(a.version.requestedAt ?? a.version.createdAt));
}

/** Latest statuses whose versions Home and Approvals need: requests to review and the author's own work. */
const OPEN_STATUSES: ReadonlySet<string> = new Set(['PENDING_APPROVAL', 'DRAFT']);

/** Every version of the rule sets whose latest version is pending or a draft, read with the rule screens' key. */
export function useOpenVersions(ruleSets: Loadable<RuleSetSummary[]>): Loadable<VersionSummary[]> {
  const { client, hostId, tenant } = useRulesSession();
  const names = ruleSets.status === 'ready' ? ruleSets.data.filter((item) => OPEN_STATUSES.has(item.latestStatus)).map((item) => item.ruleSet) : [];
  const versionQueries = useQueries({
    queries: names.map((name) => ({
      // The same key as the rule screens' version list, so both read one cache entry.
      queryKey: [hostId, tenant, 'rules', name, 'versions'],
      queryFn: ({ signal }: { signal: AbortSignal }) => allVersions(client, name, signal),
      staleTime: 10_000,
    })),
  });
  if (ruleSets.status !== 'ready') return ruleSets;
  const failed = versionQueries.find((query) => query.isError);
  if (failed) return { status: 'error', error: failed.error };
  if (versionQueries.some((query) => query.data === undefined)) return { status: 'loading' };
  return { status: 'ready', data: versionQueries.flatMap((query) => query.data ?? []) };
}

/** Pending requests this user may review or asked for themselves (the Approvals queue). */
export function usePendingRequests(ruleSets: Loadable<RuleSetSummary[]>): Loadable<PendingRequest[]> {
  const { userId } = useRulesSession();
  const versions = useOpenVersions(ruleSets);
  if (versions.status !== 'ready') return versions;
  return { status: 'ready', data: relevantRequests(versions.data, userId) };
}

/** One item of an author's own work: a draft they created or a request they made. */
export interface OwnWork {
  version: VersionSummary;
  kind: 'draft' | 'request';
  /** The engine lists withdraw on this request. */
  canWithdraw: boolean;
}

/** The author's drafts and requests, newest change first. */
export function ownWork(versions: readonly VersionSummary[], userId: string): OwnWork[] {
  const changed = (version: VersionSummary) => Date.parse(version.requestedAt ?? version.createdAt);
  return versions
    .flatMap((version): OwnWork[] => {
      if (version.status === 'DRAFT' && version.createdBy === userId) return [{ version, kind: 'draft', canWithdraw: false }];
      if (version.status === 'PENDING_APPROVAL' && version.requestedBy === userId) return [{ version, kind: 'request', canWithdraw: version.allowedActions.includes('withdraw') }];
      return [];
    })
    .sort((a, b) => changed(b.version) - changed(a.version));
}

export function mapLoadable<T, U>(value: Loadable<T>, map: (data: T) => U): Loadable<U> {
  return value.status === 'ready' ? { status: 'ready', data: map(value.data) } : value;
}
