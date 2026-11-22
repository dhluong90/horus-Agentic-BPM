import type { Conditional } from '../../api/hostCall';
import type { Page } from '../../api/rules/schemas';
import type { Incident, TaskSummary } from '../../api/processes/schemas';

/*
 * The merge of one list across hosts (cockpit DESIGN section 8, CKP-019, CKP-020). Every host returns its list in
 * the same order, so the merge is a k-way merge. A host that still has pages can hold something that belongs before
 * what another host has shown, so the list shows items only up to the smallest last item among the hosts that have
 * more (the frontier); "Load more" reads the next page of the host at the frontier only. That gives the exact global
 * order, not an approximation. Nothing here decides what a task or an incident is; it orders what the hosts said.
 */

/** One host's pages as read so far, each with the ETag the host gave it. */
export interface PageRead<T> {
  cursor: string | undefined;
  etag: string | undefined;
  page: Page<T>;
}

export interface HostRead<T> {
  pages: readonly PageRead<T>[];
}

/** One row of the merged list: the item and the host that owns it. */
export interface Row<T> {
  hostId: string;
  item: T;
}

export type Compare<T> = (a: Row<T>, b: Row<T>) => number;

/** The due time as a number; no due time sorts last. */
function dueKey(task: Pick<TaskSummary, 'dueAt'>): number {
  const time = task.dueAt === null || task.dueAt === undefined ? Number.NaN : Date.parse(task.dueAt);
  return Number.isNaN(time) ? Number.POSITIVE_INFINITY : time;
}

const text = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/** Tasks: due time ascending, tasks without one last, then host, then id. */
export const byDueTime: Compare<TaskSummary> = (a, b) => {
  const left = dueKey(a.item);
  const right = dueKey(b.item);
  if (left !== right) return left < right ? -1 : 1;
  return text(a.hostId, b.hostId) || text(a.item.id, b.item.id);
};

/** Incidents: newest first, then host, then id. */
export const byNewest: Compare<Incident> = (a, b) => {
  const left = Date.parse(a.item.createdAt);
  const right = Date.parse(b.item.createdAt);
  if (left !== right) return left > right ? -1 : 1;
  return text(a.hostId, b.hostId) || text(a.item.id, b.item.id);
};

export interface HostItems<T> {
  hostId: string;
  items: readonly T[];
  hasNext: boolean;
}

export interface Merged<T> {
  rows: Row<T>[];
  /** A host has pages beyond what is shown. */
  hasMore: boolean;
  /** The host to read next for "Load more": the one at the frontier. */
  frontier: string | undefined;
  /** Items each host holds beyond the frontier, loaded but not shown yet. */
  hidden: number;
}

export function mergeHosts<T>(hosts: readonly HostItems<T>[], compare: Compare<T>): Merged<T> {
  const all: Row<T>[] = hosts.flatMap((host) => host.items.map((item) => ({ hostId: host.hostId, item })));
  all.sort(compare);
  // The frontier is the smallest last item among hosts with more pages; a host with more pages and none loaded is at the start.
  let frontier: { hostId: string; last: Row<T> | undefined } | undefined;
  for (const host of hosts) {
    if (!host.hasNext) continue;
    const last = host.items.length === 0 ? undefined : { hostId: host.hostId, item: host.items[host.items.length - 1] as T };
    if (frontier === undefined) frontier = { hostId: host.hostId, last };
    else if (frontier.last !== undefined && (last === undefined || compare(last, frontier.last) < 0)) frontier = { hostId: host.hostId, last };
  }
  if (frontier === undefined) return { rows: all, hasMore: false, frontier: undefined, hidden: 0 };
  const edge = frontier.last;
  const rows = edge === undefined ? [] : all.filter((row) => compare(row, edge) <= 0);
  return { rows, hasMore: true, frontier: frontier.hostId, hidden: all.length - rows.length };
}

export const itemsOf = <T,>(read: HostRead<T>): T[] => read.pages.flatMap((entry) => entry.page.data);

export const hasNextOf = <T,>(read: HostRead<T>): boolean => read.pages[read.pages.length - 1]?.page.hasNext === true;

/** Reads one page: with the cursor, and with the ETag held for that page, if any. */
export type ReadPage<T> = (cursor: string | undefined, etag: string | undefined) => Promise<Conditional<Page<T>>>;

/**
 * Reads the first page of a host, or reads again what is held: each page is asked "only if changed" with its own
 * ETag, and a 304 keeps the page as it was. A host that sends no ETag is read in full each time (plain refresh).
 */
export async function readAgain<T>(held: HostRead<T> | undefined, readPage: ReadPage<T>): Promise<HostRead<T>> {
  if (held === undefined || held.pages.length === 0) {
    const first = await readPage(undefined, undefined);
    if (!first.changed) throw new Error('A host answered 304 to a read that was not conditional');
    return { pages: [{ cursor: undefined, etag: first.etag, page: first.data }] };
  }
  const pages: PageRead<T>[] = [];
  let cursor: string | undefined;
  for (const old of held.pages) {
    const answer = await readPage(cursor, old.etag);
    const entry: PageRead<T> = answer.changed ? { cursor, etag: answer.etag, page: answer.data } : old;
    pages.push(entry);
    if (!entry.page.hasNext || entry.page.nextCursor === null) break;
    cursor = entry.page.nextCursor;
  }
  return { pages };
}

/** Appends the next page of a host after its last one. */
export async function readNext<T>(held: HostRead<T>, readPage: ReadPage<T>): Promise<HostRead<T>> {
  const last = held.pages[held.pages.length - 1];
  if (last === undefined || !last.page.hasNext || last.page.nextCursor === null) return held;
  const answer = await readPage(last.page.nextCursor, undefined);
  if (!answer.changed) return held;
  return { pages: [...held.pages, { cursor: last.page.nextCursor, etag: answer.etag, page: answer.data }] };
}
