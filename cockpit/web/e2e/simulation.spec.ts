import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import type { Scenario } from '../src/mocks/fixtures';
import { simulationSeed } from '../src/mocks/ruleEngine';
import { routeHostSignIn } from './hostSignIn';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

// The simulation screen of a draft (CKP-004) in every state: 1440x900 and 1440x1200, light and dark, English and
// Vietnamese. The screen's action and the first result are visible without scrolling; axe finds nothing.
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
type Words = Record<Locale, string>;

const SIM = '**/api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate-readings';
const problem = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ type: 'about:blank', title: 'Refused', status, code, detail: 'refused', correlationId: '01JFAKE0000000000000000042' }),
});

const RUN: Words = { en: 'Run simulation', vi: 'Chạy mô phỏng' };
const PASTE: Words = { en: 'Paste rows', vi: 'Dán các dòng' };
const ROWS: Words = { en: 'Rows', vi: 'Các dòng' };
const ADD: Words = { en: 'Add these rows', vi: 'Thêm các dòng này' };
const EDIT: Words = { en: 'Edit draft', vi: 'Sửa bản nháp' };
const BACK: Words = { en: 'Change the samples', vi: 'Đổi các mẫu' };

const FIVE: Words = {
  en: ['Platinum card\tPlatinum\t\t', 'Gold, high balance\tGold\t60000000\t', 'Gold, mid balance\tGold\t30000000\t', 'Busy classic\tClassic\t\t25', 'Quiet classic\tClassic\t\t2'].join('\n'),
  vi: ['Thẻ Bạch kim\tPlatinum\t\t', 'Thẻ Vàng, số dư cao\tGold\t60000000\t', 'Thẻ Vàng, số dư vừa\tGold\t30000000\t', 'Thẻ Cổ điển, giao dịch nhiều\tClassic\t\t25', 'Thẻ Cổ điển, giao dịch ít\tClassic\t\t2'].join('\n'),
};

async function pasteFive(page: Page, locale: Locale) {
  await page.getByRole('button', { name: PASTE[locale], exact: true }).click();
  await page.getByLabel(ROWS[locale], { exact: true }).fill(FIVE[locale]);
  await page.getByRole('button', { name: ADD[locale], exact: true }).click();
}
async function runFive(page: Page, locale: Locale) {
  await pasteFive(page, locale);
  await page.getByRole('button', { name: RUN[locale], exact: true }).click();
}

interface Screen {
  name: string;
  scenario: Scenario;
  hash: string;
  /** The action that must be above the fold. */
  primary?: Words;
  /** Something else that must be visible without scrolling. */
  visible?: string;
  route?: (page: Page) => Promise<void>;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  ready: (page: Page) => Promise<void>;
}

const SCREENS: readonly Screen[] = [
  { name: 'samples-blank', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: RUN,
    ready: async (page) => { await expect(page.locator('.simrun__reason')).toBeVisible(); await expect(page.locator('.simrun__run')).toBeDisabled(); } },
  { name: 'paste', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: ADD,
    prepare: async (page, locale) => { await page.getByRole('button', { name: PASTE[locale], exact: true }).click(); await page.getByLabel(ROWS[locale], { exact: true }).fill(FIVE[locale]); },
    ready: async (page) => { await expect(page.locator('.simrun__paste')).toBeVisible(); } },
  { name: 'paste-problem', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: ADD,
    prepare: async (page, locale) => {
      await page.getByRole('button', { name: PASTE[locale], exact: true }).click();
      await page.getByLabel(ROWS[locale], { exact: true }).fill('Silver\t1\t1');
      await page.getByRole('button', { name: ADD[locale], exact: true }).click();
    },
    ready: async (page) => { await expect(page.locator('.simrun .cell-error')).toBeVisible(); } },
  { name: 'samples-five', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: RUN, prepare: pasteFive,
    ready: async (page) => { await expect(page.locator('.simrun__grid tbody tr')).toHaveCount(5); await expect(page.locator('.simrun__run')).toBeEnabled(); } },
  { name: 'samples-invalid', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: RUN,
    prepare: async (page, locale) => { await page.locator('.simrun__grid .money-input input').first().fill(locale === 'vi' ? '12,5' : '12.5'); await page.getByRole('button', { name: RUN[locale], exact: true }).click(); },
    ready: async (page) => { await expect(page.locator('.simrun__invalid')).toBeVisible(); } },
  { name: 'results', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: BACK, visible: '.simrun__results tbody tr:nth-child(3)', prepare: runFive,
    ready: async (page) => { await expect(page.locator('.simrun__row--changed')).toHaveCount(2); } },
  { name: 'loading', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: BACK, prepare: runFive,
    route: async (page) => { await page.route(SIM, () => { /* never answers: the skeleton stays */ }); },
    ready: async (page) => { await expect(page.locator('.simrun .skeleton')).toBeVisible(); } },
  { name: 'error', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: BACK, prepare: runFive,
    route: async (page) => { await page.route(SIM, (route) => route.fulfill(problem(500, 'INTERNAL'))); },
    ready: async (page) => { await expect(page.locator('.simrun .alert--error')).toBeVisible(); } },
  { name: 'forbidden', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', prepare: runFive,
    route: async (page) => { await page.route(SIM, (route) => route.fulfill(problem(403, 'PERMISSION_DENIED'))); },
    ready: async (page) => { await expect(page.locator('.simrun .reading-empty')).toBeVisible(); await expect(page.locator('.simrun__actions .btn')).toHaveCount(0); } },
  { name: 'too-large', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/2/simulate', primary: BACK, prepare: runFive,
    route: async (page) => { await page.route(SIM, (route) => route.fulfill(problem(422, 'READINGS_TOO_LARGE'))); },
    ready: async (page) => { await expect(page.locator('.simrun .reading-limit')).toBeVisible(); } },
  { name: 'no-permission', scenario: 'rules-author', hash: '#/rules/card-fee/2/simulate', primary: RUN,
    ready: async (page) => { await expect(page.locator('.simrun__reason')).toBeVisible(); await expect(page.locator('.simrun__run')).toBeDisabled(); } },
  { name: 'masked-samples', scenario: 'rules-author-simulate', hash: '#/rules/kyc-routing/2/simulate', primary: RUN,
    prepare: async (page) => { await page.locator('.simrun__grid input[type="password"]').fill('SEG-4471'); },
    ready: async (page) => { await expect(page.locator('.simrun__grid input[type="password"]')).toHaveValue('SEG-4471'); } },
  { name: 'masked-results', scenario: 'rules-author-simulate', hash: '#/rules/kyc-routing/2/simulate', primary: BACK, visible: '.simrun__results',
    prepare: async (page, locale) => { await page.locator('.simrun__grid input[type="password"]').fill('SEG-4471'); await page.getByRole('button', { name: RUN[locale], exact: true }).click(); },
    ready: async (page) => { await expect(page.locator('.simrun .reading__note')).toBeVisible(); } },
  { name: 'no-rows', scenario: 'rules-author-simulate', hash: '#/rules/new-fees/1/simulate', primary: EDIT,
    ready: async (page) => { await expect(page.locator('.simrun .reading-empty')).toBeVisible(); } },
  { name: 'drafts-only', scenario: 'rules-author-simulate', hash: '#/rules/card-fee/1/simulate',
    ready: async (page) => { await expect(page.locator('.simrun .reading-empty')).toBeVisible(); await expect(page.locator('.rs-tabs [role="tab"][aria-selected="true"]')).toHaveCount(0); } },
];

async function checkScreen(page: Page, viewport: { width: number; height: number }, locale: Locale, primary: Words | undefined, visible: string | undefined) {
  if (primary !== undefined) {
    const box = await page.getByRole('button', { name: primary[locale], exact: false }).last().boundingBox();
    expect(box, 'primary action is rendered').not.toBeNull();
    expect((box?.y ?? 0) + (box?.height ?? 0), 'primary action is above the fold').toBeLessThanOrEqual(viewport.height);
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
  }
  if (visible !== undefined) {
    const box = await page.locator(visible).first().boundingBox();
    expect((box?.y ?? 0) + Math.min(box?.height ?? 0, 120), 'result is visible without scrolling').toBeLessThanOrEqual(viewport.height);
  }
  const main = page.locator('main');
  // One primary action per view: the header's Request publish is secondary beside the view's own.
  expect(await main.locator('.btn--primary:visible').count()).toBeLessThanOrEqual(1);
  // No spec ids, raw expressions, raw JSON or masked values; one language at a time. An error code appears only
  // inside the closed Details disclosure, as the styleguide allows, so only the visible text is checked for it.
  await expect(main).not.toContainText(/CK-F-|CKP-0|PD-\d|RLE-0|READINGS_TOO_LARGE|PERMISSION_DENIED/);
  expect(await main.innerText()).not.toMatch(/\bINTERNAL\b/);
  await expect(main).not.toContainText(/==|'GOLD'|\{"|"inputs"/);
  await expect(main).not.toContainText('SEG-4471');
  if (locale === 'vi') await expect(main).not.toContainText(/Rule R\d applies|Run simulation|Samples|Sentence/);
  else await expect(main).not.toContainText(/Quy tắc R\d|Chạy mô phỏng|Mẫu thử|Rút gọn/);
}

async function axe(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
}

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `sim-${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`CKP_004_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockHost(page, screen.scenario);
          await mockRuleEngine(page, screen.scenario, simulationSeed());
          await screen.route?.(page);
          await page.goto(`./${screen.hash}`);
          await expect(page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await screen.prepare?.(page, locale);
          await screen.ready(page);
          await checkScreen(page, viewport, locale, screen.primary, screen.visible);
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
          expect(violations).toEqual([]);
          await axe(page);
        });
      }
    }
  }
}

/** The journey: the host's sign-in form, the rule sets, the draft, then its Simulate view with five samples run. */
for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      test(`CKP_004_journey_sign-in_to_simulate_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
        const size = `${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}`;
        const shot = async (step: string) => {
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `e2e/screenshots/sim-journey-${step}-${size}.png`, fullPage: false });
        };
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await rememberLocale(page, locale);
        await routeHostSignIn(page, locale === 'en' ? 'Retail banking' : 'Ngân hàng bán lẻ');
        await page.goto(`./login?lang=${locale}`);
        await expect(page.getByRole('button', { name: locale === 'en' ? 'Sign in' : 'Đăng nhập', exact: true })).toBeVisible();
        await shot('1-sign-in');

        await mockHost(page, 'rules-author-simulate');
        await mockRuleEngine(page, 'rules-author-simulate', simulationSeed());
        await page.goto('./#/rules');
        await expect(page.getByRole('table').getByText('card-fee')).toBeVisible();
        await shot('2-rules');

        await page.getByRole('table').getByRole('link', { name: 'Card fee', exact: true }).click();
        await page.locator('#version-picker').selectOption('2');
        await expect(page.locator('.dt tbody tr').first()).toBeVisible();
        await shot('3-draft');

        await page.locator('.rs-tabs').getByRole('tab', { name: locale === 'en' ? 'Simulate' : 'Mô phỏng', exact: true }).click();
        await expect(page).toHaveURL(/#\/rules\/card-fee\/2\/simulate$/);
        await shot('4-simulate');
        await runFive(page, locale);
        await expect(page.locator('.simrun__row--changed')).toHaveCount(2);
        await checkScreen(page, viewport, locale, BACK, '.simrun__results tbody tr:nth-child(3)');
        await shot('5-results');
        await axe(page);
      });
    }
  }
}

test('CKP_004_simulation_displays_outcome_changes_safely', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'rules-author-simulate');
  await mockRuleEngine(page, 'rules-author-simulate', simulationSeed());
  const posts: { path: string; key: string | undefined }[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST') posts.push({ path: new URL(request.url()).pathname, key: request.headers()['idempotency-key'] });
  });
  await page.goto('./#/rules/card-fee/2/simulate');
  await runFive(page, 'en');
  await expect(page.getByRole('heading', { name: '2 of 5 results change' })).toBeVisible();
  for (const reference of ['S1', 'S2', 'S3', 'S4', 'S5']) await expect(page.locator('.simrun__results tbody th').getByText(reference, { exact: true })).toBeVisible();
  await expect(page.locator('.simrun__row--changed').first()).toContainText('Gold, mid balance');
  // One read; nothing that writes a decision, so the decision log count cannot change.
  expect(posts).toEqual([{ path: '/api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate-readings', key: undefined }]);
});

test('CKP_004_the_samples_are_keyboard_operable', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'rules-author-simulate');
  await mockRuleEngine(page, 'rules-author-simulate', simulationSeed());
  await page.goto('./#/rules/card-fee/2/simulate');
  await page.getByLabel('S1, Tier', { exact: true }).selectOption('GOLD');
  const run = page.getByRole('button', { name: 'Run simulation', exact: true });
  await expect(run).toBeEnabled();
  await run.focus();
  expect(await run.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { level: 2 })).toBeFocused();
});
