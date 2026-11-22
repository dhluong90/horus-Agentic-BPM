import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { Scenario } from '../src/mocks/fixtures';
import { draftSeed } from '../src/mocks/ruleEngine';
import { routeHostSignIn } from './hostSignIn';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * Discard a draft (CKP-081, #444) at 1440x900 and 1440x1200, light and dark, English and Vietnamese: the draft with
 * its Discard button, the confirmation, a refusal and the screen after the discard. Screenshots go to
 * DISCARD_SHOTS_DIR, or e2e/screenshots/.
 */

const SHOTS = process.env['DISCARD_SHOTS_DIR'] ?? 'e2e/screenshots';
const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

const T = {
  discard: { en: 'Discard draft', vi: 'Hủy bỏ bản nháp' },
  reason: { en: 'Reason (optional)', vi: 'Lý do (không bắt buộc)' },
} as const;

const dialog = (page: Page) => page.getByRole('dialog');
const head = (page: Page) => page.locator('.rs-head');

interface Screen {
  name: string;
  scenario: Scenario;
  prepare?: (page: Page, l: Locale) => Promise<void>;
  focal: (page: Page, l: Locale) => Locator;
}

const SCREENS: readonly Screen[] = [
  { name: 'draft-with-discard', scenario: 'rules-author', focal: (page, l) => head(page).getByRole('button', { name: T.discard[l] }) },
  {
    name: 'confirm', scenario: 'rules-author',
    prepare: async (page, l) => {
      await head(page).getByRole('button', { name: T.discard[l] }).click();
      await dialog(page).getByLabel(T.reason[l]).fill(l === 'en' ? 'Started from the wrong version' : 'Bắt đầu từ nhầm phiên bản');
    },
    focal: (page, l) => dialog(page).getByRole('button', { name: T.discard[l] }),
  },
  {
    name: 'refused', scenario: 'rules-author',
    prepare: async (page, l) => {
      await page.route('**/api/bpm/rules/v1/rule-sets/card-fee/versions/2:discard', (route) => route.fulfill({
        status: 409, contentType: 'application/problem+json',
        body: JSON.stringify({ type: 'about:blank', title: 'Version conflict', status: 409, code: 'VERSION_CONFLICT', correlationId: '01JD6D1SCARDC0NF11CT00000E' }),
      }));
      await head(page).getByRole('button', { name: T.discard[l] }).click();
      await dialog(page).getByRole('button', { name: T.discard[l] }).click();
      await expect(dialog(page).getByRole('alert')).toBeVisible();
    },
    focal: (page) => dialog(page).getByRole('alert'),
  },
  {
    name: 'discarded', scenario: 'rules-author',
    prepare: async (page, l) => {
      await head(page).getByRole('button', { name: T.discard[l] }).click();
      await dialog(page).getByRole('button', { name: T.discard[l] }).click();
      await expect(page.locator('.toast')).toBeVisible();
      await expect(page).toHaveURL(/#\/rules\/card-fee$/);
    },
    focal: (page) => page.locator('.toast'),
  },
  { name: 'approver-no-discard', scenario: 'rules-approver', focal: (page) => page.locator('#rule-set-title') },
];

async function openScreen(page: Page, screen: Screen, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: Locale) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  await mockHost(page, screen.scenario);
  const engine = await mockRuleEngine(page, screen.scenario, draftSeed());
  await page.goto('./#/rules/card-fee/2');
  await expect(page.locator('#rule-set-title')).toBeVisible();
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
        test(`TR_UI_03_visual_discard_${screen.name}_${size}_${theme}_${locale}`, async ({ page }) => {
          await openScreen(page, screen, viewport, theme, locale);
          await expectAboveFold(screen.focal(page, locale), viewport.height);
          if (screen.name === 'approver-no-discard') await expect(head(page).getByRole('button', { name: T.discard[locale] })).toHaveCount(0);
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
          await page.screenshot({ path: `${SHOTS}/discard-${screen.name}-${size}-${theme}-${locale}.png`, fullPage: false });
        });
      }
    }
  }
}

for (const screen of SCREENS) {
  for (const theme of THEMES) {
    test(`TR_UI_03_axe_no_serious_violations_discard_${screen.name}_${theme}`, async ({ page }) => {
      await openScreen(page, screen, { width: 1440, height: 900 }, theme, 'vi');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
      expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
    });
  }
}

test('CKP_081_discard_is_confirmed_by_keyboard_and_escape_closes_the_dialog_without_a_request', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const requests: string[] = [];
  page.on('request', (request) => { if (request.method() === 'POST') requests.push(new URL(request.url()).pathname); });
  await openScreen(page, SCREENS[0] as Screen, { width: 1440, height: 900 }, 'light', 'en');
  const open = head(page).getByRole('button', { name: 'Discard draft' });
  await open.focus();
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog(page)).toHaveCount(0);
  await expect(open).toBeFocused();
  expect(requests.filter((path) => path.endsWith(':discard'))).toEqual([]);
  await page.keyboard.press('Enter');
  await dialog(page).getByRole('button', { name: 'Discard draft' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Version 2 draft discarded.')).toBeVisible();
  expect(requests.filter((path) => path.endsWith(':discard'))).toEqual(['/api/bpm/rules/v1/rule-sets/card-fee/versions/2:discard']);
});

for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      const size = `${String(viewport.width)}x${String(viewport.height)}`;
      test(`TR_UI_03_journey_sign_in_to_discard_a_draft_${size}_${theme}_${locale}`, async ({ page }) => {
        const shot = (step: string) => page.screenshot({ path: `${SHOTS}/discard-journey-${step}-${size}-${theme}-${locale}.png`, fullPage: false });
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await rememberLocale(page, locale);
        await routeHostSignIn(page, locale === 'en' ? 'Retail banking' : 'Ngân hàng bán lẻ');
        await page.goto(`./login?lang=${locale}`);
        await expectAboveFold(page.getByRole('button', { name: locale === 'en' ? 'Sign in' : 'Đăng nhập', exact: true }), viewport.height);
        await shot('1-sign-in');
        await mockHost(page, 'rules-author');
        await mockRuleEngine(page, 'rules-author', draftSeed());
        await page.goto('./#/rules');
        await expect(page.getByRole('table').getByText('card-fee')).toBeVisible();
        await shot('2-rules');
        await page.getByRole('table').getByText('card-fee').click();
        await page.locator('#version-picker').selectOption('2');
        const discard = head(page).getByRole('button', { name: T.discard[locale] });
        await expectAboveFold(discard, viewport.height);
        await shot('3-draft');
        await discard.click();
        await dialog(page).getByRole('button', { name: T.discard[locale] }).click();
        await expect(page.locator('.toast')).toBeVisible();
        await expect(page).toHaveURL(/#\/rules\/card-fee$/);
        await shot('4-discarded');
      });
    }
  }
}
