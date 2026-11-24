import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';
import { displayNamesAnswer } from '../src/mocks/displayNames';
import { ENGINE_VERSION } from '../src/mocks/engineVersion';
import { meLan } from '../src/mocks/fixtures';
import { rememberLocale } from './mockHost';
import { KYC, LOANS, mockStandalone, type HostAnswer } from './standaloneHosts';

/*
 * One task inbox and one incident list across two hosts (CKP-019, CKP-020), standalone, against hosts that answer
 * from a script. Each state is checked with axe and photographed at 1440x900; the theme and the language rotate from
 * state to state, and two states are also photographed at 1440x1200. Written to SCREENSHOT_DIR.
 */

const DIR = process.env['SCREENSHOT_DIR'] ?? 'e2e/screenshots';
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, accept, content-type, x-requested-with, idempotency-key, if-match, if-none-match, x-correlation-id, x-tenant-id',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
  'Access-Control-Expose-Headers': 'etag, x-correlation-id, content-disposition',
};
const BASE = '/api/bpm/processes/v1';
const processes = { module: 'processes' as const, engineVersion: ENGINE_VERSION, apiVersion: 'v1', features: [], settings: {} };
const operator: HostAnswer = {
  meta: { modules: [processes] },
  me: { ...meLan, user: { id: 'tuan', displayName: 'Trần Anh Tuấn' }, permissions: ['processes:read', 'processes:task:work', 'processes:incident:manage'] },
};

const ID = (tag: string) => `01JD8M${tag.padEnd(20, '0')}`;
const iso = (day: number, hour = 9) => new Date(Date.UTC(2026, 10, day, hour - 7)).toISOString();

interface TaskSeed { tag: string; name: { en: string; vi: string }; key: string; role: string; due: string | null; claimedBy?: string }
const task = (seed: TaskSeed) => ({
  id: ID(seed.tag), instanceId: ID(`I${seed.tag}`), definitionKey: 'process', definitionVersion: 1, businessKey: seed.key, nodeId: 'step', name: seed.name.en,
  assignee: seed.role, status: seed.claimedBy ? 'CLAIMED' : 'OPEN', lockVersion: 0, claimedBy: seed.claimedBy ?? null, createdAt: iso(20), dueAt: seed.due, overdueBy: null,
});

const LOAN_TASKS = [
  task({ tag: 'T1', name: { en: 'Credit review', vi: 'Thẩm định tín dụng' }, key: 'LN-2026-0142', role: 'role:credit-officer', due: iso(23, 10) }),
  task({ tag: 'T2', name: { en: 'Collateral valuation', vi: 'Định giá tài sản bảo đảm' }, key: 'LN-2026-0128', role: 'role:credit-officer', due: iso(25, 15), claimedBy: 'tuan' }),
  task({ tag: 'T3', name: { en: 'Approve limit', vi: 'Phê duyệt hạn mức' }, key: 'LN-2026-0150', role: 'group:loans-operations', due: iso(27, 9) }),
];
const KYC_TASKS = [
  task({ tag: 'T4', name: { en: 'Verify identity documents', vi: 'Xác minh giấy tờ định danh' }, key: 'KYC-9051', role: 'role:compliance-reviewer', due: iso(24, 11) }),
  task({ tag: 'T5', name: { en: 'Screen sanctions lists', vi: 'Rà soát danh sách trừng phạt' }, key: 'KYC-9057', role: 'role:compliance-reviewer', due: iso(26, 14) }),
];

const incident = (tag: string, nodeId: string, errorCode: string, createdAt: string, extra: Record<string, unknown> = {}) => ({
  id: ID(tag), instanceId: ID(`I${tag}`), nodeId, tokenId: null, errorCode, causeSummary: errorCode, retryCount: 2, status: 'OPEN', lockVersion: 3, retrying: false,
  createdAt, resolvedAt: null, resolution: null, resolvedBy: null, pendingResolution: null, allowedActions: ['retry', 'resolve'], ...extra,
});
const LOAN_INCIDENTS = [incident('N3', 'assess-documents', 'AGENT_TIMEOUT', iso(21, 10))];
const KYC_INCIDENTS = [incident('N9', 'screen-sanctions', 'ADAPTER_TIMEOUT', iso(21, 10)), incident('N8', 'verify-docs', 'SERVICE_ERROR', iso(20, 16), {
  status: 'RESOLUTION_PENDING', allowedActions: [], pendingResolution: { proposedBy: 'tuan', proposedAt: iso(21, 8), reason: 'Branch checked the originals', output: { documentsVerified: true }, approvalMode: 'four-eyes' },
})];

type Behaviour = 'ok' | 'down' | 'empty';
interface Setup { loans?: Behaviour; kyc?: Behaviour }
interface Calls { posts: { host: string; url: string; headers: Record<string, string> }[]; ifNoneMatch: string[] }

async function serve(page: Page, setup: Setup = {}): Promise<Calls> {
  const record: Calls = { posts: [], ifNoneMatch: [] };
  await mockStandalone(page, { loans: operator, kyc: operator });
  for (const [id, origin, tasks, incidents, behaviour] of [
    ['loans', LOANS, LOAN_TASKS, LOAN_INCIDENTS, setup.loans ?? 'ok'],
    ['kyc', KYC, KYC_TASKS, KYC_INCIDENTS, setup.kyc ?? 'ok'],
  ] as const) {
    let retried = false;
    await page.route(`${origin}${BASE}/**`, async (route: Route) => {
      const request = route.request();
      if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
      const url = new URL(request.url());
      const path = url.pathname.slice(BASE.length);
      const headers = request.headers();
      if (headers['if-none-match'] !== undefined) record.ifNoneMatch.push(`${id}:${headers['if-none-match']}`);
      if (behaviour === 'down') return route.fulfill({ status: 503, headers: CORS, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Unavailable', status: 503, code: 'SERVICE_UNAVAILABLE', correlationId: '01JD8M00000000000000000COR' }) });
      const json = (body: unknown, extra: Record<string, string> = {}) => route.fulfill({ status: 200, headers: { ...CORS, ...extra }, contentType: 'application/json', body: JSON.stringify(body) });
      if (request.method() === 'POST') {
        record.posts.push({ host: id, url: request.url(), headers });
        retried = true;
        return json({ ...incidents[0], retrying: true, allowedActions: [] });
      }
      if (path === '/tasks') return json({ data: behaviour === 'empty' ? [] : tasks, nextCursor: null, hasNext: false });
      if (path === '/incidents') {
        const status = url.searchParams.get('status') ?? 'OPEN';
        const list = behaviour === 'empty' ? [] : incidents.filter((item) => item.status === status).map((item) => (retried && item.id === incidents[0]?.id ? { ...item, retrying: true, allowedActions: [] } : item));
        return json({ data: list, nextCursor: null, hasNext: false });
      }
      const one = /^\/tasks\/([^/:]+)$/.exec(path);
      if (one !== null) {
        const found = tasks.find((item) => item.id === one[1]);
        return found === undefined ? route.fulfill({ status: 404, headers: CORS, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Not found', status: 404, code: 'TASK_NOT_FOUND' }) })
          : json({ ...found, fourEyes: false, claimedAt: null, completedBy: null, completedAt: null, allowedActions: found.status === 'OPEN' ? ['claim'] : ['unclaim', 'complete'] });
      }
      const inc = /^\/incidents\/([^/:]+)$/.exec(path);
      if (inc !== null) {
        const found = incidents.find((item) => item.id === inc[1]);
        return found === undefined ? route.fulfill({ status: 404, headers: CORS, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Not found', status: 404, code: 'INCIDENT_NOT_FOUND' }) })
          : json(retried && found.id === incidents[0]?.id ? { ...found, retrying: true, allowedActions: [] } : found);
      }
      return route.fulfill({ status: 404, headers: CORS, contentType: 'application/problem+json', body: JSON.stringify({ title: 'Not found', status: 404, code: 'NOT_FOUND' }) });
    });
    await page.route(`${origin}/api/bpm/display-names**`, (route) => route.fulfill({ status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify(displayNamesAnswer(route.request().url(), route.request().headers()['accept-language'])) }));
  }
  return record;
}

interface State {
  name: string;
  hash: string;
  setup?: Setup;
  ready: (page: Page) => Promise<void>;
  after?: (page: Page, record: Calls) => Promise<void>;
  tall?: boolean;
  /** Also photographed in the other language. */
  both?: boolean;
}

const rows = (page: Page) => page.locator('table tbody tr');

const STATES: readonly State[] = [
  { name: 'inbox-merged', hash: '#/hosts/loans/tasks?scope=all', tall: true, ready: async (page) => { await expect(rows(page)).toHaveCount(5); } },
  { name: 'inbox-incomplete', hash: '#/hosts/loans/tasks?scope=all', setup: { kyc: 'down' }, ready: async (page) => { await expect(rows(page)).toHaveCount(3); await expect(page.locator('.alert--warn')).toBeVisible(); } },
  { name: 'inbox-none-reachable', hash: '#/hosts/loans/tasks?scope=all', setup: { loans: 'down', kyc: 'down' }, ready: async (page) => { await expect(page.locator('.empty-state')).toBeVisible(); } },
  { name: 'inbox-empty', hash: '#/hosts/loans/tasks?scope=all', setup: { loans: 'empty', kyc: 'empty' }, ready: async (page) => { await expect(page.locator('.empty-state')).toBeVisible(); } },
  { name: 'incidents-merged', hash: '#/hosts/loans/incidents?scope=all', tall: true, ready: async (page) => { await expect(rows(page)).toHaveCount(2); } },
  { name: 'incidents-incomplete', hash: '#/hosts/loans/incidents?scope=all', setup: { kyc: 'down' }, ready: async (page) => { await expect(rows(page)).toHaveCount(1); await expect(page.locator('.alert--warn')).toBeVisible(); } },
  {
    name: 'incidents-retry-on-owning-host', both: true, hash: '#/hosts/loans/incidents?scope=all',
    ready: async (page) => { await expect(rows(page)).toHaveCount(2); },
    after: async (page, record) => {
      const inspector = page.getByRole('complementary');
      await page.locator('table tbody tr').filter({ hasText: /Assess documents/ }).getByRole('link').click();
      await inspector.getByRole('button', { name: /^(Retry|Chạy lại)$/ }).click();
      await page.getByRole('dialog').getByRole('textbox').fill('Agent service is back');
      await page.getByRole('dialog').getByRole('button', { name: /^(Retry|Chạy lại)$/ }).click();
      await expect(page.locator('table tbody tr').filter({ hasText: /Retrying|Đang chạy/ })).toHaveCount(1);
      expect(record.posts).toHaveLength(1);
      expect(record.posts[0]?.host).toBe('loans');
    },
  },
  { name: 'incidents-waiting-for-approval', hash: '#/hosts/loans/incidents?scope=all&view=pending', ready: async (page) => { await expect(rows(page)).toHaveCount(1); } },
];

const RUNS = STATES.flatMap((state, index) => {
  const theme = index % 2 === 0 ? 'light' as const : 'dark' as const;
  const locale = Math.floor(index / 2) % 2 === 0 ? 'en' as const : 'vi' as const;
  return [{ state, theme, locale }, ...(state.both === true ? [{ state, theme: theme === 'light' ? 'dark' as const : 'light' as const, locale: locale === 'en' ? 'vi' as const : 'en' as const }] : [])];
});

RUNS.forEach(({ state, theme, locale }) => {
  test(`CKP_019_CKP_020_merged_${state.name}_1440x900_${theme}_${locale}`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await rememberLocale(page, locale);
    const record = await serve(page, state.setup);
    await page.goto(`./${state.hash}`);
    await state.ready(page);
    await state.after?.(page, record);
    await expect(page.locator('html')).toHaveAttribute('lang', locale);
    const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
    expect(axe.violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.screenshot({ path: `${DIR}/${state.name}-1440x900-${theme}-${locale}.png` });
    if (state.tall === true) {
      await page.setViewportSize({ width: 1440, height: 1200 });
      await page.screenshot({ path: `${DIR}/${state.name}-1440x1200-${theme}-${locale}.png` });
    }
  });
});

test('CKP_019_the_scope_switch_moves_between_this_service_and_all_services_and_keeps_the_page_in_view', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await rememberLocale(page, 'en');
  await serve(page);
  await page.goto('./#/hosts/loans/tasks');
  await expect(page.getByRole('button', { name: 'All services' })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: 'All services' }).click();
  await expect(rows(page)).toHaveCount(5);
  // The primary action of the selected task is above the fold.
  const action = page.getByRole('complementary').getByRole('button').first();
  await expect(action).toBeInViewport();
  await page.getByRole('button', { name: 'This service' }).click();
  await expect(page).toHaveURL(/#\/hosts\/loans\/tasks$/);
});
