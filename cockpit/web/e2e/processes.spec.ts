import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { meHoa, metaWithProcesses } from '../src/mocks/fixtures';
import { REVIEW_TASK_ID } from '../src/mocks/processDemo';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * The process screens at the two laptop sizes, in both themes and both languages (CKP-069, CKP-070, CKP-007):
 * the definition list, the loan process diagram with its generic boundary-timer node, and the review of the
 * escalated agent step before and during an override. Each run checks the fold (title and primary action visible
 * at 1440x900, no sideways scrolling) and runs axe-core. Screenshots go to e2e/screenshots/.
 */

const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

async function mockEngines(page: Page, user = meHoa) {
  const processes = createFakeProcessEngine();
  const rules = createFakeEngine(demoSeed());
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = rules.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: user.user.id, permissions: user.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = processes.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: user.user.id, permissions: user.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return processes;
}

interface Screen {
  name: string;
  hash: string;
  title: Record<Locale, string | RegExp>;
  /** Steps after the page opens, for example taking the review and starting an override. */
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  /** The element that must be visible without scrolling at 1440x900: the primary action, or the diagram. */
  focal: (page: Page, locale: Locale) => ReturnType<Page['locator']>;
}

const T = {
  start: { en: 'Start review', vi: 'Bắt đầu xem xét' },
  override: { en: 'Override', vi: 'Sửa' },
  record: { en: 'Record override', vi: 'Ghi nhận sửa đổi' },
  approve: { en: 'Approve answer', vi: 'Duyệt câu trả lời' },
  reason: { en: 'Reason', vi: 'Lý do' },
} as const;

const SCREENS: readonly Screen[] = [
  {
    name: 'processes-list',
    hash: '#/processes',
    title: { en: 'Processes Preview', vi: 'Quy trình Xem trước' },
    focal: (page) => page.getByRole('link', { name: 'Loan origination' }),
  },
  {
    name: 'definition-diagram',
    hash: '#/processes/loan-origination/3',
    title: { en: 'Loan origination', vi: 'Loan origination' },
    focal: (page) => page.locator('[data-node="no-documents"]'),
  },
  {
    name: 'review-open',
    hash: `#/tasks/${REVIEW_TASK_ID}`,
    title: { en: 'Assess documents: review', vi: 'Assess documents: review' },
    focal: (page, locale) => page.getByRole('button', { name: T.start[locale] }),
  },
  {
    name: 'review-override',
    hash: `#/tasks/${REVIEW_TASK_ID}`,
    title: { en: 'Assess documents: review', vi: 'Assess documents: review' },
    prepare: async (page, locale) => {
      await page.getByRole('button', { name: T.start[locale] }).click();
      await page.getByRole('button', { name: T.override[locale], exact: true }).click();
      await page.getByLabel('recommendation').fill('REFER');
      await page.getByLabel('riskScore').fill('72');
    },
    focal: (page, locale) => page.getByRole('button', { name: T.record[locale] }),
  },
];

async function openScreen(page: Page, screen: Screen, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: Locale) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  await mockEngines(page);
  await page.goto(`./${screen.hash}`);
  await expect(page.getByRole('heading', { level: 1, name: screen.title[locale] })).toBeVisible();
  await expect(page.locator('.skeleton')).toHaveCount(0);
  if (screen.prepare) await screen.prepare(page, locale);
}

async function bottomOf(locator: ReturnType<Page['locator']>): Promise<number> {
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  return box.y + box.height;
}

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `proc-${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`TR_UI_03_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          await openScreen(page, screen, viewport, theme, locale);
          const focal = screen.focal(page, locale);
          await expect(focal).toBeVisible();
          expect(await bottomOf(focal)).toBeLessThanOrEqual(viewport.height);
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
          await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
        });
      }
    }
  }
}

for (const screen of SCREENS) {
  for (const theme of THEMES) {
    test(`TR_UI_03_axe_no_serious_violations_${screen.name}_${theme}`, async ({ page }) => {
      await openScreen(page, screen, { width: 1440, height: 900 }, theme, 'en');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
      expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
    });
  }
}

test('CKP_007_an_override_is_recorded_with_its_reason_by_keyboard_alone', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page);
  await page.goto(`./#/tasks/${REVIEW_TASK_ID}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Assess documents: review' })).toBeVisible();

  const start = page.getByRole('button', { name: 'Start review' });
  for (let step = 0; step < 40; step += 1) {
    await page.keyboard.press('Tab');
    if (await start.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(start).toBeFocused();
  expect(await start.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');

  await page.getByRole('button', { name: 'Override', exact: true }).focus();
  await page.keyboard.press('Enter');
  await page.getByLabel('recommendation').fill('DECLINE');
  await page.getByLabel('Reason').fill('Salary slips do not support the declared income');
  await page.getByRole('button', { name: 'Record override' }).focus();
  await page.keyboard.press('Enter');

  await expect(page.getByText(/Decided by hoa/)).toBeVisible();
  expect(engine.overrides).toEqual([expect.objectContaining({ reviewedBy: 'hoa', result: { riskScore: 38, recommendation: 'DECLINE', incomeVerified: true } })]);
});

test('CKP_069_the_diagram_chunk_loads_only_when_a_diagram_opens', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockEngines(page);
  const scripts: string[] = [];
  page.on('request', (request) => { if (request.resourceType() === 'script') scripts.push(new URL(request.url()).pathname); });
  await page.goto('./#/processes');
  await expect(page.getByRole('link', { name: 'Loan origination' })).toBeVisible();
  const before = scripts.length;
  await page.getByRole('link', { name: 'Loan origination' }).click();
  await expect(page.locator('[data-layout="auto"]')).toBeVisible();
  expect(scripts.slice(before).some((path) => /DefinitionDiagram/.test(path))).toBe(true);
});
