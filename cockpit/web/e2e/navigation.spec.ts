import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { demoCatalogue } from '../src/mocks/demoCatalogue';
import { meLan, meMinh, metaRulesOnly } from '../src/mocks/fixtures';
import { createFakeEngine } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/**
 * Display names the host would supply with each version (proposed `createdByName`, `requestedByName`,
 * `approvedByName`; the engine sends logins only today). The cockpit shows them when present.
 */
const DIRECTORY: Readonly<Record<string, string>> = { lan: 'Nguyễn Thị Lan', minh: 'Trần Văn Minh', hoa: 'Phạm Thu Hoa' };

function withNames(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(withNames);
  if (typeof value !== 'object' || value === null) return value;
  const out: Record<string, unknown> = {};
  for (const [key, field] of Object.entries(value)) {
    out[key] = withNames(field);
    if ((key === 'createdBy' || key === 'requestedBy' || key === 'approvedBy') && typeof field === 'string' && DIRECTORY[field] !== undefined) out[`${key}Name`] = DIRECTORY[field];
  }
  return out;
}

/** The Rule Engine endpoints answered from the fuller demo catalogue, as the screen's user. */
async function mockCatalogue(page: Page, user: string, permissions: readonly string[]): Promise<void> {
  const engine = createFakeEngine(demoCatalogue());
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user, permissions });
    const json = answer.headers['Content-Type']?.includes('json') === true && answer.body !== '';
    // 0.1.0 shows logins for other people; set NAMES=1 to preview the proposed *ByName fields.
    const named = json && process.env['NAMES'] === '1';
    await route.fulfill({ status: answer.status, headers: answer.headers, body: named ? JSON.stringify(withNames(JSON.parse(answer.body))) : answer.body });
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

/** One host configuration for every screen (Rule Engine and Process Engine), so the menu never changes. */
const META = { ...metaRulesOnly, modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] };

const USERS = {
  lan: meLan,
  minh: meMinh,
  tuan: { ...meLan, user: { id: 'tuan', displayName: 'Vũ Anh Tuấn' }, permissions: ['rules:evaluate'], roles: ['TELLER'] },
} as const;
type User = keyof typeof USERS;

type PerLocale<T> = Record<(typeof LOCALES)[number], T>;

interface Screen {
  name: string;
  user: User;
  hash: string;
  collapsed?: boolean;
  title: PerLocale<string | RegExp>;
  /** The screen's primary action, which must be visible at 1440x900. */
  action?: PerLocale<string>;
}

const SCREENS: readonly Screen[] = [
  { name: 'home-author', user: 'lan', hash: '#/home', title: { en: /Nguyễn Thị Lan/, vi: /Nguyễn Thị Lan/ }, action: { en: 'Continue editing', vi: 'Tiếp tục chỉnh sửa' } },
  { name: 'home-approver', user: 'minh', hash: '#/home', title: { en: /Trần Văn Minh/, vi: /Trần Văn Minh/ }, action: { en: 'Review', vi: 'Xem xét' } },
  { name: 'home-no-role', user: 'tuan', hash: '#/home', title: { en: /Vũ Anh Tuấn/, vi: /Vũ Anh Tuấn/ } },
  { name: 'rules-author', user: 'lan', hash: '#/rules', title: { en: 'Rules', vi: 'Quy tắc' }, action: { en: 'Import rule set', vi: 'Nhập bộ quy tắc' } },
  { name: 'approvals-approver', user: 'minh', hash: '#/approvals', title: { en: 'Approvals', vi: 'Phê duyệt' }, action: { en: 'Review next', vi: 'Xem xét tiếp' } },
  { name: 'approvals-author', user: 'lan', hash: '#/approvals', title: { en: 'Approvals', vi: 'Phê duyệt' } },
  { name: 'menu-collapsed', user: 'minh', hash: '#/home', collapsed: true, title: { en: /Trần Văn Minh/, vi: /Trần Văn Minh/ }, action: { en: 'Review', vi: 'Xem xét' } },
  { name: 'decisions', user: 'minh', hash: '#/decisions', title: { en: 'Decisions Preview', vi: 'Quyết định Xem trước' } },
  { name: 'processes', user: 'lan', hash: '#/processes', title: { en: 'Processes Preview', vi: 'Quy trình Xem trước' } },
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
  const me = USERS[screen.user];
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: META }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: me }));
  await mockCatalogue(page, me.user.id, me.permissions);
  await page.goto(`./${screen.hash}`);
  await expect(page.getByRole('heading', { level: 1, name: screen.title[locale] })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
  // Wait until every card has its answer: no skeleton left.
  await expect(page.locator('.skeleton')).toHaveCount(0);
}

function primaryAction(page: Page, name: string) {
  return page.getByRole('link', { name, exact: true }).or(page.getByRole('button', { name, exact: true })).first();
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
          if (screen.action) expect(await bottomOf(primaryAction(page, screen.action[locale]))).toBeLessThanOrEqual(viewport.height);
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
  await openScreen(page, SCREENS[1] as Screen, { width: 1440, height: 900 }, 'light', 'en');
  const wanted = ['Home', 'Rules', 'Approvals', 'Decisions', 'Processes', 'Collapse menu', 'Review'];
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
  await openScreen(page, SCREENS[1] as Screen, { width: 1440, height: 900 }, 'light', 'en');
  const items: [RegExp, string | RegExp][] = [[/^Rules$/, 'Rules'], [/^Approvals.*2 to review$/, 'Approvals'], [/^Decisions$/, 'Decisions Preview'], [/^Processes$/, 'Processes Preview'], [/^Home$/, /Trần Văn Minh/]];
  for (const [item, title] of items) {
    await page.getByRole('navigation', { name: 'Cockpit menu' }).getByRole('link', { name: item }).click();
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  }
});
