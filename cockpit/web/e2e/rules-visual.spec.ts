import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import type { Scenario } from '../src/mocks/fixtures';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

// Rule screens for the visual check: 1440x900 and 1440x1200, light and dark, English and Vietnamese.
// The screen's primary action must be fully visible without scrolling, at 1440x900 and at 1440x1200.
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

interface Screen {
  name: string;
  scenario: Scenario;
  hash: string;
  primary: Record<Locale, string>;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  ready: (page: Page, locale: Locale) => Promise<void>;
}

const SCREENS: readonly Screen[] = [
  {
    name: 'list',
    scenario: 'rules-author',
    hash: '#/rules',
    primary: { en: 'Import rule set', vi: 'Nhập bộ quy tắc' },
    ready: async (page) => { await expect(page.getByRole('table').getByText('card-fee')).toBeVisible(); },
  },
  {
    name: 'table-try-it',
    scenario: 'rules-author',
    hash: '#/rules/card-fee/1',
    primary: { en: 'Start from this version', vi: 'Bắt đầu từ phiên bản này' },
    prepare: async (page, locale) => {
      await page.getByRole('tab', { name: locale === 'en' ? 'Try it' : 'Chạy thử' }).click();
      await page.locator('.try select').first().selectOption('GOLD');
      await page.locator('.money-input input').first().fill('60000000');
      await page.getByRole('button', { name: locale === 'en' ? 'Run' : 'Chạy' }).click();
    },
    ready: async (page) => { await expect(page.locator('.match-mark')).toBeVisible(); },
  },
  {
    name: 'editor-error',
    scenario: 'rules-author',
    hash: '#/rules/card-fee/1',
    primary: { en: 'Save draft', vi: 'Lưu bản nháp' },
    prepare: async (page, locale) => {
      await page.getByRole('button', { name: locale === 'en' ? 'Start from this version' : 'Bắt đầu từ phiên bản này' }).click();
      await page.getByRole('button', { name: locale === 'en' ? 'Actions for row R2' : 'Thao tác cho dòng R2' }).click();
      await page.getByRole('menuitem', { name: locale === 'en' ? 'Duplicate row R2' : 'Nhân bản dòng R2' }).click();
      await page.getByLabel(locale === 'en' ? 'Row R6, Balance' : 'Dòng R6, Số dư', { exact: true }).fill('');
      await page.keyboard.press('Enter');
    },
    ready: async (page) => { await expect(page.locator('.cell-edit--bad')).toBeVisible(); },
  },
  {
    name: 'requester-pending',
    scenario: 'rules-author',
    hash: '#/rules/card-fee/2',
    primary: { en: 'Approve and publish', vi: 'Phê duyệt và xuất bản' },
    // Why Approve is off is a tooltip on the disabled button (the sentence is read out by a screen reader).
    ready: async (page) => { await expect(page.locator('.decision .has-tip[data-tip]')).toBeVisible(); await expect(page.locator('.decision .btn--ok')).toBeDisabled(); },
  },
  {
    name: 'approver-changes',
    scenario: 'rules-approver',
    hash: '#/rules/card-fee/2',
    primary: { en: 'Approve and publish', vi: 'Phê duyệt và xuất bản' },
    ready: async (page) => { await expect(page.locator('.change-mark').first()).toBeVisible(); },
  },
  {
    name: 'history',
    scenario: 'rules-author',
    hash: '#/rules/card-fee/1',
    primary: { en: 'Start from this version', vi: 'Bắt đầu từ phiên bản này' },
    prepare: async (page, locale) => { await page.getByRole('tab', { name: locale === 'en' ? 'History' : 'Lịch sử' }).click(); },
    ready: async (page) => { await expect(page.locator('.history__entry').nth(2)).toBeVisible(); },
  },
  {
    name: 'source',
    scenario: 'rules-author',
    hash: '#/rules/card-fee/1/source',
    primary: { en: 'Start from this version', vi: 'Bắt đầu từ phiên bản này' },
    ready: async (page) => { await expect(page.locator('.source__text')).toBeVisible(); },
  },
];

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `rules-${screen.name}-${viewport.width}x${viewport.height}-${theme}-${locale}.png`;
        test(`CKP_030_032_visual_${screen.name}_${viewport.width}x${viewport.height}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockHost(page, screen.scenario);
          await mockRuleEngine(page, screen.scenario);
          await page.goto(`./${screen.hash}`);
          await expect(page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await screen.prepare?.(page, locale);
          await screen.ready(page, locale);
          const primary = await page.getByRole('button', { name: screen.primary[locale], exact: true }).first().boundingBox();
          expect(primary, 'primary action is rendered').not.toBeNull();
          // Visible without scrolling: at 1440x900 that is the laptop fold (ux-design section 8).
          expect((primary?.y ?? 0) + (primary?.height ?? 0)).toBeLessThanOrEqual(viewport.height);
          expect(primary?.height ?? 0).toBeGreaterThanOrEqual(44);
          // No raw expressions in read views and no spec ids anywhere.
          await expect(page.locator('main')).not.toContainText(/CK-F-|CKP-0|PD-0/);
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
          expect(violations).toEqual([]);
          // WCAG 2.2 AA through axe-core, in both themes and both languages.
          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
          expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
        });
      }
    }
  }
}
