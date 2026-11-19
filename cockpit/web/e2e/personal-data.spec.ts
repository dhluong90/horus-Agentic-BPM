import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { personalDataDemo } from '../src/mocks/demoCatalogue';
import { meMinh } from '../src/mocks/fixtures';
import { createFakeEngine } from '../src/mocks/ruleEngine';
import { mockHost, rememberLocale } from './mockHost';

/*
 * Personal data on the approval screen (PD-099, RLE-049, RLE-051): customer-screening v2 drops the national ID's
 * mask and adds an email column without one. The approver sees both warnings beside Approve and the masking
 * change in the diff. Screenshots go to e2e/screenshots/ for the design review.
 */

const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
const TEXT = {
  en: { approve: 'Approve and publish', warning: 'Personal data without a masking rule', removed: 'Mask removed' },
  vi: { approve: 'Phê duyệt và xuất bản', warning: 'Dữ liệu cá nhân chưa có quy tắc che', removed: 'Bỏ che' },
} as const;

async function openReview(page: Page, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: 'en' | 'vi') {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  await mockHost(page, 'ready-approver');
  const engine = createFakeEngine(personalDataDemo());
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meMinh.user.id, permissions: meMinh.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto('./#/rules/customer-screening/2');
  await expect(page.getByRole('heading', { level: 1, name: /customer-screening/ })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
  await expect(page.locator('.masking-note__flag')).toBeVisible();
}

async function bottomOf(locator: Locator): Promise<number> {
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  return box.y + box.height;
}

test('CKP_037_the_warning_and_approve_are_both_on_screen_at_1440x900', async ({ page }) => {
  await openReview(page, { width: 1440, height: 900 }, 'light', 'en');
  const note = page.getByRole('note');
  await expect(note).toContainText(TEXT.en.warning);
  await expect(note.getByRole('listitem')).toHaveText([
    'National ID looks like personal data (national id) but has no masking rule.',
    'Email looks like personal data (email) but has no masking rule.',
  ]);
  const approve = page.getByRole('button', { name: TEXT.en.approve });
  await expect(approve).toBeEnabled();
  expect(await bottomOf(note)).toBeLessThanOrEqual(900);
  expect(await bottomOf(approve)).toBeLessThanOrEqual(900);
  await expect(page.locator('body')).not.toContainText('UNMASKED_PERSONAL_DATA');
});

test('CKP_037_the_diff_marks_the_removed_mask_and_leaves_unchanged_columns_unmarked', async ({ page }) => {
  await openReview(page, { width: 1440, height: 900 }, 'light', 'en');
  const header = (name: string) => page.getByRole('columnheader', { name: new RegExp(`^${name}`) });
  await expect(header('National ID')).toContainText(TEXT.en.removed);
  await expect(header('National ID')).toContainText(/Masked\s*→\s*,?\s*Not masked by default/);
  await expect(header('Email')).toContainText('New column: Not masked by default');
  await expect(header('Phone number')).toHaveText('Phone number');
  await expect(page.getByText('Mask removed on 1 column')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('CKP_037_a_version_without_warnings_has_no_warning_area', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'ready-approver');
  const engine = createFakeEngine(personalDataDemo());
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: meMinh.user.id, permissions: meMinh.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  await page.goto('./#/rules/card-fee/2');
  await expect(page.getByRole('button', { name: TEXT.en.approve })).toBeVisible();
  await expect(page.getByRole('note')).toHaveCount(0);
  await expect(page.locator('.masking-note')).toHaveCount(0);
});

for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      test(`CKP_037_visual_approval_with_personal_data_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
        await openReview(page, viewport, theme, locale);
        await expect(page.getByRole('note')).toContainText(TEXT[locale].warning);
        expect(await bottomOf(page.getByRole('button', { name: TEXT[locale].approve }))).toBeLessThanOrEqual(viewport.height);
        expect(await bottomOf(page.getByRole('note'))).toBeLessThanOrEqual(viewport.height);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await page.screenshot({ path: `e2e/screenshots/personal-data-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png` });
      });
    }
  }
}

for (const theme of THEMES) {
  test(`CKP_037_axe_no_serious_violations_on_the_approval_with_personal_data_${theme}`, async ({ page }) => {
    await openReview(page, { width: 1440, height: 900 }, theme, 'en');
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
    const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
    expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
  });
}
