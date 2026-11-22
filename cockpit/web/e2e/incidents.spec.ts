import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { BpmMeV1 } from '../src/api/generated/bpm-me.v1';
import { meHoa, meLinh, meTuan, metaWithProcesses } from '../src/mocks/fixtures';
import { INCIDENTS } from '../src/mocks/incidentDemo';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { routeHostSignIn } from './hostSignIn';
import { mockHost, rememberLocale } from './mockHost';

/*
 * The incident queue (CKP-008, CKP-080) at 1440x900 and 1440x1200, light and dark, English and Vietnamese: every state
 * an operations officer, a second officer and a reader meet, from the example host's sign-in page to the queue. Each
 * run checks that the list and the state's focal element are on screen without scrolling, that the page never scrolls
 * sideways, and axe-core finds no serious violation. Screenshots go to INCIDENTS_SHOTS_DIR, or e2e/screenshots/.
 */

const SHOTS = process.env['INCIDENTS_SHOTS_DIR'] ?? 'e2e/screenshots';
const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

const T = {
  title: { en: 'Incidents', vi: 'Sự cố' },
  retry: { en: 'Retry', vi: 'Chạy lại' },
  resolve: { en: 'Resolve', vi: 'Nhập kết quả' },
  propose: { en: 'Propose result', vi: 'Đề xuất kết quả' },
  approve: { en: 'Approve result', vi: 'Phê duyệt kết quả' },
  reason: { en: 'Reason', vi: 'Lý do' },
  name1: { en: 'Field 1 name', vi: 'Tên trường 1' },
  kind1: { en: 'Field 1 kind', vi: 'Loại trường 1' },
  value1: { en: 'Field 1 value', vi: 'Giá trị trường 1' },
  memo: { en: 'Limit raised by credit committee, memo CC-118', vi: 'Hội đồng tín dụng nâng hạn mức, biên bản CC-118' },
  signIn: { en: 'Sign in', vi: 'Đăng nhập' },
} as const;

type Engine = ReturnType<typeof createFakeProcessEngine>;

async function mockEngines(page: Page, user: BpmMeV1, locale: Locale): Promise<Engine> {
  const engine = createFakeProcessEngine({ locale });
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/bpm/rules/v1/**', (route) => route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ status: 403, code: 'PERMISSION_DENIED' }) }));
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: user.user.id, permissions: user.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return engine;
}

const inspector = (page: Page) => page.locator('.incidents__inspector');
const dialog = (page: Page) => page.getByRole('dialog');

async function openResolve(page: Page, l: Locale, name: string) {
  await inspector(page).getByRole('button', { name: T.resolve[l], exact: true }).click();
  await expect(dialog(page)).toBeVisible();
  await dialog(page).getByLabel(T.name1[l]).fill(name);
  await dialog(page).getByLabel(T.kind1[l]).selectOption('amount');
  await dialog(page).getByLabel(T.value1[l]).fill('30000000');
  await dialog(page).getByLabel(T.reason[l], { exact: true }).fill(T.memo[l]);
}

interface Screen {
  name: string;
  user: BpmMeV1;
  hash: string;
  prepare?: (page: Page, l: Locale) => Promise<void>;
  focal: (page: Page, l: Locale) => Locator;
  /** No list on screen (an empty or failed list): only the focal element is checked. */
  noList?: boolean;
}

const SCREENS: readonly Screen[] = [
  { name: 'open-agent-timeout', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.agentTimeout}`, focal: (page, l) => inspector(page).getByRole('button', { name: T.retry[l], exact: true }) },
  { name: 'open-business-rule', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.limitRefused}`, focal: (page, l) => inspector(page).getByRole('button', { name: T.resolve[l], exact: true }) },
  { name: 'manual-undo-retry-unavailable', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.manualUndo}`, focal: (page) => inspector(page).locator('.tasks__why') },
  {
    name: 'retry-dialog', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.agentTimeout}`,
    prepare: async (page, l) => { await inspector(page).getByRole('button', { name: T.retry[l], exact: true }).click(); await expect(dialog(page)).toBeVisible(); },
    focal: (page, l) => dialog(page).getByRole('button', { name: T.retry[l], exact: true }),
  },
  {
    name: 'retry-scheduled', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.agentTimeout}`,
    prepare: async (page, l) => {
      await inspector(page).getByRole('button', { name: T.retry[l], exact: true }).click();
      await dialog(page).getByLabel(T.reason[l], { exact: true }).fill(l === 'en' ? 'Agent service back after maintenance' : 'Dịch vụ tác tử đã hoạt động lại');
      await dialog(page).getByRole('button', { name: T.retry[l], exact: true }).click();
      await expect(page.locator('.toast')).toBeVisible();
    },
    focal: (page) => page.locator('.toast'),
  },
  {
    name: 'resolve-dialog', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.limitRefused}`,
    prepare: async (page, l) => { await openResolve(page, l, 'approvedLimit'); },
    focal: (page, l) => dialog(page).getByRole('button', { name: T.propose[l] }),
  },
  {
    name: 'resolve-refused', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.limitRefused}`,
    prepare: async (page, l) => {
      await openResolve(page, l, 'limit');
      await dialog(page).getByRole('button', { name: T.propose[l] }).click();
      await expect(dialog(page).getByRole('alert')).toBeVisible();
    },
    focal: (page) => dialog(page).getByRole('alert'),
  },
  {
    name: 'proposed', user: meTuan, hash: `#/incidents?incident=${INCIDENTS.limitRefused}`,
    prepare: async (page, l) => {
      await openResolve(page, l, 'approvedLimit');
      await dialog(page).getByRole('button', { name: T.propose[l] }).click();
      await expect(page.locator('.toast')).toBeVisible();
      await expect(inspector(page).locator('.incidents__proposal')).toBeVisible();
    },
    focal: (page) => inspector(page).locator('.tasks__why'),
  },
  { name: 'pending-second-person', user: meTuan, hash: `#/incidents?view=pending&incident=${INCIDENTS.proposed}`, focal: (page, l) => inspector(page).getByRole('button', { name: T.approve[l] }) },
  { name: 'pending-proposer', user: meLinh, hash: `#/incidents?view=pending&incident=${INCIDENTS.proposed}`, focal: (page) => inspector(page).locator('.tasks__why') },
  { name: 'reader', user: meHoa, hash: '#/incidents', focal: (page) => inspector(page).locator('.incidents__readonly') },
  {
    name: 'pending-empty', user: meTuan, hash: `#/incidents?view=pending&incident=${INCIDENTS.proposed}`, noList: true,
    prepare: async (page, l) => {
      await inspector(page).getByRole('button', { name: T.approve[l] }).click();
      await dialog(page).getByRole('button', { name: T.approve[l] }).click();
      await expect(page.locator('.tasks__empty')).toBeVisible();
    },
    focal: (page) => page.locator('.tasks__empty'),
  },
  { name: 'list-failed', user: meTuan, hash: '#/incidents', noList: true, focal: (page) => page.getByRole('alert') },
];

async function openScreen(page: Page, screen: Screen, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: Locale) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  const engine = await mockEngines(page, screen.user, locale);
  if (screen.name === 'list-failed') {
    await page.route('**/api/bpm/processes/v1/incidents?status=OPEN**', (route) => route.fulfill({
      status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Internal error', status: 500, code: 'INTERNAL_ERROR', correlationId: '01JD6N9C0RRE1AT10N000000E5' }),
    }));
  }
  await page.goto(`./${screen.hash}`);
  await expect(page.getByRole('heading', { level: 1, name: T.title[locale] })).toBeVisible();
  await expect(page.locator('.skeleton')).toHaveCount(0);
  if (screen.prepare) await screen.prepare(page, locale);
  return engine;
}

async function expectAboveFold(locator: Locator, height: number) {
  await expect(locator).toBeVisible();
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.y + box.height).toBeLessThanOrEqual(height);
}

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const size = `${String(viewport.width)}x${String(viewport.height)}`;
        test(`TR_UI_03_visual_incidents_${screen.name}_${size}_${theme}_${locale}`, async ({ page }) => {
          await openScreen(page, screen, viewport, theme, locale);
          await expectAboveFold(screen.focal(page, locale), viewport.height);
          if (screen.noList !== true) await expectAboveFold(page.locator('.tasks__table tbody tr').first(), viewport.height);
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
          await page.screenshot({ path: `${SHOTS}/incidents-${screen.name}-${size}-${theme}-${locale}.png`, fullPage: false });
        });
      }
    }
  }
}

for (const screen of SCREENS) {
  for (const theme of THEMES) {
    test(`TR_UI_03_axe_no_serious_violations_incidents_${screen.name}_${theme}`, async ({ page }) => {
      await openScreen(page, screen, { width: 1440, height: 900 }, theme, 'vi');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
      expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
    });
  }
}

test('CKP_008_demo_an_incident_retried_from_the_queue_leaves_it_once_the_step_succeeds', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page, meTuan, 'en');
  await page.goto(`./#/incidents?incident=${INCIDENTS.agentTimeout}`);
  await expect(page.getByRole('tab', { name: 'Open (5)' })).toBeVisible();
  await inspector(page).getByRole('button', { name: 'Retry', exact: true }).click();
  await dialog(page).getByLabel('Reason', { exact: true }).fill('Agent service back after maintenance');
  await dialog(page).getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByText('Retry scheduled for Assess documents. The result shows on the next refresh.')).toBeVisible();
  await expect(page.locator('.tasks__table').getByText('Retrying')).toBeVisible();
  expect(engine.incidentCommands).toEqual([expect.objectContaining({ command: 'retry', incidentId: INCIDENTS.agentTimeout, ifMatch: '"0"' })]);
  // The next refresh reads the list again; the step was accepted, so the incident has left it.
  await page.reload();
  await expect(page.getByRole('tab', { name: 'Open (4)' })).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'Assess documents' })).toHaveCount(0);
});

test('CKP_008_retry_is_reached_and_sent_by_keyboard_alone_with_a_visible_focus', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page, meTuan, 'en');
  await page.goto(`./#/incidents?incident=${INCIDENTS.coreTimeout}`);
  const retry = inspector(page).getByRole('button', { name: 'Retry', exact: true });
  await expect(retry).toBeVisible();
  for (let step = 0; step < 60; step += 1) {
    await page.keyboard.press('Tab');
    if (await retry.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(retry).toBeFocused();
  expect(await retry.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toBeVisible();
  await page.keyboard.type('Core banking back');
  const send = dialog(page).getByRole('button', { name: 'Retry', exact: true });
  for (let step = 0; step < 6; step += 1) {
    await page.keyboard.press('Tab');
    if (await send.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(send).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Retry scheduled for Screen sanctions lists. The result shows on the next refresh.')).toBeVisible();
  expect(engine.incidentCommands.map((command) => command.command)).toEqual(['retry']);
});

for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      const size = `${String(viewport.width)}x${String(viewport.height)}`;
      test(`TR_UI_03_journey_sign_in_to_incidents_${size}_${theme}_${locale}`, async ({ page }) => {
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await rememberLocale(page, locale);
        await routeHostSignIn(page, locale === 'en' ? 'Retail banking' : 'Ngân hàng bán lẻ');
        await page.goto(`./login?lang=${locale}`);
        const submit = page.getByRole('button', { name: T.signIn[locale], exact: true });
        await expectAboveFold(submit, viewport.height);
        await page.screenshot({ path: `${SHOTS}/incidents-journey-1-sign-in-${size}-${theme}-${locale}.png`, fullPage: false });
        await mockEngines(page, meTuan, locale);
        await page.goto('./#/home');
        const menu = page.getByTestId('nav-rail');
        await expect(menu.getByRole('link', { name: T.title[locale], exact: true })).toBeVisible();
        await page.screenshot({ path: `${SHOTS}/incidents-journey-2-home-${size}-${theme}-${locale}.png`, fullPage: false });
        await menu.getByRole('link', { name: T.title[locale], exact: true }).click();
        await expect(page.getByRole('heading', { level: 1, name: T.title[locale] })).toBeVisible();
        await expect(page.locator('.skeleton')).toHaveCount(0);
        await expect(menu.getByRole('link', { name: T.title[locale], exact: true })).toHaveAttribute('aria-current', 'page');
        await expectAboveFold(inspector(page).locator('.btn--primary').first(), viewport.height);
        await page.screenshot({ path: `${SHOTS}/incidents-journey-3-incidents-${size}-${theme}-${locale}.png`, fullPage: false });
      });
    }
  }
}
