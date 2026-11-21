import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import type { Scenario } from '../src/mocks/fixtures';
import { readingsSeed } from '../src/mocks/ruleEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

// Plain-language readings of rule rows in every state, with the screens leading in: 1440x900 and 1440x1200, light
// and dark, English and Vietnamese. The screen's primary action, when it has one, is visible without scrolling.
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
type Words = Record<Locale, string>;

const RULES = '**/api/bpm/rules/v1/rule-sets';
const problem = (status: number, code: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ type: 'about:blank', title: 'Refused', status, code, detail: 'refused', correlationId: '01JFAKE0000000000000000042' }),
});

const tab = (name: Words) => async (page: Page, locale: Locale) => { await page.getByRole('tab', { name: name[locale], exact: true }).click(); };
const READINGS: Words = { en: 'Readings', vi: 'Diễn giải' };
const SIMULATE: Words = { en: 'Simulate', vi: 'Mô phỏng' };
const NEW_DRAFT: Words = { en: 'New draft', vi: 'Tạo bản nháp' };
const APPROVE: Words = { en: 'Approve and publish', vi: 'Phê duyệt và xuất bản' };

async function simulate(page: Page, locale: Locale, fill: (page: Page) => Promise<void>) {
  await tab(SIMULATE)(page, locale);
  await fill(page);
  await page.locator('.sim__run').click();
}

interface Screen {
  name: string;
  scenario: Scenario;
  hash: string;
  /** The primary action that must be above the fold; undefined where the screen offers none to this user. */
  primary?: Words;
  /** A result that must also be visible without scrolling. */
  result?: string;
  route?: (page: Page) => Promise<void>;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  ready: (page: Page) => Promise<void>;
}

const SCREENS: readonly Screen[] = [
  { name: 'lead-rules-list', scenario: 'rules-author', hash: '#/rules', primary: { en: 'Import rule set', vi: 'Nhập bộ quy tắc' },
    ready: async (page) => { await expect(page.getByRole('table').getByText('card-fee')).toBeVisible(); } },
  { name: 'lead-rule-set-table', scenario: 'rules-author', hash: '#/rules/card-fee/1', primary: NEW_DRAFT,
    ready: async (page) => { await expect(page.locator('.dt tbody tr').first()).toBeVisible(); } },
  { name: 'version-sentence', scenario: 'rules-author', hash: '#/rules/card-fee/1', primary: NEW_DRAFT, prepare: tab(READINGS),
    ready: async (page) => { await expect(page.locator('.reading').nth(4)).toBeVisible(); } },
  { name: 'version-short', scenario: 'rules-author', hash: '#/rules/card-fee/1/readings', primary: NEW_DRAFT,
    prepare: async (page, locale) => { await page.getByRole('button', { name: locale === 'en' ? 'Short' : 'Rút gọn', exact: true }).click(); },
    ready: async (page) => { await expect(page.locator('.reading__text').first()).toContainText('->'); } },
  { name: 'version-loading', scenario: 'rules-author', hash: '#/rules/card-fee/1/readings', primary: NEW_DRAFT,
    route: async (page) => { await page.route(`${RULES}/card-fee/versions/1/readings`, () => { /* never answers: the skeleton stays */ }); },
    ready: async (page) => { await expect(page.locator('.readings .skeleton')).toBeVisible(); } },
  { name: 'version-empty', scenario: 'rules-author', hash: '#/rules/new-fees/1/readings',
    ready: async (page) => { await expect(page.locator('.reading-empty')).toBeVisible(); } },
  { name: 'version-error', scenario: 'rules-author', hash: '#/rules/card-fee/1/readings', primary: NEW_DRAFT,
    route: async (page) => { await page.route(`${RULES}/card-fee/versions/1/readings`, (route) => route.fulfill(problem(500, 'INTERNAL'))); },
    ready: async (page) => { await expect(page.locator('.readings .alert--error')).toBeVisible({ timeout: 15_000 }); } },
  { name: 'version-forbidden', scenario: 'rules-author', hash: '#/rules/card-fee/1/readings', primary: NEW_DRAFT,
    route: async (page) => { await page.route(`${RULES}/card-fee/versions/1/readings`, (route) => route.fulfill(problem(403, 'PERMISSION_DENIED'))); },
    ready: async (page) => { await expect(page.locator('.reading-empty')).toBeVisible(); } },
  { name: 'version-too-large', scenario: 'rules-author', hash: '#/rules/branch-fees/1/readings', primary: NEW_DRAFT,
    ready: async (page) => { await expect(page.locator('.reading-limit')).toBeVisible(); } },
  { name: 'version-masked', scenario: 'rules-author', hash: '#/rules/kyc-routing/1/readings', primary: NEW_DRAFT,
    ready: async (page) => { await expect(page.locator('.reading__note').first()).toBeVisible(); } },
  { name: 'diff-readings', scenario: 'rules-approver', hash: '#/rules/card-fee/2/changes', primary: APPROVE,
    prepare: async (page, locale) => { await page.locator('.changes__switch').getByRole('button', { name: READINGS[locale], exact: true }).click(); },
    ready: async (page) => { await expect(page.locator('.reading del').first()).toBeVisible(); } },
  { name: 'diff-too-large', scenario: 'rules-approver', hash: '#/rules/card-fee/2/changes', primary: APPROVE,
    route: async (page) => { await page.route(`${RULES}/card-fee/versions/2/diff/readings?base=1`, (route) => route.fulfill(problem(422, 'READINGS_TOO_LARGE'))); },
    prepare: async (page, locale) => { await page.locator('.changes__switch').getByRole('button', { name: READINGS[locale], exact: true }).click(); },
    ready: async (page) => { await expect(page.locator('.reading-limit')).toBeVisible(); } },
  { name: 'simulate-result', scenario: 'rules-approver', hash: '#/rules/card-fee/2/table', primary: APPROVE, result: '.sim__result',
    prepare: (page, locale) => simulate(page, locale, async (p) => { await p.locator('.sim select').first().selectOption('GOLD'); }),
    ready: async (page) => { await expect(page.locator('.sim__result')).toBeVisible(); } },
  { name: 'simulate-disabled', scenario: 'rules-author', hash: '#/rules/card-fee/2/table', prepare: tab(SIMULATE),
    ready: async (page) => { await expect(page.locator('.sim__reason')).toBeVisible(); await expect(page.locator('.sim__run')).toBeDisabled(); } },
  { name: 'simulate-masked', scenario: 'rules-approver', hash: '#/rules/kyc-routing/1/table', result: '.sim__result',
    prepare: (page, locale) => simulate(page, locale, async (p) => { await p.locator('.sim input').first().fill('SEG-4471'); }),
    ready: async (page) => { await expect(page.locator('.sim__result .reading__note')).toBeVisible(); } },
];

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `readings-${screen.name}-${viewport.width}x${viewport.height}-${theme}-${locale}.png`;
        test(`CKP_076_079_visual_${screen.name}_${viewport.width}x${viewport.height}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockHost(page, screen.scenario);
          await mockRuleEngine(page, screen.scenario, readingsSeed());
          await screen.route?.(page);
          await page.goto(`./${screen.hash}`);
          await expect(page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await screen.prepare?.(page, locale);
          await screen.ready(page);
          if (screen.primary !== undefined) {
            const primary = await page.getByRole('button', { name: screen.primary[locale], exact: true }).last().boundingBox();
            expect(primary, 'primary action is rendered').not.toBeNull();
            expect((primary?.y ?? 0) + (primary?.height ?? 0)).toBeLessThanOrEqual(viewport.height);
          }
          if (screen.result !== undefined) {
            const box = await page.locator(screen.result).boundingBox();
            expect((box?.y ?? 0) + (box?.height ?? 0), 'result is visible without scrolling').toBeLessThanOrEqual(viewport.height);
          }
          // One language per screen, no spec ids, no raw expressions in readings.
          const main = page.locator('main');
          await expect(main).not.toContainText(/CK-F-|CKP-0|PD-0|RLE-0|READINGS_TOO_LARGE/);
          if (locale === 'vi') await expect(main).not.toContainText(/Rule R\d applies|in plain words|Sentence|Short/);
          else await expect(main).not.toContainText(/Quy tắc R\d|Câu đầy đủ|Rút gọn/);
          await expect(main).not.toContainText(/==|'GOLD'/);
          // A masked input's test is never written in a reading (the table itself shows the rule as authored).
          for (const reading of await page.locator('.readings__list, .sim__result').all()) await expect(reading).not.toContainText('SEG-4471');
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
          expect(violations).toEqual([]);
          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
          expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
        });
      }
    }
  }
}

test('CKP_076_the_reading_style_is_remembered_after_a_reload', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'rules-author');
  await mockRuleEngine(page, 'rules-author', readingsSeed());
  await page.goto('./#/rules/card-fee/1/readings');
  await page.getByRole('button', { name: 'Short', exact: true }).click();
  await expect(page.locator('.reading__text').first()).toContainText('->');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Short', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.reading__text').first()).toContainText('->');
});
