// Checks from the QA review of the rule screens, kept as regression tests.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { en } from '../src/i18n/en';
import { vi } from '../src/i18n/vi';
import type { Scenario } from '../src/mocks/fixtures';
import { mockHost, mockRuleEngine, rememberLocale } from './mockHost';
import { scenarioPermissions, scenarioUser } from '../src/mocks/fixtures';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';

type Locale = 'en' | 'vi';

async function open(page: Page, scenario: Scenario, hash: string, locale: Locale = 'en', theme: 'light' | 'dark' = 'light', height = 900) {
  await page.setViewportSize({ width: 1440, height });
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  await mockHost(page, scenario);
  await mockRuleEngine(page, scenario);
  await page.goto(`./${hash}`);
  await expect(page.getByTestId('header-user')).toBeVisible();
}

const SCREENS: { name: string; scenario: Scenario; hash: string; prepare?: (p: Page, l: Locale) => Promise<void> }[] = [
  { name: 'list', scenario: 'rules-author', hash: '#/rules' },
  { name: 'table', scenario: 'rules-author', hash: '#/rules/card-fee/1' },
  { name: 'editor-error', scenario: 'rules-author', hash: '#/rules/card-fee/1', prepare: async (p, l) => {
    await p.getByRole('button', { name: l === 'en' ? 'New draft' : 'Tạo bản nháp' }).click();
    await p.getByRole('button', { name: l === 'en' ? 'Actions for row R2' : 'Thao tác cho dòng R2' }).click();
    await p.getByRole('menuitem', { name: l === 'en' ? 'Duplicate row R2' : 'Nhân bản dòng R2' }).click();
    await p.getByLabel(l === 'en' ? 'Row R6, Balance' : 'Dòng R6, Balance', { exact: true }).fill('');
    await p.getByRole('button', { name: l === 'en' ? 'Save draft' : 'Lưu bản nháp' }).click();
    await expect(p.locator('.cell-edit--bad').first()).toBeVisible();
  } },
  { name: 'requester-pending', scenario: 'rules-author', hash: '#/rules/card-fee/2' },
  { name: 'approver-changes', scenario: 'rules-approver', hash: '#/rules/card-fee/2' },
  { name: 'source', scenario: 'rules-author', hash: '#/rules/card-fee/1/source' },
];

for (const s of SCREENS) {
  for (const [locale, theme] of [['en', 'light'], ['vi', 'dark'], ['en', 'dark'], ['vi', 'light']] as const) {
    test(`QA_a11y_lang_targets_${s.name}_${locale}_${theme}`, async ({ page }) => {
      await open(page, s.scenario, s.hash, locale, theme);
      await page.waitForLoadState('networkidle');
      await s.prepare?.(page, locale);
      await page.waitForTimeout(150);

      // axe-core, WCAG 2.2 AA tags
      const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const summary = axe.violations.map((v) => `${v.id} (${v.impact}) x${v.nodes.length}: ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`);

      // targets: own box or the .hit ::after box, whichever is larger
      const small = await page.evaluate(() => {
        const out: string[] = [];
        for (const el of Array.from(document.querySelectorAll<HTMLElement>('main button, main a[href], main select, main input:not([type=file]):not([tabindex="-1"]), main summary, main textarea'))) {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) continue;
          // Recorded exemption (rules.css): the editor's cell controls draw at 36 px in a dense grid.
          if (el.closest('.cell-edit')) continue;
          const after = getComputedStyle(el, '::after');
          const aw = after.content !== 'none' ? parseFloat(after.width) || 0 : 0;
          const ah = after.content !== 'none' ? parseFloat(after.height) || 0 : 0;
          const w = Math.max(r.width, aw); const h = Math.max(r.height, ah);
          if (w < 44 || h < 44) out.push(`${el.tagName.toLowerCase()}.${el.className.split(' ').at(0) ?? ''} "${(el.getAttribute('aria-label') ?? el.textContent).trim().slice(0, 24)}" ${Math.round(w)}x${Math.round(h)}`);
        }
        return Array.from(new Set(out));
      });
      // Editor cell inputs are the recorded exemption (rules.css): 36 px in a dense grid.
      expect(small).toEqual([]);

      const main = (await page.locator('main').innerText());
      // no raw JSON outside the deliberate Source view
      if (s.name !== 'source') expect(main, 'no raw JSON').not.toMatch(/[{[]\s*"[A-Za-z]+"\s*:/);
      expect(main).not.toMatch(/CK-F-|CKP-0|PD-0|RLE-0/);

      // one language: on a Vietnamese screen, no English UI string that differs from its Vietnamese one
      if (locale === 'vi') {
        const leaks = (Object.keys(en) as (keyof typeof en)[])
          .filter((k) => en[k] !== vi[k] && !en[k].includes('{') && en[k].length >= 5 && main.includes(en[k]))
          .map((k) => `${k}="${en[k]}"`);
        expect(leaks).toEqual([]);
      }
      expect(summary).toEqual([]);
    });
  }
}

test('QA_fold_primary_at_1440x1200_and_900_for_approver', async ({ page }) => {
  for (const height of [900, 1200]) {
    await open(page, 'rules-approver', '#/rules/card-fee/2', 'vi', 'dark', height);
    for (const name of ['Phê duyệt và xuất bản', 'Từ chối']) {
      const box = await page.getByRole('button', { name, exact: true }).boundingBox();
      expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(height);
    }
  }
});

test('QA_413_on_reject_shows_the_plain_message_and_code', async ({ page }) => {
  await open(page, 'rules-approver', '#/rules/card-fee/2');
  await page.getByLabel(/Reason|Comment/).first().fill('x'.repeat(20_000));
  await page.getByRole('button', { name: 'Reject', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('The text is too long for the service.');
  await expect(page.getByTestId('problem-code')).toHaveText('PAYLOAD_TOO_LARGE');
});

test('QA_413_on_save_draft_keeps_the_edits', async ({ page }) => {
  await open(page, 'rules-author', '#/rules/card-fee/1');
  await page.route('**/api/bpm/rules/v1/rule-sets/card-fee/versions', async (route) => {
    if (route.request().method() !== 'POST') { await route.fallback(); return; }
    await route.fulfill({ status: 413, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Content Too Large', status: 413, code: 'PAYLOAD_TOO_LARGE', detail: 'The document is larger than 1048576 bytes', correlationId: '01JQA413' }) });
  });
  await page.getByRole('button', { name: 'New draft' }).click();
  await page.getByLabel('Row R3, Fee').fill('35000');
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('alert').first()).toContainText('The text is too long for the service.');
  await expect(page.getByTestId('problem-code')).toHaveText('PAYLOAD_TOO_LARGE');
  await expect(page.getByLabel('Row R3, Fee')).toHaveValue('35,000');
  await expect(page.getByRole('button', { name: 'Save draft' })).toBeVisible();
});

test('QA_query_invalid_on_show_more_offers_restart_and_recovers', async ({ page }) => {
  await open(page, 'rules-author', '#/rules');
  await page.unroute('**/api/bpm/rules/v1/**');
  let firstPages = 0;
  await page.route('**/api/bpm/rules/v1/rule-sets?**', async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.has('cursor')) {
      await route.fulfill({ status: 400, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Bad Request', status: 400, code: 'QUERY_INVALID', detail: 'The cursor was not issued by this endpoint', correlationId: '01JQA' }) });
      return;
    }
    firstPages += 1;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [{ ruleSet: 'card-fee', latestVersion: 2, latestStatus: 'PENDING_APPROVAL', publishedVersion: 1, lastChangedAt: '2026-11-18T09:00:00Z' }], nextCursor: 'stale', hasNext: true }) });
  });
  await page.reload();
  await expect(page.getByTestId('header-user')).toBeVisible();
  await page.getByRole('button', { name: 'Show more' }).click();
  await expect(page.getByRole('alert')).toContainText('The list changed while you were reading it.');
  await page.getByRole('button', { name: 'Start the list again' }).click();
  await expect(page.getByRole('link', { name: 'card-fee' })).toBeVisible();
  expect(firstPages).toBe(2);
});

test('QA_double_click_request_publish_sends_one_request', async ({ page }) => {
  await open(page, 'rules-author', '#/rules/card-fee/1');
  // make a draft first
  await page.getByRole('button', { name: 'New draft' }).click();
  await page.getByRole('button', { name: 'Actions for row R2' }).click();
  await page.getByRole('menuitem', { name: 'Duplicate row R2' }).click();
  await page.getByRole('button', { name: 'Save draft' }).click();
  await expect(page.getByRole('button', { name: 'Request publish' })).toBeVisible();
  const posts: string[] = [];
  page.on('request', (r) => { if (r.method() === 'POST') posts.push(`${new URL(r.url()).pathname} ${r.headers()['idempotency-key'] ?? ''}`); });
  await page.getByRole('button', { name: 'Request publish' }).dblclick();
  await expect(page.locator('.decision')).toBeVisible();
  expect(posts.filter((p) => p.includes(':request-publish'))).toHaveLength(1);
});

test('QA_allowed_actions_reject_only_for_a_non_author_approver', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await rememberLocale(page, 'en');
  await mockHost(page, 'rules-approver');
  const engine = createFakeEngine(demoSeed());
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser('rules-approver'), permissions: scenarioPermissions('rules-approver') });
    let body = answer.body;
    if (request.method() === 'GET' && /\/versions\/2(\?.*)?$/.test(request.url())) body = JSON.stringify({ ...JSON.parse(body), allowedActions: ['reject'] });
    await route.fulfill({ status: answer.status, headers: answer.headers, body });
  });
  await page.goto('./#/rules/card-fee/2');
  await expect(page.getByTestId('header-user')).toBeVisible();
  const approve = page.getByRole('button', { name: 'Approve and publish' });
  await expect(approve).toBeDisabled();
  await expect(approve).toHaveAccessibleDescription(/Approve is not available to you for this version\./);
});

test('QA_reject_reason_is_required_and_shows_in_history', async ({ page }) => {
  await open(page, 'rules-approver', '#/rules/card-fee/2');
  const reject = page.getByRole('button', { name: 'Reject', exact: true });
  await expect(reject).toBeDisabled();
  await page.getByLabel(/Reason|Comment/).first().fill('   ');
  await expect(reject).toBeDisabled();
  await page.getByLabel(/Reason|Comment/).first().fill('Fee for R4 should stay 15,000 VND');
  await reject.click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(/Draft/);
  await page.getByRole('tab', { name: 'History' }).click();
  await expect(page.locator('.history__reason')).toContainText('Fee for R4 should stay 15,000 VND');
});

test('QA_CKP_030_import_next_version_of_existing_rule_set_from_its_page', async ({ page }) => {
  await open(page, 'rules-author', '#/rules/card-fee/1');
  const posts: string[] = [];
  page.on('response', (r) => { if (r.request().method() === 'POST') posts.push(`${new URL(r.url()).pathname} -> ${String(r.status())}`); });
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: 'More' }).click();
  await page.getByRole('menuitem', { name: 'Import new version' }).click();
  const document = { schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }],
    rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }] };
  await (await chooser).setFiles({ name: 'card-fee-v3.yaml', mimeType: 'application/yaml', buffer: Buffer.from(JSON.stringify(document)) });
  await expect(page.locator('.rs-head__sub')).toHaveText(/^Version 3 · draft/);
  await expect(page.getByText('Draft version 3 created')).toBeVisible();
  expect(posts).toEqual(['/api/bpm/rules/v1/rule-sets/card-fee/versions -> 201']);
});

test('QA_import_twice_in_one_session', async ({ page }) => {
  await open(page, 'rules-author', '#/rules');
  const doc = (n: string) => JSON.stringify({ schemaVersion: 1, ruleSet: n, hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }], rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }] });
  for (const n of ['qa-one', 'qa-two']) {
    const chooser = page.waitForEvent('filechooser', { timeout: 5000 });
    await page.getByRole('button', { name: 'Import file' }).click();
    await (await chooser).setFiles({ name: `${n}.yaml`, mimeType: 'application/yaml', buffer: Buffer.from(doc(n)) });
    await expect(page.getByRole('heading', { level: 1 })).toContainText(n);
    await expect(page.locator('.rs-head__sub')).toHaveText(/^Version 1 · draft/);
    await page.getByRole('link', { name: 'All rule sets' }).click();
    await expect(page.getByRole('button', { name: 'Import file' })).toBeEnabled();
  }
});

for (const [name, scenario, hash] of [['table', 'rules-author', '#/rules/card-fee/1'], ['approver', 'rules-approver', '#/rules/card-fee/2'], ['list', 'ready', '#/rules']] as const) {
  test(`QA_contrast_light_${name}`, async ({ page }) => {
    await open(page, scenario, hash, 'en', 'light');
    await page.waitForLoadState('networkidle');
    const axe = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
    expect(axe.violations.flatMap((v) => v.nodes.map((n) => `${n.target.join(' ')} :: ${n.any[0]?.message ?? ''}`))).toEqual([]);
  });
}
