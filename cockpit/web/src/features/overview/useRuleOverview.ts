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
export function useEveryRuleSet(pageLimit: number): Loadable<RuleSetSummary[]> {
  const { client, hostId, tenant } = useRulesSession();
  const query = useQuery({
    queryKey: [hostId, tenant, 'rules', 'every-rule-set', pageLimit],
    queryFn: ({ signal }) => everyRuleSet(client, pageLimit, signal),
    staleTime: 10_000,
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

/** Pending requests across the tenant: the versions of every rule set whose latest version waits for approval. */
export function usePendingRequests(ruleSets: Loadable<RuleSetSummary[]>): Loadable<PendingRequest[]> {
  const { client, hostId, tenant, userId } = useRulesSession();
  const names = ruleSets.status === 'ready' ? ruleSets.data.filter((item) => item.latestStatus === 'PENDING_APPROVAL').map((item) => item.ruleSet) : [];
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
  return { status: 'ready', data: relevantRequests(versionQueries.flatMap((query) => query.data ?? []), userId) };
}
