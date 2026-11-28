import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { scenarioPermissions, scenarioUser } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/* #125: the business calendar list and editor. WAVE_SHOTS names a folder for screenshots. */

async function open(page: Page, locale: 'en' | 'vi', hash: string) {
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
  return engine;
}

async function shoot(page: Page, name: string, locale: string, height: number): Promise<void> {
  const folder = process.env['WAVE_SHOTS'];
  if (folder !== undefined) await page.screenshot({ path: `${folder}/${name}-${locale}-1440x${String(height)}.png` });
}

for (const height of [900, 1200]) {
  for (const locale of ['en', 'vi'] as const) {
    test(`calendar list and editor at 1440x${String(height)} in ${locale}`, async ({ page }) => {
      await page.setViewportSize({ width: 1440, height });
      const engine = await open(page, locale, '#/calendars');
      await expect(page.getByRole('table')).toBeVisible();
      // No raw key and no raw zone id anywhere on the list.
      await expect(page.locator('main')).not.toContainText(/vn-banking-hours|Asia\/Ho_Chi_Minh/);
      await shoot(page, 'cal-list', locale, height);

      await page.getByRole('link', { name: 'Vn banking hours' }).click();
      await expect(page.locator('main')).not.toContainText(/vn-banking-hours|Asia\/Ho_Chi_Minh/);
      await expect(page.getByRole('button', { name: /Send for approval|Gửi để duyệt/ })).toHaveCount(0);
      await shoot(page, 'cal-hours-read', locale, height);
      await page.getByRole('button', { name: /Edit calendar|Sửa lịch/ }).click();
      const send = page.getByRole('button', { name: /Send for approval|Gửi để duyệt/ });
      await expect(send).toBeVisible();
      // The send button sits above the fold on a laptop.
      expect((await send.boundingBox())?.y ?? 9999).toBeLessThan(900 - 40);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
      await shoot(page, 'cal-hours-edit', locale, height);
      expect((await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);

      await page.getByRole('tab', { name: /Holidays|Ngày nghỉ/ }).click();
      await expect(send).toBeVisible();
      expect((await send.boundingBox())?.y ?? 9999).toBeLessThan(900 - 40);
      await page.getByRole('button', { name: /Add holiday|Thêm ngày nghỉ/ }).click();
      await page.locator('input[type="date"]').last().fill('2027-04-30');
      await page.locator('.calendar-holidays__item input:not([type="date"])').last().fill('Reunification Day');
      await page.locator('.calendar-edit__reason input').fill('Holiday added');
      await shoot(page, 'cal-holidays-edit', locale, height);
      expect((await new AxeBuilder({ page }).analyze()).violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);

      await send.click();
      const link = page.getByRole('link', { name: /Open the change|Mở thay đổi/ });
      await expect(link).toBeVisible();
      await shoot(page, 'cal-pending', locale, height);

      // A second person approves; the history then says who changed it and when.
      const id = /calendar-changes\/([^/]+)$/.exec((await link.getAttribute('href')) ?? '')?.[1] ?? '';
      const minh = ['processes:read', 'processes:calendar:manage'];
      const read = engine.handle({ method: 'GET', path: `/api/bpm/processes/v1/calendar-changes/${id}`, headers: {}, body: '', user: 'minh', permissions: minh });
      const hash = (JSON.parse(read.body) as { contentHash: string }).contentHash;
      engine.handle({ method: 'POST', path: `/api/bpm/processes/v1/calendar-changes/${id}:approve`, headers: { 'Idempotency-Key': 'k1' }, body: JSON.stringify({ expectedChangeHash: hash }), user: 'minh', permissions: minh });
      await page.goto('/#/calendars/vn-banking-hours');
      await page.reload();
      await page.getByRole('tab', { name: /History|Lịch sử/ }).click();
      await expect(page.locator('.change-state__dot').first()).toBeVisible();
      await shoot(page, 'cal-history', locale, height);
    });
  }
}
