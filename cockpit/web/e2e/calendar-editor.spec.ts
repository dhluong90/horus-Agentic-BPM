import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { scenarioPermissions, scenarioUser } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/* #125: the business calendar list and editor. WAVE_SHOTS names a folder for screenshots. */

async function open(page: Page, locale: 'en' | 'vi', hash: string): Promise<void> {
  await rememberLocale(page, locale);
  await mockHost(page, 'calendar-proposer');
  await mockRuleEngine(page, 'calendar-proposer');
  const engine = createFakeProcessEngine();
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser('calendar-proposer'), permissions: scenarioPermissions('calendar-proposer') });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto(`/${hash}`);
  await expect(page.locator('main h1').first()).toBeVisible();
  await page.evaluate(async () => { await document.fonts.ready; });
}

async function shoot(page: Page, name: string, locale: string, height: number): Promise<void> {
  const folder = process.env['WAVE_SHOTS'];
  if (folder !== undefined) await page.screenshot({ path: `${folder}/${name}-${locale}-1440x${String(height)}.png` });
}

for (const height of [900, 1200]) {
  for (const locale of ['en', 'vi'] as const) {
    test(`calendar list and editor at 1440x${String(height)} in ${locale}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height });
      await open(page, locale, '#/calendars');
      await expect(page.getByRole('table')).toBeVisible();
      await shoot(page, 'calendars', locale, height);

      await page.getByRole('link', { name: 'vn-banking-hours' }).click();
      const send = page.getByRole('button', { name: /Send for approval|Gửi để duyệt/ });
      await expect(send).toBeVisible();
      // The send button sits above the fold on a laptop.
      expect((await send.boundingBox())?.y ?? 9999).toBeLessThan(900 - 40);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
      await shoot(page, 'calendar-hours', locale, height);
      expect((await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);

      await page.getByRole('tab', { name: /Holidays|Ngày nghỉ/ }).click();
      await expect(send).toBeVisible();
      expect((await send.boundingBox())?.y ?? 9999).toBeLessThan(900 - 40);
      await shoot(page, 'calendar-holidays', locale, height);
      expect((await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);

      await page.getByRole('tab', { name: /History|Lịch sử/ }).click();
      await expect(page.getByText(/No change has been decided yet|Chưa có thay đổi/)).toBeVisible();
      await shoot(page, 'calendar-history', locale, height);
    });
  }
}
