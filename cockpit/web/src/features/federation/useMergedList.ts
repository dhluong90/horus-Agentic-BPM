import { useQueries, useQueryClient } from '@tanstack/react-query';
import { useCallback, useMemo, useState } from 'react';
import type { Conditional } from '../../api/hostCall';
import { createProcessesClient, type ProcessesClient } from '../../api/processes/processesClient';
import type { Page } from '../../api/rules/schemas';
import { useFederation, type FederatedHost } from '../../app/federation/federation';
import { hasNextOf, itemsOf, mergeHosts, readAgain, readNext, type Compare, type HostRead, type ReadPage, type Row } from './merge';

/*
 * Server state of a merged list: one read per reachable host, merged in the browser (cockpit DESIGN section 8).
 * Keys start with host and tenant, under the same prefix the single-host queue uses, so a command taken on a row
 * reads that host's list again. A host that fails is named and left out; the others stay.
 */

export interface MergedSpec<T> {
  /** `tasks` or `incidents`: the end of the key prefix, and the screen it serves. */
  kind: 'tasks' | 'incidents';
  /** What distinguishes the list within its kind, for example the incident status. */
  variant: string;
  /** The permission /me must list for a host to take part; a host without it is not part of the list, and not "incomplete". */
  permission: string;
  readPage: (client: ProcessesClient, request: { cursor: string | undefined; signal: AbortSignal }, etag: string | undefined) => Promise<Conditional<Page<T>>>;
  compare: Compare<T>;
  refreshSeconds: number;
  pageLimit: number;
}

export interface HostName {
  id: string;
  label: string;
}

export type MergedState<T> =
  | { status: 'loading' }
  | { status: 'ready'; rows: Row<T>[]; hasMore: boolean; /** Hosts whose read failed: the list is incomplete without them. */ incomplete: HostName[]; /** Every host that took part and answered. */ answered: HostName[]; loadingMore: boolean; loadMore: () => void; refresh: () => void }
  | { status: 'none' };

/** The refresh period with plus or minus 10 % jitter, drawn again before each refresh. */
function jittered(seconds: number): () => number {
  return () => Math.round(seconds * 1000 * (0.9 + Math.random() * 0.2));
}

const NO_HOSTS: readonly FederatedHost[] = [];

const hasProcesses = (host: Extract<FederatedHost, { state: 'ready' }>) => host.meta.modules.some((module) => module.module === 'processes');

export function useMergedList<T>(spec: MergedSpec<T>): MergedState<T> {
  const federation = useFederation();
  const queryClient = useQueryClient();
  const hosts = federation?.hosts ?? NO_HOSTS;
  const timeoutMs = federation?.timeoutMs ?? 5_000;
  const [loadingMore, setLoadingMore] = useState(false);

  const sources = useMemo(
    () => hosts.flatMap((host) => (host.state === 'ready' && hasProcesses(host) && host.me.permissions.includes(spec.permission)
      ? [{ host, client: createProcessesClient(host.baseUrl, host.fetchImpl), key: [host.id, host.me.tenant.current, 'processes', spec.kind, 'merged', spec.variant, spec.pageLimit] as const }]
      : [])),
    [hosts, spec.permission, spec.kind, spec.variant, spec.pageLimit],
  );
  const reader = (client: ProcessesClient, signal: AbortSignal | undefined): ReadPage<T> => (cursor, etag) => {
    // One host's slow answer is cut at the registry's timeout; the host is then listed as unreachable.
    const limit = signal === undefined ? AbortSignal.timeout(timeoutMs) : AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]);
    return spec.readPage(client, { cursor, signal: limit }, etag);
  };
  const reads = useQueries({
    queries: sources.map((source) => ({
      queryKey: source.key,
      queryFn: ({ signal }: { signal: AbortSignal }) => readAgain<T>(queryClient.getQueryData<HostRead<T>>(source.key), reader(source.client, signal)),
      refetchInterval: jittered(spec.refreshSeconds),
      staleTime: 5_000,
      retry: false,
    })),
  });

  const loadMore = useCallback((hostId: string) => {
    const source = sources.find((entry) => entry.host.id === hostId);
    const held = source === undefined ? undefined : queryClient.getQueryData<HostRead<T>>(source.key);
    if (source === undefined || held === undefined) return;
    setLoadingMore(true);
    void readNext(held, reader(source.client, undefined))
      .then((next) => { queryClient.setQueryData(source.key, next); })
      .catch(() => { void queryClient.invalidateQueries({ queryKey: source.key }); })
      .finally(() => { setLoadingMore(false); });
    // `reader` closes over the spec only through values that sit in `sources`' dependencies
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources, queryClient, timeoutMs, spec.readPage]);

  const refresh = useCallback(() => {
    for (const source of sources) void queryClient.invalidateQueries({ queryKey: source.key });
  }, [sources, queryClient]);

  if (federation === undefined) return { status: 'none' };
  const probing = hosts.some((host) => host.state === 'probing');
  const unreadable = hosts.filter((host) => host.state === 'failed' || host.state === 'unsupported').map((host) => ({ id: host.id, label: host.label }));
  const answered = sources.flatMap((source, index) => (reads[index]?.isError === false && reads[index].data !== undefined ? [{ id: source.host.id, label: source.host.label }] : []));
  const failedReads = sources.flatMap((source, index) => (reads[index]?.isError === true ? [{ id: source.host.id, label: source.host.label }] : []));
  const waiting = sources.some((_, index) => reads[index]?.isPending === true);
  if ((probing || waiting) && answered.length === 0) return { status: 'loading' };
  if (sources.length === 0 && unreadable.length === 0) return { status: 'none' };

  const loaded = sources.flatMap((source, index) => {
    const data = reads[index]?.data;
    return data === undefined || reads[index]?.isError === true ? [] : [{ hostId: source.host.id, items: itemsOf(data), hasNext: hasNextOf(data) }];
  });
  const merged = mergeHosts(loaded, spec.compare);
  return {
    status: 'ready',
    rows: merged.rows,
    hasMore: merged.hasMore,
    incomplete: [...unreadable, ...failedReads],
    answered,
    loadingMore,
    loadMore: () => { if (merged.frontier !== undefined) loadMore(merged.frontier); },
    refresh,
  };
}
