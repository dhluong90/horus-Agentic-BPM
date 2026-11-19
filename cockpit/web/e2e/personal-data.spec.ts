import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { personalDataDemo } from '../src/mocks/demoCatalogue';
import { meLan, meMinh } from '../src/mocks/fixtures';
import { createFakeEngine, type StoredVersion } from '../src/mocks/ruleEngine';
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
  en: { approve: 'Approve and publish', warning: 'Personal data without a masking rule', accept: 'I accept that these inputs are logged without masking: National ID, Email' },
  vi: { approve: 'Phê duyệt và xuất bản', warning: 'Dữ liệu cá nhân chưa có quy tắc che', accept: 'Tôi chấp nhận các trường sau được ghi log không che: National ID, Email' },
} as const;

/** Answers the Rule Engine from the personal-data demo, as `user` with `permissions`. */
async function routeEngine(page: Page, seed: StoredVersion[], user: string, permissions: readonly string[]) {
  const engine = createFakeEngine(seed);
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user, permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
}

async function prepare(page: Page, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: 'en' | 'vi') {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
}

async function openReview(page: Page, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: 'en' | 'vi') {
  await prepare(page, viewport, theme, locale);
  await mockHost(page, 'ready-approver');
  await routeEngine(page, personalDataDemo(), meMinh.user.id, meMinh.permissions);
  await page.goto('./#/rules/customer-screening/2');
  await expect(page.getByRole('heading', { level: 1, name: /customer-screening/ })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
  await expect(page.locator('.masking-flag').first()).toBeAttached();
  await expect(page.getByRole('checkbox')).toBeVisible();
}

/** The author's view of the same change as a draft: the warning beside Request publish, without the box. */
async function openDraft(page: Page, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: 'en' | 'vi') {
  await prepare(page, viewport, theme, locale);
  await mockHost(page, 'rules-author');
  const seed = personalDataDemo().map((v) => (v.ruleSet === 'customer-screening' && v.version === 2 ? { ...v, status: 'DRAFT' as const, requestedBy: null, requestedAt: null, createdBy: meLan.user.id } : v));
  await routeEngine(page, seed, meLan.user.id, [...meLan.permissions, 'rules:evaluate']);
  await page.goto('./#/rules/customer-screening/2');
  await expect(page.getByRole('heading', { level: 1, name: /customer-screening/ })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
  await expect(page.locator('.pii-warning')).toBeVisible();
}

async function bottomOf(locator: Locator): Promise<number> {
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  return box.y + box.height;
}

test('CKP_037_the_warning_the_acceptance_and_approve_are_all_on_screen_at_1440x900', async ({ page }) => {
  await openReview(page, { width: 1440, height: 900 }, 'light', 'en');
  const warning = page.getByRole('group', { name: TEXT.en.warning });
  await expect(warning.getByRole('listitem')).toHaveText(['National ID: no masking rule', 'Email: no masking rule']);
  const box = page.getByRole('checkbox', { name: TEXT.en.accept });
  const approve = page.getByRole('button', { name: TEXT.en.approve });
  await expect(approve).toBeDisabled();
  for (const control of [warning, box, approve]) expect(await bottomOf(control)).toBeLessThanOrEqual(900);
  // Both controls keep a 44 px target.
  expect((await page.locator('.pii-warning__accept').boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  expect((await approve.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  await box.check();
  await expect(approve).toBeEnabled();
  await expect(page.locator('body')).not.toContainText('UNMASKED_PERSONAL_DATA');
});

test('CKP_037_approving_sends_the_accepted_code_and_publishes', async ({ page }) => {
  await openReview(page, { width: 1440, height: 900 }, 'light', 'en');
  const sent = page.waitForRequest((request) => request.method() === 'POST' && request.url().includes(':approve'));
  await page.getByRole('checkbox', { name: TEXT.en.accept }).check();
  await page.getByRole('button', { name: TEXT.en.approve }).click();
  expect((await sent).postDataJSON()).toEqual({ acknowledgedWarnings: ['UNMASKED_PERSONAL_DATA'] });
  await expect(page.getByText('Version 2 is published.')).toBeVisible();
});

test('CKP_037_masking_changes_are_sentences_above_the_table_and_a_marker_on_the_header', async ({ page }) => {
  await openReview(page, { width: 1440, height: 900 }, 'light', 'en');
  await expect(page.getByRole('list', { name: 'Changes outside the rows' }).getByRole('listitem')).toHaveText([
    'National ID: mask removed (masked → not masked by default)',
    'Condition column added: Email, not masked by default',
  ]);
  await expect(page.getByText('Mask removed on 1 column')).toBeVisible();
  await expect(page.locator('.masking-flag')).toHaveCount(2);
  await expect(page.getByText('No row changes.')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('CKP_037_a_version_without_warnings_has_no_warning_and_no_acceptance', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'ready-approver');
  await routeEngine(page, personalDataDemo(), meMinh.user.id, meMinh.permissions);
  await page.goto('./#/rules/card-fee/2');
  await expect(page.getByRole('button', { name: TEXT.en.approve })).toBeEnabled();
  await expect(page.locator('.pii-warning')).toHaveCount(0);
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await expect(page.locator('.masking-flag')).toHaveCount(0);
});

for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      test(`CKP_037_visual_approval_with_personal_data_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
        await openReview(page, viewport, theme, locale);
        await expect(page.getByRole('group', { name: TEXT[locale].warning })).toBeVisible();
        expect(await bottomOf(page.getByRole('button', { name: TEXT[locale].approve }))).toBeLessThanOrEqual(viewport.height);
        expect(await bottomOf(page.getByRole('checkbox', { name: TEXT[locale].accept }))).toBeLessThanOrEqual(viewport.height);
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
        await page.screenshot({ path: `e2e/screenshots/personal-data-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png` });
      });
    }
  }
}

for (const [theme, locale] of [['light', 'en'], ['dark', 'vi']] as const) {
  test(`CKP_037_visual_author_draft_with_personal_data_1440x900_${theme}_${locale}`, async ({ page }) => {
    await openDraft(page, { width: 1440, height: 900 }, theme, locale);
    await expect(page.getByRole('group', { name: TEXT[locale].warning })).toBeVisible();
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    expect(await bottomOf(page.locator('.pii-warning'))).toBeLessThanOrEqual(900);
    await page.screenshot({ path: `e2e/screenshots/personal-data-draft-1440x900-${theme}-${locale}.png` });
  });
}

for (const theme of THEMES) {
  test(`CKP_037_axe_no_serious_violations_on_the_approval_with_personal_data_${theme}`, async ({ page }) => {
    await openReview(page, { width: 1440, height: 900 }, theme, 'en');
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
    const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
    expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
  });
}
