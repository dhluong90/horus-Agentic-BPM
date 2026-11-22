import { describe, expect, it } from 'vitest';
import type { Conditional } from '../../api/hostCall';
import type { Page } from '../../api/rules/schemas';
import type { TaskSummary } from '../../api/processes/schemas';
import { byDueTime, byNewest, mergeHosts, readAgain, readNext, type ReadPage } from './merge';

const day = (n: number) => new Date(Date.UTC(2027, 0, n)).toISOString();
const task = (id: string, dueDay: number | null): TaskSummary => ({
  id, instanceId: 'i', definitionKey: 'k', definitionVersion: 1, businessKey: null, nodeId: 'n', name: id, assignee: 'user:a', status: 'OPEN',
  lockVersion: 0, claimedBy: null, createdAt: day(1), dueAt: dueDay === null ? null : day(dueDay), overdueBy: null,
});
const tasks = (prefix: string, from: number, to: number) => Array.from({ length: to - from + 1 }, (_, index) => task(`${prefix}${String(from + index)}`, from + index));
const page = <T,>(data: T[], next: string | null): Page<T> => ({ data, nextCursor: next, hasNext: next !== null });

describe('CKP-019 merged inbox order', () => {
  it('CKP_019_merged_order_shows_items_up_to_the_frontier_host', () => {
    // loans: 120 tasks due day 1 to 120, first page of 50; kyc: 12 tasks due day 5 to 16.
    const merged = mergeHosts([
      { hostId: 'loans', items: tasks('L', 1, 50), hasNext: true },
      { hostId: 'kyc', items: tasks('K', 5, 16), hasNext: false },
    ], byDueTime);
    expect(merged.rows).toHaveLength(62);
    expect(merged.frontier).toBe('loans');
    expect(merged.hasMore).toBe(true);
    const due = merged.rows.map((row) => Date.parse(row.item.dueAt ?? ''));
    expect(due).toEqual([...due].sort((a, b) => a - b));
    // Ties on the due time break by host, then id: day 5 is L5 then K5? loans sorts after kyc.
    expect(merged.rows.slice(4, 6).map((row) => row.item.id)).toEqual(['K5', 'L5']);
  });

  it('CKP_019_a_host_beyond_the_frontier_stays_hidden_until_the_frontier_moves', () => {
    const merged = mergeHosts([
      { hostId: 'loans', items: tasks('L', 1, 50), hasNext: true },
      { hostId: 'kyc', items: tasks('K', 40, 60), hasNext: false },
    ], byDueTime);
    expect(merged.rows.filter((row) => row.hostId === 'kyc')).toHaveLength(11);
    expect(merged.hidden).toBe(10);
  });

  it('CKP_019_tasks_without_a_due_time_come_last_and_a_complete_host_set_shows_everything', () => {
    const merged = mergeHosts([
      { hostId: 'loans', items: [task('L1', null), task('L2', 3)], hasNext: false },
      { hostId: 'kyc', items: [task('K1', 2)], hasNext: false },
    ], byDueTime);
    expect(merged.rows.map((row) => row.item.id)).toEqual(['K1', 'L2', 'L1']);
    expect(merged.hasMore).toBe(false);
    expect(merged.frontier).toBeUndefined();
  });

  it('CKP_020_incidents_merge_newest_first', () => {
    const incident = (id: string, createdAt: string) => ({ id, createdAt }) as never;
    const merged = mergeHosts([
      { hostId: 'loans', items: [incident('N3', '2027-01-01T10:00:00Z')], hasNext: false },
      { hostId: 'kyc', items: [incident('N9', '2027-01-01T10:05:00Z')], hasNext: false },
    ], byNewest);
    expect(merged.rows.map((row) => row.item.id)).toEqual(['N9', 'N3']);
  });
});

/** A host that answers from a script and records what it was asked. */
function host(answer: (cursor: string | undefined, etag: string | undefined) => Conditional<Page<TaskSummary>>) {
  const asked: { cursor: string | undefined; etag: string | undefined }[] = [];
  const read: ReadPage<TaskSummary> = (cursor, etag) => {
    asked.push({ cursor, etag });
    return Promise.resolve(answer(cursor, etag));
  };
  return { read, asked };
}

describe('CKP-019 refresh with If-None-Match', () => {
  it('CKP_019_refresh_sends_the_etag_and_a_304_keeps_the_page', async () => {
    const first = page([task('L1', 1)], null);
    const loans = host((_cursor, etag) => (etag === '"v1"' ? { changed: false, etag } : { changed: true, etag: '"v1"', data: first }));
    const held = await readAgain<TaskSummary>(undefined, loans.read);
    const again = await readAgain<TaskSummary>(held, loans.read);
    expect(loans.asked).toEqual([{ cursor: undefined, etag: undefined }, { cursor: undefined, etag: '"v1"' }]);
    expect(again.pages[0]?.page).toBe(first);
  });

  it('CKP_019_refresh_without_an_etag_reads_the_page_again_in_full', async () => {
    let body = page([task('L1', 1)], null);
    const loans = host(() => ({ changed: true, etag: undefined, data: body }));
    const held = await readAgain<TaskSummary>(undefined, loans.read);
    body = page([task('L1', 1), task('L2', 2)], null);
    const again = await readAgain<TaskSummary>(held, loans.read);
    expect(loans.asked.every((request) => request.etag === undefined)).toBe(true);
    expect(again.pages[0]?.page.data.map((item) => item.id)).toEqual(['L1', 'L2']);
  });

  it('CKP_019_refresh_keeps_each_loaded_page_with_its_own_etag', async () => {
    const pages: Record<string, Page<TaskSummary>> = { first: page([task('L1', 1)], 'c1'), c1: page([task('L2', 2)], null) };
    const loans = host((cursor) => ({ changed: true, etag: `"${cursor ?? 'first'}"`, data: pages[cursor ?? 'first'] as Page<TaskSummary> }));
    const held = await readNext<TaskSummary>(await readAgain<TaskSummary>(undefined, loans.read), loans.read);
    expect(held.pages.map((entry) => entry.etag)).toEqual(['"first"', '"c1"']);
    loans.asked.length = 0;
    const calm = host((_cursor, etag): Conditional<Page<TaskSummary>> => ({ changed: false, etag }));
    const again = await readAgain<TaskSummary>(held, calm.read);
    expect(calm.asked).toEqual([{ cursor: undefined, etag: '"first"' }, { cursor: 'c1', etag: '"c1"' }]);
    expect(again).toEqual(held);
  });
});
