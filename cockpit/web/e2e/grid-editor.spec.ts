// The grid editor of a draft (CKP-006): each state at 1440x900 and 1440x1200, light and dark, English and
// Vietnamese, with the primary action above the fold, axe-core and the keyboard path.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { en } from '../src/i18n/en';
import { vi } from '../src/i18n/vi';
import type { Scenario } from '../src/mocks/fixtures';
import { demoSeed, draftSeed, type StoredVersion } from '../src/mocks/ruleEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
const WORDS = { en, vi } as const;
const SHOTS = process.env['GRID_SCREENSHOTS'] ?? 'e2e/screenshots';

type Engine = Awaited<ReturnType<typeof mockRuleEngine>>;

/** A cell's spoken name, as the grid gives it ("Row R2, Balance"). */
const cell = (locale: Locale, row: string, column: string) => WORDS[locale]['editor.cellLabel'].replace('{row}', row).replace('{column}', column);

async function editFee(page: Page, locale: Locale) {
  const fee = page.getByLabel(cell(locale, 'R3', 'Fee'), { exact: true });
  await fee.fill('40000');
  await page.keyboard.press('Enter');
  await expect(page.locator('.edit-dock__dot')).toBeVisible();
}

interface State {
  name: string;
  scenario: Scenario;
  seed: () => StoredVersion[];
  hash: string;
  primary: keyof typeof en;
  prepare?: (page: Page, locale: Locale, engine: Engine) => Promise<void>;
  ready: (page: Page, locale: Locale) => Promise<void>;
}

const STATES: readonly State[] = [
  {
    name: 'empty',
    scenario: 'rules-author',
    seed: draftSeed,
    hash: '#/rules/card-fee/2/edit',
    primary: 'editor.save',
    ready: async (page, locale) => { await expect(page.getByText(WORDS[locale]['editor.block.nothing'])).toBeVisible(); },
  },
  {
    name: 'editing',
    scenario: 'rules-author',
    seed: draftSeed,
    hash: '#/rules/card-fee/2/edit',
    primary: 'editor.save',
    prepare: async (page, locale) => {
      await editFee(page, locale);
      await page.getByRole('button', { name: WORDS[locale]['editor.rowActions'].replace('{id}', 'R2') }).click();
      await page.getByRole('menuitem', { name: WORDS[locale]['editor.copy'].replace('{id}', 'R2') }).click();
    },
    ready: async (page) => { await expect(page.locator('.draft-changes__line')).toHaveCount(2); },
  },
  {
    name: 'validation-error',
    scenario: 'rules-author',
    seed: draftSeed,
    hash: '#/rules/card-fee/2/edit',
    primary: 'editor.save',
    prepare: async (page, locale) => {
      await page.getByLabel(cell(locale, 'R2', 'Balance'), { exact: true }).fill('');
      await page.keyboard.press('Enter');
    },
    ready: async (page, locale) => {
      await expect(page.locator('.cell-edit--bad .cell-error')).toBeVisible();
      await expect(page.getByText(WORDS[locale]['editor.block.fix'])).toBeVisible();
    },
  },
  {
    name: 'conflict-409',
    scenario: 'rules-author',
    seed: draftSeed,
    hash: '#/rules/card-fee/2/edit',
    primary: 'editor.save',
    prepare: async (page, locale, engine) => {
      await editFee(page, locale);
      const stored = engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2);
      if (stored) stored.lockVersion = 1;
      await page.getByRole('button', { name: WORDS[locale]['editor.save'], exact: true }).click();
    },
    ready: async (page, locale) => {
      await expect(page.getByText(WORDS[locale]['editor.conflict.title'])).toBeVisible();
      await expect(page.getByRole('button', { name: WORDS[locale]['editor.conflict.load'] })).toBeEnabled();
    },
  },
  {
    name: 'saved',
    scenario: 'rules-author',
    seed: draftSeed,
    hash: '#/rules/card-fee/2/edit',
    primary: 'dock.requestPublish',
    prepare: async (page, locale) => {
      await editFee(page, locale);
      await page.getByRole('button', { name: WORDS[locale]['editor.save'], exact: true }).click();
    },
    ready: async (page, locale) => { await expect(page.getByText(WORDS[locale]['editor.replaced'].replace('{version}', '2'))).toBeVisible(); },
  },
  {
    name: 'reviewer-read-only',
    scenario: 'rules-approver',
    seed: demoSeed,
    hash: '#/rules/card-fee/2/edit',
    primary: 'dock.approve',
    ready: async (page, locale) => { await expect(page.getByText(WORDS[locale]['editor.block.pending'])).toBeVisible(); },
  },
];

for (const state of STATES) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `grid-${state.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`CKP_006_visual_${state.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockHost(page, state.scenario);
          const engine = await mockRuleEngine(page, state.scenario, state.seed());
          await page.goto(`./${state.hash}`);
          await expect(page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await state.prepare?.(page, locale, engine);
          await state.ready(page, locale);
          const primary = await page.getByRole('button', { name: WORDS[locale][state.primary], exact: true }).first().boundingBox();
          expect(primary, 'primary action is rendered').not.toBeNull();
          expect((primary?.y ?? 0) + (primary?.height ?? 0)).toBeLessThanOrEqual(viewport.height);
          // One language, no ids, no codes, no raw JSON or YAML on the screen.
          const main = page.locator('main');
          await expect(main).not.toContainText(/CK-F-|CKP-0|PD-0|SYNTAX_ERROR|VERSION_CONFLICT|schemaVersion|"rules"/);
          if (locale === 'vi') await expect(main).not.toContainText(/Save draft|Discard changes|The cell is not/);
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `${SHOTS}/${file}`, fullPage: false });
          expect(violations).toEqual([]);
          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
          expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
        });
      }
    }
  }
}

test('CKP_006_grid_editor_shows_row_validation_errors', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'rules-author');
  await mockRuleEngine(page, 'rules-author', draftSeed());
  const writes: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/api/bpm/rules/')) writes.push(`${r.method()} ${new URL(r.url()).pathname} ${r.headers()['if-match'] ?? '-'}`); });
  await page.goto('./#/rules/card-fee/2');
  await page.getByRole('button', { name: 'Edit draft' }).click();
  // Keyboard only: into the grid, down to row R2's balance amount, empty it and commit with Enter.
  const amount = page.getByLabel('Row R2, Balance', { exact: true });
  await page.getByLabel('Row R1, Fee', { exact: true }).focus();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByLabel('Row R2, Fee', { exact: true })).toBeFocused();
  await amount.focus();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.press('Backspace');
  await page.keyboard.press('Enter');
  await expect(amount).toHaveAttribute('aria-invalid', 'true');
  await expect(page.locator('td').filter({ has: amount }).locator('.cell-error')).toHaveText(/The cell is not a valid expression\./);
  const save = page.getByRole('button', { name: 'Save draft', exact: true });
  await expect(save).toBeDisabled();
  await expect(page.getByText('Fix the marked cells to save.')).toBeVisible();
  expect(writes.filter((w) => w.startsWith('PUT'))).toEqual([]);
  // Fixed: the check passes and the save goes to the draft with its lock version.
  await amount.fill('40000000');
  await page.keyboard.press('Enter');
  await expect(amount).toHaveAttribute('aria-invalid', 'false');
  await expect(save).toBeEnabled();
  await save.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Draft version 2 saved')).toBeVisible();
  expect(writes.filter((w) => w.startsWith('PUT'))).toEqual(['PUT /api/bpm/rules/v1/rule-sets/card-fee/versions/2 "0"']);
  // The draft is saved; requesting publication is its own step.
  await expect(page.getByRole('button', { name: 'Request publish' })).toBeVisible();
});
