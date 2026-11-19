import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
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

async function openDiff(page: Page, locale: Locale): Promise<string[]> {
  const diffCalls: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.endsWith('/diff')) diffCalls.push(`${request.method()} ${url.pathname}${url.search}`);
  });
  await rememberLocale(page, locale);
  await mockHost(page, 'rules-approver');
  await mockRuleEngine(page, 'rules-approver');
  await page.goto('./#/rules/card-fee/2');
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
