import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { AuditOptions } from '../src/mocks/auditEngine';
import type { Scenario } from '../src/mocks/fixtures';
import { auditSeed, demoSeed, type StoredVersion } from '../src/mocks/ruleEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * The audit screens (CKP-071 to CKP-075, CKP-005), in the order of the journey: sign-in, Rules, a rule set's
 * history and evidence export, then Decisions. The decision explorer, one decision with its explanation and
 * masked inputs, a rule set's history, and the evidence export with its result, limits and refusals. Each state is
 * checked at 1440x900 and 1440x1200, light and dark, English and Vietnamese: the element the user acts on or reads
 * first is fully above the fold, axe-core finds nothing, and no raw JSON, stored hash or spec id shows.
 * Screenshots go to AUDIT_SCREENSHOTS (default e2e/screenshots/, which git ignores).
 */

const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
const SHOTS = process.env['AUDIT_SCREENSHOTS'] ?? 'e2e/screenshots';
const NAMED = '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9';
/** The month pickers start at the current month; the demo decision log is of November 2026. */
const NOW = new Date('2026-11-21T03:00:00Z');

const T = {
  en: {
    show: 'Show decisions', export: 'Export evidence', inputs: 'Inputs', v1: 'Version 1 · Retired', v2: 'Version 2 · Published', month: 'Month of the decisions',
    chooseFirst: 'Choose first 100', exported: 'Evidence exported', copySha: 'Copy SHA-256',
  },
  vi: {
    show: 'Xem quyết định', export: 'Xuất bằng chứng', inputs: 'Đầu vào', v1: 'Phiên bản 1 · Đã ngừng dùng', v2: 'Phiên bản 2 · Đã xuất bản', month: 'Tháng của các quyết định',
    chooseFirst: 'Chọn 100 quyết định đầu', exported: 'Đã xuất bằng chứng', copySha: 'Sao chép SHA-256',
  },
} as const;

/** Problem answers for the refusal states. */
const problem = (status: number, code: string, detail: string) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ type: 'about:blank', title: 'Refused', status, code, detail, correlationId: '01JFAKE0000000000000000009' }),
});

async function chooseNamedDecisions(page: Page, locale: Locale) {
  await page.getByLabel(T[locale].month).selectOption('2026-11');
  for (const short of ['01KF2Q…P7T9', '01KF2Q…P7TA', '01KF2Q…P7TB']) {
    await page.locator('.evidence__decision', { hasText: short }).getByRole('checkbox').check();
  }
}

interface Screen {
  name: string;
  scenario: Scenario;
  /** The screen before anyone is signed in: no session header. */
  signedOut?: boolean;
  hash: string;
  seed?: StoredVersion[];
  audit?: AuditOptions;
  /** Routes that answer before the fake engine, for refusals the fake engine does not produce. */
  override?: (page: Page) => Promise<void>;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  /** What must be on screen and fully above the fold once the state is reached. */
  focus: (page: Page, locale: Locale) => Locator;
  /** The action that must stay above the fold beside the focal element, such as Export beside its refusal. */
  action?: (page: Page, locale: Locale) => Locator;
}

const SCREENS: readonly Screen[] = [
  { name: 'journey-1-sign-in', scenario: 'signed-out', signedOut: true, hash: '#/', focus: (page, l) => page.getByRole('button', { name: l === 'en' ? 'Sign in again' : 'Đăng nhập lại' }) },
  { name: 'journey-2-rules', scenario: 'rules-auditor', hash: '#/rules', focus: (page) => page.getByRole('link', { name: /card-fee/ }).first() },
  { name: 'explorer-start', scenario: 'rules-auditor', hash: '#/decisions', focus: (page, l) => page.getByRole('button', { name: T[l].show }) },
  { name: 'explorer-list', scenario: 'rules-auditor', hash: '#/decisions?ruleSet=card-fee&month=2026-11', focus: (page) => page.locator('.dlog-table tbody tr').first() },
  { name: 'explorer-none', scenario: 'rules-auditor', hash: '#/decisions?ruleSet=kyc-risk&month=2026-10', focus: (page) => page.locator('.dlog-results .empty-state') },
  { name: 'explorer-case', scenario: 'rules-auditor', hash: '#/decisions?caseRef=LN-2026-0042&month=2026-11', focus: (page) => page.locator('.dlog-table tbody tr').nth(1) },
  { name: 'explorer-hidden', scenario: 'rules-author', hash: '#/decisions', focus: (page) => page.locator('main .empty-state') },
  {
    name: 'explorer-forbidden', scenario: 'rules-auditor', hash: '#/decisions?ruleSet=card-fee&month=2026-11',
    override: async (page) => { await page.route('**/api/bpm/rules/v1/decisions?**', (route) => route.fulfill(problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:log:read'))); },
    focus: (page) => page.locator('.audit-error'),
  },
  { name: 'decision-why', scenario: 'rules-auditor', hash: `#/decisions/${NAMED}`, focus: (page) => page.locator('.why__text') },
  {
    name: 'decision-inputs-masked', scenario: 'rules-auditor', hash: `#/decisions/${NAMED}`,
    prepare: async (page, l) => { await page.getByRole('tab', { name: T[l].inputs }).click(); },
    focus: (page) => page.locator('.dlog-inputs .masked').first(),
  },
  { name: 'decision-failed', scenario: 'rules-auditor', hash: '#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7TC', focus: (page) => page.locator('.why--failed .why__text') },
  { name: 'decision-not-found', scenario: 'rules-auditor', hash: '#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7ZZ', focus: (page) => page.locator('.audit-error') },
  {
    name: 'decision-unprocessable', scenario: 'rules-auditor', hash: `#/decisions/${NAMED}`,
    override: async (page) => { await page.route('**/explain?**', (route) => route.fulfill(problem(422, 'VALIDATION_FAILED', 'The stored version cannot be read'))); },
    focus: (page) => page.locator('.audit-error'),
  },
  { name: 'history-export', scenario: 'rules-auditor', hash: '#/rules/card-fee/2/history', seed: auditSeed(), focus: (page, l) => page.getByRole('button', { name: T[l].export, exact: true }) },
  {
    name: 'export-done', scenario: 'rules-auditor', hash: '#/rules/card-fee/2/history', seed: auditSeed(),
    prepare: async (page, l) => {
      await page.getByRole('checkbox', { name: T[l].v1 }).check();
      await page.getByRole('checkbox', { name: T[l].v2 }).check();
      await chooseNamedDecisions(page, l);
      await page.getByRole('button', { name: T[l].export, exact: true }).click();
    },
    focus: (page, l) => page.getByRole('region', { name: T[l].exported }).getByRole('button', { name: T[l].copySha }),
  },
  {
    name: 'export-limit', scenario: 'rules-auditor', hash: '#/rules/card-fee/2/history', seed: auditSeed(),
    prepare: async (page, l) => { await page.getByLabel(T[l].month).selectOption('2026-11'); await page.getByRole('button', { name: T[l].chooseFirst }).click(); },
    focus: (page, l) => page.getByRole('button', { name: T[l].export, exact: true }),
  },
  {
    name: 'export-too-large', scenario: 'rules-auditor', hash: '#/rules/card-fee/2/history', seed: auditSeed(), audit: { evidenceLimit: 200 },
    prepare: async (page, l) => { await page.getByRole('checkbox', { name: T[l].v2 }).check(); await page.getByRole('button', { name: T[l].export, exact: true }).click(); },
    focus: (page) => page.locator('.evidence .audit-error'),
    action: (page, l) => page.getByRole('button', { name: T[l].export, exact: true }),
  },
  {
    name: 'export-forbidden', scenario: 'rules-auditor', hash: '#/rules/card-fee/2/history', seed: auditSeed(),
    override: async (page) => { await page.route('**/rule-sets/card-fee/evidence', (route) => route.fulfill(problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:log:read'))); },
    prepare: async (page, l) => { await page.getByRole('checkbox', { name: T[l].v2 }).check(); await page.getByRole('button', { name: T[l].export, exact: true }).click(); },
    focus: (page) => page.locator('.evidence .audit-error'),
    action: (page, l) => page.getByRole('button', { name: T[l].export, exact: true }),
  },
  { name: 'export-no-log', scenario: 'rules-auditor-no-log', hash: '#/rules/card-fee/2/history', seed: auditSeed(), focus: (page) => page.locator('.evidence__locked') },
];

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `audit-${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`CKP_071_075_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.clock.setFixedTime(NOW);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockHost(page, screen.scenario);
          await mockRuleEngine(page, screen.scenario, screen.seed ?? demoSeed(), screen.audit ?? {});
          await screen.override?.(page);
          await page.goto(`./${screen.hash}`);
          await expect(screen.signedOut === true ? page.getByRole('alert') : page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await screen.prepare?.(page, locale);
          const focus = screen.focus(page, locale);
          await expect(focus).toBeVisible();
          const box = await focus.boundingBox();
          expect(box, 'the focal element is rendered').not.toBeNull();
          expect((box?.y ?? 0) + (box?.height ?? 0), 'visible without scrolling').toBeLessThanOrEqual(viewport.height);
          if (screen.action !== undefined) {
            const action = await screen.action(page, locale).boundingBox();
            expect((action?.y ?? viewport.height) + (action?.height ?? 0), 'the action stays above the fold').toBeLessThanOrEqual(viewport.height);
          }
          const main = page.locator(screen.signedOut === true ? 'body' : 'main');
          await expect(main).not.toContainText(/CKP-0|PD-0|RLE-0|hmac:|\{"/);
          await page.evaluate(async () => { await document.fonts.ready; });
          await page.screenshot({ path: `${SHOTS}/${file}`, fullPage: false });
          expect(violations).toEqual([]);
          const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
          expect(axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).slice(0, 3).join(' | ')}`)).toEqual([]);
        });
      }
    }
  }
}

test('CKP_071_an_auditor_finds_a_decision_by_month_and_reads_why', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'rules-auditor');
  await mockRuleEngine(page, 'rules-auditor');
  await page.goto('./#/decisions');
  await page.getByRole('combobox', { name: 'Month', exact: true }).selectOption('2026-11');
  await page.getByRole('button', { name: 'Show decisions' }).click();
  await expect(page.getByRole('heading', { name: 'card-fee, November 2026' })).toBeVisible();
  await page.getByRole('link', { name: /^Open decision 01KF2Q…P7T9/ }).click();
  await expect(page.locator('.why__text')).toHaveText(/^The card fee is waived because a Gold customer keeps a balance of 60,000,000 VND/);
  await page.getByRole('tab', { name: 'Inputs' }).click();
  await expect(page.getByRole('row', { name: /National id/ })).toContainText('Masked');
  // The breadcrumb leads back to the same search.
  await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('link', { name: 'Decisions' }).click();
  await expect(page).toHaveURL(/#\/decisions\?ruleSet=card-fee&month=2026-11$/);
});

test('CKP_074_the_saved_file_is_the_response_bytes_and_states_the_sha256_shown', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'rules-auditor');
  await mockRuleEngine(page, 'rules-auditor', auditSeed());
  await page.goto('./#/rules/card-fee/2/history');
  await page.getByRole('checkbox', { name: T.en.v1 }).check();
  await page.getByRole('checkbox', { name: T.en.v2 }).check();
  await chooseNamedDecisions(page, 'en');
  const posted = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith('/rule-sets/card-fee/evidence'));
  const download = page.waitForEvent('download');
  // Keyboard path: focus Export and press Enter.
  await page.getByRole('button', { name: 'Export evidence', exact: true }).focus();
  await page.keyboard.press('Enter');
  const response = await posted;
  expect(response.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  const saved = await download;
  const bytes = await response.body();
  const path = await saved.path();
  const { readFileSync } = await import('node:fs');
  expect(readFileSync(path).equals(bytes)).toBe(true);
  const sha = response.headers()['x-evidence-sha256'] ?? '';
  expect(bytes.toString('utf8')).toContain(`# Content SHA-256: ${sha}`);
  await expect(page.getByTestId('evidence-sha')).toHaveText(sha);
  await expect(page.getByText('Evidence exported by Phạm Thu Hà: 2 versions, 3 decisions')).toBeVisible();
});
