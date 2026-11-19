import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import type { StoredVersion } from '../src/mocks/ruleEngine';
import { reviewDemo } from '../src/mocks/reviewDemo';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

// CKP-002: the approver reads card-fee v2 next to v1, in effect, before the approval action. The diff comes from
// the engine's GET .../diff (the mock engine answers in its shape). Checked at 1440x900 and 1440x1200, light and
// dark, English and Vietnamese, with Approve fully visible without scrolling and axe-core clean.
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

const TEXT = {
  en: { approve: 'Approve and publish', table: 'Version 1 and version 2 of card-fee, side by side', summary: '1 added, 1 changed, 1 removed, 3 unchanged', removed: 'Removed in version 2', added: 'Not in version 1', proposed: 'Proposed · takes effect when approved', fee: '35,000 VND', old: '30,000 VND' },
  vi: { approve: 'Phê duyệt và xuất bản', table: 'Phiên bản 1 và phiên bản 2 của card-fee, đặt cạnh nhau', summary: '1 thêm, 1 sửa, 1 xóa, 3 không đổi', removed: 'Đã xóa ở phiên bản 2', added: 'Không có trong phiên bản 1', proposed: 'Đề xuất · có hiệu lực khi được phê duyệt', fee: '35.000 VND', old: '30.000 VND' },
} as const;

// Dates on screen read in the bank's time zone, the same on every machine.
test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

async function openDiff(page: Page, locale: Locale, ruleSet = 'card-fee', seed?: StoredVersion[]): Promise<string[]> {
  const diffCalls: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.endsWith('/diff')) diffCalls.push(`${request.method()} ${url.pathname}${url.search}`);
  });
  await rememberLocale(page, locale);
  await mockHost(page, 'rules-approver');
  await mockRuleEngine(page, 'rules-approver', seed);
  await page.goto(`./#/rules/${ruleSet}/2`);
  await expect(page.getByTestId('header-user')).toBeVisible();
  return diffCalls;
}

const rowOf = (page: Page, id: string) => page.locator('tr').filter({ has: page.getByRole('rowheader').filter({ hasText: new RegExp(`^${id}\\b`) }) });

for (const locale of LOCALES) {
  test(`CKP_002_version_diff_highlights_row_changes_${locale}`, async ({ page }) => {
    await page.setViewportSize(VIEWPORTS[0]);
    const diffCalls = await openDiff(page, locale);
    const text = TEXT[locale];
    const table = page.getByRole('table', { name: text.table });
    await expect(table).toBeVisible();
    await expect(page.getByText(text.summary)).toBeVisible();
    expect(diffCalls).toEqual(['GET /api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff?base=1']);
    await expect(rowOf(page, 'R6')).toHaveClass(/diff-row--added/);
    await expect(rowOf(page, 'R6')).toContainText(text.added);
    await expect(rowOf(page, 'R3')).toHaveClass(/diff-row--changed/);
    await expect(rowOf(page, 'R3').locator('.diff-td--old')).toContainText(text.old);
    await expect(rowOf(page, 'R3').locator('.diff-td--mod')).toContainText(text.fee);
    await expect(rowOf(page, 'R4')).toHaveClass(/diff-row--removed/);
    await expect(rowOf(page, 'R4')).toContainText(text.removed);
    await expect(table.getByRole('columnheader').filter({ hasText: text.proposed })).toBeVisible();
    // The diff is read before the decision: the table comes first in the page, Approve docked below it.
    const order = await page.evaluate(([tableName, approveName]) => {
      const tableEl = [...document.querySelectorAll('table')].find((t) => t.querySelector('caption')?.textContent === tableName);
      const button = [...document.querySelectorAll('button')].find((b) => b.textContent.includes(approveName));
      return tableEl && button ? tableEl.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING : 0;
    }, [text.table, text.approve] as const);
    expect(order).toBeTruthy();
  });
}

test('CKP_002_keyboard_reaches_the_diff_region_then_the_decision', async ({ page }) => {
  await page.setViewportSize(VIEWPORTS[0]);
  await openDiff(page, 'en');
  const region = page.getByRole('region', { name: TEXT.en.table });
  await region.focus();
  await expect(region).toBeFocused();
  // Tab moves on from the diff to the comment, and then Reject stays disabled until a reason is written.
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Comment')).toBeFocused();
  await page.keyboard.type('Matches the 2027 tariff');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: TEXT.en.approve })).toBeFocused();
});

test('CKP_002_the_decision_stays_docked_in_view_when_the_diff_is_taller_than_the_screen', async ({ page }) => {
  // A short window makes the six-row diff overflow; the docked decision must not scroll away with it.
  await page.setViewportSize({ width: 1440, height: 640 });
  await openDiff(page, 'en');
  await expect(page.getByRole('table', { name: TEXT.en.table })).toBeVisible();
  const approve = page.getByRole('button', { name: TEXT.en.approve, exact: true });
  const box = await approve.boundingBox();
  expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(640);
  const scrollable = await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight);
  expect(scrollable).toBe(true);
});

test('CKP_002_a_removed_row_strikes_nothing', async ({ page }) => {
  await page.setViewportSize(VIEWPORTS[0]);
  await openDiff(page, 'en');
  const struck = await rowOf(page, 'R4').evaluate((row) => [...row.querySelectorAll('td, td *')]
    .filter((el) => getComputedStyle(el).textDecorationLine.includes('line-through')).length);
  expect(struck).toBe(0);
  await expect(rowOf(page, 'R4')).toContainText(TEXT.en.removed);
});

async function settle(page: Page) {
  await page.evaluate(async () => { await document.fonts.ready; });
}

async function check(page: Page) {
  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
}

// The two review screens of the design sign-off: light English and dark Vietnamese at 1440x900.
const REVIEW_SHOTS = [{ theme: 'light', locale: 'en' }, { theme: 'dark', locale: 'vi' }] as const;
const REVIEW_TEXT = {
  en: { long: 'Version 1 and version 2 of domestic-transfer-fee, side by side', structure: 'Version 1 and version 2 of card-cashback, side by side', policy: 'Hit policy: first matching row wins → exactly one row matches', column: 'Result column added: Cap', lookalike: 'Look-alike character: О U+041E', count: '1 changed cells contain look-alike characters', scheduled: 'Takes effect 01 Jan 2027 00:00 once approved.' },
  vi: { long: 'Phiên bản 1 và phiên bản 2 của domestic-transfer-fee, đặt cạnh nhau', structure: 'Phiên bản 1 và phiên bản 2 của card-cashback, đặt cạnh nhau', policy: 'Cách chọn dòng: dòng khớp đầu tiên được áp dụng → chỉ đúng một dòng khớp', column: 'Thêm cột kết quả: Cap', lookalike: 'Ký tự dễ nhầm: О U+041E', count: '1 ô đã sửa có ký tự dễ nhầm', scheduled: 'Có hiệu lực 01/01/2027 00:00 sau khi được phê duyệt.' },
} as const;

for (const { theme, locale } of REVIEW_SHOTS) {
  test(`CKP_002_a_long_diff_keeps_the_decision_pinned_and_the_last_row_readable_${theme}_${locale}`, async ({ page }) => {
    await page.setViewportSize(VIEWPORTS[0]);
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await openDiff(page, locale, 'domestic-transfer-fee', reviewDemo());
    const table = page.getByRole('table', { name: REVIEW_TEXT[locale].long });
    await expect(table).toBeVisible();
    await expect(table.locator('tbody tr')).toHaveCount(21);
    const dock = page.locator('.decision--dock');
    const approve = page.getByRole('button', { name: TEXT[locale].approve, exact: true });
    // At the top the diff runs on under the dock: the dock is pinned to the viewport, Approve in view.
    const top = await dock.boundingBox();
    const tableBox = await table.boundingBox();
    expect((top?.y ?? 0) + (top?.height ?? 0)).toBeLessThanOrEqual(900);
    expect((tableBox?.y ?? 0) + (tableBox?.height ?? 0)).toBeGreaterThan(top?.y ?? 0);
    await expect(approve).toBeInViewport();
    // Scrolled to where the table ends: the dock is still pinned and the last row sits fully above it.
    await page.evaluate(() => { window.scrollTo(0, document.documentElement.scrollHeight); });
    const end = await page.evaluate(() => window.scrollY);
    await page.evaluate((y) => { window.scrollTo(0, y - 28); }, end);
    const last = await rowOf(page, 'R20').boundingBox();
    const pinned = await dock.boundingBox();
    expect(Math.round((pinned?.y ?? 0) + (pinned?.height ?? 0))).toBe(900 - 12);
    expect((last?.y ?? 0) + (last?.height ?? 0)).toBeLessThanOrEqual(pinned?.y ?? 0);
    await expect(rowOf(page, 'R20').locator('.diff-td--mod')).toContainText(locale === 'en' ? '12,000 VND' : '12.000 VND');
    await settle(page);
    await page.screenshot({ path: `e2e/screenshots/version-diff-long-1440x900-${theme}-${locale}.png`, fullPage: false });
    await check(page);
  });

  test(`CKP_002_hit_policy_column_and_look_alike_changes_read_in_words_${theme}_${locale}`, async ({ page }) => {
    await page.setViewportSize(VIEWPORTS[0]);
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
    await openDiff(page, locale, 'card-cashback', reviewDemo());
    const text = REVIEW_TEXT[locale];
    await expect(page.getByRole('table', { name: text.structure })).toBeVisible();
    const notes = page.locator('.changes__notes li');
    await expect(notes).toHaveText([text.policy, text.column]);
    await expect(rowOf(page, 'R2')).toContainText(text.lookalike);
    await expect(page.getByText(text.count)).toBeVisible();
    // The only hint under the decision is the scheduled date.
    await expect(page.locator('.decision--dock .dock__hint')).toHaveText([text.scheduled]);
    await expect(page.locator('main')).not.toContainText(/ASCII/);
    const approve = await page.getByRole('button', { name: TEXT[locale].approve, exact: true }).boundingBox();
    expect((approve?.y ?? 0) + (approve?.height ?? 0)).toBeLessThanOrEqual(900);
    await settle(page);
    await page.screenshot({ path: `e2e/screenshots/version-diff-structure-1440x900-${theme}-${locale}.png`, fullPage: false });
    await check(page);
  });
}

for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      const file = `version-diff-${viewport.width}x${viewport.height}-${theme}-${locale}.png`;
      test(`CKP_002_visual_${viewport.width}x${viewport.height}_${theme}_${locale}`, async ({ page }) => {
        const violations = cspViolations(page);
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await openDiff(page, locale);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await expect(page.getByRole('table', { name: TEXT[locale].table })).toBeVisible();
        const approve = await page.getByRole('button', { name: TEXT[locale].approve, exact: true }).boundingBox();
        expect(approve, 'Approve is rendered').not.toBeNull();
        // Above the fold at both heights, and a 44 px target.
        expect((approve?.y ?? 0) + (approve?.height ?? 0)).toBeLessThanOrEqual(viewport.height);
        expect(approve?.height ?? 0).toBeGreaterThanOrEqual(44);
        // Both sides fit the width: the diff region does not scroll sideways at 1440 px.
        const region = page.getByRole('region', { name: TEXT[locale].table });
        const widths = await region.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
        expect(widths.scroll).toBeLessThanOrEqual(widths.client + 1);
        // No raw expressions, no unformatted amounts and no spec ids on screen.
        const main = page.locator('main');
        await expect(main).not.toContainText(/CK-F-|CKP-0|PD-0|RLE-0/);
        await expect(page.getByRole('table', { name: TEXT[locale].table }).locator('tbody')).not.toContainText(/==|>=|\d{5,}/);
        await page.evaluate(async () => { await document.fonts.ready; });
        await page.screenshot({ path: `e2e/screenshots/${file}`, fullPage: false });
        expect(violations).toEqual([]);
        const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
        expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
      });
    }
  }
}
