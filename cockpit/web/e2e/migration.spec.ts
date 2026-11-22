import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { scenarioPermissions, scenarioUser, type Scenario } from '../src/mocks/fixtures';
import { MIGRATION_PLAN_ID } from '../src/mocks/migrationDemo';
import type { MigrationSeed } from '../src/mocks/migrationEngine';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * Planning and running a migration (CKP-027, CKP-056 to CKP-058), in the order of the journey: the plan with its
 * check, the proposal with a reason, the second person's approval, the run and the cases not moved. Every state is
 * checked at 1440x900 and 1440x1200, light and dark, English and Vietnamese: the focal element and the one primary
 * action are above the fold, no sideways scrolling, axe-core finds nothing, and no code, id or raw JSON shows.
 * Screenshots go to MIGRATION_SCREENSHOTS (default e2e/screenshots/, which git ignores): one per state at 1440x900,
 * rotating theme and language, and two at 1440x1200.
 */

const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
const SHOTS = process.env['MIGRATION_SCREENSHOTS'] ?? 'e2e/screenshots';
const NOW = new Date('2026-11-21T03:00:00Z');
const PLAN = `#/migrations/${MIGRATION_PLAN_ID}`;
const RUN: MigrationSeed = { clock: () => NOW.getTime() };

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

const T = {
  en: {
    check: 'Check plan', ask: 'Ask approval to move 50 cases', approve: 'Approve moving 50 cases', create: 'Create plan', next: 'Next step',
    unchecked: 'Not checked yet', can: '50 cases can move. 2 cannot.', self: 'You proposed this move, so a second person approves it.',
    progress: '40 of 52 moved', done: '49 moved. 3 not moved.', reader: 'You can read this plan, not run it.', cooling: 'Wait one day', unmapped: 'Some cases wait at steps the plan does not cover.',
    notMoved: 'Cases that stayed on version 2', open: 'Steps still open: 2', diagrams: 'Diagrams', steps: 'Steps',
  },
  vi: {
    check: 'Kiểm tra kế hoạch', ask: 'Xin duyệt chuyển 50 hồ sơ', approve: 'Duyệt chuyển 50 hồ sơ', create: 'Lập kế hoạch', next: 'Bước tiếp theo',
    unchecked: 'Chưa kiểm tra', can: '50 hồ sơ có thể chuyển. 2 hồ sơ không thể.', self: 'Bạn đã đề xuất việc chuyển này, nên người thứ hai sẽ duyệt.',
    progress: 'Đã chuyển 40 trên 52', done: 'Đã chuyển 49. Chưa chuyển 3.', reader: 'Bạn chỉ xem được kế hoạch này, không chạy được.', cooling: 'Chờ một ngày', unmapped: 'Một số hồ sơ đang chờ ở bước mà kế hoạch chưa nêu.',
    notMoved: 'Các hồ sơ vẫn ở phiên bản 2', open: 'Bước chưa chọn: 2', diagrams: 'Sơ đồ', steps: 'Các bước',
  },
} as const;

async function mockEngines(page: Page, scenario: Scenario, migration: MigrationSeed, locale: Locale) {
  await mockHost(page, scenario);
  await mockRuleEngine(page, scenario);
  const engine = createFakeProcessEngine({ migration, locale });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(scenario), permissions: scenarioPermissions(scenario) });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return engine;
}

const dock = (page: Page, locale: Locale) => page.getByRole('region', { name: T[locale].next });
const button = (page: Page, locale: Locale, name: string) => dock(page, locale).getByRole('button', { name });

interface Screen {
  name: string;
  scenario: Scenario;
  migration: MigrationSeed;
  hash: string;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  /** What must be on screen and fully above the fold once the state is reached. */
  focus: (page: Page, locale: Locale) => Locator;
  /** The one primary action, which must stay above the fold beside the focal element. */
  action?: (page: Page, locale: Locale) => Locator;
  /** Also shot at 1440x1200. */
  tall?: boolean;
}

const check = async (page: Page, locale: Locale) => { await button(page, locale, T[locale].check).click(); await expect(page.getByRole('heading', { name: T[locale].can })).toBeVisible(); };

const SCREENS: readonly Screen[] = [
  { name: 'plan-unchecked', scenario: 'migration-operator', migration: { ...RUN, state: 'planned' }, hash: PLAN, focus: (page, l) => page.getByText(T[l].unchecked), action: (page, l) => button(page, l, T[l].check) },
  { name: 'plan-checked', scenario: 'migration-operator', migration: { ...RUN, state: 'planned' }, hash: PLAN, prepare: check, tall: true, focus: (page, l) => page.getByRole('heading', { name: T[l].can }), action: (page, l) => button(page, l, T[l].ask) },
  {
    name: 'plan-steps', scenario: 'migration-operator', migration: { ...RUN, state: 'planned' }, hash: PLAN,
    prepare: async (page, l) => { await check(page, l); await page.getByRole('tab', { name: T[l].steps }).click(); },
    focus: (page, l) => page.getByRole('button', { name: T[l].cooling }), action: (page, l) => button(page, l, T[l].ask),
  },
  {
    name: 'plan-unmapped', scenario: 'migration-operator', migration: { ...RUN, state: 'unmapped' }, hash: PLAN,
    prepare: async (page, l) => { await button(page, l, T[l].check).click(); await expect(dock(page, l).getByText(T[l].unmapped)).toBeVisible(); },
    focus: (page, l) => dock(page, l).getByText(T[l].unmapped), action: (page, l) => dock(page, l).getByRole('button', { name: /50|22/ }),
  },
  { name: 'proposal-proposer', scenario: 'migration-operator', migration: { ...RUN, state: 'proposed', proposedBy: 'lan' }, hash: PLAN, focus: (page, l) => dock(page, l).getByText(T[l].self), action: (page, l) => button(page, l, T[l].approve) },
  { name: 'proposal-approver', scenario: 'migration-approver', migration: { ...RUN, state: 'proposed', proposedBy: 'lan' }, hash: PLAN, focus: (page, l) => button(page, l, T[l].approve), action: (page, l) => button(page, l, T[l].approve) },
  { name: 'proposal-reader', scenario: 'migration-reader', migration: { ...RUN, state: 'proposed', proposedBy: 'lan' }, hash: PLAN, focus: (page, l) => dock(page, l).getByText(T[l].reader) },
  { name: 'running', scenario: 'migration-operator', migration: { ...RUN, state: 'running' }, hash: PLAN, focus: (page, l) => page.getByRole('heading', { name: T[l].progress }) },
  { name: 'done-not-moved', scenario: 'migration-operator', migration: { ...RUN, state: 'completed' }, hash: PLAN, tall: true, focus: (page, l) => page.getByRole('table', { name: T[l].notMoved }) },
  {
    name: 'builder', scenario: 'migration-operator', migration: { ...RUN, state: 'none' }, hash: '#/processes/kyc-onboarding/3/move?from=2',
    focus: (page, l) => dock(page, l).getByText(T[l].open), action: (page, l) => button(page, l, T[l].create),
  },
];

/** One screenshot per state at 1440x900 rotating theme and language; two states also at 1440x1200. */
function shot(index: number, screen: Screen, height: number, theme: string, locale: Locale): boolean {
  const rotation = [['light', 'en'], ['dark', 'vi'], ['light', 'vi'], ['dark', 'en']][index % 4];
  if (rotation?.[0] !== theme || rotation[1] !== locale) return false;
  return height === 900 || screen.tall === true;
}

SCREENS.forEach((screen, index) => {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `migration-${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`CKP_027_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.clock.setFixedTime(NOW);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockEngines(page, screen.scenario, screen.migration, locale);
          await page.goto(`./${screen.hash}`);
          await expect(page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await expect(page.locator('.skeleton')).toHaveCount(0);
          await screen.prepare?.(page, locale);
          const focus = screen.focus(page, locale);
          await expect(focus).toBeVisible();
          const box = await focus.boundingBox();
          expect(box, 'the focal element is rendered').not.toBeNull();
          expect((box?.y ?? 0) + (box?.height ?? 0), 'visible without scrolling').toBeLessThanOrEqual(viewport.height);
          if (screen.action !== undefined) {
            const action = await screen.action(page, locale).boundingBox();
            expect((action?.y ?? viewport.height) + (action?.height ?? 0), 'the primary action stays above the fold').toBeLessThanOrEqual(viewport.height);
          }
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'no sideways scrolling').toBe(true);
          await expect(page.locator('main')).not.toContainText(/CKP-0|PD-1|PRE-\d|\{"|01JD4|STEP_IN_FLIGHT|LOCK_CONFLICT|MAPPED/);
          await page.evaluate(async () => { await document.fonts.ready; });
          if (shot(index, screen, viewport.height, theme, locale)) await page.screenshot({ path: `${SHOTS}/${file}`, fullPage: false });
          expect(violations).toEqual([]);
          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
          expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
        });
      }
    }
  }
});

for (const locale of LOCALES) {
  test(`CKP_057_the_operator_proposes_and_a_second_person_approves_by_keyboard_${locale}`, async ({ browser }) => {
    // The proposer plans from the diagrams: check, reason, ask. The engine is shared with the second person's page.
    const proposer = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await proposer.clock.setFixedTime(NOW);
    await rememberLocale(proposer, locale);
    const engine = await mockEngines(proposer, 'migration-operator', { ...RUN, state: 'planned' }, locale);
    await proposer.goto(`./${PLAN}`);
    await check(proposer, locale);
    const reason = dock(proposer, locale).getByRole('textbox');
    await reason.fill('Moving to new KYC review');
    const ask = button(proposer, locale, T[locale].ask);
    await expect(ask).toBeEnabled();
    // From the reason field to the one action by keyboard (the check before it was a click).
    await reason.press('Tab');
    await expect(ask).toBeFocused();
    expect(await ask.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
    await proposer.keyboard.press('Enter');
    await expect(button(proposer, locale, T[locale].approve)).toBeDisabled();
    await expect(dock(proposer, locale).getByText(T[locale].self)).toBeVisible();
    expect(engine.migrations.calls.map((call) => call.command)).toEqual(['validate', 'propose']);

    // The second person, on the same engine, approves the proposal as shown, by keyboard.
    const approver = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await approver.clock.setFixedTime(NOW);
    await rememberLocale(approver, locale);
    await mockHost(approver, 'migration-approver');
    await mockRuleEngine(approver, 'migration-approver');
    await approver.route('**/api/bpm/processes/v1/**', async (route) => {
      const request = route.request();
      const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser('migration-approver'), permissions: scenarioPermissions('migration-approver') });
      await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
    });
    await approver.goto(`./${PLAN}`);
    const approve = button(approver, locale, T[locale].approve);
    await expect(approve).toBeEnabled();
    await approve.focus();
    await approver.keyboard.press('Enter');
    await expect(dock(approver, locale).getByText(locale === 'en' ? 'The move runs in batches. You can leave this page.' : 'Việc chuyển chạy theo từng lô. Bạn có thể rời trang này.')).toBeVisible();
    expect(engine.migrations.calls.at(-1)).toEqual(expect.objectContaining({ command: 'approve', by: 'minh', ifMatch: '"1"' }));
    await proposer.close();
    await approver.close();
  });
}
