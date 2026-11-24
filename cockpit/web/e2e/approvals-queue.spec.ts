import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page, type Route } from '@playwright/test';
import { scenarioPermissions, scenarioUser, type Scenario } from '../src/mocks/fixtures';
import { KYC_V3_HASH } from '../src/mocks/processDemo';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * The approvals queue across both engines (#31, CKP-003), in the order of the journey: sign-in, Home, Approvals,
 * then the existing review screens the queue links to. Every state is checked at 1440x900 and 1440x1200, light and
 * dark, English and Vietnamese: the focal element and the primary action are above the fold, no sideways
 * scrolling, axe-core finds nothing, and no raw JSON, key, full hash, code or spec id shows. Screenshots go to
 * APPROVALS_SCREENSHOTS (default e2e/screenshots/, which git ignores).
 */

const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
const SHOTS = process.env['APPROVALS_SCREENSHOTS'] ?? 'e2e/screenshots';
const NOW = new Date('2026-11-21T03:00:00Z');

const T = {
  en: {
    signIn: 'Sign in again', menu: 'Cockpit menu', approvals: 'Approvals', reviewNext: 'Review next', review: 'Review', reviewAndApprove: 'Review and approve',
    approve: 'Approve', kyc: 'Customer onboarding (KYC) · Version 3', card: 'Card fee · Version 2', self: 'You made this change, so someone else approves it.',
    empty: 'Nothing is waiting for your review.', error: 'The service could not answer. Try again.', partial: 'Process changes could not be read. This list may be incomplete.',
    forbidden: 'You do not have access to approvals on this service.', decision: 'Your decision', ruleApprove: 'Approve and publish', processApprove: 'Approve change',
    showCurrent: 'Show the current changes',
    ruleChanged: 'The rules changed after you opened this version. Review it again before you decide.',
    processChanged: 'This version changed after you opened it. Check the changes again before you decide.',
    refused: 'You cannot approve a change you wrote or requested.', loading: 'Loading', waiting: '2 waiting',
  },
  vi: {
    signIn: 'Đăng nhập lại', menu: 'Menu Cockpit', approvals: 'Phê duyệt', reviewNext: 'Xem xét tiếp', review: 'Xem xét', reviewAndApprove: 'Xem xét và phê duyệt',
    approve: 'Phê duyệt', kyc: 'Mở khách hàng mới (KYC) · Phiên bản 3', card: 'Card fee · Phiên bản 2', self: 'Bạn đã tạo thay đổi này, nên người khác sẽ phê duyệt.',
    empty: 'Không có yêu cầu nào chờ bạn xem xét.', error: 'Dịch vụ chưa trả lời được. Hãy thử lại.', partial: 'Không đọc được thay đổi quy trình. Danh sách có thể chưa đủ.',
    forbidden: 'Bạn không có quyền xem phê duyệt trên dịch vụ này.', decision: 'Quyết định của bạn', ruleApprove: 'Phê duyệt và xuất bản', processApprove: 'Duyệt thay đổi',
    showCurrent: 'Hiện các thay đổi hiện tại',
    ruleChanged: 'Nội dung đã thay đổi sau khi bạn mở phiên bản này. Hãy xem lại trước khi quyết định.',
    processChanged: 'Phiên bản này đã thay đổi sau khi bạn mở. Hãy kiểm tra lại trước khi quyết định.',
    refused: 'Bạn không thể phê duyệt thay đổi do chính mình soạn hoặc gửi.', loading: 'Đang tải', waiting: '2 đang chờ',
  },
} as const;

/** The registry, /meta and /me of the scenario, both in-memory engines as its user. */
async function mockEngines(page: Page, scenario: Scenario, locale: Locale = 'en') {
  await mockHost(page, scenario);
  const rules = await mockRuleEngine(page, scenario);
  const processes = createFakeProcessEngine({ locale });
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = processes.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(scenario), permissions: scenarioPermissions(scenario) });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return { rules, processes };
}

const problem = (status: number, code: string) => ({ status, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: code, status, code, correlationId: '01JQUEUE000000000000000031' }) });
const answer = (route: Route, status: number, code: string) => route.fulfill(problem(status, code));
const preview = (page: Page, name: string) => page.getByRole('article', { name });
const card = (page: Page, name: string) => page.getByRole('button', { name: new RegExp(`^${name.replace(/[()]/g, '\\$&')}`) });
const homeApprove = (page: Page, l: Locale) => page.getByRole('link', { name: new RegExp(`^${T[l].approve} ${T[l].card}`) });
const dock = (page: Page, l: Locale) => page.getByRole('region', { name: T[l].decision });

interface Screen {
  name: string;
  scenario: Scenario;
  hash: string;
  signedOut?: boolean;
  /** Skeletons stay on screen: the state under test is the loading one. */
  loading?: boolean;
  override?: (page: Page) => Promise<void>;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  focus: (page: Page, locale: Locale) => Locator;
  action?: (page: Page, locale: Locale) => Locator;
}

const SCREENS: readonly Screen[] = [
  { name: 'journey-1-sign-in', scenario: 'signed-out', signedOut: true, hash: '#/', focus: (page, l) => page.getByRole('button', { name: T[l].signIn }) },
  {
    name: 'journey-2-home', scenario: 'processes-approver', hash: '#/',
    // Home lists each waiting change with its own Approve button, named for the change.
    focus: (page, l) => homeApprove(page, l), action: (page, l) => homeApprove(page, l),
  },
  { name: 'journey-3-approvals', scenario: 'processes-approver', hash: '#/approvals', focus: (page, l) => preview(page, T[l].card), action: (page, l) => page.getByRole('link', { name: T[l].reviewNext }) },
  {
    name: 'queue-process-selected', scenario: 'processes-approver', hash: '#/approvals',
    prepare: async (page, l) => { await card(page, T[l].kyc).click(); },
    focus: (page, l) => preview(page, T[l].kyc), action: (page, l) => preview(page, T[l].kyc).getByRole('link', { name: T[l].reviewAndApprove }),
  },
  {
    name: 'queue-requester-blocked', scenario: 'processes-requester', hash: '#/approvals',
    prepare: async (page, l) => { await card(page, T[l].kyc).click(); },
    focus: (page, l) => preview(page, T[l].kyc).getByText(T[l].self), action: (page, l) => preview(page, T[l].kyc).getByRole('button', { name: T[l].approve }),
  },
  {
    name: 'queue-empty', scenario: 'processes-approver', hash: '#/approvals',
    override: async (page) => {
      const empty = { status: 200, contentType: 'application/json', body: JSON.stringify({ data: [], nextCursor: null, hasNext: false }) };
      await page.route('**/api/bpm/rules/v1/approvals*', (route) => route.fulfill(empty));
      await page.route('**/api/bpm/processes/v1/approvals*', (route) => route.fulfill(empty));
    },
    focus: (page, l) => page.getByText(T[l].empty),
  },
  {
    name: 'queue-loading', scenario: 'processes-approver', hash: '#/approvals', loading: true,
    // The answer never comes while the screenshot is taken.
    override: async (page) => { await page.route('**/api/bpm/rules/v1/approvals*', () => undefined); },
    focus: (page, l) => page.getByRole('status', { name: T[l].loading }),
  },
  {
    name: 'queue-error', scenario: 'processes-approver', hash: '#/approvals',
    override: async (page) => {
      await page.route('**/api/bpm/rules/v1/approvals*', (route) => answer(route, 500, 'INTERNAL_ERROR'));
      await page.route('**/api/bpm/processes/v1/approvals*', (route) => answer(route, 503, 'SERVICE_UNAVAILABLE'));
    },
    focus: (page, l) => page.getByRole('alert').getByText(T[l].error),
  },
  {
    name: 'queue-partial', scenario: 'processes-approver', hash: '#/approvals',
    override: async (page) => { await page.route('**/api/bpm/processes/v1/approvals*', (route) => answer(route, 500, 'INTERNAL_ERROR')); },
    focus: (page, l) => page.getByText(T[l].partial), action: (page, l) => page.getByRole('link', { name: T[l].reviewNext }),
  },
  {
    name: 'queue-forbidden', scenario: 'processes-approver', hash: '#/approvals',
    override: async (page) => {
      await page.route('**/api/bpm/rules/v1/approvals*', (route) => answer(route, 403, 'PERMISSION_DENIED'));
      await page.route('**/api/bpm/processes/v1/approvals*', (route) => answer(route, 403, 'PERMISSION_DENIED'));
    },
    focus: (page, l) => page.getByText(T[l].forbidden),
  },
  {
    name: 'review-rule-linked', scenario: 'processes-approver', hash: '#/rules/card-fee/2/changes',
    focus: (page, l) => dock(page, l), action: (page, l) => dock(page, l).getByRole('button', { name: T[l].ruleApprove }),
  },
  {
    name: 'review-rule-content-changed', scenario: 'processes-approver', hash: '#/rules/card-fee/2/changes',
    override: async (page) => { await page.route('**/card-fee/versions/2:approve', (route) => answer(route, 409, 'CONTENT_CHANGED')); },
    prepare: async (page, l) => { await dock(page, l).getByRole('button', { name: T[l].ruleApprove }).click(); },
    focus: (page, l) => page.getByText(T[l].ruleChanged), action: (page, l) => dock(page, l).getByRole('button', { name: T[l].ruleApprove }),
  },
  {
    name: 'review-process-linked', scenario: 'processes-approver', hash: '#/processes/kyc-onboarding/3/changes',
    focus: (page, l) => dock(page, l), action: (page, l) => dock(page, l).getByRole('button', { name: T[l].processApprove }),
  },
  {
    name: 'review-process-content-changed', scenario: 'processes-approver', hash: '#/processes/kyc-onboarding/3/changes',
    override: async (page) => { await page.route('**/kyc-onboarding/versions/3:approve', (route) => answer(route, 409, 'CONTENT_CHANGED')); },
    prepare: async (page, l) => { await dock(page, l).getByRole('button', { name: T[l].processApprove }).click(); },
    focus: (page, l) => dock(page, l).getByText(T[l].processChanged), action: (page, l) => dock(page, l).getByRole('button', { name: T[l].processApprove }),
  },
];

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`CKP_003_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.clock.setFixedTime(NOW);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockEngines(page, screen.scenario, locale);
          await screen.override?.(page);
          await page.goto(`./${screen.hash}`);
          await expect(screen.signedOut === true ? page.getByTestId('signed-out') : page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          if (screen.loading !== true) await expect(page.locator('.skeleton')).toHaveCount(0);
          await screen.prepare?.(page, locale);
          const focus = screen.focus(page, locale);
          await expect(focus).toBeVisible();
          const box = await focus.boundingBox();
          expect(box, 'the focal element is rendered').not.toBeNull();
          expect((box?.y ?? 0) + (box?.height ?? 0), 'visible without scrolling').toBeLessThanOrEqual(viewport.height);
          if (screen.action !== undefined) {
            const action = await screen.action(page, locale).boundingBox();
            expect((action?.y ?? viewport.height) + (action?.height ?? 0), 'the primary action stays above the fold').toBeLessThanOrEqual(viewport.height);
          }
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'no sideways scrolling').toBe(true);
          // What is rendered, not what sits behind a closed Details (where the error code and reference belong).
          const shown = await page.locator(screen.signedOut === true ? 'body' : 'main').innerText();
          expect(shown).not.toMatch(/CKP-0|PD-1|RLE-1|PRE-1|\{"|9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b|INTERNAL_ERROR|PERMISSION_DENIED|SELF_APPROVAL_FORBIDDEN|CONTENT_CHANGED/);
          if (screen.name.startsWith('queue') || screen.name === 'journey-3-approvals') expect(shown).not.toContain('kyc-onboarding');
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

/** The hash each engine's queue listed, read from the answer the browser received. */
async function listedHash(page: Page, path: string, find: (item: Record<string, unknown>) => boolean, field: string): Promise<string> {
  const response = await page.waitForResponse((r) => new URL(r.url()).pathname.endsWith(path));
  const body = (await response.json()) as { data: Record<string, unknown>[] };
  const item = body.data.find(find);
  return String(item?.[field]);
}

test('CKP_003_maker_checker_ui_blocks_requester_self_approval_and_the_second_person_approves_the_reviewed_hash', async ({ browser }) => {
  // The requester: Approve disabled with the engine's reason; a forced call is refused by the server and nothing is approved.
  const requester = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await requester.clock.setFixedTime(NOW);
  const first = await mockEngines(requester, 'processes-requester');
  await requester.goto('./#/approvals');
  await card(requester, T.en.kyc).click();
  const blocked = preview(requester, T.en.kyc).getByRole('button', { name: T.en.approve });
  await expect(blocked).toBeDisabled();
  await expect(blocked).toHaveAccessibleDescription(T.en.self);
  await blocked.click({ force: true });
  expect(first.processes.decisions).toEqual([]);
  const forced = await requester.evaluate(async (hash) => {
    const response = await fetch('/api/bpm/processes/v1/definitions/kyc-onboarding/versions/3:approve', {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'If-Match': '"2"', 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ expectedDefinitionHash: hash }),
    });
    return { status: response.status, code: ((await response.json()) as { code?: string }).code };
  }, KYC_V3_HASH);
  expect(forced).toEqual({ status: 403, code: 'SELF_APPROVAL_FORBIDDEN' });
  await requester.close();

  // The second person: from the queue to the process diff, then Approve names the hash the queue listed and the diff showed.
  const approver = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await approver.clock.setFixedTime(NOW);
  const engines = await mockEngines(approver, 'processes-approver');
  const queued = listedHash(approver, '/processes/v1/approvals', (item) => item['key'] === 'kyc-onboarding', 'definitionHash');
  await approver.goto('./#/approvals');
  expect(await queued).toBe(KYC_V3_HASH);
  const menu = approver.getByRole('navigation', { name: T.en.menu });
  await expect(menu.getByRole('link', { name: /Approvals/ })).toContainText('2');
  await card(approver, T.en.kyc).click();
  await preview(approver, T.en.kyc).getByRole('link', { name: T.en.reviewAndApprove }).click();
  await expect(approver).toHaveURL(/#\/processes\/kyc-onboarding\/3\/changes$/);
  await dock(approver, 'en').getByRole('button', { name: T.en.processApprove }).click();
  await expect(dock(approver, 'en').getByText(/Approved/)).toBeVisible();
  expect(engines.processes.decisions).toHaveLength(1);
  expect(engines.processes.decisions[0]).toEqual(expect.objectContaining({ action: 'approve', by: 'minh', body: expect.objectContaining({ expectedDefinitionHash: KYC_V3_HASH }) }));
  // Back in the queue, the approved change is gone and the menu counts one.
  await menu.getByRole('link', { name: /Approvals/ }).click();
  await expect(card(approver, T.en.kyc)).toHaveCount(0);
  await expect(card(approver, T.en.card)).toBeVisible();
  await expect(menu.getByRole('link', { name: /Approvals/ })).toContainText('1');
  await approver.close();
});

test('CKP_003_a_rule_approval_from_the_queue_sends_the_canonical_hash_the_queue_listed', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockEngines(page, 'processes-approver');
  const queued = listedHash(page, '/rules/v1/approvals', (item) => item['ruleSet'] === 'card-fee', 'canonicalHash');
  const sent: unknown[] = [];
  page.on('request', (request) => { if (request.url().endsWith('/card-fee/versions/2:approve')) sent.push(request.postDataJSON()); });
  await page.goto('./#/approvals');
  const hash = await queued;
  expect(hash).toMatch(/^[0-9a-f]{64}$/);
  await page.getByRole('link', { name: T.en.reviewNext }).click();
  await expect(page).toHaveURL(/#\/rules\/card-fee\/2\/changes$/);
  await dock(page, 'en').getByRole('button', { name: T.en.ruleApprove }).click();
  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0]).toEqual(expect.objectContaining({ expectedCanonicalHash: hash }));
});

test('CKP_003_after_CONTENT_CHANGED_the_rule_approval_cannot_be_sent_with_the_stale_hash_until_the_current_changes_are_shown', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockHost(page, 'processes-approver');
  const user = scenarioUser('processes-approver');
  const permissions = scenarioPermissions('processes-approver');
  // The engine the reviewer opened, then the same rule set with one rule edited under them.
  const edited = demoSeed();
  const pending = edited.find((v) => v.ruleSet === 'card-fee' && v.version === 2);
  if (pending === undefined) throw new Error('the demo seed has card-fee version 2');
  pending.definition = { ...pending.definition, rules: pending.definition.rules.map((rule) => (rule.id === 'R3' ? { ...rule, then: { fee: '45000 VND', waived: false } } : rule)) };
  let engine = createFakeEngine(demoSeed());
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const reply = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user, permissions });
    await route.fulfill({ status: reply.status, headers: reply.headers, body: reply.body });
  });
  const sent: { expectedCanonicalHash: string }[] = [];
  page.on('request', (request) => { if (request.url().endsWith('/card-fee/versions/2:approve')) sent.push(request.postDataJSON() as { expectedCanonicalHash: string }); });
  await page.goto('./#/rules/card-fee/2/changes');
  const approve = dock(page, 'en').getByRole('button', { name: T.en.ruleApprove });
  await expect(approve).toBeEnabled();
  engine = createFakeEngine(edited);
  await approve.click();
  await expect(page.getByText(T.en.ruleChanged)).toBeVisible();
  expect(sent).toHaveLength(1);
  // Approve stays disabled: a forced click and the keyboard send nothing.
  await expect(approve).toBeDisabled();
  await approve.click({ force: true });
  await approve.focus();
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  expect(sent).toHaveLength(1);
  // Showing the current changes reads the version again and arms Approve with the new hash.
  await page.getByRole('button', { name: T.en.showCurrent }).click();
  await expect(approve).toBeEnabled();
  await expect(page.getByText(T.en.ruleChanged)).toHaveCount(0);
  await approve.click();
  await expect.poll(() => sent.length).toBe(2);
  expect(sent[1]?.expectedCanonicalHash).toMatch(/^[0-9a-f]{64}$/);
  expect(sent[1]?.expectedCanonicalHash).not.toBe(sent[0]?.expectedCanonicalHash);
  await expect(page.getByText('Version 2 is published.')).toBeVisible();
});

test('CKP_003_a_forced_self_approval_on_the_review_screen_shows_the_servers_refusal', async ({ page }) => {
  // Lan holds the approve permission and asked for card-fee v2. A stale screen that offers Approve still gets the engine's 403.
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  const { rules } = await mockEngines(page, 'rules-author-approver');
  await page.route(/\/rule-sets\/card-fee\/versions\/2(\?.*)?$/, async (route) => {
    const request = route.request();
    const stored = rules.handle({ method: 'GET', path: request.url(), headers: request.headers(), body: '', user: scenarioUser('rules-author-approver'), permissions: scenarioPermissions('rules-author-approver') });
    const version = { ...(JSON.parse(stored.body) as Record<string, unknown>), requestedBy: 'hoa', createdBy: 'hoa', allowedActions: ['approve', 'reject'] };
    await route.fulfill({ status: stored.status, headers: stored.headers, body: JSON.stringify(version) });
  });
  await page.goto('./#/rules/card-fee/2/changes');
  await dock(page, 'en').getByRole('button', { name: T.en.ruleApprove }).click();
  await expect(page.getByRole('alert').getByText(T.en.refused)).toBeVisible();
  await expect(page.getByRole('main')).not.toContainText(/Published/);
});

test('CKP_003_the_queue_works_by_keyboard_with_a_visible_focus', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockEngines(page, 'processes-approver');
  await page.goto('./#/approvals');
  const second = card(page, T.en.kyc);
  await expect(second).toBeVisible();
  await card(page, T.en.card).focus();
  await page.keyboard.press('Tab');
  await expect(second).toBeFocused();
  expect(await second.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');
  await expect(second).toHaveAttribute('aria-pressed', 'true');
  await expect(preview(page, T.en.kyc)).toBeVisible();
});
