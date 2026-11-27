import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { meAn, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { QUEUE_TASKS } from '../src/mocks/taskQueueDemo';
import { mockHost, rememberLocale } from './mockHost';

/*
 * A task completed through its declared form (CKP-021) at 1440x900 and 1440x1200, English and Vietnamese, light and
 * dark: the form, and the engine's refusal shown under its field. The form's fields and the Complete task action are
 * on screen without scrolling at 1440x900; the page never scrolls sideways; axe-core finds no serious violation.
 * Screenshots go to FORM_SHOTS_DIR, or e2e/screenshots/ by default.
 */

const SHOTS = process.env['FORM_SHOTS_DIR'] ?? 'e2e/screenshots';
const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

const T = {
  complete: { en: 'Complete task', vi: 'Hoàn thành tác vụ' },
  reject: { en: 'REJECT', vi: 'REJECT' },
  date: { en: /^Review date/, vi: /^Ngày thẩm định/ },
  dateText: { en: '2026-11-21', vi: '2026-11-21' },
} as const;

async function openForm(page: Page, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: Locale) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  const engine = createFakeProcessEngine({ locale });
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: meAn }));
  await page.route('**/api/bpm/rules/v1/**', (route) => route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ status: 403, code: 'PERMISSION_DENIED' }) }));
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meAn.user.id, permissions: meAn.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto(`./#/tasks/${QUEUE_TASKS.myCreditReview}`);
  await expect(page.getByRole('form')).toBeVisible();
  await expect(page.locator('.skeleton')).toHaveCount(0);
}

async function expectAboveFold(locator: Locator, height: number) {
  await expect(locator).toBeVisible();
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.y + box.height).toBeLessThanOrEqual(height);
}

async function refuse(page: Page, locale: Locale) {
  await page.getByRole('radio', { name: T.reject[locale] }).check();
  await page.getByLabel(T.date[locale]).fill(T.dateText[locale]);
  await page.getByRole('button', { name: T.complete[locale] }).click();
  await expect(page.locator('.field__error').first()).toBeVisible();
}

for (const viewport of VIEWPORTS) {
  for (const theme of ['light', 'dark'] as const) {
    for (const locale of LOCALES) {
      const size = `${String(viewport.width)}x${String(viewport.height)}`;
      test(`TR_UI_03_visual_task_form_${size}_${theme}_${locale}`, async ({ page }) => {
        await openForm(page, viewport, theme, locale);
        await expectAboveFold(page.getByRole('button', { name: T.complete[locale] }), viewport.height);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await page.screenshot({ path: `${SHOTS}/task-form-${size}-${theme}-${locale}.png`, fullPage: false });
      });
      test(`TR_UI_03_visual_task_form_refused_${size}_${theme}_${locale}`, async ({ page }) => {
        await openForm(page, viewport, theme, locale);
        await refuse(page, locale);
        await expectAboveFold(page.getByRole('button', { name: T.complete[locale] }), viewport.height);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await page.screenshot({ path: `${SHOTS}/task-form-error-${size}-${theme}-${locale}.png`, fullPage: false });
      });
    }
  }
}

for (const theme of ['light', 'dark'] as const) {
  test(`TR_UI_03_axe_no_serious_violations_task_form_${theme}`, async ({ page }) => {
    await openForm(page, { width: 1440, height: 900 }, theme, 'vi');
    await refuse(page, 'vi');
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
    const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
    expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
  });
}
