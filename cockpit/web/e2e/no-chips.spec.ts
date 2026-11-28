import { expect, test, type Page } from '@playwright/test';
import { scenarioPermissions, scenarioUser, type Scenario } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { CHIP_EXEMPT, detectChips } from '../scripts/chip-detector.mjs';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * Rendered-DOM backstop of the "no chips" rule (styleguide, "No chips"). The stylesheet check (scripts/check-no-chips.mjs)
 * reads class names; this one reads what the browser drew on the main cockpit screens at 1440x900: an element with a
 * corner radius of 4px or more, a fill or a border, and its own short text is a chip unless it is a real control. It sees
 * inline styles, SVG shapes and classes with any name. The detector is scripts/chip-detector.mjs; the same one runs over
 * the docs home in scripts/axe-site.mjs.
 */

const SCREENS: readonly (readonly [name: string, scenario: Scenario, hash: string])[] = [
  ['home', 'home-operations', '#/home'],
  ['rules list', 'rules-author', '#/rules'],
  ['rule table', 'rules-author', '#/rules/card-fee/2'],
  ['rule changes', 'processes-approver', '#/rules/card-fee/2/changes'],
  ['rule edit', 'rules-author', '#/rules/card-fee/2/edit'],
  ['simulate', 'rules-author-simulate', '#/rules/card-fee/2/simulate'],
  ['approvals', 'processes-approver', '#/approvals'],
  ['decisions', 'rules-auditor', '#/decisions'],
  ['processes list', 'processes-reviewer', '#/processes'],
  ['process diagram', 'processes-reviewer', '#/processes/kyc-onboarding/2'],
  ['process steps', 'processes-reviewer', '#/processes/kyc-onboarding/2/steps'],
  ['process changes', 'processes-approver', '#/processes/kyc-onboarding/3/changes'],
  ['process timing', 'processes-reviewer', '#/processes/kyc-onboarding/2/timing'],
  ['process cases', 'processes-reviewer', '#/processes/kyc-onboarding/2/cases'],
  ['instance', 'processes-reviewer', '#/instances/01JD4I9NSTANCE0000000000I9'],
  ['instance with an incident', 'processes-reviewer', '#/instances/01JD4I5NC1DENT00000000IN05'],
  ['instance with undone steps', 'processes-reviewer', '#/instances/01JD4U9NDONE00000000000U09'],
  ['task review', 'processes-reviewer', '#/tasks/01JD4T4REV1EW000000000AT04'],
  ['tasks', 'tasks-operator', '#/tasks'],
  ['incidents', 'incidents-operator', '#/incidents'],
  ['migration', 'migration-operator', '#/processes/kyc-onboarding/2/move'],
  ['calendar changes', 'calendar-approver', '#/calendar-changes'],
  ['calendars', 'calendar-proposer', '#/calendars'],
  ['calendar editor', 'calendar-proposer', '#/calendars/vn-banking-hours'],
  ['service', 'ready', '#/service'],
];

async function open(page: Page, scenario: Scenario, hash: string): Promise<void> {
  await rememberLocale(page, 'en');
  await mockHost(page, scenario);
  await mockRuleEngine(page, scenario);
  const engine = createFakeProcessEngine();
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(scenario), permissions: scenarioPermissions(scenario) });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto(`/${hash}`);
  await expect(page.locator('main h1, main h2').first()).toBeVisible();
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
  await page.waitForLoadState('networkidle');
}

test.describe('no chips in the rendered cockpit', () => {
  test.use({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });

  for (const [name, scenario, hash] of SCREENS) {
    test(`no chip on ${name}`, async ({ page }) => {
      await open(page, scenario, hash);
      expect(await page.evaluate(detectChips, CHIP_EXEMPT), `a rounded, filled or bordered box around short text on ${name}`).toEqual([]);
    });
  }

  test('the detector finds a chip when there is one', async ({ page }) => {
    await page.setContent('<p><span style="display:inline-block;padding:2px 8px;border-radius:999px;background:#e6f4ee">Running</span> <button style="border-radius:8px;border:1px solid #888">Retry</button></p><svg width="200" height="60"><g><rect x="4" y="4" width="120" height="30" rx="8" fill="#eef" /><text x="12" y="24">Declined</text></g></svg>');
    const found = await page.evaluate(detectChips, CHIP_EXEMPT);
    expect(found.map((item) => item.text)).toEqual(['Running', 'Declined']);
  });
});
