import { useInfiniteQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { useCallback } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { toHostError } from '../../api/hostError';
import type { Page } from '../../api/rules/schemas';
import { useProcessesSession } from '../processes/api/processesContext';
import { useRulesSession } from '../rules/api/rulesContext';
import { byRequest, fromProcess, fromRule, mergeQueue, type ApprovalItem, type Engine, type SourcePages } from './queue';

/** The permission each engine's GET /approvals needs; the answer then says what the caller may do. */
const READ: Readonly<Record<Engine, string>> = { rules: 'rules:read', processes: 'processes:read' };

/** The engines whose queue this user may read on this host: the module is running and /me lists its read permission. */
export function queueEngines(meta: BpmMetaV1, me: BpmMeV1): Engine[] {
  const modules = new Set(meta.modules.map((module) => module.module));
  return (['rules', 'processes'] as const).filter((engine) => modules.has(engine) && me.permissions.includes(READ[engine]));
}

export type QueueState =
  | { status: 'loading' }
  | { status: 'forbidden' }
  | { status: 'error'; error: unknown }
  | {
      status: 'ready';
      items: ApprovalItem[];
      /** An engine has more pages than are shown. */
      more: boolean;
      /** Engines whose list failed while another answered: the queue says it is incomplete. */
      failed: Engine[];
    };

export interface ApprovalQueue {
  state: QueueState;
  loadMore: () => void;
  loadingMore: boolean;
  retry: () => void;
}

type Pages = InfiniteData<Page<ApprovalItem>, string | undefined>;

/** A 403 means the user may not read this list; trying again cannot help. */
const forbidden = (error: unknown) => {
  const host = toHostError(error);
  return (host.kind === 'problem' || host.kind === 'unexpected-response') && host.status === 403;
};

/**
 * The pending approvals of both engines on this host, as one list oldest first (#31). Each engine is read with its
 * signed cursor, `pageLimit` items a page; "Show more" reads the next page only of the engine at the frontier, so
 * the merged order is exact. Keys sit under each engine's [host, tenant, engine] prefix, so a decision taken on a
 * review screen reads the queue again.
 */
export function useApprovalQueue(engines: readonly Engine[], pageLimit: number): ApprovalQueue {
  const rules = useRulesSession();
  const processes = useProcessesSession();
  const queryClient = useQueryClient();
  const rulesQuery = useInfiniteQuery<Page<ApprovalItem>, Error, Pages, readonly unknown[], string | undefined>({
    queryKey: [rules.hostId, rules.tenant, 'rules', 'approvals', pageLimit],
    queryFn: async ({ pageParam, signal }) => {
      const page = await rules.client.listPendingApprovals({ cursor: pageParam, limit: pageLimit, signal });
      return { ...page, data: page.data.map((item) => fromRule(item, rules.userId)) };
    },
    initialPageParam: undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor !== null ? last.nextCursor : undefined),
    enabled: engines.includes('rules'),
    staleTime: 10_000,
    retry: false,
  });
  const processesQuery = useInfiniteQuery<Page<ApprovalItem>, Error, Pages, readonly unknown[], string | undefined>({
    queryKey: [processes.hostId, processes.tenant, 'processes', 'approvals', pageLimit],
    queryFn: async ({ pageParam, signal }) => {
      const page = await processes.client.listPendingApprovals({ cursor: pageParam, limit: pageLimit, signal });
      return { ...page, data: page.data.map((item) => fromProcess(item, processes.userId)) };
    },
    initialPageParam: undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor !== null ? last.nextCursor : undefined),
    enabled: engines.includes('processes'),
    staleTime: 10_000,
    retry: false,
  });

  const queries = { rules: rulesQuery, processes: processesQuery } as const;
  const active = engines.map((engine) => ({ engine, query: queries[engine] }));
  const sources = active.flatMap(({ engine, query }) => (query.data === undefined ? [] : [{
    engine,
    pages: { items: query.data.pages.flatMap((page) => page.data), hasMore: query.hasNextPage } satisfies SourcePages,
    fetchNext: query.fetchNextPage,
  }]));

  const retry = useCallback(() => {
    for (const engine of engines) {
      const session = engine === 'rules' ? rules : processes;
      void queryClient.invalidateQueries({ queryKey: [session.hostId, session.tenant, engine, 'approvals'] });
    }
  }, [engines, queryClient, rules, processes]);

  const loadMore = () => {
    // Only the engine whose last loaded request is the earliest: its next page may hold items older than the rest.
    const frontier = sources.filter((source) => source.pages.hasMore).sort((a, b) => {
      const left = a.pages.items.at(-1);
      const right = b.pages.items.at(-1);
      return left === undefined || right === undefined ? 0 : byRequest(left, right);
    })[0];
    if (frontier !== undefined) void frontier.fetchNext();
  };
  const loadingMore = active.some(({ query }) => query.isFetchingNextPage);

  return { state: queueState(active.map(({ engine, query }) => ({ engine, error: query.isError ? query.error : undefined, loaded: query.data !== undefined })), sources.map((source) => source.pages)), loadMore, loadingMore, retry };
}

function queueState(reads: readonly { engine: Engine; error: unknown; loaded: boolean }[], pages: readonly SourcePages[]): QueueState {
  if (reads.length === 0) return { status: 'forbidden' };
  const failed = reads.filter((read) => read.error !== undefined);
  if (failed.length === reads.length) {
    if (failed.every((read) => forbidden(read.error))) return { status: 'forbidden' };
    return { status: 'error', error: failed.find((read) => !forbidden(read.error))?.error };
  }
  if (reads.some((read) => read.error === undefined && !read.loaded)) return { status: 'loading' };
  const merged = mergeQueue(pages);
  // An engine that refused with 403 is not "incomplete": the user may not read it, and the rest is the whole queue.
  return { status: 'ready', items: merged.items, more: merged.more, failed: failed.filter((read) => !forbidden(read.error)).map((read) => read.engine) };
}

/** How many requests this user may approve now, and whether an engine has more than were read (the menu count). */
export function reviewCount(state: QueueState): { count: number; more: boolean } {
  if (state.status !== 'ready') return { count: 0, more: false };
  return { count: state.items.filter((item) => item.canApprove).length, more: state.more };
}
