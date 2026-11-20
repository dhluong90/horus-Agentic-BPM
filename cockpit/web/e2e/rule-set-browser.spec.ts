import { readFile } from 'node:fs/promises';
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { canonicalJson, demoSeed } from '../src/mocks/ruleEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

// CKP-030: an author lists rule sets, reads a version as a table, and imports or exports it.
// Run at 1440x900 and 1440x1200, in English and Vietnamese; the list and the Import and Export actions are above the fold.
const VIEWPORTS = [
  { width: 1440, height: 900 },
  { width: 1440, height: 1200 },
] as const;

const WORDS = {
  en: { importSet: 'Import rule set', importVersion: 'Import new version', export: 'Export', json: 'As JSON', dmn: 'As DMN decision table', created: 'Draft version 3 created', saved: 'Saved card-fee-v1.json', refused: 'Import refused. Nothing was saved.', located: 'card-fee-bad.yaml, row R4, column Balance', reason: 'compares a money amount with a plain number', importedBy: 'Imported by lan from card-fee-v3.yaml', versions: 'Versions' },
  vi: { importSet: 'Nhập bộ quy tắc', importVersion: 'Nhập phiên bản mới', export: 'Xuất tệp', json: 'Dạng JSON', dmn: 'Dạng bảng quyết định DMN', created: 'Đã tạo bản nháp phiên bản 3', saved: 'Đã lưu card-fee-v1.json', refused: 'Đã từ chối nhập tệp. Không có thay đổi nào được lưu.', located: 'card-fee-bad.yaml, dòng R4, cột Balance', reason: 'so sánh số tiền với một số thường', importedBy: 'lan nhập từ card-fee-v3.yaml', versions: 'Phiên bản' },
} as const;
type Locale = keyof typeof WORDS;

const document3 = (balance: string) => JSON.stringify({
  schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }, { name: 'balance', type: 'money' }], outputs: [{ name: 'fee', type: 'money' }],
  rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }, { id: 'R4', when: { balance: `>= ${balance}` }, then: { fee: '1000 VND' } }],
});

async function aboveTheFold(target: Locator, height: number) {
  const box = await target.boundingBox();
  expect(box, 'rendered').not.toBeNull();
  expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(height);
}

async function shoot(page: Page, name: string) {
  await page.evaluate(async () => { await document.fonts.ready; });
  await page.screenshot({ path: `e2e/screenshots/${name}.png`, fullPage: false });
}

async function axe(page: Page) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  expect(result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
}

async function pick(page: Page, button: string, name: string, text: string) {
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: button, exact: true }).click();
  await (await chooser).setFiles({ name, mimeType: 'application/yaml', buffer: Buffer.from(text) });
}

for (const viewport of VIEWPORTS) {
  for (const locale of ['en', 'vi'] as const satisfies readonly Locale[]) {
    const size = `${String(viewport.width)}x${String(viewport.height)}`;
    test(`CKP_030_rule_set_browser_lists_reads_imports_and_exports_${size}_${locale}`, async ({ page }) => {
      const w = WORDS[locale];
      const violations = cspViolations(page);
      const traffic: string[] = [];
      const keys: string[] = [];
      page.on('request', (r) => {
        const url = new URL(r.url());
        if (!url.pathname.startsWith('/api/bpm/rules')) return;
        traffic.push(`${r.method()} ${url.pathname.replace('/api/bpm/rules/v1', '')}${url.search}`);
        const key = r.headers()['idempotency-key'];
        if (key !== undefined) keys.push(key);
      });
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
      await rememberLocale(page, locale);
      await mockHost(page, 'rules-author');
      await mockRuleEngine(page, 'rules-author');

      // The list: both rule sets and the Import action above the fold.
      await page.goto('./#/rules');
      const table = page.getByRole('table');
      await expect(table.getByRole('row')).toHaveCount(3);
      await aboveTheFold(table.getByRole('row').nth(2), viewport.height);
      await aboveTheFold(page.getByRole('button', { name: w.importSet, exact: true }), viewport.height);
      await expect(page.locator('main')).not.toContainText(/CK-F-|CKP-0|PD-0|schemaVersion/);
      await shoot(page, `ckp030-list-${size}-${locale}`);
      await axe(page);

      // Open card-fee version 1: the rule table, no YAML or JSON, Import and Export above the fold.
      await table.getByRole('link', { name: 'card-fee' }).click();
      await page.getByLabel(w.versions, { exact: true }).selectOption('1');
      await expect(page.locator('.rs-head__sub')).toHaveText(/1 ·/);
      await expect(page.getByText(/^50[.,]000[.,]000 VND$/).first()).toBeVisible();
      await expect(page.locator('main')).not.toContainText(/schemaVersion|"ruleSet"/);
      await aboveTheFold(page.getByRole('button', { name: w.importVersion, exact: true }), viewport.height);
      await aboveTheFold(page.getByRole('button', { name: w.export, exact: true }), viewport.height);
      await page.getByRole('button', { name: w.export, exact: true }).click();
      await expect(page.getByRole('menuitem', { name: w.dmn })).toBeVisible();
      await shoot(page, `ckp030-version-export-menu-${size}-${locale}`);

      // Export as JSON: one GET; the saved file is card-fee-v1.json with the engine's bytes.
      const download = page.waitForEvent('download');
      await page.getByRole('menuitem', { name: w.json }).click();
      const saved = await download;
      expect(saved.suggestedFilename()).toBe('card-fee-v1.json');
      const v1 = demoSeed().find((v) => v.ruleSet === 'card-fee' && v.version === 1);
      expect(await readFile(await saved.path(), 'utf8')).toBe(canonicalJson(v1?.definition as never));
      await expect(page.getByText(w.saved)).toBeVisible();
      expect(traffic.filter((t) => t.includes('/export'))).toEqual(['GET /rule-sets/card-fee/versions/1/export?format=json']);
      await axe(page);

      // Import card-fee-v3.yaml: one POST with a key, the message after the 201, and version 3 opened.
      await pick(page, w.importVersion, 'card-fee-v3.yaml', document3('50000000 VND'));
      await expect(page.getByText(w.created)).toBeVisible();
      await expect(page.locator('.rs-head__sub')).toHaveText(/3 ·/);
      expect(traffic.filter((t) => t.startsWith('POST'))).toEqual(['POST /rule-sets/card-fee/versions']);
      expect(keys).toHaveLength(1);
      await expect(page.getByText(w.importedBy)).toBeVisible();
      await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
      // The pointer leaves the button, as it does once the user reads the result.
      await page.mouse.move(viewport.width / 2, viewport.height - 40);
      await shoot(page, `ckp030-imported-v3-${size}-${locale}`);

      // card-fee-bad.yaml: refused whole, located by row and column, nothing created.
      await pick(page, w.importVersion, 'card-fee-bad.yaml', document3('50000000'));
      await expect(page.getByText(w.refused)).toBeVisible();
      await expect(page.getByText(w.located)).toBeVisible();
      await expect(page.getByText(w.reason)).toBeVisible();
      await aboveTheFold(page.getByText(w.reason), viewport.height);
      await expect(page.locator('main')).not.toContainText(/engine|Engine/);
      await expect(page.getByLabel(w.versions, { exact: true }).locator('option')).toHaveCount(3);
      await shoot(page, `ckp030-import-refused-${size}-${locale}`);
      await axe(page);
      expect(violations).toEqual([]);
    });
  }

  test(`CKP_030_a_reader_sees_export_and_no_import_${String(viewport.width)}x${String(viewport.height)}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockHost(page, 'rules-reader');
    await mockRuleEngine(page, 'rules-reader');
    await page.goto('./#/rules/kyc-risk/1');
    await expect(page.getByRole('columnheader', { name: 'PEP' })).toBeVisible();
    await expect(page.getByText('VN', { exact: true })).toBeVisible();
    await expect(page.locator('.navrail__count')).toHaveCount(0);
    await aboveTheFold(page.getByRole('button', { name: 'Export', exact: true }), viewport.height);
    await expect(page.getByRole('button', { name: 'Import new version' })).toHaveCount(0);
    await shoot(page, `ckp030-reader-${String(viewport.width)}x${String(viewport.height)}-en`);
    await page.goto('./#/rules');
    await expect(page.getByRole('table').getByRole('row')).toHaveCount(3);
    await expect(page.getByRole('button', { name: 'Import rule set' })).toHaveCount(0);
  });
}
