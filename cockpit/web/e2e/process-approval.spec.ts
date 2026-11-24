import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { scenarioPermissions, scenarioUser, type Scenario } from '../src/mocks/fixtures';
import { KYC_V3_HASH } from '../src/mocks/processDemo';
import { createFakeProcessEngine, type ProcessSeed } from '../src/mocks/processEngine';
import { cspViolations, mockHost, mockRuleEngine, rememberLocale } from './mockHost';

/*
 * Approving a process change as a diagram diff (CKP-024), in the order of the journey: sign-in, Home, Processes,
 * then the pending version's Changes with the docked decision. Every state is checked at 1440x900 and 1440x1200,
 * light and dark, English and Vietnamese: the focal element and the primary action are fully above the fold, no
 * sideways scrolling, axe-core finds nothing, and no raw JSON, hash in the body, code or spec id shows.
 * Screenshots go to APPROVAL_SCREENSHOTS (default e2e/screenshots/, which git ignores).
 */

const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];
const SHOTS = process.env['APPROVAL_SCREENSHOTS'] ?? 'e2e/screenshots';
const NOW = new Date('2026-11-21T03:00:00Z');
const PENDING = '#/processes/kyc-onboarding/3';

const T = {
  en: {
    approve: 'Approve change', reject: 'Reject', review: 'Review change', processes: 'Processes', comment: 'Comment', signIn: 'Sign in again',
    self: 'You requested this change, so someone else approves it.', waiting: 'Waiting for a second person to approve.',
    tooLarge: 'Too large to approve here', tooLargeLine: 'Too large to approve here: the change list is cut off.',
    changed: 'This version changed after you opened it. Check the changes again before you decide.',
    done: 'Approved. In effect from', rejected: 'Rejected. Version 3 is a draft again.', first: 'First version: nothing in effect to compare.',
    removedStep: 'Wait one day', inEffect: 'In effect · version 2', kyc: 'Customer onboarding (KYC)', card: 'Card dispute',
  },
  vi: {
    approve: 'Duyệt thay đổi', reject: 'Từ chối', review: 'Xem thay đổi', processes: 'Quy trình', comment: 'Ghi chú', signIn: 'Đăng nhập lại',
    self: 'Bạn đã gửi duyệt thay đổi này, nên người khác sẽ duyệt.', waiting: 'Đang chờ người thứ hai duyệt.',
    tooLarge: 'Quá lớn để duyệt tại đây', tooLargeLine: 'Quá lớn để duyệt tại đây: danh sách thay đổi bị cắt.',
    changed: 'Phiên bản này đã thay đổi sau khi bạn mở. Hãy kiểm tra lại trước khi quyết định.',
    done: 'Đã duyệt. Áp dụng từ', rejected: 'Đã từ chối. Phiên bản 3 trở lại bản nháp.', first: 'Phiên bản đầu tiên: chưa có phiên bản đang áp dụng để so sánh.',
    removedStep: 'Chờ một ngày', inEffect: 'Đang áp dụng · phiên bản 2', kyc: 'Mở khách hàng mới (KYC)', card: 'Khiếu nại thẻ',
  },
} as const;

/** The registry, /meta and /me of the scenario, the rule engine for Home, and the fake process engine as its user. */
async function mockEngines(page: Page, scenario: Scenario, seed: ProcessSeed = {}) {
  await mockHost(page, scenario);
  await mockRuleEngine(page, scenario);
  const engine = createFakeProcessEngine(seed);
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(scenario), permissions: scenarioPermissions(scenario) });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return engine;
}

const dock = (page: Page, locale: Locale) => page.getByRole('region', { name: locale === 'en' ? 'Your decision' : 'Quyết định của bạn' });
const approveButton = (page: Page, locale: Locale) => dock(page, locale).getByRole('button', { name: T[locale].approve });

interface Screen {
  name: string;
  scenario: Scenario;
  signedOut?: boolean;
  hash: string;
  seed?: Omit<ProcessSeed, 'locale'>;
  override?: (page: Page) => Promise<void>;
  prepare?: (page: Page, locale: Locale) => Promise<void>;
  /** What must be on screen and fully above the fold once the state is reached. */
  focus: (page: Page, locale: Locale) => Locator;
  /** The primary action, which must stay above the fold beside the focal element. */
  action?: (page: Page, locale: Locale) => Locator;
}

const SCREENS: readonly Screen[] = [
  { name: 'journey-1-sign-in', scenario: 'signed-out', signedOut: true, hash: '#/', focus: (page, l) => page.getByRole('button', { name: T[l].signIn }) },
  { name: 'journey-2-home', scenario: 'processes-approver', hash: '#/', focus: (page, l) => page.getByRole('navigation', { name: l === 'en' ? 'Cockpit menu' : 'Menu Cockpit' }).getByRole('link', { name: T[l].processes }) },
  { name: 'journey-3-processes', scenario: 'processes-approver', hash: '#/processes', focus: (page, l) => page.getByRole('link', { name: new RegExp(`^${T[l].review}`) }) },
  { name: 'approval-ready', scenario: 'processes-approver', hash: PENDING, focus: (page) => page.locator('[data-node="fraud-check"][data-mark="added"]'), action: approveButton },
  {
    name: 'approval-in-effect-removed', scenario: 'processes-approver', hash: PENDING,
    prepare: async (page, l) => { await page.getByRole('button', { name: new RegExp(` ${T[l].removedStep} `) }).click(); },
    focus: (page) => page.locator('[data-node="cooling"][data-mark="removed"].is-selected'), action: approveButton,
  },
  { name: 'approval-requester', scenario: 'processes-requester', hash: PENDING, focus: (page, l) => dock(page, l).getByText(T[l].self), action: approveButton },
  { name: 'approval-reader-waiting', scenario: 'processes-reviewer', hash: PENDING, focus: (page, l) => dock(page, l).getByText(T[l].waiting), action: approveButton },
  {
    name: 'approval-truncated', scenario: 'processes-approver', hash: PENDING, seed: { truncatedDiff: true },
    // The reason is said once, beside Approve in the dock (#443).
    focus: (page, l) => dock(page, l).getByText(T[l].tooLargeLine, { exact: true }), action: approveButton,
  },
  {
    name: 'approval-content-changed', scenario: 'processes-approver', hash: PENDING,
    override: async (page) => {
      await page.route('**/kyc-onboarding/versions/3:approve', (route) => route.fulfill({
        status: 409, contentType: 'application/problem+json',
        body: JSON.stringify({ type: 'about:blank', title: 'Content changed', status: 409, code: 'CONTENT_CHANGED', correlationId: '01JFAKE0000000000000000009' }),
      }));
    },
    prepare: async (page, l) => { await approveButton(page, l).click(); },
    focus: (page, l) => dock(page, l).getByText(T[l].changed), action: approveButton,
  },
  {
    name: 'approval-approved', scenario: 'processes-approver', hash: PENDING,
    prepare: async (page, l) => {
      await dock(page, l).getByRole('textbox', { name: T[l].comment }).fill(l === 'en' ? 'Reviewed with the KYC committee, memo KYC-31' : 'Đã xem cùng hội đồng KYC, biên bản KYC-31');
      await approveButton(page, l).click();
    },
    focus: (page, l) => dock(page, l).getByText(new RegExp(T[l].done)),
  },
  {
    name: 'approval-rejected', scenario: 'processes-approver', hash: PENDING,
    prepare: async (page, l) => {
      await dock(page, l).getByRole('textbox', { name: T[l].comment }).fill(l === 'en' ? 'Keep the one-day wait for high-risk customers' : 'Giữ thời gian chờ một ngày với khách hàng rủi ro cao');
      await dock(page, l).getByRole('button', { name: T[l].reject }).click();
    },
    focus: (page, l) => dock(page, l).getByText(T[l].rejected),
  },
  { name: 'changes-first-version', scenario: 'processes-approver', hash: '#/processes/card-dispute/1/changes', focus: (page, l) => page.getByText(T[l].first) },
];

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const file = `approval-${screen.name}-${String(viewport.width)}x${String(viewport.height)}-${theme}-${locale}.png`;
        test(`CKP_024_visual_${screen.name}_${String(viewport.width)}x${String(viewport.height)}_${theme}_${locale}`, async ({ page }) => {
          const violations = cspViolations(page);
          await page.clock.setFixedTime(NOW);
          await page.setViewportSize(viewport);
          await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
          await rememberLocale(page, locale);
          await mockEngines(page, screen.scenario, { ...screen.seed, locale });
          await screen.override?.(page);
          await page.goto(`./${screen.hash}`);
          await expect(screen.signedOut === true ? page.getByTestId('signed-out') : page.getByTestId('header-user')).toBeVisible();
          await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
          await expect(page.locator('.skeleton')).toHaveCount(0);
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
          // The docked decision never covers the changes card, however much it says (#443).
          const docked = await page.locator('.decision--dock').count() > 0 ? await page.locator('.decision--dock').boundingBox() : null;
          const card = await page.locator('.card--diagram').count() > 0 ? await page.locator('.card--diagram').boundingBox() : null;
          if (docked !== null && card !== null) expect(card.y + card.height, 'the dock never covers the changes card').toBeLessThanOrEqual(docked.y + 1);
          if (screen.name === 'approval-truncated') expect(await page.getByText(new RegExp(T[locale].tooLarge)).count(), 'the too-large reason is said once').toBe(1);
          const main = page.locator(screen.signedOut === true ? 'body' : 'main');
          await expect(main).not.toContainText(/CKP-0|PD-1|PRE-1|\{"|9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b/);
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

/** The disabled look of the styleguide: not the primary fill, a not-allowed cursor. */
async function looksDisabled(button: Locator): Promise<void> {
  await expect(button).toBeDisabled();
  const look = await button.evaluate((element) => {
    const probe = document.createElement('span');
    probe.style.color = 'var(--primary-fill)';
    document.body.append(probe);
    const primary = getComputedStyle(probe).color;
    probe.remove();
    return { background: getComputedStyle(element).backgroundColor, primary, cursor: getComputedStyle(element).cursor };
  });
  expect(look.background).not.toBe(look.primary);
  expect(look.cursor).toBe('not-allowed');
}

for (const locale of LOCALES) {
  test(`CKP_024_process_approval_graph_diff_blocks_requester_${locale}`, async ({ browser }) => {
    // The requester: the diff marked on the diagram and in the list, Approve disabled with one line saying why.
    const requester = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await requester.clock.setFixedTime(NOW);
    await rememberLocale(requester, locale);
    const first = await mockEngines(requester, 'processes-requester', { locale });
    await requester.goto(`./${PENDING}`);
    await expect(requester.locator('[data-node="fraud-check"][data-mark="added"]')).toBeVisible();
    await expect(requester.locator('[data-node="approve"][data-mark="changed"]')).toBeVisible();
    const list = requester.getByRole('region', { name: locale === 'en' ? 'What changed' : 'Những gì thay đổi' });
    await expect(list).toContainText(locale === 'en' ? 'Assignee › Role' : 'Người được giao › Vai trò');
    await expect(list).toContainText(locale === 'en' ? /Credit officer → .*Senior credit officer/ : /Cán bộ tín dụng → .*Cán bộ tín dụng cao cấp/);
    await expect(list).not.toContainText('CREDIT_OFFICER');
    // Money in a changed value reads with the language's separators and its currency.
    await expect(list).toContainText(locale === 'en' ? '500,000,000' : '500.000.000');
    const line = dock(requester, locale).getByText(T[locale].self);
    await expect(line).toBeVisible();
    expect((await line.boundingBox())?.height ?? 99).toBeLessThanOrEqual(40);
    const blocked = approveButton(requester, locale);
    await looksDisabled(blocked);
    await expect(blocked).toHaveAccessibleDescription(T[locale].self);
    await expect(dock(requester, locale).getByRole('button', { name: T[locale].reject })).toHaveCount(0);
    await blocked.click({ force: true });
    expect(first.decisions).toEqual([]);
    await requester.close();

    // The second person approves with a comment, by keyboard, naming the hash of the content on screen.
    const approver = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await approver.clock.setFixedTime(NOW);
    await rememberLocale(approver, locale);
    const engine = await mockEngines(approver, 'processes-approver', { locale });
    await approver.goto(`./${PENDING}`);
    await expect(approver.locator('[data-node="fraud-check"][data-mark="added"]')).toBeVisible();
    await dock(approver, locale).getByRole('textbox', { name: T[locale].comment }).fill('Reviewed with the KYC committee');
    const approve = approveButton(approver, locale);
    await expect(approve).toBeEnabled();
    await approve.focus();
    expect(await approve.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
    await approver.keyboard.press('Enter');
    await expect(dock(approver, locale).getByText(new RegExp(T[locale].done))).toBeVisible();
    await expect(approver.getByRole('heading', { level: 1 }).locator('..').locator('..')).toContainText(locale === 'en' ? 'Active' : 'Đang hoạt động');
    expect(engine.decisions).toHaveLength(1);
    expect(engine.decisions[0]).toEqual(expect.objectContaining({
      action: 'approve', by: 'minh', ifMatch: '"2"', body: { expectedDefinitionHash: KYC_V3_HASH, comment: 'Reviewed with the KYC committee' },
    }));
    expect(engine.decisions[0]?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    await approver.close();
  });
}

test('CKP_024_a_cut_off_change_list_is_too_large_to_approve_and_sends_nothing', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page, 'processes-approver', { truncatedDiff: true });
  await page.goto(`./${PENDING}`);
  await expect(page.getByText(T.en.tooLargeLine, { exact: true })).toBeVisible();
  await expect(page.getByText('The change list stops at 1,000 values, so it is not the whole change.')).toBeVisible();
  const approve = approveButton(page, 'en');
  await looksDisabled(approve);
  await expect(approve).toHaveAccessibleDescription(T.en.tooLargeLine);
  await approve.click({ force: true });
  expect(engine.decisions).toEqual([]);
  // Reject names no content, so it stays possible: the approver can send the change back with a reason.
  await dock(page, 'en').getByRole('textbox', { name: T.en.comment }).fill('Split this change into smaller versions');
  await dock(page, 'en').getByRole('button', { name: T.en.reject }).click();
  await expect(dock(page, 'en').getByText(T.en.rejected)).toBeVisible();
  expect(engine.decisions.map((d) => d.action)).toEqual(['reject']);
});

test('CKP_024_a_content_changed_refusal_is_not_retried_and_asks_for_a_new_review', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockEngines(page, 'processes-approver');
  let approvals = 0;
  await page.route('**/kyc-onboarding/versions/3:approve', async (route) => {
    approvals += 1;
    await route.fulfill({ status: 409, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Content changed', status: 409, code: 'CONTENT_CHANGED', correlationId: '01JFAKE0000000000000000009' }) });
  });
  await page.goto(`./${PENDING}`);
  await approveButton(page, 'en').click();
  await expect(dock(page, 'en').getByText(T.en.changed)).toBeVisible();
  await expect(approveButton(page, 'en')).toBeDisabled();
  await page.waitForTimeout(300);
  expect(approvals).toBe(1);
  await dock(page, 'en').getByRole('button', { name: 'Show the current changes' }).click();
  await expect(dock(page, 'en').getByText(T.en.changed)).toHaveCount(0);
});

test('CKP_024_a_removed_step_is_shown_on_the_version_in_effect', async ({ page }) => {
  await page.clock.setFixedTime(NOW);
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockEngines(page, 'processes-approver');
  await page.goto(`./${PENDING}`);
  await expect(page.locator('[data-node="cooling"]')).toHaveCount(0);
  await page.getByRole('button', { name: 'Removed Wait one day Timer' }).click();
  await expect(page.getByRole('button', { name: T.en.inEffect })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-node="cooling"]')).toHaveAttribute('data-mark', 'removed');
  await expect(page.getByRole('img', { name: 'Wait one day, Timer, Removed' })).toBeVisible();
  await expect(page.locator('[data-edge="k9"]')).toHaveAttribute('data-mark', 'removed');
});
