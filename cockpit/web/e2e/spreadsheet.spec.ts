// The spreadsheet round trip of a rule table (CKP-038 to CKP-040): export, the confirm dialog "Based on version N",
// the engine's refusals per row and column, a conflict that keeps the file, and the draft that opens. One shot per
// state at 1440x900, rotating light and dark and English and Vietnamese, two at 1440x1200; axe-core on each state,
// the primary action above the fold, and the keyboard path.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { en } from '../src/i18n/en';
import { vi } from '../src/i18n/vi';
import type { Scenario } from '../src/mocks/fixtures';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
const WORDS = { en, vi } as const;
const SHOTS = process.env['SHEET_SCREENSHOTS'] ?? 'e2e/screenshots';

const HEADER = 'Rule,tier,balance,txCount,fee,waived,Description';
const ROWS = ['R1,PLATINUM,-,-,0 VND,true,', 'R2,GOLD,>= 50000000 VND,-,0 VND,true,', 'R3,GOLD,-,-,30000 VND,false,', 'R4,-,-,>= 20,15000 VND,false,', 'R5,-,-,-,50000 VND,false,'];
const csv = (lines: readonly string[]) => Buffer.from(`\uFEFF${lines.join('\r\n')}\r\n`, 'utf-8');
const GOOD = csv([HEADER, ROWS[0] ?? '', ROWS[1] ?? '', 'R3,GOLD,-,-,32000 VND,false,', ROWS[3] ?? '', ROWS[4] ?? '']);
const DAMAGED = csv([
  HEADER, ROWS[0] ?? '', 'R2,GOLD,>= 51234567,-,0 VND,true,', ROWS[2] ?? '', 'R4,-,5E+07 VND,>= 20,15000 VND,false,', 'R5,-,"50,000,000 VND",-,50000 VND,false,',
]);
const ADDED_COLUMN = csv([HEADER.replace(',waived', ',segment'), ...ROWS.map((r) => r.replace(',true,', ',x,').replace(',false,', ',x,'))]);

const pick = async (page: Page, buffer: Buffer) => {
  await page.locator('.rs-head input[type=file]').setInputFiles({ name: 'card-fee-v1.csv', mimeType: 'text/csv', buffer });
};

interface State {
  name: string;
  scenario: Scenario;
  /** The screen's one primary action, checked above the fold. */
  primary: keyof typeof en;
  /** Whether the primary action is on the page or inside the dialog. */
  prepare: (page: Page, locale: Locale) => Promise<void>;
  ready: (page: Page, locale: Locale) => Promise<void>;
}

const word = (locale: Locale, key: keyof typeof en) => WORDS[locale][key];

const STATES: readonly State[] = [
  {
    name: 'export-menu', scenario: 'rules-author', primary: 'editor.newDraft',
    prepare: async (page, locale) => { await page.getByRole('button', { name: word(locale, 'export.label'), exact: true }).click(); },
    ready: async (page, locale) => { await expect(page.getByRole('menuitem', { name: word(locale, 'export.csv') })).toBeVisible(); },
  },
  {
    name: 'confirm', scenario: 'rules-author', primary: 'sheet.confirm',
    prepare: async (page) => { await pick(page, GOOD); },
    ready: async (page, locale) => { await expect(page.getByRole('dialog').getByText(word(locale, 'sheet.based').replace('{version}', '1'))).toBeVisible(); },
  },
  {
    name: 'refused', scenario: 'rules-author', primary: 'sheet.another',
    prepare: async (page, locale) => { await pick(page, DAMAGED); await page.getByRole('button', { name: word(locale, 'sheet.confirm') }).click(); },
    ready: async (page, locale) => { await expect(page.getByRole('dialog').getByText(word(locale, 'import.refused'))).toBeVisible(); },
  },
  {
    name: 'columns', scenario: 'rules-author', primary: 'sheet.another',
    prepare: async (page, locale) => { await pick(page, ADDED_COLUMN); await page.getByRole('button', { name: word(locale, 'sheet.confirm') }).click(); },
    ready: async (page, locale) => { await expect(page.getByRole('dialog').getByText(word(locale, 'csv.CSV_COLUMN_UNKNOWN'))).toBeVisible(); },
  },
  {
    name: 'conflict', scenario: 'rules-author', primary: 'sheet.retry',
    prepare: async (page, locale) => {
      await page.route('**/api/bpm/rules/v1/rule-sets/card-fee/versions?base=1', async (route) => {
        await route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ type: 'x', title: 'Conflict', status: 409, code: 'VERSION_CONFLICT', correlationId: '01JCONFLICT0000000000000000' }) });
      });
      await pick(page, GOOD);
      await page.getByRole('button', { name: word(locale, 'sheet.confirm') }).click();
    },
    ready: async (page, locale) => { await expect(page.getByRole('dialog').getByText(word(locale, 'sheet.conflict'))).toBeVisible(); },
  },
  {
    name: 'created', scenario: 'rules-author', primary: 'dock.requestPublish',
    prepare: async (page, locale) => { await pick(page, GOOD); await page.getByRole('button', { name: word(locale, 'sheet.confirm') }).click(); },
    ready: async (page, locale) => { await expect(page.getByText(word(locale, 'ruleSet.imported').replace('{version}', '3'))).toBeVisible(); },
  },
];

/** Which states are also shot at 1440x1200: the longest dialog and the draft that opens. */
const TALL = new Set(['refused', 'created']);

STATES.forEach((state, index) => {
  const theme = THEMES[index % 2] ?? 'light';
  const locale = LOCALES[Math.floor(index / 2) % 2] ?? 'en';
  const sizes = [{ width: 1440, height: 900 }, ...(TALL.has(state.name) ? [{ width: 1440, height: 1200 }] : [])];
  for (const viewport of sizes) {
    // The tall shot is taken in the other theme and language, so every combination is seen at least once.
    const tall = viewport.height === 1200;
    const t = tall ? THEMES[(index + 1) % 2] ?? theme : theme;
    const l = tall ? LOCALES[(Math.floor(index / 2) + 1) % 2] ?? locale : locale;
    test(`CKP_039_visual_${state.name}_${String(viewport.width)}x${String(viewport.height)}_${t}_${l}`, async ({ page }) => {
      const violations = cspViolations(page);
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: t, reducedMotion: 'reduce' });
      await rememberLocale(page, l);
      await mockHost(page, state.scenario);
      await mockRuleEngine(page, state.scenario);
      await page.goto('./#/rules/card-fee/1');
      await expect(page.getByTestId('header-user')).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', t);
      await expect(page.getByRole('button', { name: word(l, 'ruleSet.import'), exact: true })).toBeVisible();
      await state.prepare(page, l);
      await state.ready(page, l);
      // The primary action is fully visible without scrolling, at 1440x900 and at 1440x1200.
      const primary = await page.getByRole('button', { name: word(l, state.primary), exact: true }).first().boundingBox();
      expect(primary, 'primary action is rendered').not.toBeNull();
      expect((primary?.y ?? 0) + (primary?.height ?? 0)).toBeLessThanOrEqual(viewport.height);
      // No spec ids, no raw JSON and no ids in the main text.
      await expect(page.locator('body')).not.toContainText(/CKP-0|PD-0|CK-F-|\{"/);
      // A refusal never shows a cell's own value.
      await expect(page.locator('body')).not.toContainText('51234567');
      await page.evaluate(async () => { await document.fonts.ready; });
      await page.screenshot({ path: `${SHOTS}/sheet-${state.name}-${String(viewport.width)}x${String(viewport.height)}-${t}-${l}.png`, fullPage: false });
      expect(violations).toEqual([]);
      const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
    });
  }
});

test('CKP_039_keyboard_path_confirm_with_the_keyboard_and_escape_sends_nothing', async ({ page }) => {
  await mockHost(page, 'rules-author');
  const posts: string[] = [];
  page.on('request', (r) => { if (r.method() === 'POST' && r.url().includes('/versions?base=')) posts.push(r.url()); });
  await mockRuleEngine(page, 'rules-author');
  await page.goto('./#/rules/card-fee/1');
  await expect(page.getByRole('button', { name: en['ruleSet.import'], exact: true })).toBeVisible();
  await pick(page, GOOD);
  const dialog = page.getByRole('dialog', { name: en['sheet.title'] });
  await expect(dialog).toBeVisible();
  // Escape closes it with nothing sent; the focus returns to the page.
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  expect(posts).toEqual([]);
  await pick(page, GOOD);
  await expect(dialog).toBeVisible();
  // Focus stays inside the dialog: Tab reaches the primary button, which shows a visible focus ring.
  await page.keyboard.press('Tab');
  const confirm = dialog.getByRole('button', { name: en['sheet.confirm'] });
  await expect(confirm).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Draft version 3 created')).toBeVisible();
  expect(posts).toHaveLength(1);
});

test('CKP_039_a_reader_is_offered_export_and_not_import', async ({ page }) => {
  await mockHost(page, 'rules-reader');
  await mockRuleEngine(page, 'rules-reader');
  await page.goto('./#/rules/card-fee/1');
  await expect(page.getByRole('button', { name: en['export.label'], exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: en['ruleSet.import'], exact: true })).toHaveCount(0);
});
