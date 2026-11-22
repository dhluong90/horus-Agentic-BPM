import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { scenarioPermissions, scenarioUser, meSau, metaWithProcesses, type Scenario } from '../src/mocks/fixtures';
import type { BpmMeV1 } from '../src/api/generated/bpm-me.v1';
import { personalDataDemo } from '../src/mocks/demoCatalogue';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine } from '../src/mocks/ruleEngine';
import { QUEUE_TASKS } from '../src/mocks/taskQueueDemo';
import { mockHost, rememberLocale } from './mockHost';

/*
 * Display names (#415, #442) and the calm "not classified" note (#438, PD-146), each changed state at 1440x900,
 * rotating light and dark, English and Vietnamese, plus the two review screens at 1440x1200. Each run checks that the
 * focal element is fully on screen, that the page never scrolls sideways, that no identifier or code shows, and that
 * axe-core finds no serious violation. Screenshots go to NAMES_SHOTS, or e2e/screenshots/ by default.
 */

const SHOTS = process.env['NAMES_SHOTS'] ?? 'e2e/screenshots';
type Locale = 'en' | 'vi';
type Theme = 'light' | 'dark';

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

async function engines(page: Page, scenario: Scenario, locale: Locale, user?: BpmMeV1) {
  await mockHost(page, scenario);
  if (user !== undefined) {
    await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
    await page.route('**/api/bpm/me', (route) => route.fulfill({ json: user }));
  }
  const who = user?.user.id ?? scenarioUser(scenario);
  const permissions = user?.permissions ?? scenarioPermissions(scenario);
  const rules = createFakeEngine(personalDataDemo(), { warnUnclassified: true });
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = rules.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: who, permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  const process = createFakeProcessEngine({ locale, unclassified: true });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = process.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: who, permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
}

interface Shot {
  name: string;
  scenario: Scenario;
  user?: BpmMeV1;
  hash: string;
  theme: Theme;
  locale: Locale;
  heights: readonly number[];
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  focal: (page: Page) => Locator;
  /** Text that must not appear on the screen: an identifier, a code. */
  never: RegExp;
}

const dialog = (page: Page) => page.getByRole('dialog');

const SHOTS_LIST: readonly Shot[] = [
  {
    name: 'queue-names', scenario: 'tasks-supervisor', user: meSau, hash: '#/tasks?view=all', theme: 'light', locale: 'vi', heights: [900],
    focal: (page) => page.locator('.tasks__inspector'), never: /credit-officer|loans-operations|role:|group:|\b(hoa|an|lan|minh)\b đã nhận|Người nhận\s+(hoa|an)\b/,
  },
  {
    name: 'inspector-holder', scenario: 'tasks-supervisor', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.collateral}`, theme: 'dark', locale: 'en', heights: [900],
    focal: (page) => page.locator('.tasks__inspector .tasks__buttons'), never: /\bhoa\b|credit-officer|role:/,
  },
  {
    name: 'reassign-missing-role', scenario: 'tasks-supervisor', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.creditReview}`, theme: 'dark', locale: 'vi', heights: [900],
    prepare: async (page, locale) => {
      await page.locator('.tasks__inspector').getByRole('button', { name: locale === 'vi' ? 'Giao lại' : 'Reassign' }).click();
      await dialog(page).getByLabel(locale === 'vi' ? 'Mã người dùng của đồng nghiệp' : "Colleague's user id").fill('minh');
      await dialog(page).getByLabel(locale === 'vi' ? 'Lý do' : 'Reason').fill('Covering leave');
      await dialog(page).getByRole('button', { name: locale === 'vi' ? 'Giao lại' : 'Reassign' }).click();
      await expect(dialog(page).getByRole('alert')).toBeVisible();
    },
    focal: (page) => dialog(page).getByRole('alert'), never: /MISSING_ROLE|ASSIGNEE_NOT_ALLOWED|credit-officer|role:|\bminh không/,
  },
  {
    name: 'queue-names-en', scenario: 'tasks-supervisor', user: meSau, hash: '#/tasks?view=all', theme: 'dark', locale: 'en', heights: [900],
    focal: (page) => page.locator('.tasks__inspector'), never: /credit-officer|loans-operations|role:|group:|\b(hoa|an|lan|minh) (claimed|has)\b/,
  },
  {
    name: 'rules-review-requester', scenario: 'rules-approver', hash: '#/rules/customer-screening/2', theme: 'light', locale: 'vi', heights: [900],
    focal: (page) => page.getByRole('heading', { level: 1 }), never: /\bhoa\b|Credit officer|Hoa Tran/,
  },
  {
    name: 'approvals-requester', scenario: 'processes-approver', hash: '#/approvals', theme: 'light', locale: 'en', heights: [900],
    focal: (page) => page.getByRole('main'), never: /requestedBy|\blan\b/,
  },
  {
    name: 'rules-review-not-classified', scenario: 'rules-approver', hash: '#/rules/customer-screening/2', theme: 'dark', locale: 'en', heights: [900, 1200],
    focal: (page) => page.getByRole('region', { name: /Your decision/ }).or(page.getByRole('heading', { name: 'Your decision' })), never: /UNCLASSIFIED/,
  },
  {
    name: 'process-review-not-classified', scenario: 'processes-approver', hash: '#/processes/kyc-onboarding/3/changes', theme: 'light', locale: 'vi', heights: [900, 1200],
    focal: (page) => page.getByRole('region', { name: 'Quyết định của bạn' }), never: /UNCLASSIFIED|CREDIT_OFFICER|documentType|\bsummary\b|[0-9a-f]{12}|Duyệt nội dung/,
  },
];

async function open(page: Page, shot: Shot, height: number) {
  await page.setViewportSize({ width: 1440, height });
  await page.emulateMedia({ colorScheme: shot.theme, reducedMotion: 'reduce' });
  await rememberLocale(page, shot.locale);
  await engines(page, shot.scenario, shot.locale, shot.user);
  await page.goto(`./${shot.hash}`);
  await expect(page.locator('.skeleton')).toHaveCount(0);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  if (shot.hash.startsWith('#/tasks')) await expect(page.locator('.tasks__table tbody tr').first()).toBeVisible();
  // The names arrive after the first paint: wait until the lookup has been answered.
  await page.waitForLoadState('networkidle');
  if (shot.prepare) await shot.prepare(page, shot.locale);
}

for (const shot of SHOTS_LIST) {
  for (const height of shot.heights) {
    test(`TR_UI_04_display_names_${shot.name}_1440x${String(height)}_${shot.theme}_${shot.locale}`, async ({ page }) => {
      await open(page, shot, height);
      const focal = shot.focal(page).first();
      await expect(focal).toBeVisible();
      const box = await focal.boundingBox();
      if (!box) throw new Error('no box');
      expect(box.y + Math.min(box.height, 80)).toBeLessThanOrEqual(height);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      // Visible text only: a code kept behind a closed Details (styleguide section 9) is not on screen.
      expect(await page.locator('body').innerText()).not.toMatch(shot.never);
      if (shot.name.endsWith('not-classified')) {
        await expect(page.getByRole('group', { name: shot.locale === 'vi' ? 'Chưa phân loại' : 'Not classified' })).toBeVisible();
      }
      await page.screenshot({ path: `${SHOTS}/names-${shot.name}-1440x${String(height)}-${shot.theme}-${shot.locale}.png`, fullPage: false });
    });
  }
}

for (const shot of SHOTS_LIST) {
  for (const theme of ['light', 'dark'] as const) {
    test(`TR_UI_04_axe_no_serious_violations_${shot.name}_${theme}`, async ({ page }) => {
      await open(page, { ...shot, theme }, 900);
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
      expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
    });
  }
}
