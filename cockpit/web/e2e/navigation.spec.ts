import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { demoCatalogue } from '../src/mocks/demoCatalogue';
import { meMinh, metaRulesOnly, scenarioPermissions, scenarioUser, type Scenario } from '../src/mocks/fixtures';
import { createFakeEngine } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/** The Rule Engine endpoints answered from the fuller demo catalogue, as the scenario's user. */
async function mockCatalogue(page: Page, scenario: Scenario): Promise<void> {
  const engine = createFakeEngine(demoCatalogue());
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(scenario), permissions: scenarioPermissions(scenario) });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
}

/*
 * The menu, Home and the area landings at the two laptop sizes, in both themes and both languages.
 * Screenshots go to e2e/screenshots/ (attached to the pull request). Each run also checks the fold
 * (rail, title and primary action visible at 1440x900) and runs axe-core.
 */
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;

interface Screen {
  name: string;
  scenario: Scenario;
  hash: string;
  collapsed?: boolean;
  /** Adds the Process Engine module and processes:read for the approver. */
  processes?: boolean;
  /** The title to wait for, per language. */
  title: Record<(typeof LOCALES)[number], string>;
  /** The page's primary action, when it has one. */
  action?: Record<(typeof LOCALES)[number], string>;
}

const SCREENS: readonly Screen[] = [
  { name: 'home-approver', scenario: 'ready-approver', hash: '#/home', title: { en: 'Home', vi: 'Trang chủ' }, action: { en: 'Review 2 waiting', vi: 'Xem xét 2 yêu cầu' } },
  { name: 'home-author', scenario: 'ready', hash: '#/home', title: { en: 'Home', vi: 'Trang chủ' }, action: { en: 'Open rule sets', vi: 'Mở bộ quy tắc' } },
  { name: 'approvals-approver', scenario: 'ready-approver', hash: '#/approvals', title: { en: 'Approvals', vi: 'Phê duyệt' } },
  { name: 'menu-collapsed', scenario: 'ready-approver', hash: '#/home', collapsed: true, title: { en: 'Home', vi: 'Trang chủ' }, action: { en: 'Review 2 waiting', vi: 'Xem xét 2 yêu cầu' } },
  { name: 'rules', scenario: 'ready-approver', hash: '#/rules', title: { en: 'Rules', vi: 'Quy tắc' } },
  { name: 'decisions', scenario: 'ready-approver', hash: '#/decisions', processes: true, title: { en: 'Decisions Preview', vi: 'Quyết định Xem trước' } },
  { name: 'processes', scenario: 'ready-approver', hash: '#/processes', processes: true, title: { en: 'Processes Preview', vi: 'Quy trình Xem trước' } },
];

async function openScreen(page: Page, screen: Screen, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: 'en' | 'vi') {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  if (screen.collapsed) {
    await page.addInitScript(() => {
      window.localStorage.setItem('horus.cockpit.menu', 'collapsed');
    });
  }
  await mockHost(page, screen.scenario);
  await mockCatalogue(page, screen.scenario);
  if (screen.processes) {
    await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: { ...metaRulesOnly, modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] } }));
    await page.route('**/api/bpm/me', (route) => route.fulfill({ json: { ...meMinh, permissions: [...meMinh.permissions, 'processes:read'] } }));
  }
  await page.goto(`./${screen.hash}`);
  await expect(page.getByRole('heading', { level: 1, name: screen.title[locale] })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
  // Wait until every card has its answer: no loading line left.
  await expect(page.getByRole('status').filter({ hasText: locale === 'en' ? 'Loading…' : 'Đang tải…' })).toHaveCount(0);
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
        const file = `nav-${screen.name}-${viewport.width}x${viewport.height}-${theme}-${locale}.png`;
        test(`TR_UI_03_visual_${screen.name}_${viewport.width}x${viewport.height}_${theme}_${locale}`, async ({ page }) => {
          await openScreen(page, screen, viewport, theme, locale);

          // At 1440x900 the menu, the title and the primary action are on screen without scrolling.
          const rail = page.getByRole('navigation', { name: locale === 'en' ? 'Cockpit menu' : 'Menu Cockpit' });
          await expect(rail).toBeVisible();
          expect(await bottomOf(page.getByRole('heading', { level: 1 }))).toBeLessThanOrEqual(viewport.height);
          if (screen.action) expect(await bottomOf(page.getByRole('link', { name: screen.action[locale] }))).toBeLessThanOrEqual(viewport.height);
          // No sideways scrolling at laptop width, in either language.
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

test('TR_UI_03_every_menu_item_and_the_primary_action_are_reachable_with_tab_and_show_a_focus_ring', async ({ page }) => {
  await openScreen(page, SCREENS[0] as Screen, { width: 1440, height: 900 }, 'light', 'en');
  const wanted = ['Home', 'Rules', 'Approvals', 'Decisions · Preview', 'Collapse menu', 'Review 2 waiting'];
  const reached: string[] = [];
  for (let step = 0; step < 30 && reached.length < wanted.length; step += 1) {
    await page.keyboard.press('Tab');
    const name = await page.evaluate(() => {
      const element = document.activeElement;
      if (!element) return '';
      const text = element.querySelector('.navrail__title')?.textContent ?? element.textContent;
      return element.getAttribute('aria-label') ?? text.replace(/\s+/g, ' ').trim();
    });
    if (wanted.includes(name) && !reached.includes(name)) {
      reached.push(name);
      const outline = await page.evaluate(() => {
        const element = document.activeElement;
        return element ? getComputedStyle(element).outlineStyle : 'none';
      });
      expect(outline, name).not.toBe('none');
    }
  }
  expect(reached.sort()).toEqual([...wanted].sort());
});

test('TR_UI_08_moving_through_every_menu_item_shows_a_title_never_a_blank_page', async ({ page }) => {
  await openScreen(page, SCREENS[5] as Screen, { width: 1440, height: 900 }, 'light', 'en');
  for (const [item, title] of [['Home', 'Home'], ['Rules', 'Rules'], ['Approvals', 'Approvals'], ['Decisions · Preview', 'Decisions Preview'], ['Processes · Preview', 'Processes Preview']] as const) {
    await page.getByRole('link', { name: item, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
    await expect(page.getByRole('link', { name: item, exact: true })).toHaveAttribute('aria-current', 'page');
  }
});
