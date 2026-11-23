import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import type { Scenario } from '../src/mocks/fixtures';
import { draftSeed, type StoredVersion } from '../src/mocks/ruleEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

// The nested And/Or/Not condition builder (CK-F-45): an empty builder, a three-level condition and a problem the engine
// placed on one row. Rendered at 1440x900 and 1440x1200, light and dark, English and Vietnamese. Set CONDITION_SHOTS to
// keep the screenshots in another folder.
const SCENARIO: Scenario = 'rules-author-builder';
const OUT = process.env['CONDITION_SHOTS'] ?? 'e2e/screenshots';
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

const NESTED = "(tier == 'GOLD' or balance >= 50000000 VND) and not (txCount < 5 or tier == 'CLASSIC')";
const MONEY_WITHOUT_CURRENCY = "(txCount >= 20 or balance >= 5000000) and tier == 'GOLD'";

function seedWith(tierCell: string): StoredVersion[] {
  return draftSeed().map((stored) => {
    if (stored.ruleSet !== 'card-fee' || stored.version !== 2) return stored;
    const rules = stored.definition.rules.map((rule) => {
      if (rule.id !== 'R1') return rule;
      const rest = Object.fromEntries(Object.entries(rule.when).filter(([name]) => name !== 'tier'));
      return { ...rule, when: tierCell === '' ? rest : { ...rest, tier: tierCell } };
    });
    const definition = { ...stored.definition, rules };
    return { ...stored, definition, source: JSON.stringify(definition, null, 2) };
  });
}

const T = {
  open: { en: 'And/Or condition builder for Row R1, Tier', vi: 'Trình dựng điều kiện Và/Hoặc cho Dòng R1, Hạng' },
  panel: { en: 'Condition of Row R1, Tier', vi: 'Điều kiện của Dòng R1, Hạng' },
  summary: { en: /^Row R1, Tier: /, vi: /^Dòng R1, Hạng: / },
  save: { en: 'Save draft', vi: 'Lưu bản nháp' },
  value: { en: 'Value', vi: 'Giá trị' },
} as const;

interface Screen {
  name: string;
  tierCell: string;
  open: (page: Page, locale: Locale) => Promise<void>;
}

const SCREENS: readonly Screen[] = [
  {
    name: 'empty',
    tierCell: '',
    open: async (page, locale) => { await page.getByRole('button', { name: T.open[locale] }).click(); },
  },
  {
    name: 'nested',
    tierCell: NESTED,
    open: async (page, locale) => { await page.getByRole('button', { name: T.summary[locale] }).click(); },
  },
  {
    name: 'error',
    tierCell: MONEY_WITHOUT_CURRENCY,
    open: async (page, locale) => {
      await page.getByRole('button', { name: T.summary[locale] }).click();
      const panel = page.getByRole('region', { name: T.panel[locale] });
      await panel.getByRole('textbox', { name: T.value[locale] }).first().fill('30');
      await page.keyboard.press('Enter');
      await expect(panel.locator('.cb-leaf--bad')).toBeVisible();
    },
  },
];

for (const viewport of [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }]) {
  for (const locale of LOCALES) {
    for (const screen of SCREENS) {
      test(`CK_F_45_visual_${screen.name}_${viewport.width}x${viewport.height}_light_${locale}`, async ({ page }) => {
        const violations = cspViolations(page);
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
        await rememberLocale(page, locale);
        await mockHost(page, SCENARIO);
        await mockRuleEngine(page, SCENARIO, seedWith(screen.tierCell));
        await page.goto('./#/rules/card-fee/2/edit');
        await expect(page.getByTestId('header-user')).toBeVisible();
        await screen.open(page, locale);
        const panel = page.getByRole('region', { name: T.panel[locale] });
        await expect(panel).toBeVisible();
        // The primary action of the screen is visible without scrolling.
        const save = await page.getByRole('button', { name: T.save[locale], exact: true }).first().boundingBox();
        expect(save, 'primary action is rendered').not.toBeNull();
        expect((save?.y ?? 0) + (save?.height ?? 0)).toBeLessThanOrEqual(viewport.height);
        // No raw expression outside Details, and no spec ids.
        await expect(panel.locator('.cb-reading')).not.toContainText(/==|!=|>=|<=|txCount/);
        await expect(page.locator('main')).not.toContainText(/CK-F-|CKP-0|PD-0/);
        await page.screenshot({ path: `${OUT}/${screen.name}-${viewport.width}x${viewport.height}-light-${locale}.png`, fullPage: false });
        expect(violations).toEqual([]);
        const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
        expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
      });
    }
  }
}

test('CK_F_45_visual_nested_1440x900_dark_en', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
  await rememberLocale(page, 'en');
  await mockHost(page, SCENARIO);
  await mockRuleEngine(page, SCENARIO, seedWith(NESTED));
  await page.goto('./#/rules/card-fee/2/edit');
  await page.getByRole('button', { name: T.summary.en }).click();
  await expect(page.getByRole('region', { name: T.panel.en })).toBeVisible();
  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
  await page.screenshot({ path: `${OUT}/nested-1440x900-dark-en.png`, fullPage: false });
});
