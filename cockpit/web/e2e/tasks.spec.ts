import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { BpmMeV1 } from '../src/api/generated/bpm-me.v1';
import { meAn, meSau, metaWithProcesses } from '../src/mocks/fixtures';
import { createFakeProcessEngine } from '../src/mocks/processEngine';
import { QUEUE_TASKS } from '../src/mocks/taskQueueDemo';
import { mockHost, rememberLocale } from './mockHost';

/*
 * The task queue (CKP-046 to CKP-048) at 1440x900 and 1440x1200, light and dark, English and Vietnamese: every state
 * an operator and a supervisor meet, from the example host's sign-in page to the queue. Each run checks that the list
 * and the selected task's primary action (or the state's focal element) are on screen without scrolling, that the
 * page never scrolls sideways, and axe-core finds no serious violation. Screenshots go to TASKS_SHOTS_DIR, or
 * e2e/screenshots/ by default.
 */

const SHOTS = process.env['TASKS_SHOTS_DIR'] ?? 'e2e/screenshots';
const VIEWPORTS = [{ width: 1440, height: 900 }, { width: 1440, height: 1200 }] as const;
const THEMES = ['light', 'dark'] as const;
const LOCALES = ['en', 'vi'] as const;
type Locale = (typeof LOCALES)[number];

test.use({ timezoneId: 'Asia/Ho_Chi_Minh' });

const T = {
  claim: { en: 'Claim', vi: 'Nhận việc' },
  release: { en: 'Release', vi: 'Trả lại' },
  reassign: { en: 'Reassign', vi: 'Giao lại' },
  userId: { en: "Colleague's user id", vi: 'Mã người dùng của đồng nghiệp' },
  reason: { en: 'Reason', vi: 'Lý do' },
  title: { en: 'Tasks', vi: 'Công việc' },
  covering: { en: 'Covering annual leave', vi: 'Làm thay trong thời gian nghỉ phép' },
  signIn: { en: 'Sign in', vi: 'Đăng nhập' },
} as const;

async function mockEngines(page: Page, user: BpmMeV1, locale: Locale) {
  const engine = createFakeProcessEngine({ locale });
  await mockHost(page, 'ready');
  await page.route('**/api/bpm/meta', (route) => route.fulfill({ json: metaWithProcesses }));
  await page.route('**/api/bpm/me', (route) => route.fulfill({ json: user }));
  await page.route('**/api/bpm/rules/v1/**', (route) => route.fulfill({ status: 403, contentType: 'application/problem+json', body: JSON.stringify({ status: 403, code: 'PERMISSION_DENIED' }) }));
  await page.route('**/api/bpm/processes/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: user.user.id, permissions: user.permissions });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return engine;
}

interface Screen {
  name: string;
  user: BpmMeV1;
  hash: string;
  prepare?: (page: Page, locale: Locale, engine: ReturnType<typeof createFakeProcessEngine>) => Promise<void>;
  /** What must be fully on screen at 1440x900. */
  focal: (page: Page, locale: Locale) => Locator;
}

const inspector = (page: Page) => page.locator('.tasks__inspector');
const dialog = (page: Page) => page.getByRole('dialog');

async function openReassign(page: Page, locale: Locale) {
  await inspector(page).getByRole('button', { name: T.reassign[locale] }).click();
  await expect(dialog(page)).toBeVisible();
}

async function fillReassign(page: Page, locale: Locale, who: string) {
  await openReassign(page, locale);
  await dialog(page).getByLabel(T.userId[locale]).fill(who);
  await dialog(page).getByLabel(T.reason[locale]).fill(T.covering[locale]);
}

const SCREENS: readonly Screen[] = [
  { name: 'operator-mine', user: meAn, hash: `#/tasks?view=mine&task=${QUEUE_TASKS.myCreditReview}`, focal: (page, l) => inspector(page).getByRole('button', { name: T.release[l] }) },
  { name: 'operator-available', user: meAn, hash: '#/tasks?view=candidate', focal: (page, l) => inspector(page).getByRole('button', { name: T.claim[l] }) },
  {
    name: 'operator-claimed-first', user: meAn, hash: '#/tasks?view=candidate',
    prepare: async (page, l, engine) => {
      await expect(inspector(page).getByRole('button', { name: T.claim[l] })).toBeVisible();
      engine.claimAs(QUEUE_TASKS.creditReviewOverdue, 'hoa');
      await inspector(page).getByRole('button', { name: T.claim[l] }).click();
      await expect(page.locator('.tasks__notice')).toBeVisible();
    },
    focal: (page) => page.locator('.tasks__notice'),
  },
  {
    name: 'operator-claimed', user: meAn, hash: '#/tasks?view=candidate',
    prepare: async (page, l) => {
      await inspector(page).getByRole('button', { name: T.claim[l] }).click();
      await expect(page.locator('.toast')).toBeVisible();
    },
    focal: (page) => page.locator('.toast'),
  },
  {
    name: 'operator-empty', user: meAn, hash: '#/tasks?view=mine',
    prepare: async () => { /* the route below answers an empty list */ },
    focal: (page) => page.locator('.tasks__empty .link-action'),
  },
  { name: 'supervisor-all', user: meSau, hash: '#/tasks?view=all', focal: (page, l) => inspector(page).getByRole('button', { name: T.reassign[l] }) },
  { name: 'supervisor-four-eyes', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.approveOwnCase}`, focal: (page) => inspector(page).locator('.tasks__why') },
  { name: 'supervisor-claimed-by-colleague', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.collateral}`, focal: (page, l) => inspector(page).getByRole('button', { name: T.reassign[l] }) },
  { name: 'supervisor-masked', user: meSau, hash: '#/tasks?view=all&task=01JD4T5REV1EW000000000AT05', focal: (page) => inspector(page).locator('.tasks__line').last() },
  {
    name: 'reassign-empty', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.creditReview}`,
    prepare: async (page, l) => { await openReassign(page, l); },
    focal: (page, l) => dialog(page).getByRole('button', { name: T.reassign[l] }),
  },
  {
    name: 'reassign-filled', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.creditReview}`,
    prepare: async (page, l) => { await fillReassign(page, l, 'hoa'); },
    focal: (page, l) => dialog(page).getByRole('button', { name: T.reassign[l] }),
  },
  {
    name: 'reassign-refused', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.approveLimit}`,
    prepare: async (page, l) => {
      await fillReassign(page, l, 'hoa');
      await dialog(page).getByRole('button', { name: T.reassign[l] }).click();
      await expect(dialog(page).getByRole('alert')).toBeVisible();
    },
    focal: (page) => dialog(page).getByRole('alert'),
  },
  {
    name: 'reassign-done', user: meSau, hash: `#/tasks?view=all&task=${QUEUE_TASKS.myCreditReview}`,
    prepare: async (page, l) => {
      await fillReassign(page, l, 'hoa');
      await dialog(page).getByRole('button', { name: T.reassign[l] }).click();
      await expect(page.locator('.toast')).toBeVisible();
    },
    focal: (page) => page.locator('.toast'),
  },
];

async function openScreen(page: Page, screen: Screen, viewport: { width: number; height: number }, theme: 'light' | 'dark', locale: Locale) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
  await rememberLocale(page, locale);
  const engine = await mockEngines(page, screen.user, locale);
  if (screen.name === 'operator-empty') {
    await page.route('**/api/bpm/processes/v1/tasks?assignment=mine&**', (route) => route.fulfill({ json: { data: [], nextCursor: null, hasNext: false } }));
  }
  await page.goto(`./${screen.hash}`);
  await expect(page.getByRole('heading', { level: 1, name: T.title[locale] })).toBeVisible();
  await expect(page.locator('.skeleton')).toHaveCount(0);
  if (screen.prepare) await screen.prepare(page, locale, engine);
  return engine;
}

async function expectAboveFold(locator: Locator, height: number) {
  await expect(locator).toBeVisible();
  const box = await locator.boundingBox();
  if (!box) throw new Error('element has no box');
  expect(box.y).toBeGreaterThanOrEqual(0);
  expect(box.y + box.height).toBeLessThanOrEqual(height);
}

for (const screen of SCREENS) {
  for (const viewport of VIEWPORTS) {
    for (const theme of THEMES) {
      for (const locale of LOCALES) {
        const size = `${String(viewport.width)}x${String(viewport.height)}`;
        test(`TR_UI_03_visual_tasks_${screen.name}_${size}_${theme}_${locale}`, async ({ page }) => {
          await openScreen(page, screen, viewport, theme, locale);
          await expectAboveFold(screen.focal(page, locale), viewport.height);
          if (screen.name !== 'operator-empty') await expectAboveFold(page.locator('.tasks__table tbody tr').first(), viewport.height);
          expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
          await page.screenshot({ path: `${SHOTS}/tasks-${screen.name}-${size}-${theme}-${locale}.png`, fullPage: false });
        });
      }
    }
  }
}

for (const screen of SCREENS) {
  for (const theme of THEMES) {
    test(`TR_UI_03_axe_no_serious_violations_tasks_${screen.name}_${theme}`, async ({ page }) => {
      await openScreen(page, screen, { width: 1440, height: 900 }, theme, 'vi');
      const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
      const serious = results.violations.filter((violation) => violation.impact === 'serious' || violation.impact === 'critical');
      expect(serious.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.target.join(' ')).join(', ')}`)).toEqual([]);
    });
  }
}

test('CKP_046_claim_is_reached_and_sent_by_keyboard_alone_with_a_visible_focus', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page, meAn, 'en');
  await page.goto('./#/tasks?view=candidate');
  const claim = inspector(page).getByRole('button', { name: 'Claim' });
  await expect(claim).toBeVisible();
  for (let step = 0; step < 60; step += 1) {
    await page.keyboard.press('Tab');
    if (await claim.evaluate((element) => element === document.activeElement)) break;
  }
  await expect(claim).toBeFocused();
  expect(await claim.evaluate((element) => getComputedStyle(element).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Enter');
  await expect(page.getByText('You claimed Credit review. It is in My tasks.')).toBeVisible();
  expect(engine.requests.filter((request) => request.path.endsWith(':claim'))).toHaveLength(1);
});

test('CKP_048_reassign_is_done_by_keyboard_and_escape_closes_the_dialog_without_a_request', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const engine = await mockEngines(page, meSau, 'en');
  await page.goto(`./#/tasks?view=all&task=${QUEUE_TASKS.creditReview}`);
  const open = inspector(page).getByRole('button', { name: 'Reassign' });
  await open.focus();
  await page.keyboard.press('Enter');
  await expect(dialog(page)).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog(page)).toHaveCount(0);
  await expect(open).toBeFocused();
  await page.keyboard.press('Enter');
  await page.keyboard.type('hoa');
  await page.keyboard.press('Tab');
  await page.keyboard.type('Covering leave');
  await dialog(page).getByRole('button', { name: 'Reassign' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Credit review is now with hoa.')).toBeVisible();
  expect(engine.handOvers).toEqual([expect.objectContaining({ command: 'reassign', to: 'hoa', reason: 'Covering leave' })]);
});

/* Sign-in to Tasks: the example host's own sign-in page, filled from its template and messages, then the queue. */

const SIGN_IN = fileURLToPath(new URL('../../../examples/rule-engine-only/src/main/resources/', import.meta.url));

function properties(file: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const match = /^([^#=][^=]*)=(.*)$/.exec(line.trim());
    if (match?.[1] !== undefined && match[2] !== undefined) out[match[1].trim()] = match[2];
  }
  return out;
}

const escapeHtml = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

function signInPage(locale: Locale): string {
  const messages = properties(`${SIGN_IN}sign-in/messages${locale === 'vi' ? '_vi' : ''}.properties`);
  const service = locale === 'vi' ? 'Khởi tạo khoản vay' : 'Loan origination';
  const links = LOCALES.map((l) => `<a href="/login?lang=${l}" lang="${l}" data-lang="${l}" hreflang="${l}"${l === locale ? ' aria-current="true"' : ''} title="${l === 'vi' ? 'Tiếng Việt' : 'English'}">${l.toUpperCase()}</a>`).join('');
  const users = [['an', locale === 'vi' ? 'Chuyên viên tín dụng' : 'Credit officer'], ['sau', locale === 'vi' ? 'Giám sát vận hành' : 'Operations supervisor']] as const;
  const demo = `<aside class="demo" aria-labelledby="demo-body"><p class="demo__head"><span class="tag tag--demo">${escapeHtml(messages['demoTag'] ?? '')}</span><span id="demo-body">${escapeHtml(messages['demoBody'] ?? '')}</span></p><ul class="demo__users">${users
    .map(([user, role]) => `<li><button type="button" class="demo__user" data-username="${user}" aria-label="${escapeHtml((messages['demoPick'] ?? '').replace('{user}', user).replace('{role}', role))}"><span class="demo__name">${user}</span><span class="demo__role">${escapeHtml(role)}</span></button></li>`)
    .join('')}</ul></aside>`;
  const markup: Record<string, string> = { environmentTag: '', languageLinks: links, alert: '', usernameInvalid: '', passwordInvalid: '', demo };
  const text: Record<string, string> = { ...messages, lang: locale, assets: '/sign-in-assets', action: '/login', csrfParameter: '_csrf', csrfToken: 'demo', service, subtitle: (messages['subtitle'] ?? '').replace('{service}', service) };
  return readFileSync(`${SIGN_IN}sign-in/sign-in.html`, 'utf8')
    .replace(/<!--[\s\S]*?-->\n/, '')
    .replace(/\{\{\{(\w+)\}\}\}/g, (_, key: string) => markup[key] ?? '')
    .replace(/\{\{(\w+)\}\}/g, (_, key: string) => escapeHtml(text[key] ?? ''));
}

for (const viewport of VIEWPORTS) {
  for (const theme of THEMES) {
    for (const locale of LOCALES) {
      const size = `${String(viewport.width)}x${String(viewport.height)}`;
      test(`TR_UI_03_journey_sign_in_to_tasks_${size}_${theme}_${locale}`, async ({ page }) => {
        await page.setViewportSize(viewport);
        await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
        await rememberLocale(page, locale);
        await mockEngines(page, meAn, locale);
        await page.route('**/sign-in-assets/*', async (route) => {
          const file = new URL(route.request().url()).pathname.split('/').pop() ?? '';
          await route.fulfill({ contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript', body: readFileSync(`${SIGN_IN}static/sign-in/${file}`, 'utf8') });
        });
        await page.route('**/login*', async (route) => {
          if (route.request().method() === 'POST') await route.fulfill({ status: 302, headers: { Location: '/#/tasks' }, body: '' });
          else await route.fulfill({ contentType: 'text/html; charset=utf-8', body: signInPage(locale) });
        });
        await page.goto('./login');
        const submit = page.getByRole('button', { name: T.signIn[locale], exact: true });
        await expectAboveFold(submit, viewport.height);
        await page.getByRole('button', { name: /^(Sign in as|Đăng nhập với) an/ }).click();
        await page.getByLabel(locale === 'vi' ? 'Mật khẩu' : 'Password').fill('demo-password');
        await page.screenshot({ path: `${SHOTS}/tasks-journey-1-sign-in-${size}-${theme}-${locale}.png`, fullPage: false });
        await submit.click();
        await expect(page.getByRole('heading', { level: 1, name: T.title[locale] })).toBeVisible();
        await expect(page.locator('.skeleton')).toHaveCount(0);
        await expect(page.getByTestId('nav-rail').getByRole('link', { name: T.title[locale], exact: true })).toHaveAttribute('aria-current', 'page');
        await expectAboveFold(inspector(page).locator('.btn--primary').first(), viewport.height);
        await page.screenshot({ path: `${SHOTS}/tasks-journey-2-tasks-${size}-${theme}-${locale}.png`, fullPage: false });
      });
    }
  }
}
