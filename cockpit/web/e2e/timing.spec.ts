import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { scenarioPermissions, scenarioUser } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/* #123: the Timing view of a definition version, at 1440x900 and 1440x1200. WAVE_SHOTS names a folder for screenshots. */

async function open(page: Page, locale: 'en' | 'vi'): Promise<void> {
  await rememberLocale(page, locale);
  await mockHost(page, 'processes-reviewer');
  await mockRuleEngine(page, 'processes-reviewer');
  const engine = createFakeProcessEngine();
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser('processes-reviewer'), permissions: scenarioPermissions('processes-reviewer') });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto('/#/processes/kyc-onboarding/2/timing');
  await expect(page.getByRole('table', { name: /./ })).toBeVisible();
  await page.evaluate(async () => { await document.fonts.ready; });
}

for (const height of [900, 1200]) {
  for (const locale of ['en', 'vi'] as const) {
    test(`step timing at 1440x${String(height)} in ${locale}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height });
      await open(page, locale);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
      expect((await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);
      const folder = process.env['WAVE_SHOTS'];
      if (folder !== undefined) await page.screenshot({ path: `${folder}/timing-${locale}-1440x${String(height)}.png` });
    });
  }
}
