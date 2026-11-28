import { expect, test, type Page } from '@playwright/test';
import { BOOKED_INSTANCE_ID } from '../src/mocks/caseDemo';
import { scenarioPermissions, scenarioUser, type Scenario } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * Visual regression (#587): the main cockpit screens at 1440x900 in the light theme and English, compared with the
 * reviewed images in e2e/visual-baselines. The data is the demo data of the fake engines, the clock is fixed, motion and
 * the caret are off, and the page waits for the fonts, so two runs draw the same pixels.
 *
 * The images are drawn on Linux, in the Playwright image the CI job uses (the system fonts differ on other systems), so the
 * spec runs on Linux only. To compare or update: `npm run e2e:visual` and `npm run e2e:visual:update` run it in that
 * image through Docker (see cockpit/docs/TEST-PLAN.md, "Visual regression baselines").
 * A drawing may differ from its image by at most MAX_DIFF_RATIO of its pixels (0.05 %, about 650 pixels), so a moved
 * control, a changed colour or a different line of text fails and anti-aliasing does not.
 */

const NOW = new Date('2026-11-21T03:00:00Z');
const VIEWPORT = { width: 1440, height: 900 } as const;
const MAX_DIFF_RATIO = 0.0005;

interface Screen {
  name: string;
  scenario: Scenario;
  hash: string;
  /** A control that exists only once the screen's data is drawn. */
  ready: (page: Page) => ReturnType<Page['locator']>;
}

const SCREENS: readonly Screen[] = [
  { name: 'home', scenario: 'home-operations', hash: '#/home', ready: (page) => page.locator('main h1') },
  { name: 'rules-list', scenario: 'rules-author', hash: '#/rules', ready: (page) => page.getByRole('table').getByText('card-fee') },
  { name: 'rule-table', scenario: 'rules-author', hash: '#/rules/card-fee/1', ready: (page) => page.getByRole('table').first() },
  { name: 'rule-change-review', scenario: 'rules-approver', hash: '#/rules/card-fee/2', ready: (page) => page.locator('.change-mark').first() },
  { name: 'approvals-queue', scenario: 'processes-approver', hash: '#/approvals', ready: (page) => page.locator('main h1') },
  { name: 'decision-log', scenario: 'rules-auditor', hash: '#/decisions', ready: (page) => page.locator('main h1') },
  { name: 'process-list', scenario: 'processes-reviewer', hash: '#/processes', ready: (page) => page.locator('main h1') },
  { name: 'process-diagram', scenario: 'processes-reviewer', hash: '#/processes/loan-origination/3', ready: (page) => page.locator('svg[data-layout]') },
  { name: 'process-review', scenario: 'processes-approver', hash: '#/processes/kyc-onboarding/3/changes', ready: (page) => page.locator('main h1') },
  { name: 'case', scenario: 'processes-reviewer', hash: `#/instances/${BOOKED_INSTANCE_ID}`, ready: (page) => page.locator('svg[data-layout]') },
  { name: 'task-queue', scenario: 'tasks-supervisor', hash: '#/tasks?view=all', ready: (page) => page.locator('main h1') },
  { name: 'incidents', scenario: 'incidents-operator', hash: '#/incidents', ready: (page) => page.locator('main h1') },
];

test.skip(process.platform !== 'linux', 'The baselines are drawn on Linux, in the CI Playwright image: run npm run e2e:visual');

for (const screen of SCREENS) {
  test(`TR_UI_587_visual_${screen.name}_1440x900_light_en`, async ({ page }) => {
    await page.clock.setFixedTime(NOW);
    await page.setViewportSize(VIEWPORT);
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    await rememberLocale(page, 'en');
    await mockHost(page, screen.scenario);
    await mockRuleEngine(page, screen.scenario);
    const processes = createFakeProcessEngine({ locale: 'en' });
    await page.route('**/api/bpm/processes/v1/**', async (route) => {
      const request = route.request();
      const answer = processes.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(screen.scenario), permissions: scenarioPermissions(screen.scenario) });
      await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
    });
    await page.goto(`./${screen.hash}`);
    await expect(page.getByTestId('header-user')).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await expect(screen.ready(page).first()).toBeVisible();
    await expect(page.locator('.skeleton')).toHaveCount(0);
    await page.evaluate(async () => { await document.fonts.ready; });
    await expect(page).toHaveScreenshot(`${screen.name}.png`, { animations: 'disabled', caret: 'hide', maxDiffPixelRatio: MAX_DIFF_RATIO });
  });
}
